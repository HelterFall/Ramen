package io.github.helterfall.ramen.runtime.renpy

import io.github.helterfall.ramen.library.GameRecord
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RenPyGamePathsTest {
    @Test
    fun resolvesGameInsideImportedBaseDirectory() {
        val filesRoot = Files.createTempDirectory("ramen-renpy-paths").toFile()
        try {
            val gameRoot = File(filesRoot, "games/demo-1/game").apply { mkdirs() }
            File(gameRoot, "script.rpyc").writeBytes("RENPY RPC2".toByteArray())

            val paths = RenPyGamePaths.resolve(filesRoot, record())

            assertEquals(gameRoot.canonicalFile, paths.gameDirectory)
            assertEquals(File(filesRoot, "renpy-data/demo-1/saves").canonicalFile, paths.savesRoot)
            assertEquals(File(filesRoot, "renpy-data/demo-1/logs").canonicalFile, paths.logDirectory)
            assertTrue(paths.savesRoot.isDirectory)
            assertTrue(paths.logDirectory.isDirectory)
        } finally {
            filesRoot.deleteRecursively()
        }
    }

    @Test
    fun resolvesWhenImportedFolderIsAlreadyTheGameDirectory() {
        val filesRoot = Files.createTempDirectory("ramen-renpy-direct-game").toFile()
        try {
            val gameRoot = File(filesRoot, "games/demo-1").apply { mkdirs() }
            File(gameRoot, "options.rpy").writeText("label start:\n    \"hello\"")

            val paths = RenPyGamePaths.resolve(filesRoot, record())

            assertEquals(gameRoot.canonicalFile, paths.gameDirectory)
        } finally {
            filesRoot.deleteRecursively()
        }
    }

    @Test
    fun acceptsRpaOnlyDirectoryBecauseRenpyScriptsMayBePackedIntoRpa() {
        val filesRoot = Files.createTempDirectory("ramen-renpy-rpa-only").toFile()
        try {
            val gameRoot = File(filesRoot, "games/demo-1/game").apply { mkdirs() }
            File(gameRoot, "ZATO_arch.rpa").writeBytes("RPA-3.0".toByteArray())

            val paths = RenPyGamePaths.resolve(filesRoot, record())

            assertEquals(gameRoot.canonicalFile, paths.gameDirectory)
            assertTrue(File(paths.gameDirectory, "ZATO_arch.rpa").isFile)
        } finally {
            filesRoot.deleteRecursively()
        }
    }

    @Test
    fun findsScriptFilesInNestedGameSubdirectories() {
        val filesRoot = Files.createTempDirectory("ramen-renpy-nested-script").toFile()
        try {
            val gameRoot = File(filesRoot, "games/demo-1/game/scripts").apply { mkdirs() }
            File(gameRoot, "chapter_1.rpyc").writeBytes("RENPY RPC2".toByteArray())

            val paths = RenPyGamePaths.resolve(filesRoot, record())

            assertEquals(gameRoot.parentFile.canonicalFile, paths.gameDirectory)
        } finally {
            filesRoot.deleteRecursively()
        }
    }

    @Test
    fun resolvesGameDirectoryInsideAnArchiveWrapperFolder() {
        val filesRoot = Files.createTempDirectory("ramen-renpy-zip-wrapper").toFile()
        try {
            val gameRoot = File(filesRoot, "games/demo-1/MyNovel-1.0/game/scripts").apply { mkdirs() }
            File(gameRoot, "chapter_1.rpyc").writeBytes("RENPY RPC2".toByteArray())

            val paths = RenPyGamePaths.resolve(filesRoot, record())

            assertEquals(gameRoot.parentFile.canonicalFile, paths.gameDirectory)
        } finally {
            filesRoot.deleteRecursively()
        }
    }

    @Test
    fun rejectsAmbiguousNestedGameDirectories() {
        val filesRoot = Files.createTempDirectory("ramen-renpy-ambiguous-game").toFile()
        try {
            val first = File(filesRoot, "games/demo-1/disc-1/game").apply { mkdirs() }
            val second = File(filesRoot, "games/demo-1/disc-2/game").apply { mkdirs() }
            File(first, "script.rpyc").writeBytes("RENPY RPC2".toByteArray())
            File(second, "script.rpyc").writeBytes("RENPY RPC2".toByteArray())

            try {
                RenPyGamePaths.resolve(filesRoot, record())
                fail("Expected ambiguous game/ directories to be rejected.")
            } catch (_: IOException) {
                // Expected: guessing which archive root is correct is unsafe.
            }
        } finally {
            filesRoot.deleteRecursively()
        }
    }

    @Test
    fun rejectsGamePathTraversal() {
        val filesRoot = Files.createTempDirectory("ramen-renpy-traversal").toFile()
        try {
            try {
                RenPyGamePaths.resolve(filesRoot, record(relativeDirectory = "games/../outside"))
                fail("Expected invalid path to be rejected.")
            } catch (_: IOException) {
                // Expected.
            }
        } finally {
            filesRoot.deleteRecursively()
        }
    }

    @Test
    fun rejectsAnImportWithoutRenpyPayload() {
        val filesRoot = Files.createTempDirectory("ramen-renpy-empty-game").toFile()
        try {
            File(filesRoot, "games/demo-1").mkdirs()
            try {
                RenPyGamePaths.resolve(filesRoot, record())
                fail("Expected a missing script payload to be rejected.")
            } catch (_: IOException) {
                // Expected.
            }
        } finally {
            filesRoot.deleteRecursively()
        }
    }

    private fun record(relativeDirectory: String = "games/demo-1") = GameRecord(
        id = "demo-1",
        title = "Test game",
        relativeDirectory = relativeDirectory,
        engineId = "renpy",
        engineLabel = "Ren'Py",
        compatibilityStatus = "DETECTED_UNSUPPORTED",
        evidence = "script.rpyc",
        importedAtEpochMillis = 0L
    )
}
