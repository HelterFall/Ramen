package io.github.helterfall.ramen.runtime.hsp3

import io.github.helterfall.ramen.inspection.DpmxContainer
import io.github.helterfall.ramen.inspection.DpmxKeyResolver
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
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
