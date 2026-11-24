package io.github.helterfall.ramen.importer

import io.github.helterfall.ramen.inspection.FileRandomAccessSource
import io.github.helterfall.ramen.inspection.LiveMakerArchiveParser
import io.github.helterfall.ramen.inspection.LiveMakerLsbParser
import io.github.helterfall.ramen.inspection.ReadOnlyRandomAccessSource
import io.github.helterfall.ramen.parser.SystemNnnScriptLoader
import java.io.ByteArrayOutputStream
import java.io.IOException
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
            val archiveParser = LiveMakerArchiveParser()
            val hasLooseCompiledScript = scannedFiles.any { candidate ->
                candidate.extension.equals("lsb", ignoreCase = true) &&
                    candidate.length() in 1L..MAX_LSB_BYTES &&
                    runCatching {
                        FileRandomAccessSource(candidate).use { source ->
                            LiveMakerLsbParser().parse(source).isComplete
                        }
                    }.getOrDefault(false)
            }
            val hasArchivedCompiledScript = archive.entries.asSequence()
                .filter { it.name.endsWith(".lsb", ignoreCase = true) }
                .any { entry ->
                    runCatching {
                        val output = ByteArrayOutputStream()
                        FileRandomAccessSource(file).use { source ->
                            archiveParser.copyEntryTo(
                                source = source,
                                archive = archive,
                                entry = entry,
                                output = output,
                                maxOutputBytes = MAX_LSB_BYTES
                            )
                        }
                        DetectorByteArraySource(output.toByteArray()).use { source ->
                            LiveMakerLsbParser().parse(source).isComplete
                        }
                    }.getOrDefault(false)
                }
            val hasCompiledScript = hasLooseCompiledScript || hasArchivedCompiledScript
            return GameDetection(
                engineId = "livemaker-vf102",
                engineLabel = if (hasCompiledScript) {
                    "LiveMaker VF v102 (активный рантайм)"
                } else {
                    "LiveMaker VF v102 (парсер архива)"
                },
                compatibilityStatus = if (hasCompiledScript) "PLAYABLE" else "PARSER_AVAILABLE",
                evidence = "Validated LiveMaker VF v102 archive in $relativePath at " +
                    "0x${archive.archiveOffset.toString(16)}; " +
                    "${archive.entries.size} directory entries parsed. " +
                    if (hasCompiledScript) {
                        "A compiled .lsb page passed the LSB parser; the Ramen LiveMaker runner is enabled."
                    } else {
                        "No valid compiled .lsb page was found; archive inspection only."
                    }
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
        private const val MAX_LSB_BYTES = 32L * 1024L * 1024L
        private val LIVEMAKER_CONTAINER_EXTENSIONS = setOf("exe", "dat", "vf", "ext")
    }
}


private class DetectorByteArraySource(
    private val bytes: ByteArray
) : ReadOnlyRandomAccessSource {
    override val sizeBytes: Long
        get() = bytes.size.toLong()

    override fun readAt(
        position: Long,
        target: ByteArray,
        targetOffset: Int,
        length: Int
    ): Int {
        require(position >= 0L)
        require(targetOffset >= 0 && targetOffset <= target.size)
        require(length >= 0 && length <= target.size - targetOffset)
        if (length == 0) return 0
        if (position >= bytes.size.toLong()) return -1
        val count = minOf(length, bytes.size - position.toInt())
        System.arraycopy(bytes, position.toInt(), target, targetOffset, count)
        return count
    }

    override fun close() = Unit
}
