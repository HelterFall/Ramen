package io.github.helterfall.ramen.importer

import io.github.helterfall.ramen.parser.SystemNnnScriptLoader
import java.io.File
import java.util.Locale

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
            file.relativeTo(gameDirectory).invariantSeparatorsPath.lowercase(Locale.ROOT)
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
                    sptScript.offsetTables.size + " table descriptors, " +
                    sptScript.commandByteCount + " raw command-region bytes and " +
                    sptScript.textPool.size + " Shift-JIS text/resource entries parsed. " +
                    "The playback interpreter is not connected yet."
            )
        }

        val selectedGameDirectory = gameDirectory.name.equals("game", ignoreCase = true)
        val renpyScript = scannedFiles.firstOrNull { file ->
            val relativePath = file.relativeTo(gameDirectory).invariantSeparatorsPath
                .lowercase(Locale.ROOT)
            isRenPyGamePayload(relativePath, selectedGameDirectory) &&
                hasRenPyScriptMarker(file)
        }

        if (renpyScript != null) {
            val relativePath = renpyScript.relativeTo(gameDirectory).invariantSeparatorsPath
            return unsupported(
                engineId = "renpy",
                engineLabel = "Ren'Py (runtime not installed)",
                evidence = "Validated a Ren'Py script marker in the game payload: $relativePath. " +
                    "Engine recognition does not mean playback support; no Ren'Py runtime is bundled."
            )
        }

        val renpyArchive = scannedFiles.asSequence()
            .filter { file ->
                val relativePath = file.relativeTo(gameDirectory).invariantSeparatorsPath
                    .lowercase(Locale.ROOT)
                isRenPyGamePayload(relativePath, selectedGameDirectory) &&
                    file.extension.equals("rpa", ignoreCase = true)
            }
            .mapNotNull { file ->
                val format = detectRpaHeader(file) ?: return@mapNotNull null
                file to format
            }
            .firstOrNull()

        if (renpyArchive != null) {
            val relativePath = renpyArchive.first.relativeTo(gameDirectory).invariantSeparatorsPath
            return unsupported(
                engineId = "renpy",
                engineLabel = "Ren'Py (RPA-архив без сценария)",
                evidence = "Recognized " + renpyArchive.second +
                    " resource archive in the game payload: $relativePath. " +
                    "No standalone .rpy/.rpyc script file was found in game/. " +
                    "An RPA resource archive alone is not a complete runnable game."
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

    private fun isRenPyGamePayload(relativePath: String, selectedGameDirectory: Boolean): Boolean {
        return selectedGameDirectory ||
            !relativePath.contains('/') ||
            relativePath.startsWith("game/") ||
            relativePath.contains("/game/")
    }

    private fun hasRenPyScriptMarker(file: File): Boolean {
        return when (file.extension.lowercase(Locale.ROOT)) {
            "rpy", "rpym" -> hasRenPySourceSyntax(file)
            "rpyc", "rpymc" -> hasCompiledRenPyHeader(file)
            else -> file.name.endsWith("_ren.py", ignoreCase = true) &&
                hasRenPyPythonBlock(file)
        }
    }

    /**
     * Checks a bounded source prefix for Ren'Py statements instead of treating
     * an arbitrary file with a .rpy extension as a confirmed engine marker.
     */
    private fun hasRenPySourceSyntax(file: File): Boolean {
        val prefix = readPrefix(file, MAX_SOURCE_PROBE_BYTES) ?: return false
        return RENPY_SOURCE_MARKER_REGEX.containsMatchIn(String(prefix, Charsets.UTF_8))
    }

    private fun hasRenPyPythonBlock(file: File): Boolean {
        val prefix = readPrefix(file, MAX_SOURCE_PROBE_BYTES) ?: return false
        return RENPY_PYTHON_BLOCK_REGEX.containsMatchIn(String(prefix, Charsets.UTF_8))
    }

    /**
     * Current RPYC2 files start with "RENPY RPC2". Older releases use a zlib
     * stream; this checks only the RFC 1950 header and does not deserialize data.
     */
    private fun hasCompiledRenPyHeader(file: File): Boolean {
        val prefix = readPrefix(file, RPYC2_MAGIC.size) ?: return false
        if (prefix.contentEquals(RPYC2_MAGIC)) return true
        if (prefix.size < 2) return false

        val cmf = prefix[0].toInt() and 0xFF
        val flg = prefix[1].toInt() and 0xFF
        return (cmf and 0x0F) == 8 &&
            (cmf ushr 4) <= 7 &&
            ((cmf shl 8) + flg) % 31 == 0
    }

    private fun detectRpaHeader(file: File): String? {
        val prefix = readPrefix(file, RPA_HEADER_LENGTH) ?: return null
        val marker = String(prefix, Charsets.US_ASCII)
        return when (marker) {
            "RPA-2.0", "RPA-3.0" -> marker
            else -> null
        }
    }

    private fun readPrefix(file: File, length: Int): ByteArray? {
        return runCatching {
            file.inputStream().use { input ->
                val buffer = ByteArray(length)
                var offset = 0

                while (offset < buffer.size) {
                    val count = input.read(buffer, offset, buffer.size - offset)
                    if (count <= 0) break
                    offset += count
                }

                buffer.copyOf(offset)
            }
        }.getOrNull()
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
        private const val MAX_SOURCE_PROBE_BYTES = 64 * 1024
        private const val RPA_HEADER_LENGTH = 7

        private val RPYC2_MAGIC = "RENPY RPC2".toByteArray(Charsets.US_ASCII)
        private val RENPY_SOURCE_MARKER_REGEX = Regex(
            "(?m)^\\s*(?:label\\s+[A-Za-z_][A-Za-z0-9_.$]*\\s*:|" +
                "define\\s+[A-Za-z_][A-Za-z0-9_.]*\\s*=|" +
                "default\\s+[A-Za-z_][A-Za-z0-9_.]*\\s*=|" +
                "screen\\s+[A-Za-z_][A-Za-z0-9_]*\\s*(?:\\([^\\n]*\\))?\\s*:|" +
                "image\\s+[A-Za-z_][A-Za-z0-9_ .]*\\s*=|" +
                "transform\\s+[A-Za-z_][A-Za-z0-9_]*\\s*:|" +
                "init(?:\\s+[^\\n:]*)?\\s*:)"
        )
        private val RENPY_PYTHON_BLOCK_REGEX = Regex("(?m)^\"\"\"renpy\\s*$")
    }
}
