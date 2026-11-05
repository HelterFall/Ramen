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
    val payloadPreview: List<UInt>
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
    private val maxPreviewWords: Int = DEFAULT_PREVIEW_WORDS
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
        val candidates = ArrayList<SystemNnnResourceFunctionCandidate>()

        stream.records.forEach { record ->
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
                }
            )
        }

        return SystemNnnResourceFunctionScanResult(
            candidates = candidates,
            countWordPayloadDifferenceCount = candidates.count { it.countWordEqualsRemainingPayloadWordCount == false }
        )
    }

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
    }
}
