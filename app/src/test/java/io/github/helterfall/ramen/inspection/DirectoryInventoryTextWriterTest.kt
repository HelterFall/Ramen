package io.github.helterfall.ramen.inspection

import java.io.ByteArrayOutputStream
import java.io.StringWriter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryInventoryTextWriterTest {
    @Test
    fun writesReadableFolderTreeNamesSizesAndEmptyDirectories() {
        val report = DirectoryInventoryReport(
            rootName = "novel",
            entries = listOf(
                DirectoryInventoryEntry("data/story.spt", 2048L, null, null),
                DirectoryInventoryEntry("project.xtx", 109L, null, null)
            ),
            discoveredEntries = 2,
            totalBytesObserved = 2157L,
            totalBytesHashed = 0L,
            truncated = false,
            directoryPaths = listOf("data", "empty")
        )

        val output = StringWriter()
        DirectoryInventoryTextWriter().write(report, output)
        val text = output.toString()

        assertTrue(text.contains("RAMEN — СТРУКТУРА ВИЗУАЛЬНОЙ НОВЕЛЛЫ"))
        assertTrue(text.contains("novel/"))
        assertTrue(text.contains("data/"))
        assertTrue(text.contains("story.spt"))
        assertTrue(text.contains("project.xtx"))
        assertTrue(text.contains("empty/"))
        assertTrue(text.contains("2048 байт"))
        assertTrue(text.contains("Общий объём файлов в отчёте: 2157 байт"))
        assertFalse(text.contains("SHA-256"))
        assertFalse(text.contains("содержимое story.spt"))
    }

    @Test
    fun reportsTruncationAndWarnings() {
        val report = DirectoryInventoryReport(
            rootName = "partial",
            entries = listOf(DirectoryInventoryEntry("a.txt", 1L, null, null)),
            discoveredEntries = 2,
            totalBytesObserved = 1L,
            totalBytesHashed = 0L,
            truncated = true,
            warnings = listOf("File scan reached a configured limit.")
        )

        val output = StringWriter()
        DirectoryInventoryTextWriter().write(report, output)
        val text = output.toString()

        assertTrue(text.contains("ОТЧЁТ ОГРАНИЧЕН"))
        assertTrue(text.contains("File scan reached a configured limit"))
    }

    @Test
    fun outputStreamRemainsOwnedByCaller() {
        val output = TrackingOutputStream()
        val report = DirectoryInventoryReport(
            rootName = "empty",
            entries = emptyList(),
            discoveredEntries = 0,
            totalBytesObserved = 0L,
            totalBytesHashed = 0L,
            truncated = false
        )

        DirectoryInventoryTextWriter().write(report, output)

        assertFalse(output.wasClosed)
        output.write('x'.code)
        assertTrue(output.toByteArray().isNotEmpty())
    }

    private class TrackingOutputStream : ByteArrayOutputStream() {
        var wasClosed: Boolean = false
            private set

        override fun close() {
            wasClosed = true
            super.close()
        }
    }
}
