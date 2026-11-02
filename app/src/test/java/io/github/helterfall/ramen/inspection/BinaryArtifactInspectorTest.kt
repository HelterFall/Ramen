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
        assertEquals("50 4B 03 04 / 50 4B 05 06 / 50 4B 07 08", ContainerFormatRegistry.findVerifiedByExtension("zip").single().signatureHex)
        assertTrue(ContainerFormatRegistry.findObservedByExtension("gpk").isNotEmpty())
        assertTrue(ContainerFormatRegistry.findVerifiedByExtension("gpk").isEmpty())
    }

    @Test
    fun readsImportedDllNamesFromPe32Image() {
        val directory = Files.createTempDirectory("ramen-pe-imports32").toFile()
        try {
            val file = File(directory, "sample.exe")
            file.writeBytes(createPeWithImports(is64Bit = false, dllName = "KERNEL32.dll"))

            val result = BinaryArtifactInspector().inspect(file)

            assertEquals(ArtifactKind.PE_IMAGE, result.kind)
            assertEquals(listOf("KERNEL32.dll"), result.peMetadata?.importedLibraries)
            assertEquals(0x1100L, result.peMetadata?.importDirectoryRva)
            assertEquals(40L, result.peMetadata?.importDirectorySize)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun readsImportedDllNamesFromPe32PlusImage() {
        val directory = Files.createTempDirectory("ramen-pe-imports64").toFile()
        try {
            val file = File(directory, "sample64.exe")
            file.writeBytes(createPeWithImports(is64Bit = true, dllName = "SDL2.dll"))

            val result = BinaryArtifactInspector().inspect(file)

            assertEquals(ArtifactKind.PE_IMAGE, result.kind)
            assertEquals("x86-64", result.peMetadata?.machineLabel)
            assertEquals(listOf("SDL2.dll"), result.peMetadata?.importedLibraries)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun reportsUnmappedImportNameWithoutCrashing() {
        val directory = Files.createTempDirectory("ramen-pe-imports-invalid").toFile()
        try {
            val file = File(directory, "broken.exe")
            file.writeBytes(createPeWithImports(is64Bit = false, dllName = null, nameRva = 0x900000))

            val result = BinaryArtifactInspector().inspect(file)

            assertEquals(ArtifactKind.PE_IMAGE, result.kind)
            assertTrue(result.peMetadata?.importedLibraries.isNullOrEmpty())
            assertTrue(result.warnings.any { it.contains("DLL name points outside") })
        } finally {
            directory.deleteRecursively()
        }
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

    private fun createPeWithImports(
        is64Bit: Boolean,
        dllName: String?,
        nameRva: Int = 0x1140
    ): ByteArray {
        val peOffset = 0x80
        val coffOffset = peOffset + 4
        val optionalOffset = coffOffset + 20
        val optionalSize = if (is64Bit) 0xF0 else 0xE0
        val sectionTableOffset = optionalOffset + optionalSize
        val fileBytes = ByteArray(0x800)

        fileBytes[0] = 0x4D
        fileBytes[1] = 0x5A
        putU32(fileBytes, 0x3C, peOffset)
        fileBytes[peOffset] = 0x50
        fileBytes[peOffset + 1] = 0x45

        putU16(fileBytes, coffOffset, if (is64Bit) 0x8664 else 0x014C)
        putU16(fileBytes, coffOffset + 2, 1)
        putU16(fileBytes, coffOffset + 16, optionalSize)
        putU16(fileBytes, coffOffset + 18, 0x0102)

        putU16(fileBytes, optionalOffset, if (is64Bit) 0x020B else 0x010B)
        putU32(fileBytes, optionalOffset + 60, 0x200)
        val numberOfDirectoriesOffset = if (is64Bit) optionalOffset + 108 else optionalOffset + 92
        val dataDirectoriesOffset = if (is64Bit) optionalOffset + 112 else optionalOffset + 96
        putU32(fileBytes, numberOfDirectoriesOffset, 16)
        putU32(fileBytes, dataDirectoriesOffset + 8, 0x1100)
        putU32(fileBytes, dataDirectoriesOffset + 12, 40)

        // One section maps RVA 0x1000..0x15FF to file offset 0x200..0x7FF.
        putU32(fileBytes, sectionTableOffset + 8, 0x600)
        putU32(fileBytes, sectionTableOffset + 12, 0x1000)
        putU32(fileBytes, sectionTableOffset + 16, 0x600)
        putU32(fileBytes, sectionTableOffset + 20, 0x200)

        val descriptorOffset = 0x300
        putU32(fileBytes, descriptorOffset + 12, nameRva)
        putU32(fileBytes, descriptorOffset + 16, 0x1150)
        // The next 20-byte import descriptor is zero-filled and terminates the table.

        if (dllName != null) {
            val nameOffset = 0x200 + (nameRva - 0x1000)
            val encodedName = (dllName + "\u0000").toByteArray(Charsets.US_ASCII)
            encodedName.copyInto(fileBytes, nameOffset)
        }
        return fileBytes
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

    private fun putU32(target: ByteArray, offset: Int, value: Long) {
        putU32(target, offset, value.toInt())
    }
}
