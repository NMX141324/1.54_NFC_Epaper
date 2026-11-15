package com.example.epapernfc

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.pow

enum class Dither(val title: String) {
    NONE("无抖动"), FLOYD_STEINBERG("Floyd–Steinberg"), ATKINSON("Atkinson"), BAYER("Bayer 4×4")
}

/** Screen codes match GDEH0154E01, not sequential palette indices. */
object EpaperCodec {
    const val SIZE = 240
    const val PIXEL_BYTES = 28800
    val codes = intArrayOf(0, 1, 2, 3, 5, 6)
    // Approximate panel colours shared with the existing firmware image tools.
    val colors = intArrayOf(0xff191c1d.toInt(), 0xfffaf8ed.toInt(), 0xfff4cc2a.toInt(),
        0xffc73430.toInt(), 0xff2e53ab.toInt(), 0xff318950.toInt())
    private fun linear(v: Int): Float {
        val s = v / 255.0
        return (if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)).toFloat()
    }
    private val lut = FloatArray(256) { linear(it) }
    private val palette = colors.map { c -> floatArrayOf(lut[(c ushr 16) and 255], lut[(c ushr 8) and 255], lut[c and 255]) }
    private val bayer = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

    fun quantize(pixels: IntArray, algorithm: Dither, strength: Float, check: () -> Unit = {}): ByteArray {
        require(pixels.size == SIZE * SIZE)
        require(strength in 0f..1f)
        val work = FloatArray(pixels.size * 3)
        for (i in pixels.indices) {
            val c = pixels[i]
            work[i * 3] = lut[(c ushr 16) and 255]
            work[i * 3 + 1] = lut[(c ushr 8) and 255]
            work[i * 3 + 2] = lut[c and 255]
        }
        val indices = IntArray(pixels.size)
        for (y in 0 until SIZE) {
            check()
            val direction = if (y % 2 == 0) 1 else -1
            for (step in 0 until SIZE) {
                val x = if (direction == 1) step else SIZE - 1 - step
                val offset = (y * SIZE + x) * 3
                val ordered = if (algorithm == Dither.BAYER) (bayer[(y % 4) * 4 + x % 4] - 7.5f) / 16f * 0.6f * strength else 0f
                val current = FloatArray(3) { c -> (work[offset + c] + ordered).coerceIn(palette[0][c], palette[1][c]) }
                var nearest = 0
                var best = Float.MAX_VALUE
                for (k in palette.indices) {
                    var distance = 0f
                    for (c in 0..2) { val d = current[c] - palette[k][c]; distance += d * d }
                    if (distance < best) { best = distance; nearest = k }
                }
                indices[y * SIZE + x] = codes[nearest]
                fun spread(dx: Int, dy: Int, weight: Float) {
                    val xx = x + dx * direction
                    val yy = y + dy
                    if (xx !in 0 until SIZE || yy !in 0 until SIZE) return
                    for (c in 0..2) work[(yy * SIZE + xx) * 3 + c] += (current[c] - palette[nearest][c]) * weight * strength
                }
                when (algorithm) {
                    Dither.FLOYD_STEINBERG -> {
                        spread(1, 0, 7f / 16); spread(-1, 1, 3f / 16)
                        spread(0, 1, 5f / 16); spread(1, 1, 1f / 16)
                    }
                    Dither.ATKINSON -> {
                        spread(1, 0, 1f / 8); spread(2, 0, 1f / 8); spread(-1, 1, 1f / 8)
                        spread(0, 1, 1f / 8); spread(1, 1, 1f / 8); spread(0, 2, 1f / 8)
                    }
                    else -> Unit
                }
            }
        }
        return ByteArray(PIXEL_BYTES) { i -> ((indices[i * 2] shl 4) or indices[i * 2 + 1]).toByte() }
    }

    fun preview(raw: ByteArray): IntArray {
        require(raw.size == PIXEL_BYTES)
        return IntArray(SIZE * SIZE) { i ->
            val code = if (i % 2 == 0) (raw[i / 2].toInt() and 255) ushr 4 else raw[i / 2].toInt() and 15
            val index = codes.indexOf(code)
            require(index >= 0) { "非法屏幕颜色编码：$code" }
            colors[index]
        }
    }

    fun crc(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

    fun file(raw: ByteArray, imageId: Int): ByteArray {
        preview(raw) // Validate before exporting or sending.
        val header = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
            .put(byteArrayOf(69, 54, 73, 77)).put(1.toByte()).put(1.toByte())
            .putShort(32.toShort()).putShort(SIZE.toShort()).putShort(SIZE.toShort())
            .putInt(PIXEL_BYTES).putInt(imageId).putInt(crc(raw).toInt()).putInt(0)
        header.putInt(crc(header.array().copyOf(28)).toInt())
        return header.array() + raw
    }
}

data class FtmSegment(val frames: List<ByteArray>, val endOffset: Int)

/** ST25FTM: explicit payload lengths, BE total length, alternating segment IDs, IEEE segment CRC. */
object FtmCodec {
    fun segments(data: ByteArray, maxPacket: Int, segmentSize: Int = 2048): List<FtmSegment> {
        require(data.isNotEmpty() && maxPacket in 16..256 && segmentSize > 0)
        val segments = mutableListOf<FtmSegment>()
        var offset = 0
        var id = 0
        while (offset < data.size) {
            val end = minOf(offset + segmentSize, data.size)
            val crc = EpaperCodec.crc(data.copyOfRange(offset, end))
            val frames = mutableListOf<ByteArray>()
            var firstInSegment = true
            while (offset < end) {
                val firstInTransfer = offset == 0
                val header = if (firstInTransfer) 6 else 2
                val take = minOf(end - offset, maxPacket - header - 4)
                val lastInSegment = offset + take == end
                val lastInTransfer = offset + take == data.size
                val position = if (firstInTransfer && lastInTransfer) 0 else if (firstInTransfer) 1 else if (lastInTransfer) 3 else 2
                val ack = (if (firstInSegment) 1 else 0) or (if (lastInSegment) 2 else 0)
                val frame = ByteBuffer.allocate((if (position == 1) 6 else 2) + take + if (lastInSegment) 4 else 0).order(ByteOrder.BIG_ENDIAN)
                frame.put((0x41 or (id shl 1) or (position shl 2) or (ack shl 4)).toByte())
                frame.put((take + if (lastInSegment) 4 else 0).toByte())
                if (position == 1) frame.putInt(data.size)
                frame.put(data, offset, take)
                if (lastInSegment) frame.putInt(crc.toInt())
                frames += frame.array()
                offset += take
                firstInSegment = false
            }
            segments += FtmSegment(frames, end)
            id = id xor 1
        }
        return segments
    }
}
