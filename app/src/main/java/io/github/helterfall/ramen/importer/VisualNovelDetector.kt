package io.github.helterfall.ramen.importer

import io.github.helterfall.ramen.parser.SystemNnnScriptLoader
import java.io.File

data class GameDetection(
    val engineId: String,
    val engineLabel: String,
    val compatibilityStatus: String,
    val evidence: String
)

class VisualNovelDetector {
    fun detect(gameDirectory: File): GameDetection {
        if (!gameDirectory.isDirectory) {
            return unknown("The imported directory is unavailable.")
        }

        val scannedFiles = gameDirectory
            .walkTopDown()
            .maxDepth(MAX_SCAN_DEPTH)
            .filter { it.isFile }
            .take(MAX_SCANNED_FILES)
            .toList()

        val files = scannedFiles.map { file ->
            file.relativeTo(gameDirectory).invariantSeparatorsPath.lowercase()
        }

        // Parse valid SPT candidates before assigning a parser-available status.
        // A signature alone is used for fast filtering; load() validates bounds.
        val sptScript = scannedFiles.asSequence()
            .filter { file ->
                file.extension.equals("spt", ignoreCase = true) &&
                    SystemNnnScriptLoader.hasValidHeader(file)
            }
            .mapNotNull { file ->
                runCatching { SystemNnnScriptLoader().load(file) }.getOrNull()
            }
            .firstOrNull()

        if (sptScript != null) {
            return GameDetection(
                engineId = "system-nnn-spt",
                engineLabel = "System-NNN SPT (парсер доступен)",
                compatibilityStatus = "PARSER_AVAILABLE",
                evidence = "Validated XOR-masked SPTHEADER[0-9] at 0x30; " +
                    "${sptScript.offsetTables.size} table descriptors, " +
                    "${sptScript.commandByteCount} raw command-region bytes and " +
                    "${sptScript.allTextEntries.size} records across both indexed pools parsed. " +
                    "The playback interpreter is not connected yet."
            )
        }

        val renpyScript = files.firstOrNull { path ->
            path.endsWith(".rpy") &&
                (path.startsWith("game/") || path.contains("/game/"))
        }
        if (renpyScript != null) {
            return unsupported(
                engineId = "renpy",
                engineLabel = "Ren'Py",
                evidence = "Found a Ren'Py script: $renpyScript"
            )
        }

        val kirikiriMarker = files.firstOrNull { path ->
            path.endsWith(".xp3") ||
                path.endsWith("/startup.tjs") ||
                path == "startup.tjs" ||
                path.endsWith("/default.tjs") ||
                path == "default.tjs"
        }
        if (kirikiriMarker != null) {
            return unsupported(
                engineId = "kirikiri",
                engineLabel = "KiriKiri",
                evidence = "Found a TJS or XP3 marker: $kirikiriMarker"
            )
        }

        val indexHtml = files.firstOrNull { it == "index.html" }
        if (indexHtml != null) {
            val tyranoMarker = files.firstOrNull { path ->
                path.endsWith("/tyrano.js") ||
                    path == "tyrano.js" ||
                    path.endsWith("/tyrano/tyrano.js")
            }

            if (tyranoMarker != null) {
                return unsupported(
                    engineId = "tyranoscript",
                    engineLabel = "TyranoScript / HTML5",
                    evidence = "Found $indexHtml and $tyranoMarker"
                )
            }

            return GameDetection(
                engineId = "html5",
                engineLabel = "HTML5 (ограниченная поддержка)",
                compatibilityStatus = "SUPPORTED_LIMITED",
                evidence = "Found the root HTML entry point: index.html"
            )
        }

        return unknown("No known visual-novel engine markers were found.")
    }

    private fun unsupported(
        engineId: String,
        engineLabel: String,
        evidence: String
    ) = GameDetection(
        engineId = engineId,
        engineLabel = engineLabel,
        compatibilityStatus = "DETECTED_UNSUPPORTED",
        evidence = evidence
    )

    private fun unknown(evidence: String) = GameDetection(
        engineId = "unknown",
        engineLabel = "Неизвестный движок",
        compatibilityStatus = "UNKNOWN",
        evidence = evidence
    )

    companion object {
        private const val MAX_SCAN_DEPTH = 4
        private const val MAX_SCANNED_FILES = 10_000
    }
}
