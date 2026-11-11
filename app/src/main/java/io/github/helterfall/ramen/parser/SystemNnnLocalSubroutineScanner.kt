package io.github.helterfall.ramen.parser

import java.io.IOException

/**
 * A local SUB record linked through the descriptor-3 pointer vector.
 *
 * The first operand is preserved as the descriptor table index. The second
 * operand is the local SUB identifier; its dense numbering is reported by the
 * scan result, not assumed by the scanner.
 */
data class SystemNnnLocalSubroutineCandidate(
    val sourceModuleName: String,
    val commandOffset: Long,
    val subroutineId: Int,
    val targetTableIndex: Int,
    val targetCommandOffset: Long,
    val targetFunctionCodeId: Int,
    val targetFilmNumber: Int?,
    val sourceFilmTableIndex: Int?,
    val sourceFilmNumber: Int?,
    val sourceFilmStartOffset: Long?,
    val sourceFilmEndOffsetExclusive: Long?,
    val previousRecord: SystemNnnRecordContext?,
    val nextRecord: SystemNnnRecordContext?
)

data class SystemNnnLocalSubroutineScanResult(
    val candidates: List<SystemNnnLocalSubroutineCandidate>,
    val subroutineIdsAreDense: Boolean,
    val missingSubroutineIds: List<Int>,
    val duplicateSubroutineIds: List<Int>,
    val unresolvedTargetCount: Int
)

/**
 * Read-only scanner for CONTROL SUB (0x5556/0x0009).
 *
 * In the NNNLib family reference SUB uses its first operand to index the
 * descriptor-3 jump vector and its second operand as a local subroutine ID.
 * This scanner checks those relationships against the target SPT but does
 * not execute SUB or RETURN.
 */
class SystemNnnLocalSubroutineScanner(
    private val streamReader: SystemNnnCommandStreamReader = SystemNnnCommandStreamReader()
) {

    @Throws(IOException::class)
    fun scan(
        sourceModuleName: String,
        script: SystemNnnScript,
        stream: SystemNnnCommandStream
    ): SystemNnnLocalSubroutineScanResult {
        require(sourceModuleName.isNotBlank()) { "sourceModuleName must not be blank." }

        val tableEntries = streamReader.filmTableEntries(script, stream)
            .sortedBy { it.targetCommandOffset }
        if (tableEntries.zipWithNext().any { (left, right) ->
                left.targetCommandOffset == right.targetCommandOffset
            }
        ) {
            throw IOException("The SPT descriptor-3 table contains duplicate target offsets.")
        }

        val subRecords = stream.records.filter { record ->
            record.recordKind == SystemNnnRecordKind.CONTROL &&
                record.codeFamily == CONTROL_FAMILY &&
                record.codeId == CODE_CONTROL_SUB
        }
        if (subRecords.size > MAX_SUBROUTINES_PER_MODULE) {
            throw IOException("The SPT module contains too many local SUB records.")
        }

        val ids = ArrayList<Int>(subRecords.size)
        val candidates = ArrayList<SystemNnnLocalSubroutineCandidate>(subRecords.size)
        var unresolvedTargets = 0

        subRecords.forEach { record ->
            if (record.payloadWordCount != SUB_PAYLOAD_WORD_COUNT) {
                throw IOException(
                    "A CONTROL SUB record at 0x${record.offset.toString(16)} " +
                        "does not contain the two observed operands."
                )
            }

            val tableIndexValue = readPayloadWord(script, record, 0)
            val subroutineIdValue = readPayloadWord(script, record, 1)
            if (subroutineIdValue > MAX_SUBROUTINE_ID.toUInt()) {
                throw IOException("A CONTROL SUB record has an out-of-range local subroutine ID.")
            }
            val subroutineId = subroutineIdValue.toInt()
            ids += subroutineId

            if (tableIndexValue > Int.MAX_VALUE.toUInt() ||
                tableIndexValue.toLong() >= tableEntries.size.toLong()
            ) {
                unresolvedTargets += 1
                return@forEach
            }

            val tableIndex = tableIndexValue.toInt()
            val targetEntry = tableEntries[tableIndex]
            val targetRecordIndex = stream.records.binarySearchBy(targetEntry.targetCommandOffset) { it.offset }
            val targetRecord = stream.records.getOrNull(targetRecordIndex)
                ?: throw IOException("A descriptor-3 SUB target is not a command-record boundary.")

            if (
                targetRecord.recordKind != SystemNnnRecordKind.SYSTEM_FUNCTION ||
                targetRecord.codeFamily != SYSTEM_FUNCTION_FAMILY ||
                targetRecord.codeId !in setOf(RESERVED_FILM_ENTRY_CODE_ID, FILM_ENTRY_CODE_ID) ||
                targetRecord.lengthWords != FILM_TABLE_FUNCTION_LENGTH_WORDS
            ) {
                throw IOException("A descriptor-3 SUB target has an unexpected function-record type.")
            }

            val sourceFilm = findSourceFilmEntry(record.offset, tableEntries)
            val sourceFilmIndex = sourceFilm?.let(tableEntries::indexOf)?.takeIf { it >= 0 }
            val sourceFilmEnd = sourceFilmIndex
                ?.let { tableEntries.getOrNull(it + 1)?.targetCommandOffset }
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

            candidates += SystemNnnLocalSubroutineCandidate(
                sourceModuleName = sourceModuleName,
                commandOffset = record.offset,
                subroutineId = subroutineId,
                targetTableIndex = tableIndex,
                targetCommandOffset = targetEntry.targetCommandOffset,
                targetFunctionCodeId = targetRecord.codeId,
                targetFilmNumber = targetEntry.filmNumber,
                sourceFilmTableIndex = sourceFilm?.tableIndex,
                sourceFilmNumber = sourceFilm?.filmNumber,
                sourceFilmStartOffset = sourceFilm?.targetCommandOffset,
                sourceFilmEndOffsetExclusive = sourceFilmEnd,
                previousRecord = previous,
                nextRecord = next
            )
        }

        val counts = ids.groupingBy { it }.eachCount()
        val duplicates = counts.filterValues { it > 1 }.keys.sorted()
        val distinctIds = counts.keys
        val missing = (0 until ids.size).filterNot { it in distinctIds }
        val dense = duplicates.isEmpty() && missing.isEmpty() && unresolvedTargets == 0

        return SystemNnnLocalSubroutineScanResult(
            candidates = candidates,
            subroutineIdsAreDense = dense,
            missingSubroutineIds = missing,
            duplicateSubroutineIds = duplicates,
            unresolvedTargetCount = unresolvedTargets
        )
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

    private fun toContext(
        script: SystemNnnScript,
        record: SystemNnnCommandRecord
    ) = SystemNnnRecordContext(
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
            throw IOException("The local SUB operand index is outside the command record.")
        }
        val offset = record.offset + RECORD_HEADER_BYTES + index.toLong() * UINT32_BYTES
        val bytes = try {
            script.readCommandBytes(offset, UINT32_BYTES.toInt())
        } catch (failure: IllegalArgumentException) {
            throw IOException("Could not read a local SUB operand.", failure)
        }
        val value = (bytes[0].toInt() and 0xFF) or
            ((bytes[1].toInt() and 0xFF) shl 8) or
            ((bytes[2].toInt() and 0xFF) shl 16) or
            ((bytes[3].toInt() and 0xFF) shl 24)
        return value.toUInt()
    }

    private companion object {
        const val CONTROL_FAMILY = 0x5556
        const val CODE_CONTROL_SUB = 0x0009
        const val SYSTEM_FUNCTION_FAMILY = 0x3333
        const val RESERVED_FILM_ENTRY_CODE_ID = 0x51
        const val FILM_ENTRY_CODE_ID = 0x50
        const val FILM_TABLE_FUNCTION_LENGTH_WORDS = 5u
        const val SUB_PAYLOAD_WORD_COUNT = 2
        const val RECORD_HEADER_BYTES = 12L
        const val UINT32_BYTES = 4L
        const val MAX_CONTEXT_PAYLOAD_WORDS = 4
        const val MAX_SUBROUTINES_PER_MODULE = 10_000
        const val MAX_SUBROUTINE_ID = 10_000
    }
}
