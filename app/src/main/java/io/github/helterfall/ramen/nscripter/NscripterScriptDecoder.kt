package io.github.helterfall.ramen.nscripter

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class NscripterDecodedScript(
    val text: String,
    val encoding: String,
    val mode: String
)

data class NscripterScriptSource(
    val relativePath: String,
    val text: String,
    val encoding: String,
    val mode: String
)

/**
 * Decodes classic NScripter scripts and ONScripter UTF-8 scripts.
 *
 * Supported loose entry scripts:
 * - ONScripter UTF-8: 0.utf / 00.utf, followed by numbered .utf segments.
 * - Plain text: 0.txt / 00.txt, followed by numbered .txt segments.
 * - Classic packed script: nscript.dat (XOR 0x84).
 *
 * Protected formats (nscr_sec.dat, nscript.___, onscript.nt2 and onscript.nt3)
 * are reported as unsupported instead of being decoded with a guessed key.
 */
class NscripterScriptDecoder {
    private val shiftJis: Charset = Charset.forName("Shift_JIS")

    fun decodeNscriptDat(file: File): NscripterDecodedScript {
        if (!file.isFile) throw IOException("Script file does not exist: ${file.path}")
        return decodeNscriptDat(file.readBytes())
    }

    fun decodeNscriptDat(bytes: ByteArray): NscripterDecodedScript {
        if (bytes.isEmpty()) throw IOException("nscript.dat is empty.")

        val decoded = ByteArray(bytes.size) { index ->
            (bytes[index].toInt() xor XOR_KEY).toByte()
        }

        var contentLength = decoded.size
        while (contentLength > 0 && decoded[contentLength - 1] == 0.toByte()) {
            contentLength--
        }
        if (contentLength == 0) {
            throw IOException("nscript.dat contains no script after XOR 0x84 decoding.")
        }

        val content = decoded.copyOf(contentLength)
        rejectEmbeddedNul(content, "nscript.dat")
        val (text, encoding) = decodeStrict(content, preferUtf8 = false)
        return NscripterDecodedScript(
            text = text,
            encoding = encoding,
            mode = "xor-0x84"
        )
    }

    fun decodeTextFile(file: File): NscripterDecodedScript {
        if (!file.isFile) throw IOException("Script file does not exist: ${file.path}")
        val bytes = file.readBytes()
        return decodeTextBytes(file.name, bytes)
    }

    /** UTF-8 is mandatory for .utf files; Shift-JIS fallback would hide corrupt UTF-8. */
    fun decodeUtf8Script(file: File): NscripterDecodedScript {
        if (!file.isFile) throw IOException("Script file does not exist: ${file.path}")
        return decodeUtf8Script(file.name, file.readBytes())
    }

    fun decodeUtf8Script(sourceName: String, bytes: ByteArray): NscripterDecodedScript {
        val content = stripUtf8Bom(bytes)
        if (content.isEmpty()) {
            return NscripterDecodedScript("", StandardCharsets.UTF_8.name(), "utf8-script")
        }
        rejectEmbeddedNul(content, sourceName)
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content))
                .toString()
        } catch (error: CharacterCodingException) {
            throw IOException("ONScripter .utf script is not valid UTF-8: $sourceName", error)
        }
        return NscripterDecodedScript(
            text = text,
            encoding = StandardCharsets.UTF_8.name(),
            mode = "utf8-script"
        )
    }

    /**
     * Decodes a script buffer using the source file name to select its format.
     * This is also used for script entries read from NSA archives.
     */
    fun decodeScriptBytes(fileName: String, bytes: ByteArray): NscripterDecodedScript {
        val leafName = fileName.substringAfterLast('/').substringAfterLast('\\')
        return when {
            leafName.equals("nscript.dat", ignoreCase = true) -> decodeNscriptDat(bytes)
            leafName.endsWith(".utf", ignoreCase = true) -> decodeUtf8Script(leafName, bytes)
            else -> decodeTextBytes(leafName, bytes)
        }
    }

    /**
     * Prefers ONScripter UTF-8 entry scripts, then NScripter plain text scripts.
     * Numbered segments are appended in numeric order using the entry's extension.
     * If neither plain entry exists, nscript.dat is used.
     */
    fun findScriptFiles(gameDirectory: File): List<File> {
        if (!gameDirectory.isDirectory) {
            throw IOException("The NScripter game directory is unavailable: ${gameDirectory.path}")
        }

        val entryCandidates = listOf(
            "0.utf",
            "00.utf",
            "0.txt",
            "00.txt"
        )
        val entryScript = entryCandidates
            .asSequence()
            .map { File(gameDirectory, it) }
            .firstOrNull { it.isFile }

        if (entryScript != null) {
            val extension = entryScript.extension
            return buildList {
                add(entryScript)
                for (number in 1..99) {
                    val unpadded = File(gameDirectory, "$number.$extension")
                    val padded = File(
                        gameDirectory,
                        number.toString().padStart(2, '0') + ".$extension"
                    )
                    val segment = when {
                        unpadded.isFile -> unpadded
                        padded.isFile -> padded
                        else -> null
                    }
                    if (segment != null) add(segment)
                }
            }
        }

        // Protected formats take precedence over the legacy XOR nscript.dat fallback.
        val protectedBeforeXor = listOf("nscr_sec.dat", "nscript.___")
            .map { File(gameDirectory, it) }
            .firstOrNull { it.isFile }
        if (protectedBeforeXor != null) {
            throw UnsupportedNscripterScriptFormatException(
                protectedBeforeXor.name,
                "magic-table/key-table encryption is not implemented"
            )
        }

        val encrypted = File(gameDirectory, "nscript.dat")
        if (encrypted.isFile) return listOf(encrypted)

        val protectedAfterXor = listOf("onscript.nt2", "onscript.nt3")
            .map { File(gameDirectory, it) }
            .firstOrNull { it.isFile }
        if (protectedAfterXor != null) {
            throw UnsupportedNscripterScriptFormatException(
                protectedAfterXor.name,
                "ONScripter NT2/NT3 encryption is not implemented"
            )
        }

        return emptyList()
    }

    fun loadScripts(gameDirectory: File): List<NscripterScriptSource> {
        return findScriptFiles(gameDirectory).map { file ->
            val decoded = decodeScriptBytes(file.name, file.readBytes())
            NscripterScriptSource(
                relativePath = file.relativeTo(gameDirectory).invariantSeparatorsPath,
                text = decoded.text,
                encoding = decoded.encoding,
                mode = decoded.mode
            )
        }
    }

    private fun decodeTextBytes(sourceName: String, bytes: ByteArray): NscripterDecodedScript {
        if (bytes.isEmpty()) {
            return NscripterDecodedScript("", StandardCharsets.UTF_8.name(), "plain-text")
        }
        val content = stripUtf8Bom(bytes)
        rejectEmbeddedNul(content, sourceName)
        val (text, encoding) = decodeStrict(content, preferUtf8 = true)
        return NscripterDecodedScript(
            text = text,
            encoding = encoding,
            mode = "plain-text"
        )
    }

    private fun stripUtf8Bom(bytes: ByteArray): ByteArray {
        val hasUtf8Bom = bytes.size >= UTF8_BOM.size &&
            UTF8_BOM.indices.all { bytes[it] == UTF8_BOM[it] }
        return if (hasUtf8Bom) bytes.copyOfRange(UTF8_BOM.size, bytes.size) else bytes
    }

    private fun decodeStrict(
        bytes: ByteArray,
        preferUtf8: Boolean
    ): Pair<String, String> {
        val charsets = if (preferUtf8) {
            listOf(StandardCharsets.UTF_8, shiftJis)
        } else {
            listOf(shiftJis, StandardCharsets.UTF_8)
        }

        var lastError: CharacterCodingException? = null
        for (charset in charsets) {
            try {
                val text = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
                return text to charset.name()
            } catch (error: CharacterCodingException) {
                lastError = error
            }
        }

        throw IOException(
            "Script bytes are neither valid ${charsets[0].name()} nor ${charsets[1].name()} text.",
            lastError
        )
    }

    private fun rejectEmbeddedNul(bytes: ByteArray, sourceName: String) {
        if (bytes.any { it == 0.toByte() }) {
            throw IOException("Unexpected embedded NUL byte in script: $sourceName")
        }
    }

    companion object {
        private const val XOR_KEY = 0x84
        private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }
}

class UnsupportedNscripterScriptFormatException(
    val fileName: String,
    reason: String
) : IOException("Unsupported NScripter script format '$fileName': $reason.")
