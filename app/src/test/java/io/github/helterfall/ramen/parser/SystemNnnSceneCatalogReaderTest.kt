package io.github.helterfall.ramen.parser

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemNnnSceneCatalogReaderTest {

    @Test
    fun readsSceneRowsAndPreservesSignedModuleAndFilmNumbers() {
        val bytes = sceneData(
            listOf(
                10 to listOf(1),
                -13 to listOf(29, -31, 33, 34),
                18 to listOf(9, 10, 11)
            )
        )

        val parsed = SystemNnnSceneCatalogReader().readSceneData(bytes)

        assertEquals(bytes.size.toLong(), parsed.declaredFileSize)
        assertEquals(1, parsed.characterCount)
        assertEquals(3, parsed.recordCount)

        val first = parsed.sceneAt(0, 0)!!
        assertEquals(10, first.moduleIndex)
        assertFalse(first.isHScene)
        assertEquals(listOf(0), first.filmIndices)

        val second = parsed.sceneAt(0, 1)!!
        assertEquals(13, second.moduleIndex)
        assertTrue(second.isHScene)
        assertEquals(listOf(29, -31, 33, 34), second.storedFilmNumbers)
        assertEquals(listOf(28, 30, 32, 33), second.filmIndices)
        assertEquals(listOf(28, 32, 33), second.completionCheckFilmIndices)

        assertEquals(listOf(8, 9, 10), parsed.sceneAt(0, 2)!!.filmIndices)
        assertEquals(null, parsed.sceneAt(1, 0))
    }

    @Test
    fun rejectsSceneDataWithSizeMismatchTruncationOrTrailingBytes() {
        val valid = sceneData(listOf(10 to listOf(1)))
        val wrongSize = valid.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val truncated = valid.copyOf(valid.size - 1)
        val trailing = valid + byteArrayOf(0)

        val reader = SystemNnnSceneCatalogReader()
        assertThrows(IOException::class.java) { reader.readSceneData(wrongSize) }
        assertThrows(IOException::class.java) { reader.readSceneData(truncated) }
        assertThrows(IOException::class.java) { reader.readSceneData(trailing) }
    }

    @Test
    fun rejectsInvalidCountsAndInvalidModuleOrFilmNumbers() {
        val valid = sceneData(listOf(10 to listOf(1)))
        val negativeCharacterCount = valid.copyOf().also { putInt32(it, 4, -1) }
        val invalidModuleNumber = sceneData(listOf(Int.MIN_VALUE to listOf(1)))
        val invalidZeroFilmNumber = sceneData(listOf(10 to listOf(0)))
        val tooManyScenes = sceneData(listOf(10 to listOf(1), 11 to listOf(2)))
        val tooManyFilms = sceneData(listOf(10 to listOf(1, 2)))

        assertThrows(IOException::class.java) {
            SystemNnnSceneCatalogReader().readSceneData(negativeCharacterCount)
        }
        assertThrows(IOException::class.java) {
            SystemNnnSceneCatalogReader().readSceneData(invalidModuleNumber)
        }
        assertThrows(IOException::class.java) {
            SystemNnnSceneCatalogReader().readSceneData(invalidZeroFilmNumber)
        }
        assertThrows(IOException::class.java) {
            SystemNnnSceneCatalogReader(maxScenesPerCharacter = 1).readSceneData(tooManyScenes)
        }
        assertThrows(IOException::class.java) {
            SystemNnnSceneCatalogReader(maxFilmsPerScene = 1).readSceneData(tooManyFilms)
        }
    }

    @Test
    fun readsSceneVoiceIdsWithoutDecodingDisplayText() {
        val source = "[scene gallery]\r\n@sc01\r\n@sc02\r\n@sc69\r\n".toByteArray(Charsets.UTF_8)

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
        val parsedSceneData = SystemNnnSceneCatalogReader().readSceneData(
            sceneData(List(69) { index -> 10 to listOf(index + 1) })
        )
        val voice = SystemNnnSceneVoiceCatalog((1..69).toList())
        val script = testScriptWithFilmCounts(total = 69u, reserved = 2u)

        val report = SystemNnnSceneCatalogReader().compareWithFilmTable(parsedSceneData, voice, script)

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
        val sceneData = SystemNnnSceneData(
            declaredFileSize = 1L,
            characters = listOf(
                SystemNnnSceneCharacter(
                    scenes = listOf(SystemNnnSceneEntry(10, listOf(1)))
                )
            )
        )
        val voice = SystemNnnSceneVoiceCatalog(listOf(1, 2))
        val script = testScriptWithFilmCounts(total = 3u, reserved = 1u)

        val report = SystemNnnSceneCatalogReader().compareWithFilmTable(sceneData, voice, script)

        assertFalse(report.hasMatchingCardinalities)
        assertFalse(report.sameIndexMappingProven)
    }

    private fun sceneData(rows: List<Pair<Int, List<Int>>>): ByteArray {
        val byteCount = 12 + rows.sumOf { 8 + it.second.size * 4 }
        val bytes = ByteArray(byteCount)
        putInt32(bytes, 0, byteCount)
        putInt32(bytes, 4, 1) // one character group
        putInt32(bytes, 8, rows.size)

        var offset = 12
        rows.forEach { (moduleNumber, filmNumbers) ->
            putInt32(bytes, offset, moduleNumber)
            putInt32(bytes, offset + 4, filmNumbers.size)
            offset += 8
            filmNumbers.forEach { filmNumber ->
                putInt32(bytes, offset, filmNumber)
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

    private fun putInt32(bytes: ByteArray, offset: Int, value: Int) {
        repeat(4) { index ->
            bytes[offset + index] = (value ushr (index * 8)).toByte()
        }
    }
}
