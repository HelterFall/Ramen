package io.github.helterfall.ramen.runtime.engine

import io.github.helterfall.ramen.runtime.resources.PackArchiveReader
import io.github.helterfall.ramen.runtime.scenario.ScriptScenarioLoader
import java.io.IOException

enum class BinaryResourceType(val id: String) {
    SCRIPT("script"),
    RESOURCE_CONTAINER("resource-container"),
    GRAPHIC_ARCHIVE("graphic-archive")
}

/**
 * Recognizes known binary marker signatures and records structural validation separately.
 *
 * A marker match is discovery evidence, not proof that the body uses the synthetic Ramen
 * test layout. In particular, a legacy SPT dialect may share the script prefix while
 * remaining unsupported by ScriptScenarioLoader. Only a successfully parsed Ramen Script
 * v1 file is eligible for the experimental Script v1 player.
 */
class BinarySignatureProbe : EngineAdapter {
    override val descriptor = EngineAdapterDescriptor(
        id = "ramen-binary-signature",
        displayName = "Ramen test binary signatures",
        formatIds = setOf("ramen-test-binary-signatures-v1")
    )

    /**
     * Inspects one explicitly selected source entry.
     *
     * Short files and unrecognized magic bytes are reported as Malformed. Once a known
     * signature is present, the probe returns a Match even when the synthetic body parser
     * cannot validate the layout; the evidence explains that the body remains unparsed.
     */
    fun probeFile(source: EngineSource, relativePath: String): EngineProbeResult {
        val entry = source.entries.firstOrNull { it.relativePath == relativePath }
            ?: return malformed(
                "The requested file is not present in the source snapshot.",
                relativePath
            )

        if (entry.sizeBytes < MAGIC_BYTE_COUNT) {
            return malformed(
                "File has ${entry.sizeBytes} bytes; an 8-byte magic header is required.",
                relativePath
            )
        }

        val (signature, validationDescription) = try {
            source.openRandomAccess(relativePath).use { randomAccess ->
                if (randomAccess.sizeBytes < MAGIC_BYTE_COUNT) {
                    return malformed(
                        "The source became shorter than the required 8-byte magic header.",
                        relativePath
                    )
                }

                val header = ByteArray(MAGIC_BYTE_COUNT)
                randomAccess.readFullyAt(0L, header)
                val signatureMatch = matchSignature(header)
                    ?: return malformed(
                        "Unrecognized 8-byte magic header: ${header.toHexString()}.",
                        relativePath
                    )
                val (matchedSignature, xorKey) = signatureMatch

                val validation = when (matchedSignature.resourceType) {
                    BinaryResourceType.RESOURCE_CONTAINER -> {
                        try {
                            val index = PackArchiveReader(randomAccess).openArchive()
                            " Validated Ramen test-container index with ${index.entries.size} entries."
                        } catch (exception: IOException) {
                            // The signature is still useful discovery evidence for a
                            // proprietary container that the synthetic test reader does
                            // not understand. Keep it Detected, but do not claim parsing.
                            " Signature matched; the Ramen test-container parser did not " +
                                "validate this layout: ${exception.message ?: "unknown structure"}."
                        }
                    }
                    BinaryResourceType.SCRIPT -> {
                        try {
                            val project = source.openRead(relativePath).use { stream ->
                                ScriptScenarioLoader.load(stream, Charsets.UTF_8, xorKey)
                            }
                            " Parsed ${project.scenes.size} command records into the Ramen Scenario model."
                        } catch (exception: IOException) {
                            // A recognized script magic can belong to a different SPT
                            // dialect. Detection must not promote it to the Ramen Script
                            // v1 launcher unless the complete test format validates.
                            " Signature matched; the Ramen Script v1 parser did not " +
                                "validate this command stream: ${exception.message ?: "unknown structure"}."
                        }
                    }
                    BinaryResourceType.GRAPHIC_ARCHIVE -> ""
                }

                matchedSignature to validation
            }
        } catch (exception: IOException) {
            return malformed(
                exception.message?.take(MAX_FAILURE_REASON_LENGTH)
                    ?: "I/O failure while reading the binary resource.",
                relativePath
            )
        }

        return EngineProbeResult.Match(
            confidence = EngineMatchConfidence.HIGH,
            formatVersion = TEST_FORMAT_VERSION.takeIf {
                isValidatedTestFormat(validationDescription)
            },
            evidence = listOf(
                EngineEvidence(
                    description = "Matched ${signature.label} signature.$validationDescription",
                    relativePath = relativePath,
                    resourceType = signature.resourceType.id
                )
            )
        )
    }

    /**
     * Scans the bounded source snapshot.
     *
     * A directory may contain unrelated files, so the aggregate probe keeps scanning
     * after per-file Malformed results. Recognized entries are returned as a Match;
     * skipped malformed entries are preserved as diagnostic evidence. If no signature
     * matches, the aggregate result is Malformed (unless the source is empty).
     */
    override fun probe(source: EngineSource): EngineProbeResult {
        if (source.entries.isEmpty()) return EngineProbeResult.NoMatch

        val matchedEvidence = mutableListOf<EngineEvidence>()
        val rejectedEvidence = mutableListOf<EngineEvidence>()

        source.entries.forEach { entry ->
            when (val result = probeFile(source, entry.relativePath)) {
                EngineProbeResult.NoMatch -> Unit
                is EngineProbeResult.Match -> matchedEvidence += result.evidence
                is EngineProbeResult.Malformed -> {
                    rejectedEvidence += result.evidence.ifEmpty {
                        listOf(EngineEvidence(result.reason, entry.relativePath))
                    }.map { evidence ->
                        evidence.copy(
                            description = "Rejected candidate: ${evidence.description}",
                            resourceType = null
                        )
                    }
                }
            }
        }

        if (matchedEvidence.isNotEmpty()) {
            return EngineProbeResult.Match(
                confidence = EngineMatchConfidence.HIGH,
                formatVersion = TEST_FORMAT_VERSION.takeIf {
                    matchedEvidence.any { evidence -> isValidatedTestFormat(evidence.description) }
                },
                evidence = matchedEvidence + rejectedEvidence
            )
        }

        val primaryReason = rejectedEvidence.firstOrNull()?.description
            ?: "No file matched the Ramen test binary signatures."
        return EngineProbeResult.Malformed(
            reason = primaryReason.take(MAX_FAILURE_REASON_LENGTH),
            evidence = rejectedEvidence
        )
    }

    private fun matchSignature(header: ByteArray): Pair<Signature, Int>? {
        SIGNATURES.firstOrNull { it.bytes.contentEquals(header) }?.let {
            return it to 0
        }

        val scriptSignature = SIGNATURES.firstOrNull {
            it.resourceType == BinaryResourceType.SCRIPT
        } ?: return null
        val key = (header[0].toInt() and 0xFF) xor
            (scriptSignature.bytes[0].toInt() and 0xFF)
        val decodedHeader = ByteArray(header.size) { index ->
            ((header[index].toInt() and 0xFF) xor key).toByte()
        }
        return if (decodedHeader.contentEquals(scriptSignature.bytes)) {
            scriptSignature to key
        } else {
            null
        }
    }

    private fun isValidatedTestFormat(description: String): Boolean =
        description.contains("Validated Ramen test-container index") ||
            description.contains("Parsed ") &&
            description.contains("Ramen Scenario model")

    private fun malformed(reason: String, relativePath: String): EngineProbeResult.Malformed =
        EngineProbeResult.Malformed(
            reason = reason.take(MAX_FAILURE_REASON_LENGTH),
            evidence = listOf(EngineEvidence(reason.take(MAX_FAILURE_REASON_LENGTH), relativePath))
        )

    private data class Signature(
        val bytes: ByteArray,
        val resourceType: BinaryResourceType,
        val label: String
    )

    private fun ByteArray.toHexString(): String =
        joinToString(" ") { byte -> "%02X".format(byte.toInt() and 0xFF) }

    companion object {
        private const val MAGIC_BYTE_COUNT = 8
        private const val MAX_FAILURE_REASON_LENGTH = 512
        private const val TEST_FORMAT_VERSION = "ramen-test-v1"

        private val SIGNATURES = listOf(
            Signature(
                bytes = byteArrayOf(
                    0xEB.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
                    0xFE.toByte(), 0xFF.toByte(), 0x99.toByte(), 0x99.toByte()
                ),
                resourceType = BinaryResourceType.SCRIPT,
                label = "Script"
            ),
            Signature(
                bytes = "IF PACKT".toByteArray(Charsets.US_ASCII),
                resourceType = BinaryResourceType.RESOURCE_CONTAINER,
                label = "ResourceContainer"
            ),
            Signature(
                bytes = "JPEG    ".toByteArray(Charsets.US_ASCII),
                resourceType = BinaryResourceType.GRAPHIC_ARCHIVE,
                label = "GraphicArchive (JPEG)"
            ),
            Signature(
                bytes = "BMP     ".toByteArray(Charsets.US_ASCII),
                resourceType = BinaryResourceType.GRAPHIC_ARCHIVE,
                label = "GraphicArchive (BMP)"
            )
        )
    }
}
