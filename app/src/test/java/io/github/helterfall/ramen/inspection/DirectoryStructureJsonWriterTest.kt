package io.github.helterfall.ramen.inspection

import java.io.StringWriter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryStructureJsonWriterTest {
    @Test
    fun writesOnlyDirectoryPathsAndTruncationState() {
        val report = DirectoryStructureReport(
            rootName = "Visual Novel",
            directories = listOf("assets", "assets/empty", "script"),
            truncated = false
        )

        val writer = StringWriter()
        DirectoryStructureJsonWriter().write(report, writer)
        val json = writer.toString()

        assertTrue(json.contains("\"schemaVersion\": 2"))
        assertTrue(json.contains("\"rootName\": \"Visual Novel\""))
        assertTrue(json.contains("\"directoryCount\": 3"))
        assertTrue(json.contains("\"assets/empty\""))
        assertTrue(json.contains("\"truncated\": false"))
        assertTrue(json.contains("\"directories\""))
        assertFalse(json.contains("\"fileCount\""))
        assertFalse(json.contains("\"files\""))
        assertFalse(json.contains("\"sizeBytes\""))
    }

    @Test
    fun escapesQuotesBackslashesAndControlCharacters() {
        val report = DirectoryStructureReport(
            rootName = "root\"name\nnext",
            directories = listOf("dir\\part", "dir\tname"),
            truncated = true,
            warnings = listOf("line one\nline two")
        )

        val writer = StringWriter()
        DirectoryStructureJsonWriter().write(report, writer)
        val json = writer.toString()

        assertTrue(json.contains("\"rootName\": \"root\\\"name\\nnext\""))
        assertTrue(json.contains("\"dir\\\\part\""))
        assertTrue(json.contains("dir\\tname"))
        assertTrue(json.contains("\"line one\\nline two\""))
        assertFalse(json.contains("root\"name\nnext"))
    }
}
