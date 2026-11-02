package io.github.helterfall.ramen.inspection

import java.io.ByteArrayOutputStream
import java.io.StringWriter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryInventoryJsonWriterTest {
    @Test
    fun writesVersionedInventoryWithBinaryAndPeMetadata() {
        val report = DirectoryInventoryReport(
            rootName = "test-game",
            entries = listOf(
                DirectoryInventoryEntry(
                    relativePath = "bin/game.exe",
                    sizeBytes = 1024L,
                    sha256Hex = "a".repeat(64),
                    artifactReport = BinaryArtifactReport(
                        fileName = "game.exe",
                        fileSizeBytes = 1024L,
                        extensionHint = "exe",
                        kind = ArtifactKind.PE_IMAGE,
                        signature = "MZ/PE",
                        evidence = "PE32+ executable image.",
                        peMetadata = PeImageMetadata(
                            machineCode = 0x8664,
                            machineLabel = "x86-64",
                            optionalHeaderMagic = 0x020B,
                            optionalHeaderLabel = "PE32+",
                            sectionCount = 5,
                            isDll = false,
                            importedLibraries = listOf("KERNEL32.dll", "SDL2.dll"),
                            importDirectoryRva = 0x2000L,
                            importDirectorySize = 80L
                        )
                    )
                )
            ),
            discoveredEntries = 1,
            totalBytesObserved = 1024L,
            totalBytesHashed = 1024L,
            truncated = false
        )

        val writer = StringWriter()
        DirectoryInventoryJsonWriter().write(report, writer)
        val json = writer.toString()

        assertTrue(json.contains("\"schemaVersion\": 1"))
        assertTrue(json.contains("\"rootName\": \"test-game\""))
        assertTrue(json.contains("\"relativePath\": \"bin/game.exe\""))
        assertTrue(json.contains("\"kind\": \"PE_IMAGE\""))
        assertTrue(json.contains("\"machineLabel\": \"x86-64\""))
        assertTrue(json.contains("\"importedLibraries\": [\"KERNEL32.dll\",\"SDL2.dll\"]"))
        assertTrue(json.contains("\"importDirectoryRva\": 8192"))
    }

    @Test
    fun escapesQuotesBackslashesAndControlCharacters() {
        val report = DirectoryInventoryReport(
            rootName = "root\\name\"quoted\nnext",
            entries = listOf(
                DirectoryInventoryEntry(
                    relativePath = "folder\\part/quote\"line\nname.txt",
                    sizeBytes = 0L,
                    sha256Hex = null,
                    artifactReport = null,
                    warnings = listOf("first\nsecond", "tab\tvalue", "control\u0001value")
                )
            ),
            discoveredEntries = 1,
            totalBytesObserved = 0L,
            totalBytesHashed = 0L,
            truncated = false
        )

        val writer = StringWriter()
        DirectoryInventoryJsonWriter().write(report, writer)
        val json = writer.toString()

        assertTrue(json.contains("\"rootName\": \"root\\\\name\\\"quoted\\nnext\""))
        assertTrue(json.contains("\"relativePath\": \"folder\\\\part/quote\\\"line\\nname.txt\""))
        assertTrue(json.contains("\"first\\nsecond\""))
        assertTrue(json.contains("\"tab\\tvalue\""))
        assertTrue(json.contains("\"control\\u0001value\""))
        assertFalse(json.contains("quote\"line\nname.txt"))
    }

    @Test
    fun writesNullsForUnavailableInspectionAndHashData() {
        val report = DirectoryInventoryReport(
            rootName = "partial",
            entries = listOf(
                DirectoryInventoryEntry(
                    relativePath = "unreadable.dat",
                    sizeBytes = 0L,
                    sha256Hex = null,
                    artifactReport = null,
                    warnings = listOf("Could not inspect file.")
                )
            ),
            discoveredEntries = 1,
            totalBytesObserved = 0L,
            totalBytesHashed = 0L,
            truncated = true,
            warnings = listOf("Depth limit reached.")
        )

        val writer = StringWriter()
        DirectoryInventoryJsonWriter().write(report, writer)
        val json = writer.toString()

        assertTrue(json.contains("\"sha256\": null"))
        assertTrue(json.contains("\"artifact\": null"))
        assertTrue(json.contains("\"truncated\": true"))
        assertTrue(json.contains("\"warnings\": [\"Depth limit reached.\"]"))
    }

    @Test
    fun outputStreamRemainsOwnedByCaller() {
        val output = TrackingOutputStream()
        val report = DirectoryInventoryReport(
            rootName = "empty",
            entries = emptyList(),
            discoveredEntries = 0,
            totalBytesObserved = 0L,
            totalBytesHashed = 0L,
            truncated = false
        )

        DirectoryInventoryJsonWriter().write(report, output)

        assertFalse(output.wasClosed)
        output.write('x'.code)
        assertTrue(output.toByteArray().isNotEmpty())
    }

    private class TrackingOutputStream : ByteArrayOutputStream() {
        var wasClosed: Boolean = false
            private set

        override fun close() {
            wasClosed = true
            super.close()
        }
    }
}
