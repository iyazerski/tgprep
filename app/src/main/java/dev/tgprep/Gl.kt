package dev.tgprep

import android.graphics.SurfaceTexture
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** EGL context bound to the encoder's input surface. Prefers GLES 3 (needed for 10-bit YUV sampling). */
class EglCore(encoderSurface: Surface) {
    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: EGLContext
    private val surface: EGLSurface
    val glesVersion: Int

    init {
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        var chosen: Pair<EGLConfig, EGLContext>? = null
        var gles = 0
        for (v in intArrayOf(3, 2)) {
            val config = chooseConfig(if (v == 3) EGLExt.EGL_OPENGL_ES3_BIT_KHR else EGL14.EGL_OPENGL_ES2_BIT) ?: continue
            val ctx = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, v, EGL14.EGL_NONE), 0,
            )
            if (ctx != null && ctx != EGL14.EGL_NO_CONTEXT) {
                chosen = config to ctx
                gles = v
                break
            }
        }
        val (config, ctx) = chosen ?: error("No usable EGL context")
        context = ctx
        glesVersion = gles
        surface = EGL14.eglCreateWindowSurface(display, config, encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(surface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}" }
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }
    }

    private fun chooseConfig(renderableType: Int): EGLConfig? {
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, renderableType,
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

/** External OES texture that receives decoded frames from MediaCodec. */
class FrameInput {
    val textureId: Int
    private val surfaceTexture: SurfaceTexture
    val surface: Surface
    val transform = FloatArray(16)
    private val thread = HandlerThread("frame-input").apply { start() }
    private val lock = Object()
    private var frameAvailable = false

    init {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        surfaceTexture = SurfaceTexture(textureId)
        // Callbacks go to a separate thread because the GL thread blocks while waiting for them.
        surfaceTexture.setOnFrameAvailableListener({
            synchronized(lock) {
                frameAvailable = true
                lock.notifyAll()
            }
        }, Handler(thread.looper))
        surface = Surface(surfaceTexture)
    }

    /** Wait for the frame just released by the decoder and latch it into the texture. */
    fun awaitFrame() {
        synchronized(lock) {
            val deadline = System.nanoTime() + 3_000_000_000L
            while (!frameAvailable) {
                val leftMs = (deadline - System.nanoTime()) / 1_000_000
                if (leftMs <= 0) error("Timed out waiting for a decoded frame")
                lock.wait(leftMs)
            }
            frameAvailable = false
        }
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(transform)
    }

    fun release() {
        surface.release()
        surfaceTexture.release()
        thread.quitSafely()
    }
}

/** Color description of the decoded frames, as fed to the shader. */
data class ColorSetup(
    val transfer: Transfer,
    val standard: Int,
    val fullRange: Boolean,
    val bitDepth: Int,
    /** Content peak luminance in nits, used only for PQ sources. */
    val peakNits: Float,
)

/**
 * Draws one decoded frame to the encoder surface: rotation, downscaling, and HDR→SDR conversion.
 *
 * With GLES 3 + GL_EXT_YUV_target the shader reads raw 10-bit YUV and applies the exact BT.2020
 * matrix itself. Otherwise it falls back to the driver's YUV→RGB conversion (8-bit precision).
 */
class Renderer(
    glesVersion: Int,
    private val color: ColorSetup,
    rotation: Int,
    srcWidth: Int,
    srcHeight: Int,
    private val outWidth: Int,
    private val outHeight: Int,
) {
    val usesYuvSampling: Boolean
    private val program: Int
    private val vertices: FloatBuffer
    private val tapX: Float
    private val tapY: Float

    init {
        val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
        var yuv = glesVersion >= 3 && extensions.contains("GL_EXT_YUV_target")
        var prog = if (yuv) buildProgram(VERTEX_300, FRAGMENT_YUV_HEADER + FRAGMENT_COMMON) else 0
        if (prog == 0) {
            yuv = false
            prog = buildProgram(VERTEX_100, FRAGMENT_RGB_HEADER + FRAGMENT_COMMON)
        }
        check(prog != 0) { "Shader build failed" }
        usesYuvSampling = yuv
        program = prog

        // Quad corners (x, y) with the source texture coordinate that lands there after rotation.
        // Rotation is clockwise degrees needed to display the decoded frame upright.
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

    fun draw(input: FrameInput) {
        GLES20.glViewport(0, 0, outWidth, outHeight)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, input.textureId)
        GLES20.glUniform1i(loc("sTex"), 0)
        GLES20.glUniformMatrix4fv(loc("uSt"), 1, false, input.transform, 0)
        GLES20.glUniform1i(loc("uTransfer"), color.transfer.ordinal)
        GLES20.glUniform1f(loc("uSrcPeak"), color.peakNits)
        GLES20.glUniform2f(loc("uTap"), tapX, tapY)
        if (usesYuvSampling) {
            val (matrix, offset) = yuvToRgb(color.standard, color.fullRange, color.bitDepth)
            GLES20.glUniformMatrix3fv(loc("uYuvToRgb"), 1, false, matrix, 0)
            GLES20.glUniform3f(loc("uYuvOffset"), offset[0], offset[1], offset[2])
        }
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val aTex = GLES20.glGetAttribLocation(program, "aTex")
        vertices.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(aPos)
        vertices.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        val err = GLES20.glGetError()
        check(err == GLES20.GL_NO_ERROR) { "GL error 0x${Integer.toHexString(err)}" }
    }

    fun release() = GLES20.glDeleteProgram(program)

    private fun loc(name: String) = GLES20.glGetUniformLocation(program, name)

    private fun buildProgram(vertex: String, fragment: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
        if (vs == 0 || fs == 0) return 0
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (status[0] == 0) {
            Log.w("link failed: ${GLES20.glGetProgramInfoLog(prog)}")
            GLES20.glDeleteProgram(prog)
            return 0
        }
        return prog
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.w("compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    companion object {
        /**
         * Return a column-major YUV→RGB matrix and the YUV offset for the given color standard and range.
         *
         * Values are for normalized samples (code / (2^bits - 1)), as returned by the YUV sampler.
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

        private const val VERTEX_300 = """#version 300 es
in vec4 aPos;
in vec4 aTex;
uniform mat4 uSt;
out vec2 vTex;
void main() {
    gl_Position = aPos;
    vTex = (uSt * aTex).xy;
}
"""

        private const val VERTEX_100 = """
attribute vec4 aPos;
attribute vec4 aTex;
uniform mat4 uSt;
varying vec2 vTex;
void main() {
    gl_Position = aPos;
    vTex = (uSt * aTex).xy;
}
"""

        private const val FRAGMENT_YUV_HEADER = """#version 300 es
#extension GL_OES_EGL_image_external : require
#extension GL_EXT_YUV_target : require
precision highp float;
uniform __samplerExternal2DY2YEXT sTex;
uniform mat3 uYuvToRgb;
uniform vec3 uYuvOffset;
in vec2 vTex;
out vec4 fragColor;
#define FRAG_COLOR fragColor
vec3 signalAt(vec2 uv) {
    vec3 yuv = texture(sTex, uv).xyz;
    return clamp(uYuvToRgb * (yuv - uYuvOffset), 0.0, 1.0);
}
"""

        private const val FRAGMENT_RGB_HEADER = """
#extension GL_OES_EGL_image_external : require
precision highp float;
uniform samplerExternalOES sTex;
varying vec2 vTex;
#define FRAG_COLOR gl_FragColor
vec3 signalAt(vec2 uv) {
    return texture2D(sTex, uv).rgb;
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
         *    banding from the 10-bit to 8-bit step.
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
