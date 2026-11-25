package io.github.helterfall.ramen.inspection

import io.github.helterfall.ramen.importer.FolderEntryNode
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryStructureScannerTest {
    @Test
    fun preservesNestedAndEmptyDirectoriesWithoutReadingFileContents() {
        val root = FakeNode(
            name = "game",
            isDirectory = true,
            children = listOf(
                FakeNode(
                    name = "assets",
                    isDirectory = true,
                    children = listOf(
                        FakeNode(name = "empty", isDirectory = true),
                        FakeNode(name = "logo.png", isDirectory = false, declaredSizeBytes = 128L)
                    )
                ),
                FakeNode(
                    name = "scripts",
                    isDirectory = true,
                    children = listOf(
                        FakeNode(name = "START.lsb", isDirectory = false, declaredSizeBytes = 512L)
                    )
                ),
                FakeNode(name = "root.dat", isDirectory = false)
            )
        )

        val report = DirectoryStructureScanner().scan(root)

        assertEquals(listOf("assets", "assets/empty", "scripts"), report.directories)
        assertEquals(
            listOf("assets/logo.png", "scripts/START.lsb", "root.dat"),
            report.files.map { it.relativePath }
        )
        assertEquals(128L, report.files[0].sizeBytes)
        assertNull(report.files[2].sizeBytes)
        assertEquals("game", report.rootName)
        assertFalse(report.truncated)
        assertEquals(0, root.openStreamCalls)
    }

    @Test
    fun marksReportTruncatedWhenEntryLimitIsReached() {
        val root = FakeNode(
            name = "game",
            isDirectory = true,
            children = listOf(
                FakeNode(
                    name = "a",
                    isDirectory = true,
                    children = listOf(FakeNode("one.dat", isDirectory = false))
                ),
                FakeNode(name = "b.dat", isDirectory = false)
            )
        )

        val report = DirectoryStructureScanner(maxEntries = 2).scan(root)

        assertEquals(1, report.directories.size)
        assertEquals(1, report.files.size)
        assertTrue(report.truncated)
        assertTrue(report.warnings.any { it.contains("limit") })
    }

    @Test
    fun marksReportTruncatedWhenDepthLimitSkipsChildren() {
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
    fun rejectsUnsafePathSegments() {
        val root = FakeNode(
            name = "game",
            isDirectory = true,
            children = listOf(FakeNode("../outside.lsb", isDirectory = false))
        )

        val error = runCatching { DirectoryStructureScanner().scan(root) }.exceptionOrNull()

        assertTrue(error is IOException)
    }

    private class FakeNode(
        override val name: String?,
        override val isDirectory: Boolean,
        override val declaredSizeBytes: Long? = null,
        private val children: List<FakeNode> = emptyList()
    ) : FolderEntryNode {
        var openStreamCalls: Int = 0
            private set

        override fun listChildren(): List<FolderEntryNode> = children

        override fun openInputStream(): InputStream {
            openStreamCalls += 1
            return ByteArrayInputStream(byteArrayOf())
        }
    }
}
