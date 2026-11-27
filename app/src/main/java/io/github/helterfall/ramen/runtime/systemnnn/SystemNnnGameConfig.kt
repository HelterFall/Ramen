package io.github.helterfall.ramen.runtime.systemnnn

import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Values read by native CGame from CNameList::LoadInit("game"). */
data class SystemNnnGameConfig(
    val frameTimeMillis: Int = DEFAULT_FRAME_TIME_MILLIS,
    /** Native CMyGraphics viewport size; auxiliary DWQ dimensions never redefine it. */
    val screenWidth: Int = DEFAULT_SCREEN_WIDTH,
    val screenHeight: Int = DEFAULT_SCREEN_HEIGHT,
    val maxSkipFrame: Int = DEFAULT_MAX_SKIP_FRAME,
    val maxSkipFrameBackScript: Int = DEFAULT_MAX_SKIP_FRAME,
    val skipAdjustFrame: Int = DEFAULT_SKIP_ADJUST_FRAME,
    val isConfigured: Boolean = false
) {
    companion object {
        const val DEFAULT_FRAME_TIME_MILLIS = 50
        const val DEFAULT_SCREEN_WIDTH = 800
        const val DEFAULT_SCREEN_HEIGHT = 600
        const val DEFAULT_MAX_SKIP_FRAME = 2
        const val DEFAULT_SKIP_ADJUST_FRAME = 10
        private const val MAX_SETUP_BYTES = 128 * 1024

        fun parse(text: String): SystemNnnGameConfig {
            val values = LinkedHashMap<String, Int>()
            text.lineSequence().forEach { line ->
                val content = line.substringBefore('#').substringBefore(';')
                val tokens = content.split(',', ' ', '\t', '\r')
                    .asSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
                var index = 0
                while (index < tokens.size) {
                    val token = tokens[index++]
                    val pair = token.split('=', limit = 2)
                    if (pair.size == 2) {
                        pair[1].toIntOrNull()?.let { values[pair[0].lowercase()] = it }
                    } else if (index < tokens.size) {
                        tokens[index++].toIntOrNull()?.let { values[token.lowercase()] = it }
                    }
                }
            }
            val maxSkip = (values["maxskipframe"] ?: DEFAULT_MAX_SKIP_FRAME).coerceAtLeast(1)
            val knownKeys = setOf(
                "frametime", "maxskipframe", "maxskipframebackscript", "skipadjustframe",
                "screenwidth", "screensizex", "logicalwidth",
                "screenheight", "screensizey", "logicalheight"
            )
            return SystemNnnGameConfig(
                frameTimeMillis = (values["frametime"] ?: DEFAULT_FRAME_TIME_MILLIS)
                    .takeIf { it > 0 } ?: DEFAULT_FRAME_TIME_MILLIS,
                screenWidth = (values["screenwidth"] ?: values["screensizex"] ?: values["logicalwidth"])
                    ?.takeIf { it in 1..4_096 } ?: DEFAULT_SCREEN_WIDTH,
                screenHeight = (values["screenheight"] ?: values["screensizey"] ?: values["logicalheight"])
                    ?.takeIf { it in 1..4_096 } ?: DEFAULT_SCREEN_HEIGHT,
                maxSkipFrame = maxSkip,
                maxSkipFrameBackScript = values["maxskipframebackscript"] ?: maxSkip,
                skipAdjustFrame = values["skipadjustframe"] ?: DEFAULT_SKIP_ADJUST_FRAME,
                isConfigured = values.keys.any(knownKeys::contains)
            )
        }

        /** Mirrors CNameList::LoadInit("game"): init2/game.fxf XOR 0xff, then init/game.xtx. */
        @Throws(IOException::class)
        fun load(installRoot: File): SystemNnnGameConfig {
            val root = installRoot.canonicalFile
            if (!root.isDirectory) throw IOException("Native game configuration root is not a directory.")

            fun childDirectory(name: String): File? {
                val matches = root.listFiles()?.filter { candidate ->
                    candidate.isDirectory && candidate.name.equals(name, ignoreCase = true) &&
                        !Files.isSymbolicLink(candidate.toPath()) && candidate.canonicalFile.parentFile == root
                }.orEmpty()
                if (matches.size > 1) throw IOException("Multiple '$name' setup directories are ambiguous.")
                return matches.singleOrNull()?.canonicalFile
            }

            fun setupFile(directory: File?, filename: String): File? {
                if (directory == null) return null
                val matches = directory.listFiles()?.filter { candidate ->
                    candidate.isFile && candidate.name.equals(filename, ignoreCase = true) &&
                        !Files.isSymbolicLink(candidate.toPath()) && candidate.canonicalFile.parentFile == directory
                }.orEmpty()
                if (matches.size > 1) throw IOException("Multiple '$filename' setup files are ambiguous.")
                val file = matches.singleOrNull() ?: return null
                if (file.length() > MAX_SETUP_BYTES) throw IOException("Native $filename exceeds the setup-file size limit.")
                return file
            }

            val encrypted = setupFile(childDirectory("init2"), "game.fxf")
            if (encrypted != null) {
                val decoded = encrypted.readBytes().also { bytes ->
                    for (i in bytes.indices) bytes[i] = (bytes[i].toInt() xor 0xFF).toByte()
                }
                return parse(SystemNnnLegacyText.decode(decoded))
            }
            val plain = setupFile(childDirectory("init"), "game.xtx") ?: return SystemNnnGameConfig()
            return parse(SystemNnnLegacyText.decode(plain.readBytes()))
        }
    }
}
