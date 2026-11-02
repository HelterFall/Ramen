package io.github.helterfall.ramen.runtime.engine

import java.io.IOException

data class EngineProbeMatch(
    val descriptor: EngineAdapterDescriptor,
    val confidence: EngineMatchConfidence,
    val formatVersion: String?,
    val evidence: List<EngineEvidence>
)

data class EngineProbeFailure(
    val descriptor: EngineAdapterDescriptor,
    val reason: String,
    val evidence: List<EngineEvidence>
)

/**
 * Aggregates independent adapters without assigning arbitrary priority to one engine.
 *
 * A single match is selectable. Multiple matches remain ambiguous until a stronger,
 * sample-backed discriminator is implemented. Malformed candidates remain visible.
 */
data class EngineProbeSummary(
    val matches: List<EngineProbeMatch>,
    val malformed: List<EngineProbeFailure>
) {
    val uniqueMatch: EngineProbeMatch?
        get() = matches.singleOrNull()

    val isAmbiguous: Boolean
        get() = matches.size > 1

    val hasNoMatch: Boolean
        get() = matches.isEmpty() && malformed.isEmpty()
}

class EngineAdapterRegistry(adapters: Collection<EngineAdapter>) {
    /**
     * Stable ordering makes diagnostics and tests reproducible even when callers pass
     * an unordered collection.
     */
    private val adapters = adapters.sortedBy { it.descriptor.id }

    init {
        val ids = this.adapters.map { it.descriptor.id }
        require(ids.distinct().size == ids.size) {
            "Engine adapter IDs must be unique."
        }
    }

    fun probe(source: EngineSource): EngineProbeSummary {
        val matches = mutableListOf<EngineProbeMatch>()
        val malformed = mutableListOf<EngineProbeFailure>()

        adapters.forEach { adapter ->
            val result = try {
                adapter.probe(source)
            } catch (exception: IOException) {
                EngineProbeResult.Malformed(
                    reason = exception.message
                        ?.trim()
                        ?.take(MAX_FAILURE_REASON_LENGTH)
                        ?.takeIf { it.isNotEmpty() }
                        ?: DEFAULT_IO_FAILURE_REASON
                )
            }

            when (result) {
                EngineProbeResult.NoMatch -> Unit
                is EngineProbeResult.Match -> matches += EngineProbeMatch(
                    descriptor = adapter.descriptor,
                    confidence = result.confidence,
                    formatVersion = result.formatVersion,
                    evidence = result.evidence.toList()
                )
                is EngineProbeResult.Malformed -> malformed += EngineProbeFailure(
                    descriptor = adapter.descriptor,
                    reason = result.reason,
                    evidence = result.evidence.toList()
                )
            }
        }

        return EngineProbeSummary(
            matches = matches.toList(),
            malformed = malformed.toList()
        )
    }

    private companion object {
        const val MAX_FAILURE_REASON_LENGTH = 512
        const val DEFAULT_IO_FAILURE_REASON = "I/O error while probing this engine candidate."
    }
}
