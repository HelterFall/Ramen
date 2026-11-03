package io.github.helterfall.ramen.importer

import io.github.helterfall.ramen.inspection.BlackCycScriptLoader
import java.io.File

data class GameDetection(
    val engineId: String,
    val engineLabel: String,
    val compatibilityStatus: String,
    val evidence: String
)

class VisualNovelDetector(
    private val blackCycScriptLoader: BlackCycScriptLoader = BlackCycScriptLoader()
) {
    fun detect(gameDirectory: File): GameDetection {
        if (!gameDirectory.isDirectory) {
            return unknown("The imported directory is unavailable.")
        }

        val files = gameDirectory
            .walkTopDown()
            .maxDepth(MAX_SCAN_DEPTH)
            .filter { it.isFile }
            .take(MAX_SCANNED_FILES)
            .map { file ->
                file.relativeTo(gameDirectory).invariantSeparatorsPath.lowercase()
            }
            .toList()

        val renpyScript = files.firstOrNull { path ->
            path.endsWith(".rpy") &&
                (path.startsWith("game/") || path.contains("/game/"))
        }
        if (renpyScript != null) {
            return unsupported(
                engineId = "renpy",
                engineLabel = "Ren'Py",
                evidence = "Found a Ren'Py script: " + renpyScript
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
                evidence = "Found a TJS or XP3 marker: " + kirikiriMarker
            )
        }

        // Black Cyc GPK indexes the matching GPK data file by basename.
        // Treat .spt as opaque only when this archive pair is present.
        val gpkBases = files
            .filter { it.endsWith(".gpk") }
            .map { it.removeSuffix(".gpk") }
            .toSet()

        val gtbBases = files
            .filter { it.endsWith(".gtb") }
            .map { it.removeSuffix(".gtb") }
            .toSet()

        val hasMatchingGpkGtbPair =
            gpkBases.intersect(gtbBases).isNotEmpty()

        if (hasMatchingGpkGtbPair) {
            val blackCycScript = files.firstNotNullOfOrNull { path ->
                blackCycScriptLoader.inspect(
                    entryName = path,
                    hasMatchingGpkGtbPair = true
                )
            }

            if (blackCycScript != null &&
                blackCycScriptLoader.shouldBypassCommandParser(blackCycScript)
            ) {
                return GameDetection(
                    engineId = "blackcyc",
                    engineLabel = "Black Cyc",
                    compatibilityStatus = "DETECTED_RESOURCE_PREVIEW",
                    evidence =
                        "Found a .spt entry and a matching-basename GPK/GTB archive pair. " +
                        "The SPT entry is classified as opaque; application bytecode parsing " +
                        "is intentionally skipped. Resource preview is available only for " +
                        "implemented resource formats."
                )
            }
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
                    evidence = "Found " + indexHtml + " and " + tyranoMarker
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
