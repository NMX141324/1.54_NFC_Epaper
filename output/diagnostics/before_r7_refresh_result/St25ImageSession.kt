package com.example.epapernfc

import java.io.IOException

/** Pure protocol session, exercised with injected RF/I2C busy windows in JVM tests. */
class St25ImageSession(
    private val rf: St25CommandChannel,
    private val packetLimit: Int,
    private val clock: () -> Long,
    private val pause: (Long) -> Unit,
    private val checkActive: () -> Unit
) {
    var stage = "等待 NFC 就绪"
        private set
    var confirmedOffset = 0
        private set
    var handshakeOk = false
        private set
    var enabled = false
        private set
    private var progress: (Float, String) -> Unit = { _, _ -> }

    private fun step(name: String) {
        stage = name
        progress(confirmedOffset.toFloat() / 28833, name)
    }
    private fun control(): Int {
        val data = rf.command(0xad, 0x0d)
        if (data.size != 1) throw IOException("Mailbox 控制寄存器响应长度错误")
        return data[0].toInt() and 255
    }
    private fun awaitControl(predicate: (Int) -> Boolean): Int {
        val until = clock() + 6000
        do {
            checkActive()
            val value = control()
            if (value and 1 == 0) throw IOException("Mailbox 已关闭：请检查板子供电、是否复位及 MCU 初始化状态")
            if (predicate(value)) return value
            pause(30)
        } while (clock() < until)
        throw IOException("等待 MCU Mailbox 超时：MCU 未在 6 秒内完成交接")
    }
    private fun readMessage(): ByteArray {
        awaitControl { it and 2 != 0 }
        val lengthReply = rf.command(0xab)
        if (lengthReply.size != 1) throw IOException("Mailbox 长度响应错误")
        val length = (lengthReply[0].toInt() and 255) + 1
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = minOf(220, length - offset)
            val part = rf.command(0xac, offset, count - 1)
            if (part.size != count) throw IOException("Mailbox 读取长度不匹配")
            part.copyInto(bytes, offset)
            offset += count
        }
        // Reading the last byte fires RF_GET_MSG. Leave time for the MCU to
        // commit the segment/queue the next response before another RF write.
        pause(30)
        return bytes
    }
    private fun writeMessage(frame: ByteArray) {
        awaitControl { it and 6 == 0 }
        rf.command(0xaa, frame.size - 1, *frame.map { it.toInt() and 255 }.toIntArray())
        // This board has software I2C and a 10ms main loop. The explicit 0F
        // retry remains necessary: a fixed guard alone cannot synchronize it.
        pause(50)
    }
    fun send(file: ByteArray, onProgress: (Float, String) -> Unit) {
        require(file.size == 28832)
        progress = onProgress
        step("等待标签供电与 MCU 准备（300ms）…")
        pause(300)
        checkActive()
        step("读取 ST25DV FTM 配置（A0）…")
        val until = clock() + 2000
        while (true) {
            val mode = rf.command(0xa0, 0x0d)
            if (mode.size != 1) throw IOException("FTM 配置响应长度错误")
            if (mode[0].toInt() and 1 != 0) break
            if (clock() >= until) throw IOException("芯片 MB_MODE 未启用，请检查 MCU 供电和启动配置")
            pause(100)
            checkActive()
        }
        step("读取 Mailbox 状态（AD）…")
        if (control() and 1 != 0) {
            step("复位上次 Mailbox 会话（AE）…")
            rf.command(0xae, 0x0d, 0)
            pause(80)
        }
        step("开启 Mailbox（AE）…")
        rf.command(0xae, 0x0d, 1)
        enabled = true
        pause(80)
        step("发送 MCU 握手查询（AA）…")
        writeMessage(byteArrayOf(0x40, 1, 0))
        step("等待 MCU 握手回执…")
        val hello = readMessage()
        if (hello.size < 4 || hello[0] != 0x40.toByte() || hello[2] != 0x80.toByte() || hello[3] != 0x81.toByte()) {
            throw IOException("MCU 握手回执不兼容")
        }
        handshakeOk = true
        val payload = byteArrayOf(5) + file
        val segments = FtmCodec.segments(payload, packetLimit)
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
        pause(60)
        step("MCU 已确认收图，等待屏幕刷新完成；忙重试 ${rf.busyRetries} 次")
    }
}
