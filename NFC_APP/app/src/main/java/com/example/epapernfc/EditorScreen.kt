package com.example.epapernfc

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private enum class EditorTool(val title: String, val symbol: String) {
    DITHER("算法", ""), CROP("构图", "↔"), ANGLE("角度", "↻"),
    CONTRAST("对比度", "◐"), BRIGHTNESS("亮度", "☼"), SATURATION("饱和度", "●")
}

@Composable
fun EditorScreen(
    state: EditorState, hasNfc: Boolean, nfcEnabled: Boolean,
    onImport: () -> Unit, onSettings: (EditSettings) -> Unit,
    onGestureStart: () -> Unit, onDither: () -> Unit,
    onSend: () -> Unit, onCancel: () -> Unit, onNfcSettings: () -> Unit
) {
    var selectedTool by rememberSaveable { mutableIntStateOf(-1) }
    var showDither by rememberSaveable { mutableStateOf(true) }
    val locked = state.waiting || state.sending
    val editable = state.hasSource && !locked && !state.processing && !state.dithering
    val displayDither = showDither && state.image != null
    LaunchedEffect(state.image) { if (state.image != null) showDither = true }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
            val previewSide = (maxHeight - if (selectedTool >= 0) 424.dp else 368.dp).coerceIn(152.dp, 248.dp)
                .coerceAtMost(maxWidth - 48.dp)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("纸间 · NFC", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    Text("240 × 240", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Column(Modifier.fillMaxWidth().padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (displayDither) "六色预览" else "照片预览", fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            Text(if (displayDither) "查看原图" else "查看六色图", fontSize = 12.sp,
                                color = if (state.image != null) MaterialTheme.colorScheme.primary else Color.Transparent,
                                modifier = Modifier.padding(4.dp).clickable(enabled = state.image != null) { showDither = !showDither })
                        }
                        Box(Modifier.size(previewSide).clip(RoundedCornerShape(12.dp))
                            .background(Color(0xfffaf9f3))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center) {
                            state.source?.let { source ->
                                PhotoCanvas(source, state.settings, if (displayDither) state.image?.preview else null,
                                    editable, Modifier.fillMaxSize(), onGestureStart, onSettings)
                            } ?: Text("选择一张照片", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (state.processing || state.dithering) {
                                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                            }
                        }
                        Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = onImport, enabled = !locked && !state.processing && !state.dithering,
                                modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) {
                                Text("选择照片", fontSize = 13.sp)
                            }
                            TextButton(onClick = { onSettings(EditSettings()) }, enabled = editable,
                                modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) {
                                Text("全部重置", fontSize = 13.sp)
                            }
                            FilledTonalButton(onClick = onDither, enabled = editable,
                                modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) {
                                Text(if (state.dithering) "处理中" else "生成预览", fontSize = 13.sp)
                            }
                        }
                    }
                }
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            EditorTool.entries.forEachIndexed { index, tool ->
                                ToolChoice(tool, selectedTool == index, toolValue(tool, state.settings), editable,
                                    Modifier.weight(1f)) { selectedTool = if (selectedTool == index) -1 else index }
                            }
                        }
                        AnimatedVisibility(selectedTool >= 0, enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()) {
                            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                if (selectedTool in EditorTool.entries.indices) {
                                    ToolPanel(EditorTool.entries[selectedTool], state.settings, editable, onSettings)
                                }
                            }
                        }
                    }
                }
                if (state.error != null && !state.showTransferDialog) {
                    Text(state.error.substringBefore('\n').take(60), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error, maxLines = 2)
                }
                Button(onClick = { if (!nfcEnabled && hasNfc) onNfcSettings() else onSend() },
                    enabled = hasNfc && state.image != null && !locked && !state.processing && !state.dithering,
                    modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(15.dp)) {
                    Text(when { !hasNfc -> "设备不支持 NFC"; !nfcEnabled -> "开启 NFC"; else -> "传输到墨水屏" },
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    if (state.showTransferDialog) TransferProgressDialog(state, onCancel)
}

private fun toolValue(tool: EditorTool, s: EditSettings): String = when (tool) {
    EditorTool.DITHER -> when (s.algorithm) { Dither.NONE -> "无"; Dither.FLOYD_STEINBERG -> "F–S"; Dither.ATKINSON -> "Atkinson"; Dither.BAYER -> "Bayer" }
    EditorTool.CROP -> "${(s.zoom * 100).roundToInt()}%"
    EditorTool.ANGLE -> "${s.rotation.roundToInt()}°"
    EditorTool.CONTRAST -> "${(s.contrast * 100).roundToInt()}%"
    EditorTool.BRIGHTNESS -> "${((s.brightness + 1) * 100).roundToInt()}%"
    EditorTool.SATURATION -> "${(s.saturation * 100).roundToInt()}%"
}

@Composable
private fun ToolChoice(tool: EditorTool, selected: Boolean, value: String, enabled: Boolean,
                       modifier: Modifier, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.height(70.dp),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (tool == EditorTool.DITHER) {
                Row(Modifier.height(24.dp), horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    listOf(Color(0xff236b5b), Color(0xffd9b43c), Color(0xffbd5e4f)).forEach {
                        Box(Modifier.size(5.dp).background(it, CircleShape))
                    }
                }
            } else Text(tool.symbol, fontSize = 21.sp, lineHeight = 24.sp, color = color)
            Text(tool.title, fontSize = 11.sp, lineHeight = 16.sp, color = color,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            Text(value, fontSize = 10.sp, lineHeight = 14.sp, color = color, maxLines = 1)
        }
    }
}

@Composable
private fun ToolPanel(tool: EditorTool, s: EditSettings, enabled: Boolean, change: (EditSettings) -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp)) {
            when (tool) {
                EditorTool.DITHER -> {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        Row(Modifier.fillMaxWidth().height(32.dp).clickable(enabled = enabled) { menu = true },
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(s.algorithm.title, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text("⌄", fontSize = 18.sp)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            Dither.entries.forEach { algorithm ->
                                DropdownMenuItem(text = { Text(algorithm.title) }, onClick = {
                                    menu = false;change(s.copy(algorithm = algorithm))
                                })
                            }
                        }
                    }
                    Adjustment("强度", s.strength, 0f..1f, "${(s.strength * 100).roundToInt()}%",
                        enabled && s.algorithm != Dither.NONE, { change(s.copy(strength = 1f)) }) { change(s.copy(strength = it)) }
                }
                EditorTool.CROP -> {
                    Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("构图", fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(if (s.fill) "填满裁剪" else "完整留白", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(4.dp).clickable(enabled = enabled) { change(s.copy(fill = !s.fill)) })
                    }
                    Adjustment("缩放", s.zoom, 0.5f..4f, "${(s.zoom * 100).roundToInt()}%", enabled,
                        { change(s.copy(zoom = 1f, panX = 0f, panY = 0f)) }) { change(s.copy(zoom = it)) }
                }
                EditorTool.ANGLE -> {
                    Row(Modifier.fillMaxWidth().height(32.dp), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("旋转", fontSize = 13.sp)
                        Text("左转 90°", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(4.dp).clickable(enabled = enabled) { change(s.copy(rotation = (s.rotation + 270f) % 360f)) })
                        Text("右转 90°", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(4.dp).clickable(enabled = enabled) { change(s.copy(rotation = (s.rotation + 90f) % 360f)) })
                    }
                    Adjustment("角度", s.rotation, 0f..360f, "${s.rotation.roundToInt()}°", enabled,
                        { change(s.copy(rotation = 0f)) }) { change(s.copy(rotation = it)) }
                }
                EditorTool.CONTRAST -> Adjustment("对比度", s.contrast, 0.5f..2f,
                    "${(s.contrast * 100).roundToInt()}%", enabled, { change(s.copy(contrast = 1f)) }) { change(s.copy(contrast = it)) }
                EditorTool.BRIGHTNESS -> Adjustment("亮度", s.brightness, -0.5f..0.5f,
                    "${((s.brightness + 1) * 100).roundToInt()}%", enabled, { change(s.copy(brightness = 0f)) }) { change(s.copy(brightness = it)) }
                EditorTool.SATURATION -> Adjustment("饱和度", s.saturation, 0f..2f,
                    "${(s.saturation * 100).roundToInt()}%", enabled, { change(s.copy(saturation = 1f)) }) { change(s.copy(saturation = it)) }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun Adjustment(label: String, value: Float, range: ClosedFloatingPointRange<Float>,
                       display: String, enabled: Boolean, reset: () -> Unit, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(display, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        Text("重置", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp).clickable(enabled = enabled, onClick = reset))
    }
    Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range,
        enabled = enabled, modifier = Modifier.fillMaxWidth().height(36.dp),
        thumb = {
            Box(Modifier.size(18.dp).background(
                if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f), CircleShape))
        },
        track = { sliderState ->
            SliderDefaults.Track(sliderState = sliderState, enabled = enabled,
                modifier = Modifier.height(4.dp), thumbTrackGapSize = 0.dp,
                drawStopIndicator = null)
        })
}

@Composable
internal fun TransferProgressDialog(state: EditorState, onClose: () -> Unit) {
    val active = state.waiting || state.sending
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    LaunchedEffect(state.refreshCompleted) {
        if (state.refreshCompleted) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
    }
    val title = when {
        state.error != null -> "未完成"
        state.refreshCompleted -> "刷新完成"
        state.refreshing -> "刷新中"
        state.waiting -> "贴近墨水屏"
        else -> "传输中"
    }
    AlertDialog(onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
        title = { Text(title, fontSize = 19.sp) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.refreshing || state.waiting) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { state.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(if (state.refreshing) "图片已传完" else "${(state.progress * 100).roundToInt()}%",
                    Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.primary)
                state.error?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) {
            Text(if (active) "取消" else if (state.refreshCompleted) "完成" else "关闭")
        } })
}
