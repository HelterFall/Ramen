package io.github.helterfall.ramen.parser

import java.io.File
import java.io.IOException
import java.nio.file.Files

/** One opaque variable-length row from scene.dat. */
data class SystemNnnSceneDataRecord(
    val type: UInt,
    val values: List<UInt>
)

/** Parsed scene.dat container. Row types and values are preserved without assigning semantics. */
data class SystemNnnSceneData(
    val version: UInt,
    val records: List<SystemNnnSceneDataRecord>
) {
    val recordCount: Int
        get() = records.size
}

/** The scene identifiers listed by nya/scenevoice.xtx, in source order. */
data class SystemNnnSceneVoiceCatalog(
    val sceneIds: List<Int>
)

/**
 * Count-only comparison between scene metadata and an SPT film pointer table.
 *
 * A matching count is a useful correlation, not proof that rows and film slots
 * correspond by index. No runtime navigation decision may be based on this report.
 */
data class SystemNnnSceneCatalogAlignment(
    val sceneDataRecordCount: Int,
    val sceneVoiceSceneIds: List<Int>,
    val filmTableEntryCount: Int,
    val reservedFilmEntryCount: Int
) {
    val sceneVoiceEntryCount: Int
        get() = sceneVoiceSceneIds.size

    val ordinaryFilmEntryCount: Int
        get() = filmTableEntryCount - reservedFilmEntryCount

    val sceneDataAndVoiceCountsMatch: Boolean
        get() = sceneDataRecordCount == sceneVoiceEntryCount

    val sceneDataAndFilmTableCountsMatch: Boolean
        get() = sceneDataRecordCount == filmTableEntryCount

    val hasMatchingCardinalities: Boolean
        get() = sceneDataAndVoiceCountsMatch && sceneDataAndFilmTableCountsMatch

    /** Deliberately false until an actual row-to-pointer relation is demonstrated. */
    val sameIndexMappingProven: Boolean = false
}

/**
 * Bounded read-only readers for the scene metadata sidecars.
 *
 * scene.dat is parsed as:
 *   u32 fileSize, u32 version, u32 recordCount,
 *   repeated { u32 type, u32 valueCount, valueCount * u32 values }.
 *
 * scenevoice.xtx is scanned only for ASCII @scNN identifiers. Its CP932
 * display/header text is not decoded or copied into the resulting model.
 */
class SystemNnnSceneCatalogReader(
    private val maxSceneDataFileBytes: Long = DEFAULT_MAX_SCENE_DATA_FILE_BYTES,
    private val maxSceneVoiceFileBytes: Long = DEFAULT_MAX_SCENE_VOICE_FILE_BYTES,
    private val maxRecords: Int = DEFAULT_MAX_RECORDS,
    private val maxValuesPerRecord: Int = DEFAULT_MAX_VALUES_PER_RECORD
) {
    init {
        require(maxSceneDataFileBytes >= HEADER_BYTES.toLong())
        require(maxSceneVoiceFileBytes > 0L)
        require(maxRecords > 0)
        require(maxValuesPerRecord >= 0)
    }

    @Throws(IOException::class)
    fun readSceneData(file: File): SystemNnnSceneData =
        readSceneData(readBoundedFile(file, maxSceneDataFileBytes, "scene.dat"))

    @Throws(IOException::class)
    fun readSceneData(bytes: ByteArray): SystemNnnSceneData {
        if (bytes.size.toLong() > maxSceneDataFileBytes) {
            throw IOException("scene.dat exceeds the configured size limit.")
        }
        if (bytes.size < HEADER_BYTES) {
            throw IOException("scene.dat is shorter than its fixed header.")
        }

        val declaredSize = readUInt32LE(bytes, 0).toLong()
        if (declaredSize != bytes.size.toLong()) {
            throw IOException("scene.dat declared size does not match its file size.")
        }

        val version = readUInt32LE(bytes, UINT32_BYTES)
        if (version != SUPPORTED_VERSION) {
            throw IOException("scene.dat uses an unsupported version.")
        }

        val recordCountValue = readUInt32LE(bytes, UINT32_BYTES * 2L).toLong()
        if (recordCountValue > maxRecords.toLong()) {
            throw IOException("scene.dat contains too many records.")
        }

        var cursor = HEADER_BYTES
        val records = ArrayList<SystemNnnSceneDataRecord>(recordCountValue.toInt())
        repeat(recordCountValue.toInt()) {
            ensureAvailable(bytes, cursor, RECORD_HEADER_BYTES, "scene.dat record header")
            val type = readUInt32LE(bytes, cursor.toLong())
            val valueCount = readUInt32LE(bytes, cursor.toLong() + UINT32_BYTES).toLong()
            cursor += RECORD_HEADER_BYTES

            if (valueCount > maxValuesPerRecord.toLong()) {
                throw IOException("A scene.dat record exceeds the value-count limit.")
            }
            val valueBytes = valueCount * UINT32_BYTES
            if (valueBytes > (bytes.size - cursor).toLong()) {
                throw IOException("A scene.dat record has truncated values.")
            }

            val values = ArrayList<UInt>(valueCount.toInt())
            repeat(valueCount.toInt()) { index ->
                values += readUInt32LE(bytes, cursor.toLong() + index.toLong() * UINT32_BYTES)
            }
            cursor += valueBytes.toInt()
            records += SystemNnnSceneDataRecord(type, values)
        }

        if (cursor != bytes.size) {
            throw IOException("scene.dat has trailing bytes after its declared records.")
        }
        return SystemNnnSceneData(version, records)
    }

    @Throws(IOException::class)
    fun readSceneVoiceCatalog(file: File): SystemNnnSceneVoiceCatalog =
        readSceneVoiceCatalog(readBoundedFile(file, maxSceneVoiceFileBytes, "scenevoice.xtx"))

    @Throws(IOException::class)
    fun readSceneVoiceCatalog(bytes: ByteArray): SystemNnnSceneVoiceCatalog {
        if (bytes.size.toLong() > maxSceneVoiceFileBytes) {
            throw IOException("scenevoice.xtx exceeds the configured size limit.")
        }

        // ISO-8859-1 is used as a byte-preserving view. Only ASCII @scNN
        // tokens are consumed; all other CP932 bytes remain uninterpreted.
        val byteView = String(bytes, Charsets.ISO_8859_1)
        val ids = ArrayList<Int>()
        val seen = HashSet<Int>()
        SCENE_ID_PATTERN.findAll(byteView).forEach { match ->
            val id = match.groupValues[1].toIntOrNull()
                ?: throw IOException("scenevoice.xtx contains an invalid scene identifier.")
            if (id <= 0 || !seen.add(id)) {
                throw IOException("scenevoice.xtx contains an invalid or duplicate scene identifier.")
            }
            ids += id
            if (ids.size > maxRecords) {
                throw IOException("scenevoice.xtx contains too many scene identifiers.")
            }
        }
        if (ids.isEmpty()) {
            throw IOException("scenevoice.xtx contains no @scNN scene identifiers.")
        }
        return SystemNnnSceneVoiceCatalog(ids)
    }

    @Throws(IOException::class)
    fun compareWithFilmTable(
        sceneData: SystemNnnSceneData,
        sceneVoiceCatalog: SystemNnnSceneVoiceCatalog,
        script: SystemNnnScript
    ): SystemNnnSceneCatalogAlignment {
        val filmTable = script.offsetTables.getOrNull(FILM_TABLE_DESCRIPTOR_INDEX)
            ?: throw IOException("The SPT film-entry descriptor is missing.")
        val reservedCount = script.offsetTables.getOrNull(RESERVED_FILM_ENTRIES_DESCRIPTOR_INDEX)
            ?.entryCount
            ?.toLong()
            ?: 0L
        val totalCount = filmTable.entryCount.toLong()
        if (reservedCount > totalCount) {
            throw IOException("The SPT reserved-film count exceeds the film table.")
        }
        if (totalCount > Int.MAX_VALUE.toLong()) {
            throw IOException("The SPT film table is too large for scene catalog diagnostics.")
        }

        return SystemNnnSceneCatalogAlignment(
            sceneDataRecordCount = sceneData.recordCount,
            sceneVoiceSceneIds = sceneVoiceCatalog.sceneIds,
            filmTableEntryCount = totalCount.toInt(),
            reservedFilmEntryCount = reservedCount.toInt()
        )
    }

    private fun readBoundedFile(file: File, maxBytes: Long, label: String): ByteArray {
        if (!file.isFile || Files.isSymbolicLink(file.toPath())) {
            throw IOException("$label is not a regular file.")
        }
        if (file.length() > maxBytes) {
            throw IOException("$label exceeds the configured size limit.")
        }
        val bytes = try {
            file.readBytes()
        } catch (failure: Exception) {
            throw IOException("Could not read $label.", failure)
        }
        if (bytes.size.toLong() > maxBytes) {
            throw IOException("$label exceeds the configured size limit.")
        }
        return bytes
    }

    private fun ensureAvailable(bytes: ByteArray, offset: Int, length: Int, label: String) {
        if (offset < 0 || length < 0 || offset > bytes.size - length) {
            throw IOException("The $label is truncated.")
        }
    }

    private fun readUInt32LE(bytes: ByteArray, offset: Long): UInt {
        if (offset < 0L || offset > bytes.size.toLong() - UINT32_BYTES) {
            throw IOException("A 32-bit scene metadata field extends beyond the file.")
        }
        val index = offset.toInt()
        val value = (bytes[index].toInt() and 0xFF) or
            ((bytes[index + 1].toInt() and 0xFF) shl 8) or
            ((bytes[index + 2].toInt() and 0xFF) shl 16) or
            ((bytes[index + 3].toInt() and 0xFF) shl 24)
        return value.toUInt()
    }

    companion object {
        private const val HEADER_BYTES = 12
        private const val RECORD_HEADER_BYTES = 8
        private const val UINT32_BYTES = 4L
        private const val FILM_TABLE_DESCRIPTOR_INDEX = 2
        private const val RESERVED_FILM_ENTRIES_DESCRIPTOR_INDEX = 3
        private const val DEFAULT_MAX_SCENE_DATA_FILE_BYTES = 4L * 1024L * 1024L
        private const val DEFAULT_MAX_SCENE_VOICE_FILE_BYTES = 64L * 1024L
        private const val DEFAULT_MAX_RECORDS = 4096
        private const val DEFAULT_MAX_VALUES_PER_RECORD = 64
        private val SUPPORTED_VERSION = 1u
        private val SCENE_ID_PATTERN = Regex("(?m)^[\\t ]*@sc([0-9]{2,3})[\\t ]*\\r?$")
    }
}
