package io.github.helterfall.ramen.runtime.resources

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportedResourcePairScannerTest {
    @Test
    fun findsImageAndAudioPairsRecursively() {
        val root = Files.createTempDirectory("ramen-pair-scanner").toFile()
        try {
            val init = File(root, "init").apply { mkdirs() }
            File(init, "bg0.gpk").writeBytes(byteArrayOf(1))
            File(init, "bg0.gtb").writeBytes(byteArrayOf(2))

            val audio = File(root, "sound").apply { mkdirs() }
            File(audio, "aa.VPK").writeBytes(byteArrayOf(3))
            File(audio, "aa.vtb").writeBytes(byteArrayOf(4))

            val result = ImportedResourcePairScanner().scan(root)

            assertEquals(2, result.size)
            assertEquals(ResourceArchiveFamily.IMAGES, result[0].family)
            assertEquals("init/bg0.gpk", result[0].relativePath)
            assertEquals("bg0.gpk", result[0].archiveFile.name)
            assertEquals("bg0.gtb", result[0].indexFile.name)

            assertEquals(ResourceArchiveFamily.AUDIO, result[1].family)
            assertEquals("sound/aa.VPK", result[1].relativePath)
            assertEquals("aa.VPK", result[1].archiveFile.name)
            assertEquals("aa.vtb", result[1].indexFile.name)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun doesNotPairFilesWithDifferentNamesOrDifferentParentFolders() {
        val root = Files.createTempDirectory("ramen-pair-scanner-unmatched").toFile()
        try {
            val first = File(root, "first").apply { mkdirs() }
            val second = File(root, "second").apply { mkdirs() }
            File(first, "bg0.gpk").writeBytes(byteArrayOf(1))
            File(second, "bg0.gtb").writeBytes(byteArrayOf(2))
            File(root, "other.vpk").writeBytes(byteArrayOf(3))
            File(root, "different.vtb").writeBytes(byteArrayOf(4))

            assertTrue(ImportedResourcePairScanner().scan(root).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsDirectoriesThatExceedEntryLimit() {
        val root = Files.createTempDirectory("ramen-pair-scanner-limit").toFile()
        try {
            File(root, "a.gpk").writeBytes(byteArrayOf(1))
            File(root, "a.gtb").writeBytes(byteArrayOf(2))
            File(root, "note.txt").writeBytes(byteArrayOf(3))

            val result = runCatching {
                ImportedResourcePairScanner(maxEntries = 2).scan(root)
            }
            assertTrue(result.exceptionOrNull() is IOException)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsNonDirectoryRoot() {
        val directory = Files.createTempDirectory("ramen-pair-scanner-file").toFile()
        try {
            val file = File(directory, "not-a-directory").apply { writeBytes(byteArrayOf(1)) }
            val result = runCatching { ImportedResourcePairScanner().scan(file) }
            assertTrue(result.exceptionOrNull() is IOException)
        } finally {
            directory.deleteRecursively()
        }
    }
}
