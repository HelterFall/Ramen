package io.github.helterfall.ramen.runtime.systemnnn

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SystemNnnAssetManagerTest {

    @Test
    fun discoversMultipleImageBanksWithoutInventingAGlobalIndex() {
        val root = Files.createTempDirectory("ramen-systemnnn-asset-manager").toFile()
        try {
            createImageBank(File(root, "a/sc0"))
            createImageBank(File(root, "b/sc0"))

            SystemNnnAssetManager.forGameDirectory(
                gameDirectory = root,
                cacheDirectory = File(root, "cache")
            ).use { manager ->
                assertEquals(2, manager.catalog.imageBanks.size)
                assertEquals(
                    listOf("a/sc0.gtb", "b/sc0.gtb"),
                    manager.catalog.imageBanks.map { it.id }
                )
                assertEquals(2, manager.catalog.totalIndexedImageResources)
                assertEquals(2, manager.resourceCount)
                assertEquals("sc", manager.catalog.imageBanks.first().nativeNamespace)
                assertEquals(0, manager.catalog.imageBanks.first().bankSlot)

                // The same local row exists in both banks; neither name nor row
                // number can identify one image without a bank qualifier.
                assertThrows(IOException::class.java) {
                    manager.loadBitmap("shared-background")
                }
                assertThrows(IOException::class.java) {
                    manager.loadBitmapByIndex(0L)
                }
                assertThrows(IOException::class.java) {
                    manager.loadBitmapByBankIndex("missing/sc0.gtb", 0L)
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun fallsBackToSameStemWgqWhenPreferredGpkIsInvalid() {
        val root = Files.createTempDirectory("ramen-systemnnn-image-fallback").toFile()
        try {
            val base = File(root, "dwq/scene")
            createImageBank(base)
            val gpk = File(root, "dwq/scene.gpk")
            val validArchive = gpk.readBytes()
            gpk.writeBytes(byteArrayOf(1, 2, 3))
            File(root, "dwq/scene.wgq").writeBytes(validArchive)

            SystemNnnAssetManager.forGameDirectory(
                gameDirectory = root,
                cacheDirectory = File(root, "cache")
            ).use { manager ->
                assertEquals(1, manager.catalog.imageBanks.size)
                assertEquals("dwq/scene.wgq", manager.catalog.imageBanks.single().archivePath)
                assertEquals(1, manager.catalog.totalIndexedImageResources)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun discoversIndexedVpkVoiceBankAndKeepsItsRowsBankLocal() {
        val root = Files.createTempDirectory("ramen-systemnnn-audio-bank").toFile()
        try {
            val directory = File(root, "cdvaw").apply { mkdirs() }
            val archive = createVpkAudioBlock()
            File(directory, "gg.vpk").writeBytes(archive)
            File(directory, "gg.vtb").writeBytes(createVtbIndex("track001", 0))

            SystemNnnAssetManager.forGameDirectory(
                gameDirectory = root,
                cacheDirectory = File(root, "cache")
            ).use { manager ->
                assertEquals(1, manager.catalog.audioBanks.size)
                assertEquals("cdvaw/gg.vtb", manager.catalog.audioBanks.single().id)
                assertEquals(1, manager.catalog.totalIndexedAudioResources)
                assertEquals("track001", manager.entries.single().name)
                assertEquals("track001", manager.findResource("TRACK001")?.name)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun fallsBackToVpkWhenPreferredSameStemVawIsInvalid() {
        val root = Files.createTempDirectory("ramen-systemnnn-audio-fallback").toFile()
        try {
            val directory = File(root, "cdvaw").apply { mkdirs() }
            File(directory, "gg.vaw").writeBytes(byteArrayOf(1, 2, 3))
            File(directory, "gg.vpk").writeBytes(createVpkAudioBlock())
            File(directory, "gg.vtb").writeBytes(createVtbIndex("track001", 0))

            SystemNnnAssetManager.forGameDirectory(
                gameDirectory = root,
                cacheDirectory = File(root, "cache")
            ).use { manager ->
                assertEquals("cdvaw/gg.vpk", manager.catalog.audioBanks.single().archivePath)
                assertEquals(listOf("cdvaw/gg.vaw"), manager.catalog.unindexedVawFiles)
                assertEquals("track001", manager.entries.single().name)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun keepsUnindexedWgqFilesVisibleWithoutTreatingThemAsImages() {
        val root = Files.createTempDirectory("ramen-systemnnn-unindexed-wgq").toFile()
        try {
            val wgqDirectory = File(root, "wgq").apply { mkdirs() }
            val file = File(wgqDirectory, "gss01_1.wgq")
            File(wgqDirectory, "gsstxt01.mml").writeText("synthetic MML metadata")
            val bytes = ByteArray(128) { ' '.code.toByte() }
            "OGG".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0)
            "PACKTYPE=6       ".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0x30)
            "OggS".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0x40)
            file.writeBytes(bytes)

            SystemNnnAssetManager.forGameDirectory(
                gameDirectory = root,
                cacheDirectory = File(root, "cache")
            ).use { manager ->
                assertTrue(manager.catalog.imageBanks.isEmpty())
                assertEquals(listOf("wgq/gss01_1.wgq"), manager.catalog.unindexedWgqFiles)
                assertEquals(listOf("wgq/gsstxt01.mml"), manager.catalog.unindexedMmlFiles)
                assertNull(manager.loadBitmapByIndex(0L))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createVtbIndex(name: String, offset: Int): ByteArray {
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        require(nameBytes.size == 8)
        val index = ByteArray(12)
        nameBytes.copyInto(index, 0)
        putU32(index, 8, offset)
        return index
    }

    private fun createVpkAudioBlock(): ByteArray {
        val ogg = createOggPage(flags = 0x02, sequence = 0) +
            createOggPage(flags = 0x04, sequence = 1)
        val result = ByteArray(108) + ogg
        "IF PACKTYPE==2  CUT THIS 108BYTETHEN REMAKE OGG PACKTYPE=2      "
            .toByteArray(Charsets.US_ASCII)
            .copyInto(result, 0)
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(result, 64)
        putU32(result, 68, 40)
        "WAVEfmt ".toByteArray(Charsets.US_ASCII).copyInto(result, 72)
        putU32(result, 80, 16)
        putU16(result, 84, 1)
        putU16(result, 86, 1)
        putU32(result, 88, 44_100)
        putU32(result, 92, 88_200)
        putU16(result, 96, 2)
        putU16(result, 98, 16)
        "data".toByteArray(Charsets.US_ASCII).copyInto(result, 100)
        putU32(result, 104, 4)
        return result
    }

    private fun createOggPage(flags: Int, sequence: Int): ByteArray {
        val page = ByteArray(31)
        "OggS".toByteArray(Charsets.US_ASCII).copyInto(page, 0)
        page[4] = 0
        page[5] = flags.toByte()
        putU32(page, 14, 42)
        putU32(page, 18, sequence)
        page[26] = 1
        page[27] = 3
        page[28] = 'a'.code.toByte()
        page[29] = 'b'.code.toByte()
        page[30] = 'c'.code.toByte()
        return page
    }

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun createImageBank(baseFile: File) {
        val directory = baseFile.parentFile ?: error("Image-bank fixture has no parent.")
        directory.mkdirs()
        val archiveBytes = ByteArray(64) { ' '.code.toByte() } + createJpegWithSof(1, 1)
        File(directory, baseFile.name + ".gpk").writeBytes(archiveBytes)

        val name = "shared-background".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        val index = ByteArray(12 + name.size)
        putU32(index, 0, 1)
        putU32(index, 4, 0)
        putU32(index, 8, 0)
        name.copyInto(index, 12)
        File(directory, baseFile.name + ".gtb").writeBytes(index)
    }

    private fun createJpegWithSof(width: Int, height: Int): ByteArray =
        byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xC0.toByte(),
            0x00, 0x0B,
            0x08,
            (height ushr 8).toByte(), height.toByte(),
            (width ushr 8).toByte(), width.toByte(),
            0x01,
            0x01, 0x11, 0x00,
            0xFF.toByte(), 0xD9.toByte()
        )

    private fun putU32(bytes: ByteArray, offset: Int, value: Int) {
        repeat(4) { index ->
            bytes[offset + index] = (value ushr (index * 8)).toByte()
        }
    }
}
