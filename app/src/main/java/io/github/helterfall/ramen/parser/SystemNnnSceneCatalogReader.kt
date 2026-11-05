package io.github.helterfall.ramen.parser

import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * One FILMDATA row from scene.dat.
 *
 * The original engine uses [type] as the zero-based index into project.xtx
 * (its old comment says 1-based, but CNameList::GetName consumes it directly).
 * A negative value marks a special/H scene; its absolute value is still the
 * module index. Each [values] item is a signed, one-based film number; its
 * absolute value selects the film, while a negative value disables completion
 * checking for that film.
 */
data class SystemNnnSceneDataRecord(
    val type: UInt,
    val values: List<UInt>,
    val characterIndex: Int = -1,
    val sceneIndex: Int = -1
) {
    val sptNumber: Int
        get() = type.toInt()

    val moduleIndex: Int
        get() = kotlin.math.abs(sptNumber.toLong()).toInt()

    val isSpecialScene: Boolean
        get() = sptNumber < 0

    val filmNumbers: List<Int>
        get() = values.map(UInt::toInt)
}

data class SystemNnnResolvedSceneFilm(
    val storedFilmNumber: Int,
    /** Zero-based ordinary film index used by SetPCByFilmNumber. */
    val filmIndex: Int,
    /** Negative scene.dat entries play normally but are ignored for completion checks. */
    val checkForCompletion: Boolean
)

data class SystemNnnResolvedSceneRoute(
    val characterIndex: Int,
    val sceneIndex: Int,
    val moduleIndex: Int,
    val moduleName: String,
    val isSpecialScene: Boolean,
    val films: List<SystemNnnResolvedSceneFilm>
)

/**
 * Parsed scene.dat. The legacy [version] property now preserves the second
 * header DWORD, which is the character count in the reference CSceneList.
 */
data class SystemNnnSceneData(
    val version: UInt,
    val records: List<SystemNnnSceneDataRecord>
) {
    val characterCount: Int
        get() = version.toInt()

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
    val sameIndexMappingProven: Boolean
        get() = false
}

/**
 * Bounded read-only readers for the scene metadata sidecars.
 *
 * scene.dat follows CSceneList:
 *   u32 fileSize, u32 characterCount,
 *   repeated per character { u32 sceneCount,
 *     repeated per scene { i32 projectModuleIndex, i32 filmCount,
 *       filmCount * i32 oneBasedFilmNumbers } }.
 *
 * projectModuleIndex is zero-based because the reference passes it directly
 * to CNameList::GetName. Negative module values flag special scenes. Negative
 * film values are played by absolute one-based number but ignored for
 * completion checks.
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

        val characterCountValue = readUInt32LE(bytes, UINT32_BYTES).toLong()
        if (characterCountValue > MAX_CHARACTERS.toLong()) {
            throw IOException("scene.dat contains too many character groups.")
        }

        var cursor = HEADER_BYTES
        var totalSceneCount = 0
        val records = ArrayList<SystemNnnSceneDataRecord>()
        repeat(characterCountValue.toInt()) { characterIndex ->
            ensureAvailable(bytes, cursor, UINT32_BYTES.toInt(), "scene.dat character header")
            val sceneCountValue = readUInt32LE(bytes, cursor.toLong()).toLong()
            cursor += UINT32_BYTES.toInt()
            if (sceneCountValue > (maxRecords - totalSceneCount).toLong()) {
                throw IOException("scene.dat contains too many scene rows.")
            }

            repeat(sceneCountValue.toInt()) { sceneIndex ->
                ensureAvailable(bytes, cursor, RECORD_HEADER_BYTES, "scene.dat scene row")
                val moduleIndex = readUInt32LE(bytes, cursor.toLong()).toInt()
                val filmCountValue = readUInt32LE(
                    bytes,
                    cursor.toLong() + UINT32_BYTES
                ).toLong()
                cursor += RECORD_HEADER_BYTES

                if (filmCountValue > maxValuesPerRecord.toLong()) {
                    throw IOException("A scene.dat scene row exceeds the film-count limit.")
                }
                if (filmCountValue * UINT32_BYTES > (bytes.size - cursor).toLong()) {
                    throw IOException("A scene.dat scene row has truncated film numbers.")
                }

                val filmNumbers = ArrayList<UInt>(filmCountValue.toInt())
                repeat(filmCountValue.toInt()) { filmIndex ->
                    val raw = readUInt32LE(
                        bytes,
                        cursor.toLong() + filmIndex.toLong() * UINT32_BYTES
                    )
                    if (raw.toInt() == 0 || raw.toInt() == Int.MIN_VALUE) {
                        throw IOException("scene.dat contains an invalid zero or overflowing film number.")
                    }
                    filmNumbers += raw
                }
                cursor += (filmCountValue * UINT32_BYTES).toInt()
                records += SystemNnnSceneDataRecord(
                    type = moduleIndex.toUInt(),
                    values = filmNumbers,
                    characterIndex = characterIndex,
                    sceneIndex = sceneIndex
                )
                totalSceneCount += 1
            }
        }

        if (cursor != bytes.size) {
            throw IOException("scene.dat has trailing bytes after its declared character and scene rows.")
        }
        return SystemNnnSceneData(characterCountValue.toUInt(), records)
    }

    /**
     * Resolves scene.dat's zero-based SPT module index through project.xtx and
     * validates every one-based film number against that module's ordinary
     * descriptor-3 film slots. This is the concrete scene → module → film map.
     */
    @Throws(IOException::class)
    fun resolveRoutes(
        sceneData: SystemNnnSceneData,
        projectModuleNames: List<String>,
        scriptsByModuleName: Map<String, SystemNnnScript>
    ): List<SystemNnnResolvedSceneRoute> {
        val scripts = scriptsByModuleName.entries.associate {
            it.key.lowercase(java.util.Locale.ROOT) to it.value
        }
        return sceneData.records.map { row ->
            val moduleIndex = row.moduleIndex
            val moduleName = projectModuleNames.getOrNull(moduleIndex)
                ?: throw IOException(
                    "scene.dat row ${row.characterIndex}/${row.sceneIndex} references project module index $moduleIndex."
                )
            val script = scripts[moduleName.lowercase(java.util.Locale.ROOT)]
                ?: throw IOException("scene.dat references module '$moduleName', but its SPT was not loaded.")
            val filmTableCount = script.offsetTables.getOrNull(FILM_TABLE_DESCRIPTOR_INDEX)
                ?.entryCount
                ?.toLong()
                ?: throw IOException("Module '$moduleName' has no descriptor-3 film table.")
            val reservedCount = script.offsetTables.getOrNull(RESERVED_FILM_ENTRIES_DESCRIPTOR_INDEX)
                ?.entryCount
                ?.toLong()
                ?: 0L
            if (reservedCount > filmTableCount) {
                throw IOException("Module '$moduleName' has an invalid reserved-film prefix.")
            }
            val ordinaryFilmCount = filmTableCount - reservedCount

            val films = row.filmNumbers.map { storedFilmNumber ->
                val filmIndex = kotlin.math.abs(storedFilmNumber.toLong()) - 1L
                if (filmIndex < 0L || filmIndex >= ordinaryFilmCount) {
                    throw IOException(
                        "scene.dat row ${row.characterIndex}/${row.sceneIndex} selects " +
                            "film $storedFilmNumber outside module '$moduleName'."
                    )
                }
                SystemNnnResolvedSceneFilm(
                    storedFilmNumber = storedFilmNumber,
                    filmIndex = filmIndex.toInt(),
                    checkForCompletion = storedFilmNumber > 0
                )
            }

            SystemNnnResolvedSceneRoute(
                characterIndex = row.characterIndex,
                sceneIndex = row.sceneIndex,
                moduleIndex = moduleIndex,
                moduleName = moduleName,
                isSpecialScene = row.isSpecialScene,
                films = films
            )
        }
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
