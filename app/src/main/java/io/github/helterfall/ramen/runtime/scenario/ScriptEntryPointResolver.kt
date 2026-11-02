package io.github.helterfall.ramen.runtime.scenario

import io.github.helterfall.ramen.runtime.engine.EngineSource
import io.github.helterfall.ramen.runtime.engine.EngineSourceEntry

/**
 * Selects one Ramen Script v1 entry point from a bounded source snapshot.
 * Conventional SPT names are preferred; otherwise the exact Script v1 magic is checked.
 */
class ScriptEntryPointResolver {
    fun select(source: EngineSource): EngineSourceEntry? {
        val scriptFiles = source.entries.filter {
            it.relativePath.substringAfterLast('/').endsWith(".spt", ignoreCase = true)
        }
        if (scriptFiles.isNotEmpty()) {
            return scriptFiles.minWithOrNull(
                compareBy<EngineSourceEntry> { priority(it.relativePath) }
                    .thenBy { it.relativePath.count { char -> char == '/' } }
                    .thenBy { it.relativePath.lowercase() }
            )
        }

        return source.entries
            .asSequence()
            .filter { it.sizeBytes >= MAGIC.size }
            .sortedBy { it.relativePath.lowercase() }
            .firstOrNull { entry -> hasScriptMagic(source, entry) }
    }

    private fun priority(relativePath: String): Int =
        when (relativePath.substringAfterLast('/').lowercase()) {
            "01.spt" -> 0
            "00.spt" -> 1
            "init.spt", "initialize.spt", "startup.spt" -> 2
            "start.spt", "main.spt" -> 3
            else -> 4
        }

    private fun hasScriptMagic(source: EngineSource, entry: EngineSourceEntry): Boolean =
        source.openRandomAccess(entry.relativePath).use { randomAccess ->
            if (randomAccess.sizeBytes < MAGIC.size) {
                false
            } else {
                val header = ByteArray(MAGIC.size)
                randomAccess.readFullyAt(0L, header)
                header.contentEquals(MAGIC)
            }
        }

    private companion object {
        private val MAGIC = byteArrayOf(
            0xEB.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFE.toByte(), 0xFF.toByte(), 0x99.toByte(), 0x99.toByte()
        )
    }
}
