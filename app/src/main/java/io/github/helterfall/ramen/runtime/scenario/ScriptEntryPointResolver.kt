package io.github.helterfall.ramen.runtime.scenario

import io.github.helterfall.ramen.runtime.engine.EngineSource
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import io.github.helterfall.ramen.runtime.engine.EngineSourceEntry
import java.io.IOException
import java.util.Locale

sealed interface ScriptEntryPointLoadResult {
    data class Ready(
        val entry: EngineSourceEntry,
        val project: ScenarioProject
    ) : ScriptEntryPointLoadResult

    data class Malformed(
        val failures: List<ScriptEntryPointFailure>,
        val candidatesInspected: Int
    ) : ScriptEntryPointLoadResult

    data object NotFound : ScriptEntryPointLoadResult
}

data class ScriptEntryPointFailure(
    val relativePath: String,
    val reason: String
)

/**
 * Finds and fully validates a Ramen Script v1 startup file.
 *
 * .spt is an ordering hint, not proof of format. Candidate scripts are passed through
 * ScriptScenarioLoader in priority order; malformed candidates (including unknown
 * opcodes) are recorded and skipped. Non-.spt files are considered only when their
 * bytes match the exact Ramen Script v1 signature.
 */
class ScriptEntryPointResolver {
    fun select(source: EngineSource): EngineSourceEntry? =
        orderedCandidates(source).firstOrNull()

    fun load(source: EngineSource): ScriptEntryPointLoadResult {
        val candidates = orderedCandidates(source)
        if (candidates.isEmpty()) return ScriptEntryPointLoadResult.NotFound

        val inspected = candidates.take(MAX_CANDIDATES)
        val failures = mutableListOf<ScriptEntryPointFailure>()

        for (entry in inspected) {
            try {
                val project = source.openRead(entry.relativePath).use(::loadWithDetectedXorKey)
                return ScriptEntryPointLoadResult.Ready(entry, project)
            } catch (exception: IOException) {
                failures += ScriptEntryPointFailure(
                    relativePath = entry.relativePath,
                    reason = (exception.message ?: "Malformed Script v1 candidate.")
                        .take(MAX_FAILURE_LENGTH)
                )
            }
        }

        if (candidates.size > MAX_CANDIDATES) {
            failures += ScriptEntryPointFailure(
                relativePath = "<scan>",
                reason = "Startup candidate limit reached ($MAX_CANDIDATES)."
            )
        }

        return ScriptEntryPointLoadResult.Malformed(
            failures = failures.take(MAX_REPORTED_FAILURES),
            candidatesInspected = inspected.size
        )
    }

    private fun orderedCandidates(source: EngineSource): List<EngineSourceEntry> {
        val sptEntries = source.entries
            .filter {
                it.relativePath.substringAfterLast('/')
                    .endsWith(".spt", ignoreCase = true)
            }
            .sortedWith(
                compareBy<EngineSourceEntry> { priority(it.relativePath) }
                    .thenBy { it.relativePath.count { char -> char == '/' } }
                    .thenBy { it.relativePath.lowercase(Locale.ROOT) }
            )

        val sptPaths = sptEntries.mapTo(mutableSetOf()) { it.relativePath }
        val magicEntries = source.entries.asSequence()
            .filter { it.relativePath !in sptPaths && it.sizeBytes >= MAGIC.size }
            .sortedBy { it.relativePath.lowercase(Locale.ROOT) }
            .filter { entry ->
                runCatching { hasScriptMagic(source, entry) }.getOrDefault(false)
            }
            .toList()

        return sptEntries + magicEntries
    }

    private fun priority(relativePath: String): Int =
        when (relativePath.substringAfterLast('/').lowercase(Locale.ROOT)) {
            "01.spt" -> 0
            "00.spt" -> 1
            "init.spt", "initialize.spt", "startup.spt" -> 2
            "start.spt", "main.spt" -> 3
            else -> 4
        }

    private fun loadWithDetectedXorKey(stream: InputStream): ScenarioProject {
        val firstByte = stream.read()
        if (firstByte < 0) {
            throw ScriptParseException(0L, "Script file is empty.")
        }

        // The known plaintext signature begins with 0xEB. Deriving the mask from
        // the observed first byte also keeps unmasked legacy scripts compatible.
        val xorKey = firstByte xor (MAGIC[0].toInt() and 0xFF)
        val replay = SequenceInputStream(
            ByteArrayInputStream(byteArrayOf(firstByte.toByte())),
            stream
        )
        return ScriptScenarioLoader.load(replay, java.nio.charset.StandardCharsets.UTF_8, xorKey)
    }

    private fun hasScriptMagic(source: EngineSource, entry: EngineSourceEntry): Boolean =
        source.openRandomAccess(entry.relativePath).use { randomAccess ->
            if (randomAccess.sizeBytes < MAGIC.size) {
                false
            } else {
                val header = ByteArray(MAGIC.size)
                randomAccess.readFullyAt(0L, header)
                val xorKey = (header[0].toInt() and 0xFF) xor (MAGIC[0].toInt() and 0xFF)
                header.indices.all { index ->
                    ((header[index].toInt() and 0xFF) xor xorKey) ==
                        (MAGIC[index].toInt() and 0xFF)
                }
            }
        }

    private companion object {
        private const val MAX_CANDIDATES = 64
        private const val MAX_REPORTED_FAILURES = 12
        private const val MAX_FAILURE_LENGTH = 384
        private val MAGIC = byteArrayOf(
            0xEB.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFE.toByte(), 0xFF.toByte(), 0x99.toByte(), 0x99.toByte()
        )
    }
}
