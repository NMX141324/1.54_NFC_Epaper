package com.example.epapernfc

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class St25CommandsTest {
    private val uid = byteArrayOf(0x08, 0x07, 0x06, 0x05, 0x04, 0x24, 0x02, 0xe0.toByte())

    @Test fun commandsMatchStRfGoldenFramesWithoutUidDuringI2cAccess() {
        val expected = mapOf(
            0xa0 to (intArrayOf(0x0d) to byteArrayOf(0x02, 0xa0.toByte(), 0x02, 0x0d)),
            0xad to (intArrayOf(0x0d) to byteArrayOf(0x02, 0xad.toByte(), 0x02, 0x0d)),
            0xae to (intArrayOf(0x0d, 1) to byteArrayOf(0x02, 0xae.toByte(), 0x02, 0x0d, 1)),
            0xaa to (intArrayOf(2, 0x40, 1, 0) to byteArrayOf(0x02, 0xaa.toByte(), 0x02, 2, 0x40, 1, 0)),
            0xab to (intArrayOf() to byteArrayOf(0x02, 0xab.toByte(), 0x02)),
            0xac to (intArrayOf(0, 7) to byteArrayOf(0x02, 0xac.toByte(), 0x02, 0, 7))
        )
        for ((code, pair) in expected) {
            var request = byteArrayOf()
            val channel = St25CommandChannel(uid, 253, { request = it; byteArrayOf(0) }, {}, {})
            channel.command(code, *pair.first)
            assertArrayEquals(pair.second, request)
        }
    }

    /** Mailbox commands must remain non-addressed while I2C arbitrates access. */
    @Test fun handshakeWorksWhenI2cArbitrationRejectsAddressedCustomCommands() {
        val hello = byteArrayOf(0x40, 6, 0x80.toByte(), 0x81.toByte(), 0xff.toByte(), 5, 0, 0)
        var enabled = false
        var hostMessage = byteArrayOf()
        var hostReading = false
        fun chip(request: ByteArray): ByteArray {
            if (request[0].toInt() and 0x20 != 0) throw IOException("no UID response during I2C access")
            require(request[2] == 2.toByte())
            return when (request[1].toInt() and 255) {
                0xa0 -> byteArrayOf(0, 1)
                0xae -> { enabled = request[4] == 1.toByte(); byteArrayOf(0) }
                0xad -> byteArrayOf(0, if (!enabled) 0 else if (hostReading) 5 else if (hostMessage.isEmpty()) 1 else 3)
                0xaa -> {
                    assertTrue(enabled)
                    assertEquals(request.size - 5, request[3].toInt() and 255)
                    assertArrayEquals(byteArrayOf(0x40, 1, 0), request.copyOfRange(4, request.size))
                    hostReading = true
                    byteArrayOf(0)
                }
                0xab -> byteArrayOf(0, (hostMessage.size - 1).toByte())
                0xac -> {
                    val response = byteArrayOf(0) + hostMessage
                    hostMessage = byteArrayOf()
                    response
                }
                else -> error("unexpected command")
            }
        }
        val oldRequest = byteArrayOf(0x22, 0xad.toByte(), 2) + uid + byteArrayOf(0x0d)
        assertThrows(IOException::class.java) { chip(oldRequest) }
        val channel = St25CommandChannel(uid, 253, ::chip, {}, {})
        assertArrayEquals(byteArrayOf(1), channel.command(0xa0, 0x0d))
        channel.command(0xae, 0x0d, 1)
        assertArrayEquals(byteArrayOf(1), channel.command(0xad, 0x0d))
        channel.command(0xaa, 2, 0x40, 1, 0)
        assertArrayEquals(byteArrayOf(5), channel.command(0xad, 0x0d))
        hostReading = false
        hostMessage = hello
        assertArrayEquals(byteArrayOf(3), channel.command(0xad, 0x0d))
        assertArrayEquals(byteArrayOf(7), channel.command(0xab))
        assertArrayEquals(hello, channel.command(0xac, 0, 7))
        assertArrayEquals(byteArrayOf(1), channel.command(0xad, 0x0d))
        assertEquals(1, channel.lastControl)
    }

    @Test fun explicitBusyReadRetriesAreBounded() {
        var calls = 0
        val delays = mutableListOf<Long>()
        val channel = St25CommandChannel(uid, 253, {
            calls++
            if (calls < 3) byteArrayOf(1, 0x0f) else byteArrayOf(0, 1)
        }, { delays += it }, {})
        assertArrayEquals(byteArrayOf(1), channel.command(0xad, 0x0d))
        assertEquals(3, calls)
        assertEquals(listOf(20L, 40L), delays)
        assertEquals(1, channel.successfulCommands)
    }

    @Test fun persistentBusyReadStopsAfterBoundedResponses() {
        var calls = 0
        val channel = St25CommandChannel(uid, 253, { calls++; byteArrayOf(1, 0x0f) }, {}, {})
        assertThrows(IOException::class.java) { channel.command(0xad, 0x0d) }
        assertEquals(St25CommandChannel.MAX_BUSY_ATTEMPTS, calls)
    }

    @Test fun mailboxWritesAndConsumingReadsAreNeverBlindlyRetried() {
        for (code in listOf(0xaa, 0xac, 0xae)) {
            var calls = 0
            val channel = St25CommandChannel(uid, 253, { calls++; throw IOException("RF response lost") }, {}, {})
            assertThrows(IOException::class.java) { channel.command(code, 0) }
            assertEquals(1, calls)
            assertTrue(channel.lastExchange.contains("%02X".format(code)))
        }
        var calls = 0
        val channel = St25CommandChannel(uid, 253, { calls++; byteArrayOf(1, 0x0f) }, {}, {})
        assertThrows(IOException::class.java) { channel.command(0xac, 0, 0) }
        assertEquals(St25CommandChannel.MAX_BUSY_ATTEMPTS, calls)
    }

    @Test fun readIoFailureIsNotMistakenForAnExplicitBusyResponse() {
        var calls = 0
        val channel = St25CommandChannel(uid, 253, { calls++; throw IOException("lost") }, {}, {})
        assertThrows(IOException::class.java) { channel.command(0xad, 0x0d) }
        assertEquals(1, calls)
    }

    @Test fun explicitBusyRefusalsRetryWritesAndConsumeExactlyOnce() {
        for (code in listOf(0xaa, 0xac, 0xae)) {
            var calls = 0
            var executed = 0
            val requests = mutableListOf<ByteArray>()
            val channel = St25CommandChannel(uid, 253, {
                calls++; requests += it
                if (calls <= 4) byteArrayOf(1, 0x0f)
                else { executed++; byteArrayOf(0) }
            }, {}, {})
            channel.command(code, 0)
            assertEquals(5, calls)
            assertEquals(1, executed)
            assertEquals(4, channel.busyRetries)
            requests.forEach { assertArrayEquals(requests[0], it) }
        }
    }
    @Test fun nonBusyErrorsAndEmptyRepliesNeverReplayCommands() {
        for (reply in listOf(byteArrayOf(1, 3), byteArrayOf(), byteArrayOf(1))) {
            var calls = 0
            val channel = St25CommandChannel(uid, 253, { calls++; reply }, {}, {})
            assertThrows(IOException::class.java) { channel.command(0xaa, 0) }
            assertEquals(1, calls)
        }
    }
    @Test fun cancellationInterruptsBusyRetryBeforeNextRfCommand() {
        var cancelled = false
        var calls = 0
        val channel = St25CommandChannel(uid, 253, { calls++; byteArrayOf(1, 0x0f) },
            { cancelled = true }, { if (cancelled) throw IOException("cancelled") })
        assertThrows(IOException::class.java) { channel.command(0xaa, 0) }
        assertEquals(1, calls)
    }

    @Test fun packetLimitAccountsForEntireRfCommand() {
        for (phoneLimit in listOf(20, 64, 128, 253)) {
            val frame = ByteArray(St25Commands.maxMailboxPacket(phoneLimit))
            val request = St25Commands.request(0xaa, uid, byteArrayOf((frame.size - 1).toByte()) + frame)
            assertTrue(request.size <= phoneLimit)
        }
        var sent = false
        val channel = St25CommandChannel(uid, 20, { sent = true; byteArrayOf(0) }, {}, {})
        assertThrows(IllegalArgumentException::class.java) { channel.command(0xaa, *IntArray(100)) }
        assertFalse(sent)
    }
}
