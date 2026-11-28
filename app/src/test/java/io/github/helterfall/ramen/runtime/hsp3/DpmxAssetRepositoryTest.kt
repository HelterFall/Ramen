package io.github.helterfall.ramen.runtime.hsp3

import io.github.helterfall.ramen.inspection.DpmxContainer
import io.github.helterfall.ramen.inspection.DpmxContainerParser
import io.github.helterfall.ramen.inspection.DpmxCipherVersion
import io.github.helterfall.ramen.inspection.DpmxKeyResolver
import io.github.helterfall.ramen.inspection.FileRandomAccessSource
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DpmxAssetRepositoryTest {
    @Test
    fun findsCaseInsensitiveLocalAssetsFromImportedRootWhenExecutableIsNested() {
        val root = Files.createTempDirectory("ramen-hsp-asset-root").toFile()
        try {
            val executableDirectory = File(root, "bin/runtime")
            assertTrue(executableDirectory.mkdirs())
            val executable = File(executableDirectory, "Demonophobia.exe")
            executable.writeBytes(byteArrayOf(0x4D, 0x5A))

            val imageDirectory = File(root, "Images/UI")
            assertTrue(imageDirectory.mkdirs())
            val expected = byteArrayOf(1, 2, 3, 4, 5)
            File(imageDirectory, "Continue.BMP").writeBytes(expected)

            DpmxAssetRepository(
                executable = executable,
                container = emptyContainer(),
                keyResolver = DpmxKeyResolver(),
                assetRoot = root
            ).use { assets ->
                assertArrayEquals(expected, assets.read("images/ui/continue.bmp"))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsTraversalBeforeLookingUpLocalOrArchivedAssets() {
        val root = Files.createTempDirectory("ramen-hsp-asset-traversal").toFile()
        try {
            val executableDirectory = File(root, "bin")
            assertTrue(executableDirectory.mkdirs())
            val executable = File(executableDirectory, "Demonophobia.exe")
            executable.writeBytes(byteArrayOf(0x4D, 0x5A))

            val outside = File(root.parentFile, "outside-secret.bin")
            outside.writeBytes(byteArrayOf(9, 8, 7))
            try {
                DpmxAssetRepository(
                    executable = executable,
                    container = emptyContainer(),
                    keyResolver = DpmxKeyResolver(),
                    assetRoot = root
                ).use { assets ->
                    assertThrows(java.io.IOException::class.java) {
                        assets.read("../outside-secret.bin")
                    }
                }
            } finally {
                outside.delete()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun decryptsArchiveResourceUsingEmbeddedDeckeyAndPerEntryFileKey() {
        val root = Files.createTempDirectory("ramen-hsp-dpmx-resource").toFile()
        try {
            val executable = File(root, "game.exe")
            val expected = byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x01
            )
            val overlayOffset = 0x21000
            val dataOffset = 0x80
            val deckey = 0x81AA9ECDL
            val fileKey = 0xB2B8EDB5L
            val sumSize = dataOffset + expected.size - 16L
            val key = deriveEntryKey(deckey, fileKey, sumSize, DpmxCipherVersion.HSP_26_TO_32)
            val encrypted = encrypt(expected, key)
            val bytes = ByteArray(overlayOffset + dataOffset + encrypted.size)

            bytes[0] = 'M'.code.toByte()
            bytes[1] = 'Z'.code.toByte()
            putUInt32(bytes, 0x3C, 0x80L)
            "PE\u0000\u0000".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0x80)
            putUInt16(bytes, 0x86, 1)
            putUInt16(bytes, 0x94, 0xE0)
            val optional = 0x98
            putUInt16(bytes, optional, 0x10B)
            putUInt32(bytes, optional + 28, 0x00400000L)
            val section = optional + 0xE0
            ".data".toByteArray(Charsets.US_ASCII).copyInto(bytes, section)
            putUInt32(bytes, section + 8, 0x200L)
            putUInt32(bytes, section + 12, 0x1000L)
            putUInt32(bytes, section + 16, 0x200L)
            putUInt32(bytes, section + 20, 0x200L)

            "DPMX".toByteArray(Charsets.US_ASCII).copyInto(bytes, overlayOffset)
            putUInt32(bytes, overlayOffset + 4, dataOffset.toLong())
            putUInt32(bytes, overlayOffset + 8, 1L)
            putUInt32(bytes, overlayOffset + 12, 0L)
            val record = overlayOffset + 16
            "bg/forest.png".toByteArray(Charsets.US_ASCII).copyInto(bytes, record)
            putUInt32(bytes, record + 16, 0xFFFF_FFFFL)
            putUInt32(bytes, record + 20, fileKey)
            putUInt32(bytes, record + 24, 0L)
            putUInt32(bytes, record + 28, encrypted.size.toLong())
            encrypted.copyInto(bytes, overlayOffset + dataOffset)

            writeHspExecutableMetadata(bytes, overlayOffset, deckey)
            executable.writeBytes(bytes)

            val container = DpmxContainerParser().parse(executable)
            assertEquals(overlayOffset.toLong(), container.overlayOffset)
            assertEquals(true, container.executableMetadata?.checksumValid)

            FileRandomAccessSource(executable).use { source ->
                val report = DpmxKeyResolver().verifyArchiveResources(source, container)
                assertEquals(1, report.signatureValidatedEntries)
                assertEquals(0, report.unresolvedEncryptedEntries)
            }

            DpmxAssetRepository(
                executable = executable,
                container = container,
                keyResolver = DpmxKeyResolver(),
                assetRoot = root
            ).use { assets ->
                assertArrayEquals(expected, assets.read("bg/forest.png"))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun deriveEntryKey(
        deckey: Long,
        fileKey: Long,
        sumSize: Long,
        version: DpmxCipherVersion
    ): io.github.helterfall.ramen.inspection.DpmxCipherKey {
        val d1 = deckey.toInt() and 0xFF
        val d2 = (deckey.toInt() ushr 8) and 0xFF
        val d3 = (deckey.toInt() ushr 16) and 0xFF
        val d4 = (deckey.toInt() ushr 24) and 0xFF
        val seed1 = (((d1 * d3 / 3) xor sumSize.toInt()) and 0xFF)
        val seed2 = (((d2 * d4 / 5) xor sumSize.toInt() xor 0xAA) and 0xFF)
        val f1 = fileKey.toInt() and 0xFF
        val f2 = (fileKey.toInt() ushr 8) and 0xFF
        val f3 = (fileKey.toInt() ushr 16) and 0xFF
        val f4 = (fileKey.toInt() ushr 24) and 0xFF
        val a = if (version == DpmxCipherVersion.HSP_33_TO_36) 0x5A else 0x55
        val b = if (version == DpmxCipherVersion.HSP_33_TO_36) 0xA5 else 0xAA
        val fileSeed1 = (f1 + a) xor f3
        val fileSeed2 = (f2 + b) xor f4
        return io.github.helterfall.ramen.inspection.DpmxCipherKey(
            al = (seed2 + fileSeed2) and 0xFF,
            cl = (seed1 + fileSeed1) and 0xFF,
            version = version
        )
    }

    private fun encrypt(
        plain: ByteArray,
        key: io.github.helterfall.ramen.inspection.DpmxCipherKey
    ): ByteArray {
        val encrypted = ByteArray(plain.size)
        var previous = key.initialDl and 0xFF
        for (index in plain.indices) {
            val current = plain[index].toInt() and 0xFF
            val delta = (current - previous) and 0xFF
            val encoded = when (key.version) {
                DpmxCipherVersion.HSP_26_TO_32 -> ((delta xor key.cl) + key.al) and 0xFF
                DpmxCipherVersion.HSP_33_TO_36 -> ((delta + key.al) and 0xFF) xor key.cl
            }
            encrypted[index] = encoded.toByte()
            previous = current
        }
        return encrypted
    }

    private fun writeHspExecutableMetadata(bytes: ByteArray, dpmOffset: Int, deckey: Long) {
        val offset = 0x300
        (dpmOffset - 0x10000).toString().toByteArray(Charsets.US_ASCII)
            .copyInto(bytes, offset + 9)
        bytes[offset + 17] = 0
        bytes[offset + 18] = 0
        bytes[offset + 19] = 'x'.code.toByte()
        bytes[offset + 22] = 'y'.code.toByte()
        bytes[offset + 25] = 'd'.code.toByte()
        bytes[offset + 28] = 's'.code.toByte()
        bytes[offset + 31] = 'k'.code.toByte()
        putUInt32(bytes, offset + 32, deckey)
        val sumSeed = ((deckey ushr 24) and 0xFFL).toInt() / 7
        var checksum = 0
        for (index in dpmOffset until bytes.size) {
            checksum = (checksum + (bytes[index].toInt() and 0xFF) + sumSeed) and 0xFFFF
        }
        putUInt16(bytes, offset + 29, checksum)
    }

    private fun putUInt16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun putUInt32(bytes: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 4) bytes[offset + index] = (value ushr (index * 8)).toByte()
    }

    private fun emptyContainer() = DpmxContainer(
        peOffset = 0L,
        imageBase = 0L,
        sections = emptyList(),
        overlayOffset = 0L,
        dataOffset = 0L,
        dataAbsoluteOffset = 0L,
        fileCount = 0,
        optionalNameLength = 0,
        entries = emptyList()
    )
}
