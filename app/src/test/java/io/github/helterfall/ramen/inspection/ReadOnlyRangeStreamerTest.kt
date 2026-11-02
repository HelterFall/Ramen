package io.github.helterfall.ramen.inspection

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadOnlyRangeStreamerTest {
    @Test
    fun copiesOnlyRequestedRangeWithBoundedBuffer() {
        val directory = Files.createTempDirectory("ramen-range-streamer").toFile()
        try {
            val file = File(directory, "sample.bin").apply {
                writeBytes("0123456789".toByteArray(Charsets.US_ASCII))
            }
            FileRandomAccessSource(file).use { source ->
                val destination = RecordingOutputStream()
                val copied = ReadOnlyRangeStreamer(bufferSizeBytes = 3).copy(
                    source = source,
                    offset = 2,
                    length = 6,
                    destination = destination
                )

                assertEquals(6L, copied)
                assertEquals("234567", destination.bytes.toString(Charsets.US_ASCII.name()))
                assertFalse(destination.wasClosed)
                val sourceCheck = ByteArray(2)
                source.readFullyAt(0, sourceCheck)
                assertEquals("01", sourceCheck.toString(Charsets.US_ASCII))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun acceptsEmptyRangeAtEndOfSource() {
        val directory = Files.createTempDirectory("ramen-range-streamer-empty").toFile()
        try {
            val file = File(directory, "sample.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            FileRandomAccessSource(file).use { source ->
                val destination = RecordingOutputStream()
                val copied = ReadOnlyRangeStreamer().copy(
                    source = source,
                    offset = source.sizeBytes,
                    length = 0,
                    destination = destination
                )
                assertEquals(0L, copied)
                assertTrue(destination.bytes.size() == 0)
                assertFalse(destination.wasClosed)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsOutOfBoundsAndNegativeRanges() {
        val directory = Files.createTempDirectory("ramen-range-streamer-bounds").toFile()
        try {
            val file = File(directory, "sample.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            FileRandomAccessSource(file).use { source ->
                val destination = RecordingOutputStream()
                assertTrue(
                    runCatching {
                        ReadOnlyRangeStreamer().copy(source, offset = 2, length = 2, destination = destination)
                    }.exceptionOrNull() is IOException
                )
                assertTrue(
                    runCatching {
                        ReadOnlyRangeStreamer().copy(source, offset = -1, length = 1, destination = destination)
                    }.exceptionOrNull() is IOException
                )
                assertTrue(
                    runCatching {
                        ReadOnlyRangeStreamer().copy(source, offset = 0, length = -1, destination = destination)
                    }.exceptionOrNull() is IOException
                )
                assertEquals(0, destination.bytes.size())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private class RecordingOutputStream : OutputStream() {
        val bytes = ByteArrayOutputStream()
        var wasClosed = false
            private set

        override fun write(value: Int) {
            bytes.write(value)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            bytes.write(buffer, offset, length)
        }

        override fun close() {
            wasClosed = true
        }
    }
}
