package io.github.helterfall.ramen.runtime.systemnnn

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Stages the full-game report before the Android document picker is opened.
 *
 * Keeping serialization and SAF writing as separate checked steps prevents a
 * newly-created empty document from being mistaken for a completed audit when
 * the Activity is recreated or the output provider cannot be opened.
 */
internal object SystemNnnFullGameAuditExporter {

    @Throws(IOException::class)
    fun stageReport(report: Map<String, Any?>, destination: File): Long {
        if (report["reportType"] != REPORT_TYPE ||
            report["schemaVersion"] != 1 ||
            report["scope"] !is Map<*, *>
        ) {
            throw IOException("The full-game audit returned an invalid report structure.")
        }

        val json = SystemNnnTraceJson.encode(report)
        if (json.length < 2 || json.first() != '{' || json.last() != '}') {
            throw IOException("The full-game audit JSON serialization was empty or malformed.")
        }
        val bytes = (json + "\n").toByteArray(StandardCharsets.UTF_8)

        val parent = destination.absoluteFile.parentFile
            ?: throw IOException("The audit staging directory is unavailable.")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Could not create the audit staging directory.")
        }

        val temporary = File(parent, destination.name + ".tmp")
        try {
            temporary.outputStream().buffered().use { output ->
                output.write(bytes)
                output.flush()
            }
            if (temporary.length() != bytes.size.toLong() || temporary.length() == 0L) {
                throw IOException("The staged audit report size does not match the serialized JSON.")
            }

            if (destination.exists() && !destination.delete()) {
                throw IOException("Could not replace the previous staged audit report.")
            }
            if (!temporary.renameTo(destination)) {
                throw IOException("Could not finalize the staged audit report.")
            }
            val finalizedSize = destination.length()
            if (finalizedSize != bytes.size.toLong() || finalizedSize == 0L) {
                destination.delete()
                throw IOException("The finalized audit report is empty or incomplete.")
            }
            return finalizedSize
        } finally {
            temporary.delete()
        }
    }

    @Throws(IOException::class)
    fun copyTo(source: File, destination: OutputStream): Long {
        if (!source.isFile || source.length() <= 0L) {
            throw IOException("The staged full-game audit report is missing or empty.")
        }
        val expectedBytes = source.length()
        val copiedBytes = source.inputStream().buffered().use { input ->
            input.copyTo(destination)
        }
        destination.flush()
        if (copiedBytes != expectedBytes) {
            throw IOException(
                "The audit export copied $copiedBytes bytes, expected $expectedBytes."
            )
        }
        return copiedBytes
    }

    private const val REPORT_TYPE = "ramen.system-nnn.full-game-audit"
}
