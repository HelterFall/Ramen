package io.github.helterfall.ramen.runtime.livemaker

import io.github.helterfall.ramen.inspection.LiveMakerArchiveFixture
import io.github.helterfall.ramen.inspection.LiveMakerFixtureFile
import io.github.helterfall.ramen.inspection.LiveMakerLsbFixture
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveMakerStartupAndMediaRepositoryTest {
    @Test
    fun conventionalStartPageNamesTakePrecedenceOverFirstPage() {
        assertEquals(
            "chapter/ゲームメイン.lsb",
            LiveMakerStartupScriptResolver.selectStartupPage(
                listOf("z-first.lsb", "ノベルシステム/START.lsb", "chapter/ゲームメイン.lsb")
            )
        )
        assertEquals(
            "ノベルシステム/START.lsb",
            LiveMakerStartupScriptResolver.selectStartupPage(
                listOf("z-first.lsb", "ノベルシステム\\START.lsb")
            )
        )
        assertEquals(
            "a-first.lsb",
            LiveMakerStartupScriptResolver.selectStartupPage(listOf("z.lsb", "a-first.lsb"))
        )
        assertEquals(null, LiveMakerStartupScriptResolver.selectStartupPage(listOf("image.gal")))
    }

    @Test
    fun opensStartupSessionFromEmbeddedArchiveAndRunsInitialScript() {
        val root = Files.createTempDirectory("ramen-livemaker-bootstrap").toFile()
        try {
            val lsb = LiveMakerLsbFixture.build(
                commands = listOf(LiveMakerLsbFixture.variableNew("booted", 7))
            )
            val archive = LiveMakerArchiveFixture.executable(
                listOf(LiveMakerFixtureFile("data/ゲームメイン.lsb", lsb)),
                archiveOffset = 48
            )
            File(root, "Game.exe").writeBytes(archive)
            File(root, "ノベルシステム/START.lsb").apply {
                parentFile?.mkdirs()
                writeBytes(LiveMakerLsbFixture.build(commands = emptyList()))
            }

            val opened = LiveMakerStartupScriptResolver(root).openSession()
            opened.session.use { session ->
                assertEquals("data/ゲームメイン.lsb", opened.pageReference)
                assertEquals(103, opened.scriptVersion)
                assertTrue(opened.commandCount > 0)
                val result = session.runUntilEvent() as LiveMakerSessionEvent.EngineEvent
                assertTrue(result.event is LiveMakerVmEvent.Completed)
                assertEquals(7, session.variablesSnapshot()["booted"])
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun indexesLooseAndVfMediaAndStagesAudioWithOriginalBytes() {
        val root = Files.createTempDirectory("ramen-livemaker-media").toFile()
        try {
            val pngBytes = byteArrayOf(0x50, 0x4E, 0x47, 0x0D, 0x0A)
            File(root, "images/cover.png").apply {
                parentFile?.mkdirs()
                writeBytes(pngBytes)
            }
            val oggBytes = "OggS synthetic payload".toByteArray()
            File(root, "Media.vf").writeBytes(
                LiveMakerArchiveFixture.standalone(
                    listOf(
                        LiveMakerFixtureFile(
                            "audio/intro.ogg",
                            LiveMakerArchiveFixture.zlib(oggBytes),
                            compressionFlag = 0
                        )
                    )
                )
            )

            LiveMakerMediaAssetRepository(root).use { repository ->
                val assets = repository.listAssets()
                assertEquals(2, assets.size)
                val png = assets.single { it.extension == "png" }
                val ogg = assets.single { it.extension == "ogg" }
                assertTrue(repository.readAsset(png).contentEquals(pngBytes))
                assertTrue(repository.readAsset(ogg).contentEquals(oggBytes))

                val staged = repository.stageAudio(ogg, File(root, "cache"))
                try {
                    assertTrue(staged.isFile)
                    assertTrue(staged.readBytes().contentEquals(oggBytes))
                } finally {
                    staged.delete()
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun excludesOversizedLooseMediaFilesFromIndex() {
        val root = Files.createTempDirectory("ramen-livemaker-media-limit").toFile()
        try {
            File(root, "large.wav").writeBytes(ByteArray(32))
            LiveMakerMediaAssetRepository(root, maxAssetBytes = 16L).use { repository ->
                assertTrue(repository.listAssets().isEmpty())
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
