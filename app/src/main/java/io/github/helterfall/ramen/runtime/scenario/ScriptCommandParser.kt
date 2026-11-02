package io.github.helterfall.ramen.runtime.scenario

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

sealed interface TextScriptCommand

data class DisplayDialogueCommand(val text: String) : TextScriptCommand
data class ChangeBackgroundCommand(val imagePath: String) : TextScriptCommand
data class JumpToSceneCommand(val targetAddress: Long) : TextScriptCommand

class ScriptParseException(
    val byteOffset: Long,
    message: String
) : IOException("Script byte $byteOffset: $message")

/**
 * Decodes one command at a time from the stream immediately following the eight-byte
 * script magic. The caller owns the InputStream and must keep using the same parser
 * instance for consecutive calls so byte offsets remain stable.
 */
class ScriptCommandParser(
    private val textCharset: Charset = StandardCharsets.UTF_8,
    initialOffset: Long = 0L
) {
    var nextCommandOffset: Long = initialOffset
        private set

    init {
        require(initialOffset >= 0L) { "Initial byte offset must not be negative." }
    }

    /**
     * Returns null only when EOF occurs between commands. EOF inside an opcode operand
     * or string payload is malformed and throws ScriptParseException.
     */
    @Throws(IOException::class)
    fun parseNextCommand(stream: InputStream): TextScriptCommand? {
        val commandOffset = nextCommandOffset
        val opcode = stream.read()
        if (opcode < 0) return null
        nextCommandOffset++

        return when (opcode) {
            OPCODE_DISPLAY_DIALOGUE -> {
                val text = readText(stream, "dialogue", commandOffset)
                if (text.isBlank()) {
                    throw ScriptParseException(commandOffset, "Dialogue text must not be blank.")
                }
                if (text.length > MAX_DIALOGUE_CHARS) {
                    throw ScriptParseException(commandOffset, "Dialogue text exceeds the character limit.")
                }
                DisplayDialogueCommand(text)
            }

            OPCODE_CHANGE_BACKGROUND -> {
                val imagePath = readText(stream, "background path", commandOffset)
                validateImagePath(imagePath, commandOffset)
                ChangeBackgroundCommand(imagePath)
            }

            OPCODE_JUMP_TO_SCENE -> {
                val target = readUnsignedInt32(stream, "jump target", commandOffset)
                JumpToSceneCommand(target)
            }

            else -> throw ScriptParseException(
                commandOffset,
                "Unknown command opcode 0x%02X.".format(opcode)
            )
        }
    }

    private fun readText(stream: InputStream, field: String, commandOffset: Long): String {
        val byteLength = readUnsignedInt16(stream, "$field length", commandOffset)
        if (byteLength == 0) {
            throw ScriptParseException(commandOffset, "$field must not be empty.")
        }
        if (byteLength > MAX_STRING_BYTES) {
            throw ScriptParseException(
                commandOffset,
                "$field is $byteLength bytes; maximum is $MAX_STRING_BYTES."
            )
        }

        val bytes = ByteArray(byteLength)
        readFully(stream, bytes, field, commandOffset)
        return try {
            textCharset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (exception: CharacterCodingException) {
            throw ScriptParseException(
                commandOffset,
                "$field is not valid ${textCharset.name()} text."
            )
        }
    }

    private fun validateImagePath(path: String, commandOffset: Long) {
        val validSyntax = path.matches(IMAGE_PATH_PATTERN)
        val safeSegments = path.split('/').none { it == "." || it == ".." }
        if (!validSyntax || !safeSegments) {
            throw ScriptParseException(
                commandOffset,
                "Background path must be a safe relative resource path."
            )
        }
    }

    private fun readUnsignedInt16(
        stream: InputStream,
        field: String,
        commandOffset: Long
    ): Int {
        val low = readRequiredByte(stream, field, commandOffset)
        val high = readRequiredByte(stream, field, commandOffset)
        return low or (high shl 8)
    }

    private fun readUnsignedInt32(
        stream: InputStream,
        field: String,
        commandOffset: Long
    ): Long {
        val b0 = readRequiredByte(stream, field, commandOffset).toLong()
        val b1 = readRequiredByte(stream, field, commandOffset).toLong()
        val b2 = readRequiredByte(stream, field, commandOffset).toLong()
        val b3 = readRequiredByte(stream, field, commandOffset).toLong()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun readRequiredByte(
        stream: InputStream,
        field: String,
        commandOffset: Long
    ): Int {
        val value = stream.read()
        if (value < 0) {
            throw ScriptParseException(
                nextCommandOffset,
                "Unexpected EOF while reading $field for command at byte $commandOffset."
            )
        }
        nextCommandOffset++
        return value
    }

    private fun readFully(
        stream: InputStream,
        target: ByteArray,
        field: String,
        commandOffset: Long
    ) {
        var written = 0
        while (written < target.size) {
            val count = stream.read(target, written, target.size - written)
            if (count < 0) {
                throw ScriptParseException(
                    nextCommandOffset,
                    "Unexpected EOF while reading $field for command at byte $commandOffset."
                )
            }
            if (count == 0) {
                target[written++] = readRequiredByte(stream, field, commandOffset).toByte()
            } else {
                written += count
                nextCommandOffset += count
            }
        }
    }

    companion object {
        const val OPCODE_DISPLAY_DIALOGUE = 0x01
        const val OPCODE_CHANGE_BACKGROUND = 0x02
        const val OPCODE_JUMP_TO_SCENE = 0x03

        const val MAX_STRING_BYTES = 4_096
        const val MAX_DIALOGUE_CHARS = 4_096

        private val IMAGE_PATH_PATTERN =
            Regex("^[\\p{L}\\p{N}_.-]{1,64}(/[\\p{L}\\p{N}_.-]{1,64})*$")
    }
}
