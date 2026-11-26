package io.github.helterfall.ramen.runtime.systemnnn

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Bounded on-device diagnostic recorder. The JSONL trace is deliberately
 * separate from the human-readable log so a single scene replay can be
 * inspected mechanically without parsing log prose.
 */
class SystemNnnDiagnosticLog(
    private val directory: File,
    private val decoderLogFile: File? = null
) {
    private val lock = Any()
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RamenSceneTraceIO").apply { isDaemon = true }
    }
    private val logFile = File(directory, LOG_FILE_NAME)
    private val previousLogFile = File(directory, PREVIOUS_LOG_FILE_NAME)
    private val traceFile = File(directory, TRACE_FILE_NAME)
    private val previousTraceFile = File(directory, PREVIOUS_TRACE_FILE_NAME)
    private val snapshotFile = File(directory, SNAPSHOT_FILE_NAME)
    private val crashFile = File(directory, CRASH_FILE_NAME)
    private var traceSequence = 0L

    private var recentEntriesState by mutableStateOf<List<String>>(emptyList())

    /** Snapshot-state-backed tail consumed by the in-game debug overlay. */
    val recentEntries: List<String>
        get() = recentEntriesState

    fun record(event: String, details: String = "") {
        val safeEvent = sanitize(event).take(MAX_EVENT_CHARS)
        val safeDetails = sanitize(details).take(MAX_DETAILS_CHARS)
        val line = "${timestamp()} [$safeEvent] $safeDetails"
        rememberEntry(line)
        enqueueWrite("record") {
            appendBoundedLog(line)
            appendTraceLocked(
                mapOf(
                    "event" to "log",
                    "sourceEvent" to safeEvent,
                    "details" to safeDetails
                )
            )
        }
    }

    /**
     * Append one structured scene event. Values may contain nested maps,
     * lists, primitive arrays, strings, booleans and numbers.
     */
    fun recordTrace(
        event: String,
        data: Map<String, Any?>,
        summary: String = event
    ) {
        val safeEvent = sanitize(event).take(MAX_EVENT_CHARS)
        val safeSummary = sanitize(summary).take(MAX_DETAILS_CHARS)
        val line = "${timestamp()} [TRACE:$safeEvent] $safeSummary"
        rememberEntry(line)
        enqueueWrite("recordTrace") {
            appendTraceLocked(
                linkedMapOf(
                    "event" to safeEvent,
                    "data" to data
                )
            )
            appendBoundedLog(line)
        }
    }

    fun updateRuntimeSnapshot(snapshot: String) {
        val safeSnapshot = sanitize(snapshot).take(MAX_SNAPSHOT_CHARS)
        enqueueWrite("updateRuntimeSnapshot") {
            snapshotFile.writeText(safeSnapshot, Charsets.UTF_8)
        }
    }

    fun recordException(event: String, failure: Throwable, details: String = "") {
        record(
            event,
            details + " exception=" + failure.javaClass.name + ": " + (failure.message ?: "")
        )
        val trace = StringWriter().also { writer ->
            failure.printStackTrace(PrintWriter(writer))
        }.toString()
        val safeEvent = sanitize(event)
        val safeTrace = sanitize(trace).take(MAX_CRASH_TRACE_CHARS)
        enqueueWrite("recordException") {
            if (crashFile.exists() && crashFile.length() > MAX_CRASH_BYTES) {
                crashFile.writeText("", Charsets.UTF_8)
            }
            crashFile.appendText(
                timestamp() + " [" + safeEvent + "]\n" + safeTrace + "\n",
                Charsets.UTF_8
            )
        }
    }

    /** Writes the complete retained JSONL trace to a user-selected SAF document. */
    @Throws(IOException::class)
    fun writeTraceTo(destination: OutputStream) {
        flushPendingWrites()
        synchronized(lock) {
            if (previousTraceFile.isFile) {
                previousTraceFile.inputStream().buffered().use { it.copyTo(destination) }
            }
            if (traceFile.isFile) {
                traceFile.inputStream().buffered().use { it.copyTo(destination) }
            }
            destination.flush()
        }
    }

    fun installUncaughtExceptionHandler(): AutoCloseable {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val installed = Thread.UncaughtExceptionHandler { thread, failure ->
            recordException("uncaught_exception", failure, "thread=" + thread.name)
            runCatching { flushPendingWrites() }.onFailure { flushFailure ->
                reportRecorderFailure("uncaught_exception_flush", flushFailure)
            }
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
        flushPendingWrites()
        synchronized(lock) {
            destination.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) {
                    throw IOException("Could not create the diagnostic export directory.")
                }
            }
            ZipOutputStream(destination.outputStream().buffered()).use { zip ->
                addFile(zip, logFile, LOG_FILE_NAME)
                addFile(zip, previousLogFile, PREVIOUS_LOG_FILE_NAME)
                addFile(zip, traceFile, TRACE_FILE_NAME)
                addFile(zip, previousTraceFile, PREVIOUS_TRACE_FILE_NAME)
                addFile(zip, snapshotFile, SNAPSHOT_FILE_NAME)
                addFile(zip, crashFile, CRASH_FILE_NAME)
                decoderLogFile?.let { addFile(zip, it, DECODER_LOG_FILE_NAME) }
                zip.putNextEntry(ZipEntry("README.txt"))
                zip.write(
                    ("Ramen System-NNN diagnostic bundle.\n" +
                        "ramen_scene_trace.jsonl contains one JSON object per command, frame, viewport change and warning.\n" +
                        "The trace includes the active dialogue text so the reported scene can be identified.\n" +
                        "Game assets and audio bytes are not included.\n")
                        .toByteArray(Charsets.UTF_8)
                )
                zip.closeEntry()
            }
        }
        return destination
    }

    private fun enqueueWrite(operation: String, action: () -> Unit) {
        try {
            writer.execute {
                synchronized(lock) {
                    try {
                        ensureDirectory()
                        action()
                    } catch (failure: Exception) {
                        reportRecorderFailure(operation, failure)
                    }
                }
            }
        } catch (failure: Exception) {
            reportRecorderFailure(operation + "_queue", failure)
        }
    }

    fun flush() {
        flushPendingWrites()
    }

    private fun flushPendingWrites() {
        try {
            writer.submit(Runnable { }).get()
        } catch (failure: Exception) {
            throw IOException("Could not flush pending scene trace writes.", failure)
        }
    }

    private fun appendTraceLocked(data: Map<String, Any?>) {
        if (traceFile.exists() && traceFile.length() >= MAX_TRACE_BYTES) {
            previousTraceFile.delete()
            if (!traceFile.renameTo(previousTraceFile)) {
                traceFile.writeText("", Charsets.UTF_8)
            }
        }
        traceSequence += 1L
        val record = linkedMapOf<String, Any?>(
            "timestampUtc" to timestamp(),
            "sequence" to traceSequence,
            "thread" to Thread.currentThread().name
        )
        record.putAll(data)
        val encoded = SystemNnnTraceJson.encode(record)
        val bounded = if (encoded.length > MAX_TRACE_LINE_CHARS) {
            SystemNnnTraceJson.encode(
                mapOf(
                    "timestampUtc" to timestamp(),
                    "sequence" to traceSequence,
                    "event" to "trace_record_truncated",
                    "originalChars" to encoded.length,
                    "prefix" to encoded.take(MAX_TRACE_LINE_CHARS / 2)
                )
            )
        } else {
            encoded
        }
        traceFile.appendText(bounded + "\n", Charsets.UTF_8)
    }

    private fun appendBoundedLog(line: String) {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        if (logFile.exists() && logFile.length() + bytes.size > MAX_LOG_BYTES) {
            previousLogFile.delete()
            if (!logFile.renameTo(previousLogFile)) {
                logFile.writeText("", Charsets.UTF_8)
            }
        }
        logFile.appendBytes(bytes)
    }

    private fun rememberEntry(line: String) {
        recentEntriesState = (recentEntriesState + line).takeLast(MAX_RECENT_ENTRIES)
    }

    private fun ensureDirectory() {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create the diagnostics directory.")
        }
    }

    private fun reportRecorderFailure(operation: String, failure: Throwable) {
        // Recorder failures must not break gameplay, but they must not disappear
        // silently either. Android routes stderr to logcat.
        System.err.println(
            "RamenSceneTrace recorder failure during $operation: " +
                failure.javaClass.name + ": " + (failure.message ?: "")
        )
    }

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

    companion object {
        @Volatile
        var active: SystemNnnDiagnosticLog? = null

        const val TRACE_FILE_NAME = "ramen_scene_trace.jsonl"
        private const val PREVIOUS_TRACE_FILE_NAME = "ramen_scene_trace.previous.jsonl"
        private const val LOG_FILE_NAME = "system-nnn-diagnostics.log"
        private const val PREVIOUS_LOG_FILE_NAME = "system-nnn-diagnostics.previous.log"
        private const val SNAPSHOT_FILE_NAME = "system-nnn-runtime-snapshot.txt"
        private const val CRASH_FILE_NAME = "system-nnn-crashes.txt"
        private const val DECODER_LOG_FILE_NAME = "ramen_debug.log"
        private const val MAX_LOG_BYTES = 4L * 1024L * 1024L
        private const val MAX_TRACE_BYTES = 32L * 1024L * 1024L
        private const val MAX_CRASH_BYTES = 2L * 1024L * 1024L
        private const val MAX_EVENT_CHARS = 80
        private const val MAX_DETAILS_CHARS = 4_000
        private const val MAX_SNAPSHOT_CHARS = 16_000
        private const val MAX_CRASH_TRACE_CHARS = 64_000
        private const val MAX_TRACE_LINE_CHARS = 128_000
        private const val MAX_RECENT_ENTRIES = 28
    }
}
