package io.github.helterfall.ramen.parser

import java.io.IOException

/**
 * A [1, Pool2Index] record payload that resolves exactly to a project module
 * name. This is a string reference candidate, not a proven call or jump.
 */
data class SystemNnnModuleReferenceCandidate(
    val sourceModuleName: String,
    val targetModuleName: String,
    val commandOffset: Long,
    val identifierType: Int,
    val codeFamily: Int,
    val codeId: Int,
    val pool2Index: UInt,
    val recordLengthWords: UInt
)

/**
 * Read-only scanner for possible module-name references in one SPT module.
 *
 * The scanner requires an exact two-DWORD payload [1, index] and an exact
 * Pool 2 string match against project.xtx names. It deliberately does not
 * classify any matching record as a module transition.
 */
class SystemNnnModuleReferenceScanner {

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

            candidates += SystemNnnModuleReferenceCandidate(
                sourceModuleName = sourceModuleName,
                targetModuleName = targetName,
                commandOffset = record.offset,
                identifierType = record.identifierType,
                codeFamily = record.codeFamily,
                codeId = record.codeId,
                pool2Index = poolIndex,
                recordLengthWords = record.lengthWords
            )
        }

        return candidates
    }

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
        const val RECORD_HEADER_BYTES = 12L
        const val UINT32_BYTES = 4L
    }
}
