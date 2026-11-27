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
 * Decodes the script formats loaded by the classic NScripter/ONScripter path:
 * plain 0.txt (or 00.txt) plus 1.txt..99.txt (with two-digit fallbacks), and nscript.dat XOR 0x84.
 *
 * ONScripter has other protected formats (for example nscr_sec.dat,
 * nscript.___, onscript.nt2 and onscript.nt3). They are intentionally not
 * guessed or decoded here: callers receive an explicit unsupported-format error.
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
        if (bytes.isEmpty()) {
            return NscripterDecodedScript("", "UTF-8", "plain-text")
        }

        val hasUtf8Bom = bytes.size >= UTF8_BOM.size &&
            UTF8_BOM.indices.all { bytes[it] == UTF8_BOM[it] }
        val content = if (hasUtf8Bom) bytes.copyOfRange(UTF8_BOM.size, bytes.size) else bytes
        rejectEmbeddedNul(content, file.name)
        val (text, encoding) = decodeStrict(content, preferUtf8 = true)
        return NscripterDecodedScript(
            text = text,
            encoding = encoding,
            mode = "plain-text"
        )
    }

    /**
     * Uses the same entry-file preference as ONScripter: 0.txt wins over 00.txt;
     * numbered text segments 1.txt..99.txt are appended. If neither entry exists,
     * nscript.dat is used.
     */
    fun findScriptFiles(gameDirectory: File): List<File> {
        if (!gameDirectory.isDirectory) {
            throw IOException("The NScripter game directory is unavailable: ${gameDirectory.path}")
        }

        val zero = File(gameDirectory, "0.txt")
        val doubleZero = File(gameDirectory, "00.txt")
        val entryScript = when {
            zero.isFile -> zero
            doubleZero.isFile -> doubleZero
            else -> null
        }

        if (entryScript != null) {
            return buildList {
                add(entryScript)
                for (number in 1..99) {
                    val unpadded = File(gameDirectory, "$number.txt")
                    val padded = File(gameDirectory, number.toString().padStart(2, '0') + ".txt")
                    val part = when {
                        unpadded.isFile -> unpadded
                        padded.isFile -> padded
                        else -> null
                    }
                    if (part != null) add(part)
                }
            }
        }

        // ONScripter gives these protected formats precedence over nscript.dat.
        // Refuse to parse a lower-priority file with the wrong cipher.
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
            val decoded = if (file.name.equals("nscript.dat", ignoreCase = true)) {
                decodeNscriptDat(file)
            } else {
                decodeTextFile(file)
            }
            NscripterScriptSource(
                relativePath = file.relativeTo(gameDirectory).invariantSeparatorsPath,
                text = decoded.text,
                encoding = decoded.encoding,
                mode = decoded.mode
            )
        }
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

class UnsupportedNscripterScriptFormatException(
    val fileName: String,
    reason: String
) : IOException("Unsupported NScripter script format '$fileName': $reason.")