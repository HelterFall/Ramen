package io.github.helterfall.ramen.runtime.systemnnn

import java.nio.charset.Charset

/** Native System-NNN XTX and MML byte strings use Japanese Windows CP932. */
internal object SystemNnnLegacyText {
    private val windows31j: Charset by lazy {
        runCatching { Charset.forName("windows-31j") }
            .getOrElse { Charset.forName("Shift_JIS") }
    }

    fun decode(bytes: ByteArray): String {
        val hasUtf8Bom = bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        return if (hasUtf8Bom) String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        else String(bytes, windows31j)
    }
}
