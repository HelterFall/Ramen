package io.github.helterfall.ramen.parser

import java.io.IOException

/**
 * Family-reference labels for resource/audio-related SYSTEM_FUNCTION records.
 *
 * The code IDs are named in tinyan/SystemNNN at pinned revision
 * 78a05053c78b3b80065a9a209c6685a7c60a2e83:
 * - nnnUtilLib/scriptDefine.h
 * - nnnLib/execscript.cpp
 *
 * That is cross-title engine context, not proof that every handler or operand
 * has identical semantics in this target. The scanner therefore preserves only
 * record metadata and numeric payload DWORDs; it never executes records or
 * resolves any pool index to text/a filename.
 */
enum class SystemNnnResourceFunctionKind(
    val codeId: Int,
    val familyReferenceName: String
) {
    MUSIC(0x0007, "MUSIC"),
    VOICE(0x0008, "VOICE"),
    SOUND(0x0009, "SOUND"),
    LOAD_DWQ(0x0015, "LOADDWQ"),
    SET_CG(0x0018, "SETCG"),
    SET_SCENE(0x0019, "SETSCENE"),
    PRELOAD_DWQ(0x001F, "PRELOADDWQ"),
    NEXT_FADE_SE(0x0066, "NEXTFADE_SE"),
    NEXT_FADE_VOICE(0x0067, "NEXTFADE_VOICE"),
    VOLUME_ONLY_SE(0x0068, "VOLUMEONLY_SE"),
    VOLUME_ONLY_VOICE(0x0069, "VOLUMEONLY_VOICE"),
    MUSIC_VOLUME_ONLY(0x006A, "MUSICVOLUMEONLY"),
    SET_VOICE_FLAG(0x006C, "SETVOICEFLAG");

    companion object {
        fun fromCodeId(codeId: Int): SystemNnnResourceFunctionKind? =
            values().firstOrNull { it.codeId == codeId }
    }
}

data class SystemNnnResourceFunctionCandidate(
    val sourceModuleName: String,
    val kind: SystemNnnResourceFunctionKind,
    val commandOffset: Long,
    val recordLengthWords: UInt,
    /** Raw first payload DWORD; some family handlers treat it as a parameter count. */
    val leadingCountWord: UInt?,
    /** Physical DWORDs after the leading word; may include additional flags/defaults. */
    val remainingPayloadWordCount: Int,
    /** Equality hint only: false does not mean the target record is malformed. */
    val countWordEqualsRemainingPayloadWordCount: Boolean?,
    /** Bounded numeric-only payload preview. Never resolves string/pool references. */
    val payloadPreview: List<UInt>,
    val sourceFilmTableIndex: Int?,
    val sourceFilmNumber: Int?,
    val sourceFilmStartOffset: Long?,
    val sourceFilmEndOffsetExclusive: Long?,
    val previousRecord: SystemNnnRecordContext?,
    val nextRecord: SystemNnnRecordContext?
)

data class SystemNnnResourceFunctionScanResult(
    val candidates: List<SystemNnnResourceFunctionCandidate>,
    val countWordPayloadDifferenceCount: Int
)

/**
 * Read-only inventory of family-known resource/audio SYSTEM_FUNCTION codes.
 *
 * It records the first payload DWORD and counts the remaining bounded words.
 * Equality is reported only as a structural comparison: family handlers may
 * carry optional trailing values, flags or defaults, so unequal counts are not
 * treated as a malformed record and never reject the SPT.
 */
class SystemNnnResourceFunctionScanner(
    private val maxPreviewWords: Int = DEFAULT_PREVIEW_WORDS,
    private val streamReader: SystemNnnCommandStreamReader = SystemNnnCommandStreamReader()
) {
    init {
        require(maxPreviewWords in 0..MAX_PREVIEW_WORDS) {
            "maxPreviewWords must be between 0 and $MAX_PREVIEW_WORDS."
        }
    }

    @Throws(IOException::class)
    fun scan(
        sourceModuleName: String,
        script: SystemNnnScript,
        stream: SystemNnnCommandStream
    ): SystemNnnResourceFunctionScanResult {
        require(sourceModuleName.isNotBlank()) { "sourceModuleName must not be blank." }
        val orderedFilmEntries = if (script.offsetTables.size > FILM_TABLE_DESCRIPTOR_INDEX) {
            streamReader.filmTableEntries(script, stream).sortedBy { it.targetCommandOffset }
        } else {
            emptyList()
        }
        if (orderedFilmEntries.zipWithNext().any { (left, right) ->
                left.targetCommandOffset == right.targetCommandOffset
            }
        ) {
            throw IOException("The SPT film table contains duplicate physical scene-entry offsets.")
        }

        val candidates = ArrayList<SystemNnnResourceFunctionCandidate>()

        stream.records.forEachIndexed { recordIndex, record ->
            if (
                record.recordKind != SystemNnnRecordKind.SYSTEM_FUNCTION ||
                record.codeFamily != SYSTEM_FUNCTION_FAMILY
            ) return@forEach

            val kind = SystemNnnResourceFunctionKind.fromCodeId(record.codeId) ?: return@forEach
            val remainingPayloadWords = (record.payloadWordCount - 1).coerceAtLeast(0)
            val leadingCountWord = if (record.payloadWordCount > 0) {
                readPayloadWord(script, record, ARGUMENT_COUNT_PAYLOAD_INDEX)
            } else {
                null
            }
            val countEqualsRemainingPayload = leadingCountWord?.let { it.toLong() == remainingPayloadWords.toLong() }
            val previewCount = minOf(record.payloadWordCount, maxPreviewWords)
            val sourceFilm = findSourceFilmEntry(record.offset, orderedFilmEntries)
            val sourceFilmIndex = sourceFilm?.let(orderedFilmEntries::indexOf)?.takeIf { it >= 0 }
            val sourceFilmEnd = sourceFilmIndex
                ?.let { orderedFilmEntries.getOrNull(it + 1)?.targetCommandOffset }
                ?: if (sourceFilm != null) stream.recordEndOffsetExclusive else null
            val previousRecord = stream.records.getOrNull(recordIndex - 1)
                ?.takeIf { sourceFilm == null || it.offset >= sourceFilm.targetCommandOffset }
                ?.let { toContext(script, it) }
            val nextRecord = stream.records.getOrNull(recordIndex + 1)
                ?.takeIf { sourceFilmEnd == null || it.offset < sourceFilmEnd }
                ?.let { toContext(script, it) }

            candidates += SystemNnnResourceFunctionCandidate(
                sourceModuleName = sourceModuleName,
                kind = kind,
                commandOffset = record.offset,
                recordLengthWords = record.lengthWords,
                leadingCountWord = leadingCountWord,
                remainingPayloadWordCount = remainingPayloadWords,
                countWordEqualsRemainingPayloadWordCount = countEqualsRemainingPayload,
                payloadPreview = List(previewCount) { index ->
                    readPayloadWord(script, record, index)
                },
                sourceFilmTableIndex = sourceFilm?.tableIndex,
                sourceFilmNumber = sourceFilm?.filmNumber,
                sourceFilmStartOffset = sourceFilm?.targetCommandOffset,
                sourceFilmEndOffsetExclusive = sourceFilmEnd,
                previousRecord = previousRecord,
                nextRecord = nextRecord
            )
        }

        return SystemNnnResourceFunctionScanResult(
            candidates = candidates,
            countWordPayloadDifferenceCount = candidates.count { it.countWordEqualsRemainingPayloadWordCount == false }
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
        payloadIndex: Int
    ): UInt {
        if (payloadIndex !in 0 until record.payloadWordCount) {
            throw IOException("The resource-function payload index is outside the record.")
        }
        val offset = record.offset + (RECORD_HEADER_WORDS + payloadIndex) * DWORD_BYTES
        val bytes = try {
            script.readCommandBytes(offset, DWORD_BYTES.toInt())
        } catch (failure: IllegalArgumentException) {
            throw IOException("Could not read the bounded resource-function payload.", failure)
        }
        return (bytes[0].toInt() and 0xFF).toUInt() or
            ((bytes[1].toInt() and 0xFF).toUInt() shl 8) or
            ((bytes[2].toInt() and 0xFF).toUInt() shl 16) or
            ((bytes[3].toInt() and 0xFF).toUInt() shl 24)
    }

    private companion object {
        const val DWORD_BYTES = 4L
        const val RECORD_HEADER_WORDS = 3
        const val SYSTEM_FUNCTION_FAMILY = 0x3333
        const val ARGUMENT_COUNT_PAYLOAD_INDEX = 0
        const val DEFAULT_PREVIEW_WORDS = 8
        const val MAX_PREVIEW_WORDS = 8
        const val MAX_CONTEXT_PAYLOAD_WORDS = 4
        const val FILM_TABLE_DESCRIPTOR_INDEX = 2
    }
}
