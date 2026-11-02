package io.github.helterfall.ramen.inspection

enum class FormatEvidenceLevel {
    VERIFIED_SIGNATURE,
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
 * An extension-only observation is never promoted to a verified format until a byte-level
 * signature or a documented parser establishes what the file actually contains.
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

    /**
     * These pairs were visible in a user-provided directory listing. Their semantics,
     * internal layout, compression, and relationship are not yet verified from file bytes.
     */
    val observedUnverified: List<ContainerFormatDefinition> = listOf(
        ContainerFormatDefinition(
            id = "observed-gpk-gtb",
            label = "Unverified .gpk / .gtb file family",
            extensions = setOf("gpk", "gtb"),
            signatureHex = null,
            evidenceLevel = FormatEvidenceLevel.OBSERVED_EXTENSION_ONLY,
            notes = "Paired extensions appeared in the same game-data directory listing. " +
                "No binary samples have been inspected; do not infer that one file indexes the other."
        ),
        ContainerFormatDefinition(
            id = "observed-vpk-vtb",
            label = "Unverified .vpk / .vtb file family",
            extensions = setOf("vpk", "vtb"),
            signatureHex = null,
            evidenceLevel = FormatEvidenceLevel.OBSERVED_EXTENSION_ONLY,
            notes = "Paired extensions appeared in the same game-data directory listing. " +
                "Container semantics and pairing behavior are unknown."
        ),
        ContainerFormatDefinition(
            id = "observed-xtx",
            label = "Unverified .xtx files",
            extensions = setOf("xtx"),
            signatureHex = null,
            evidenceLevel = FormatEvidenceLevel.OBSERVED_EXTENSION_ONLY,
            notes = "Several .xtx filenames appeared in an initialization directory. " +
                "Their role as scripts, configuration, indexes, or another format is unverified."
        )
    )

    fun findVerifiedByExtension(extension: String): List<ContainerFormatDefinition> {
        val normalized = extension.removePrefix(".").lowercase()
        return verified.filter { normalized in it.extensions }
    }

    fun findObservedByExtension(extension: String): List<ContainerFormatDefinition> {
        val normalized = extension.removePrefix(".").lowercase()
        return observedUnverified.filter { normalized in it.extensions }
    }
}
