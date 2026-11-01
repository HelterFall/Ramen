package io.github.helterfall.ramen.importer

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

        val indexHtml = files.firstOrNull { path ->
            path == "index.html" ||
                (path.endsWith("/index.html") && path.count { it == '/' } <= 2)
        }
        if (indexHtml != null) {
            val tyranoMarker = files.firstOrNull { path ->
                path.endsWith("/tyrano.js") ||
                    path == "tyrano.js" ||
                    path.endsWith("/tyrano/tyrano.js")
            }

            return unsupported(
                engineId = if (tyranoMarker != null) "tyranoscript" else "html5",
                engineLabel = if (tyranoMarker != null) "TyranoScript / HTML5" else "HTML5",
                evidence = if (tyranoMarker != null) {
                    "Found $indexHtml and $tyranoMarker"
                } else {
                    "Found an HTML entry point: $indexHtml"
                }
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
