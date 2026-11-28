package io.github.helterfall.ramen.runtime.hsp3

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun gcopyModeTwoMakesBlackPixelsTransparentForOpaqueDecodedAssets() {
        val image = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        val encoded = try {
            image.setHasAlpha(false)
            image.setPixel(0, 0, Color.BLACK)
            image.setPixel(1, 0, Color.GREEN)
            java.io.ByteArrayOutputStream().use { output ->
                image.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
        } finally {
            image.recycle()
        }

        val renderer = Hsp3CanvasRenderer({ ByteArrayInputStream(encoded) }, 2, 1)
        try {
            renderer.buffer(1, 2, 1)
            renderer.gsel(1)
            renderer.picload("opaque-sprite.png", 0)

            val source = renderer.copySurfaceBitmap(1)
            try {
                assertFalse("the fixture must decode without an alpha channel", source.hasAlpha())
            } finally {
                source.recycle()
            }

            renderer.gsel(0)
            renderer.color(255, 0, 0)
            renderer.boxf(0, 0, 1, 0)
            renderer.gmode(2, 2, 1, 0)
            renderer.gcopy(1, 0, 0, 2, 1, 0, 0)

            // The render path must build the cached color-key image from this
            // opaque decoded source. Assert the mask's alpha directly rather than
            // relying on Robolectric's one-pixel Canvas resampling behavior.
            val cachedMask = renderer.copyColorKeyBlackBitmap(1)
            try {
                assertEquals(0, cachedMask.getPixel(0, 0) ushr 24)
                assertEquals(255, cachedMask.getPixel(1, 0) ushr 24)
            } finally {
                cachedMask.recycle()
            }
        } finally {
            renderer.close()
        }
    }

    @Test
    fun gmodeFourUsesAReusableCurrentColorKeyBitmap() {
        val renderer = Hsp3CanvasRenderer({ ByteArrayInputStream(byteArrayOf()) }, 6, 4)
        try {
            prepareBlackAndGreenSource(renderer)
            renderer.gsel(0)
            renderer.color(255, 0, 0)
            renderer.boxf(0, 0, 5, 3)
            renderer.color(0, 0, 0)
            renderer.gmode(4, 2, 2, 256)

            renderer.gcopy(1, 0, 0, 2, 2, 0, 0)
            // Inspect the prepared mask directly: Robolectric's Canvas sampling
            // can differ at bitmap edges even when the alpha mask is exact.
            val initialMask = renderer.copyColorKeyCurrentBitmap(1, Color.BLACK)
            try {
                assertEquals(0, initialMask.getPixel(0, 0) ushr 24)
                assertEquals(255, initialMask.getPixel(1, 1) ushr 24)
            } finally {
                initialMask.recycle()
            }
            assertEquals(1, renderer.currentColorKeyCacheBuildCount)

            renderer.pos(2, 0)
            renderer.gzoom(2, 2, 1, 0, 0, 2, 2, 0)
            assertEquals(1, renderer.currentColorKeyCacheBuildCount)

            val blackMask = renderer.copyColorKeyCurrentBitmap(1, Color.BLACK)
            try {
                assertEquals(0, blackMask.getPixel(0, 0) ushr 24)
                assertEquals(255, blackMask.getPixel(1, 1) ushr 24)
            } finally {
                blackMask.recycle()
            }

            // The mode-4 cache is bounded to one current key per source. A key
            // color change replaces rather than accumulating another bitmap.
            renderer.color(0, 0, 255)
            renderer.gcopy(1, 0, 0, 2, 2, 4, 0)
            assertEquals(2, renderer.currentColorKeyCacheBuildCount)

            // Mutating the source invalidates that current-key bitmap.
            renderer.gsel(1)
            renderer.color(0, 0, 255)
            renderer.boxf(0, 1, 0, 1)
            renderer.gsel(0)
            renderer.color(0, 0, 0)
            renderer.gcopy(1, 0, 0, 2, 2, 4, 2)
            assertEquals(3, renderer.currentColorKeyCacheBuildCount)
            // Changing keys and writing the source refresh one allocated bitmap
            // and one reusable pixel scratch array rather than allocating per use.
            assertEquals(1, renderer.colorKeyBitmapAllocationCount)
            assertEquals(1, renderer.colorKeyPixelScratchAllocationCount)
        } finally {
            renderer.close()
        }
    }

    @Test
    fun gcopyModeTwoCachesNonBlackSourcePixelsAsOpaque() {
        val renderer = Hsp3CanvasRenderer({ ByteArrayInputStream(byteArrayOf()) }, 6, 4)
        try {
            prepareBlackAndGreenSource(renderer)
            assertSurfacePixel(renderer, 1, 0, 255, 0, 1, 1)
            renderer.gsel(0)
            renderer.gmode(2, 2, 2, 0)
            renderer.gcopy(1, 0, 0, 2, 2, 0, 0)
            assertCachedPixel(renderer, 1, 0, 255, 0, 1, 1)
            assertEquals(1, renderer.colorKeyCacheBuildCount)
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
    fun gzoomModeTwoReusesOpaqueNonBlackSourcePixelsFromTheSameCache() {
        val renderer = Hsp3CanvasRenderer({ ByteArrayInputStream(byteArrayOf()) }, 6, 4)
        try {
            prepareBlackAndGreenSource(renderer)
            renderer.gsel(0)
            renderer.gmode(2, 2, 2, 0)
            renderer.pos(2, 0)
            renderer.gzoom(4, 4, 1, 0, 0, 2, 2, 0)
            assertCachedPixel(renderer, 1, 0, 255, 0, 1, 1)
            assertEquals(1, renderer.colorKeyCacheBuildCount)
        } finally { renderer.close() }
    }

    @Test
    fun gmodeTwoReusesCachedMaskAndInvalidatesItWhenSourceChanges() {
        val renderer = Hsp3CanvasRenderer({ ByteArrayInputStream(byteArrayOf()) }, 6, 4)
        try {
            prepareBlackAndGreenSource(renderer)
            assertSurfacePixel(renderer, 1, 0, 255, 0, 1, 1)
            renderer.gsel(0)
            renderer.gmode(2, 2, 2, 0)
            renderer.gcopy(1, 0, 0, 2, 2, 0, 0)
            assertEquals(1, renderer.colorKeyCacheBuildCount)
            assertCachedPixel(renderer, 1, 0, 255, 0, 1, 1)

            renderer.pos(2, 0)
            renderer.gzoom(4, 4, 1, 0, 0, 2, 2, 0)
            assertEquals(1, renderer.colorKeyCacheBuildCount)

            renderer.gsel(1)
            renderer.color(0, 0, 255)
            renderer.boxf(0, 0, 0, 0)
            renderer.gsel(0)
            renderer.gcopy(1, 0, 0, 2, 2, 4, 0)
            assertEquals(2, renderer.colorKeyCacheBuildCount)
            assertEquals(1, renderer.colorKeyBitmapAllocationCount)
            assertEquals(1, renderer.colorKeyPixelScratchAllocationCount)
            assertCachedPixel(renderer, 1, 0, 0, 255, 0, 0)
        } finally { renderer.close() }
    }

    @Test
    fun arithmeticBlendReusesSourceSnapshotAndDestinationScratchUntilSourceChanges() {
        val renderer = Hsp3CanvasRenderer({ ByteArrayInputStream(byteArrayOf()) }, 6, 4)
        try {
            prepareBlackAndGreenSource(renderer)
            renderer.gsel(0)
            renderer.gmode(5, 2, 2, 128)
            renderer.gcopy(1, 0, 0, 2, 2, 0, 0)
            renderer.gcopy(1, 0, 0, 2, 2, 2, 0)
            assertEquals(1, renderer.arithmeticSourceSnapshotBuildCount)
            assertEquals(1, renderer.arithmeticScratchAllocationCount)

            renderer.gsel(1)
            renderer.color(0, 0, 255)
            renderer.boxf(0, 0, 0, 0)
            renderer.gsel(0)
            renderer.gcopy(1, 0, 0, 2, 2, 4, 0)
            assertEquals(2, renderer.arithmeticSourceSnapshotBuildCount)
            assertEquals(1, renderer.arithmeticScratchAllocationCount)
        } finally {
            renderer.close()
        }
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

    @Test
    fun picloadWhileRedrawIsOffPreservesTheSuspendedFrameSnapshot() {
        val image = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val encoded = try {
            image.setPixel(0, 0, 0xFF0000FF.toInt())
            java.io.ByteArrayOutputStream().use { output ->
                image.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
        } finally {
            image.recycle()
        }
        val renderer = Hsp3CanvasRenderer({ ByteArrayInputStream(encoded) }, 4, 4)
        val target = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        try {
            renderer.screen(0, 4, 4)
            renderer.color(255, 0, 0)
            renderer.boxf(0, 0, 3, 3)
            renderer.redraw(false)
            renderer.color(0, 255, 0)
            renderer.boxf(0, 0, 3, 3)

            renderer.picload("replacement.png", 0)
            renderer.draw(android.graphics.Canvas(target))
            assertEquals(0xFFFF0000.toInt(), target.getPixel(1, 1))
        } finally {
            renderer.close()
            target.recycle()
        }
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

    private fun assertCachedPixel(
        renderer: Hsp3CanvasRenderer,
        surfaceId: Int,
        red: Int,
        green: Int,
        blue: Int,
        x: Int,
        y: Int
    ) {
        val bitmap = renderer.copyColorKeyBlackBitmap(surfaceId)
        try {
            val expected = 0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
            assertEquals(expected, bitmap.getPixel(x, y))
        } finally { bitmap.recycle() }
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
