package io.github.helterfall.ramen.inspection

import io.github.helterfall.ramen.importer.FolderEntryNode
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryStructureScannerTest {
    @Test
    fun preservesNestedAndEmptyDirectoriesWhileIgnoringFiles() {
        val root = FakeNode(
            name = "game",
            isDirectory = true,
            children = listOf(
                FakeNode(
                    name = "assets",
                    isDirectory = true,
                    children = listOf(
                        FakeNode(name = "empty", isDirectory = true),
                        FakeNode(name = "logo.png", isDirectory = false, sizeBytes = 128L)
                    )
                ),
                FakeNode(
                    name = "scripts",
                    isDirectory = true,
                    children = listOf(
                        FakeNode(name = "START.lsb", isDirectory = false, sizeBytes = 512L)
                    )
                ),
                FakeNode(name = "root.dat", isDirectory = false)
            )
        )

        val report = DirectoryStructureScanner().scan(root)

        assertEquals(listOf("assets", "assets/empty", "scripts"), report.directories)
        assertEquals("game", report.rootName)
        assertFalse(report.truncated)
    }

    @Test
    fun entryLimitCountsDirectoriesRatherThanFiles() {
        val root = FakeNode(
            name = "game",
            isDirectory = true,
            children = listOf(
                FakeNode(
                    name = "a",
                    isDirectory = true,
                    children = listOf(FakeNode("inside", isDirectory = true))
                ),
                FakeNode(name = "b", isDirectory = true),
                FakeNode(name = "many-files", isDirectory = true, children = listOf(
                    FakeNode("one.dat", isDirectory = false),
                    FakeNode("two.dat", isDirectory = false),
                    FakeNode("three.dat", isDirectory = false)
                ))
            )
        )

        val report = DirectoryStructureScanner(maxEntries = 2).scan(root)

        assertEquals(listOf("a", "a/inside"), report.directories)
        assertTrue(report.truncated)
        assertTrue(report.warnings.any { it.contains("limit") })
    }

    @Test
    fun marksReportTruncatedWhenDepthLimitSkipsSubdirectories() {
        val root = FakeNode(
            name = "game",
            isDirectory = true,
            children = listOf(
                FakeNode(
                    name = "level1",
                    isDirectory = true,
                    children = listOf(
                        FakeNode(
                            name = "level2",
                            isDirectory = true,
                            children = listOf(FakeNode("inside.dat", isDirectory = false))
                        )
                    )
                )
            )
        )

        val report = DirectoryStructureScanner(maxDepth = 1).scan(root)

        assertEquals(listOf("level1"), report.directories)
        assertTrue(report.truncated)
    }

    @Test
    fun rejectsUnsafeDirectoryPathSegments() {
        val root = FakeNode(
            name = "game",
            isDirectory = true,
            children = listOf(FakeNode("../outside", isDirectory = true))
        )

        val error = runCatching { DirectoryStructureScanner().scan(root) }.exceptionOrNull()

        assertTrue(error is IOException)
    }

    private class FakeNode(
        override val name: String?,
        override val isDirectory: Boolean,
        private val sizeBytes: Long? = null,
        private val children: List<FakeNode> = emptyList()
    ) : FolderEntryNode {
        override val declaredSizeBytes: Long?
            get() {
                check(isDirectory) { "The directory scanner must not query file sizes." }
                return sizeBytes
            }

        override fun listChildren(): List<FolderEntryNode> = children

        override fun openInputStream(): InputStream {
            throw AssertionError("The directory scanner must not open file streams.")
        }
    }
}
