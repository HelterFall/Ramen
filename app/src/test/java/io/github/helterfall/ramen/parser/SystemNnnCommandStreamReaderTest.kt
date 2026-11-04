package io.github.helterfall.ramen.parser

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemNnnCommandStreamReaderTest {

    @Test
    fun readsLengthPrefixedRecordsAndPreservesOpaquePreambleAndTrailer() {
        val script = scriptOf(
            preamble = listOf(0x0000C3D5u, 0x0000C41Cu, 0u, 0u),
            records = listOf(
                listOf(5u, 0x66660007u, 0x33330051u, 1u, 0x66660002u),
                listOf(3u, 0x66660002u, 0x55560006u)
            ),
            trailer = listOf(0x00005417u, 0x66660001u, 0x55550002u, 0x44440002u)
        )

        val stream = SystemNnnCommandStreamReader().read(script)

        assertEquals(listOf(0x0000C3D5u, 0x0000C41Cu, 0u, 0u), stream.preambleWords)
        assertEquals(listOf(0x00005417u, 0x66660001u, 0x55550002u, 0x44440002u), stream.trailerWords)
        assertEquals(2, stream.records.size)

        val first = stream.records[0]
        assertEquals(0x50L, first.offset)
        assertEquals(0x64L, first.endOffsetExclusive)
        assertEquals(5u, first.lengthWords)
        assertEquals(0x66660007u, first.identifier)
        assertEquals(7, first.identifierType)
        assertEquals(0x3333, first.codeFamily)
        assertEquals(0x51, first.codeId)
        assertEquals(2, first.payloadWordCount)

        val second = stream.records[1]
        assertEquals(0x64L, second.offset)
        assertEquals(0x70L, second.endOffsetExclusive)
        assertEquals(2, second.identifierType)
        assertEquals(0x5556, second.codeFamily)
        assertEquals(6, second.codeId)
        assertEquals(0, second.payloadWordCount)

        assertEquals(0x50L, stream.recordStartOffset)
        assertEquals(0x70L, stream.recordEndOffsetExclusive)
    }

    @Test
    fun payloadWordsThatResembleRecordHeadersAreNotScannedAsRecords() {
        val script = scriptOf(
            records = listOf(
                listOf(5u, 0x66660007u, 0x33330051u, 3u, 0x66660002u),
                listOf(3u, 0x66660002u, 0x55560006u)
            )
        )

        val stream = SystemNnnCommandStreamReader().read(script)

        assertEquals(2, stream.records.size)
        assertEquals(0x64L, stream.records[1].offset)
    }

    @Test
    fun rejectsRecordLengthsSmallerThanTheThreeWordHeader() {
        val script = scriptOf(
            records = listOf(listOf(2u, 0x66660002u, 0x55560006u))
        )

        assertThrows(IOException::class.java) {
            SystemNnnCommandStreamReader().read(script)
        }
    }

    @Test
    fun rejectsRecordsThatExtendBeyondTheBoundedCodeRegion() {
        val script = scriptOf(
            records = listOf(listOf(100u, 0x66660002u, 0x55560006u))
        )

        assertThrows(IOException::class.java) {
            SystemNnnCommandStreamReader().read(script)
        }
    }

    @Test
    fun rejectsUnknownIdentifierFamiliesAndTypes() {
        val wrongFamily = scriptOf(
            records = listOf(listOf(3u, 0x12340002u, 0x55560006u))
        )
        val wrongType = scriptOf(
            records = listOf(listOf(3u, 0x66660008u, 0x55560006u))
        )

        assertThrows(IOException::class.java) {
            SystemNnnCommandStreamReader().read(wrongFamily)
        }
        assertThrows(IOException::class.java) {
            SystemNnnCommandStreamReader().read(wrongType)
        }
    }

    @Test
    fun rejectsARegionTooSmallForItsPreambleAndTrailer() {
        val bytes = ByteArray(24)
        val script = scriptWithRawCommandBytes(bytes)

        assertThrows(IOException::class.java) {
            SystemNnnCommandStreamReader().read(script)
        }
    }

    private fun scriptOf(
        preamble: List<UInt> = listOf(0u, 0u, 0u, 0u),
        records: List<List<UInt>>,
        trailer: List<UInt> = listOf(0u, 0u, 0u, 0u)
    ): SystemNnnScript {
        val words = preamble + records.flatten() + trailer
        val bytes = ByteArray(words.size * 4)
        words.forEachIndexed { index, word -> putUInt32(bytes, index * 4, word) }
        return scriptWithRawCommandBytes(bytes)
    }

    private fun scriptWithRawCommandBytes(bytes: ByteArray): SystemNnnScript =
        SystemNnnScript(
            fileSizeBytes = 0x40L + bytes.size,
            firstHeaderWord = 0u,
            offsetTables = emptyList(),
            commandStartOffset = 0x40L,
            commandEndOffset = 0x40L + bytes.size,
            textPoolOffset = 0x40L + bytes.size,
            textPool = emptyList(),
            commandBytes = bytes
        )

    private fun putUInt32(bytes: ByteArray, offset: Int, value: UInt) {
        for (index in 0 until 4) {
            bytes[offset + index] = ((value shr (index * 8)) and 0xFFu).toByte()
        }
    }
}
