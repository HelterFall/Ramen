package io.github.helterfall.ramen.runtime.nativeaudio

import io.github.helterfall.ramen.inspection.GpkImageFormat
import io.github.helterfall.ramen.inspection.GpkImageResource
import io.github.helterfall.ramen.inspection.ReadOnlyRandomAccessSource
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidGpkImageDecoderTest {
    @Test
    fun packtypeOneUsesDwqDecoderEvenWhenConvenienceFlagIsMissing() {
        val bmp = ByteArray(0x36 + 3)
        putU32(bmp, 10, 0x36)
        putU32(bmp, 18, 1)
        putU32(bmp, 22, 1)
        putU16(bmp, 28, 24)
        bmp[0x36] = 1
        bmp[0x37] = 2
        bmp[0x38] = 3

        val header = ByteArray(0x40) { ' '.code.toByte() }
        "IF PACKTYPE==".toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        "0 ".toByteArray(Charsets.US_ASCII).copyInto(header, 0x0D)
        "BMP ".toByteArray(Charsets.US_ASCII).copyInto(header, 0x2C)
        "PACKTYPE=1      ".toByteArray(Charsets.US_ASCII).copyInto(header, 0x30)
        val block = header + bmp

        val resource = GpkImageResource(
            name = "packed",
            format = GpkImageFormat.BMP,
            blockOffset = 0L,
            blockSizeBytes = block.size.toLong(),
            payloadOffset = 0x40L,
            payloadSizeBytes = bmp.size.toLong(),
            widthPixels = 1,
            heightPixels = 1,
            packType = 1,
            dwqPackedBmp = false
        )

        val failure = assertThrows(IOException::class.java) {
            AndroidGpkImageDecoder().decode(ByteArraySource(block), resource)
        }
        // The local JVM Android stub fails only at Bitmap allocation. Reaching this
        // message proves PACKTYPE=1 was decoded as DWQ, not sent to BitmapFactory.
        assertEquals("Could not allocate the decoded DWQ bitmap.", failure.message)
    }

    @Test
    fun rejectsIndexedPackedDwqBlocksAboveConfiguredBudgetBeforeReading() {
        val source = ByteArraySource(ByteArray(65))
        val resource = GpkImageResource(
            name = "oversized",
            format = GpkImageFormat.BMP,
            blockOffset = 0L,
            blockSizeBytes = 65L,
            payloadOffset = 0x40L,
            payloadSizeBytes = 1L,
            widthPixels = 1,
            heightPixels = 1,
            packType = 1,
            dwqPackedBmp = true
        )

        val failure = assertThrows(IOException::class.java) {
            AndroidGpkImageDecoder(
                maxDecodedPixels = 1L,
                maxPackedBlockBytes = 64L
            ).decode(source, resource)
        }

        assertEquals("Packed DWQ resource exceeds the configured block-size budget.", failure.message)
        assertEquals(0, source.readCalls)
    }

    @Test
    fun rejectsOversizedDwqJpegAlphaMaskBeforeReadingOrSamplingJpeg() {
        val source = ByteArraySource(ByteArray(128))
        val resource = GpkImageResource(
            name = "oversized-alpha",
            format = GpkImageFormat.JPEG,
            blockOffset = 0L,
            blockSizeBytes = 128L,
            payloadOffset = 0x40L,
            payloadSizeBytes = 64L,
            widthPixels = 2,
            heightPixels = 1,
            packType = 7,
            hasAlpha = true
        )

        val failure = assertThrows(IOException::class.java) {
            AndroidGpkImageDecoder(maxDecodedPixels = 1L).decode(source, resource)
        }

        assertEquals(
            "DWQ JPEG alpha-mask image exceeds the configured decoded-pixel budget.",
            failure.message
        )
        assertEquals(0, source.readCalls)
    }

    @Test
    fun appliesConfiguredBlockBudgetToStandaloneWgqFiles() {
        val directory = Files.createTempDirectory("ramen-wgq-block-budget").toFile()
        try {
            val image = File(directory, "oversized.wgq").apply {
                writeBytes(ByteArray(0x76))
            }
            val failure = assertThrows(IOException::class.java) {
                AndroidGpkImageDecoder(
                    maxDecodedPixels = 1L,
                    maxPackedBlockBytes = 0x75L
                ).decodeStandaloneWgq(image)
            }

            assertEquals(
                "The WGQ image resource exceeds the configured block-size budget or supported buffer range.",
                failure.message
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    private class ByteArraySource(
        private val bytes: ByteArray
    ) : ReadOnlyRandomAccessSource {
        var readCalls: Int = 0
            private set

        override val sizeBytes: Long
            get() = bytes.size.toLong()

        override fun readAt(
            position: Long,
            target: ByteArray,
            targetOffset: Int,
            length: Int
        ): Int {
            readCalls++
            require(position >= 0L)
            require(targetOffset >= 0 && targetOffset <= target.size)
            require(length >= 0 && length <= target.size - targetOffset)
            if (length == 0) return 0
            if (position >= bytes.size.toLong()) return -1

            val start = position.toInt()
            val count = minOf(length, bytes.size - start)
            bytes.copyInto(target, targetOffset, start, start + count)
            return count
        }

        override fun close() = Unit
    }

    private fun putU32(bytes: ByteArray, offset: Int, value: Int) {
        repeat(4) { index ->
            bytes[offset + index] = (value ushr (index * 8)).toByte()
        }
    }

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }
}
