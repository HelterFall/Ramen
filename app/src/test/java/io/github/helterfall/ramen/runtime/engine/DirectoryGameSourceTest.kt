package io.github.helterfall.ramen.runtime.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryGameSourceTest {
    @Test
    fun createsDeterministicReadOnlySnapshotAndOpensBothAccessTypes() {
        val root = Files.createTempDirectory("ramen-engine-source").toFile()

        try {
            File(root, "z.bin").writeBytes(byteArrayOf(9, 8, 7))
            val nested = File(root, "data")
            nested.mkdirs()
            File(nested, "a.bin").writeBytes(byteArrayOf(1, 2, 3, 4))

            val source = DirectoryGameSource(root, sourceId = "unit-test")

            assertEquals(listOf("data/a.bin", "z.bin"), source.entries.map { it.relativePath })
            assertEquals("unit-test", source.sourceId)
            assertFalse(source.isTruncated)

            source.openRead("data/a.bin").use { input ->
                assertArrayEquals(byteArrayOf(1, 2, 3, 4), input.readBytes())
            }
            source.openRandomAccess("z.bin").use { randomAccess ->
                val bytes = ByteArray(3)
                randomAccess.readFullyAt(0L, bytes)
                assertArrayEquals(byteArrayOf(9, 8, 7), bytes)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun enforcesTheSnapshotFileLimitAndReportsTruncation() {
        val root = Files.createTempDirectory("ramen-engine-source-limit").toFile()

        try {
            File(root, "a.bin").writeBytes(byteArrayOf(1))
            File(root, "b.bin").writeBytes(byteArrayOf(2))
            File(root, "c.bin").writeBytes(byteArrayOf(3))

            val source = DirectoryGameSource(
                rootDirectory = root,
                sourceId = "limited-test",
                maxFiles = 2
            )

            assertEquals(2, source.entries.size)
            assertTrue(source.isTruncated)
            assertTrue(source.warnings.any { it.contains("File count limit") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTraversalWhenOpeningAFile() {
        val root = Files.createTempDirectory("ramen-engine-source-traversal").toFile()

        try {
            File(root, "safe.bin").writeBytes(byteArrayOf(1))
            val source = DirectoryGameSource(root, sourceId = "traversal-test")
            source.openRead("../outside.bin")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun ignoresFilesAddedAfterTheSnapshotWasCreated() {
        val root = Files.createTempDirectory("ramen-engine-source-snapshot").toFile()

        try {
            File(root, "before.bin").writeBytes(byteArrayOf(1))
            val source = DirectoryGameSource(root, sourceId = "snapshot-test")
            File(root, "after.bin").writeBytes(byteArrayOf(2))

            assertEquals(listOf("before.bin"), source.entries.map { it.relativePath })
        } finally {
            root.deleteRecursively()
        }
    }
}
