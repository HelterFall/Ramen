package io.github.helterfall.ramen.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemNnnResourceFunctionScannerTest {

    @Test
    fun inventoriesFamilyKnownResourceAndAudioFunctionsWithNumericPayloadOnly() {
        val script = scriptOf(
            records = listOf(
                listOf(5u, 0x66660007u, 0x33330007u, 1u, 0x1234u),
                listOf(6u, 0x66660007u, 0x33330008u, 2u, 10u, 11u),
                listOf(5u, 0x66660007u, 0x33330015u, 1u, 3u),
                listOf(5u, 0x66660007u, 0x3333001Fu, 1u, 4u),
                listOf(3u, 0x66660002u, 0x33330008u),
                listOf(4u, 0x66660007u, 0x3333001Au, 1u)
            )
        )
        val stream = SystemNnnCommandStreamReader().read(script)
        val result = SystemNnnResourceFunctionScanner().scan("03", script, stream)

        assertEquals(
            listOf(
                SystemNnnResourceFunctionKind.MUSIC,
                SystemNnnResourceFunctionKind.VOICE,
                SystemNnnResourceFunctionKind.LOAD_DWQ,
                SystemNnnResourceFunctionKind.PRELOAD_DWQ
            ),
            result.candidates.map { it.kind }
        )
        assertEquals(listOf(0x50L, 0x64L, 0x7CL, 0x90L), result.candidates.map { it.commandOffset })
        assertEquals(listOf(1u, 2u, 1u, 1u), result.candidates.map { it.leadingCountWord })
        assertTrue(result.candidates.all { it.countWordEqualsRemainingPayloadWordCount == true })
        assertEquals(0, result.countWordPayloadDifferenceCount)
        assertEquals(listOf(1u, 0x1234u), result.candidates.first().payloadPreview)
        assertEquals(0x3333, result.candidates.first().nextRecord?.codeFamily)
        assertEquals(0x0008, result.candidates.first().nextRecord?.codeId)
        assertEquals(0x3333, result.candidates[1].previousRecord?.codeFamily)
        assertEquals(0x0007, result.candidates[1].previousRecord?.codeId)
        assertTrue(result.candidates.all { it.sourceFilmTableIndex == null })
    }

    @Test
    fun reportsCountWordPayloadDifferenceWithoutReadingPastRecordBoundary() {
        val script = scriptOf(records = listOf(listOf(5u, 0x66660007u, 0x33330009u, 3u, 0xFFFFu)))
        val stream = SystemNnnCommandStreamReader().read(script)
        val result = SystemNnnResourceFunctionScanner().scan("akane", script, stream)
        val candidate = result.candidates.single()

        assertEquals(SystemNnnResourceFunctionKind.SOUND, candidate.kind)
        assertEquals(3u, candidate.leadingCountWord)
        assertEquals(1, candidate.remainingPayloadWordCount)
        assertEquals(false, candidate.countWordEqualsRemainingPayloadWordCount)
        assertEquals(1, result.countWordPayloadDifferenceCount)
        assertEquals(listOf(3u, 0xFFFFu), candidate.payloadPreview)
        assertNull(candidate.previousRecord)
        assertNull(candidate.nextRecord)
    }

    @Test
    fun marksMissingCountWordAndKeepsEmptyPayloadBounded() {
        val script = scriptOf(records = listOf(listOf(3u, 0x66660007u, 0x33330018u)))
        val stream = SystemNnnCommandStreamReader().read(script)
        val candidate = SystemNnnResourceFunctionScanner().scan("01", script, stream).candidates.single()

        assertNull(candidate.leadingCountWord)
        assertNull(candidate.countWordEqualsRemainingPayloadWordCount)
        assertEquals(0, candidate.remainingPayloadWordCount)
        assertTrue(candidate.payloadPreview.isEmpty())
    }

    @Test
    fun capsPayloadAndNeighborContextPreviewsAtConfiguredLimits() {
        val payload = (0u..11u).toList()
        val script = scriptOf(
            records = listOf(
                listOf(15u, 0x66660007u, 0x33330007u) + payload,
                listOf(9u, 0x66660001u, 0x55550001u, 1u, 2u, 3u)
            )
        )
        val stream = SystemNnnCommandStreamReader().read(script)
        val result = SystemNnnResourceFunctionScanner(maxPreviewWords = 2).scan("03", script, stream)
        val candidate = result.candidates.single()

        assertEquals(listOf(0u, 1u), candidate.payloadPreview)
        assertEquals(12, candidate.remainingPayloadWordCount)
        assertEquals(4, candidate.nextRecord?.payloadPreview?.size)
    }

    @Test
    fun rejectsBlankModuleName() {
        val script = scriptOf(records = emptyList())
        val stream = SystemNnnCommandStreamReader().read(script)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            SystemNnnResourceFunctionScanner().scan(" ", script, stream)
        }
    }

    private fun scriptOf(records: List<List<UInt>>): SystemNnnScript {
        val words = listOf(0u, 0u, 0u, 0u) + records.flatten() + listOf(0u, 0u, 0u, 0u)
        val bytes = ByteArray(words.size * 4)
        words.forEachIndexed { index, word ->
            repeat(4) { offset ->
                bytes[index * 4 + offset] = ((word shr (offset * 8)) and 0xFFu).toByte()
            }
        }
        return SystemNnnScript(
            fileSizeBytes = 0x40L + bytes.size,
            firstHeaderWord = 0u,
            offsetTables = emptyList(),
            commandStartOffset = 0x40L,
            commandEndOffset = 0x40L + bytes.size,
            textPoolOffset = 0x40L + bytes.size,
            textPool = emptyList(),
            commandBytes = bytes
        )
    }
}
