package io.github.helterfall.ramen.inspection

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BinaryArtifactInspectorTest {
    @Test
    fun identifiesPe32X86ExecutableFromHeaderBytes() {
        val directory = Files.createTempDirectory("ramen-pe-inspect").toFile()
        try {
            val file = File(directory, "sample.exe")
            file.writeBytes(createPeImage(machine = 0x014C, optionalMagic = 0x010B))

            val result = BinaryArtifactInspector().inspect(file)

            assertEquals(ArtifactKind.PE_IMAGE, result.kind)
            assertEquals("MZ/PE", result.signature)
            assertNotNull(result.peMetadata)
            assertEquals("x86", result.peMetadata?.machineLabel)
            assertEquals("PE32", result.peMetadata?.optionalHeaderLabel)
            assertEquals(3, result.peMetadata?.sectionCount)
            assertFalse(result.peMetadata?.isDll ?: true)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun identifiesPe32PlusArm64Dll() {
        val directory = Files.createTempDirectory("ramen-pe64-inspect").toFile()
        try {
            val file = File(directory, "sample.dll")
            file.writeBytes(
                createPeImage(
                    machine = 0xAA64,
                    optionalMagic = 0x020B,
                    characteristics = 0x2022
                )
            )

            val result = BinaryArtifactInspector().inspect(file)

            assertEquals(ArtifactKind.PE_IMAGE, result.kind)
            assertEquals("ARM64", result.peMetadata?.machineLabel)
            assertEquals("PE32+", result.peMetadata?.optionalHeaderLabel)
            assertTrue(result.peMetadata?.isDll == true)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun recognizesZipAndOggByBytes() {
        val directory = Files.createTempDirectory("ramen-signatures").toFile()
        try {
            val zip = File(directory, "archive.bin")
            zip.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00))
            val ogg = File(directory, "audio.dat")
            ogg.writeBytes(byteArrayOf(0x4F, 0x67, 0x67, 0x53, 0x00))

            assertEquals(ArtifactKind.ZIP_CONTAINER, BinaryArtifactInspector().inspect(zip).kind)
            assertEquals(ArtifactKind.OGG_STREAM, BinaryArtifactInspector().inspect(ogg).kind)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun extensionAloneDoesNotClassifyUnknownProprietaryFile() {
        val directory = Files.createTempDirectory("ramen-unknown-container").toFile()
        try {
            val file = File(directory, "resource.gpk")
            file.writeBytes(byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55))

            val result = BinaryArtifactInspector().inspect(file)

            assertEquals(ArtifactKind.UNKNOWN, result.kind)
            assertEquals("gpk", result.extensionHint)
            assertNull(result.peMetadata)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun malformedMzFileIsReportedWithoutReadingOutsideBounds() {
        val directory = Files.createTempDirectory("ramen-malformed-mz").toFile()
        try {
            val bytes = ByteArray(64)
            bytes[0] = 0x4D
            bytes[1] = 0x5A
            bytes[0x3C] = 0x40
            val file = File(directory, "broken.exe")
            file.writeBytes(bytes)

            val result = BinaryArtifactInspector().inspect(file)

            assertEquals(ArtifactKind.DOS_MZ_IMAGE, result.kind)
            assertTrue(result.warnings.isNotEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun containerRegistrySeparatesVerifiedFormatsFromExtensionObservations() {
        assertEquals("zip", ContainerFormatRegistry.findVerifiedByExtension(".ZIP").single().id)
        assertTrue(ContainerFormatRegistry.findObservedByExtension("gpk").isNotEmpty())
        assertTrue(ContainerFormatRegistry.findVerifiedByExtension("gpk").isEmpty())
    }

    private fun createPeImage(
        machine: Int,
        optionalMagic: Int,
        characteristics: Int = 0x0102
    ): ByteArray {
        val peOffset = 0x80
        val optionalHeaderSize = 0xE0
        val bytes = ByteArray(peOffset + 4 + 20 + optionalHeaderSize)

        bytes[0] = 0x4D
        bytes[1] = 0x5A
        putU32(bytes, 0x3C, peOffset)
        bytes[peOffset] = 0x50
        bytes[peOffset + 1] = 0x45
        bytes[peOffset + 2] = 0x00
        bytes[peOffset + 3] = 0x00

        val coff = peOffset + 4
        putU16(bytes, coff, machine)
        putU16(bytes, coff + 2, 3)
        putU16(bytes, coff + 16, optionalHeaderSize)
        putU16(bytes, coff + 18, characteristics)
        putU16(bytes, coff + 20, optionalMagic)

        return bytes
    }

    private fun putU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
    }

    private fun putU32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
        target[offset + 2] = (value ushr 16).toByte()
        target[offset + 3] = (value ushr 24).toByte()
    }
}
