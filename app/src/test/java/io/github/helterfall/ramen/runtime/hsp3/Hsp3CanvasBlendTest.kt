package io.github.helterfall.ramen.runtime.hsp3

import android.graphics.Bitmap
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Hsp3CanvasBlendTest {
    @Test
    fun gcopyModeTwoLeavesDestinationUnchangedForBlackPixels() {
        val renderer = Hsp3CanvasRenderer({ ByteArrayInputStream(byteArrayOf()) }, 6, 4)
        try {
            prepareBlackAndGreenSource(renderer)
            renderer.gsel(0)
            renderer.gmode(2, 2, 2, 0)
            renderer.gcopy(1, 0, 0, 2, 2, 0, 0)
            assertPixel(renderer, 255, 0, 0, 0, 0)
        } finally { renderer.close() }
    }

    @Test
    fun gcopyModeTwoStillCopiesNonBlackPixels() {
        val renderer = Hsp3CanvasRenderer({ ByteArrayInputStream(byteArrayOf()) }, 6, 4)
        try {
            prepareBlackAndGreenSource(renderer)
            renderer.gsel(0)
            renderer.gmode(2, 2, 2, 0)
            renderer.gcopy(1, 0, 0, 2, 2, 0, 0)
            assertPixel(renderer, 0, 255, 0, 1, 1)
        } finally { renderer.close() }
    }

    @Test
    fun gzoomModeTwoLeavesDestinationUnchangedForBlackPixels() {
        val renderer = Hsp3CanvasRenderer({ ByteArrayInputStream(byteArrayOf()) }, 6, 4)
        try {
            prepareBlackAndGreenSource(renderer)
            renderer.gsel(0)
            renderer.gmode(2, 2, 2, 0)
            renderer.pos(2, 0)
            renderer.gzoom(4, 4, 1, 0, 0, 2, 2, 0)
            assertPixel(renderer, 255, 0, 0, 2, 0)
        } finally { renderer.close() }
    }

    @Test
    fun gzoomModeTwoStillCopiesNonBlackPixels() {
        val renderer = Hsp3CanvasRenderer({ ByteArrayInputStream(byteArrayOf()) }, 6, 4)
        try {
            prepareBlackAndGreenSource(renderer)
            renderer.gsel(0)
            renderer.gmode(2, 2, 2, 0)
            renderer.pos(2, 0)
            renderer.gzoom(4, 4, 1, 0, 0, 2, 2, 0)
            assertPixel(renderer, 0, 255, 0, 4, 2)
        } finally { renderer.close() }
    }

    @Test
    fun gmodeTwoReusesCachedMaskAndInvalidatesItWhenSourceChanges() {
        val renderer = Hsp3CanvasRenderer({ ByteArrayInputStream(byteArrayOf()) }, 6, 4)
        try {
            prepareBlackAndGreenSource(renderer)
            renderer.gsel(0)
            renderer.gmode(2, 2, 2, 0)
            renderer.gcopy(1, 0, 0, 2, 2, 0, 0)
            assertEquals(1, renderer.colorKeyCacheBuildCount)

            renderer.pos(2, 0)
            renderer.gzoom(4, 4, 1, 0, 0, 2, 2, 0)
            assertEquals(1, renderer.colorKeyCacheBuildCount)

            renderer.gsel(1)
            renderer.color(0, 0, 255)
            renderer.boxf(0, 0, 0, 0)
            renderer.gsel(0)
            renderer.gcopy(1, 0, 0, 2, 2, 4, 0)
            assertEquals(2, renderer.colorKeyCacheBuildCount)
            assertPixel(renderer, 0, 0, 255, 4, 0)
        } finally { renderer.close() }
    }

    @Test
    fun redrawOffKeepsPresentationRevisionStableUntilRedrawOn() {
        val renderer = Hsp3CanvasRenderer({ ByteArrayInputStream(byteArrayOf()) }, 6, 4)
        try {
            renderer.screen(0, 6, 4)
            renderer.redraw(false)
            val frozenRevision = renderer.presentationRevision()
            renderer.color(0, 255, 0)
            renderer.boxf(0, 0, 5, 3)
            assertEquals(frozenRevision, renderer.presentationRevision())

            renderer.redraw(true)
            assertTrue(renderer.presentationRevision() > frozenRevision)
        } finally { renderer.close() }
    }

    private fun prepareBlackAndGreenSource(renderer: Hsp3CanvasRenderer) {
        renderer.screen(0, 6, 4)
        renderer.color(255, 0, 0)
        renderer.boxf(0, 0, 5, 3)
        renderer.buffer(1, 2, 2)
        renderer.gsel(1)
        renderer.color(0, 0, 0)
        renderer.boxf(0, 0, 1, 1)
        renderer.color(0, 255, 0)
        renderer.boxf(1, 1, 1, 1)
    }

    @Test
    fun gmodeFiveAddsSourceChannelsScaledByAlphaAndClampsAt255() {
        val renderer = Hsp3CanvasRenderer({ ByteArrayInputStream(byteArrayOf()) }, 3, 3)
        try {
            renderer.screen(0, 3, 3)
            renderer.buffer(1, 3, 3)
            renderer.gsel(1)
            renderer.color(100, 200, 255)
            renderer.boxf(0, 0, 2, 2)
            renderer.gsel(0)
            renderer.color(200, 80, 10)
            renderer.boxf(0, 0, 2, 2)
            assertSurfacePixel(renderer, 1, 100, 200, 255, 1, 1)
            assertSurfacePixel(renderer, 0, 200, 80, 10, 1, 1)
            renderer.gmode(5, 3, 3, 128)
            renderer.gcopy(1, 0, 0, 3, 3, 0, 0)
            assertPixel(renderer, 250, 180, 137, 1, 1)
        } finally { renderer.close() }
    }

    @Test
    fun gmodeSixSubtractsSourceChannelsScaledByAlphaAndClampsAtZero() {
        val renderer = Hsp3CanvasRenderer({ ByteArrayInputStream(byteArrayOf()) }, 3, 3)
        try {
            renderer.screen(0, 3, 3)
            renderer.buffer(1, 3, 3)
            renderer.gsel(1)
            renderer.color(100, 200, 255)
            renderer.boxf(0, 0, 2, 2)
            renderer.gsel(0)
            renderer.color(200, 80, 10)
            renderer.boxf(0, 0, 2, 2)
            assertSurfacePixel(renderer, 1, 100, 200, 255, 1, 1)
            assertSurfacePixel(renderer, 0, 200, 80, 10, 1, 1)
            renderer.gmode(6, 3, 3, 128)
            renderer.gcopy(1, 0, 0, 3, 3, 0, 0)
            assertPixel(renderer, 150, 0, 0, 1, 1)
        } finally { renderer.close() }
    }

    private fun assertSurfacePixel(
        renderer: Hsp3CanvasRenderer,
        surfaceId: Int,
        red: Int,
        green: Int,
        blue: Int,
        x: Int,
        y: Int
    ) {
        val bitmap = renderer.copySurfaceBitmap(surfaceId)
        try {
            val expected = 0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
            val actual = bitmap.getPixel(x, y)
            println("HSP source surface $surfaceId pixel ($x,$y): expected=0x${expected.toUInt().toString(16)} actual=0x${actual.toUInt().toString(16)}")
            assertEquals(
                "surface $surfaceId expected 0x${expected.toUInt().toString(16)}, " +
                    "actual 0x${actual.toUInt().toString(16)} at ($x,$y)",
                expected,
                actual
            )
        } finally { bitmap.recycle() }
    }

    private fun assertPixel(
        renderer: Hsp3CanvasRenderer,
        red: Int,
        green: Int,
        blue: Int,
        x: Int,
        y: Int
    ) {
        val bitmap = renderer.copySurfaceBitmap(0)
        try {
            assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
            val expected = 0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
            val actual = bitmap.getPixel(x, y)
            println("HSP blend pixel ($x,$y): expected=0x${expected.toUInt().toString(16)} actual=0x${actual.toUInt().toString(16)}")
            assertEquals(
                "expected 0x${expected.toUInt().toString(16)}, actual 0x${actual.toUInt().toString(16)} at ($x,$y)",
                expected,
                actual
            )
        } finally { bitmap.recycle() }
    }
}
