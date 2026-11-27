package io.github.helterfall.ramen.runtime.hsp3

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Hsp3CanvasBmpTest {
    @Test
    fun drawPresentsScreenZeroInsteadOfTheCurrentlySelectedOffscreenBuffer() {
        val renderer = Hsp3CanvasRenderer({ null }, logicalWidth = 2, logicalHeight = 2)
        val target = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try {
            renderer.screen(0, 2, 2)
            renderer.color(0, 255, 0)
            renderer.boxf(0, 0, 1, 1)

            renderer.buffer(1, 2, 2)
            renderer.gsel(1)
            renderer.color(255, 0, 0)
            renderer.boxf(0, 0, 1, 1)

            renderer.draw(Canvas(target))
            assertEquals(Color.GREEN, target.getPixel(0, 0))

            // redraw state belongs to the selected Bmscr; locking an offscreen
            // buffer must not hide or clear the visible screen window.
            renderer.redraw(false)
            target.eraseColor(Color.BLACK)
            renderer.draw(Canvas(target))
            assertEquals(Color.GREEN, target.getPixel(0, 0))

            renderer.gsel(0)
            renderer.redraw(false)
            target.eraseColor(Color.BLACK)
            renderer.draw(Canvas(target))
            assertEquals(Color.BLACK, target.getPixel(0, 0))
            renderer.redraw(true)
        } finally {
            target.recycle()
            renderer.close()
        }
    }

    @Test
    fun mesAdvancesCursorBetweenCallsAndHonorsNoCrAcrossMultilineText() {
        val renderer = Hsp3CanvasRenderer({ null }, logicalWidth = 160, logicalHeight = 100)
        try {
            renderer.font("sans-serif", 18, 0)
            renderer.pos(7, 9)
            renderer.mes("first")
            val afterFirst = renderer.currentPosition()
            assertEquals(7, afterFirst.first)
            assertTrue(afterFirst.second > 9)

            renderer.mes("second")
            val afterSecond = renderer.currentPosition()
            assertEquals(7, afterSecond.first)
            assertTrue(afterSecond.second > afterFirst.second)

            renderer.pos(11, 13)
            renderer.mes("a\r\nlonger", 1) // OpenHSP HSPMES_NOCR
            val noCrPosition = renderer.currentPosition()
            assertEquals(13, noCrPosition.second)
            assertTrue(noCrPosition.first > 11)
        } finally {
            renderer.close()
        }
    }

    @Test
    fun picloadLoadsEightBitPalettedBmpWithBgrxPaletteEntries() {
        val red = 0xFFFF0000.toInt()
        val green = 0xFF00FF00.toInt()
        val blue = 0xFF0000FF.toInt()
        val yellow = 0xFFFFFF00.toInt()
        val bmp = createBmp(
            bitsPerPixel = 8,
            topRows = listOf(listOf(red, green), listOf(blue, yellow)),
            palette = listOf(red, green, blue, yellow),
            topDown = false
        )
        val renderer = Hsp3CanvasRenderer(
            openAsset = { name -> if (name == "continue.bmp") ByteArrayInputStream(bmp) else null },
            logicalWidth = 2,
            logicalHeight = 2
        )
        try {
            renderer.picload("continue.bmp")
            val snapshot = renderer.copySurfaceBitmap(0)
            try {
                assertTrue(snapshot.isMutable)
                assertEquals(Bitmap.Config.ARGB_8888, snapshot.config)
                assertEquals(red, snapshot.getPixel(0, 0))
                assertEquals(green, snapshot.getPixel(1, 0))
                assertEquals(blue, snapshot.getPixel(0, 1))
                assertEquals(yellow, snapshot.getPixel(1, 1))
            } finally {
                snapshot.recycle()
            }
        } finally {
            renderer.close()
        }
    }

    @Test
    fun picloadLoadsTopDownTwentyFourBitBmp() {
        val cyan = 0xFF00FFFF.toInt()
        val magenta = 0xFFFF00FF.toInt()
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        val bmp = createBmp(
            bitsPerPixel = 24,
            topRows = listOf(listOf(cyan, magenta), listOf(black, white)),
            palette = emptyList(),
            topDown = true
        )
        val renderer = Hsp3CanvasRenderer(
            openAsset = { name -> if (name == "next.bmp") ByteArrayInputStream(bmp) else null },
            logicalWidth = 2,
            logicalHeight = 2
        )
        try {
            renderer.picload("next.bmp")
            val snapshot = renderer.copySurfaceBitmap(0)
            try {
                assertTrue(snapshot.isMutable)
                assertEquals(Bitmap.Config.ARGB_8888, snapshot.config)
                assertEquals(cyan, snapshot.getPixel(0, 0))
                assertEquals(magenta, snapshot.getPixel(1, 0))
                assertEquals(black, snapshot.getPixel(0, 1))
                assertEquals(white, snapshot.getPixel(1, 1))
            } finally {
                snapshot.recycle()
            }
        } finally {
            renderer.close()
        }
    }

    private fun createBmp(
        bitsPerPixel: Int,
        topRows: List<List<Int>>,
        palette: List<Int>,
        topDown: Boolean
    ): ByteArray {
        val width = topRows.first().size
        val height = topRows.size
        val bytesPerPixel = bitsPerPixel / 8
        val rowStride = ((width * bitsPerPixel + 31) / 32) * 4
        val paletteOffset = 14 + 40
        val pixelOffset = paletteOffset + palette.size * 4
        val bytes = ByteArray(pixelOffset + rowStride * height)
        bytes[0] = 'B'.code.toByte()
        bytes[1] = 'M'.code.toByte()
        putU32(bytes, 2, bytes.size)
        putU32(bytes, 10, pixelOffset)
        putU32(bytes, 14, 40)
        putU32(bytes, 18, width)
        putU32(bytes, 22, if (topDown) -height else height)
        putU16(bytes, 26, 1)
        putU16(bytes, 28, bitsPerPixel)
        putU32(bytes, 34, rowStride * height)
        if (palette.isNotEmpty()) putU32(bytes, 46, palette.size)
        palette.forEachIndexed { index, color ->
            val position = paletteOffset + index * 4
            bytes[position] = color.toByte()
            bytes[position + 1] = (color ushr 8).toByte()
            bytes[position + 2] = (color ushr 16).toByte()
            bytes[position + 3] = 0xA7.toByte()
        }
        topRows.forEachIndexed { y, row ->
            val fileY = if (topDown) y else height - 1 - y
            val rowStart = pixelOffset + fileY * rowStride
            row.forEachIndexed { x, color ->
                if (bitsPerPixel == 8) {
                    val index = palette.indexOf(color)
                    require(index >= 0)
                    bytes[rowStart + x] = index.toByte()
                } else {
                    val position = rowStart + x * bytesPerPixel
                    bytes[position] = color.toByte()
                    bytes[position + 1] = (color ushr 8).toByte()
                    bytes[position + 2] = (color ushr 16).toByte()
                }
            }
        }
        return bytes
    }

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun putU32(bytes: ByteArray, offset: Int, value: Int) {
        repeat(4) { index -> bytes[offset + index] = (value ushr (index * 8)).toByte() }
    }
}
