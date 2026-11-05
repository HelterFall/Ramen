package io.github.helterfall.ramen.parser

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemNnnLocalSubroutineScannerTest {

    @Test
    fun mapsSubroutineTableIndexesToValidatedDescriptorThreeTargets() {
        val script = localSubroutineScript(
            subCalls = listOf(1u to 0u, 0u to 1u)
        )
        val stream = SystemNnnCommandStreamReader().read(script)

        val result = SystemNnnLocalSubroutineScanner().scan("03", script, stream)

        assertEquals(2, result.candidates.size)
        assertEquals(listOf(1, 0), result.candidates.map { it.targetTableIndex })
        assertEquals(listOf(0x78L, 0x64L), result.candidates.map { it.targetCommandOffset })
        assertEquals(listOf(0x50, 0x51), result.candidates.map { it.targetFunctionCodeId })
        assertEquals(listOf(0, 1), result.candidates.map { it.subroutineId })
        assertTrue(result.subroutineIdsAreDense)
        assertTrue(result.missingSubroutineIds.isEmpty())
        assertTrue(result.duplicateSubroutineIds.isEmpty())
        assertEquals(0, result.unresolvedTargetCount)
    }

    @Test
    fun reportsDuplicateAndMissingLocalSubroutineIds() {
        val script = localSubroutineScript(
            subCalls = listOf(1u to 0u, 1u to 0u, 0u to 2u)
        )
        val stream = SystemNnnCommandStreamReader().read(script)

        val result = SystemNnnLocalSubroutineScanner().scan("03", script, stream)

        assertFalse(result.subroutineIdsAreDense)
        assertEquals(listOf(0), result.duplicateSubroutineIds)
        assertEquals(listOf(1), result.missingSubroutineIds)
    }

    @Test
    fun rejectsLocalSubRecordsWithUnexpectedOperandCount() {
        val script = localSubroutineScript(
            subCalls = emptyList(),
            extraRecords = listOf(listOf(4u, 0x66660002u, 0x55560009u, 1u))
        )
        val stream = SystemNnnCommandStreamReader().read(script)

        org.junit.Assert.assertThrows(IOException::class.java) {
            SystemNnnLocalSubroutineScanner().scan("03", script, stream)
        }
    }

    private fun localSubroutineScript(
        subCalls: List<Pair<UInt, UInt>>,
        extraRecords: List<List<UInt>> = emptyList()
    ): SystemNnnScript {
        val words = mutableListOf<UInt>()
        words += listOf(0u, 0u, 0u, 0u)
        words += listOf(5u, 0x66660001u, 0x55550002u, 0x19u, 0x1Eu)
        words += listOf(5u, 0x66660007u, 0x33330051u, 1u, 0u)
        words += listOf(5u, 0x66660007u, 0x33330050u, 0u, 0u)
        words += listOf(5u, 0x66660007u, 0x33330052u, 0u, 0u)
        subCalls.forEach { (tableIndex, subroutineId) ->
            words += listOf(5u, 0x66660002u, 0x55560009u, tableIndex, subroutineId)
        }
        extraRecords.forEach { words += it }
        words += listOf(3u, 0x66660002u, 0x55560007u)
        words += listOf(0u, 0u, 0u, 0u)

        val bytes = ByteArray(words.size * 4)
        words.forEachIndexed { index, value ->
            repeat(4) { offset ->
                bytes[index * 4 + offset] =
                    ((value shr (offset * 8)) and 0xFFu).toByte()
            }
        }

        return SystemNnnScript(
            fileSizeBytes = 0x40L + bytes.size,
            firstHeaderWord = 0u,
            offsetTables = listOf(
                SystemNnnOffsetTable("main", 0u, 0u),
                SystemNnnOffsetTable("secondary", 0u, 0u),
                SystemNnnOffsetTable("tertiary", 2u, 0x17u),
                SystemNnnOffsetTable("auxiliary", 1u, 0u)
            ),
            commandStartOffset = 0x40L,
            commandEndOffset = 0x40L + bytes.size,
            textPoolOffset = 0x40L + bytes.size,
            textPool = emptyList(),
            commandBytes = bytes
        )
    }
}
