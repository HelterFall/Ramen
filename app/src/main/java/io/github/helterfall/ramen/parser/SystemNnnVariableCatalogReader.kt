package io.github.helterfall.ramen.parser

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.nio.file.Files

/**
 * Ordered variable-name table loaded from the XOR-0xFF encoded spt/var.fxf.
 *
 * Variable IDs used by CALCU are direct zero-based positions in this list.
 * The reference game runtime starts a new game with all variables zero, then
 * initializes date=101 and year=2000 before executing sys.spt.
 */
data class SystemNnnVariableCatalog(
    val names: List<String>
) {
    private val indexes: Map<String, Int> =
        names.mapIndexed { index, name -> name to index }.toMap()

    fun indexOf(name: String): Int? = indexes[name]

    fun newGameDefaults(): Map<Int, Int> = buildMap {
        indexOf("date")?.let { put(it, DEFAULT_START_DATE) }
        indexOf("year")?.let { put(it, DEFAULT_START_YEAR) }
    }

    companion object {
        const val DEFAULT_START_DATE = 101
        const val DEFAULT_START_YEAR = 2000
    }
}

/**
 * Reads the same token sequence used by CNameList::LoadFile(..., TRUE):
 * XOR each byte with 0xFF, then split on comma and ASCII whitespace.
 */
class SystemNnnVariableCatalogReader(
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val maxVariables: Int = DEFAULT_MAX_VARIABLES,
    private val maxNameBytes: Int = DEFAULT_MAX_NAME_BYTES
) {
    init {
        require(maxFileBytes > 0L)
        require(maxVariables > 0)
        require(maxNameBytes > 0)
    }

    @Throws(IOException::class)
    fun read(file: File): SystemNnnVariableCatalog {
        if (!file.isFile || Files.isSymbolicLink(file.toPath())) {
            throw IOException("spt/var.fxf is not a regular file.")
        }
        if (file.length() > maxFileBytes) {
            throw IOException("spt/var.fxf exceeds the configured size limit.")
        }

        val masked = try {
            file.readBytes()
        } catch (failure: Exception) {
            throw IOException("Could not read spt/var.fxf.", failure)
        }
        if (masked.size.toLong() > maxFileBytes) {
            throw IOException("spt/var.fxf exceeds the configured size limit.")
        }

        val decoded = ByteArray(masked.size) { index ->
            (masked[index].toInt() xor 0xFF).toByte()
        }
        val names = ArrayList<String>()
        val token = ByteArrayOutputStreamBounded(maxNameBytes)

        fun finishToken() {
            if (token.size == 0) return
            val name = try {
                SHIFT_JIS.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(token.toByteArray()))
                    .toString()
            } catch (failure: Exception) {
                throw IOException("spt/var.fxf contains an invalid Shift-JIS variable name.", failure)
            }
            if (name.isEmpty() || name.any { it.isISOControl() }) {
                throw IOException("spt/var.fxf contains an invalid variable name.")
            }
            names += name
            if (names.size > maxVariables) {
                throw IOException("spt/var.fxf contains too many variables.")
            }
            token.clear()
        }

        decoded.forEach { byte ->
            val value = byte.toInt() and 0xFF
            if (value == COMMA || value == SPACE || value == TAB ||
                value == CR || value == LF
            ) {
                finishToken()
            } else {
                token.write(value)
            }
        }
        finishToken()

        if (names.isEmpty()) {
            throw IOException("spt/var.fxf contains no variable names.")
        }
        if (names.distinct().size != names.size) {
            throw IOException("spt/var.fxf contains duplicate variable names.")
        }
        return SystemNnnVariableCatalog(names.toList())
    }

    /** Small fixed-capacity token buffer to keep the parser bounded. */
    private class ByteArrayOutputStreamBounded(
        private val limit: Int
    ) {
        private val bytes = ByteArray(limit)
        var size: Int = 0
            private set

        fun write(value: Int) {
            if (size >= limit) {
                throw IOException("spt/var.fxf contains an excessively long variable name.")
            }
            bytes[size++] = value.toByte()
        }

        fun toByteArray(): ByteArray = bytes.copyOf(size)

        fun clear() {
            size = 0
        }
    }

    private companion object {
        const val DEFAULT_MAX_FILE_BYTES = 1024L * 1024L
        const val DEFAULT_MAX_VARIABLES = 10_000
        const val DEFAULT_MAX_NAME_BYTES = 512
        const val COMMA = 0x2C
        const val SPACE = 0x20
        const val TAB = 0x09
        const val CR = 0x0D
        const val LF = 0x0A
        val SHIFT_JIS: Charset = Charset.forName("Shift_JIS")
    }
}
