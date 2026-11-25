package io.github.helterfall.ramen.importer

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NsisArchiveExtractorTest {
    @Test
    fun normalizesWindowsSeparatorsAndDotSegments() {
        assertEquals("game/data/start.ax", normalizeNsisEntryName(".\\game\\data\\start.ax"))
    }

    @Test
    fun rejectsParentTraversalAndAbsolutePaths() {
        assertThrows(IOException::class.java) {
            normalizeNsisEntryName("..\\outside.txt")
        }
        assertThrows(IOException::class.java) {
            normalizeNsisEntryName("/absolute/file.txt")
        }
        assertThrows(IOException::class.java) {
            normalizeNsisEntryName("C:\\Windows\\system.ini")
        }
    }

    @Test
    fun rejectsControlCharactersAndExcessiveDepth() {
        assertThrows(IOException::class.java) {
            normalizeNsisEntryName("folder/\u0000evil")
        }
        assertThrows(IOException::class.java) {
            normalizeNsisEntryName("a/b/c/d", maxPathDepth = 3)
        }
    }
}
