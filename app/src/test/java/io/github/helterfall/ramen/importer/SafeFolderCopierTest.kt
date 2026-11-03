package io.github.helterfall.ramen.importer

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeFolderCopierTest {
    @Test
    fun copiesNestedFolderContents() {
        val root = FakeNode(
            name = "root",
            directory = true,
            children = listOf(
                FakeNode(
                    name = "assets",
                    directory = true,
                    children = listOf(
                        FakeNode("story.txt", bytes = "An original story.".toByteArray())
                    )
                ),
                FakeNode("index.html", bytes = "<html></html>".toByteArray())
            )
        )
        val destination = Files.createTempDirectory("ramen-folder-copy").toFile()

        try {
            val summary = SafeFolderCopier().copyContents(root, File(destination, "game"))

            assertEquals(2, summary.fileCount)
            assertEquals(1, summary.directoryCount)
            assertEquals(
                "An original story.",
                File(destination, "game/assets/story.txt").readText()
            )
            assertTrue(File(destination, "game/index.html").isFile)
        } finally {
            destination.deleteRecursively()
        }
    }

    @Test
    fun rejectsTraversalNames() {
        val root = FakeNode(
            name = "root",
            directory = true,
            children = listOf(
                FakeNode("../outside.txt", bytes = "not allowed".toByteArray())
            )
        )
        val destination = Files.createTempDirectory("ramen-folder-traversal").toFile()
        val outside = File(destination, "outside.txt")

        try {
            org.junit.Assert.assertThrows(IOException::class.java) {
                SafeFolderCopier().copyContents(root, File(destination, "game"))
            }
            assertFalse(outside.exists())
        } finally {
            destination.deleteRecursively()
        }
    }

    @Test
    fun copiesExpandedFilesWithoutAnArbitraryTotalByteCeiling() {
        val payload = "large-original-game-data-".repeat(80_000).toByteArray()
        val root = FakeNode(
            name = "root",
            directory = true,
            children = listOf(FakeNode("large.bin", bytes = payload))
        )
        val destination = Files.createTempDirectory("ramen-folder-size").toFile()

        try {
            val summary = SafeFolderCopier().copyContents(root, File(destination, "game"))
            assertEquals(payload.size.toLong(), summary.totalBytes)
            assertTrue(File(destination, "game/large.bin").readBytes().contentEquals(payload))
        } finally {
            destination.deleteRecursively()
        }
    }

    @Test
    fun rejectsDuplicatePathsWithoutCaseSensitivity() {
        val root = FakeNode(
            name = "root",
            directory = true,
            children = listOf(
                FakeNode("Story.txt", bytes = "first".toByteArray()),
                FakeNode("story.txt", bytes = "second".toByteArray())
            )
        )
        val destination = Files.createTempDirectory("ramen-folder-duplicates").toFile()

        try {
            org.junit.Assert.assertThrows(IOException::class.java) {
                SafeFolderCopier().copyContents(root, File(destination, "game"))
            }
        } finally {
            destination.deleteRecursively()
        }
    }

    private class FakeNode(
        override val name: String?,
        private val directory: Boolean = false,
        private val bytes: ByteArray = byteArrayOf(),
        private val children: List<FakeNode> = emptyList()
    ) : FolderEntryNode {
        override val sourceId: String
            get() = name ?: "unnamed"

        override val isDirectory: Boolean
            get() = directory

        override val declaredSizeBytes: Long?
            get() = if (directory) null else bytes.size.toLong()

        override fun listChildren(): List<FolderEntryNode> = children

        override fun openInputStream(): InputStream = ByteArrayInputStream(bytes)
    }
}
