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
                assertEquals(png.id, repository.findAsset("images/cover.png")?.id)
                assertEquals(ogg.id, repository.findAsset("intro.ogg")?.id)
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
    fun indexesJapaneseMediaPathsFromEmbeddedExeAndReportsArchiveDiagnostics() {
        val root = Files.createTempDirectory("ramen-livemaker-exe-media").toFile()
        try {
            val galBytes = byteArrayOf(0x47, 0x41, 0x4C, 0x01)
            val oggBytes = byteArrayOf(0x4F, 0x67, 0x67, 0x53)
            val wavBytes = byteArrayOf(0x52, 0x49, 0x46, 0x46)
            File(root, "Game.exe").writeBytes(
                LiveMakerArchiveFixture.executable(
                    listOf(
                        LiveMakerFixtureFile("グラフィック\\背景\\リビング.gal", galBytes),
                        LiveMakerFixtureFile("SE\\ガラス壊れ.wav", wavBytes),
                        LiveMakerFixtureFile("ボイス\\intro.ogg", oggBytes),
                        LiveMakerFixtureFile("グラフィック\\選択肢.lpm", byteArrayOf(1, 2, 3))
                    ),
                    archiveOffset = 64
                )
            )

            LiveMakerMediaAssetRepository(root).use { repository ->
                val diagnostics = repository.diagnostics
                assertEquals(1, diagnostics.filesScanned)
                assertEquals(1, diagnostics.archiveCandidates)
                assertEquals(1, diagnostics.archivesParsed)
                assertEquals(4, diagnostics.archiveEntriesVisited)
                assertEquals(0, diagnostics.rejectedArchivePaths)
                assertEquals(0, diagnostics.archiveParseFailures)
                assertEquals(3, diagnostics.archiveAssetCount)
                assertEquals(0, diagnostics.looseAssetCount)
                assertEquals(3, diagnostics.indexedAssetCount)
                assertEquals(1, diagnostics.indexedByExtension["gal"])
                assertEquals(1, diagnostics.indexedByExtension["ogg"])
                assertEquals(1, diagnostics.indexedByExtension["wav"])

                val gal = repository.findAsset("グラフィック/背景/リビング.gal")
                val ogg = repository.findAsset("intro.ogg")
                val wav = repository.findAsset("SE/ガラス壊れ.wav")
                assertTrue(gal != null)
                assertTrue(ogg != null)
                assertTrue(wav != null)
                assertTrue(repository.readAsset(gal!!).contentEquals(galBytes))
                assertTrue(repository.readAsset(ogg!!).contentEquals(oggBytes))
                assertTrue(repository.readAsset(wav!!).contentEquals(wavBytes))
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
