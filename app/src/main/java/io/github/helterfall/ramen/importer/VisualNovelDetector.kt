package io.github.helterfall.ramen.importer

import io.github.helterfall.ramen.runtime.engine.BinarySignatureProbe
import io.github.helterfall.ramen.runtime.engine.DirectoryGameSource
import io.github.helterfall.ramen.runtime.engine.EngineAdapterRegistry
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

        detectRamenBinaryEngine(gameDirectory)?.let { return it }

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

    /**
     * Runs the bounded binary signature probe against an immutable imported-folder
     * snapshot. Original EXE/DLL files and other unrelated binaries remain per-file
     * diagnostics; they do not cancel a positive script/container marker elsewhere.
     */
    private fun detectRamenBinaryEngine(gameDirectory: File): GameDetection? = runCatching {
        val source = DirectoryGameSource(
            rootDirectory = gameDirectory,
            sourceId = "library-detection",
            maxFiles = MAX_SCANNED_FILES,
            maxDepth = MAX_SCAN_DEPTH
        )
        val summary = EngineAdapterRegistry(listOf(BinarySignatureProbe())).probe(source)
        val evidence = summary.matches
            .asSequence()
            .flatMap { it.evidence.asSequence() }
            .filter { it.resourceType == "script" || it.resourceType == "resource-container" }
            .toList()
        if (evidence.isEmpty()) {
            null
        } else {
            val details = evidence.take(MAX_BINARY_EVIDENCE_ITEMS).joinToString("; ") { item ->
                (item.relativePath ?: "binary resource") + ": " + item.description
            }
            val hasValidatedScript = evidence.any { item ->
                item.resourceType == "script" &&
                    item.description.contains("Parsed") &&
                    item.description.contains("Ramen Scenario model")
            }
            GameDetection(
                // Keep the experimental test player gated behind full Script v1
                // validation. A shared/recognized magic prefix alone is Detected,
                // not proof that the proprietary scenario dialect is executable.
                engineId = if (hasValidatedScript) BINARY_SCRIPT_ENGINE_ID else BINARY_ENGINE_ID,
                engineLabel = "Обнаружен кастомный движок v1",
                compatibilityStatus = "DETECTED_UNSUPPORTED",
                evidence = "BinarySignatureProbe: $details"
            )
        }
    }.getOrNull()

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
        private const val MAX_BINARY_EVIDENCE_ITEMS = 8
        private const val BINARY_ENGINE_ID = "ramen-binary-signature"
        private const val BINARY_SCRIPT_ENGINE_ID = "ramen-script-v1"
    }
}
