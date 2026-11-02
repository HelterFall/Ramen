package io.github.helterfall.ramen.inspection

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryInventoryScannerTest {
    @Test
    fun scansFilesInDeterministicPathOrderAndReportsSignatures() {
        val root = Files.createTempDirectory("ramen-inventory-order").toFile()
        try {
            File(root, "z-last.txt").writeText("last")
            File(root, "a-first.png").writeBytes(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            )
            val nested = File(root, "assets")
            nested.mkdirs()
            File(nested, "middle.ogg").writeBytes(byteArrayOf(0x4F, 0x67, 0x67, 0x53))

            val result = DirectoryInventoryScanner().scan(root)

            assertEquals(
                listOf("a-first.png", "assets/middle.ogg", "z-last.txt"),
                result.entries.map { it.relativePath }
            )
            assertEquals(3, result.discoveredEntries)
            assertFalse(result.truncated)
            assertEquals(ArtifactKind.PNG_IMAGE, result.entries[0].artifactReport?.kind)
            assertEquals(ArtifactKind.OGG_STREAM, result.entries[1].artifactReport?.kind)
            assertTrue(result.entries.all { it.sha256Hex?.length == 64 })
            assertEquals(result.totalBytesObserved, result.totalBytesHashed)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun totalHashBudgetSkipsLaterHashesButStillInspectsFiles() {
        val root = Files.createTempDirectory("ramen-inventory-budget").toFile()
        try {
            File(root, "a.txt").writeText("abc")
            File(root, "b.txt").writeText("defg")

            val result = DirectoryInventoryScanner(maxTotalBytesToHash = 3L).scan(root)

            assertNotNull(result.entries[0].sha256Hex)
            assertNull(result.entries[1].sha256Hex)
            assertEquals(ArtifactKind.UNKNOWN, result.entries[1].artifactReport?.kind)
            assertTrue(result.entries[1].warnings.any { it.contains("hash budget") })
            assertEquals(3L, result.totalBytesHashed)
            assertEquals(7L, result.totalBytesObserved)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun stopsAtConfiguredFileLimitAndMarksInventoryTruncated() {
        val root = Files.createTempDirectory("ramen-inventory-count").toFile()
        try {
            File(root, "a.txt").writeText("a")
            File(root, "b.txt").writeText("b")

            val result = DirectoryInventoryScanner(maxFiles = 1).scan(root)

            assertEquals(1, result.entries.size)
            assertEquals("a.txt", result.entries.single().relativePath)
            assertEquals(2, result.discoveredEntries)
            assertTrue(result.truncated)
            assertTrue(result.warnings.isNotEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsNonDirectoryRoot() {
        val directory = Files.createTempDirectory("ramen-inventory-invalid").toFile()
        try {
            val file = File(directory, "not-a-directory.txt")
            file.writeText("x")

            val error = runCatching { DirectoryInventoryScanner().scan(file) }.exceptionOrNull()

            assertTrue(error is java.io.IOException)
        } finally {
            directory.deleteRecursively()
        }
    }
}
