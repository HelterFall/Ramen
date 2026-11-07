package io.github.helterfall.ramen.runtime.systemnnn

import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Privacy-conscious diagnostic recorder for reproducible System-NNN issues.
 * Dialogue text, audio bytes, source files and absolute game-library paths are excluded.
 */
class SystemNnnDiagnosticLog(
    private val directory: File,
    private val decoderLogFile: File? = null,
    private val previousDecoderLogFile: File? = null
) {
    private val lock = Any()
    private val logFile = File(directory, LOG_FILE_NAME)
    private val previousLogFile = File(directory, PREVIOUS_LOG_FILE_NAME)
    private val snapshotFile = File(directory, SNAPSHOT_FILE_NAME)
    private val crashFile = File(directory, CRASH_FILE_NAME)
    private val previousCrashFile = File(directory, PREVIOUS_CRASH_FILE_NAME)

    /** Starts a clean per-launch log while retaining the immediately previous session. */
    fun beginSession() {
        synchronized(lock) {
            try {
                ensureDirectory()
                rotateSessionFile(logFile, previousLogFile)
                rotateSessionFile(crashFile, previousCrashFile)
                snapshotFile.delete()
            } catch (_: Exception) {
                // Diagnostics must never block game startup.
            }
        }
    }

    fun record(event: String, details: String = "") {
        val safeEvent = sanitize(event).take(MAX_EVENT_CHARS)
        val safeDetails = sanitize(details).take(MAX_DETAILS_CHARS)
        val line = "${timestamp()} [$safeEvent] $safeDetails\n"
        synchronized(lock) {
            try {
                ensureDirectory()
                val bytes = line.toByteArray(Charsets.UTF_8)
                if (logFile.exists() && logFile.length() + bytes.size > MAX_LOG_BYTES) {
                    previousLogFile.delete()
                    if (!logFile.renameTo(previousLogFile)) {
                        logFile.writeText("", Charsets.UTF_8)
                    }
                }
                logFile.appendBytes(bytes)
            } catch (_: Exception) {
                // Diagnostics must never break gameplay.
            }
        }
    }

    fun updateRuntimeSnapshot(snapshot: String) {
        synchronized(lock) {
            try {
                ensureDirectory()
                snapshotFile.writeText(sanitize(snapshot).take(MAX_SNAPSHOT_CHARS), Charsets.UTF_8)
            } catch (_: Exception) {
                // Diagnostics must never break gameplay.
            }
        }
    }

    fun recordException(event: String, failure: Throwable, details: String = "") {
        record(event, details + " exception=" + failure.javaClass.name + ": " + (failure.message ?: ""))
        val trace = StringWriter().also { writer ->
            failure.printStackTrace(PrintWriter(writer))
        }.toString()
        synchronized(lock) {
            try {
                ensureDirectory()
                if (crashFile.exists() && crashFile.length() > MAX_CRASH_BYTES) {
                    crashFile.writeText("", Charsets.UTF_8)
                }
                val snapshot = if (snapshotFile.isFile) {
                    runCatching { snapshotFile.readText(Charsets.UTF_8).take(MAX_SNAPSHOT_CHARS) }
                        .getOrNull()
                } else null
                crashFile.appendText(
                    timestamp() + " [" + sanitize(event) + "]\n" +
                        "context: " + sanitize(details) + "\n" +
                        (snapshot?.let { "runtime_snapshot:\n$it\n" }
                            ?: "runtime_snapshot: unavailable\n") +
                        "stack_trace:\n" + sanitizeTrace(trace).take(MAX_CRASH_TRACE_CHARS) + "\n\n",
                    Charsets.UTF_8
                )
            } catch (_: Exception) {
                // Diagnostics must never break crash handling.
            }
        }
    }

    fun installUncaughtExceptionHandler(): AutoCloseable {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val installed = Thread.UncaughtExceptionHandler { thread, failure ->
            recordException("uncaught_exception", failure, "thread=" + thread.name)
            if (previous != null) previous.uncaughtException(thread, failure)
            else throw failure
        }
        Thread.setDefaultUncaughtExceptionHandler(installed)
        return AutoCloseable {
            if (Thread.getDefaultUncaughtExceptionHandler() === installed) {
                Thread.setDefaultUncaughtExceptionHandler(previous)
            }
        }
    }

    @Throws(IOException::class)
    fun createArchive(destination: File): File {
        synchronized(lock) {
            destination.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) {
                    throw IOException("Could not create the diagnostic export directory.")
                }
            }
            ZipOutputStream(destination.outputStream().buffered()).use { zip ->
                addFile(zip, logFile, LOG_FILE_NAME)
                addFile(zip, previousLogFile, PREVIOUS_LOG_FILE_NAME)
                addFile(zip, snapshotFile, SNAPSHOT_FILE_NAME)
                addFile(zip, crashFile, CRASH_FILE_NAME)
                addFile(zip, previousCrashFile, PREVIOUS_CRASH_FILE_NAME)
                decoderLogFile?.let { addFile(zip, it, DECODER_LOG_FILE_NAME) }
                previousDecoderLogFile?.let { addFile(zip, it, PREVIOUS_DECODER_LOG_FILE_NAME) }

                zip.putNextEntry(ZipEntry(SUMMARY_FILE_NAME))
                zip.write(buildSummary().toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("README.txt"))
                zip.write(
                    ("Ramen System-NNN diagnostic bundle.\n" +
                        "diagnostic-summary.txt is the recommended starting point.\n" +
                        "decoder_unsupported means the Android VM skipped a native record.\n" +
                        "decoder_known_unimplemented and effect_unsupported are tracked separately.\n" +
                        "Dialogue text, game assets, audio data and absolute library paths are not included.\n")
                        .toByteArray(Charsets.UTF_8)
                )
                zip.closeEntry()
            }
        }
        return destination
    }

    private fun buildSummary(): String {
        val currentDiagnosticLines = readLinesSafely(logFile)
        val previousDiagnosticLines = readLinesSafely(previousLogFile)
        val diagnosticLines = previousDiagnosticLines + currentDiagnosticLines
        val decoderLines = listOf(previousDecoderLogFile, decoderLogFile)
            .filterNotNull()
            .filter { it.isFile }
            .flatMap { file ->
                runCatching { file.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
            }

        val eventCounts = sortedMapOf<String, Int>()
        diagnosticLines.forEach { line ->
            val event = line.substringAfter(" [", "").substringBefore(']')
            if (event.isNotBlank()) eventCounts[event] = (eventCounts[event] ?: 0) + 1
        }

        val currentUnsupported = currentDiagnosticLines.filter { "[decoder_unsupported]" in it }
        val previousUnsupported = previousDiagnosticLines.filter { "[decoder_unsupported]" in it }
        val unsupported = currentUnsupported + previousUnsupported
        val knownUnimplemented = diagnosticLines.filter { "[decoder_known_unimplemented]" in it }
        val knownIgnored = diagnosticLines.filter { "[decoder_known_ignored]" in it }
        val rendererGaps = diagnosticLines.filter { "[effect_unsupported]" in it }
        val unsupportedCodes = sortedMapOf<String, Int>()
        val codeRegex = Regex("\\bcode=(0x[0-9a-fA-F]+)")
        currentUnsupported.forEach { line ->
            val code = codeRegex.find(line)?.groupValues?.getOrNull(1) ?: "unknown"
            unsupportedCodes[code] = (unsupportedCodes[code] ?: 0) + 1
        }

        val oldDecoderLabelCount = decoderLines.count { it.startsWith("unsupported_record ") }
        val crashCount = countCrashRecords(crashFile)
        val previousCrashCount = countCrashRecords(previousCrashFile)
        val snapshot = if (snapshotFile.isFile) {
            runCatching { snapshotFile.readText(Charsets.UTF_8).take(MAX_SNAPSHOT_CHARS) }
                .getOrDefault("unavailable")
        } else "unavailable"

        return buildString {
            appendLine("Ramen System-NNN diagnostic summary")
            appendLine("Exported at: ${timestamp()}")
            appendLine("Current session diagnostic events: ${currentDiagnosticLines.size}")
            appendLine("Previous session diagnostic events: ${previousDiagnosticLines.size}")
            appendLine("Current session crash/exception records: $crashCount")
            appendLine("Previous session crash/exception records: $previousCrashCount")
            appendLine("Current session unsupported decoder records: ${currentUnsupported.size}")
            appendLine("Previous session unsupported decoder records: ${previousUnsupported.size}")
            appendLine("All exported unsupported decoder records: ${unsupported.size}")
            appendLine("Known native operations not implemented: ${knownUnimplemented.size}")
            appendLine("Known native metadata/no-op operations ignored: ${knownIgnored.size}")
            appendLine("Effects without a renderer path: ${rendererGaps.size}")
            appendLine("Raw decoder lines labelled unsupported_record: $oldDecoderLabelCount")
            appendLine()
            appendLine("Event counts:")
            if (eventCounts.isEmpty()) appendLine("  (none)")
            eventCounts.forEach { (event, count) -> appendLine("  $event: $count") }
            appendLine()
            appendLine("Unsupported record codes:")
            if (unsupportedCodes.isEmpty()) appendLine("  (none)")
            unsupportedCodes.forEach { (code, count) -> appendLine("  $code: $count") }
            appendLine()
            appendLine("Current runtime snapshot:")
            appendLine(snapshot)
            appendLine()
            appendLine("Last decoder-unsupported events:")
            if (unsupported.isEmpty()) appendLine("  (none)")
            unsupported.takeLast(MAX_SUMMARY_UNSUPPORTED_LINES).forEach { appendLine(it) }
            appendLine()
            appendLine("Last meaningful runtime events:")
            val meaningful = diagnosticLines.filter {
                "[decoder_unsupported]" in it || "[decoder_known_unimplemented]" in it ||
                    "[decoder_known_ignored]" in it || "[effect_unsupported]" in it ||
                    "[image_decode_failure]" in it || "[bitmap_missing]" in it ||
                    "[bitmap_budget_skip]" in it || "[sprite_resolution_warning]" in it ||
                    "[scene_advance_failure]" in it || "[uncaught_exception]" in it ||
                    "[draw_frame]" in it || "[effect_set]" in it
            }
            if (meaningful.isEmpty()) appendLine("  (none)")
            meaningful.takeLast(MAX_SUMMARY_EVENT_LINES).forEach { appendLine(it) }
            appendLine()
            appendLine("Classification note:")
            appendLine("  Current builds write successful effect decoding as effect_set, not unsupported_record.")
            appendLine("  Older decoder logs may contain successful SETEFFECT entries with the old unsupported_record label.")
            appendLine("  Use decoder_unsupported counts above to identify records actually skipped by the VM.")
        }
    }

    private fun ensureDirectory() {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create the diagnostics directory.")
        }
    }

    private fun rotateSessionFile(current: File, previous: File) {
        if (!current.isFile) return
        previous.delete()
        if (!current.renameTo(previous)) {
            current.copyTo(previous, overwrite = true)
            current.writeText("", Charsets.UTF_8)
        }
    }

    private fun readLinesSafely(file: File): List<String> =
        if (file.isFile) runCatching { file.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
        else emptyList()

    private fun countCrashRecords(file: File): Int =
        readLinesSafely(file).count { it.startsWith("20") && " [" in it }

    private fun addFile(zip: ZipOutputStream, file: File, entryName: String) {
        if (!file.isFile) return
        zip.putNextEntry(ZipEntry(entryName))
        file.inputStream().buffered().use { input -> input.copyTo(zip) }
        zip.closeEntry()
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

    private fun sanitize(value: String): String =
        value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')

    private fun sanitizeTrace(value: String): String =
        value.replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ')

    companion object {
        @Volatile
        var active: SystemNnnDiagnosticLog? = null

        private const val LOG_FILE_NAME = "system-nnn-diagnostics.log"
        private const val PREVIOUS_LOG_FILE_NAME = "system-nnn-diagnostics.previous.log"
        private const val SNAPSHOT_FILE_NAME = "system-nnn-runtime-snapshot.txt"
        private const val CRASH_FILE_NAME = "system-nnn-crashes.txt"
        private const val PREVIOUS_CRASH_FILE_NAME = "system-nnn-crashes.previous.txt"
        private const val DECODER_LOG_FILE_NAME = "ramen_debug.log"
        private const val PREVIOUS_DECODER_LOG_FILE_NAME = "ramen_debug.previous.log"
        private const val SUMMARY_FILE_NAME = "diagnostic-summary.txt"
        private const val MAX_LOG_BYTES = 4L * 1024L * 1024L
        private const val MAX_CRASH_BYTES = 2L * 1024L * 1024L
        private const val MAX_EVENT_CHARS = 80
        private const val MAX_DETAILS_CHARS = 4_000
        private const val MAX_SNAPSHOT_CHARS = 16_000
        private const val MAX_CRASH_TRACE_CHARS = 64_000
        private const val MAX_SUMMARY_UNSUPPORTED_LINES = 40
        private const val MAX_SUMMARY_EVENT_LINES = 60
    }
}
