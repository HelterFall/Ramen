package io.github.helterfall.ramen.runtime.systemnnn

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SystemNnnAssetManagerTest {

    @Test
    fun discoversMultipleImageBanksWithoutInventingAGlobalIndex() {
        val root = Files.createTempDirectory("ramen-systemnnn-asset-manager").toFile()
        try {
            createImageBank(File(root, "a/scene"))
            createImageBank(File(root, "b/scene"))

            SystemNnnAssetManager.forGameDirectory(
                gameDirectory = root,
                cacheDirectory = File(root, "cache")
            ).use { manager ->
                assertEquals(2, manager.catalog.imageBanks.size)
                assertEquals(
                    listOf("a/scene.gtb", "b/scene.gtb"),
                    manager.catalog.imageBanks.map { it.id }
                )
                assertEquals(2, manager.catalog.totalIndexedImageResources)
                assertEquals(2, manager.resourceCount)

                // The same local row exists in both banks; neither name nor row
                // number can identify one image without a bank qualifier.
                assertThrows(IOException::class.java) {
                    manager.loadBitmap("shared-background")
                }
                assertThrows(IOException::class.java) {
                    manager.loadBitmapByIndex(0L)
                }
                assertThrows(IOException::class.java) {
                    manager.loadBitmapByBankIndex("missing/scene.gtb", 0L)
                }
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
                assertEquals(null, manager.loadBitmapByIndex(0L))
            }
        } finally {
            root.deleteRecursively()
        }
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
