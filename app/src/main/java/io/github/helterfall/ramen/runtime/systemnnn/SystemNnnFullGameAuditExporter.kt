package io.github.helterfall.ramen.runtime.systemnnn

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

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


    @Throws(IOException::class)
    fun sha256(source: File): String {
        if (!source.isFile || source.length() <= 0L) {
            throw IOException("The staged full-game audit report is missing or empty.")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        source.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHexString()
    }

    @Throws(IOException::class)
    fun verifySavedReport(
        source: InputStream,
        expectedBytes: Long,
        expectedSha256: String
    ): Long {
        if (expectedBytes <= 0L) {
            throw IOException("The expected audit report size is empty.")
        }
        var actualBytes = 0L
        var firstByte = -1
        var lastByte = -1
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        source.use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (firstByte < 0) firstByte = buffer[0].toInt() and 0xFF
                lastByte = buffer[count - 1].toInt() and 0xFF
                digest.update(buffer, 0, count)
                actualBytes += count.toLong()
            }
        }
        if (actualBytes != expectedBytes ||
            firstByte != '{'.code ||
            lastByte != '\n'.code ||
            !digest.digest().toHexString().equals(expectedSha256, ignoreCase = true)
        ) {
            throw IOException(
                "The saved document did not match the staged JSON: byte count, JSON boundaries, or SHA-256 checksum differ."
            )
        }
        return actualBytes
    }

    private fun ByteArray.toHexString(): String = joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xFF)
    }

    private const val REPORT_TYPE = "ramen.system-nnn.full-game-audit"
}
