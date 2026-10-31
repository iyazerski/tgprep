package dev.tgprep

import java.io.File
import java.io.RandomAccessFile

/**
 * Small in-place edits of the metadata boxes MediaMuxer writes. MediaMuxer can set a location but
 * always stamps the current time as the creation date and has no API to change it.
 */
object Mp4Meta {
    /** Seconds from the MP4 epoch (1904-01-01) to the Unix epoch. */
    private const val MP4_EPOCH_OFFSET_S = 2_082_844_800L
    private val CONTAINERS = setOf("moov", "trak", "mdia", "udta")
    private val TIME_BOXES = setOf("mvhd", "tkhd", "mdhd")

    /** ISO 6709 location box that MediaMuxer.setLocation writes inside moov/udta. */
    private const val LOCATION_BOX = "\u00a9xyz"

    /** Set the creation and modification time of the movie, track, and media headers. */
    fun setCreationTime(file: File, epochMs: Long) {
        val mp4Time = epochMs / 1000 + MP4_EPOCH_OFFSET_S
        RandomAccessFile(file, "rw").use { f ->
            walk(f, 0, f.length()) { type, _, payload ->
                if (type in TIME_BOXES) {
                    f.seek(payload)
                    // Full box: 1 byte version, 3 bytes flags, then creation and modification time.
                    if (f.readUnsignedByte() == 1) {
                        f.seek(payload + 4); f.writeLong(mp4Time); f.writeLong(mp4Time)
                    } else {
                        f.seek(payload + 4); f.writeInt(mp4Time.toInt()); f.writeInt(mp4Time.toInt())
                    }
                }
            }
        }
    }

    /** Copy [source] to [target] with the location box turned into a 'free' box (same size, so offsets stay valid). */
    fun copyWithoutLocation(source: File, target: File) {
        source.copyTo(target, overwrite = true)
        RandomAccessFile(target, "rw").use { f ->
            walk(f, 0, f.length()) { type, box, _ ->
                if (type == LOCATION_BOX) {
                    f.seek(box + 4)
                    f.write("free".toByteArray(Charsets.US_ASCII))
                }
            }
        }
    }

    /** Visit every box in [start, end), descending into known containers. Calls [visit] with the type, box offset, and payload offset. */
    private fun walk(f: RandomAccessFile, start: Long, end: Long, visit: (String, Long, Long) -> Unit) {
        var pos = start
        while (pos + 8 <= end) {
            f.seek(pos)
            var size = f.readInt().toLong() and 0xFFFFFFFFL
            val typeBytes = ByteArray(4).also { f.readFully(it) }
            val type = String(typeBytes, Charsets.ISO_8859_1)
            var header = 8L
            when (size) {
                1L -> { size = f.readLong(); header = 16 }
                0L -> size = end - pos
            }
            if (size < header || pos + size > end) return
            visit(type, pos, pos + header)
            if (type in CONTAINERS) walk(f, pos + header, pos + size, visit)
            pos += size
        }
    }
}
