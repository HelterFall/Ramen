package io.github.helterfall.ramen.inspection

import java.io.IOException

enum class ContainerProbeStatus {
    MATCH,
    NO_MATCH,
    MALFORMED
}

data class ContainerProbeResult(
    val formatId: String,
    val status: ContainerProbeStatus,
    val evidence: String,
    val signatureHex: String? = null
)

/**
 * A read-only parser probe for a candidate binary/container format.
 *
 * Probes must validate all offsets and declared sizes before reading or allocating memory.
 * A probe must never close the source; the caller owns its lifetime.
 */
interface ContainerProbe {
    val formatId: String

    @Throws(IOException::class)
    fun probe(source: ReadOnlyRandomAccessSource): ContainerProbeResult
}

/**
 * Minimal byte-signature probe used for formats whose current evidence is a fixed magic value.
 * It intentionally identifies only the signature family, not the complete container structure.
 */
class FixedSignatureProbe(
    override val formatId: String,
    signature: ByteArray,
    private val offset: Long = 0L
) : ContainerProbe {
    private val expectedSignature = signature.copyOf()

    init {
        require(formatId.isNotBlank())
        require(expectedSignature.isNotEmpty())
        require(offset >= 0)
        require(offset <= Long.MAX_VALUE - expectedSignature.size)
    }

    override fun probe(source: ReadOnlyRandomAccessSource): ContainerProbeResult {
        val endOffset = offset + expectedSignature.size
        if (endOffset > source.sizeBytes) {
            return ContainerProbeResult(
                formatId = formatId,
                status = ContainerProbeStatus.MALFORMED,
                evidence = "Source is shorter than the required signature at offset $offset."
            )
        }

        val actual = ByteArray(expectedSignature.size)
        return try {
            source.readFullyAt(offset, actual)
            if (actual.contentEquals(expectedSignature)) {
                ContainerProbeResult(
                    formatId = formatId,
                    status = ContainerProbeStatus.MATCH,
                    evidence = "Expected byte signature found at offset $offset.",
                    signatureHex = expectedSignature.toHexString()
                )
            } else {
                ContainerProbeResult(
                    formatId = formatId,
                    status = ContainerProbeStatus.NO_MATCH,
                    evidence = "Byte signature did not match at offset $offset.",
                    signatureHex = actual.toHexString()
                )
            }
        } catch (error: IOException) {
            ContainerProbeResult(
                formatId = formatId,
                status = ContainerProbeStatus.MALFORMED,
                evidence = "Could not read the required signature: ${error.message ?: "I/O error"}."
            )
        }
    }

    private fun ByteArray.toHexString(): String = joinToString(" ") { byte ->
        "%02X".format(byte.toInt() and 0xFF)
    }
}

/**
 * Runs independent probes against one already-open source.
 * Each result is retained, including NO_MATCH, so callers can explain the evidence.
 */
class ContainerProbeRunner(
    private val probes: List<ContainerProbe>
) {
    init {
        require(probes.map { it.formatId }.distinct().size == probes.size) {
            "Container probe format IDs must be unique."
        }
    }

    fun run(source: ReadOnlyRandomAccessSource): List<ContainerProbeResult> =
        probes.map { probe ->
            try {
                probe.probe(source)
            } catch (error: Exception) {
                ContainerProbeResult(
                    formatId = probe.formatId,
                    status = ContainerProbeStatus.MALFORMED,
                    evidence = "Probe failed safely: ${error.message ?: error::class.java.simpleName}."
                )
            }
        }
}
