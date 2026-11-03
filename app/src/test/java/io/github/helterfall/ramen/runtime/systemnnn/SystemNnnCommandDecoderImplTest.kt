package io.github.helterfall.ramen.runtime.systemnnn

import io.github.helterfall.ramen.parser.SystemNnnOffsetTable
import io.github.helterfall.ramen.parser.SystemNnnScript
import io.github.helterfall.ramen.parser.SystemNnnTextEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemNnnCommandDecoderImplTest {

    @Test
    fun mapsExactTextOffsetsAndValidGtbIndexesAndSkipsUnknownValues() {
        val script = scriptWithPointers(0x32B64, 7, 0x40000, 9)
        val decoder = SystemNnnCommandDecoderImpl(gtbResourceCount = 8)

        val dialogue = decoder.decodeNext(script, TABLE_START) as SystemNnnDecodedCommand.Dialogue
        assertEquals(TABLE_START + 4L, dialogue.nextCommandOffset)
        assertEquals(0x32B64L, dialogue.textEntryOffset)

        val scene = decoder.decodeNext(script, TABLE_START + 4L) as SystemNnnDecodedCommand.SceneUpdate
        assertEquals(TABLE_START + 8L, scene.nextCommandOffset)
        assertEquals(7L, scene.backgroundResourceIndex)
        assertEquals(null, scene.backgroundResourceId)

        val skippedHighValue = decoder.decodeNext(
            script,
            TABLE_START + 8L
        ) as SystemNnnDecodedCommand.Skip
        assertEquals(TABLE_START + 12L, skippedHighValue.nextCommandOffset)

        val skippedOutOfRangeIndex = decoder.decodeNext(
            script,
            TABLE_START + 12L
        ) as SystemNnnDecodedCommand.Skip
        assertEquals(TABLE_START + 16L, skippedOutOfRangeIndex.nextCommandOffset)

        assertTrue(decoder.decodeNext(script, TABLE_END) is SystemNnnDecodedCommand.End)
    }

    @Test
    fun rejectsUnalignedOrOutOfRangeTableCursors() {
        val decoder = SystemNnnCommandDecoderImpl(gtbResourceCount = 8)
        val script = scriptWithPointers(0x32B64, 0)

        assertTrue(runCatching {
            decoder.decodeNext(script, TABLE_START + 1L)
        }.exceptionOrNull() is java.io.IOException)

        assertTrue(runCatching {
            decoder.decodeNext(script, TABLE_END + 4L)
        }.exceptionOrNull() is java.io.IOException)
    }

    private fun scriptWithPointers(vararg pointers: Long): SystemNnnScript {
        val commandEnd = 0x32B20L
        val commandBytes = ByteArray((commandEnd - TABLE_START).toInt())
        pointers.forEachIndexed { index, value ->
            val offset = index * 4
            repeat(4) { byteIndex ->
                commandBytes[offset + byteIndex] = (value ushr (byteIndex * 8)).toByte()
            }
        }

        return SystemNnnScript(
            fileSizeBytes = 0x32B64L + 64L,
            firstHeaderWord = 20u,
            offsetTables = listOf(
                SystemNnnOffsetTable("main", 1_299u, TABLE_START.toUInt()),
                SystemNnnOffsetTable("secondary", 1_124u, 0x167F2u),
                SystemNnnOffsetTable("tertiary", 69u, 0xC390u),
                SystemNnnOffsetTable("auxiliary", 2u, 0u)
            ),
            commandStartOffset = TABLE_START,
            commandEndOffset = commandEnd,
            textPoolOffset = 0x32B64L,
            textPool = listOf(SystemNnnTextEntry(0x32B64L, "First line")),
            commandBytes = commandBytes
        )
    }

    companion object {
        private const val TABLE_START = 0xC5BAL
        private const val TABLE_END = TABLE_START + 1_299L * 4L
    }
}
