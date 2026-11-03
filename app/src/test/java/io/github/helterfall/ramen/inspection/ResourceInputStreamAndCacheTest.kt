package io.github.helterfall.ramen.inspection

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceInputStreamAndCacheTest {
    @Test
    fun rangeInputStreamReadsOnlyItsWindowAndDoesNotCloseSource() {
        val directory = Files.createTempDirectory("ramen-range-input-stream").toFile()
        try {
            val file = File(directory, "source.bin").apply {
                writeBytes("0123456789".toByteArray(Charsets.US_ASCII))
            }
            FileRandomAccessSource(file).use { source ->
                val input = ReadOnlySourceRangeInputStream(source, offset = 3, length = 4)
                val first = ByteArray(3)
                assertEquals(3, input.read(first))
                assertEquals("345", first.toString(Charsets.US_ASCII))
                assertEquals(1L, input.skip(1))
                assertEquals(-1, input.read())
                input.close()

                assertTrue(runCatching { input.read() }.exceptionOrNull() is IOException)
                val sourceBytes = ByteArray(2)
                source.readFullyAt(0, sourceBytes)
                assertEquals("01", sourceBytes.toString(Charsets.US_ASCII))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rangeInputStreamHandlesEmptyRangeAndBounds() {
        val directory = Files.createTempDirectory("ramen-range-input-bounds").toFile()
        try {
            val file = File(directory, "source.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            FileRandomAccessSource(file).use { source ->
                val empty = ReadOnlySourceRangeInputStream(source, source.sizeBytes, 0)
                assertEquals(-1, empty.read())
                assertEquals(0, empty.available())
                assertTrue(
                    runCatching {
                        ReadOnlySourceRangeInputStream(source, offset = 2, length = 2)
                    }.exceptionOrNull() is IOException
                )
                assertTrue(
                    runCatching {
                        ReadOnlySourceRangeInputStream(source, offset = -1, length = 1)
                    }.exceptionOrNull() is IOException
                )
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun materializesOnlyOggPayloadIntoUniqueCacheFile() {
        val directory = Files.createTempDirectory("ramen-ogg-cache").toFile()
        try {
            val sourceFile = File(directory, "archive.bin").apply {
                writeBytes(byteArrayOf(90, 91, 0x4F, 0x67, 0x67, 0x53, 10, 11))
            }
            val cache = File(directory, "cache")
            FileRandomAccessSource(sourceFile).use { source ->
                val resource = VpkAudioResource(
                    name = "track001",
                    blockOffset = 0,
                    blockSizeBytes = 8,
                    oggPayloadOffset = 2,
                    oggPayloadSizeBytes = 4,
                    sampleRateHz = 44_100,
                    channels = 2,
                    bitsPerSample = 16,
                    decodedPcmSizeBytes = 16,
                    oggPageCount = 1
                )
                val first = VpkOggCacheMaterializer().materialize(source, resource, cache)
                val second = VpkOggCacheMaterializer().materialize(source, resource, cache)

                assertTrue(first.isFile)
                assertTrue(second.isFile)
                assertFalse(first == second)
                assertEquals(4L, first.length())
                assertTrue(first.readBytes().contentEquals(byteArrayOf(0x4F, 0x67, 0x67, 0x53)))
                assertTrue(second.delete())
                assertTrue(first.delete())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun materializerRejectsOversizedResourceBeforeCreatingFile() {
        val directory = Files.createTempDirectory("ramen-ogg-cache-limit").toFile()
        try {
            val sourceFile = File(directory, "archive.bin").apply {
                writeBytes(byteArrayOf(1, 2, 3, 4))
            }
            val cache = File(directory, "cache")
            FileRandomAccessSource(sourceFile).use { source ->
                val resource = VpkAudioResource(
                    name = "track001",
                    blockOffset = 0,
                    blockSizeBytes = 4,
                    oggPayloadOffset = 0,
                    oggPayloadSizeBytes = 4,
                    sampleRateHz = 44_100,
                    channels = 2,
                    bitsPerSample = 16,
                    decodedPcmSizeBytes = 16,
                    oggPageCount = 1
                )
                val result = runCatching {
                    VpkOggCacheMaterializer(maxResourceBytes = 3).materialize(
                        source,
                        resource,
                        cache
                    )
                }
                assertTrue(result.exceptionOrNull() is IOException)
                assertFalse(cache.exists())
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
