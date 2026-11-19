package com.example.epapernfc

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.IntSize

@Composable
fun PhotoCanvas(source: Bitmap, settings: EditSettings, sixColor: Bitmap?, enabled: Boolean,
                modifier: Modifier = Modifier, onGestureStart: () -> Unit,
                onSettings: (EditSettings) -> Unit) {
    // Read this state only in drawing and pointer handling, not in composition.
    // Hundreds of move events do not launch bitmap jobs or recompose the editor.
    val draft = remember { mutableStateOf(settings) }
    val painter = remember(source) { ImageProcessor.Painter() }
    val currentStart by rememberUpdatedState(onGestureStart)
    val currentSettings by rememberUpdatedState(onSettings)
    val currentSixColor by rememberUpdatedState(sixColor)
    LaunchedEffect(source, settings) { draft.value = settings }
    Canvas(modifier.pointerInput(source, enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var moved = false
            do {
                val event = awaitPointerEvent()
                val pan = event.calculatePan()
                val zoom = event.calculateZoom()
                if (pan != Offset.Zero || zoom != 1f) {
                    if (!moved) { moved = true; currentStart() }
                    val center = event.calculateCentroid(useCurrent = false)
                    draft.value = applyPhotoGesture(draft.value,
                        pan.x / size.width, pan.y / size.height, zoom,
                        center.x / size.width - .5f, center.y / size.height - .5f)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } while (event.changes.any { it.pressed })
            if (moved) currentSettings(draft.value)
        }
    }) {
        drawRect(Color.White)
        val preview = currentSixColor
        if (preview != null) {
            drawImage(preview.asImageBitmap(), dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                filterQuality = FilterQuality.None)
        } else {
            drawIntoCanvas { painter.draw(it.nativeCanvas, source, draft.value, size.width) }
        }
    }
}
