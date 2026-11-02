package io.github.helterfall.ramen.runtime.engine

import io.github.helterfall.ramen.inspection.ReadOnlyRandomAccessSource
import java.io.InputStream

/**
 * Read-only view of one imported game directory.
 *
 * Implementations expose a bounded, immutable entry snapshot. Returned streams and
 * positional sources are owned by the caller and must be closed by that caller.
 */
interface EngineSource {
    val sourceId: String
    val displayName: String
    val entries: List<EngineSourceEntry>
    val isTruncated: Boolean
    val warnings: List<String>

    fun openRead(relativePath: String): InputStream

    fun openRandomAccess(relativePath: String): ReadOnlyRandomAccessSource
}

data class EngineSourceEntry(
    val relativePath: String,
    val sizeBytes: Long
) {
    init {
        validateEngineRelativePath(relativePath)
        require(sizeBytes >= 0L) { "File size must not be negative." }
    }
}

data class EngineAdapterDescriptor(
    val id: String,
    val displayName: String,
    val formatIds: Set<String> = emptySet()
) {
    init {
        require(ENGINE_ID_PATTERN.matches(id)) { "Engine ID is not a stable identifier." }
        require(displayName.isNotBlank()) { "Engine display name must not be blank." }
        require(formatIds.all(ENGINE_ID_PATTERN::matches)) {
            "Format IDs must be stable identifiers."
        }
    }

    companion object {
        private val ENGINE_ID_PATTERN = Regex("^[a-z][a-z0-9._-]{0,63}$")
    }
}

enum class EngineMatchConfidence {
    LOW,
    MEDIUM,
    HIGH
}

data class EngineEvidence(
    val description: String,
    val relativePath: String? = null,
    val resourceType: String? = null
) {
    init {
        require(description.isNotBlank()) { "Evidence description must not be blank." }
        relativePath?.let(::validateEngineRelativePath)
        require(resourceType == null || RESOURCE_TYPE_PATTERN.matches(resourceType)) {
            "Resource type must be a stable identifier."
        }
    }

    private companion object {
        val RESOURCE_TYPE_PATTERN = Regex("^[a-z][a-z0-9._-]{0,63}$")
    }
}

/**
 * A probe must distinguish an ordinary non-match from a damaged candidate.
 * A malformed result must never be silently downgraded to NoMatch.
 */
sealed interface EngineProbeResult {
    data object NoMatch : EngineProbeResult

    data class Match(
        val confidence: EngineMatchConfidence,
        val formatVersion: String? = null,
        val evidence: List<EngineEvidence>
    ) : EngineProbeResult {
        init {
            require(evidence.isNotEmpty()) { "A match must include observable evidence." }
            require(formatVersion == null || formatVersion.isNotBlank()) {
                "Format version must be null or non-blank."
            }
        }
    }

    data class Malformed(
        val reason: String,
        val evidence: List<EngineEvidence> = emptyList()
    ) : EngineProbeResult {
        init {
            require(reason.isNotBlank()) { "Malformed result must explain the failure." }
        }
    }
}

/**
 * Engine-specific discovery and format inspection.
 *
 * This is intentionally not a launcher yet. A runtime factory/session contract will be
 * added only after the target command set, render semantics, and save behavior are known.
 */
interface EngineAdapter {
    val descriptor: EngineAdapterDescriptor

    fun probe(source: EngineSource): EngineProbeResult
}

internal fun validateEngineRelativePath(path: String) {
    require(path.isNotBlank()) { "Relative path must not be blank." }
    require(!path.startsWith("/") && !path.contains('\\')) {
        "Path must use relative, slash-separated notation."
    }
    require(!Regex("^[A-Za-z]:").containsMatchIn(path)) {
        "Drive-qualified paths are not allowed."
    }
    require(path.none { it.code < 0x20 }) { "Control characters are not allowed in paths." }

    val segments = path.split('/')
    require(segments.all { it.isNotBlank() && it != "." && it != ".." }) {
        "Path contains an empty or traversal segment."
    }
}
