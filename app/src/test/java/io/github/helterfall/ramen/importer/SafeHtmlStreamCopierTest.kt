package io.github.helterfall.ramen.importer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SafeHtmlStreamCopierTest {
    @Test
    fun streamsHtmlWithoutAnArbitraryByteCeiling() {
        val contents = "<p>original project data</p>".repeat(50_000)
        val input = contents.toByteArray()
        val output = ByteArrayOutputStream()
        val copied = SafeHtmlStreamCopier().copy(
            input = ByteArrayInputStream(input),
            output = output,
            declaredSizeBytes = input.size.toLong()
        )

        assertEquals(input.size.toLong(), copied)
        assertEquals(contents, output.toString("UTF-8"))
    }

    @Test
    fun rejectsMismatchBetweenDeclaredAndActualSize() {
        assertThrows(IOException::class.java) {
            SafeHtmlStreamCopier().copy(
                input = ByteArrayInputStream("abc".toByteArray()),
                output = ByteArrayOutputStream(),
                declaredSizeBytes = 4L
            )
        }
    }
}
