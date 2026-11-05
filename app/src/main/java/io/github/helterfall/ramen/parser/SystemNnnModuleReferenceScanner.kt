package io.github.helterfall.ramen.parser

import java.io.IOException

/**
 * A [1, Pool2Index] record payload that resolves exactly to a project module
 * name. This is a string reference candidate, not a proven call or jump.
 */
data class SystemNnnRecordContext(
    val commandOffset: Long,
    val identifierType: Int,
    val codeFamily: Int,
    val codeId: Int,
    val recordLengthWords: UInt,
    /** First payload words only; retained as opaque unsigned values. */
    val payloadPreview: List<UInt>
)

data class SystemNnnModuleReferenceCandidate(
    val sourceModuleName: String,
    val targetModuleName: String,
    val commandOffset: Long,
    val identifierType: Int,
    val codeFamily: Int,
    val codeId: Int,
    val pool2Index: UInt,
    val recordLengthWords: UInt,
    val sourceFilmTableIndex: Int?,
    val sourceFilmNumber: Int?,
    val sourceFilmStartOffset: Long?,
    val sourceFilmEndOffsetExclusive: Long?,
    val previousRecord: SystemNnnRecordContext?,
    val nextRecord: SystemNnnRecordContext?
)

/**
 * Read-only scanner for possible module-name references in one SPT module.
 *
 * The scanner requires an exact two-DWORD payload [1, index] and an exact
 * Pool 2 string match against project.xtx names. It deliberately does not
 * classify any matching record as a module transition.
 */
class SystemNnnModuleReferenceScanner(
    private val streamReader: SystemNnnCommandStreamReader = SystemNnnCommandStreamReader()
) {

    @Throws(IOException::class)
    fun scan(
        sourceModuleName: String,
        script: SystemNnnScript,
        stream: SystemNnnCommandStream,
        projectModuleNames: Collection<String>
    ): List<SystemNnnModuleReferenceCandidate> {
        require(sourceModuleName.isNotBlank()) { "sourceModuleName must not be blank." }
        if (projectModuleNames.isEmpty()) return emptyList()

        val knownModules = projectModuleNames.toHashSet()
        val filmEntries = if (script.offsetTables.size > FILM_TABLE_DESCRIPTOR_INDEX) {
            streamReader.filmTableEntries(script, stream)
                .sortedBy { it.targetCommandOffset }
        } else {
            emptyList()
        }
        if (filmEntries.zipWithNext().any { (left, right) ->
                left.targetCommandOffset == right.targetCommandOffset
            }
        ) {
            throw IOException("The SPT film table contains duplicate physical scene-entry offsets.")
        }
        val candidates = ArrayList<SystemNnnModuleReferenceCandidate>()

        stream.records.forEach { record ->
            if (record.payloadWordCount != REFERENCE_PAYLOAD_WORD_COUNT) {
                return@forEach
            }
            if (readPayloadWord(script, record, 0) != REFERENCE_TAG) {
                return@forEach
            }

            val poolIndex = readPayloadWord(script, record, 1)
            if (poolIndex > Int.MAX_VALUE.toUInt()) {
                return@forEach
            }
            val targetName = script.secondaryTextPool.getOrNull(poolIndex.toInt())?.text
                ?: return@forEach
            if (targetName !in knownModules) {
                return@forEach
            }

            val sourceFilm = findSourceFilmEntry(record.offset, filmEntries)
            val sourceFilmIndex = sourceFilm?.let(filmEntries::indexOf)
                ?.takeIf { it >= 0 }
            val sourceFilmEnd = sourceFilmIndex
                ?.let { filmEntries.getOrNull(it + 1)?.targetCommandOffset }
                ?: if (sourceFilm != null) stream.recordEndOffsetExclusive else null
            val recordIndex = stream.records.binarySearchBy(record.offset) { it.offset }
            val previousRecord = if (sourceFilm != null && recordIndex > 0) {
                stream.records.getOrNull(recordIndex - 1)
                    ?.takeIf { it.offset >= sourceFilm.targetCommandOffset }
                    ?.let { toContext(script, it) }
            } else {
                null
            }
            val nextRecord = if (sourceFilm != null && recordIndex >= 0) {
                stream.records.getOrNull(recordIndex + 1)
                    ?.takeIf { next -> sourceFilmEnd != null && next.offset < sourceFilmEnd }
                    ?.let { toContext(script, it) }
            } else {
                null
            }

            candidates += SystemNnnModuleReferenceCandidate(
                sourceModuleName = sourceModuleName,
                targetModuleName = targetName,
                commandOffset = record.offset,
                identifierType = record.identifierType,
                codeFamily = record.codeFamily,
                codeId = record.codeId,
                pool2Index = poolIndex,
                recordLengthWords = record.lengthWords,
                sourceFilmTableIndex = sourceFilm?.tableIndex,
                sourceFilmNumber = sourceFilm?.filmNumber,
                sourceFilmStartOffset = sourceFilm?.targetCommandOffset,
                sourceFilmEndOffsetExclusive = sourceFilmEnd,
                previousRecord = previousRecord,
                nextRecord = nextRecord
            )
        }

        return candidates
    }

    /**
     * Finds the physical film interval containing a record. Film entries are
     * sorted by their validated command offsets; reserved prefixes retain their
     * table index but intentionally have no ordinary film number.
     */
    private fun findSourceFilmEntry(
        commandOffset: Long,
        orderedEntries: List<SystemNnnFilmTableEntry>
    ): SystemNnnFilmTableEntry? {
        var low = 0
        var high = orderedEntries.lastIndex
        var bestIndex = -1
        while (low <= high) {
            val middle = low + (high - low) / 2
            if (orderedEntries[middle].targetCommandOffset <= commandOffset) {
                bestIndex = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return orderedEntries.getOrNull(bestIndex)
    }

    private fun toContext(
        script: SystemNnnScript,
        record: SystemNnnCommandRecord
    ): SystemNnnRecordContext =
        SystemNnnRecordContext(
            commandOffset = record.offset,
            identifierType = record.identifierType,
            codeFamily = record.codeFamily,
            codeId = record.codeId,
            recordLengthWords = record.lengthWords,
            payloadPreview = List(
                minOf(record.payloadWordCount, MAX_CONTEXT_PAYLOAD_WORDS)
            ) { index -> readPayloadWord(script, record, index) }
        )

    private fun readPayloadWord(
        script: SystemNnnScript,
        record: SystemNnnCommandRecord,
        payloadIndex: Int
    ): UInt {
        if (payloadIndex !in 0 until record.payloadWordCount) {
            throw IOException("The candidate module-reference payload is outside its SPT record.")
        }
        val absoluteOffset = record.offset + RECORD_HEADER_BYTES + payloadIndex.toLong() * UINT32_BYTES
        val bytes = try {
            script.readCommandBytes(absoluteOffset, UINT32_BYTES.toInt())
        } catch (failure: IllegalArgumentException) {
            throw IOException("Could not read a candidate module-reference payload.", failure)
        }
        val value = (bytes[0].toInt() and 0xFF) or
            ((bytes[1].toInt() and 0xFF) shl 8) or
            ((bytes[2].toInt() and 0xFF) shl 16) or
            ((bytes[3].toInt() and 0xFF) shl 24)
        return value.toUInt()
    }

    private companion object {
        const val REFERENCE_PAYLOAD_WORD_COUNT = 2
        const val REFERENCE_TAG = 1u
        const val FILM_TABLE_DESCRIPTOR_INDEX = 2
        const val MAX_CONTEXT_PAYLOAD_WORDS = 4
        const val RECORD_HEADER_BYTES = 12L
        const val UINT32_BYTES = 4L
    }
}
