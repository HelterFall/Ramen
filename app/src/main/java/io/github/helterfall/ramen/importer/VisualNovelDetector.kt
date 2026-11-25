package io.github.helterfall.ramen.importer

import io.github.helterfall.ramen.inspection.PeOverlayScanner
import io.github.helterfall.ramen.parser.SystemNnnScriptLoader
import io.github.helterfall.ramen.runtime.hsp3.Hsp3RuntimeAdapter
import io.github.helterfall.ramen.runtime.hsp3.Hsp3BytecodeDecoder
import io.github.helterfall.ramen.runtime.hsp3.Hsp3Instruction
import java.io.File
import java.io.IOException

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

        val executables = scannedFiles.filter { it.extension.equals("exe", ignoreCase = true) }
        var successfulHsp: Pair<io.github.helterfall.ramen.runtime.hsp3.Hsp3PreparedGame, List<Hsp3Instruction>>? = null
        var dpmxFailure: GameDetection? = null
        var nsisMarker: Pair<File, Long>? = null

        for (executable in executables) {
            val dpmxOffset = findOverlayMarker(executable, DPMX_SIGNATURE)
            val nsisOffset = findOverlayMarker(executable, NSIS_SIGNATURE)
            val attempt = runCatching {
                val prepared = Hsp3RuntimeAdapter().prepare(executable)
                val instructions = try {
                    Hsp3BytecodeDecoder().decode(prepared.program)
                } catch (error: Exception) {
                    throw IOException(
                        "CS command decoding failed: ${error.message ?: error.javaClass.simpleName}",
                        error
                    )
                }
                prepared to instructions
            }
            val loaded = attempt.getOrNull()
            if (loaded != null) {
                successfulHsp = loaded
                break
            }

            if (dpmxOffset != null && dpmxFailure == null) {
                val reason = attempt.exceptionOrNull()?.let(::formatFailure)
                    ?: "The HSP3 preparation pipeline did not return a program."
                dpmxFailure = GameDetection(
                    engineId = Hsp3RuntimeAdapter.ENGINE_ID,
                    engineLabel = Hsp3RuntimeAdapter.ENGINE_LABEL,
                    compatibilityStatus = "DETECTED_UNSUPPORTED",
                    evidence = "DPMX overlay signature at 0x${dpmxOffset.toString(16)}; " +
                        "HSP3 validation stage failed: $reason"
                )
            }
            if (nsisOffset != null && nsisMarker == null) {
                nsisMarker = executable to nsisOffset
            }
        }

        successfulHsp?.let { (prepared, decodedInstructions) ->
            val unsupportedCount = decodedInstructions.count { it is Hsp3Instruction.Unsupported }
            return GameDetection(
                engineId = Hsp3RuntimeAdapter.ENGINE_ID,
                engineLabel = Hsp3RuntimeAdapter.ENGINE_LABEL,
                compatibilityStatus = "PARSER_AVAILABLE",
                evidence = "Validated PE32/DPMX overlay at 0x" +
                    prepared.container.overlayOffset.toString(16) + "; " +
                    prepared.container.fileCount + " DPMX entries; " +
                    "HSP3.1 HSPHED with CS/DS/OT/DI segments; " +
                    decodedInstructions.size + " CS commands decoded, " +
                    unsupportedCount + " command IDs retained as runtime-unsupported markers. " +
                    "Android Canvas player and touch controls are connected."
            )
        }

        if (dpmxFailure != null) return dpmxFailure
        nsisMarker?.let { (file, offset) ->
            return GameDetection(
                engineId = NSIS_ENGINE_ID,
                engineLabel = "NSIS installer",
                compatibilityStatus = "DETECTED_UNSUPPORTED",
                evidence = "NullsoftInst overlay signature at 0x${offset.toString(16)} " +
                    "in ${file.name}; installer payload extraction is required before engine detection."
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

    private fun findOverlayMarker(file: File, signature: ByteArray): Long? =
        runCatching {
            PeOverlayScanner.findSignature(file, signature)
        }.getOrNull()

    private fun formatFailure(error: Throwable): String {
        var root = error
        while (root.cause != null && root.cause !== root) root = root.cause!!
        val message = error.message?.takeIf { it.isNotBlank() }
            ?: root.message?.takeIf { it.isNotBlank() }
            ?: error.javaClass.simpleName
        return message.replace('\n', ' ').take(MAX_DIAGNOSTIC_LENGTH)
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
        private const val MAX_DIAGNOSTIC_LENGTH = 240
        private const val NSIS_ENGINE_ID = "nsis-installer"
        private val DPMX_SIGNATURE = "DPMX".toByteArray(Charsets.US_ASCII)
        private val NSIS_SIGNATURE = byteArrayOf(
            0xEF.toByte(), 0xBE.toByte(), 0xAD.toByte(), 0xDE.toByte()
        ) + "NullsoftInst".toByteArray(Charsets.US_ASCII)
    }
}
