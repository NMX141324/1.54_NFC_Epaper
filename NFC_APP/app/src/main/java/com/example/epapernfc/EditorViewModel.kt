package com.example.epapernfc

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.nfc.Tag
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EditorState(
    val settings: EditSettings = EditSettings(),
    val image: RenderedImage? = null,
    val adjusted: Bitmap? = null,
    val processing: Boolean = false,
    val dithering: Boolean = false,
    val showTransferDialog: Boolean = false,
    val hasSource: Boolean = false,
    val waiting: Boolean = false,
    val sending: Boolean = false,
    val refreshing: Boolean = false,
    val refreshCompleted: Boolean = false,
    val progress: Float = 0f,
    val status: String = "导入照片，制作你的六色墨水屏图片",
    val error: String? = null
)

class EditorViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {
    private fun restored() = EditSettings(
        zoom = saved["zoom"] ?: 1f, rotation = saved["rotation"] ?: 0f,
        panX = saved["panX"] ?: 0f, panY = saved["panY"] ?: 0f, fill = saved["fill"] ?: true,
        brightness = saved["brightness"] ?: 0f, contrast = saved["contrast"] ?: 1f,
        saturation = saved["saturation"] ?: 1f,
        algorithm = Dither.entries.firstOrNull { it.name == saved.get<String>("algorithm") } ?: Dither.FLOYD_STEINBERG,
        strength = saved["strength"] ?: 1f
    )
    var state by mutableStateOf(EditorState(settings = restored()))
        private set
    private var source: Bitmap? = null
    private var loadJob: Job? = null
    private var renderJob: Job? = null
    private var ditherJob: Job? = null
    private var sendJob: Job? = null
    private var transport: St25Transfer? = null
    private var pendingFile: ByteArray? = null

    init { saved.get<String>("uri")?.let { load(Uri.parse(it), reset = false) } }

    fun load(uri: Uri, reset: Boolean = true) {
        if (state.waiting || state.sending) return
        loadJob?.cancel()
        renderJob?.cancel()
        ditherJob?.cancel()
        state = state.copy(processing = true, dithering = false, image = null, adjusted = null,
            hasSource = false, error = null, status = "正在读取照片…")
        loadJob = viewModelScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    ImageProcessor.load(getApplication<Application>().contentResolver, uri)
                }
                source = bitmap
                saved["uri"] = uri.toString()
                state = state.copy(hasSource = true, settings = if (reset) EditSettings() else state.settings)
                update(state.settings)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                state = state.copy(processing = false, error = "导入失败：${e.message}", hasSource = source != null)
            } catch (_: OutOfMemoryError) {
                state = state.copy(processing = false, error = "照片过大，内存不足，请选择较小图片")
            }
        }
    }

    fun update(settings: EditSettings) {
        if (state.waiting || state.sending || !state.hasSource) return
        saved["zoom"] = settings.zoom; saved["rotation"] = settings.rotation
        saved["panX"] = settings.panX; saved["panY"] = settings.panY; saved["fill"] = settings.fill
        saved["brightness"] = settings.brightness; saved["contrast"] = settings.contrast
        saved["saturation"] = settings.saturation; saved["algorithm"] = settings.algorithm.name
        saved["strength"] = settings.strength
        ditherJob?.cancel()
        state = state.copy(settings = settings, image = null, processing = source != null,
            dithering = false, progress = 0f, showTransferDialog = false, error = null,
            status = "编辑完成后，点击「开始抖动」生成六色图")
        renderJob?.cancel()
        val bitmap = source ?: return
        renderJob = viewModelScope.launch {
            delay(32) // Coalesce gestures; preview never runs quantization.
            try {
                val adjusted = withContext(Dispatchers.Default) { ImageProcessor.adjust(bitmap, settings) }
                state = state.copy(adjusted = adjusted, processing = false,
                    status = "调色预览已更新，请点击「开始抖动」")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state = state.copy(processing = false, error = "图像处理失败：${e.message}") }
        }
    }

    fun dither() {
        if (state.processing || state.dithering || state.waiting || state.sending) return
        val adjusted = state.adjusted ?: return
        val settings = state.settings
        state = state.copy(dithering = true, error = null, status = "正在生成六色图…")
        ditherJob = viewModelScope.launch {
            try {
                val rendered = withContext(Dispatchers.Default) {
                    val context = coroutineContext
                    ImageProcessor.dither(adjusted, settings) { context.ensureActive() }
                }
                state = state.copy(image = rendered, dithering = false,
                    status = "六色图已生成 · 240 × 240 · 可以发送或导出")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                state = state.copy(dithering = false, error = "抖动处理失败：${e.message}")
            }
        }
    }

    fun arm() {
        val image = state.image ?: return
        if (state.processing || state.dithering || state.waiting || state.sending) return
        pendingFile = image.file.copyOf()
        state = state.copy(waiting = true, showTransferDialog = true, progress = 0f, error = null,
            refreshing = false, refreshCompleted = false,
            status = "请先移开手机，再将 NFC 天线贴近标签并保持位置")
    }

    fun onTag(tag: Tag) {
        val bytes = pendingFile ?: return
        if (!state.waiting || state.sending || sendJob?.isActive == true) return
        val link = St25Transfer(tag)
        transport = link
        state = state.copy(waiting = false, sending = true, error = null, status = "正在连接 ST25DV…")
        sendJob = viewModelScope.launch {
            try {
                val refreshMs = withContext(Dispatchers.IO) {
                    link.send(bytes, onRefreshWaiting = {
                        viewModelScope.launch {
                            if (transport === link) state = state.copy(refreshing = true)
                        }
                    }) { progress, message ->
                        viewModelScope.launch {
                            if (transport === link) state = state.copy(progress = progress, status = message)
                        }
                    }
                }
                if (transport === link) state = state.copy(sending = false, progress = 1f,
                    refreshing = false, refreshCompleted = true,
                    status = "刷新完成：MCU 已确认本张图片刷屏结束，用时 ${refreshMs / 1000.0} 秒。")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (transport === link) state = state.copy(sending = false,
                    refreshing = false, refreshCompleted = false,
                    error = if (link.imageAccepted) "图片已传完，但未确认刷新完成：${e.message ?: "NFC 连接断开"}。请保持供电，不会自动重刷。"
                        else "传输失败：${e.message ?: "NFC 连接断开"}。移开手机后重新点击发送，将从整图开始。")
            } finally {
                if (transport === link) { transport = null; pendingFile = null }
            }
        }
    }

    fun cancelTransfer() {
        val link = transport
        transport = null
        pendingFile = null
        sendJob?.cancel()
        if (link != null) viewModelScope.launch(Dispatchers.IO) { link.cancel() }
        state = if (state.waiting || state.sending) state.copy(waiting = false, sending = false, refreshing = false,
            showTransferDialog = false, status = if (link?.imageAccepted == true)
                "已停止等待结果，屏幕可能仍在刷新；请保持供电，不会自动重刷"
                else "传输已取消；重新发送将从整图开始")
        else state.copy(showTransferDialog = false)
    }

    fun report(message: String) { state = state.copy(error = message) }

    fun export(uri: Uri, png: Boolean, image: RenderedImage) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri).use { stream ->
                        requireNotNull(stream) { "无法打开目标文件" }
                        if (png) check(image.preview.compress(Bitmap.CompressFormat.PNG, 100, stream))
                        else stream.write(image.file)
                    }
                }
                state = state.copy(status = if (png) "六色预览 PNG 已保存" else "E6IM 数据文件已保存，可供 ST25 NFC Tap 发送")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { report("保存失败：${e.message}") }
        }
    }

    override fun onCleared() {
        transport?.let { link -> Thread { link.cancel() }.start() }
        super.onCleared()
    }
}
