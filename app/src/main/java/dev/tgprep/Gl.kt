package dev.tgprep

import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.min

/** GLES 3 context bound to the encoder's input surface. */
class EglCore(encoderSurface: Surface) {
    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: EGLContext
    private val surface: EGLSurface

    init {
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val config = chooseConfig() ?: error("No recordable GLES 3 EGL config")
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
        )
        check(context != null && context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        surface = EGL14.eglCreateWindowSurface(display, config, encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(surface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}" }
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }
    }

    private fun chooseConfig(): EGLConfig? {
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        return if (EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) && count[0] > 0) configs[0] else null
    }

    /** Submit the rendered frame to the encoder with the given presentation time. */
    fun swap(presentationTimeUs: Long) {
        EGLExt.eglPresentationTimeANDROID(display, surface, presentationTimeUs * 1000)
        EGL14.eglSwapBuffers(display, surface)
    }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}

/** Color description of the decoded frames, as fed to the shader. */
data class ColorSetup(
    val transfer: Transfer,
    val standard: Int,
    val fullRange: Boolean,
    /** Content peak luminance in nits, used only for PQ sources. */
    val peakNits: Float,
)

/**
 * Byte textures holding the Y, U, and V planes of one decoded frame.
 *
 * Frames come from the decoder as CPU-visible YUV (8-bit YUV_420_888 or 10-bit P010), so the
 * YUV→RGB step runs in our shader with the exact matrix and range. Letting the GPU driver sample the
 * decoder's surface instead gave wrong colors on real devices: a green image with GL_EXT_YUV_target
 * and a pink cast with the driver's own conversion of 10-bit BT.2020 buffers.
 */
class YuvTextures {
    val ids = IntArray(3).also { GLES30.glGenTextures(3, it, 0) }
    /** Bits to shift right to get the code value (6 for MSB-aligned P010). */
    var shift = 0; private set
    var bytesPerSample = 1; private set
    /** Bytes between horizontally adjacent samples in each plane. */
    val pixelStrides = IntArray(3)
    var bitDepth = 8; private set
    var width = 0; private set
    var height = 0; private set
    var crop = android.graphics.Rect(); private set
    private val allocated = Array(3) { IntArray(3) } // width, height, internal format
    private var staging: ByteBuffer = ByteBuffer.allocateDirect(0)

    /** Copy [image]'s planes into the textures. The image can be closed afterwards. */
    fun upload(image: Image) {
        bytesPerSample = when (image.format) {
            ImageFormat.YUV_420_888 -> 1
            ImageFormat.YCBCR_P010 -> 2
            else -> error("Unsupported decoder image format ${image.format}")
        }
        bitDepth = if (bytesPerSample == 2) 10 else 8
        shift = if (bytesPerSample == 2) 6 else 0
        width = image.width
        height = image.height
        crop = image.cropRect
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)
        image.planes.forEachIndexed { i, plane ->
            val w = if (i == 0) image.width else (image.width + 1) / 2
            val h = if (i == 0) image.height else (image.height + 1) / 2
            val ps = plane.pixelStride
            // Each plane goes up as raw bytes in an RGBA8 texture (4 bytes per texel) and the shader picks
            // the bytes it needs. RGBA8 is the one format every GPU and the emulator handle reliably.
            check(ps in 1..4 && ps >= bytesPerSample && plane.rowStride % 4 == 0) {
                "Unsupported plane layout: pixelStride=$ps rowStride=${plane.rowStride}"
            }
            pixelStrides[i] = ps
            val internal = GLES30.GL_RGBA8
            val format = GLES30.GL_RGBA
            val type = GLES30.GL_UNSIGNED_BYTE
            // The texture spans the whole row stride, so rows upload without GL_UNPACK_ROW_LENGTH (which
            // the emulator's GL encoder mis-sizes). The shader only reads the visible w x h part.
            val texWidth = plane.rowStride / 4
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[i])
            val a = allocated[i]
            if (a[0] != texWidth || a[1] != h || a[2] != internal) {
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internal, texWidth, h, 0, format, type, null)
                // Exact texel values are needed to rebuild 16-bit samples; the shader interpolates itself.
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
                a[0] = texWidth; a[1] = h; a[2] = internal
            }
            // Copy into our own buffer first: GL must not read the decoder's memory directly (on the
            // emulator that deadlocks), and the plane usually ends right after the last sample, so the
            // last row is shorter than the stride and gets zero-padded here.
            val bytes = plane.rowStride * h
            if (staging.capacity() < bytes) staging = ByteBuffer.allocateDirect(bytes)
            staging.clear()
            staging.put(plane.buffer.duplicate().position(0) as ByteBuffer)
            while (staging.position() < bytes) staging.put(0)
            staging.flip()
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, texWidth, h, format, type, staging)
        }
        val err = GLES30.glGetError()
        check(err == GLES30.GL_NO_ERROR) { "Texture upload failed: GL error 0x${Integer.toHexString(err)}" }
    }

    fun release() = GLES30.glDeleteTextures(3, ids, 0)
}

/** Draws one decoded frame to the encoder surface: YUV→RGB, rotation, downscaling, and HDR→SDR conversion. */
class Renderer(
    private val color: ColorSetup,
    rotation: Int,
    srcWidth: Int,
    srcHeight: Int,
    private val outWidth: Int,
    private val outHeight: Int,
) {
    private val program: Int = buildProgram(VERTEX, FRAGMENT_HEADER + FRAGMENT_COMMON).also { check(it != 0) { "Shader build failed" } }
    private val vertices: FloatBuffer
    private val tapX: Float
    private val tapY: Float

    init {
        // Quad corners (x, y) with the source texture coordinate that lands there after rotation.
        // Rotation is clockwise degrees needed to display the decoded frame upright. Texture t runs
        // bottom-up here; the vertex shader flips it to the image's top-down row order.
        val display = arrayOf(floatArrayOf(0f, 0f), floatArrayOf(1f, 0f), floatArrayOf(0f, 1f), floatArrayOf(1f, 1f))
        val pos = arrayOf(floatArrayOf(-1f, -1f), floatArrayOf(1f, -1f), floatArrayOf(-1f, 1f), floatArrayOf(1f, 1f))
        val data = FloatArray(16)
        for (i in 0 until 4) {
            val (s, t) = display[i].let { it[0] to it[1] }
            val src = when (rotation) {
                90 -> floatArrayOf(1f - t, s)
                180 -> floatArrayOf(1f - s, 1f - t)
                270 -> floatArrayOf(t, 1f - s)
                else -> floatArrayOf(s, t)
            }
            data[i * 4] = pos[i][0]; data[i * 4 + 1] = pos[i][1]; data[i * 4 + 2] = src[0]; data[i * 4 + 3] = src[1]
        }
        vertices = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data)

        // Downscaling: average a 2x2 grid of bilinear taps (exact box filter for 2:1) to avoid aliasing.
        val outAlongSrcX = if (rotation % 180 == 0) outWidth else outHeight
        val outAlongSrcY = if (rotation % 180 == 0) outHeight else outWidth
        val ratioX = srcWidth.toFloat() / outAlongSrcX
        val ratioY = srcHeight.toFloat() / outAlongSrcY
        val needTaps = ratioX >= 1.3f || ratioY >= 1.3f
        tapX = if (needTaps) ratioX / 4f / srcWidth else 0f
        tapY = if (needTaps) ratioY / 4f / srcHeight else 0f
    }

    fun draw(frame: YuvTextures) {
        GLES30.glViewport(0, 0, outWidth, outHeight)
        GLES30.glUseProgram(program)
        for (i in 0 until 3) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + i)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frame.ids[i])
        }
        GLES30.glUniform1i(loc("sY"), 0)
        GLES30.glUniform1i(loc("sU"), 1)
        GLES30.glUniform1i(loc("sV"), 2)
        val c = frame.crop
        GLES30.glUniform4f(loc("uCrop"), c.left.toFloat(), c.top.toFloat(), c.width().toFloat(), c.height().toFloat())
        GLES30.glUniform2i(loc("uLumaSize"), frame.width, frame.height)
        GLES30.glUniform2i(loc("uChromaSize"), (frame.width + 1) / 2, (frame.height + 1) / 2)
        GLES30.glUniform1f(loc("uShiftDiv"), (1 shl frame.shift).toFloat())
        GLES30.glUniform1i(loc("uBytes"), frame.bytesPerSample)
        GLES30.glUniform3i(loc("uPixelStride"), frame.pixelStrides[0], frame.pixelStrides[1], frame.pixelStrides[2])
        val (matrix, offset) = yuvToRgb(color.standard, color.fullRange, frame.bitDepth)
        GLES30.glUniformMatrix3fv(loc("uYuvToRgb"), 1, false, matrix, 0)
        GLES30.glUniform3f(loc("uYuvOffset"), offset[0], offset[1], offset[2])
        GLES30.glUniform1f(loc("uMaxCode"), ((1 shl frame.bitDepth) - 1).toFloat())
        GLES30.glUniform1i(loc("uTransfer"), color.transfer.ordinal)
        GLES30.glUniform1f(loc("uSrcPeak"), color.peakNits)
        GLES30.glUniform2f(loc("uTap"), tapX, tapY)
        val aPos = GLES30.glGetAttribLocation(program, "aPos")
        val aTex = GLES30.glGetAttribLocation(program, "aTex")
        vertices.position(0)
        GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 16, vertices)
        GLES30.glEnableVertexAttribArray(aPos)
        vertices.position(2)
        GLES30.glVertexAttribPointer(aTex, 2, GLES30.GL_FLOAT, false, 16, vertices)
        GLES30.glEnableVertexAttribArray(aTex)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        val err = GLES30.glGetError()
        check(err == GLES30.GL_NO_ERROR) { "GL error 0x${Integer.toHexString(err)}" }
    }

    fun release() = GLES30.glDeleteProgram(program)

    private fun loc(name: String) = GLES30.glGetUniformLocation(program, name)

    private fun buildProgram(vertex: String, fragment: String): Int {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertex)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragment)
        if (vs == 0 || fs == 0) return 0
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        val status = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, status, 0)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        if (status[0] == 0) {
            Log.w("link failed: ${GLES30.glGetProgramInfoLog(prog)}")
            GLES30.glDeleteProgram(prog)
            return 0
        }
        return prog
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.w("compile failed: ${GLES30.glGetShaderInfoLog(shader)}")
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    companion object {
        /**
         * Return a column-major YUV→RGB matrix and the YUV offset for the given color standard and range.
         *
         * Values are for normalized samples (code / (2^bits - 1)), as produced by the shader's fetch.
         */
        fun yuvToRgb(standard: Int, fullRange: Boolean, bitDepth: Int): Pair<FloatArray, FloatArray> {
            val (kr, kb) = when (standard) {
                MediaFormat.COLOR_STANDARD_BT2020 -> 0.2627 to 0.0593
                MediaFormat.COLOR_STANDARD_BT601_PAL, MediaFormat.COLOR_STANDARD_BT601_NTSC -> 0.299 to 0.114
                else -> 0.2126 to 0.0722
            }
            val kg = 1 - kr - kb
            val max = ((1 shl bitDepth) - 1).toDouble()
            val scale = (1 shl (bitDepth - 8)).toDouble()
            val yOff = if (fullRange) 0.0 else 16 * scale / max
            val yScale = if (fullRange) 1.0 else max / (219 * scale)
            val cScale = if (fullRange) 1.0 else max / (224 * scale)
            val cOff = 128 * scale / max
            val m = floatArrayOf(
                // column 0: Y
                yScale.toFloat(), yScale.toFloat(), yScale.toFloat(),
                // column 1: Cb
                0f, (-2 * kb * (1 - kb) / kg * cScale).toFloat(), (2 * (1 - kb) * cScale).toFloat(),
                // column 2: Cr
                (2 * (1 - kr) * cScale).toFloat(), (-2 * kr * (1 - kr) / kg * cScale).toFloat(), 0f,
            )
            return m to floatArrayOf(yOff.toFloat(), cOff.toFloat(), cOff.toFloat())
        }

        private const val VERTEX = """#version 300 es
in vec4 aPos;
in vec4 aTex;
out vec2 vTex;
void main() {
    gl_Position = aPos;
    vTex = vec2(aTex.x, 1.0 - aTex.y);
}
"""

        /**
         * Fetch Y'CbCr from the plane textures and convert to R'G'B'. Positions are in luma pixels
         * (pixel centers at i + 0.5). 4:2:0 chroma uses the default MPEG-2/H.264/HEVC siting: co-sited
         * with even luma columns horizontally, halfway between luma rows vertically.
         */
        private const val FRAGMENT_HEADER = """#version 300 es
precision highp float;
uniform sampler2D sY;
uniform sampler2D sU;
uniform sampler2D sV;
uniform vec4 uCrop;         // visible rect in luma pixels: x, y, w, h
uniform ivec2 uLumaSize;
uniform ivec2 uChromaSize;
uniform float uShiftDiv;    // 64 for MSB-aligned P010, else 1
uniform int uBytes;         // bytes per sample: 1, or 2 for little-endian 16-bit
uniform ivec3 uPixelStride; // bytes between adjacent samples in the Y, U, V planes
uniform float uMaxCode;
uniform mat3 uYuvToRgb;
uniform vec3 uYuvOffset;
in vec2 vTex;
out vec4 fragColor;
#define FRAG_COLOR fragColor

float byteAt(sampler2D s, int x, int y) {
    vec4 t = texelFetch(s, ivec2(x >> 2, y), 0);
    return floor(t[x & 3] * 255.0 + 0.5);
}

// Code value of the sample at p (in samples, clamped to the visible plane), normalized to 0..1.
float fetch(sampler2D s, int stride, ivec2 p, ivec2 size) {
    p = clamp(p, ivec2(0), size - 1);
    int x = p.x * stride;
    float v = byteAt(s, x, p.y);
    if (uBytes == 2) v += 256.0 * byteAt(s, x + 1, p.y);
    return floor(v / uShiftDiv) / uMaxCode;
}

float bilinear(sampler2D s, int stride, vec2 pos, ivec2 size) {
    vec2 q = pos - 0.5;
    vec2 f = fract(q);
    ivec2 i = ivec2(floor(q));
    float a = fetch(s, stride, i, size);
    float b = fetch(s, stride, i + ivec2(1, 0), size);
    float c = fetch(s, stride, i + ivec2(0, 1), size);
    float d = fetch(s, stride, i + ivec2(1, 1), size);
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

vec3 signalAt(vec2 uv) {
    vec2 p = uCrop.xy + clamp(uv, 0.0, 1.0) * uCrop.zw;
    vec2 pc = vec2(p.x * 0.5 + 0.25, p.y * 0.5);
    vec3 yuv = vec3(
        bilinear(sY, uPixelStride.x, p, uLumaSize),
        bilinear(sU, uPixelStride.y, pc, uChromaSize),
        bilinear(sV, uPixelStride.z, pc, uChromaSize));
    return clamp(uYuvToRgb * (yuv - uYuvOffset), 0.0, 1.0);
}
"""

        /**
         * HDR→SDR conversion, following the approach used by mpv/libplacebo:
         * 1. Decode HLG/PQ to display light in nits (HLG rendered for a 1000-nit display, system gamma 1.2).
         * 2. Compress luminance with the ITU-R BT.2390 EETF to a 203-nit target (BT.2408 HDR reference
         *    white), so diffuse white lands near 90% SDR signal and highlights roll off instead of clipping.
         * 3. Convert BT.2020 to BT.709 primaries and bring out-of-gamut colors back by desaturating
         *    toward the pixel's luminance (keeps hue and brightness).
         * 4. Encode with the BT.1886 inverse EOTF (gamma 2.4) and add 0.5 LSB of static dither to hide
         *    banding in the 8-bit output.
         */
        private const val FRAGMENT_COMMON = """
uniform int uTransfer;   // 0 = SDR, 1 = HLG, 2 = PQ
uniform float uSrcPeak;  // PQ content peak, nits
uniform vec2 uTap;       // 2x2 tap offset in texture units, 0 = single tap

const float SDR_WHITE = 203.0;
const vec3 K2020 = vec3(0.2627, 0.6780, 0.0593);
const vec3 K709 = vec3(0.2126, 0.7152, 0.0722);
const mat3 BT2020_TO_BT709 = mat3(
    1.660491, -0.124550, -0.018151,
    -0.587641, 1.132900, -0.100579,
    -0.072850, -0.008349, 1.118730);

float hlgInverseOetf(float e) {
    return e <= 0.5 ? e * e / 3.0 : (exp((e - 0.55991073) / 0.17883277) + 0.28466892) / 12.0;
}

float pqToNits(float e) {
    float p = pow(clamp(e, 0.0, 1.0), 1.0 / 78.84375);
    return 10000.0 * pow(max(p - 0.8359375, 0.0) / (18.8515625 - 18.6875 * p), 1.0 / 0.1593017578125);
}

float nitsToPq(float n) {
    float y = pow(clamp(n / 10000.0, 0.0, 1.0), 0.1593017578125);
    return pow((0.8359375 + 18.8515625 * y) / (1.0 + 18.6875 * y), 78.84375);
}

// ITU-R BT.2390 EETF (black level 0), applied in the PQ domain.
float eetf(float nits, float srcPeak) {
    float srcPq = nitsToPq(srcPeak);
    float maxLum = nitsToPq(SDR_WHITE) / srcPq;
    if (maxLum >= 1.0) return min(nits, SDR_WHITE);
    float e = min(nitsToPq(nits) / srcPq, 1.0);
    float ks = 1.5 * maxLum - 0.5;
    if (e > ks) {
        float t = (e - ks) / (1.0 - ks);
        float t2 = t * t;
        float t3 = t2 * t;
        e = (2.0 * t3 - 3.0 * t2 + 1.0) * ks + (t3 - 2.0 * t2 + t) * (1.0 - ks) + (-2.0 * t3 + 3.0 * t2) * maxLum;
    }
    return pqToNits(e * srcPq);
}

// HDR signal (BT.2020 R'G'B') -> linear BT.709 relative to SDR white, in [0, 1].
vec3 hdrToSdrLinear(vec3 sig) {
    vec3 nits;
    float peak;
    if (uTransfer == 1) {
        vec3 scene = vec3(hlgInverseOetf(sig.r), hlgInverseOetf(sig.g), hlgInverseOetf(sig.b));
        nits = 1000.0 * pow(max(dot(K2020, scene), 1e-6), 0.2) * scene;
        peak = 1000.0;
    } else {
        nits = vec3(pqToNits(sig.r), pqToNits(sig.g), pqToNits(sig.b));
        peak = uSrcPeak;
    }
    float y = dot(K2020, nits);
    if (y > 0.0) nits *= eetf(y, peak) / y;
    vec3 c = BT2020_TO_BT709 * (nits / SDR_WHITE);
    float l = clamp(dot(K709, c), 0.0, 1.0);
    float mn = min(c.r, min(c.g, c.b));
    if (mn < 0.0) c = l + (c - l) * (l / (l - mn));
    float mx = max(c.r, max(c.g, c.b));
    if (mx > 1.0) c = l + (c - l) * ((1.0 - l) / (mx - l));
    return clamp(c, 0.0, 1.0);
}

vec3 pixel(vec2 uv) {
    vec3 s = signalAt(uv);
    return uTransfer == 0 ? s : hdrToSdrLinear(s);
}

void main() {
    vec3 c;
    if (uTap.x > 0.0 || uTap.y > 0.0) {
        c = 0.25 * (pixel(vTex + vec2(-uTap.x, -uTap.y)) + pixel(vTex + vec2(uTap.x, -uTap.y))
                  + pixel(vTex + vec2(-uTap.x, uTap.y)) + pixel(vTex + uTap));
    } else {
        c = pixel(vTex);
    }
    if (uTransfer != 0) {
        c = pow(c, vec3(1.0 / 2.4));
        float n = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
        c = clamp(c + (n - 0.5) / 255.0, 0.0, 1.0);
    }
    FRAG_COLOR = vec4(c, 1.0);
}
"""
    }
}
