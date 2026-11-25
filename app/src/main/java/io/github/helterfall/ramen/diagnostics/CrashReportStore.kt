package io.github.helterfall.ramen.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.time.Instant

/** Small synchronous crash journal suitable for an uncaught-exception handler. */
internal class CrashReportStore(private val directory: File) {
    private val reportFile = File(directory, REPORT_FILE_NAME)

    fun save(threadName: String, error: Throwable) {
        val report = buildString {
            appendLine("Ramen crash report")
            appendLine("Time: ${Instant.now()}")
            appendLine("Thread: $threadName")
            appendLine()
            append(error.stackTraceToString().take(MAX_REPORT_CHARS))
        }
        directory.mkdirs()
        FileOutputStream(reportFile, false).use { output ->
            output.write(report.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    fun read(): String? =
        if (reportFile.isFile) runCatching { reportFile.readText(Charsets.UTF_8) }.getOrNull()
        else null

    fun clear() {
        runCatching { reportFile.delete() }
    }

    companion object {
        private const val REPORT_FILE_NAME = "last-crash-report.txt"
        private const val MAX_REPORT_CHARS = 128_000
    }
}
