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

        val protectedNscripterMarker = files.firstOrNull { path ->
            path == "nscr_sec.dat" || path == "nscript.___" ||
                path == "onscript.nt2" || path == "onscript.nt3" ||
                path == "arc.___" || ENCRYPTED_NSA_PATTERN.matches(path)
        }
        if (protectedNscripterMarker != null &&
            "0.utf" !in files && "00.utf" !in files &&
            "0.txt" !in files && "00.txt" !in files && "nscript.dat" !in files
        ) {
            return GameDetection(
                engineId = "nscripter",
                engineLabel = "NScripter / ONScripter (обнаружен защищённый формат)",
                compatibilityStatus = "DETECTED_UNSUPPORTED",
                evidence = "Found '$protectedNscripterMarker'; its encryption/archive variant " +
                    "is recognized but not decoded by the current scanner."
            )
        }

        val nscripterMarker = files.firstOrNull { path ->
            if (path.contains('/')) {
                false
            } else {
                path == "nscript.dat" ||
                    path == "0.utf" ||
                    path == "00.utf" ||
                    UTF_NUMBERED_SCRIPT_PATTERN.matches(path) ||
                    path == "0.txt" ||
                    path == "00.txt" ||
                    NUMBERED_SCRIPT_PATTERN.matches(path) ||
                    path == "arc.nsa" ||
                    path == "arc.sar" ||
                    EXTRA_NSA_PATTERN.matches(path)
            }
        }
        if (nscripterMarker != null) {
            return GameDetection(
                engineId = "nscripter",
                engineLabel = "NScripter / ONScripter (статический сканер доступен)",
                compatibilityStatus = "PARSER_AVAILABLE",
                evidence = "Found NScripter marker '$nscripterMarker'. Script decoding and " +
                    "NSA/SAR index auditing are available; no playback renderer is connected."
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
        private val NUMBERED_SCRIPT_PATTERN = Regex("^(?:[1-9]|[1-9][0-9]|0[1-9])\\.txt$")
        private val UTF_NUMBERED_SCRIPT_PATTERN = Regex("^(?:[1-9]|[1-9][0-9]|0[1-9])\\.utf$")
        private val EXTRA_NSA_PATTERN = Regex("^arc[1-9]\\.nsa$")
        private val ENCRYPTED_NSA_PATTERN = Regex("^arc[1-9]\\.___$")
    }
}