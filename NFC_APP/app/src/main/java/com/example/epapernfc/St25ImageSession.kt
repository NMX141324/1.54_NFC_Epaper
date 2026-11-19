package com.example.epapernfc

import java.io.IOException

/** Pure protocol session, exercised with injected RF/I2C busy windows in JVM tests. */
class St25ImageSession(
    private val rf: St25CommandChannel,
    private val packetLimit: Int,
    private val clock: () -> Long,
    private val pause: (Long) -> Unit,
    private val checkActive: () -> Unit,
    private val adaptivePacing: Boolean = true
) {
    var stage = "等待 NFC 就绪"
        private set
    var confirmedOffset = 0
        private set
    var handshakeOk = false
        private set
    var enabled = false
        private set
    var imageAccepted = false
        private set
    var uploadElapsedMs = 0L
        private set
    var writeGuardMs = 50L
        private set
    private var cleanWriteHandoffs = 0
    private var previousWasWrite = false
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
    private fun awaitControl(onWait: () -> Unit = {}, predicate: (Int) -> Boolean): Int {
        val until = clock() + 6000
        do {
            checkActive()
            val value = control()
            if (value and 1 == 0) throw IOException("Mailbox 已关闭：请检查板子供电、是否复位及 MCU 初始化状态")
            if (predicate(value)) return value
            onWait()
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
        // RF_GET_MSG service has its own guard. Do not attribute that boundary
        // to the preceding RF_PUT_MSG drain speed when tuning packet pacing.
        previousWasWrite = false
        return bytes
    }
    private fun writeMessage(frame: ByteArray) {
        val busyBefore = rf.busyRetries
        var hostStillReading = false
        awaitControl(onWait = { hostStillReading = true }) { it and 6 == 0 }
        rf.command(0xaa, frame.size - 1, *frame.map { it.toInt() and 255 }.toIntArray())
        if (adaptivePacing && previousWasWrite) {
            if (hostStillReading || rf.busyRetries != busyBefore) {
                writeGuardMs = minOf(80L, writeGuardMs + 10L)
                cleanWriteHandoffs = 0
            } else if (++cleanWriteHandoffs >= 8) {
                writeGuardMs = maxOf(20L, writeGuardMs - 5L)
                cleanWriteHandoffs = 0
            }
        }
        // Retain a quiet window for software I2C/GPO service. Mailbox ownership
        // and bounded explicit-0F retry remain authoritative, even at the floor.
        pause(writeGuardMs)
        previousWasWrite = true
    }
    fun send(file: ByteArray, onRefreshWaiting: () -> Unit = {}, onProgress: (Float, String) -> Unit): Long {
        require(file.size == 28832)
        val uploadStart = clock()
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
        if (hello.size < 8 || hello[6].toInt() and 1 == 0) {
            throw IOException("板上固件不支持刷新完成回执，请更新配套固件")
        }
        val payload = byteArrayOf(9) + file
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
        if (!result.contentEquals(byteArrayOf(0x40, 2, 0x89.toByte(), 0x81.toByte()))) {
            throw IOException("MCU 未确认整图，可能是 E6IM 校验失败")
        }
        imageAccepted = true
        uploadElapsedMs = clock() - uploadStart
        onRefreshWaiting()
        val refreshAt = clock()
        val untilRefresh = refreshAt + 330000
        var shownSecond = -1L
        while (clock() < untilRefresh) {
            checkActive()
            val seconds = (clock() - refreshAt) / 1000
            if (seconds != shownSecond) {
                shownSecond = seconds
                step("图片已传完，等待屏幕刷新完成（${seconds}秒）；请保持贴紧供电")
            }
            val value = control()
            if (value and 1 == 0) {
                throw IOException("刷屏期间 Mailbox 关闭，可能断电或 MCU 复位；未确认刷新完成，不会自动重发图片")
            }
            if (value and 2 != 0) {
                val completed = readMessage()
                if (completed.size != 24 || completed[0] != 0x40.toByte() ||
                    completed[1] != 22.toByte() || completed[2] != 0x89.toByte()) {
                    throw IOException("刷屏结果回执格式不匹配，未确认刷新完成")
                }
                if (!completed.copyOfRange(4, 12).contentEquals(file.copyOfRange(16, 24))) {
                    throw IOException("刷屏回执不属于本张图片，未确认刷新完成")
                }
                fun word(offset: Int): Long = (0..3).fold(0L) { v, b ->
                    v or ((completed[offset + b].toLong() and 255) shl (8 * b))
                }
                val elapsed = word(12)
                val error = word(16).toInt()
                val reset = word(20)
                if (completed[3] != 0x90.toByte() || error != 0) {
                    throw IOException("屏幕刷新失败：驱动错误 $error；用时 ${elapsed}ms；复位标志 %08X".format(reset))
                }
                step("屏幕刷新完成，用时 ${elapsed / 1000.0} 秒")
                return elapsed
            }
            pause(250)
        }
        throw IOException("图片已传完，但 330 秒内未收到刷屏结果；未确认完成，不会自动重发")
    }
}
