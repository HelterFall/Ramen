package io.github.helterfall.ramen.parser

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SystemNnnSceneCatalogReaderTest {

    @Test
    fun readsVersionOneSceneDataAndPreservesOpaqueUnsignedValues() {
        val bytes = sceneData(
            listOf(
                10u to listOf(0x10u),
                13u to listOf(0x1Du, 0xFFFF_FFE1u, 0x20u, 0x22u),
                18u to listOf(9u, 10u, 11u)
            )
        )

        val parsed = SystemNnnSceneCatalogReader().readSceneData(bytes)

        assertEquals(1u, parsed.version)
        assertEquals(3, parsed.recordCount)
        assertEquals(10u, parsed.records[0].type)
        assertEquals(listOf(0xFFFF_FFE1u, 0x20u, 0x22u), parsed.records[1].values.drop(1))
        assertEquals(listOf(9u, 10u, 11u), parsed.records[2].values)
    }

    @Test
    fun rejectsSceneDataWithSizeMismatchTruncationOrTrailingBytes() {
        val valid = sceneData(listOf(10u to listOf(1u)))
        val wrongSize = valid.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val truncated = valid.copyOf(valid.size - 1)
        val trailing = valid + byteArrayOf(0)

        val reader = SystemNnnSceneCatalogReader()
        assertThrows(IOException::class.java) { reader.readSceneData(wrongSize) }
        assertThrows(IOException::class.java) { reader.readSceneData(truncated) }
        assertThrows(IOException::class.java) { reader.readSceneData(trailing) }
    }

    @Test
    fun rejectsUnsupportedVersionAndUnboundedRecordValues() {
        val wrongVersion = sceneData(listOf(10u to listOf(1u))).also {
            putUInt32(it, 4, 2u)
        }
        val tooManyValues = sceneData(listOf(10u to List(65) { it.toUInt() }))

        val reader = SystemNnnSceneCatalogReader()
        assertThrows(IOException::class.java) { reader.readSceneData(wrongVersion) }
        assertThrows(IOException::class.java) { reader.readSceneData(tooManyValues) }
    }

    @Test
    fun readsSceneVoiceIdsWithoutDecodingDisplayText() {
        val source = "[scene gallery]\n@sc01\n@sc02\n@sc69\n".toByteArray(Charsets.UTF_8)

        val parsed = SystemNnnSceneCatalogReader().readSceneVoiceCatalog(source)

        assertEquals(listOf(1, 2, 69), parsed.sceneIds)
    }

    @Test
    fun rejectsDuplicateSceneVoiceIds() {
        val source = "@sc01\n@sc01\n".toByteArray(Charsets.US_ASCII)

        assertThrows(IOException::class.java) {
            SystemNnnSceneCatalogReader().readSceneVoiceCatalog(source)
        }
    }

    @Test
    fun reportsMatchingCountsWithoutClaimingAnIndexMapping() {
        val sceneData = SystemNnnSceneCatalogReader().readSceneData(
            sceneData(List(69) { index -> 10u to listOf(index.toUInt()) })
        )
        val voice = SystemNnnSceneVoiceCatalog((1..69).toList())
        val script = testScriptWithFilmCounts(total = 69u, reserved = 2u)

        val report = SystemNnnSceneCatalogReader().compareWithFilmTable(sceneData, voice, script)

        assertEquals(69, report.sceneDataRecordCount)
        assertEquals(69, report.sceneVoiceEntryCount)
        assertEquals(69, report.filmTableEntryCount)
        assertEquals(2, report.reservedFilmEntryCount)
        assertEquals(67, report.ordinaryFilmEntryCount)
        assertTrue(report.hasMatchingCardinalities)
        assertFalse(report.sameIndexMappingProven)
    }

    @Test
    fun reportsCardinalityMismatchWithoutInventingCorrespondence() {
        val sceneData = SystemNnnSceneData(1u, listOf(SystemNnnSceneDataRecord(10u, listOf(1u))))
        val voice = SystemNnnSceneVoiceCatalog(listOf(1, 2))
        val script = testScriptWithFilmCounts(total = 3u, reserved = 1u)

        val report = SystemNnnSceneCatalogReader().compareWithFilmTable(sceneData, voice, script)

        assertFalse(report.hasMatchingCardinalities)
        assertFalse(report.sameIndexMappingProven)
    }

    private fun sceneData(rows: List<Pair<UInt, List<UInt>>>): ByteArray {
        val byteCount = 12 + rows.sumOf { 8 + it.second.size * 4 }
        val bytes = ByteArray(byteCount)
        putUInt32(bytes, 0, byteCount.toUInt())
        putUInt32(bytes, 4, 1u)
        putUInt32(bytes, 8, rows.size.toUInt())
        var offset = 12
        rows.forEach { (type, values) ->
            putUInt32(bytes, offset, type)
            putUInt32(bytes, offset + 4, values.size.toUInt())
            offset += 8
            values.forEach { value ->
                putUInt32(bytes, offset, value)
                offset += 4
            }
        }
        return bytes
    }

    private fun testScriptWithFilmCounts(total: UInt, reserved: UInt): SystemNnnScript =
        SystemNnnScript(
            fileSizeBytes = 0x40L,
            firstHeaderWord = 0u,
            offsetTables = listOf(
                SystemNnnOffsetTable("main", 0u, 0u),
                SystemNnnOffsetTable("secondary", 0u, 0u),
                SystemNnnOffsetTable("tertiary", total, 0x100u),
                SystemNnnOffsetTable("auxiliary", reserved, 0u)
            ),
            commandStartOffset = 0x40L,
            commandEndOffset = 0x40L,
            textPoolOffset = 0x40L,
            textPool = emptyList(),
            commandBytes = ByteArray(0)
        )

    private fun putUInt32(bytes: ByteArray, offset: Int, value: UInt) {
        repeat(4) { index ->
            bytes[offset + index] = ((value shr (index * 8)) and 0xFFu).toByte()
        }
    }
}
