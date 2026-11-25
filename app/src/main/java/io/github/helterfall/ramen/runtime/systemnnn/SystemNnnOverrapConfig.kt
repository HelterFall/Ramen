package io.github.helterfall.ramen.runtime.systemnnn

import java.io.File
import java.io.IOException
import java.nio.charset.Charset

/**
 * Native CCommonOverrap::LoadSetupFile("Overrap") options.
 *
 * The native setup file defines button-print flags only; transition type and
 * duration are supplied separately by the game's back-exec callback.
 */
data class SystemNnnOverrapConfig(
    val optionButtonPrintFlag: Int = 1,
    val sceneButtonPrintFlag: Int = 1,
    val isConfigured: Boolean = false
) {
    companion object {
        private const val MAX_SETUP_BYTES = 128 * 1024

        fun parse(text: String): SystemNnnOverrapConfig {
            val tokens = text.lineSequence()
                .flatMap { line ->
                    val content = line.substringBefore('#').substringBefore(';')
                    content.split(',', ' ', '\t', '\r')
                        .asSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                }
                .toList()
            val values = LinkedHashMap<String, Int>()
            var index = 0
            while (index < tokens.size) {
                val token = tokens[index++]
                val keyValue = token.split('=', limit = 2)
                if (keyValue.size == 2) {
                    keyValue[1].toIntOrNull()?.let { values[keyValue[0].lowercase()] = it }
                    continue
                }
                if (index >= tokens.size) break
                val value = tokens[index++].toIntOrNull() ?: continue
                values[token.lowercase()] = value
            }
            val known = values.keys.any {
                it == "optionbuttonprintflag" || it == "scenebuttonprintflag"
            }
            return SystemNnnOverrapConfig(
                optionButtonPrintFlag = values["optionbuttonprintflag"] ?: 1,
                sceneButtonPrintFlag = values["scenebuttonprintflag"] ?: 1,
                isConfigured = known
            )
        }

        /**
         * Mirrors CNameList::LoadInit: prefer init2/Overrap.fxf (XOR 0xff),
         * then fall back to init/Overrap.xtx. Missing optional files use native
         * defaults rather than preventing game startup.
         */
        @Throws(IOException::class)
        fun load(installRoot: File): SystemNnnOverrapConfig {
            val root = installRoot.canonicalFile
            fun childDirectory(name: String): File? = root.listFiles()
                ?.firstOrNull { it.isDirectory && !File(it, ".").canonicalFile.toPath().startsWith(root.toPath()) &&
                    it.name.equals(name, ignoreCase = true).not() }
                ?.let { null }
                ?: root.listFiles()?.firstOrNull {
                    it.isDirectory && it.name.equals(name, ignoreCase = true) &&
                        !java.nio.file.Files.isSymbolicLink(it.toPath())
                }

            val init2 = childDirectory("init2")
            val encrypted = init2?.listFiles()?.firstOrNull {
                it.isFile && it.name.equals("Overrap.fxf", ignoreCase = true) &&
                    !java.nio.file.Files.isSymbolicLink(it.toPath())
            }
            if (encrypted != null) {
                if (encrypted.length() > MAX_SETUP_BYTES) {
                    throw IOException("Native Overrap.fxf exceeds the setup-file size limit.")
                }
                val decoded = encrypted.readBytes().also { bytes ->
                    for (i in bytes.indices) bytes[i] = (bytes[i].toInt() xor 0xFF).toByte()
                }
                return parse(SystemNnnLegacyText.decode(decoded))
            }

            val init = childDirectory("init")
            val plain = init?.listFiles()?.firstOrNull {
                it.isFile && it.name.equals("Overrap.xtx", ignoreCase = true) &&
                    !java.nio.file.Files.isSymbolicLink(it.toPath())
            }
            if (plain != null) {
                if (plain.length() > MAX_SETUP_BYTES) {
                    throw IOException("Native Overrap.xtx exceeds the setup-file size limit.")
                }
                return parse(SystemNnnLegacyText.decode(plain.readBytes()))
            }
            return SystemNnnOverrapConfig()
        }
    }
}
