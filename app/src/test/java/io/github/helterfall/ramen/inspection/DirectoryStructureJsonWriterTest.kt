package io.github.helterfall.ramen.inspection

import java.io.StringWriter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryStructureJsonWriterTest {
    @Test
    fun writesFoldersFilesSizesAndTruncationState() {
        val report = DirectoryStructureReport(
            rootName = "Visual Novel",
            directories = listOf("assets", "assets/empty", "script"),
            files = listOf(
                DirectoryStructureFile("START.lsb", 256L),
                DirectoryStructureFile("script/main.lsb", null)
            ),
            truncated = false
        )

        val writer = StringWriter()
        DirectoryStructureJsonWriter().write(report, writer)
        val json = writer.toString()

        assertTrue(json.contains("\"schemaVersion\": 1"))
        assertTrue(json.contains("\"rootName\": \"Visual Novel\""))
        assertTrue(json.contains("\"directoryCount\": 3"))
        assertTrue(json.contains("\"fileCount\": 2"))
        assertTrue(json.contains("\"assets/empty\""))
        assertTrue(json.contains("\"relativePath\": \"START.lsb\""))
        assertTrue(json.contains("\"sizeBytes\": 256"))
        assertTrue(json.contains("\"sizeBytes\": null"))
        assertTrue(json.contains("\"truncated\": false"))
    }

    @Test
    fun escapesQuotesBackslashesAndControlCharacters() {
        val report = DirectoryStructureReport(
            rootName = "root\"name\nnext",
            directories = listOf("dir\\part"),
            files = listOf(DirectoryStructureFile("dir\\part/file\tname.lsb", 1L)),
            truncated = true,
            warnings = listOf("line one\nline two")
        )

        val writer = StringWriter()
        DirectoryStructureJsonWriter().write(report, writer)
        val json = writer.toString()

        assertTrue(json.contains("\"rootName\": \"root\\\"name\\nnext\""))
        assertTrue(json.contains("\"dir\\\\part\""))
        assertTrue(json.contains("file\\tname.lsb"))
        assertTrue(json.contains("\"line one\\nline two\""))
        assertFalse(json.contains("root\"name\nnext"))
    }
}
