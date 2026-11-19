package com.example.epapernfc

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.ArrayDeque
import java.util.zip.CRC32

class St25ImageSessionTest {
    private val uid = byteArrayOf(8, 7, 6, 5, 4, 0x24, 2, 0xe0.toByte())
    private val file = ByteArray(28832) { (it * 13).toByte() }

    /** Separate chip/MCU model. A busy refusal has no side effects; successful
     * messages are decoded, CRC checked and assembled without FtmCodec. */
    private inner class Chip {
        var now = 0L
        var modeReadyAt = 0L
        var enabled = false
        var busyUntil = 0L
        var readyAt = 0L
        var hostReadDelay = 25L
        var enableRefusals = 2
        var writeRefusals = 0
        var consumedReplies = 0
        var handshakeWrites = 0
        var segments = 0
        var writes = 0
        var failAtSegment: Int? = null
        var timeout = false
        var dropMailbox = false
        var capability = true
        var refreshDelay = 35000L
        var completionDue: Long? = null
        var completionQueued = false
        var panelError = 0
        var mismatchImage = false
        var losePowerDuringRefresh = false
        var suppressCompletion = false
        val pending = ArrayDeque<ByteArray>()
        val assembled = ByteArrayOutputStream()
        var segment = ByteArrayOutputStream()
        var total = 0
        val refusedSegments = mutableSetOf<Int>()

        fun exchange(request: ByteArray): ByteArray {
            assertEquals(2, request[0].toInt() and 255)
            assertEquals(2, request[2].toInt() and 255)
            val code = request[1].toInt() and 255
            if (completionDue != null && now >= completionDue!! && !completionQueued && !suppressCompletion) {
                completionQueued = true
                val result = ByteArray(24)
                result[0] = 0x40;result[1] = 22;result[2] = 0x89.toByte()
                result[3] = if (panelError == 0) 0x90.toByte() else 0x91.toByte()
                file.copyInto(result, 4, 16, 24)
                if (mismatchImage) result[4] = (result[4].toInt() xor 1).toByte()
                for (b in 0..3) {
                    result[12 + b] = (refreshDelay ushr (8 * b)).toByte()
                    result[16 + b] = (panelError ushr (8 * b)).toByte()
                }
                result[23] = 8 // power-on reset flag, from boot diagnostics
                pending.add(result)
            }
            if (now < busyUntil) return byteArrayOf(1, 0x0f)
            if (code == 0xae && enableRefusals-- > 0) return byteArrayOf(1, 0x0f)
            if (code == 0xaa && handshakeWrites > 0 && segment.size() == 0 &&
                segments + 1 in setOf(4, 9, 10, 15) && refusedSegments.add(segments + 1)) {
                writeRefusals++
                return byteArrayOf(1, 0x0f)
            }
            if (code == 0xaa && failAtSegment == segments + 1 && handshakeWrites > 0) {
                throw IOException("RF reply lost after send")
            }
            return when (code) {
                0xa0 -> byteArrayOf(0, if (now >= modeReadyAt) 1 else 0)
                0xae -> {
                    enabled = request[4] == 1.toByte()
                    if (!enabled) pending.clear()
                    byteArrayOf(0)
                }
                0xad -> byteArrayOf(0, when {
                    !enabled || dropMailbox || (losePowerDuringRefresh && completionDue != null) -> 0
                    timeout -> 5
                    now < readyAt -> 5
                    pending.isNotEmpty() -> 0x43
                    else -> 0x41
                }.toByte())
                0xab -> byteArrayOf(0, (pending.first.size - 1).toByte())
                0xac -> {
                    assertTrue(now >= readyAt)
                    val reply = pending.removeFirst()
                    assertEquals(0, request[3].toInt())
                    assertEquals(reply.size - 1, request[4].toInt() and 255)
                    consumedReplies++
                    if (reply.size == 4 && reply[2] == 0x89.toByte() && reply[3] == 0x81.toByte()) {
                        completionDue = now + refreshDelay
                    }
                    busyUntil = now + 45 // MCU servicing RF_GET_MSG
                    readyAt = busyUntil
                    byteArrayOf(0) + reply
                }
                0xaa -> {
                    assertTrue(enabled && pending.isEmpty())
                    assertTrue("RF must not overwrite an unread host message", now >= readyAt)
                    val frame = request.copyOfRange(4, request.size)
                    assertEquals(frame.size - 1, request[3].toInt() and 255)
                    writes++
                    if (frame.contentEquals(byteArrayOf(0x40, 1, 0))) {
                        handshakeWrites++
                        pending.add(byteArrayOf(0x40, 6, 0x80.toByte(), 0x81.toByte(), -1, 5, if (capability) 1 else 0, 0))
                    } else {
                        val ctrl = frame[0].toInt() and 255
                        val pos = (ctrl ushr 2) and 3
                        val h = if (pos == 1) 6 else 2
                        if (pos == 1) {
                            total = ((frame[2].toInt() and 255) shl 24) or
                                ((frame[3].toInt() and 255) shl 16) or
                                ((frame[4].toInt() and 255) shl 8) or (frame[5].toInt() and 255)
                        }
                        val count = frame[1].toInt() and 255
                        val final = ctrl and 0x20 != 0
                        segment.write(frame, h, count - if (final) 4 else 0)
                        if (final) {
                            val data = segment.toByteArray()
                            val crc = CRC32().apply { update(data) }.value
                            val peer = frame.takeLast(4).fold(0L) { c, b -> (c shl 8) or (b.toLong() and 255) }
                            assertEquals(crc, peer)
                            assertEquals(segments and 1, (ctrl ushr 1) and 1)
                            assembled.write(data)
                            segment = ByteArrayOutputStream()
                            segments++
                            pending.add(byteArrayOf(0x80.toByte()))
                            if (pos == 3 || pos == 0) {
                                assertEquals(total, assembled.size())
                                pending.add(byteArrayOf(0x40, 2, 0x89.toByte(), 0x81.toByte()))
                            }
                        }
                    }
                    readyAt = now + hostReadDelay
                    byteArrayOf(0)
                }
                else -> error("unexpected RF command $code")
            }
        }
    }
    private fun setup(chip: Chip, limit: Int = 240, adaptive: Boolean = true): Pair<St25ImageSession, St25CommandChannel> {
        val channel = St25CommandChannel(uid, limit + 4, chip::exchange, { chip.now += it }, {})
        val session = St25ImageSession(channel, limit, { chip.now }, { chip.now += it }, {}, adaptive)
        return session to channel
    }
    @Test fun wholeImageSurvivesEnableAndReportedSegmentBoundaryBusyRefusals() {
        val chip = Chip()
        val (session, channel) = setup(chip)
        val progress = mutableListOf<Float>()
        session.send(file) { p, _ -> progress += p }
        assertArrayEquals(byteArrayOf(9) + file, chip.assembled.toByteArray())
        assertEquals(28833, session.confirmedOffset)
        assertTrue(session.handshakeOk)
        assertEquals(15, chip.segments)
        assertEquals(setOf(4, 9, 10, 15), chip.refusedSegments)
        assertEquals(4, chip.writeRefusals)
        assertTrue(channel.busyRetries >= 6)
        assertEquals(1, chip.handshakeWrites)
        assertEquals(18, chip.consumedReplies) // hello, 15 ACKs, receipt, actual refresh result
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
    }
    @Test fun coldStartAndSmallerPhonePacketLimitStillTransferWholeImage() {
        val chip = Chip().apply { modeReadyAt = 650 }
        val (session, _) = setup(chip, 124)
        session.send(file) { _, _ -> }
        assertArrayEquals(byteArrayOf(9) + file, chip.assembled.toByteArray())
        assertEquals(28833, session.confirmedOffset)
    }
    @Test fun adaptivePacingCutsIdleTimeWithoutSkippingMailboxOwnershipOrPanelResult() {
        val legacyChip = Chip()
        val (legacy, _) = setup(legacyChip, adaptive = false)
        legacy.send(file) { _, _ -> }
        val chip = Chip()
        val (session, _) = setup(chip)
        session.send(file) { _, _ -> }
        assertArrayEquals(legacyChip.assembled.toByteArray(), chip.assembled.toByteArray())
        assertEquals(legacyChip.writes, chip.writes)
        assertEquals(legacyChip.consumedReplies, chip.consumedReplies)
        assertTrue("Must save idle time against the same host model", session.uploadElapsedMs < legacy.uploadElapsedMs)
        assertTrue(session.writeGuardMs < 50L)
        assertEquals(18, chip.consumedReplies)
        assertTrue(chip.completionQueued)
        println("Mailbox model only: fixed50=${legacy.uploadElapsedMs}ms adaptive=${session.uploadElapsedMs}ms")
    }
    @Test fun slowerHostRaisesGuardInsteadOfOverwritingUnreadPackets() {
        val chip = Chip().apply { hostReadDelay = 90 }
        val (session, _) = setup(chip)
        session.send(file) { _, _ -> }
        assertArrayEquals(byteArrayOf(9) + file, chip.assembled.toByteArray())
        assertEquals(28833, session.confirmedOffset)
        assertTrue(session.writeGuardMs > 50L && session.writeGuardMs <= 80L)
        assertEquals(15, chip.segments)
    }
    @Test fun ioFailureDoesNotResendAnAmbiguousWriteOrAdvanceConfirmedProgress() {
        val chip = Chip().apply { failAtSegment = 4 }
        val (session, _) = setup(chip)
        assertThrows(IOException::class.java) { session.send(file) { _, _ -> } }
        assertEquals(6144, session.confirmedOffset)
        assertEquals(3, chip.segments)
    }
    @Test fun mailboxPowerLossIsReportedBeforeWritingMoreData() {
        val chip = Chip().apply { dropMailbox = true }
        val (session, _) = setup(chip)
        assertThrows(IOException::class.java) { session.send(file) { _, _ -> } }
        assertEquals(0, chip.writes)
        assertEquals(0, session.confirmedOffset)
    }
    @Test fun hostStallHasBoundedTimeout() {
        val chip = Chip().apply { timeout = true }
        val (session, _) = setup(chip)
        assertThrows(IOException::class.java) { session.send(file) { _, _ -> } }
        assertTrue(chip.now in 6000..8000)
        assertEquals(0, session.confirmedOffset)
    }
    @Test fun acceptanceDoesNotCompleteSessionBeforePanelResult() {
        val chip = Chip()
        val (session, _) = setup(chip)
        var waiting = false
        var acceptedAt = 0L
        val elapsed = session.send(file, onRefreshWaiting = {
            waiting = true;acceptedAt = chip.now
            assertEquals(17, chip.consumedReplies)
            assertFalse(chip.completionQueued)
        }) { _, _ -> }
        assertTrue(waiting && session.imageAccepted)
        assertEquals(35000L, elapsed)
        assertTrue(chip.now - acceptedAt >= 34900)
        assertEquals(18, chip.consumedReplies)
    }
    @Test fun refreshFailureAndWrongImageNeverShowCompletion() {
        for (wrongImage in listOf(false, true)) {
            val chip = Chip().apply {
                if (wrongImage) mismatchImage = true else panelError = -10
            }
            val (session, _) = setup(chip)
            assertThrows(IOException::class.java) { session.send(file) { _, _ -> } }
            assertTrue(session.imageAccepted)
            assertEquals(15, chip.segments) // no automatic image resend
        }
    }
    @Test fun powerLossAfterAcceptanceDoesNotReplayImage() {
        val chip = Chip().apply { losePowerDuringRefresh = true }
        val (session, _) = setup(chip)
        assertThrows(IOException::class.java) { session.send(file) { _, _ -> } }
        assertTrue(session.imageAccepted)
        assertFalse(chip.completionQueued)
        assertEquals(15, chip.segments)
        assertEquals(1, chip.handshakeWrites)
    }
    @Test fun missingRefreshResultHasLongButBoundedWait() {
        val chip = Chip().apply { suppressCompletion = true }
        val (session, _) = setup(chip)
        assertThrows(IOException::class.java) { session.send(file) { _, _ -> } }
        assertTrue(session.imageAccepted)
        assertTrue(chip.now - chip.completionDue!! in 295000..296000)
        assertEquals(15, chip.segments)
    }
    @Test fun oldFirmwareIsRejectedBeforeUploadingImage() {
        val chip = Chip().apply { capability = false }
        val (session, _) = setup(chip)
        assertThrows(IOException::class.java) { session.send(file) { _, _ -> } }
        assertFalse(session.imageAccepted)
        assertEquals(0, chip.assembled.size())
    }

}
