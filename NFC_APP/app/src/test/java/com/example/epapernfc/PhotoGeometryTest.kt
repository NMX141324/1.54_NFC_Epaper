package com.example.epapernfc

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class PhotoGeometryTest {
    @Test fun fillAndFitMatchSquareOutput() {
        assertEquals(1.2f, photoTransform(400, 200, 240f, EditSettings()).scale, .0001f)
        assertEquals(.6f, photoTransform(400, 200, 240f, EditSettings(fill = false)).scale, .0001f)
        val t = photoTransform(400, 200, 240f, EditSettings(panX = .25f, panY = -.1f))
        assertEquals(180f, t.centerX, .0001f)
        assertEquals(96f, t.centerY, .0001f)
    }
    @Test fun everyAngleKeepsFillCornersInsideSourceAndFitInsideCanvas() {
        for (angle in listOf(0f, 30f, 45f, 90f, 135f, 179f, 270f)) {
            val radians = Math.toRadians(angle.toDouble())
            val c = cos(radians);val s = sin(radians)
            val fill = photoTransform(400, 200, 240f, EditSettings(rotation = angle))
            for (x in listOf(-120.0, 120.0)) for (y in listOf(-120.0, 120.0)) {
                assertTrue(abs((x * c + y * s) / fill.scale) <= 200.0001)
                assertTrue(abs((-x * s + y * c) / fill.scale) <= 100.0001)
            }
            val fit = photoTransform(400, 200, 240f, EditSettings(rotation = angle, fill = false))
            for (x in listOf(-200.0, 200.0)) for (y in listOf(-100.0, 100.0)) {
                assertTrue(abs((x * c - y * s) * fit.scale) <= 120.0001)
                assertTrue(abs((x * s + y * c) * fit.scale) <= 120.0001)
            }
        }
    }
    @Test fun manySmallMovesAccumulateWithoutDroppedEvents() {
        var settings = EditSettings()
        repeat(100) { settings = applyPhotoGesture(settings, .002f, -.001f, 1f) }
        assertEquals(.2f, settings.panX, .0001f)
        assertEquals(-.1f, settings.panY, .0001f)
    }
    @Test fun pinchAnchorsToFingersAndUsesActualClampedScale() {
        val zoomed = applyPhotoGesture(EditSettings(), 0f, 0f, 2f, .2f, -.1f)
        assertEquals(2f, zoomed.zoom, .0001f)
        assertEquals(-.2f, zoomed.panX, .0001f)
        assertEquals(.1f, zoomed.panY, .0001f)
        val clamped = applyPhotoGesture(EditSettings(zoom = 2f, panX = -.1f), 0f, 0f, 9f, .3f)
        assertEquals(4f, clamped.zoom, .0001f)
        assertEquals(-.5f, clamped.panX, .0001f)
    }
    @Test fun invalidAndOutOfRangeGestureInputsAreContained() {
        val initial = EditSettings()
        assertEquals(initial, applyPhotoGesture(initial, Float.NaN, 0f, 1f))
        assertEquals(initial, applyPhotoGesture(initial, 0f, 0f, Float.POSITIVE_INFINITY))
        val moved = applyPhotoGesture(initial, 10f, -10f, .01f)
        assertEquals(.5f, moved.zoom, .0001f)
        assertEquals(1f, moved.panX, .0001f)
        assertEquals(-1f, moved.panY, .0001f)
    }
}
