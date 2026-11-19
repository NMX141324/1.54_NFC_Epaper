package com.example.epapernfc

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

data class EditSettings(
    val zoom: Float = 1f,
    val rotation: Float = 0f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val fill: Boolean = true,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val algorithm: Dither = Dither.FLOYD_STEINBERG,
    val strength: Float = 1f
)

data class PhotoTransform(val scale: Float, val centerX: Float, val centerY: Float)

/** One geometry calculation for both the live canvas and the transmitted pixels. */
fun photoTransform(width: Int, height: Int, size: Float, settings: EditSettings): PhotoTransform {
    require(width > 0 && height > 0 && size > 0)
    val radians = Math.toRadians(settings.rotation.toDouble())
    val c = abs(cos(radians)).toFloat()
    val s = abs(sin(radians)).toFloat()
    val ratio = if (settings.fill) {
        maxOf(size * (c + s) / width, size * (c + s) / height)
    } else {
        minOf(size / (width * c + height * s), size / (width * s + height * c))
    }
    return PhotoTransform(ratio * settings.zoom, size * (0.5f + settings.panX), size * (0.5f + settings.panY))
}

/** Pan is normalized to the canvas; zoom remains anchored under the fingers. */
fun applyPhotoGesture(settings: EditSettings, dx: Float, dy: Float, zoom: Float,
                      centerX: Float = 0f, centerY: Float = 0f): EditSettings {
    if (!dx.isFinite() || !dy.isFinite() || !zoom.isFinite() || zoom <= 0f) return settings
    val nextZoom = (settings.zoom * zoom).coerceIn(0.5f, 4f)
    val ratio = nextZoom / settings.zoom
    val cx = if (centerX.isFinite()) centerX else 0f
    val cy = if (centerY.isFinite()) centerY else 0f
    return settings.copy(zoom = nextZoom,
        panX = (cx + (settings.panX - cx) * ratio + dx).coerceIn(-1f, 1f),
        panY = (cy + (settings.panY - cy) * ratio + dy).coerceIn(-1f, 1f))
}
