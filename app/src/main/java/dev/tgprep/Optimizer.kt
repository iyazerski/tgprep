package dev.tgprep

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class Transfer { SDR, HLG, PQ }

object Log {
    fun w(msg: String) = android.util.Log.w("TgPrep", msg)
    fun i(msg: String) = android.util.Log.i("TgPrep", msg)
}

/** What the container says about the source video. */
data class SourceInfo(
    val mime: String,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val fps: Float,
    val durationUs: Long,
    val bitrate: Int,
    val transfer: Transfer?,
    val standard: Int,
    val fullRange: Boolean,
    val peakNits: Float,
    val dvProfile: Int?,
    val dvCompatId: Int?,
    val audioMime: String?,
) {
    val isHdr get() = transfer == Transfer.HLG || transfer == Transfer.PQ

    fun describe(): String = buildString {
        append("${width}x$height")
        if (rotation != 0) append(" rot $rotation°")
        append(" · ${"%.2f".format(fps)} fps · ${durationUs / 1_000_000.0}s")
        append("\n").append(mime.removePrefix("video/"))
        dvProfile?.let { append(" profile $it") }
        dvCompatId?.let { append(".$it") }
        append(" · ").append(transfer?.name ?: "unknown transfer")
        if (standard == MediaFormat.COLOR_STANDARD_BT2020) append(" BT.2020")
        if (bitrate > 0) append(" · ${bitrate / 1000} kbps")
        append("\naudio: ").append(audioMime?.removePrefix("audio/") ?: "none")
    }
}

/** Output decisions derived from the source. */
data class Plan(val width: Int, val height: Int, val fps: Int, val frameIntervalUs: Long, val bitrate: Int)

class Optimizer(private val context: Context, private val source: Uri) {

    @Volatile var cancelled = false

    fun probe(): SourceInfo {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, source, null)
            val v = findTrack(extractor, "video/") ?: error("No video track")
            val a = findTrack(extractor, "audio/")
            val f = extractor.getTrackFormat(v)
            val retriever = MediaMetadataRetriever()
            val (mmrRotation, mmrBitrate) = try {
                retriever.setDataSource(context, source)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() to
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()
            } finally {
                retriever.release()
            }
            val isDv = f.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION
            // Android's MP4 extractor exposes the dvcC/dvvC box of Dolby Vision tracks as csd-2.
            val dv = if (isDv) f.getByteBuffer("csd-2")?.let(::parseDolbyVisionConfig) else null
            val dvProfile = if (isDv) dv?.first ?: f.intOrNull(MediaFormat.KEY_PROFILE)?.let(::dolbyVisionProfileNumber) else null
            return SourceInfo(
                mime = f.getString(MediaFormat.KEY_MIME)!!,
                width = f.getInteger(MediaFormat.KEY_WIDTH),
                height = f.getInteger(MediaFormat.KEY_HEIGHT),
                rotation = f.intOrNull(MediaFormat.KEY_ROTATION) ?: mmrRotation ?: 0,
                fps = f.numberOrNull(MediaFormat.KEY_FRAME_RATE) ?: estimateFps(extractor, v),
                durationUs = f.longOrNull(MediaFormat.KEY_DURATION) ?: 0,
                bitrate = f.intOrNull(MediaFormat.KEY_BIT_RATE) ?: mmrBitrate ?: 0,
                transfer = transferOf(f.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)) ?: dv?.second?.let(::transferOfDvCompat),
                standard = f.intOrNull(MediaFormat.KEY_COLOR_STANDARD) ?: 0,
                fullRange = f.intOrNull(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL,
                peakNits = f.getByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO)?.let(::parsePeakNits) ?: 1000f,
                dvProfile = dvProfile,
                dvCompatId = dv?.second,
                audioMime = a?.let { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) },
            )
        } finally {
            extractor.release()
        }
    }

    fun plan(info: SourceInfo): Plan {
        val (dispW, dispH) = if (info.rotation % 180 != 0) info.height to info.width else info.width to info.height
        // Telegram sends the file untouched only if its long side is <= 1920 (top quality preset).
        val scale = min(1f, min(MAX_LONG / max(dispW, dispH).toFloat(), MAX_SHORT / min(dispW, dispH).toFloat()))
        val w = even(dispW * scale)
        val h = even(dispH * scale)
        // Cap frame rate at 60: 120 fps footage would double the size for little visible benefit.
        val srcFps = if (info.fps > 1f) info.fps else 30f
        val fps = min(srcFps, 60f)
        val intervalUs = if (srcFps > 61f) (1_000_000 / 60f).toLong() else 0L
        // Bits per pixel tuned for hardware H.264: ~11 Mbps at 1080p30, ~16 Mbps at 1080p60.
        val fpsWeight = if (fps <= 30f) fps else 30f * sqrt(fps / 30f)
        var bitrate = (w.toLong() * h * fpsWeight * BITS_PER_PIXEL).toInt().coerceAtLeast(1_500_000)
        // Never inflate an already-H.264 source.
        if (info.mime == MediaFormat.MIMETYPE_VIDEO_AVC && info.bitrate > 0) bitrate = min(bitrate, info.bitrate)
        return Plan(w, h, fps.roundToInt(), intervalUs, bitrate)
    }

    /** Transcode [source] into [output]. Returns a human-readable report. */
    fun run(info: SourceInfo, plan: Plan, output: File, onProgress: (Float) -> Unit): String {
        // Some decoders accept the configuration but fail on the first samples (e.g. 10-bit HEVC on
        // weak decoders). Retry with the next candidate when nothing has been produced yet.
        val skip = mutableSetOf<String>()
        var firstFailure: Throwable? = null
        while (true) {
            try {
                return attempt(info, plan, output, onProgress, skip)
            } catch (e: DecoderStartFailure) {
                Log.w("Decoder ${e.decoder} failed before the first frame: ${e.cause}")
                if (firstFailure == null) firstFailure = e.cause
                if (!skip.add(e.decoder) || skip.size > 4) throw allDecodersFailed(info, skip, firstFailure)
            } catch (e: NoDecoderLeft) {
                if (skip.isEmpty()) throw e
                throw allDecodersFailed(info, skip, firstFailure)
            }
        }
    }

    private fun allDecodersFailed(info: SourceInfo, tried: Set<String>, cause: Throwable?): Exception =
        IllegalStateException(
            "No decoder on this device could decode ${info.mime}${if (info.isHdr) " HDR" else ""} (tried ${tried.joinToString()})",
            cause,
        )

    private class DecoderStartFailure(val decoder: String, cause: Throwable) : Exception(cause)
    private class NoDecoderLeft(mime: String, cause: Throwable?) : Exception("No decoder for $mime", cause)

    private fun attempt(info: SourceInfo, plan: Plan, output: File, onProgress: (Float) -> Unit, skip: Set<String>): String {
        val started = System.currentTimeMillis()
        val extractor = MediaExtractor().apply { setDataSource(context, source, null) }
        val audioExtractor = MediaExtractor().apply { setDataSource(context, source, null) }
        var encoder: MediaCodec? = null
        var decoder: MediaCodec? = null
        var egl: EglCore? = null
        var input: FrameInput? = null
        var renderer: Renderer? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var ok = false
        var frames = 0
        try {
            val videoTrack = findTrack(extractor, "video/")!!
            extractor.selectTrack(videoTrack)
            val audioTrack = findTrack(audioExtractor, "audio/")
            audioTrack?.let { audioExtractor.selectTrack(it) }
            val audioFormat = audioTrack?.let { audioExtractor.getTrackFormat(it) }

            val (enc, encoderName) = createEncoder(plan)
            encoder = enc
            egl = EglCore(enc.createInputSurface())
            enc.start()
            input = FrameInput()

            val decoderFormat = extractor.getTrackFormat(videoTrack).apply {
                // With a Surface output, MediaCodec applies this rotation to the buffer transform itself.
                // The renderer rotates explicitly, so drop it to avoid rotating twice.
                removeKey(MediaFormat.KEY_ROTATION)
            }
            val (dec, decoderNote) = createDecoder(decoderFormat, info, input.surface, skip)
            decoder = dec
            dec.start()

            muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxVideo = -1
            var producedProfile = "?"
            var muxAudio = -1
            val audioBuffer = ByteBuffer.allocateDirect(audioFormat?.intOrNull(MediaFormat.KEY_MAX_INPUT_SIZE)?.coerceAtLeast(64 * 1024) ?: (1 shl 20))
            val audioInfo = MediaCodec.BufferInfo()
            var audioDone = audioTrack == null

            fun copyAudioUpTo(limitUs: Long) {
                while (!audioDone) {
                    if (audioExtractor.sampleTrackIndex < 0) { audioDone = true; return }
                    val t = audioExtractor.sampleTime
                    if (t > limitUs) return
                    if (t < 0) {
                        // Encoder priming samples before t=0 (edit-list offset); players skip them anyway.
                        audioExtractor.advance()
                        continue
                    }
                    audioInfo.size = audioExtractor.readSampleData(audioBuffer, 0)
                    audioInfo.offset = 0
                    audioInfo.presentationTimeUs = t
                    audioInfo.flags = if (audioExtractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    muxer.writeSampleData(muxAudio, audioBuffer, audioInfo)
                    audioExtractor.advance()
                }
            }

            val encInfo = MediaCodec.BufferInfo()
            val decInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var decoderDone = false
            var encoderDone = false
            var lastKeptUs = Long.MIN_VALUE
            var colorSetup: ColorSetup? = null
            var decoderOutFormat: MediaFormat? = null
            var lastProgress = 0L

            while (!encoderDone) {
                if (cancelled) error("Cancelled")

                if (!inputDone) {
                    val idx = dec.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) {
                        val buf = dec.getInputBuffer(idx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // Drain the encoder.
                while (true) {
                    val idx = enc.dequeueOutputBuffer(encInfo, if (decoderDone) TIMEOUT_US else 0)
                    if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        muxVideo = muxer.addTrack(enc.outputFormat)
                        producedProfile = enc.outputFormat.getByteBuffer("csd-0")?.let(::avcProfileName) ?: "?"
                        if (audioFormat != null) {
                            muxAudio = try {
                                muxer.addTrack(audioFormat)
                            } catch (e: Exception) {
                                throw IllegalStateException("Audio codec ${audioFormat.getString(MediaFormat.KEY_MIME)} can't be copied into MP4", e)
                            }
                        }
                        muxer.start()
                        muxerStarted = true
                        continue
                    }
                    if (idx < 0) continue
                    val buf = enc.getOutputBuffer(idx)!!
                    if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) encInfo.size = 0
                    if (encInfo.size > 0) {
                        check(muxerStarted) { "Encoder produced data before its format" }
                        buf.position(encInfo.offset).limit(encInfo.offset + encInfo.size)
                        muxer.writeSampleData(muxVideo, buf, encInfo)
                        copyAudioUpTo(encInfo.presentationTimeUs + 500_000)
                    }
                    enc.releaseOutputBuffer(idx, false)
                    if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        encoderDone = true
                        break
                    }
                }

                if (!decoderDone) {
                    val idx = dec.dequeueOutputBuffer(decInfo, TIMEOUT_US)
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        decoderOutFormat = dec.outputFormat
                    } else if (idx >= 0) {
                        val eos = decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val pts = decInfo.presentationTimeUs
                        val keep = decInfo.size > 0 &&
                            (plan.frameIntervalUs == 0L || lastKeptUs == Long.MIN_VALUE || pts - lastKeptUs >= plan.frameIntervalUs - 2_000)
                        dec.releaseOutputBuffer(idx, keep)
                        if (keep) {
                            if (renderer == null) {
                                colorSetup = resolveColor(info, decoderOutFormat ?: dec.outputFormat)
                                renderer = Renderer(egl.glesVersion, colorSetup, info.rotation, info.width, info.height, plan.width, plan.height)
                            }
                            input.awaitFrame()
                            renderer.draw(input)
                            egl.swap(pts)
                            lastKeptUs = pts
                            frames++
                            val now = System.currentTimeMillis()
                            if (info.durationUs > 0 && now - lastProgress > 200) {
                                lastProgress = now
                                onProgress((pts.toFloat() / info.durationUs).coerceIn(0f, 1f))
                            }
                        }
                        if (eos) {
                            enc.signalEndOfInputStream()
                            decoderDone = true
                        }
                    }
                }
            }
            check(frames > 0) { "No frames decoded" }
            copyAudioUpTo(Long.MAX_VALUE)
            muxer.stop()
            muxerStarted = false
            ok = true

            val seconds = (System.currentTimeMillis() - started) / 1000.0
            val c = colorSetup!!
            return buildString {
                append("Output: ${plan.width}x${plan.height} H.264 SDR BT.709 · ${plan.fps} fps · target ${plan.bitrate / 1000} kbps\n")
                append("Size: ${"%.1f".format(output.length() / 1e6)} MB · $frames frames in ${"%.1f".format(seconds)} s\n")
                append("Decoder: $decoderNote\n")
                append("Encoder: $encoderName → $producedProfile\n")
                append("Decoded as: ${c.transfer} · ${standardName(c.standard)} · ${if (c.fullRange) "full" else "limited"}")
                if (c.transfer == Transfer.PQ) append(" · peak ${c.peakNits.toInt()} nits")
                append("\nSampling: ${if (renderer!!.usesYuvSampling) "10-bit YUV (GLES3)" else "driver RGB (8-bit)"}")
            }
        } catch (e: Exception) {
            val name = decoder?.name
            throw if (frames == 0 && name != null && !cancelled) DecoderStartFailure(name, e) else e
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { renderer?.release() }
            runCatching { input?.release() }
            runCatching { egl?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            extractor.release()
            audioExtractor.release()
            if (!ok) output.delete()
        }
    }

    /**
     * Pick the transfer of the decoded frames. An HDR transfer reported by the decoder wins (a Dolby
     * Vision decoder may output something other than the container tag). A decoder "SDR" does not
     * override an HDR container tag, because backward-compatible HLG streams signal BT.2020 SDR in
     * the bitstream VUI and HLG only in the container/SEI.
     */
    private fun resolveColor(info: SourceInfo, decoderOut: MediaFormat): ColorSetup {
        val decoded = transferOf(decoderOut.intOrNull(MediaFormat.KEY_COLOR_TRANSFER))
        val transfer = when {
            decoded == Transfer.HLG || decoded == Transfer.PQ -> decoded
            info.isHdr -> info.transfer!!
            else -> Transfer.SDR
        }
        val hdr = transfer != Transfer.SDR
        // HLG and PQ always use BT.2020 primaries in practice.
        val standard = if (hdr) MediaFormat.COLOR_STANDARD_BT2020 else
            decoderOut.intOrNull(MediaFormat.KEY_COLOR_STANDARD)?.takeIf { it != 0 }
                ?: info.standard.takeIf { it != 0 }
                ?: MediaFormat.COLOR_STANDARD_BT709
        val range = decoderOut.intOrNull(MediaFormat.KEY_COLOR_RANGE)
        val fullRange = if (range != null && range != 0) range == MediaFormat.COLOR_RANGE_FULL else info.fullRange
        // Ignore implausible peak values; 1000 nits is the common mastering level for phone HDR.
        val peak = info.peakNits.takeIf { it in 400f..10000f } ?: 1000f
        return ColorSetup(transfer, standard, fullRange, if (hdr) 10 else 8, peak)
    }

    private fun createDecoder(format: MediaFormat, info: SourceInfo, surface: android.view.Surface, skip: Set<String>): Pair<MediaCodec, String> {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        if (info.mime == MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION) {
            // Profiles 8 and 9 carry a standard HEVC/AVC base layer (HLG or HDR10) plus RPU metadata.
            // Decoding the base layer with the plain decoder gives deterministic, container-signalled colors.
            val baseMime = when (info.dvProfile) {
                8 -> MediaFormat.MIMETYPE_VIDEO_HEVC
                9 -> MediaFormat.MIMETYPE_VIDEO_AVC
                else -> null
            }
            if (baseMime != null && format.containsKey("csd-0") && info.dvCompatId != 0) {
                val base = MediaFormat(format).apply {
                    setString(MediaFormat.KEY_MIME, baseMime)
                    removeKey(MediaFormat.KEY_PROFILE)
                    removeKey(MediaFormat.KEY_LEVEL)
                }
                try {
                    val (codec, name) = configureDecoder(list, base, surface, skip, info.isHdr)
                    return codec to "$name (Dolby Vision base layer)"
                } catch (e: Exception) {
                    Log.w("Base-layer decoder failed, falling back to Dolby Vision decoder: $e")
                }
            }
            // Ask the Dolby Vision decoder for HLG output, as Dolby recommends for transcoding on Android.
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_HLG)
        }
        return configureDecoder(list, format, surface, skip, info.isHdr)
    }

    /**
     * Configure the best decoder for [format]: the platform's pick first, then every decoder of the
     * MIME type, since advertised capabilities are often conservative. For 10-bit sources, decoders
     * that advertise a 10-bit profile go first; then hardware before software.
     */
    private fun configureDecoder(list: MediaCodecList, format: MediaFormat, surface: android.view.Surface, skip: Set<String>, tenBit: Boolean): Pair<MediaCodec, String> {
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val all = list.codecInfos.filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
        fun rank(c: MediaCodecInfo): Int {
            val tenBitOk = !tenBit || c.getCapabilitiesForType(mime).profileLevels.any { it.profile in TEN_BIT_PROFILES }
            return (if (tenBitOk) 0 else 2) + (if (c.isHardwareAccelerated && !c.isSoftwareOnly) 0 else 1)
        }
        val platformPick = list.findDecoderForFormat(format)
        val names = all.sortedWith(compareBy<MediaCodecInfo> { rank(it) }.thenBy { if (it.name == platformPick) 0 else 1 })
            .map { it.name } - skip
        var lastError: Exception? = null
        for (name in names) {
            val codec = MediaCodec.createByCodecName(name)
            try {
                codec.configure(format, surface, null, 0)
                return codec to name
            } catch (e: Exception) {
                codec.release()
                lastError = e
            }
        }
        throw NoDecoderLeft(mime, lastError)
    }

    private fun createEncoder(plan: Plan): Pair<MediaCodec, String> {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
            .filter { it.getCapabilitiesForType(mime).videoCapabilities?.isSizeSupported(plan.width, plan.height) == true }
            .sortedBy { if (it.isHardwareAccelerated && !it.isSoftwareOnly) 0 else 1 }
        check(candidates.isNotEmpty()) { "No H.264 encoder supports ${plan.width}x${plan.height}" }
        var lastError: Exception? = null
        for (codecInfo in candidates) {
            val caps = codecInfo.getCapabilitiesForType(mime)
            for (tuned in listOf(true, false)) {
                val format = MediaFormat.createVideoFormat(mime, plan.width, plan.height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, plan.bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, plan.fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                    // Tag output as SDR BT.709 limited range so every player decodes it the same way.
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                    setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                    if (tuned) {
                        if (caps.encoderCapabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) == true) {
                            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                        }
                        val level = avcLevel(plan)
                        val maxHigh = caps.profileLevels.filter { it.profile == CodecProfileLevel.AVCProfileHigh }.maxOfOrNull { it.level }
                        if (maxHigh != null && maxHigh >= level) {
                            setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.AVCProfileHigh)
                            setInteger(MediaFormat.KEY_LEVEL, level)
                        }
                    }
                }
                val codec = MediaCodec.createByCodecName(codecInfo.name)
                try {
                    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    return codec to "${codecInfo.name}${if (tuned) " (asked High, VBR)" else " (defaults)"}"
                } catch (e: Exception) {
                    codec.release()
                    lastError = e
                }
            }
        }
        throw IllegalStateException("Could not configure an H.264 encoder", lastError)
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val MAX_LONG = 1920
        const val MAX_SHORT = 1080
        const val BITS_PER_PIXEL = 0.18f

        val TEN_BIT_PROFILES = setOf(
            CodecProfileLevel.HEVCProfileMain10, CodecProfileLevel.HEVCProfileMain10HDR10, CodecProfileLevel.HEVCProfileMain10HDR10Plus,
            CodecProfileLevel.VP9Profile2, CodecProfileLevel.VP9Profile2HDR, CodecProfileLevel.VP9Profile2HDR10Plus,
            CodecProfileLevel.AV1ProfileMain10, CodecProfileLevel.AV1ProfileMain10HDR10, CodecProfileLevel.AV1ProfileMain10HDR10Plus,
        )

        fun even(v: Float) = (v / 2f).roundToInt() * 2

        /** Smallest H.264 level that fits the plan's macroblock rate and bitrate (High profile limits). */
        fun avcLevel(plan: Plan): Int {
            val mbps = ((plan.width + 15) / 16).toLong() * ((plan.height + 15) / 16) * plan.fps
            return when {
                mbps <= 245_760 && plan.bitrate <= 25_000_000 -> CodecProfileLevel.AVCLevel4
                mbps <= 522_240 && plan.bitrate <= 62_500_000 -> CodecProfileLevel.AVCLevel42
                else -> CodecProfileLevel.AVCLevel51
            }
        }

        fun findTrack(extractor: MediaExtractor, prefix: String): Int? =
            (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true
            }

        fun transferOf(code: Int?): Transfer? = when (code) {
            MediaFormat.COLOR_TRANSFER_HLG -> Transfer.HLG
            MediaFormat.COLOR_TRANSFER_ST2084 -> Transfer.PQ
            MediaFormat.COLOR_TRANSFER_SDR_VIDEO -> Transfer.SDR
            else -> null
        }

        /** Map a Dolby Vision base-layer compatibility id to its transfer (0 = not backward compatible). */
        fun transferOfDvCompat(id: Int): Transfer? = when (id) {
            1, 6 -> Transfer.PQ
            2 -> Transfer.SDR
            4 -> Transfer.HLG
            else -> null
        }

        fun dolbyVisionProfileNumber(codecProfile: Int): Int? = when (codecProfile) {
            CodecProfileLevel.DolbyVisionProfileDvheStn -> 5
            CodecProfileLevel.DolbyVisionProfileDvheDtb -> 7
            CodecProfileLevel.DolbyVisionProfileDvheSt -> 8
            CodecProfileLevel.DolbyVisionProfileDvavSe -> 9
            else -> null
        }

        /** Name the H.264 profile from the SPS in an Annex-B csd-0 buffer (profile_idc + constraint flags). */
        fun avcProfileName(csd: ByteBuffer): String {
            val b = csd.duplicate()
            for (i in b.position() until b.limit() - 6) {
                // Start code 00 00 01 followed by an SPS NAL header (type 7).
                if (b.get(i) == 0.toByte() && b.get(i + 1) == 0.toByte() && b.get(i + 2) == 1.toByte() &&
                    (b.get(i + 3).toInt() and 0x1F) == 7
                ) {
                    val idc = b.get(i + 4).toInt() and 0xFF
                    val constraintSet1 = (b.get(i + 5).toInt() and 0x40) != 0
                    return when (idc) {
                        66 -> if (constraintSet1) "Constrained Baseline" else "Baseline"
                        77 -> "Main"
                        100 -> "High"
                        else -> "profile_idc $idc"
                    }
                }
            }
            return "?"
        }

        /** Parse a dvcC/dvvC box payload into (profile, base-layer compatibility id). */
        fun parseDolbyVisionConfig(buf: ByteBuffer): Pair<Int, Int>? {
            val b = buf.duplicate().order(ByteOrder.BIG_ENDIAN)
            if (b.remaining() < 5) return null
            val p = b.position()
            val profile = (b.get(p + 2).toInt() and 0xFF) shr 1
            val compat = (b.get(p + 4).toInt() and 0xFF) shr 4
            return profile to compat
        }

        /** Read MaxCLL, else max mastering luminance, from CTA-861.3 static metadata. */
        fun parsePeakNits(buf: ByteBuffer): Float? {
            val b = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            if (b.remaining() < 25) return null
            val p = b.position()
            val maxMastering = b.getShort(p + 17).toInt() and 0xFFFF
            val maxCll = b.getShort(p + 21).toInt() and 0xFFFF
            return (if (maxCll > 0) maxCll else maxMastering).takeIf { it > 0 }?.toFloat()
        }

        fun estimateFps(extractor: MediaExtractor, track: Int): Float {
            extractor.selectTrack(track)
            val times = ArrayList<Long>()
            while (times.size < 120 && extractor.sampleTime >= 0) {
                times += extractor.sampleTime
                extractor.advance()
            }
            extractor.unselectTrack(track)
            if (times.size < 2) return 30f
            times.sort()
            val deltas = times.zipWithNext { a, b -> b - a }.filter { it > 0 }.sorted()
            return if (deltas.isEmpty()) 30f else 1_000_000f / deltas[deltas.size / 2]
        }

        fun standardName(s: Int) = when (s) {
            MediaFormat.COLOR_STANDARD_BT2020 -> "BT.2020"
            MediaFormat.COLOR_STANDARD_BT709 -> "BT.709"
            MediaFormat.COLOR_STANDARD_BT601_PAL, MediaFormat.COLOR_STANDARD_BT601_NTSC -> "BT.601"
            else -> "unknown"
        }

        fun MediaFormat.intOrNull(key: String): Int? = if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null
        fun MediaFormat.longOrNull(key: String): Long? = if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null
        fun MediaFormat.numberOrNull(key: String): Float? = if (!containsKey(key)) null else
            runCatching { getNumber(key)?.toFloat() }.getOrNull()
    }
}
