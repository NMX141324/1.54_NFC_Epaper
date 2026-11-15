package com.example.epapernfc

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

class EpaperCodecTest {
    @Test fun headerAndPackingMatchFirmwareContract() {
        val pixels = IntArray(240 * 240) { EpaperCodec.colors[(it % 240) / 40] }
        val raw = EpaperCodec.quantize(pixels, Dither.NONE, 1f)
        assertEquals(28800, raw.size)
        assertEquals(0x00, raw[0].toInt() and 255)
        assertEquals(0x11, raw[20].toInt() and 255)
        assertEquals(0x22, raw[40].toInt() and 255)
        assertEquals(0x33, raw[60].toInt() and 255)
        assertEquals(0x55, raw[80].toInt() and 255)
        assertEquals(0x66, raw[100].toInt() and 255)
        assertArrayEquals(pixels, EpaperCodec.preview(raw))
        val file = EpaperCodec.file(raw, 0x12345678)
        val header = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("E6IM", String(file.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals(1, file[4].toInt()); assertEquals(1, file[5].toInt())
        assertEquals(32, header.getShort(6).toInt())
        assertEquals(240, header.getShort(8).toInt()); assertEquals(240, header.getShort(10).toInt())
        assertEquals(28800, header.getInt(12)); assertEquals(0x12345678, header.getInt(16))
        assertEquals(0, header.getInt(24)); assertEquals(28832, file.size)
        assertEquals(crc(file.copyOfRange(32, file.size)), header.getInt(20).toLong() and 0xffffffffL)
        assertEquals(crc(file.copyOfRange(0, 28)), header.getInt(28).toLong() and 0xffffffffL)
    }

    @Test fun everyAlgorithmProducesOnlyPanelCodesAndStableFlatColors() {
        for (algorithm in Dither.entries) {
            for (color in EpaperCodec.colors) {
                val pixels = IntArray(57600) { color }
                assertArrayEquals(pixels, EpaperCodec.preview(EpaperCodec.quantize(pixels, algorithm, 0f)))
            }
            val gradient = IntArray(57600) { i ->
                val x = i % 240
                val v = x * 255 / 239
                (0xff000000L or (v.toLong() shl 16) or ((255 - v).toLong() shl 8) or v.toLong()).toInt()
            }
            val raw = EpaperCodec.quantize(gradient, algorithm, 1f)
            assertEquals(57600, EpaperCodec.preview(raw).size)
            assertTrue(raw.all { (it.toInt() and 15) in EpaperCodec.codes && ((it.toInt() and 255) ushr 4) in EpaperCodec.codes })
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun forbiddenPanelCodeFourIsRejected() { EpaperCodec.preview(ByteArray(28800) { 0x44 }) }

    @Test fun crcUsesIeeeProfile() { assertEquals(0xcbf43926L, EpaperCodec.crc("123456789".toByteArray())) }

    /** Independent wire decoder, checking field lengths, sequence, segment CRC and total length. */
    private fun decode(segments: List<FtmSegment>, packetLimit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        var expectedId = 0
        var declared = -1
        for ((segmentIndex, segment) in segments.withIndex()) {
            val segmentBytes = ByteArrayOutputStream()
            for ((frameIndex, frame) in segment.frames.withIndex()) {
                assertTrue(frame.size <= packetLimit)
                val control = frame[0].toInt() and 255
                assertEquals(0, control and 128)
                assertEquals(1, control and 1)
                assertEquals(expectedId, (control ushr 1) and 1)
                val pos = (control ushr 2) and 3
                val flags = (control ushr 4) and 3
                assertEquals(frameIndex == 0, flags and 1 != 0)
                assertEquals(frameIndex == segment.frames.lastIndex, flags and 2 != 0)
                assertTrue(control and 64 != 0)
                val count = frame[1].toInt() and 255
                val start = if (pos == 1) 6 else 2
                assertEquals(frame.size, start + count)
                if (segmentIndex == 0 && frameIndex == 0) {
                    assertTrue(pos == 0 || pos == 1)
                    declared = if (pos == 1) ByteBuffer.wrap(frame, 2, 4).int else count - 4
                } else assertTrue(pos == 2 || pos == 3)
                val last = flags and 2 != 0
                val bytes = frame.copyOfRange(start, frame.size - if (last) 4 else 0)
                segmentBytes.write(bytes); output.write(bytes)
                if (last) {
                    val transmittedCrc = ByteBuffer.wrap(frame, frame.size - 4, 4).int.toLong() and 0xffffffffL
                    assertEquals(crc(segmentBytes.toByteArray()), transmittedCrc)
                }
                val finalFrame = segmentIndex == segments.lastIndex && frameIndex == segment.frames.lastIndex
                assertEquals(finalFrame, pos == 0 || pos == 3)
            }
            assertEquals(output.size(), segment.endOffset)
            expectedId = expectedId xor 1
        }
        assertEquals(declared, output.size())
        return output.toByteArray()
    }

    @Test fun segmentedTransportWorksAtDifferentPhoneLimitsAndBoundarySizes() {
        for (limit in listOf(16, 64, 128, 240, 249, 256)) {
            for (length in listOf(1, 6, 10, 230, 234, 240, 2048, 2049, 28833)) {
                val payload = ByteArray(length) { (it * 37).toByte() }
                assertArrayEquals(payload, decode(FtmCodec.segments(payload, limit), limit))
            }
        }
    }

    private fun crc(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value
}
