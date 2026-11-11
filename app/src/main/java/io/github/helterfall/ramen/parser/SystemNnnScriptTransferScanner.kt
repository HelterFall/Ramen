package io.github.helterfall.ramen.parser

import java.io.IOException

enum class SystemNnnScriptTransferKind(val codeId: Int) {
    SCRIPT(0x000A),
    SCRIPTJUMP(0x0015),
    SUBSCRIPT(0x0016);

    companion object {
        fun fromCodeId(codeId: Int): SystemNnnScriptTransferKind? =
            values().firstOrNull { it.codeId == codeId }
    }
}

data class SystemNnnScriptTransferCandidate(
    val sourceModuleName: String,
    val targetModuleName: String,
    val kind: SystemNnnScriptTransferKind,
    val commandOffset: Long,
    val pool2Index: UInt,
    val recordLengthWords: UInt,
    val sourceFilmTableIndex: Int?,
    val sourceFilmNumber: Int?,
    val sourceFilmStartOffset: Long?,
    val sourceFilmEndOffsetExclusive: Long?,
    val followingRecordLengthWord: UInt?,
    val previousRecord: SystemNnnRecordContext?,
    val nextRecord: SystemNnnRecordContext?
)

data class SystemNnnScriptTransferScanResult(
    val candidates: List<SystemNnnScriptTransferCandidate>,
    val unresolvedTargetCount: Int
)

/**
 * Read-only scanner for type-2 CONTROL records in family 0x5556 with code
 * IDs SCRIPT, SCRIPTJUMP and SUBSCRIPT. It never executes a transfer.
 */
class SystemNnnScriptTransferScanner(
    private val streamReader: SystemNnnCommandStreamReader = SystemNnnCommandStreamReader()
) {

    @Throws(IOException::class)
    fun scan(
        sourceModuleName: String,
        script: SystemNnnScript,
        stream: SystemNnnCommandStream,
        projectModuleNames: Collection<String>
    ): SystemNnnScriptTransferScanResult {
        require(sourceModuleName.isNotBlank()) { "sourceModuleName must not be blank." }
        if (projectModuleNames.isEmpty()) return SystemNnnScriptTransferScanResult(emptyList(), 0)

        val knownModules = projectModuleNames.toHashSet()
        val filmEntries = if (script.offsetTables.size > FILM_TABLE_DESCRIPTOR_INDEX) {
            streamReader.filmTableEntries(script, stream).sortedBy { it.targetCommandOffset }
        } else {
            emptyList()
        }
        if (filmEntries.zipWithNext().any { (left, right) ->
                left.targetCommandOffset == right.targetCommandOffset
            }
        ) {
            throw IOException("The SPT film table contains duplicate physical scene-entry offsets.")
        }

        val candidates = ArrayList<SystemNnnScriptTransferCandidate>()
        var unresolvedTargets = 0

        stream.records.forEach { record ->
            if (
                record.recordKind != SystemNnnRecordKind.CONTROL ||
                record.codeFamily != CONTROL_FAMILY
            ) return@forEach

            val kind = SystemNnnScriptTransferKind.fromCodeId(record.codeId) ?: return@forEach
            if (record.payloadWordCount != 1) {
                throw IOException(
                    "A System-NNN ${kind.name} record at 0x${record.offset.toString(16)} " +
                        "does not have exactly one Pool 2 operand."
                )
            }

            val poolIndex = readPayloadWord(script, record, 0)
            if (poolIndex > Int.MAX_VALUE.toUInt()) {
                unresolvedTargets += 1
                return@forEach
            }
            val entry = script.secondaryTextPool.getOrNull(poolIndex.toInt())
            if (entry == null || entry.hasDecodingErrors || entry.text !in knownModules) {
                unresolvedTargets += 1
                return@forEach
            }

            val sourceFilm = findSourceFilmEntry(record.offset, filmEntries)
            val sourceFilmIndex = sourceFilm?.let(filmEntries::indexOf)?.takeIf { it >= 0 }
            val sourceFilmEnd = sourceFilmIndex
                ?.let { filmEntries.getOrNull(it + 1)?.targetCommandOffset }
                ?: if (sourceFilm != null) stream.recordEndOffsetExclusive else null

            val recordIndex = stream.records.binarySearchBy(record.offset) { it.offset }
            val previous = if (sourceFilm != null && recordIndex > 0) {
                stream.records.getOrNull(recordIndex - 1)
                    ?.takeIf { it.offset >= sourceFilm.targetCommandOffset }
                    ?.let { toContext(script, it) }
            } else null
            val next = stream.records.getOrNull(recordIndex + 1)
                ?.takeIf { sourceFilmEnd == null || it.offset < sourceFilmEnd }
                ?.let { toContext(script, it) }

            candidates += SystemNnnScriptTransferCandidate(
                sourceModuleName = sourceModuleName,
                targetModuleName = entry.text,
                kind = kind,
                commandOffset = record.offset,
                pool2Index = poolIndex,
                recordLengthWords = record.lengthWords,
                sourceFilmTableIndex = sourceFilm?.tableIndex,
                sourceFilmNumber = sourceFilm?.filmNumber,
                sourceFilmStartOffset = sourceFilm?.targetCommandOffset,
                sourceFilmEndOffsetExclusive = sourceFilmEnd,
                followingRecordLengthWord = next?.recordLengthWords,
                previousRecord = previous,
                nextRecord = next
            )
        }

        return SystemNnnScriptTransferScanResult(candidates, unresolvedTargets)
    }

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

    private fun toContext(script: SystemNnnScript, record: SystemNnnCommandRecord) =
        SystemNnnRecordContext(
            commandOffset = record.offset,
            identifierType = record.identifierType,
            codeFamily = record.codeFamily,
            codeId = record.codeId,
            recordLengthWords = record.lengthWords,
            payloadPreview = List(minOf(record.payloadWordCount, MAX_CONTEXT_PAYLOAD_WORDS)) {
                readPayloadWord(script, record, it)
            }
        )

    private fun readPayloadWord(
        script: SystemNnnScript,
        record: SystemNnnCommandRecord,
        index: Int
    ): UInt {
        if (index !in 0 until record.payloadWordCount) {
            throw IOException("The script-transfer payload index is outside the SPT record.")
        }
        val offset = record.offset + RECORD_HEADER_BYTES + index.toLong() * UINT32_BYTES
        val bytes = try {
            script.readCommandBytes(offset, UINT32_BYTES.toInt())
        } catch (failure: IllegalArgumentException) {
            throw IOException("Could not read a script-transfer Pool 2 operand.", failure)
        }
        val value = (bytes[0].toInt() and 0xFF) or
            ((bytes[1].toInt() and 0xFF) shl 8) or
            ((bytes[2].toInt() and 0xFF) shl 16) or
            ((bytes[3].toInt() and 0xFF) shl 24)
        return value.toUInt()
    }

    private companion object {
        const val CONTROL_FAMILY = 0x5556
        const val FILM_TABLE_DESCRIPTOR_INDEX = 2
        const val RECORD_HEADER_BYTES = 12L
        const val UINT32_BYTES = 4L
        const val MAX_CONTEXT_PAYLOAD_WORDS = 4
    }
}
