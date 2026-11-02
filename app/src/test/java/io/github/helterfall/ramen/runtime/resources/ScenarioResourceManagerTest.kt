package io.github.helterfall.ramen.runtime.resources

import org.junit.Assert.assertEquals
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioResourceManagerTest {
    @Test
    fun matchesFullResourcePathBeforeBasename() {
        assertEquals(0, GpkResourceNameMatcher.rank("background/shore", "background/shore"))
        assertEquals(1, GpkResourceNameMatcher.rank("background/shore.png", "shore.png"))
    }

    @Test
    fun matchesFilenameWithoutExtensionAsFallback() {
        assertEquals(2, GpkResourceNameMatcher.rank("background/shore.jpg", "shore"))
        assertEquals(2, GpkResourceNameMatcher.rank("shore", "shore.bmp"))
    }

    @Test
    fun resolvesBackgroundToValidatedGpkPayloadRange() {
        withGameDirectory { root ->
            writeImagePair(root, "images/bg", "shore")

            val location = ScenarioResourceManager(root)
                .resolveBackground("background/shore.png")

            assertEquals("shore", location.resource.name)
            assertEquals(64L, location.resource.payloadOffset)
            assertEquals(4L, location.resource.payloadSizeBytes)
            assertEquals("images/bg.gpk", location.archiveRelativePath)
        }
    }

    @Test
    fun malformedArchivePairDoesNotHideAnotherValidBackground() {
        withGameDirectory { root ->
            writeImagePair(root, "images/good", "shore")
            File(root, "images/bad.gpk").writeBytes(byteArrayOf(1, 2, 3))
            File(root, "images/bad.gtb").writeBytes(byteArrayOf(1, 2, 3))

            val location = ScenarioResourceManager(root).resolveBackground("shore")

            assertEquals("shore", location.resource.name)
            assertEquals("images/good.gpk", location.archiveRelativePath)
        }
    }

    @Test
    fun rejectsUnsafeBackgroundKeysBeforeResourceLookup() {
        withGameDirectory { root ->
            writeImagePair(root, "images/bg", "shore")

            val failure = runCatching {
                ScenarioResourceManager(root).resolveBackground("../shore")
            }.exceptionOrNull()

            assertTrue(failure is java.io.IOException)
            assertTrue(failure?.message.orEmpty().contains("safe relative path"))
        }
    }

    private fun writeImagePair(root: File, relativeStem: String, resourceName: String) {
        val archiveFile = File(root, relativeStem + ".gpk")
        val indexFile = File(root, relativeStem + ".gtb")
        archiveFile.parentFile?.mkdirs()

        val payload = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01)
        val block = ByteArray(64) { ' '.code.toByte() }
        "JPEG".toByteArray(Charsets.US_ASCII).copyInto(block, 0)
        putU32(block, 32, payload.size.toLong())
        putU32(block, 36, 1L)
        putU32(block, 40, 1L)
        "    PACKTYPE=5      ".toByteArray(Charsets.US_ASCII).copyInto(block, 44)
        archiveFile.writeBytes(block + payload)

        val name = resourceName.toByteArray(Charsets.US_ASCII)
        val index = ByteArray(13 + name.size)
        putU32(index, 0, 1L)
        putU32(index, 4, 0L)
        putU32(index, 8, 0L)
        name.copyInto(index, 12)
        index[12 + name.size] = 0
        indexFile.writeBytes(index)
    }

    private fun putU32(target: ByteArray, offset: Int, value: Long) {
        ByteBuffer.wrap(target, offset, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(value.toInt())
    }

    private fun withGameDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("scenario-resource-manager").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun matchingIsCaseInsensitiveAndRejectsUnrelatedNames() {
        assertEquals(0, GpkResourceNameMatcher.rank("BG/SHORE", "bg/shore"))
        assertNull(GpkResourceNameMatcher.rank("bg/shore", "bg/night"))
    }
}
