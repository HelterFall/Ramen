package io.github.helterfall.ramen.runtime.engine

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BinarySignatureProbeTest {
    @Test
    fun detectsAllFourMagicHeadersByTheirBytesAndPreservesResourceTypesThroughRegistry() {
        val root = Files.createTempDirectory("ramen-binary-signatures").toFile()
        try {
            File(root, "a-script.data").writeBytes(validScriptFixture())
            File(root, "b-container.any").writeBytes(emptyPackArchive())
            File(root, "c-jpeg.bin").writeBytes("JPEG    ".toByteArray(Charsets.US_ASCII))
            File(root, "d-bmp.bin").writeBytes("BMP     ".toByteArray(Charsets.US_ASCII))

            val source = DirectoryGameSource(root, sourceId = "binary-signature-test")
            val summary = EngineAdapterRegistry(listOf(BinarySignatureProbe())).probe(source)

            assertEquals(1, summary.matches.size)
            val match = summary.matches.single()
            assertEquals("ramen-binary-signature", match.descriptor.id)
            assertEquals(
                listOf("script", "resource-container", "graphic-archive", "graphic-archive"),
                match.evidence.filter { it.resourceType != null }.map { it.resourceType }
            )
            assertTrue(match.evidence.all { it.relativePath != null })
            assertFalse(
                match.evidence.single { it.resourceType == "script" }
                    .description.contains("did not validate")
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun recognizedScriptMagicIsAMatchEvenWhenTheSyntheticBodyParserRejectsIt() {
        val root = Files.createTempDirectory("ramen-binary-script-marker").toFile()
        try {
            val magic = byteArrayOf(
                0xEB.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
                0xFE.toByte(), 0xFF.toByte(), 0x99.toByte(), 0x99.toByte()
            )
            File(root, "legacy.spt").writeBytes(magic)
            val source = DirectoryGameSource(root, sourceId = "script-marker-test")

            val result = BinarySignatureProbe().probeFile(source, "legacy.spt")

            assertTrue(result is EngineProbeResult.Match)
            val evidence = (result as EngineProbeResult.Match).evidence.single()
            assertEquals("script", evidence.resourceType)
            assertEquals("legacy.spt", evidence.relativePath)
            assertTrue(evidence.description.contains("did not validate"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unknownEightByteHeaderIsMalformedForAnIndividualFile() {
        val root = Files.createTempDirectory("ramen-binary-unknown").toFile()
        try {
            File(root, "unknown.bin").writeBytes(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7))
            val source = DirectoryGameSource(root, sourceId = "unknown-test")

            val result = BinarySignatureProbe().probeFile(source, "unknown.bin")

            assertTrue(result is EngineProbeResult.Malformed)
            assertTrue((result as EngineProbeResult.Malformed).reason.contains("Unrecognized"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun shortHeaderIsMalformedAndReportsThePath() {
        val root = Files.createTempDirectory("ramen-binary-short").toFile()
        try {
            File(root, "short.bin").writeBytes(byteArrayOf(0xEB.toByte(), 0xFF.toByte()))
            val source = DirectoryGameSource(root, sourceId = "short-test")

            val result = BinarySignatureProbe().probeFile(source, "short.bin")

            assertTrue(result is EngineProbeResult.Malformed)
            val malformed = result as EngineProbeResult.Malformed
            assertTrue(malformed.reason.contains("8-byte"))
            assertEquals("short.bin", malformed.evidence.single().relativePath)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun directoryScanRetainsValidMatchesAndDiagnosticsForUnrecognizedFiles() {
        val root = Files.createTempDirectory("ramen-binary-mixed").toFile()
        try {
            File(root, "valid.data").writeBytes(emptyPackArchive())
            File(root, "unknown.data").writeBytes(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7))
            val source = DirectoryGameSource(root, sourceId = "mixed-test")

            val result = BinarySignatureProbe().probe(source)

            assertTrue(result is EngineProbeResult.Match)
            val match = result as EngineProbeResult.Match
            assertEquals("resource-container", match.evidence.single { it.resourceType != null }.resourceType)
            assertFalse(match.evidence.none { it.description.startsWith("Rejected candidate:") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun emptySourceIsAnOrdinaryNoMatch() {
        val root = Files.createTempDirectory("ramen-binary-empty").toFile()
        try {
            val source = DirectoryGameSource(root, sourceId = "empty-test")

            val result = BinarySignatureProbe().probe(source)

            assertEquals(EngineProbeResult.NoMatch, result)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun validScriptFixture(): ByteArray =
        byteArrayOf(
            0xEB.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFE.toByte(), 0xFF.toByte(), 0x99.toByte(), 0x99.toByte(),
            0x01, // Display dialogue
            0x05, 0x00
        ) + "Hello".toByteArray(Charsets.UTF_8)

    private fun emptyPackArchive(): ByteArray =
        ByteBuffer.allocate(20)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("IF PACKT".toByteArray(Charsets.US_ASCII))
            .putInt(1) // format version
            .putInt(0) // entry count
            .putInt(0) // index length
            .array()

}
