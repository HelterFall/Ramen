package io.github.helterfall.ramen.inspection

import java.util.Locale

enum class FormatEvidenceLevel {
    VERIFIED_SIGNATURE,
    STRUCTURE_VALIDATED,
    PARSER_IMPLEMENTED,
    OBSERVED_EXTENSION_ONLY
}

data class ContainerFormatDefinition(
    val id: String,
    val label: String,
    val extensions: Set<String>,
    val signatureHex: String?,
    val evidenceLevel: FormatEvidenceLevel,
    val notes: String
)

/**
 * Central catalogue for container-format knowledge.
 *
 * Signature recognition, parser availability, and runtime compatibility are separate
 * evidence levels. A parser entry does not imply that Ramen can play a game using it.
 */
object ContainerFormatRegistry {
    val verified: List<ContainerFormatDefinition> = listOf(
        ContainerFormatDefinition(
            id = "zip",
            label = "ZIP archive",
            extensions = setOf("zip"),
            signatureHex = "50 4B 03 04 / 50 4B 05 06 / 50 4B 07 08",
            evidenceLevel = FormatEvidenceLevel.VERIFIED_SIGNATURE,
            notes = "Recognized by ZIP local-file, empty-archive, or spanning signatures."
        ),
        ContainerFormatDefinition(
            id = "ogg",
            label = "Ogg container",
            extensions = setOf("ogg", "oga", "ogv"),
            signatureHex = "4F 67 67 53",
            evidenceLevel = FormatEvidenceLevel.VERIFIED_SIGNATURE,
            notes = "Recognized by the OggS capture pattern."
        ),
        ContainerFormatDefinition(
            id = "riff",
            label = "RIFF container",
            extensions = setOf("wav", "avi", "webp"),
            signatureHex = "52 49 46 46",
            evidenceLevel = FormatEvidenceLevel.VERIFIED_SIGNATURE,
            notes = "Recognized by the RIFF marker; the subtype must be inspected separately."
        )
    )

    val parsersImplemented: List<ContainerFormatDefinition> = listOf(
        ContainerFormatDefinition(
            id = "gpk-gtb",
            label = "GPK / GTB image resources",
            extensions = setOf("gpk", "gtb"),
            signatureHex = null,
            evidenceLevel = FormatEvidenceLevel.PARSER_IMPLEMENTED,
            notes = "Bounded paired-index parser validates the little-endian GTB tables and " +
                "64-byte GPK block headers for JPEG and BMP payloads. It returns payload ranges; " +
                "it does not decode images or establish game-runtime compatibility."
        ),
        ContainerFormatDefinition(
            id = "vpk-vtb",
            label = "VPK / VTB audio resources",
            extensions = setOf("vpk", "vtb"),
            signatureHex = null,
            evidenceLevel = FormatEvidenceLevel.PARSER_IMPLEMENTED,
            notes = "Bounded paired-index parser validates fixed 12-byte VTB records, VPK audio " +
                "preambles, PCM-style descriptors, and Ogg page boundaries. Ogg page CRCs and " +
                "audio decoding are not performed."
        )
    )

    /**
     * A single supplied .xtx sample is a CRLF-delimited text key/value configuration.
     * This observation is deliberately not generalized to every .xtx file.
     */
    val observedUnverified: List<ContainerFormatDefinition> = listOf(
        ContainerFormatDefinition(
            id = "observed-xtx",
            label = "Observed .xtx text configuration sample",
            extensions = setOf("xtx"),
            signatureHex = null,
            evidenceLevel = FormatEvidenceLevel.OBSERVED_EXTENSION_ONLY,
            notes = "One supplied codecerror.xtx sample is readable CRLF-delimited key/value text. " +
                "Its semantics and whether other .xtx files share this syntax are unverified."
        )
    )

    fun findVerifiedByExtension(extension: String): List<ContainerFormatDefinition> =
        verified.filter { extension.removePrefix(".").lowercase(Locale.ROOT) in it.extensions }

    fun findParsedByExtension(extension: String): List<ContainerFormatDefinition> =
        parsersImplemented.filter { extension.removePrefix(".").lowercase(Locale.ROOT) in it.extensions }

    fun findObservedByExtension(extension: String): List<ContainerFormatDefinition> =
        observedUnverified.filter { extension.removePrefix(".").lowercase(Locale.ROOT) in it.extensions }
}
