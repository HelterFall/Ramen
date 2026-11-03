package io.github.helterfall.ramen.runtime.systemnnn

import io.github.helterfall.ramen.parser.SystemNnnOffsetTable
import io.github.helterfall.ramen.parser.SystemNnnScript
import io.github.helterfall.ramen.parser.SystemNnnTextEntry
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemNnnCommandDecoderImplTest {

    @Test
    fun mapsGtbIndexesAndAbsoluteTextOffsetsUsingTheTextPoolBoundary() {
        val script = scriptWithPointers(7, 0x32B64, 0x32B70)
        val decoder = SystemNnnCommandDecoderImpl(gtbResourceCount = 8)

        val scene = decoder.decodeNext(script, TABLE_START) as SystemNnnDecodedCommand.SceneUpdate
        assertEquals(TABLE_START + 4L, scene.nextCommandOffset)
        assertEquals(7L, scene.backgroundResourceIndex)

        val firstLine = decoder.decodeNext(
            script,
            TABLE_START + 4L
        ) as SystemNnnDecodedCommand.Dialogue
        assertEquals(TABLE_START + 8L, firstLine.nextCommandOffset)
        assertEquals(0x32B64L, firstLine.textEntryOffset)

        val secondLine = decoder.decodeNext(
            script,
            TABLE_START + 8L
        ) as SystemNnnDecodedCommand.Dialogue
        assertEquals(TABLE_START + 12L, secondLine.nextCommandOffset)
        assertEquals(0x32B70L, secondLine.textEntryOffset)

        assertTrue(decoder.decodeNext(script, TABLE_END) is SystemNnnDecodedCommand.End)
    }

    @Test
    fun skipsOutOfCatalogServiceWordAndContinuesToDialoguePointer() {
        val decoder = SystemNnnCommandDecoderImpl(gtbResourceCount = 8)
        val script = scriptWithPointers(144_179, 0x32B64)

        val skipped = decoder.decodeNext(script, TABLE_START)
        assertTrue(skipped is SystemNnnDecodedCommand.Skip)
        assertEquals(TABLE_START + 4L, (skipped as SystemNnnDecodedCommand.Skip).nextCommandOffset)

        val dialogue = decoder.decodeNext(script, skipped.nextCommandOffset)
        assertTrue(dialogue is SystemNnnDecodedCommand.Dialogue)
        assertEquals(0x32B64L, (dialogue as SystemNnnDecodedCommand.Dialogue).textEntryOffset)
    }

    @Test
    fun skipsUnmappedTextRangeValueAndContinuesToNextTextEntry() {
        val decoder = SystemNnnCommandDecoderImpl(gtbResourceCount = 8)
        val script = scriptWithPointers(0x32B65, 0x32B64)

        val skipped = decoder.decodeNext(script, TABLE_START)
        assertTrue(skipped is SystemNnnDecodedCommand.Skip)
        assertEquals(TABLE_START + 4L, (skipped as SystemNnnDecodedCommand.Skip).nextCommandOffset)

        val dialogue = decoder.decodeNext(script, skipped.nextCommandOffset)
        assertTrue(dialogue is SystemNnnDecodedCommand.Dialogue)
        assertEquals(0x32B64L, (dialogue as SystemNnnDecodedCommand.Dialogue).textEntryOffset)
    }

    @Test
    fun skipsFfff0000SystemMarkerAndContinuesToDialoguePointer() {
        val decoder = SystemNnnCommandDecoderImpl(gtbResourceCount = 8)
        val script = scriptWithPointers(0xFFFF0000L, 0x32B64)

        val skipped = decoder.decodeNext(script, TABLE_START)
        assertTrue(skipped is SystemNnnDecodedCommand.Skip)
        assertEquals(TABLE_START + 4L, (skipped as SystemNnnDecodedCommand.Skip).nextCommandOffset)

        val dialogue = decoder.decodeNext(script, skipped.nextCommandOffset)
        assertTrue(dialogue is SystemNnnDecodedCommand.Dialogue)
        assertEquals(0x32B64L, (dialogue as SystemNnnDecodedCommand.Dialogue).textEntryOffset)
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
            textPool = listOf(
                SystemNnnTextEntry(0x32B64L, "First line"),
                SystemNnnTextEntry(0x32B70L, "Second line")
            ),
            commandBytes = commandBytes
        )
    }

    companion object {
        private const val TABLE_START = 0xC5BAL
        private const val TABLE_END = TABLE_START + 1_299L * 4L
    }
}
