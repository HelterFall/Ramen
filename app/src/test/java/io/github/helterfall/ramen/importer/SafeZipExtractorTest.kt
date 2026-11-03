package io.github.helterfall.ramen.importer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeZipExtractorTest {
    @Test
    fun extractsNormalFilesIntoDestination() {
        val temporaryRoot = Files.createTempDirectory("ramen-zip-test").toFile()

        try {
            val destination = File(temporaryRoot, "game")
            val archive = zipOf(
                "index.html" to "<html>hello</html>",
                "assets/story.txt" to "A short original story."
            )

            val summary = SafeZipExtractor().extract(
                ByteArrayInputStream(archive),
                destination
            )

            assertEquals(2, summary.fileCount)
            assertEquals("<html>hello</html>", File(destination, "index.html").readText())
            assertEquals(
                "A short original story.",
                File(destination, "assets/story.txt").readText()
            )
            assertTrue(summary.totalBytes > 0L)
        } finally {
            temporaryRoot.deleteRecursively()
        }
    }

    @Test
    fun rejectsParentDirectoryTraversal() {
        val temporaryRoot = Files.createTempDirectory("ramen-zip-slip-test").toFile()

        try {
            val destination = File(temporaryRoot, "game")
            val outsideFile = File(temporaryRoot, "outside.txt")
            val archive = zipOf("../outside.txt" to "not allowed")

            val error = org.junit.Assert.assertThrows(IOException::class.java) {
                SafeZipExtractor().extract(ByteArrayInputStream(archive), destination)
            }

            assertTrue(error.message.orEmpty().contains("Path traversal"))
            assertFalse(outsideFile.exists())
        } finally {
            temporaryRoot.deleteRecursively()
        }
    }

    @Test
    fun streamsExpandedFilesWithoutAnArbitraryTotalByteCeiling() {
        val temporaryRoot = Files.createTempDirectory("ramen-zip-size-test").toFile()

        try {
            val contents = "large-original-game-data-".repeat(80_000)
            val archive = zipOf("assets/data.bin" to contents, "story.txt" to "next")
            val summary = SafeZipExtractor().extract(
                ByteArrayInputStream(archive),
                File(temporaryRoot, "game")
            )

            assertEquals(contents, File(temporaryRoot, "game/assets/data.bin").readText())
            assertEquals(contents.toByteArray(Charsets.UTF_8).size.toLong() + 4L, summary.totalBytes)
            assertEquals(2, summary.fileCount)
        } finally {
            temporaryRoot.deleteRecursively()
        }
    }

    @Test
    fun rejectsDuplicatePathsRegardlessOfCase() {
        val temporaryRoot = Files.createTempDirectory("ramen-zip-duplicate-test").toFile()

        try {
            val archive = zipOf(
                "Story.txt" to "first",
                "story.txt" to "second"
            )

            org.junit.Assert.assertThrows(IOException::class.java) {
                SafeZipExtractor().extract(
                    ByteArrayInputStream(archive),
                    File(temporaryRoot, "game")
                )
            }
        } finally {
            temporaryRoot.deleteRecursively()
        }
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()

        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }

        return output.toByteArray()
    }
}
