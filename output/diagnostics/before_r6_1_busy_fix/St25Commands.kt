package com.example.epapernfc

import java.io.IOException

/** RF command framing, distinct from the FTM payload processed by the STM32.
 * ST recommends non-addressed Mailbox commands during simultaneous I2C access.
 * Only one NFC-V target should be in the phone's field during an image transfer.
 */
object St25Commands {
    private val nonAddressedCommands = setOf(0xa0, 0xaa, 0xab, 0xac, 0xad, 0xae)

    fun request(code: Int, uid: ByteArray, parameters: ByteArray): ByteArray {
        require(uid.size == 8)
        require(code in 0..255)
        val addressed = code !in nonAddressedCommands
        return byteArrayOf(if (addressed) 0x22 else 0x02, code.toByte(), 0x02) +
            (if (addressed) uid else byteArrayOf()) + parameters
    }

    fun maxMailboxPacket(maxTransceiveLength: Int): Int {
        // Flags + command + ST manufacturer code + message length. No UID.
        require(maxTransceiveLength >= 20) { "手机 NFC 单次传输长度不足" }
        return minOf(240, maxTransceiveLength - 4)
    }
}

/** Testable raw RF channel; an I/O failure is never blindly retried as a write.
 * Retry only explicit transient 0F responses to side-effect-free register reads.
 */
class St25CommandChannel(
    private val uid: ByteArray,
    private val maxTransceiveLength: Int,
    private val transceive: (ByteArray) -> ByteArray,
    private val pause: (Long) -> Unit,
    private val checkActive: () -> Unit
) {
    var lastExchange = "尚未发送 RF 命令"
        private set
    var successfulCommands = 0
        private set
    var lastControl: Int? = null
        private set

    fun command(code: Int, vararg parameters: Int): ByteArray {
        require(parameters.all { it in 0..255 })
        val request = St25Commands.request(code, uid, parameters.map { it.toByte() }.toByteArray())
        require(request.size <= maxTransceiveLength) {
            "RF 包长 ${request.size} 超过手机上限 $maxTransceiveLength"
        }
        val attempts = if (code == 0xa0 || code == 0xad || code == 0xab) 3 else 1
        for (attempt in 0 until attempts) {
            checkActive()
            lastExchange = "%02X / flags=%02X / TX=%d 字节".format(code, request[0].toInt() and 255, request.size)
            val reply = transceive(request)
            if (reply.isEmpty()) throw IOException("ST25DV 返回空响应：$lastExchange")
            if (reply[0].toInt() and 1 != 0) {
                val error = reply.getOrNull(1)?.toInt()?.and(255) ?: -1
                if (error == 0x0f && attempt + 1 < attempts) {
                    pause(20L * (attempt + 1))
                    continue
                }
                throw IOException("ST25DV 错误 %02X：%s".format(error, lastExchange))
            }
            successfulCommands++
            val data = reply.copyOfRange(1, reply.size)
            if (code == 0xad && parameters.contentEquals(intArrayOf(0x0d)) && data.size == 1) {
                lastControl = data[0].toInt() and 255
            }
            return data
        }
        error("unreachable")
    }
}
