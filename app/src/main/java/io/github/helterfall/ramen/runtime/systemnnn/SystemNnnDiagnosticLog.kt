package io.github.helterfall.ramen.runtime.systemnnn

import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Small, privacy-conscious diagnostic recorder for reproducible System-NNN issues.
 * Dialogue text, audio bytes, source files and absolute game-library paths are excluded.
 */
class SystemNnnDiagnosticLog(
    private val directory: File,
    private val decoderLogFile: File? = null
) {
    private val lock = Any()
    private val logFile = File(directory, LOG_FILE_NAME)
    private val previousLogFile = File(directory, PREVIOUS_LOG_FILE_NAME)
    private val snapshotFile = File(directory, SNAPSHOT_FILE_NAME)
    private val crashFile = File(directory, CRASH_FILE_NAME)

    fun record(event: String, details: String = "") {
        val safeEvent = sanitize(event).take(MAX_EVENT_CHARS)
        val safeDetails = sanitize(details).take(MAX_DETAILS_CHARS)
        val line = "${timestamp()} [$safeEvent] $safeDetails\n"
        synchronized(lock) {
            try {
                if (!directory.exists() && !directory.mkdirs()) {
                    throw IOException("Could not create the diagnostics directory.")
                }
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
                if (!directory.exists()) directory.mkdirs()
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
                if (!directory.exists()) directory.mkdirs()
                if (crashFile.exists() && crashFile.length() > MAX_CRASH_BYTES) {
                    crashFile.writeText("", Charsets.UTF_8)
                }
                crashFile.appendText(
                    timestamp() + " [" + sanitize(event) + "]\n" +
                        sanitize(trace).take(MAX_CRASH_TRACE_CHARS) + "\n",
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
                decoderLogFile?.let { addFile(zip, it, DECODER_LOG_FILE_NAME) }
                zip.putNextEntry(ZipEntry("README.txt"))
                zip.write(
                    ("Ramen System-NNN diagnostic bundle.\n" +
                        "Contains command/effect/resource metadata and exception traces.\n" +
                        "Dialogue text, game assets, audio data and absolute library paths are not included.\n")
                        .toByteArray(Charsets.UTF_8)
                )
                zip.closeEntry()
            }
        }
        return destination
    }

    private fun addFile(zip: ZipOutputStream, file: File, entryName: String) {
        if (!file.isFile) return
        zip.putNextEntry(ZipEntry(entryName))
        file.inputStream().buffered().use { input -> input.copyTo(zip) }
        zip.closeEntry()
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).format(Date())

    private fun sanitize(value: String): String =
        value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')

    private companion object {
        const val LOG_FILE_NAME = "system-nnn-diagnostics.log"
        const val PREVIOUS_LOG_FILE_NAME = "system-nnn-diagnostics.previous.log"
        const val SNAPSHOT_FILE_NAME = "system-nnn-runtime-snapshot.txt"
        const val CRASH_FILE_NAME = "system-nnn-crashes.txt"
        const val DECODER_LOG_FILE_NAME = "ramen_debug.log"
        const val MAX_LOG_BYTES = 4L * 1024L * 1024L
        const val MAX_CRASH_BYTES = 2L * 1024L * 1024L
        const val MAX_EVENT_CHARS = 80
        const val MAX_DETAILS_CHARS = 4_000
        const val MAX_SNAPSHOT_CHARS = 16_000
        const val MAX_CRASH_TRACE_CHARS = 64_000
    }
}
