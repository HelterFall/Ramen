package io.github.helterfall.ramen.importer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SafeHtmlStreamCopierTest {
    @Test
    fun copiesHtmlWithinLimit() {
        val output = ByteArrayOutputStream()
        val copied = SafeHtmlStreamCopier(maxBytes = 64).copy(
            input = ByteArrayInputStream("<h1>Hello</h1>".toByteArray()),
            output = output,
            declaredSizeBytes = 14L
        )

        assertEquals(14L, copied)
        assertEquals("<h1>Hello</h1>", output.toString("UTF-8"))
    }

    @Test
    fun rejectsStreamThatExceedsLimitEvenWhenProviderDoesNotDeclareSize() {
        val output = ByteArrayOutputStream()

        assertThrows(IOException::class.java) {
            SafeHtmlStreamCopier(maxBytes = 4).copy(
                input = ByteArrayInputStream("12345".toByteArray()),
                output = output,
                declaredSizeBytes = null
            )
        }
    }

    @Test
    fun rejectsDeclaredSizeAboveLimitBeforeCopying() {
        val output = ByteArrayOutputStream()

        assertThrows(IOException::class.java) {
            SafeHtmlStreamCopier(maxBytes = 4).copy(
                input = ByteArrayInputStream("1".toByteArray()),
                output = output,
                declaredSizeBytes = 5L
            )
        }

        assertEquals(0, output.size())
    }

    @Test
    fun rejectsMismatchBetweenDeclaredAndActualSize() {
        assertThrows(IOException::class.java) {
            SafeHtmlStreamCopier(maxBytes = 16).copy(
                input = ByteArrayInputStream("abc".toByteArray()),
                output = ByteArrayOutputStream(),
                declaredSizeBytes = 4L
            )
        }
    }
}
