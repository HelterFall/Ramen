package io.github.helterfall.ramen.runtime.livemaker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.helterfall.ramen.inspection.ReadOnlyRandomAccessSource
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.nio.charset.Charset
import java.io.IOException
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * Decoded first frame of a LiveMaker GAL/GAL-X image.
 *
 * Pixels are stored as Android ARGB_8888 values. The array returned by
 * [copyArgbPixels] is independent from this image.
 */
class LiveMakerGalImage internal constructor(
    val width: Int,
    val height: Int,
    val version: Int,
    val frameCount: Int,
    val layerCount: Int,
    val selectedLayerIndex: Int,
    argbPixels: IntArray
) {
    private val pixels = argbPixels.copyOf()

    fun copyArgbPixels(): IntArray = pixels.copyOf()

    /** Materializes the decoded pixels as an Android ARGB_8888 bitmap. */
    @Throws(IOException::class)
    fun toBitmap(): Bitmap {
        val bitmap = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (failure: RuntimeException) {
            throw IOException("Could not allocate the decoded GAL bitmap.", failure)
        }

        try {
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        } catch (failure: Exception) {
            bitmap.recycle()
            throw IOException("Could not copy decoded GAL pixels into the bitmap.", failure)
        }
        return bitmap
    }
}

/**
 * Bounded read-only decoder for the documented LiveMaker GAL 103..107 and GAL/X200 formats.
 *
 * The decoder reads the first frame. It parses and unpacks the frame's layers, then returns
 * the first visible layer, matching the current first-layer behavior in the public reference
 * readers. Transparent-color keys, layer-origin compositing, animation playback, and
 * randomized/encrypted Pro assets are intentionally not guessed.
 *
 * Compression modes:
 * - 0: zlib-compressed pixel/block data;
 * - 1: uncompressed pixel/block data;
 * - 2: JPEG color plane, with a zlib alpha plane when present.
 */
class LiveMakerGalDecoder(
    private val maxDecodedPixels: Long = DEFAULT_MAX_DECODED_PIXELS,
    private val maxEncodedLayerBytes: Int = DEFAULT_MAX_ENCODED_LAYER_BYTES,
    private val maxDecodedLayerBytes: Long = DEFAULT_MAX_DECODED_LAYER_BYTES,
    private val maxXmlBytes: Int = DEFAULT_MAX_XML_BYTES
) {
    init {
        require(maxDecodedPixels > 0L)
        require(maxEncodedLayerBytes > 0)
        require(maxDecodedLayerBytes > 0L && maxDecodedLayerBytes <= Int.MAX_VALUE.toLong())
        require(maxXmlBytes > 0)
    }

    private data class Metadata(
        val version: Int,
        val frameCount: Int,
        val compression: Int,
        val randomized: Boolean,
        val blockWidth: Int,
        val blockHeight: Int
    )

    private data class LayerRecord(
        val visible: Boolean,
        val opacity: Int,
        val alphaOn: Boolean,
        val colorPayload: ByteArray,
        val alphaPayload: ByteArray?
    )

    private data class FrameDescription(
        val width: Int,
        val height: Int,
        val bitsPerPixel: Int,
        val palette: IntArray?,
        val layers: List<LayerRecord>
    )

    private data class DecodedLayer(
        val rawColorPlane: ByteArray?,
        val jpegPixels: IntArray?,
        val alphaPlane: ByteArray?,
        val visible: Boolean,
        val opacity: Int
    )

    /**
     * Decodes a GAL image located in [source] at [offsetBytes].
     *
     * [lengthBytes] is the exact file range belonging to the GAL image. This allows
     * decoding a GAL entry directly from a containing resource file without extraction.
     */
    @Throws(IOException::class)
    fun decode(
        source: ReadOnlyRandomAccessSource,
        offsetBytes: Long = 0L,
        lengthBytes: Long = source.sizeBytes - offsetBytes
    ): LiveMakerGalImage {
        if (offsetBytes < 0L || lengthBytes <= 0L ||
            offsetBytes > source.sizeBytes ||
            lengthBytes > source.sizeBytes - offsetBytes
        ) {
            throw IOException("GAL source range is outside the input.")
        }

        val cursor = Cursor(source, offsetBytes, lengthBytes)
        if (cursor.readAscii(4) != "Gale") {
            throw IOException("GAL signature is not Gale.")
        }

        val firstVersionByte = cursor.readAscii(1)
        return if (firstVersionByte == "X") {
            val suffix = cursor.readAscii(3)
            if (suffix != "200") throw IOException("Unsupported GAL/X version.")
            decodeGalX(cursor)
        } else {
            val versionText = firstVersionByte + cursor.readAscii(2)
            val version = versionText.toIntOrNull()
                ?: throw IOException("GAL version marker is invalid.")
            decodeClassicGal(cursor, version)
        }
    }

    /** Decodes the image and creates its Android bitmap. */
    @Throws(IOException::class)
    fun decodeBitmap(
        source: ReadOnlyRandomAccessSource,
        offsetBytes: Long = 0L,
        lengthBytes: Long = source.sizeBytes - offsetBytes
    ): Bitmap = decode(source, offsetBytes, lengthBytes).toBitmap()

    private fun decodeClassicGal(cursor: Cursor, version: Int): LiveMakerGalImage {
        if (version !in MIN_CLASSIC_VERSION..MAX_CLASSIC_VERSION) {
            throw IOException("Unsupported classic GAL version " + version + ".")
        }

        val headerSize = cursor.readInt32()
        if (headerSize !in MIN_CLASSIC_HEADER_BYTES..MAX_CLASSIC_HEADER_BYTES) {
            throw IOException("GAL header length is outside the supported range.")
        }
        val header = ByteCursor(cursor.readBytes(headerSize))
        if (header.readInt32() != version) {
            throw IOException("GAL header version disagrees with its signature.")
        }

        val headerWidth = header.readInt32()
        val headerHeight = header.readInt32()
        val headerBpp = header.readInt32()
        val frameCount = header.readInt32()
        if (headerWidth <= 0 || headerHeight <= 0 || headerBpp <= 0 || frameCount <= 0) {
            throw IOException("GAL header contains invalid image dimensions or frame count.")
        }
        val randomized = header.readU8At(0x15) != 0
        val compression = header.readU8At(0x16)
        header.readInt32At(0x18) // Background/mask color is metadata, not a decoded pixel plane.
        val blockWidth = header.readInt32At(0x1C)
        val blockHeight = header.readInt32At(0x20)
        val metadata = validateMetadata(
            Metadata(version, frameCount, compression, randomized, blockWidth, blockHeight)
        )
        if (metadata.randomized) {
            throw IOException("Randomized/encrypted LiveMaker GAL assets are not supported.")
        }

        // The classic GAL data section begins with the first frame record.
        val frameNameLength = cursor.readCount("GAL frame name length", MAX_NAME_BYTES)
        cursor.skip(frameNameLength.toLong())
        cursor.readInt32() // Frame mask.
        cursor.skip(9L)

        val layerCount = cursor.readInt32()
        if (layerCount !in 1..MAX_LAYERS) {
            throw IOException("GAL frame layer count is outside the supported range.")
        }
        val width = cursor.readInt32()
        val height = cursor.readInt32()
        val bpp = cursor.readInt32()
        validateDimensions(width, height, bpp)

        val palette = if (bpp <= 8) {
            readClassicPalette(cursor, 1 shl bpp)
        } else {
            null
        }

        val layers = ArrayList<LayerRecord>(layerCount)
        repeat(layerCount) { index ->
            cursor.readInt32() // Layer left; origin compositing is not part of this decoder.
            cursor.readInt32() // Layer top.
            val visible = cursor.readU8() != 0
            cursor.readInt32() // Transparent-color key; not applied by the reference readers.
            val opacity = cursor.readInt32()
            if (opacity !in 0..255) {
                throw IOException("GAL layer opacity is outside the 0..255 range.")
            }
            val alphaOn = cursor.readU8() != 0
            val layerNameLength = cursor.readCount("GAL layer name length", MAX_NAME_BYTES)
            cursor.skip(layerNameLength.toLong())
            if (version >= 107) cursor.readU8() // Layer lock flag.

            val colorSize = cursor.readCount("GAL layer byte length", maxEncodedLayerBytes)
            if (colorSize == 0) throw IOException("GAL layer has an empty color payload.")
            val colorPayload = cursor.readBytes(colorSize)
            val alphaSize = cursor.readCount("GAL alpha byte length", maxEncodedLayerBytes)
            val alphaPayload = if (alphaSize > 0) cursor.readBytes(alphaSize) else null
            layers += LayerRecord(visible, opacity, alphaOn, colorPayload, alphaPayload)
        }

        val description = FrameDescription(width, height, bpp, palette, layers)
        return decodeFrame(metadata, description)
    }

    private fun decodeGalX(cursor: Cursor): LiveMakerGalImage {
        val headerSize = cursor.readCount("GAL/X XML byte length", maxXmlBytes)
        if (headerSize == 0) throw IOException("GAL/X XML header is empty.")
        val compressedXml = cursor.readBytes(headerSize)
        val xmlBytes = inflateZlib(compressedXml, maxXmlBytes.toLong())
        val root = parseSecureXml(xmlBytes)

        val metadata = validateMetadata(
            Metadata(
                version = readAttributeInt(root, "Version", 200),
                frameCount = readAttributeInt(root, "Count", 1),
                compression = readAttributeInt(root, "CompType", -1),
                randomized = readAttributeInt(root, "Randomized", 0) != 0,
                blockWidth = readAttributeInt(root, "BlockWidth", 0),
                blockHeight = readAttributeInt(root, "BlockHeight", 0)
            )
        )
        if (metadata.randomized) {
            throw IOException("Randomized/encrypted GAL/X assets are not supported.")
        }

        val frame = root.getElementsByTagName("Frame").item(0) as? Element
            ?: throw IOException("GAL/X XML does not contain a frame.")
        val layersElement = frame.getElementsByTagName("Layers").item(0) as? Element
            ?: throw IOException("GAL/X frame does not contain a Layers element.")

        val layerNodes = layersElement.getElementsByTagName("Layer")
        val layerCount = readAttributeInt(layersElement, "Count", layerNodes.length)
        if (layerCount !in 1..MAX_LAYERS || layerNodes.length != layerCount) {
            throw IOException("GAL/X layer count is invalid or disagrees with its XML.")
        }

        val width = readAttributeInt(layersElement, "Width", readAttributeInt(root, "Width", 0))
        val height = readAttributeInt(layersElement, "Height", readAttributeInt(root, "Height", 0))
        val bpp = readAttributeInt(
            layersElement,
            "Bpp",
            readAttributeInt(layersElement, "bpp", readAttributeInt(root, "Bpp", 0))
        )
        validateDimensions(width, height, bpp)

        val palette = if (bpp <= 8) {
            readGalXPalette(layersElement, 1 shl bpp)
        } else {
            null
        }

        val layers = ArrayList<LayerRecord>(layerCount)
        repeat(layerCount) { index ->
            val node = layerNodes.item(index) as? Element
                ?: throw IOException("GAL/X layer XML node is invalid.")
            val visible = readAttributeInt(node, "Visible", 1) != 0
            val alphaOn = readAttributeInt(node, "AlphaOn", 0) != 0
            val opacity = readAttributeInt(node, "Alpha", 255)
            if (opacity !in 0..255) {
                throw IOException("GAL/X layer opacity is outside the 0..255 range.")
            }

            val colorSize = cursor.readCount("GAL/X layer byte length", maxEncodedLayerBytes)
            if (colorSize == 0) throw IOException("GAL/X layer has an empty color payload.")
            val colorPayload = cursor.readBytes(colorSize)
            val alphaPayload = if (alphaOn) {
                val alphaSize = cursor.readCount("GAL/X alpha byte length", maxEncodedLayerBytes)
                if (alphaSize == 0) null else cursor.readBytes(alphaSize)
            } else {
                null
            }
            layers += LayerRecord(visible, opacity, alphaOn, colorPayload, alphaPayload)
        }

        return decodeFrame(
            metadata,
            FrameDescription(width, height, bpp, palette, layers)
        )
    }

    private fun decodeFrame(
        metadata: Metadata,
        frame: FrameDescription
    ): LiveMakerGalImage {
        validateDimensions(frame.width, frame.height, frame.bitsPerPixel)
        val colorStride = rowStride(frame.width, frame.bitsPerPixel)
        val alphaStride = alignedStride(frame.width)
        val colorPlaneBytes = checkedPlaneBytes(colorStride, frame.height)
        val alphaPlaneBytes = checkedPlaneBytes(alphaStride, frame.height)
        val previousColorPlanes = ArrayList<ByteArray?>(frame.layers.size)
        val previousAlphaPlanes = ArrayList<ByteArray?>(frame.layers.size)
        var selectedPixels: IntArray? = null
        var selectedIndex = -1

        frame.layers.forEachIndexed { index, layer ->
            val jpegPixels: IntArray?
            val colorPlane: ByteArray?
            if (metadata.compression == COMPRESSION_JPEG) {
                jpegPixels = decodeJpeg(layer.colorPayload, frame.width, frame.height)
                colorPlane = null
            } else {
                jpegPixels = null
                colorPlane = decodePlane(
                    encoded = layer.colorPayload,
                    width = frame.width,
                    height = frame.height,
                    bitsPerPixel = frame.bitsPerPixel,
                    stride = colorStride,
                    isAlpha = false,
                    metadata = metadata,
                    previousLayers = previousColorPlanes,
                    expectedPlaneBytes = colorPlaneBytes
                )
            }

            val alphaPlane = if (layer.alphaOn && layer.alphaPayload != null) {
                decodePlane(
                    encoded = layer.alphaPayload,
                    width = frame.width,
                    height = frame.height,
                    bitsPerPixel = 8,
                    stride = alphaStride,
                    isAlpha = true,
                    metadata = metadata,
                    previousLayers = previousAlphaPlanes,
                    expectedPlaneBytes = alphaPlaneBytes
                )
            } else {
                null
            }

            previousColorPlanes += colorPlane
            previousAlphaPlanes += alphaPlane

            if (selectedPixels == null && layer.visible) {
                selectedPixels = toArgbPixels(
                    width = frame.width,
                    height = frame.height,
                    bpp = frame.bitsPerPixel,
                    stride = colorStride,
                    colorPlane = colorPlane,
                    jpegPixels = jpegPixels,
                    palette = frame.palette,
                    alphaPlane = alphaPlane,
                    alphaStride = alphaStride,
                    opacity = layer.opacity
                )
                selectedIndex = index
            }
        }

        val pixels = selectedPixels
            ?: throw IOException("GAL frame does not contain a visible layer.")
        return LiveMakerGalImage(
            width = frame.width,
            height = frame.height,
            version = metadata.version,
            frameCount = metadata.frameCount,
            layerCount = frame.layers.size,
            selectedLayerIndex = selectedIndex,
            argbPixels = pixels
        )
    }

    private fun decodePlane(
        encoded: ByteArray,
        width: Int,
        height: Int,
        bitsPerPixel: Int,
        stride: Int,
        isAlpha: Boolean,
        metadata: Metadata,
        previousLayers: List<ByteArray?>,
        expectedPlaneBytes: Long
    ): ByteArray {
        val usesZlib = metadata.compression == COMPRESSION_ZLIB ||
            (metadata.compression == COMPRESSION_JPEG && isAlpha)
        val blocksEnabled = metadata.blockWidth > 0 && metadata.blockHeight > 0
        val blockMapBytes = if (blocksEnabled) {
            val blocksWide = (width.toLong() + metadata.blockWidth - 1L) / metadata.blockWidth
            val blocksHigh = (height.toLong() + metadata.blockHeight - 1L) / metadata.blockHeight
            blocksWide * blocksHigh * BLOCK_REFERENCE_BYTES
        } else {
            0L
        }
        val maxUnpackedBytes = expectedPlaneBytes + blockMapBytes
        if (maxUnpackedBytes <= 0L || maxUnpackedBytes > maxDecodedLayerBytes ||
            maxUnpackedBytes > Int.MAX_VALUE.toLong()
        ) {
            throw IOException("GAL decoded layer exceeds the configured output limit.")
        }

        val unpacked = if (usesZlib) {
            inflateZlib(encoded, maxUnpackedBytes)
        } else {
            encoded
        }

        if (!blocksEnabled) {
            if (unpacked.size.toLong() != expectedPlaneBytes) {
                throw IOException("GAL raw layer size does not match its dimensions and stride.")
            }
            return unpacked
        }

        return unpackBlockPlane(
            packed = unpacked,
            width = width,
            height = height,
            bitsPerPixel = bitsPerPixel,
            stride = stride,
            blockWidth = metadata.blockWidth,
            blockHeight = metadata.blockHeight,
            previousLayers = previousLayers,
            expectedPlaneBytes = expectedPlaneBytes
        )
    }

    private fun unpackBlockPlane(
        packed: ByteArray,
        width: Int,
        height: Int,
        bitsPerPixel: Int,
        stride: Int,
        blockWidth: Int,
        blockHeight: Int,
        previousLayers: List<ByteArray?>,
        expectedPlaneBytes: Long
    ): ByteArray {
        val blocksWide = (width.toLong() + blockWidth - 1L) / blockWidth
        val blocksHigh = (height.toLong() + blockHeight - 1L) / blockHeight
        val blockCountLong = blocksWide * blocksHigh
        if (blockWidth <= 0 || blockHeight <= 0 ||
            blockCountLong <= 0L || blockCountLong > MAX_BLOCKS.toLong()
        ) {
            throw IOException("GAL block dimensions exceed the supported range.")
        }
        val blockCount = blockCountLong.toInt()
        val mapBytes = blockCount.toLong() * BLOCK_REFERENCE_BYTES
        if (mapBytes > packed.size.toLong()) {
            throw IOException("GAL block reference table is truncated.")
        }

        val cursor = ByteCursor(packed)
        val frameRefs = IntArray(blockCount)
        val layerRefs = IntArray(blockCount)
        repeat(blockCount) { index ->
            frameRefs[index] = cursor.readInt32()
            layerRefs[index] = cursor.readInt32()
        }

        val output = ByteArray(expectedPlaneBytes.toInt())
        for (blockIndex in 0 until blockCount) {
            val blockX = blockIndex % blocksWide.toInt()
            val blockY = blockIndex / blocksWide.toInt()
            val x = blockX * blockWidth
            val y = blockY * blockHeight
            val runWidth = minOf(blockWidth, width - x)
            val runHeight = minOf(blockHeight, height - y)
            val chunkBytes = ((runWidth.toLong() * bitsPerPixel + 7L) / 8L).toInt()
            val destination = y * stride + ((x.toLong() * bitsPerPixel + 7L) / 8L).toInt()

            when (frameRefs[blockIndex]) {
                -1 -> {
                    repeat(runHeight) { row ->
                        val bytes = cursor.readBytes(chunkBytes)
                        System.arraycopy(bytes, 0, output, destination + row * stride, chunkBytes)
                    }
                }
                -2 -> {
                    val sourceBlock = layerRefs[blockIndex]
                    if (sourceBlock !in 0 until blockIndex) {
                        throw IOException("GAL block refers to a missing or forward block.")
                    }
                    val sourceX = (sourceBlock % blocksWide.toInt()) * blockWidth
                    val sourceY = (sourceBlock / blocksWide.toInt()) * blockHeight
                    val sourceWidth = minOf(blockWidth, width - sourceX)
                    val sourceHeight = minOf(blockHeight, height - sourceY)
                    if (sourceWidth < runWidth || sourceHeight < runHeight) {
                        throw IOException("GAL block copy has incompatible edge dimensions.")
                    }
                    val sourceOffset = sourceY * stride +
                        ((sourceX.toLong() * bitsPerPixel + 7L) / 8L).toInt()
                    repeat(runHeight) { row ->
                        System.arraycopy(
                            output,
                            sourceOffset + row * stride,
                            output,
                            destination + row * stride,
                            chunkBytes
                        )
                    }
                }
                else -> {
                    val frameReference = frameRefs[blockIndex]
                    val layerReference = layerRefs[blockIndex]
                    if (frameReference != 0 ||
                        layerReference !in previousLayers.indices
                    ) {
                        throw IOException("GAL block references a frame/layer not decoded by this first-frame reader.")
                    }
                    val referencePlane = previousLayers[layerReference]
                        ?: throw IOException("GAL block references a layer without a decoded plane.")
                    repeat(runHeight) { row ->
                        val offset = destination + row * stride
                        if (offset < 0 || offset.toLong() + chunkBytes > referencePlane.size.toLong()) {
                            throw IOException("GAL referenced block extends beyond its source layer.")
                        }
                        System.arraycopy(referencePlane, offset, output, offset, chunkBytes)
                    }
                }
            }
        }

        if (cursor.remaining != 0) {
            throw IOException("GAL block payload has unexpected trailing bytes.")
        }
        return output
    }

    private fun toArgbPixels(
        width: Int,
        height: Int,
        bpp: Int,
        stride: Int,
        colorPlane: ByteArray?,
        jpegPixels: IntArray?,
        palette: IntArray?,
        alphaPlane: ByteArray?,
        alphaStride: Int,
        opacity: Int
    ): IntArray {
        val jpeg = jpegPixels
        val raw = colorPlane
        if (jpeg == null && raw == null) {
            throw IOException("GAL layer has no decoded color plane.")
        }
        if (jpeg != null && jpeg.size != width * height) {
            throw IOException("GAL JPEG decoder returned unexpected dimensions.")
        }
        val paletteValues = palette
        if (bpp <= 8 && paletteValues == null) {
            throw IOException("Indexed GAL image is missing its color palette.")
        }

        val output = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixelIndex = y * width + x
                var argb = if (jpeg != null) {
                    jpeg[pixelIndex]
                } else {
                    val bytes = raw ?: throw IOException("GAL color data is missing.")
                    val row = y * stride
                    when (bpp) {
                        4 -> {
                            val packed = bytes[row + x / 2].toInt() and 0xFF
                            val paletteIndex = if (x and 1 == 0) packed and 0x0F else packed ushr 4
                            paletteValues!![paletteIndexOrFail(paletteIndex, paletteValues.size)]
                        }
                        8 -> {
                            val paletteIndex = bytes[row + x].toInt() and 0xFF
                            paletteValues!![paletteIndexOrFail(paletteIndex, paletteValues.size)]
                        }
                        16 -> {
                            val offset = row + x * 2
                            val value = (bytes[offset].toInt() and 0xFF) or
                                ((bytes[offset + 1].toInt() and 0xFF) shl 8)
                            val blue = (value and 0x1F) * 255 / 31
                            val green = ((value ushr 5) and 0x3F) * 255 / 63
                            val red = ((value ushr 11) and 0x1F) * 255 / 31
                            (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
                        }
                        24 -> {
                            val offset = row + x * 3
                            val blue = bytes[offset].toInt() and 0xFF
                            val green = bytes[offset + 1].toInt() and 0xFF
                            val red = bytes[offset + 2].toInt() and 0xFF
                            (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
                        }
                        32 -> {
                            val offset = row + x * 4
                            val blue = bytes[offset].toInt() and 0xFF
                            val green = bytes[offset + 1].toInt() and 0xFF
                            val red = bytes[offset + 2].toInt() and 0xFF
                            val alpha = bytes[offset + 3].toInt() and 0xFF
                            (alpha shl 24) or (red shl 16) or (green shl 8) or blue
                        }
                        else -> throw IOException("GAL color depth is not supported.")
                    }
                }

                val originalAlpha = (argb ushr 24) and 0xFF
                val alpha = if (alphaPlane != null) {
                    (alphaPlane[y * alphaStride + x].toInt() and 0xFF) * opacity / 255
                } else {
                    originalAlpha * opacity / 255
                }
                argb = (argb and 0x00FFFFFF) or (alpha shl 24)
                output[pixelIndex] = argb
            }
        }
        return output
    }

    private fun decodeJpeg(encoded: ByteArray, width: Int, height: Int): IntArray {
        if (encoded.isEmpty() || encoded.size > maxEncodedLayerBytes) {
            throw IOException("GAL JPEG payload length is outside the configured range.")
        }
        val bitmap = try {
            BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
        } catch (failure: RuntimeException) {
            throw IOException("Android BitmapFactory rejected the GAL JPEG layer.", failure)
        } ?: throw IOException("Android BitmapFactory could not decode the GAL JPEG layer.")

        try {
            if (bitmap.width != width || bitmap.height != height) {
                throw IOException("GAL JPEG dimensions disagree with the frame metadata.")
            }
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            return pixels
        } catch (failure: Exception) {
            if (failure is IOException) throw failure
            throw IOException("Could not read the decoded GAL JPEG pixels.", failure)
        } finally {
            bitmap.recycle()
        }
    }

    private fun readClassicPalette(cursor: Cursor, colorCount: Int): IntArray {
        val bytes = cursor.readBytes(colorCount * PALETTE_ENTRY_BYTES)
        return IntArray(colorCount) { index ->
            val offset = index * PALETTE_ENTRY_BYTES
            val blue = bytes[offset].toInt() and 0xFF
            val green = bytes[offset + 1].toInt() and 0xFF
            val red = bytes[offset + 2].toInt() and 0xFF
            (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
        }
    }

    private fun readGalXPalette(layers: Element, expectedColors: Int): IntArray {
        val rgb = layers.getElementsByTagName("RGB").item(0)?.textContent
            ?: throw IOException("Indexed GAL/X image is missing its RGB palette.")
        val hex = rgb.filterNot { it.isWhitespace() }
        if (hex.length != expectedColors * 6) {
            throw IOException("GAL/X RGB palette length does not match its color depth.")
        }
        return IntArray(expectedColors) { index ->
            val offset = index * 6
            val red = hex.substring(offset, offset + 2).toIntOrNull(16)
                ?: throw IOException("GAL/X RGB palette contains invalid hexadecimal data.")
            val green = hex.substring(offset + 2, offset + 4).toIntOrNull(16)
                ?: throw IOException("GAL/X RGB palette contains invalid hexadecimal data.")
            val blue = hex.substring(offset + 4, offset + 6).toIntOrNull(16)
                ?: throw IOException("GAL/X RGB palette contains invalid hexadecimal data.")
            (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
        }
    }

    private fun parseSecureXml(xml: ByteArray): Element {
        try {
            // GAL/X metadata is a ZLIB payload containing XML bytes. Most game headers are
            // ASCII; use CP932 for non-ASCII text, including Japanese names.
            val xmlText = String(xml, GALX_XML_CHARSET).removePrefix("\uFEFF")
            if (xmlText.contains("<!DOCTYPE", ignoreCase = true) ||
                xmlText.contains("<!ENTITY", ignoreCase = true)
            ) {
                throw IOException("GAL/X XML declarations and external entities are not supported.")
            }

            // Parse the original text first. Some Android XML providers do not implement all
            // Xerces feature URIs; configure those defensively instead of failing valid XML
            // before parsing begins.
            return try {
                parseGalXXmlDocument(xmlText)
            } catch (firstFailure: Exception) {
                // Some LiveMaker builds write duplicate attributes on <Frame>. pylivemaker's
                // recovery parser and GARbro's reader tolerate this nonessential metadata.
                // Retry only after a normal parse fails, leaving well-formed headers untouched.
                val normalizedXml = GALX_FRAME_OPEN_TAG.replace(xmlText, "<Frame>")
                if (normalizedXml == xmlText) throw firstFailure
                try {
                    parseGalXXmlDocument(normalizedXml)
                } catch (recoveryFailure: Exception) {
                    recoveryFailure.addSuppressed(firstFailure)
                    throw recoveryFailure
                }
            }
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Exception) {
            throw IOException(
                "Could not parse the bounded GAL/X XML header: " +
                    (failure.message ?: failure.javaClass.simpleName),
                failure
            )
        }
    }

    private fun parseGalXXmlDocument(xmlText: String): Element {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        // Android's built-in XML provider may reject Xerces-specific feature names. The input
        // is explicitly checked for DTD/entity declarations above, and the builder also gets
        // an empty external-entity resolver, so unsupported optional flags remain safe.
        runCatching { factory.isXIncludeAware = false }
        runCatching { factory.setExpandEntityReferences(false) }
        runCatching { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        runCatching {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        runCatching {
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        }
        runCatching {
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        runCatching {
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }

        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        val document = builder.parse(InputSource(StringReader(xmlText)))
        val root = document.documentElement
            ?: throw IOException("GAL/X XML does not have a root element.")
        if (root.tagName != "Frames") {
            throw IOException("GAL/X XML root element is not Frames.")
        }
        return root
    }
    private fun readAttributeInt(
        element: Element,
        name: String,
        defaultValue: Int
    ): Int {
        if (!element.hasAttribute(name)) return defaultValue
        return element.getAttribute(name).trim().toIntOrNull()
            ?: throw IOException("GAL/X XML attribute " + name + " is not an integer.")
    }

    private fun validateMetadata(metadata: Metadata): Metadata {
        if (metadata.frameCount !in 1..MAX_FRAMES) {
            throw IOException("GAL frame count is outside the supported range.")
        }
        if (metadata.compression !in COMPRESSION_ZLIB..COMPRESSION_JPEG) {
            throw IOException("GAL compression method is not supported.")
        }
        if ((metadata.blockWidth == 0) != (metadata.blockHeight == 0) ||
            metadata.blockWidth < 0 || metadata.blockHeight < 0
        ) {
            throw IOException("GAL block dimensions are inconsistent.")
        }
        return metadata
    }

    private fun validateDimensions(width: Int, height: Int, bpp: Int) {
        if (width <= 0 || height <= 0 || bpp !in SUPPORTED_BPP) {
            throw IOException("GAL frame dimensions or color depth are not supported.")
        }
        val pixels = width.toLong() * height.toLong()
        if (pixels <= 0L || pixels > maxDecodedPixels || pixels > Int.MAX_VALUE.toLong()) {
            throw IOException("GAL pixel count exceeds the configured decode limit.")
        }
        val rowBytes = rowStride(width, bpp).toLong()
        val planeBytes = rowBytes * height.toLong()
        val alphaBytes = alignedStride(width).toLong() * height.toLong()
        if (planeBytes <= 0L || planeBytes > maxDecodedLayerBytes ||
            alphaBytes <= 0L || alphaBytes > maxDecodedLayerBytes
        ) {
            throw IOException("GAL decoded plane exceeds the configured byte limit.")
        }
        if (bpp <= 8 && (1 shl bpp) > MAX_PALETTE_COLORS) {
            throw IOException("GAL palette size exceeds the supported range.")
        }
    }

    private fun checkedPlaneBytes(stride: Int, height: Int): Long {
        val length = stride.toLong() * height.toLong()
        if (length <= 0L || length > maxDecodedLayerBytes || length > Int.MAX_VALUE.toLong()) {
            throw IOException("GAL pixel plane exceeds the configured output limit.")
        }
        return length
    }

    private fun rowStride(width: Int, bpp: Int): Int {
        val rowBytes = (width.toLong() * bpp.toLong() + 7L) / 8L
        val aligned = if (bpp >= 8) (rowBytes + 3L) and -4L else rowBytes
        if (aligned <= 0L || aligned > Int.MAX_VALUE.toLong()) {
            throw IOException("GAL row stride is outside the supported range.")
        }
        return aligned.toInt()
    }

    private fun alignedStride(width: Int): Int {
        val aligned = (width.toLong() + 3L) and -4L
        if (aligned <= 0L || aligned > Int.MAX_VALUE.toLong()) {
            throw IOException("GAL alpha stride is outside the supported range.")
        }
        return aligned.toInt()
    }

    private fun inflateZlib(encoded: ByteArray, maximumOutputBytes: Long): ByteArray {
        if (encoded.isEmpty() || maximumOutputBytes <= 0L ||
            maximumOutputBytes > Int.MAX_VALUE.toLong()
        ) {
            throw IOException("GAL zlib stream size is outside the supported range.")
        }
        val inflater = Inflater(false)
        val inputBuffer = ByteArray(INFLATE_BUFFER_BYTES)
        val output = ByteArrayOutputStream()
        try {
            inflater.setInput(encoded)
            while (!inflater.finished()) {
                val count = try {
                    inflater.inflate(inputBuffer)
                } catch (failure: DataFormatException) {
                    throw IOException("GAL layer contains an invalid zlib stream.", failure)
                }
                if (count > 0) {
                    if (output.size().toLong() + count.toLong() > maximumOutputBytes) {
                        throw IOException("GAL decompressed layer exceeds the configured output limit.")
                    }
                    output.write(inputBuffer, 0, count)
                } else if (inflater.needsDictionary()) {
                    throw IOException("GAL zlib stream requires a preset dictionary.")
                } else if (inflater.needsInput()) {
                    throw IOException("GAL zlib stream is truncated.")
                } else {
                    throw IOException("GAL zlib inflater made no progress.")
                }
            }
            if (inflater.remaining != 0) {
                throw IOException("GAL zlib stream contains trailing bytes.")
            }
            return output.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private fun paletteIndexOrFail(index: Int, count: Int): Int {
        if (index !in 0 until count) {
            throw IOException("GAL pixel refers to a missing palette entry.")
        }
        return index
    }

    private class Cursor(
        private val source: ReadOnlyRandomAccessSource,
        start: Long,
        length: Long
    ) {
        private val end = start + length
        var position = start
            private set

        init {
            if (start < 0L || length < 0L || start > source.sizeBytes ||
                length > source.sizeBytes - start
            ) {
                throw IOException("GAL range is outside the input.")
            }
        }

        val remaining: Long
            get() = end - position

        fun readU8(): Int {
            requireRemaining(1L)
            val bytes = ByteArray(1)
            source.readFullyAt(position, bytes)
            position++
            return bytes[0].toInt() and 0xFF
        }

        fun readInt32(): Int = readUInt32().toInt()

        fun readUInt32(): Long {
            requireRemaining(4L)
            val bytes = ByteArray(4)
            source.readFullyAt(position, bytes)
            position += 4L
            return readUInt32LE(bytes, 0)
        }

        fun readAscii(length: Int): String {
            if (length < 0 || length > MAX_NAME_BYTES) {
                throw IOException("GAL text field length is outside the supported range.")
            }
            return String(readBytes(length), Charsets.US_ASCII)
        }

        fun readCount(label: String, maximum: Int): Int {
            val count = readInt32()
            if (count < 0 || count > maximum) {
                throw IOException(label + " is outside the supported range.")
            }
            return count
        }

        fun readBytes(length: Int): ByteArray {
            if (length < 0) throw IOException("GAL byte count is negative.")
            requireRemaining(length.toLong())
            val bytes = ByteArray(length)
            if (length > 0) source.readFullyAt(position, bytes)
            position += length.toLong()
            return bytes
        }

        fun skip(length: Long) {
            if (length < 0L) throw IOException("GAL skip length is negative.")
            requireRemaining(length)
            position += length
        }

        private fun requireRemaining(length: Long) {
            if (length < 0L || position > end || length > end - position) {
                throw IOException("GAL data is truncated.")
            }
        }
    }

    private class ByteCursor(private val bytes: ByteArray) {
        var position: Int = 0
            private set

        val remaining: Int
            get() = bytes.size - position

        fun readU8(): Int {
            requireBytes(1)
            return bytes[position++].toInt() and 0xFF
        }

        fun readInt32(): Int {
            requireBytes(4)
            val value = readUInt32LE(bytes, position).toInt()
            position += 4
            return value
        }

        fun readU8At(offset: Int): Int {
            if (offset < 0 || offset >= bytes.size) {
                throw IOException("GAL metadata header is too short.")
            }
            return bytes[offset].toInt() and 0xFF
        }

        fun readInt32At(offset: Int): Int {
            if (offset < 0 || offset > bytes.size - 4) {
                throw IOException("GAL metadata header is too short.")
            }
            return readUInt32LE(bytes, offset).toInt()
        }

        fun readBytes(length: Int): ByteArray {
            requireBytes(length)
            val result = bytes.copyOfRange(position, position + length)
            position += length
            return result
        }

        private fun requireBytes(length: Int) {
            if (length < 0 || position > bytes.size || length > bytes.size - position) {
                throw IOException("GAL layer/block data is truncated.")
            }
        }
    }

    companion object {
        private const val MIN_CLASSIC_VERSION = 103
        private const val MAX_CLASSIC_VERSION = 107
        private const val MIN_CLASSIC_HEADER_BYTES = 0x28
        private const val MAX_CLASSIC_HEADER_BYTES = 0x100
        private const val DEFAULT_MAX_DECODED_PIXELS = 16L * 1024L * 1024L
        private const val DEFAULT_MAX_ENCODED_LAYER_BYTES = 64 * 1024 * 1024
        private const val DEFAULT_MAX_DECODED_LAYER_BYTES = 64L * 1024L * 1024L
        private const val DEFAULT_MAX_XML_BYTES = 4 * 1024 * 1024
        private val GALX_XML_CHARSET: Charset = Charset.forName("windows-31j")
        private val GALX_FRAME_OPEN_TAG = Regex("<Frame(?=\\\\s)[^>]*>")
        private const val MAX_NAME_BYTES = 1024 * 1024
        private const val MAX_LAYERS = 32
        private const val MAX_FRAMES = 100_000
        private const val MAX_BLOCKS = 1_000_000
        private const val MAX_PALETTE_COLORS = 256
        private const val PALETTE_ENTRY_BYTES = 4
        private const val BLOCK_REFERENCE_BYTES = 8
        private const val INFLATE_BUFFER_BYTES = 64 * 1024
        private const val COMPRESSION_ZLIB = 0
        private const val COMPRESSION_NONE = 1
        private const val COMPRESSION_JPEG = 2
        private val SUPPORTED_BPP = setOf(4, 8, 16, 24, 32)

        private fun readUInt32LE(bytes: ByteArray, offset: Int): Long =
            (bytes[offset].toLong() and 0xFFL) or
                ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
                ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
                ((bytes[offset + 3].toLong() and 0xFFL) shl 24)
    }
}
