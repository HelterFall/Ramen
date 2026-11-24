package io.github.helterfall.ramen.runtime.livemaker

import io.github.helterfall.ramen.inspection.ReadOnlyRandomAccessSource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.DeflaterOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LiveMakerGalDecoderTest {
    @Test
    fun decodesZlibColorAndAlphaPlanesFromClassicGal() {
        val colorPlane = byteArrayOf(
            3, 2, 1, 6, 5, 4, 0, 0 // Two BGR pixels, then row-alignment padding.
        )
        val alphaPlane = byteArrayOf(
            0x40, 0xC8.toByte(), 0, 0 // Alpha rows are aligned to four bytes.
        )
        val gal = classicGal(
            width = 2,
            height = 1,
            bpp = 24,
            compression = 0,
            blockWidth = 0,
            blockHeight = 0,
            layers = listOf(
                FixtureLayer(
                    visible = true,
                    alphaOn = true,
                    colorPayload = zlib(colorPlane),
                    alphaPayload = zlib(alphaPlane)
                ),
                FixtureLayer(
                    visible = false,
                    alphaOn = false,
                    colorPayload = zlib(colorPlane),
                    alphaPayload = null
                )
            )
        )

        val image = LiveMakerGalDecoder().decode(ByteArraySource(gal))

        assertEquals(2, image.width)
        assertEquals(1, image.height)
        assertEquals(106, image.version)
        assertEquals(1, image.frameCount)
        assertEquals(2, image.layerCount)
        assertEquals(0, image.selectedLayerIndex)
        assertArrayEquals(
            intArrayOf(0x40010203, 0xC8040506.toInt()),
            image.copyArgbPixels()
        )
    }

    @Test
    fun decodesUncompressedBlockReferencesAndPalettePixels() {
        val palette = ByteArray(256 * 4)
        // Classic GAL palette entries are B, G, R, unused.
        palette[2] = 0xFF.toByte() // Palette index 0: red.
        palette[4 + 1] = 0xFF.toByte() // Palette index 1: green.

        val packed = ByteArrayOutputStream().also { output ->
            writeInt(output, -1)
            writeInt(output, 0)
            writeInt(output, -1)
            writeInt(output, 0)
            output.write(0) // First 1x1 block: palette index 0.
            output.write(1) // Second 1x1 block: palette index 1.
        }.toByteArray()

        val gal = classicGal(
            width = 2,
            height = 1,
            bpp = 8,
            compression = 1,
            blockWidth = 1,
            blockHeight = 1,
            palette = palette,
            layers = listOf(
                FixtureLayer(
                    visible = true,
                    alphaOn = false,
                    colorPayload = packed,
                    alphaPayload = null
                )
            )
        )

        val image = LiveMakerGalDecoder().decode(ByteArraySource(gal))
        assertArrayEquals(
            intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt()),
            image.copyArgbPixels()
        )
    }

    @Test
    fun decodesGalX200XmlHeaderAndRgbPalette() {
        val xml = """
            <Frames Width="2" Height="1" Bpp="24" Version="200" Count="1"
                Randomized="0" CompType="1" BGColor="0" BlockWidth="0" BlockHeight="0">
                <Frame Name="frame0">
                    <Layers Count="1" Width="2" Height="1" Bpp="24">
                        <Layer Left="0" Top="0" Visible="1" Alpha="255" AlphaOn="1" TransColor="-1"/>
                    </Layers>
                </Frame>
            </Frames>
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val colorPlane = byteArrayOf(30, 20, 10, 60, 50, 40, 0, 0)
        val alphaPlane = byteArrayOf(255.toByte(), 128.toByte(), 0, 0)
        val galX = ByteArrayOutputStream().also { output ->
            output.write("GaleX200".toByteArray(Charsets.US_ASCII))
            writeInt(output, zlib(xml).size)
            output.write(zlib(xml))
            writeInt(output, colorPlane.size)
            output.write(colorPlane)
            writeInt(output, alphaPlane.size)
            output.write(alphaPlane)
        }.toByteArray()

        val image = LiveMakerGalDecoder().decode(ByteArraySource(galX))
        assertEquals(200, image.version)
        assertArrayEquals(
            intArrayOf(0xFF0A141E.toInt(), 0x8028323C.toInt()),
            image.copyArgbPixels()
        )
    }

    @Test
    fun decodesGalX200Cp932HeaderAndToleratesDuplicateFrameAttributes() {
        // GAL/X200: 8-byte signature, LE compressed XML length at offset 8,
        // ZLIB XML bytes beginning at offset 12, then length-prefixed layer data.
        val cp932 = java.nio.charset.Charset.forName("windows-31j")
        val xml = """
            <Frames Title="背景" Width="2" Height="1" Bpp="24" Version="200" Count="1"
                Randomized="0" CompType="1" BGColor="0" BlockWidth="0" BlockHeight="0">
                <Frame Name="背景" Name="重複">
                    <Layers Count="1" Width="2" Height="1" Bpp="24">
                        <Layer Name="背景画像" Left="0" Top="0" Visible="1"
                            Alpha="255" AlphaOn="1" TransColor="-1"/>
                    </Layers>
                </Frame>
            </Frames>
        """.trimIndent().toByteArray(cp932)
        val compressedXml = zlib(xml)
        val colorPlane = byteArrayOf(30, 20, 10, 60, 50, 40, 0, 0)
        val alphaPlane = byteArrayOf(255.toByte(), 128.toByte(), 0, 0)
        val galX = ByteArrayOutputStream().also { output ->
            output.write("GaleX200".toByteArray(Charsets.US_ASCII))
            writeInt(output, compressedXml.size)
            output.write(compressedXml)
            writeInt(output, colorPlane.size)
            output.write(colorPlane)
            writeInt(output, alphaPlane.size)
            output.write(alphaPlane)
        }.toByteArray()

        val image = LiveMakerGalDecoder().decode(ByteArraySource(galX))

        assertEquals(200, image.version)
        assertEquals(2, image.width)
        assertEquals(1, image.height)
        assertArrayEquals(
            intArrayOf(0xFF0A141E.toInt(), 0x8028323C.toInt()),
            image.copyArgbPixels()
        )
    }

    @Test
    fun rejectsTruncatedAndRandomizedImages() {
        val short = byteArrayOf('G'.code.toByte(), 'a'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(), '1'.code.toByte())
        assertThrows(IOException::class.java) {
            LiveMakerGalDecoder().decode(ByteArraySource(short))
        }

        val randomized = classicGal(
            width = 1,
            height = 1,
            bpp = 24,
            compression = 1,
            blockWidth = 0,
            blockHeight = 0,
            randomized = true,
            layers = listOf(
                FixtureLayer(true, false, byteArrayOf(1, 2, 3, 0), null)
            )
        )
        assertThrows(IOException::class.java) {
            LiveMakerGalDecoder().decode(ByteArraySource(randomized))
        }
    }

    @Test
    fun rejectsDecompressionBombAndOutOfRangeBlockReference() {
        val largePlane = ByteArray(4096) { 0x55 }
        val oversized = classicGal(
            width = 64,
            height = 64,
            bpp = 24,
            compression = 0,
            blockWidth = 0,
            blockHeight = 0,
            layers = listOf(FixtureLayer(true, false, zlib(largePlane), null))
        )
        assertThrows(IOException::class.java) {
            LiveMakerGalDecoder(maxDecodedPixels = 1024L).decode(ByteArraySource(oversized))
        }

        val badBlocks = ByteArrayOutputStream().also { output ->
            writeInt(output, -2)
            writeInt(output, 0)
            output.write(ByteArray(3))
        }.toByteArray()
        val invalidReference = classicGal(
            width = 1,
            height = 1,
            bpp = 24,
            compression = 1,
            blockWidth = 1,
            blockHeight = 1,
            layers = listOf(FixtureLayer(true, false, badBlocks, null))
        )
        assertThrows(IOException::class.java) {
            LiveMakerGalDecoder().decode(ByteArraySource(invalidReference))
        }
    }

    private data class FixtureLayer(
        val visible: Boolean,
        val alphaOn: Boolean,
        val colorPayload: ByteArray,
        val alphaPayload: ByteArray?
    )

    private fun classicGal(
        width: Int,
        height: Int,
        bpp: Int,
        compression: Int,
        blockWidth: Int,
        blockHeight: Int,
        palette: ByteArray? = null,
        randomized: Boolean = false,
        layers: List<FixtureLayer>
    ): ByteArray {
        val output = ByteArrayOutputStream()
        output.write("Gale106".toByteArray(Charsets.US_ASCII))
        writeInt(output, 0x28)

        val header = ByteArray(0x28)
        putInt(header, 0, 106)
        putInt(header, 4, width)
        putInt(header, 8, height)
        putInt(header, 12, bpp)
        putInt(header, 16, 1)
        header[0x15] = if (randomized) 1 else 0
        header[0x16] = compression.toByte()
        putInt(header, 0x18, 0)
        putInt(header, 0x1C, blockWidth)
        putInt(header, 0x20, blockHeight)
        output.write(header)

        writeInt(output, 0) // Empty frame name.
        writeInt(output, 0) // Mask.
        output.write(ByteArray(9))
        writeInt(output, layers.size)
        writeInt(output, width)
        writeInt(output, height)
        writeInt(output, bpp)
        palette?.let(output::write)

        layers.forEach { layer ->
            writeInt(output, 0) // left
            writeInt(output, 0) // top
            output.write(if (layer.visible) 1 else 0)
            writeInt(output, -1) // no transparent-color key
            writeInt(output, 255) // layer opacity
            output.write(if (layer.alphaOn) 1 else 0)
            writeInt(output, 0) // empty layer name
            writeInt(output, layer.colorPayload.size)
            output.write(layer.colorPayload)
            val alphaPayload = layer.alphaPayload
            writeInt(output, alphaPayload?.size ?: 0)
            if (alphaPayload != null) output.write(alphaPayload)
        }
        return output.toByteArray()
    }

    private fun zlib(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { output ->
            DeflaterOutputStream(output).use { it.write(bytes) }
        }.toByteArray()

    private fun writeInt(output: ByteArrayOutputStream, value: Int) {
        repeat(4) { index ->
            output.write((value ushr (index * 8)) and 0xFF)
        }
    }

    private fun putInt(bytes: ByteArray, offset: Int, value: Int) {
        repeat(4) { index ->
            bytes[offset + index] = ((value ushr (index * 8)) and 0xFF).toByte()
        }
    }

    private class ByteArraySource(private val bytes: ByteArray) : ReadOnlyRandomAccessSource {
        override val sizeBytes: Long
            get() = bytes.size.toLong()

        override fun readAt(
            position: Long,
            target: ByteArray,
            targetOffset: Int,
            length: Int
        ): Int {
            require(position >= 0L)
            require(targetOffset >= 0 && targetOffset <= target.size)
            require(length >= 0 && length <= target.size - targetOffset)
            if (length == 0) return 0
            if (position >= bytes.size.toLong()) return -1
            val offset = position.toInt()
            val count = minOf(length, bytes.size - offset)
            System.arraycopy(bytes, offset, target, targetOffset, count)
            return count
        }

        override fun close() = Unit
    }
}
