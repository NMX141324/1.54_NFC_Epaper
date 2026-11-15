package com.example.epapernfc

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun EditorScreen(
    state: EditorState, hasNfc: Boolean, nfcEnabled: Boolean,
    onImport: () -> Unit, onSettings: (EditSettings) -> Unit,
    onDither: () -> Unit, onImportGallery: () -> Unit, onImportFile: () -> Unit,
    onSend: () -> Unit, onCancel: () -> Unit, onNfcSettings: () -> Unit,
    onSaveBin: () -> Unit, onSavePng: () -> Unit
) {
    var importOptions by remember { mutableStateOf(false) }
    val settings = state.settings
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showDither by rememberSaveable { mutableStateOf(true) }
    val locked = state.waiting || state.sending
    val displayDither = showDither && state.image != null
    LaunchedEffect(state.image) { if (state.image != null) showDither = true }
    val currentSettings by rememberUpdatedState(settings)
    val currentChange by rememberUpdatedState(onSettings)
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("纸间 · NFC", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("1.54 英寸 / 六色墨水屏", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(24.dp)) {
                    Text("240 × 240", Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge)
                }
            }
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(if (displayDither) "六色输出预览" else "构图与调色", fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { showDither = !showDither }, enabled = state.image != null) {
                            Text(if (displayDither) "查看调色图" else "查看六色图")
                        }
                    }
                    Box(Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp)).background(Color.White)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .pointerInput(state.hasSource, locked) {
                            if (state.hasSource && !locked) detectTransformGestures { _, pan, zoom, _ ->
                                val s = currentSettings
                                currentChange(s.copy(zoom = (s.zoom * zoom).coerceIn(0.5f, 4f),
                                    panX = (s.panX + pan.x / size.width).coerceIn(-1f, 1f),
                                    panY = (s.panY + pan.y / size.height).coerceIn(-1f, 1f)))
                            }
                        }, contentAlignment = Alignment.Center) {
                        val bitmap = if (displayDither) state.image?.preview else state.adjusted
                        if (bitmap != null) {
                            Image(bitmap.asImageBitmap(),
                                contentDescription = if (displayDither) "最终六色像素预览，可拖动和双指缩放" else "调色后的照片，可拖动和双指缩放",
                                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                                filterQuality = if (displayDither) FilterQuality.None else FilterQuality.Medium)
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("把喜欢的照片，留在纸上", color = Color(0xff555b58))
                                Button(onClick = onImport, enabled = !state.processing && !locked) { Text("相册选图") }
                                TextButton(onClick = { importOptions = true }, enabled = !state.processing && !locked) { Text("其他导入方式") }
                                Text("照片仅在本机处理", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                        }
                        if (state.processing || state.dithering) CircularProgressIndicator(Modifier.size(36.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        EpaperCodec.colors.forEach { color ->
                            Box(Modifier.size(16.dp).clip(CircleShape).background(Color(color))
                                .border(1.dp, Color(0x22000000), CircleShape))
                        }
                        Text("六色 · 4 bpp", style = MaterialTheme.typography.labelMedium)
                    }
                    Text("拖动或双指缩放；调整参数后请手动点击「开始抖动」",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onImport, enabled = !locked && !state.processing && !state.dithering,
                            contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.weight(1f)) { Text("相册选图") }
                        TextButton(onClick = { importOptions = true }, enabled = !locked && !state.processing && !state.dithering,
                            modifier = Modifier.weight(1f)) { Text("其他导入") }
                        TextButton(onClick = { onSettings(EditSettings()) }, enabled = state.hasSource && !locked,
                            modifier = Modifier.weight(1f)) { Text("重置编辑") }
                    }
                    if (state.hasSource) {
                        Button(onClick = onDither, modifier = Modifier.fillMaxWidth(),
                            enabled = !locked && !state.processing && !state.dithering && state.adjusted != null) {
                            Text(if (state.dithering) "正在抖动处理…" else if (state.image == null) "开始抖动 · 生成六色图" else "重新抖动")
                        }
                        Text(if (state.image == null) "当前为调色预览，生成六色图后才可发送或导出" else "六色图已就绪；更改参数后需要重新抖动",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (state.hasSource) {
                PrimaryTabRow(selectedTabIndex = tab) {
                    listOf("构图", "调色", "抖动").forEachIndexed { index, label ->
                        Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
                    }
                }
                Card {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (tab) {
                            0 -> {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(selected = settings.fill, onClick = { onSettings(settings.copy(fill = true)) },
                                        enabled = !locked, label = { Text("填满裁剪") })
                                    FilterChip(selected = !settings.fill, onClick = { onSettings(settings.copy(fill = false)) },
                                        enabled = !locked, label = { Text("完整留白") })
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { onSettings(settings.copy(rotation = (settings.rotation + 270f) % 360f)) },
                                        enabled = !locked, modifier = Modifier.weight(1f)) { Text("左转 90°") }
                                    OutlinedButton(onClick = { onSettings(settings.copy(rotation = (settings.rotation + 90f) % 360f)) },
                                        enabled = !locked, modifier = Modifier.weight(1f)) { Text("右转 90°") }
                                }
                                Adjustment("缩放", settings.zoom, 0.5f..4f, "%.2f×".format(settings.zoom), !locked) { onSettings(settings.copy(zoom = it)) }
                                Adjustment("水平位置", settings.panX, -1f..1f, "${(settings.panX * 100).roundToInt()}%", !locked) { onSettings(settings.copy(panX = it)) }
                                Adjustment("垂直位置", settings.panY, -1f..1f, "${(settings.panY * 100).roundToInt()}%", !locked) { onSettings(settings.copy(panY = it)) }
                            }
                            1 -> {
                                Adjustment("亮度", settings.brightness, -0.5f..0.5f, "${(settings.brightness * 100).roundToInt()}", !locked) { onSettings(settings.copy(brightness = it)) }
                                Adjustment("对比度", settings.contrast, 0.5f..2f, "%.2f".format(settings.contrast), !locked) { onSettings(settings.copy(contrast = it)) }
                                Adjustment("饱和度", settings.saturation, 0f..2f, "%.2f".format(settings.saturation), !locked) { onSettings(settings.copy(saturation = it)) }
                            }
                            else -> {
                                Dither.entries.forEach { algorithm ->
                                    FilterChip(selected = settings.algorithm == algorithm,
                                        onClick = { onSettings(settings.copy(algorithm = algorithm)) },
                                        enabled = !locked, label = { Text(algorithm.title) })
                                }
                                Text(when (settings.algorithm) {
                                    Dither.NONE -> "直接映射到最近屏色，适合文字、图标和色块。"
                                    Dither.FLOYD_STEINBERG -> "蛇形误差扩散，保留照片层次，适合作为默认选项。"
                                    Dither.ATKINSON -> "颗粒较轻、对比鲜明，适合插画和人像。"
                                    Dither.BAYER -> "规则 4×4 网点，适合有秩序的像素风格。"
                                }, style = MaterialTheme.typography.bodySmall)
                                Adjustment("抖动强度", settings.strength, 0f..1f, "${(settings.strength * 100).roundToInt()}%",
                                    !locked && settings.algorithm != Dither.NONE) { onSettings(settings.copy(strength = it)) }
                                Button(onClick = onDither, modifier = Modifier.fillMaxWidth(),
                                    enabled = !locked && !state.processing && !state.dithering && state.adjusted != null) {
                                    Text(if (state.dithering) "正在抖动处理…" else "开始抖动 · 生成六色图")
                                }
                            }
                        }
                    }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("传到墨水屏", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(when {
                        !hasNfc -> "本机无 NFC，仍可编辑并导出 E6IM 文件。"
                        !nfcEnabled -> "NFC 尚未开启，请在系统设置中打开。"
                        else -> "ST25DV04K · Mailbox · 兼容 r5.1 固件"
                    }, style = MaterialTheme.typography.bodySmall)
                    if (hasNfc && !nfcEnabled) OutlinedButton(onClick = onNfcSettings) { Text("打开 NFC 设置") }
                    Text(state.status, style = MaterialTheme.typography.bodyMedium)
                    if (state.sending) {
                        Text("传输进度 ${(state.progress * 100).toInt()}%", fontWeight = FontWeight.Bold)
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    }
                    if (state.waiting) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    if (locked) {
                        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("取消传输") }
                    } else {
                        Button(onClick = onSend, modifier = Modifier.fillMaxWidth(),
                            enabled = hasNfc && nfcEnabled && state.image != null && !state.processing && !state.dithering) {
                            Text("通过 NFC 发送图片")
                        }
                    }
                    Text("上电保留原画面，直接等待传图。发送时保持贴紧；稳定性对照请外接 3.3V。收图后仍需保持供电，等待屏幕刷新完成。",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSavePng, modifier = Modifier.weight(1f),
                    enabled = state.image != null && !state.processing && !state.dithering && !locked) { Text("保存六色 PNG") }
                OutlinedButton(onClick = onSaveBin, modifier = Modifier.weight(1f),
                    enabled = state.image != null && !state.processing && !state.dithering && !locked) { Text("导出 E6IM") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    if (importOptions) {
        AlertDialog(onDismissRequest = { importOptions = false }, title = { Text("选择照片来源") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { importOptions = false; onImportGallery() }) { Text("其他相册应用") }
                    TextButton(onClick = { importOptions = false; onImportFile() }) { Text("从文件选择") }
                }
            }, confirmButton = { TextButton(onClick = { importOptions = false }) { Text("取消") } })
    }
    if (state.showTransferDialog) {
        TransferProgressDialog(state, onCancel)
    }
}

@Composable
internal fun TransferProgressDialog(state: EditorState, onClose: () -> Unit) {
    val active = state.waiting || state.sending
    val context = androidx.compose.ui.platform.LocalContext.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    androidx.compose.runtime.LaunchedEffect(state.refreshCompleted) {
        if (state.refreshCompleted) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
    }
    AlertDialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
        title = { Text(when {
            state.waiting -> "等待 NFC 标签"
            state.refreshing -> "正在刷新屏幕"
            state.sending -> "正在传输图片"
            state.error != null -> "操作未完成"
            state.refreshCompleted -> "刷新完成"
            else -> "等待刷新结果"
        }) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.waiting) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("贴近标签后开始传输，当前 0%")
                } else {
                    Text("${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    Text("MCU 已确认 ${(state.progress * 28833).roundToInt()} / 28,833 字节")
                }
                Text(state.error ?: state.status)
                if (state.sending) Text(if (state.refreshing)
                    "图片已传完。保持手机贴紧、不要切换应用；收到刷屏结果后才会提示完成。停止等待不会撤销已启动的刷新。"
                    else "保持贴紧 NFC 天线，请勿切换应用。",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        dismissButton = {
            if (state.error != null) TextButton(onClick = {
                context.getSystemService(android.content.ClipboardManager::class.java)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText("NFC 传输诊断", state.error))
            }) { Text("复制错误详情") }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(if (state.refreshing) "停止等待" else if (active) "取消传输" else "关闭") } }
    )
}

@Composable
private fun Adjustment(label: String, value: Float, range: ClosedFloatingPointRange<Float>,
    display: String, enabled: Boolean, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(display, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, enabled = enabled)
    }
}
