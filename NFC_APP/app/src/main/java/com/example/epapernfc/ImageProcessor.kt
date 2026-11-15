package com.example.epapernfc

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import kotlin.math.min

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

data class RenderedImage(val adjusted: Bitmap, val preview: Bitmap, val file: ByteArray)

object ImageProcessor {
    /** Bounded decode avoids loading full-resolution camera images into memory. */
    fun load(resolver: ContentResolver, uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { requireNotNull(it) { "无法打开照片" }; BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法解码照片，请选择 JPG、PNG 或 WebP 图片" }
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize.coerceAtLeast(1) > 1600) {
                inSampleSize = inSampleSize.coerceAtLeast(1) * 2
            }
        }
        val bitmap = resolver.openInputStream(uri).use { BitmapFactory.decodeStream(requireNotNull(it), null, options) }
            ?: error("照片解码失败")
        val orientation = try {
            resolver.openInputStream(uri).use { ExifInterface(requireNotNull(it)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }
        } catch (_: Exception) { 1 }
        val matrix = Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f, 1f)
                3 -> setRotate(180f)
                4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }
                6 -> setRotate(90f)
                7 -> { setRotate(270f); postScale(-1f, 1f) }
                8 -> setRotate(270f)
            }
        }
        return if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun adjust(source: Bitmap, settings: EditSettings): Bitmap {
        val size = EpaperCodec.SIZE
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE)
        val quarterTurn = settings.rotation.toInt() % 180 != 0
        val width = if (quarterTurn) source.height else source.width
        val height = if (quarterTurn) source.width else source.height
        val ratio = if (settings.fill) maxOf(size.toFloat() / width, size.toFloat() / height)
                    else min(size.toFloat() / width, size.toFloat() / height)
        val matrix = Matrix().apply {
            postTranslate(-source.width / 2f, -source.height / 2f)
            postRotate(settings.rotation)
            postScale(ratio * settings.zoom, ratio * settings.zoom)
            postTranslate(size / 2f + settings.panX * size, size / 2f + settings.panY * size)
        }
        val saturation = ColorMatrix().apply { setSaturation(settings.saturation) }
        val c = settings.contrast
        val shift = (1f - c) * 127.5f + settings.brightness * 255f
        val adjustment = ColorMatrix(floatArrayOf(
            c, 0f, 0f, 0f, shift, 0f, c, 0f, 0f, shift,
            0f, 0f, c, 0f, shift, 0f, 0f, 0f, 1f, 0f
        )).apply { postConcat(saturation) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(adjustment)
        }
        canvas.drawBitmap(source, matrix, paint)
        return output
    }

    /** Expensive six-colour quantization runs only after the user's explicit request. */
    fun dither(adjusted: Bitmap, settings: EditSettings, check: () -> Unit): RenderedImage {
        check()
        val size = EpaperCodec.SIZE
        val pixels = IntArray(size * size)
        adjusted.getPixels(pixels, 0, size, 0, 0, size, size)
        val raw = EpaperCodec.quantize(pixels, settings.algorithm, settings.strength, check)
        val preview = Bitmap.createBitmap(EpaperCodec.preview(raw), size, size, Bitmap.Config.ARGB_8888)
        return RenderedImage(adjusted, preview, EpaperCodec.file(raw, (System.currentTimeMillis() / 1000).toInt()))
    }
}
