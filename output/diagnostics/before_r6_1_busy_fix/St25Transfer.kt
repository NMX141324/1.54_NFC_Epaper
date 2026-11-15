package com.example.epapernfc

import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.NfcV
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Blocking transport; must only run on Dispatchers.IO. No EEPROM/config/password writes. */
class St25Transfer(private val tag: Tag) {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var connection: NfcV? = null

    fun cancel() { cancelled.set(true); runCatching { connection?.close() } }

    private fun checkActive() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw IOException("传输已取消")
    }

    fun send(file: ByteArray, progress: (Float, String) -> Unit) {
        require(file.size == 28832)
        require(tag.id.size == 8 && tag.id[7] == 0xe0.toByte() && tag.id[6] == 2.toByte() && tag.id[5] == 0x24.toByte()) {
            "请贴近 ST25DV04K-IE 标签（UID 应以 E00224 开头）"
        }
        val nfc = NfcV.get(tag) ?: throw IOException("该标签不支持 NFC-V / ISO15693")
        connection = nfc
        var enabled = false
        var rfLost = false
        var stage = "连接 NFC-V"
        var confirmedOffset = 0
        var handshakeOk = false
        var channel: St25CommandChannel? = null
        fun step(name: String) {
            stage = name
            progress(confirmedOffset.toFloat() / (file.size + 1), name)
            Log.i("EpaperNfc", "stage=$name confirmed=$confirmedOffset")
        }
        try {
            checkActive()
            step("连接 NFC-V…")
            nfc.connect()
            channel = St25CommandChannel(tag.id, nfc.maxTransceiveLength, nfc::transceive,
                { SystemClock.sleep(it) }, ::checkActive)
            val rf = channel
            val maxPacket = St25Commands.maxMailboxPacket(nfc.maxTransceiveLength)
            Log.i("EpaperNfc", "phone=${Build.MANUFACTURER} ${Build.MODEL} Android=${Build.VERSION.RELEASE} maxTx=${nfc.maxTransceiveLength} packet=$maxPacket mailboxFlags=02")
            fun command(code: Int, vararg parameters: Int) = rf.command(code, *parameters)
            fun control(): Int {
                val data = command(0xad, 0x0d)
                if (data.size != 1) throw IOException("Mailbox 控制寄存器响应长度错误")
                return data[0].toInt() and 255
            }
            fun awaitControl(predicate: (Int) -> Boolean): Int {
                val until = SystemClock.elapsedRealtime() + 6000
                do {
                    checkActive()
                    val value = control()
                    if (value and 1 == 0) throw IOException("Mailbox 已关闭，请确认 MCU 已启动 r5.1 固件")
                    if (predicate(value)) return value
                    SystemClock.sleep(20)
                } while (SystemClock.elapsedRealtime() < until)
                throw IOException("等待 MCU Mailbox 超时：RF 寄存器可读，但 MCU 未在 6 秒内完成交接")
            }
            fun readMessage(): ByteArray {
                awaitControl { it and 2 != 0 }
                val lengthReply = command(0xab)
                if (lengthReply.size != 1) throw IOException("Mailbox 长度响应错误")
                val length = (lengthReply[0].toInt() and 255) + 1
                val bytes = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    val count = minOf(220, length - offset)
                    val part = command(0xac, offset, count - 1)
                    if (part.size != count) throw IOException("Mailbox 读取长度不匹配")
                    part.copyInto(bytes, offset)
                    offset += count
                }
                return bytes
            }
            fun writeMessage(frame: ByteArray) {
                awaitControl { it and 6 == 0 }
                command(0xaa, frame.size - 1, *frame.map { it.toInt() and 255 }.toIntArray())
                // The board uses software I2C (~100 kHz) and a 10 ms main-loop sleep.
                // Let it consume a 240-byte message before polling RF again.
                SystemClock.sleep(40)
            }
            step("读取 ST25DV FTM 配置（A0）…")
            val mode = command(0xa0, 0x0d)
            if (mode.size != 1 || mode[0].toInt() and 1 == 0) {
                throw IOException("芯片未启用 MB_MODE；请确认板上 r5.1 固件已完成启动配置")
            }
            step("读取 Mailbox 状态（AD，非寻址）…")
            if (control() and 1 != 0) {
                step("复位上次 Mailbox 会话（AE）…")
                command(0xae, 0x0d, 0)
                SystemClock.sleep(60)
            }
            step("开启 Mailbox（AE）…")
            command(0xae, 0x0d, 1)
            enabled = true
            SystemClock.sleep(60)
            step("发送 MCU 握手查询（AA）…")
            writeMessage(byteArrayOf(0x40, 1, 0))
            step("等待 MCU 握手回执（AD / AB / AC）…")
            val hello = readMessage()
            if (hello.size < 4 || hello[0] != 0x40.toByte() || hello[2] != 0x80.toByte() || hello[3] != 0x81.toByte()) {
                throw IOException("MCU 握手回执不兼容：${hello.joinToString(" ") { "%02X".format(it.toInt() and 255) }}")
            }
            handshakeOk = true

            val payload = byteArrayOf(5) + file
            val segments = FtmCodec.segments(payload, maxPacket)
            for ((index, segment) in segments.withIndex()) {
                var accepted = false
                for (attempt in 0..2) {
                    for ((frameIndex, frame) in segment.frames.withIndex()) {
                        step("发送第 ${index + 1}/${segments.size} 段，第 ${frameIndex + 1}/${segment.frames.size} 包")
                        writeMessage(frame)
                    }
                    step("等待第 ${index + 1} 段 CRC 确认…")
                    val ack = readMessage()
                    if (ack.size != 1) throw IOException("段确认格式错误，请重新发送整图")
                    when (ack[0].toInt() and 255) {
                        0x80 -> { accepted = true; break }
                        0x81 -> step("第 ${index + 1} 段校验重试 ${attempt + 1}/3")
                        else -> throw IOException("MCU 拒绝图像分段：%02X".format(ack[0].toInt() and 255))
                    }
                }
                if (!accepted) throw IOException("连续三次段 CRC 校验失败")
                confirmedOffset = segment.endOffset
                step("MCU 已确认 $confirmedOffset / ${payload.size} 字节")
            }
            step("数据已发完，等待 MCU 确认整图…")
            val result = readMessage()
            if (!result.contentEquals(byteArrayOf(0x40, 2, 0x85.toByte(), 0x81.toByte()))) {
                throw IOException("MCU 未确认整图，可能是 E6IM 校验失败")
            }
            SystemClock.sleep(60)
            step("MCU 已确认收图，屏幕开始刷新；此回执不代表屏幕已刷新完成")
        } catch (e: Exception) {
            rfLost = e is TagLostException
            if (cancelled.get()) throw IOException("传输已取消", e)
            val reason = if (rfLost) "手机 NFC 未收到标签响应（Tag was lost）" else (e.message ?: e.javaClass.simpleName)
            val ctrl = channel?.lastControl?.let { "%02X".format(it) } ?: "未知"
            val message = "$reason\n阶段：$stage\n最后命令：${channel?.lastExchange ?: "尚未发送"}\nMB_CTRL=$ctrl；已确认 $confirmedOffset/28833 字节；MCU 握手=${if (handshakeOk) "成功" else "尚未成功"}\n手机：${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}"
            Log.e("EpaperNfc", message, e)
            throw IOException(message, e)
        } finally {
            if (enabled && !rfLost && !cancelled.get() && nfc.isConnected) {
                runCatching {
                    nfc.transceive(St25Commands.request(0xae, tag.id, byteArrayOf(0x0d, 0)))
                }
            }
            runCatching { nfc.close() }
            connection = null
        }
    }
}
