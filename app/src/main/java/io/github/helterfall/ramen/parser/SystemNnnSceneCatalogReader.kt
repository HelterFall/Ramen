package io.github.helterfall.ramen.parser

import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * One scene row in scene.dat.
 *
 * The signed module number is an index into project.xtx. Its sign marks the
 * scene type; the absolute value is passed to CNameList::GetName unchanged.
 * Film numbers are one-based; a negative sign only disables completion checks.
 */
data class SystemNnnSceneEntry(
    val storedModuleNumber: Int,
    val storedFilmNumbers: List<Int>
) {
    val moduleIndex: Int
        get() = kotlin.math.abs(storedModuleNumber)

    val isHScene: Boolean
        get() = storedModuleNumber < 0

    val filmIndices: List<Int>
        get() = storedFilmNumbers.map { kotlin.math.abs(it) - 1 }

    val completionCheckFilmIndices: List<Int>
        get() = storedFilmNumbers.filter { it > 0 }.map { it - 1 }
}

/** One character/scene group from the outer scene.dat array. */
data class SystemNnnSceneCharacter(
    val scenes: List<SystemNnnSceneEntry>
)

/** Parsed scene.dat file: file size, character groups, and variable-length scene rows. */
data class SystemNnnSceneData(
    val declaredFileSize: Long,
    val characters: List<SystemNnnSceneCharacter>
) {
    val characterCount: Int
        get() = characters.size

    val recordCount: Int
        get() = characters.sumOf { it.scenes.size }

    fun sceneAt(characterIndex: Int, sceneIndex: Int): SystemNnnSceneEntry? =
        characters.getOrNull(characterIndex)?.scenes?.getOrNull(sceneIndex)
}

/** The scene identifiers listed by nya/scenevoice.xtx, in source order. */
data class SystemNnnSceneVoiceCatalog(
    val sceneIds: List<Int>
)

/** A direct scene.dat route resolved through the ordered project.xtx module list. */
data class SystemNnnSceneRoute(
    val characterIndex: Int,
    val sceneIndex: Int,
    val moduleIndex: Int,
    val moduleName: String,
    val isHScene: Boolean,
    val filmChoiceIndex: Int,
    val filmIndex: Int,
    val filmIndices: List<Int>,
    val completionCheckEnabled: Boolean
)

/**
 * Count-only comparison retained for diagnostics. It never asserts that scene
 * order and descriptor-3 pointer order are interchangeable.
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

    /** The per-row relation is now represented directly by scene.dat fields. */
    val sameIndexMappingProven: Boolean
        get() = false
}

/**
 * Bounded readers for the original System-NNN sidecars.
 *
 * scene.dat layout, matching CSceneList in NNNLib:
 *   int fileSize
 *   int characterCount
 *   repeated characterCount times:
 *     int sceneCount
 *     repeated sceneCount times:
 *       int sptNumber
 *       int filmCount
 *       int filmNumbers[filmCount]
 *
 * sptNumber is an index into project.xtx (CNameList::GetName); a negative
 * value marks an H-scene and is made positive before lookup. Film numbers are
 * one-based; absolute value minus one is passed to SetPCByFilmNumber. A
 * negative film number is playable but excluded from completion checks.
 *
 * scenevoice.xtx is scanned only for ASCII @scNN identifiers. Its CP932
 * display/header text is not decoded or copied into the resulting model.
 */
class SystemNnnSceneCatalogReader(
    private val maxSceneDataFileBytes: Long = DEFAULT_MAX_SCENE_DATA_FILE_BYTES,
    private val maxSceneVoiceFileBytes: Long = DEFAULT_MAX_SCENE_VOICE_FILE_BYTES,
    private val maxCharacters: Int = DEFAULT_MAX_CHARACTERS,
    private val maxScenesPerCharacter: Int = DEFAULT_MAX_SCENES_PER_CHARACTER,
    private val maxFilmsPerScene: Int = DEFAULT_MAX_FILMS_PER_SCENE
) {
    init {
        require(maxSceneDataFileBytes >= HEADER_BYTES.toLong())
        require(maxSceneVoiceFileBytes > 0L)
        require(maxCharacters > 0)
        require(maxScenesPerCharacter > 0)
        require(maxFilmsPerScene > 0)
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

        val declaredSize = readInt32LE(bytes, FILE_SIZE_OFFSET).toLong()
        if (declaredSize != bytes.size.toLong()) {
            throw IOException("scene.dat declared size does not match its file size.")
        }

        val characterCount = readNonNegativeCount(
            bytes,
            CHARACTER_COUNT_OFFSET,
            maxCharacters,
            "character"
        )
        var cursor = HEADER_BYTES
        var totalSceneCount = 0
        val characters = ArrayList<SystemNnnSceneCharacter>(characterCount)

        repeat(characterCount) { characterIndex ->
            ensureAvailable(bytes, cursor, INT32_BYTES, "scene.dat character header")
            val sceneCount = readNonNegativeCount(
                bytes,
                cursor,
                maxScenesPerCharacter,
                "scene"
            )
            cursor += INT32_BYTES
            totalSceneCount += sceneCount
            if (totalSceneCount > MAX_TOTAL_SCENES) {
                throw IOException("scene.dat contains too many scene rows.")
            }

            val scenes = ArrayList<SystemNnnSceneEntry>(sceneCount)
            repeat(sceneCount) { sceneIndex ->
                ensureAvailable(bytes, cursor, SCENE_HEADER_BYTES, "scene.dat scene header")
                val storedModuleNumber = readInt32LE(bytes, cursor)
                val filmCount = readNonNegativeCount(
                    bytes,
                    cursor + INT32_BYTES,
                    maxFilmsPerScene,
                    "film"
                )
                cursor += SCENE_HEADER_BYTES

                if (storedModuleNumber == Int.MIN_VALUE) {
                    throw IOException(
                        "scene.dat character $characterIndex scene $sceneIndex has an invalid module number."
                    )
                }
                val filmBytes = filmCount.toLong() * INT32_BYTES
                if (filmBytes > (bytes.size - cursor).toLong()) {
                    throw IOException(
                        "scene.dat character $characterIndex scene $sceneIndex has truncated film numbers."
                    )
                }

                val films = ArrayList<Int>(filmCount)
                repeat(filmCount) {
                    val filmNumber = readInt32LE(bytes, cursor)
                    if (filmNumber == 0 || filmNumber == Int.MIN_VALUE) {
                        throw IOException(
                            "scene.dat character $characterIndex scene $sceneIndex has an invalid film number."
                        )
                    }
                    films += filmNumber
                    cursor += INT32_BYTES
                }
                scenes += SystemNnnSceneEntry(storedModuleNumber, films)
            }
            characters += SystemNnnSceneCharacter(scenes)
        }

        if (cursor != bytes.size) {
            throw IOException("scene.dat has trailing bytes after its character and scene tables.")
        }
        return SystemNnnSceneData(declaredSize, characters)
    }

    @Throws(IOException::class)
    fun readSceneVoiceCatalog(file: File): SystemNnnSceneVoiceCatalog =
        readSceneVoiceCatalog(readBoundedFile(file, maxSceneVoiceFileBytes, "scenevoice.xtx"))

    @Throws(IOException::class)
    fun readSceneVoiceCatalog(bytes: ByteArray): SystemNnnSceneVoiceCatalog {
        if (bytes.size.toLong() > maxSceneVoiceFileBytes) {
            throw IOException("scenevoice.xtx exceeds the configured size limit.")
        }

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
            if (ids.size > MAX_TOTAL_SCENES) {
                throw IOException("scenevoice.xtx contains too many scene identifiers.")
            }
        }
        if (ids.isEmpty()) {
            throw IOException("scenevoice.xtx contains no @scNN scene identifiers.")
        }
        return SystemNnnSceneVoiceCatalog(ids)
    }

    /**
     * Resolves scene.dat's direct project module index and its one-based film
     * number to a runtime route. filmChoiceIndex selects among alternate films
     * stored in one scene row.
     */
    @Throws(IOException::class)
    fun resolveRoute(
        sceneData: SystemNnnSceneData,
        characterIndex: Int,
        sceneIndex: Int,
        filmChoiceIndex: Int,
        projectModuleNames: List<String>
    ): SystemNnnSceneRoute {
        if (characterIndex < 0 || sceneIndex < 0 || filmChoiceIndex < 0) {
            throw IOException("Scene route indexes must not be negative.")
        }
        val scene = sceneData.sceneAt(characterIndex, sceneIndex)
            ?: throw IOException("The requested scene.dat row does not exist.")
        val moduleName = projectModuleNames.getOrNull(scene.moduleIndex)
            ?: throw IOException(
                "scene.dat module index ${scene.moduleIndex} is outside project.xtx."
            )
        val storedFilmNumber = scene.storedFilmNumbers.getOrNull(filmChoiceIndex)
            ?: throw IOException("The requested film choice does not exist in the scene.dat row.")
        return SystemNnnSceneRoute(
            characterIndex = characterIndex,
            sceneIndex = sceneIndex,
            moduleIndex = scene.moduleIndex,
            moduleName = moduleName,
            isHScene = scene.isHScene,
            filmChoiceIndex = filmChoiceIndex,
            filmIndex = kotlin.math.abs(storedFilmNumber) - 1,
            filmIndices = scene.filmIndices,
            completionCheckEnabled = storedFilmNumber > 0
        )
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

    private fun readNonNegativeCount(
        bytes: ByteArray,
        offset: Int,
        maxCount: Int,
        label: String
    ): Int {
        val count = readInt32LE(bytes, offset)
        if (count < 0 || count > maxCount) {
            throw IOException("scene.dat contains an invalid $label count.")
        }
        return count
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

    private fun readInt32LE(bytes: ByteArray, offset: Int): Int {
        ensureAvailable(bytes, offset, INT32_BYTES, "scene.dat integer")
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    companion object {
        private const val HEADER_BYTES = 8
        private const val FILE_SIZE_OFFSET = 0
        private const val CHARACTER_COUNT_OFFSET = 4
        private const val SCENE_HEADER_BYTES = 8
        private const val INT32_BYTES = 4
        private const val FILM_TABLE_DESCRIPTOR_INDEX = 2
        private const val RESERVED_FILM_ENTRIES_DESCRIPTOR_INDEX = 3
        private const val DEFAULT_MAX_SCENE_DATA_FILE_BYTES = 4L * 1024L * 1024L
        private const val DEFAULT_MAX_SCENE_VOICE_FILE_BYTES = 64L * 1024L
        private const val DEFAULT_MAX_CHARACTERS = 256
        private const val DEFAULT_MAX_SCENES_PER_CHARACTER = 4096
        private const val DEFAULT_MAX_FILMS_PER_SCENE = 256
        private const val MAX_TOTAL_SCENES = 16_384
        private val SCENE_ID_PATTERN = Regex("(?m)^[\\t ]*@sc([0-9]{2,3})[\\t ]*\\r?$")
    }
}
