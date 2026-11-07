package io.github.helterfall.ramen.parser

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemNnnScriptTransferScannerTest {

    @Test
    fun resolvesScriptScriptJumpAndSubscriptTargetsFromExactPoolTwoIndexes() {
        val script = scriptOf(
            records = listOf(
                listOf(4u, 0x66660002u, 0x5556000Au, 0u),
                listOf(4u, 0x66660002u, 0x55560015u, 1u),
                listOf(4u, 0x66660002u, 0x55560016u, 2u),
                listOf(3u, 0x66660002u, 0x5556000Bu),
                listOf(5u, 0x66660007u, 0x33330053u, 1u, 3u)
            ),
            pool2 = listOf("op1", "yuka", "_staff", "ordinary_label")
        )
        val stream = SystemNnnCommandStreamReader().read(script)
        val result = SystemNnnScriptTransferScanner().scan(
            "sys", script, stream, listOf("sys", "op1", "01", "yuka", "_staff")
        )

        assertEquals(3, result.candidates.size)
        assertEquals(0, result.unresolvedTargetCount)
        assertEquals(
            listOf(
                SystemNnnScriptTransferKind.SCRIPT,
                SystemNnnScriptTransferKind.SCRIPTJUMP,
                SystemNnnScriptTransferKind.SUBSCRIPT
            ),
            result.candidates.map { it.kind }
        )
        assertEquals(listOf("op1", "yuka", "_staff"), result.candidates.map { it.targetModuleName })
        assertEquals(listOf(0x50L, 0x60L, 0x70L), result.candidates.map { it.commandOffset })
        assertEquals(listOf(4u, 4u, 3u), result.candidates.map { it.followingRecordLengthWord })
        assertTrue(result.candidates.all { it.sourceFilmNumber == null })
    }

    @Test
    fun countsNonModuleStringsWithoutExposingTheirText() {
        val script = scriptOf(
            records = listOf(
                listOf(4u, 0x66660002u, 0x5556000Au, 0u),
                listOf(3u, 0x66660002u, 0x5556000Bu)
            ),
            pool2 = listOf("ordinary_label")
        )
        val stream = SystemNnnCommandStreamReader().read(script)
        val result = SystemNnnScriptTransferScanner().scan(
            "03", script, stream, listOf("03", "01")
        )

        assertTrue(result.candidates.isEmpty())
        assertEquals(1, result.unresolvedTargetCount)
    }

    @Test
    fun rejectsRecognizedScriptControlRecordsWithUnexpectedOperandCount() {
        val script = scriptOf(
            records = listOf(listOf(5u, 0x66660002u, 0x55560016u, 0u, 1u)),
            pool2 = listOf("_staff")
        )
        val stream = SystemNnnCommandStreamReader().read(script)

        org.junit.Assert.assertThrows(IOException::class.java) {
            SystemNnnScriptTransferScanner().scan("03", script, stream, listOf("03", "_staff"))
        }
    }

    private fun scriptOf(
        records: List<List<UInt>>,
        pool2: List<String>
    ): SystemNnnScript {
        val words = listOf(0u, 0u, 0u, 0u) + records.flatten() + listOf(0u, 0u, 0u, 0u)
        val bytes = ByteArray(words.size * 4)
        words.forEachIndexed { index, word ->
            repeat(4) { offset ->
                bytes[index * 4 + offset] =
                    ((word shr (offset * 8)) and 0xFFu).toByte()
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
            commandBytes = bytes,
            secondaryTextPool = pool2.mapIndexed { index, value ->
                SystemNnnTextEntry(
                    offset = 0L,
                    text = value,
                    rawBytes = value.toByteArray(Charsets.US_ASCII),
                    logicalAddress = index.toUInt()
                )
            }
        )
    }
}
