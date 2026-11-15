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
    @Volatile var imageAccepted = false
        private set

    fun cancel() { cancelled.set(true); runCatching { connection?.close() } }

    private fun checkActive() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw IOException("传输已取消")
    }

    fun send(file: ByteArray, onRefreshWaiting: () -> Unit = {}, progress: (Float, String) -> Unit): Long {
        require(file.size == 28832)
        require(tag.id.size == 8 && tag.id[7] == 0xe0.toByte() && tag.id[6] == 2.toByte() && tag.id[5] == 0x24.toByte()) {
            "请贴近 ST25DV04K-IE 标签（UID 应以 E00224 开头）"
        }
        val nfc = NfcV.get(tag) ?: throw IOException("该标签不支持 NFC-V / ISO15693")
        connection = nfc
        var rfLost = false
        var stage = "连接 NFC-V"
        var session: St25ImageSession? = null
        var channel: St25CommandChannel? = null
        var refreshElapsed = 0L
        fun step(name: String) {
            stage = name
            progress((session?.confirmedOffset ?: 0).toFloat() / (file.size + 1), name)
            Log.i("EpaperNfc", "stage=$name confirmed=${session?.confirmedOffset ?: 0}")
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
            val transferSession = St25ImageSession(rf, maxPacket, SystemClock::elapsedRealtime,
                { SystemClock.sleep(it) }, ::checkActive)
            session = transferSession
            refreshElapsed = transferSession.send(file, onRefreshWaiting = {
                imageAccepted = true
                onRefreshWaiting()
            }) { fraction, message ->
                stage = message
                progress(fraction, message)
                Log.i("EpaperNfc", "stage=$message confirmed=${transferSession.confirmedOffset} busyRetries=${rf.busyRetries}")
            }
        } catch (e: Exception) {
            rfLost = e is TagLostException
            if (cancelled.get()) throw IOException("传输已取消", e)
            val reason = if (rfLost) "手机 NFC 未收到标签响应（Tag was lost）" else (e.message ?: e.javaClass.simpleName)
            val ctrl = channel?.lastControl?.let { "%02X".format(it) } ?: "未知"
            val message = "$reason\n阶段：${session?.stage ?: stage}\n最后命令：${channel?.lastExchange ?: "尚未发送"}\nMB_CTRL=$ctrl；已确认 ${session?.confirmedOffset ?: 0}/28833 字节；MCU 握手=${if (session?.handshakeOk == true) "成功" else "尚未成功"}\n手机：${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}; busyRetries=${channel?.busyRetries ?: 0}"
            Log.e("EpaperNfc", message, e)
            throw IOException(message, e)
        } finally {
            if (session?.enabled == true && !rfLost && !cancelled.get() && nfc.isConnected) {
                runCatching {
                    channel?.command(0xae, 0x0d, 0)
                }
            }
            runCatching { nfc.close() }
            connection = null
        }
        return refreshElapsed
    }
}
