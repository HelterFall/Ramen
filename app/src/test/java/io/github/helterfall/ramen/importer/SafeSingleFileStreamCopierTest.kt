package io.github.helterfall.ramen.importer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SafeSingleFileStreamCopierTest {
    @Test
    fun streamsArbitraryBinaryFilesWithoutChangingTheirBytes() {
        val input = ByteArray(256 * 1024) { index -> (index * 31).toByte() }
        input[0] = 0
        input[1] = 0x4D
        input[2] = 0x5A
        input[3] = 0
        val output = ByteArrayOutputStream()

        val copied = SafeSingleFileStreamCopier().copy(
            input = ByteArrayInputStream(input),
            output = output,
            declaredSizeBytes = input.size.toLong()
        )

        assertEquals(input.size.toLong(), copied)
        assertArrayEquals(input, output.toByteArray())
    }

    @Test
    fun acceptsUnknownSizeAndCopiesAnExtensionlessFile() {
        val bytes = byteArrayOf(0, 1, 2, 3, 0x7F, 0x80.toByte())
        val output = ByteArrayOutputStream()

        val copied = SafeSingleFileStreamCopier().copy(
            input = ByteArrayInputStream(bytes),
            output = output
        )

        assertEquals(bytes.size.toLong(), copied)
        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test
    fun rejectsMismatchBetweenDeclaredAndActualFileSize() {
        assertThrows(IOException::class.java) {
            SafeSingleFileStreamCopier().copy(
                input = ByteArrayInputStream(byteArrayOf(1, 2, 3)),
                output = ByteArrayOutputStream(),
                declaredSizeBytes = 4L
            )
        }
    }
}