package io.github.helterfall.ramen.importer

import io.github.helterfall.ramen.inspection.FileRandomAccessSource
import io.github.helterfall.ramen.inspection.LiveMakerArchiveParser
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

        // A structurally valid LiveMaker VF v102 archive is stronger evidence than an EXE
        // extension or an MZ signature. Both ZIP and SAF folder imports reach this detector
        // after copying their contents into Ramen's private game directory.
        val liveMakerArchive = scannedFiles.asSequence()
            .filter { file ->
                file.length() >= MIN_LIVEMAKER_CONTAINER_SIZE &&
                    file.extension.lowercase() in LIVEMAKER_CONTAINER_EXTENSIONS
            }
            .mapNotNull { file ->
                val archive = runCatching {
                    FileRandomAccessSource(file).use { source ->
                        LiveMakerArchiveParser().parse(source)
                    }
                }.getOrNull()
                archive?.let { file to it }
            }
            .firstOrNull()

        if (liveMakerArchive != null) {
            val (file, archive) = liveMakerArchive
            val relativePath = file.relativeTo(gameDirectory).invariantSeparatorsPath
            return GameDetection(
                engineId = "livemaker-vf102",
                engineLabel = "LiveMaker VF v102 (парсер доступен)",
                compatibilityStatus = "PARSER_AVAILABLE",
                evidence = "Validated LiveMaker VF v102 archive in $relativePath at " +
                    "0x${archive.archiveOffset.toString(16)}; " +
                    "${archive.entries.size} directory entries parsed. " +
                    "The LiveMaker playback runtime is not connected yet."
            )
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
                    "${sptScript.textPool.size} Shift-JIS text/resource entries parsed. " +
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
        private const val MIN_LIVEMAKER_CONTAINER_SIZE = 10L
        private val LIVEMAKER_CONTAINER_EXTENSIONS = setOf("exe", "dat", "vf", "ext")
    }
}
