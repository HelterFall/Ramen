package io.github.helterfall.ramen.runtime.hsp3

import android.graphics.Bitmap
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Hsp3CanvasBlendTest {
    @Test
    fun gmodeFiveAddsSourceChannelsScaledByAlphaAndClampsAt255() {
        val renderer = Hsp3CanvasRenderer({ ByteArrayInputStream(byteArrayOf()) }, 1, 1)
        try {
            renderer.screen(0, 1, 1)
            renderer.buffer(1, 1, 1)
            renderer.gsel(1)
            renderer.color(100, 200, 255)
            renderer.boxf(0, 0, 0, 0)
            renderer.gsel(0)
            renderer.color(200, 80, 10)
            renderer.boxf(0, 0, 0, 0)
            assertSurfacePixel(renderer, 1, 100, 200, 255)
            assertSurfacePixel(renderer, 0, 200, 80, 10)
            renderer.gmode(5, 1, 1, 128)
            renderer.gcopy(1, 0, 0, 1, 1, 0, 0)
            assertPixel(renderer, 250, 180, 137)
        } finally { renderer.close() }
    }

    @Test
    fun gmodeSixSubtractsSourceChannelsScaledByAlphaAndClampsAtZero() {
        val renderer = Hsp3CanvasRenderer({ ByteArrayInputStream(byteArrayOf()) }, 1, 1)
        try {
            renderer.screen(0, 1, 1)
            renderer.buffer(1, 1, 1)
            renderer.gsel(1)
            renderer.color(100, 200, 255)
            renderer.boxf(0, 0, 0, 0)
            renderer.gsel(0)
            renderer.color(200, 80, 10)
            renderer.boxf(0, 0, 0, 0)
            assertSurfacePixel(renderer, 1, 100, 200, 255)
            assertSurfacePixel(renderer, 0, 200, 80, 10)
            renderer.gmode(6, 1, 1, 128)
            renderer.gcopy(1, 0, 0, 1, 1, 0, 0)
            assertPixel(renderer, 150, 0, 0)
        } finally { renderer.close() }
    }

    private fun assertSurfacePixel(renderer: Hsp3CanvasRenderer, surfaceId: Int, red: Int, green: Int, blue: Int) {
        val bitmap = renderer.copySurfaceBitmap(surfaceId)
        try {
            val expected = 0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
            assertEquals("surface $surfaceId source/destination pixel", expected, bitmap.getPixel(0, 0))
        } finally { bitmap.recycle() }
    }

    private fun assertPixel(renderer: Hsp3CanvasRenderer, red: Int, green: Int, blue: Int) {
        val bitmap = renderer.copySurfaceBitmap(0)
        try {
            assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
            val expected = 0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
            val actual = bitmap.getPixel(0, 0)
            assertEquals("expected 0x${expected.toUInt().toString(16)}, actual 0x${actual.toUInt().toString(16)}",
                expected, actual)
        } finally { bitmap.recycle() }
    }
}
