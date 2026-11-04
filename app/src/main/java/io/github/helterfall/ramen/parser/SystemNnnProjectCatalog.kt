package io.github.helterfall.ramen.parser

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale

/**
 * One module declared by a System-NNN project manifest and its sibling SPT file.
 *
 * The manifest order is preserved for diagnostics and future module routing.
 * It is not treated as an execution order or an entrypoint declaration.
 */
data class SystemNnnProjectModule(
    val name: String,
    val scriptFile: File
)

/**
 * Read-only parser for the line-oriented project.xtx module catalog.
 *
 * This class validates names and resolves sibling SPT files only. It does not
 * infer which module starts the game or how modules transition between one
 * another.
 */
class SystemNnnProjectCatalog private constructor(
    val moduleNames: List<String>
) {

    fun resolveModules(projectFile: File): List<SystemNnnProjectModule> {
        if (!projectFile.isFile || Files.isSymbolicLink(projectFile.toPath())) {
            throw IOException("The System-NNN project manifest is not a regular file.")
        }

        val directory = projectFile.canonicalFile.parentFile
            ?: throw IOException("The System-NNN project manifest has no parent directory.")
        val children = directory.listFiles()
            ?: throw IOException("Could not list the System-NNN project directory.")

        return moduleNames.map { moduleName ->
            val matches = children.filter { candidate ->
                candidate.isFile &&
                    !Files.isSymbolicLink(candidate.toPath()) &&
                    candidate.extension.equals("spt", ignoreCase = true) &&
                    candidate.nameWithoutExtension.equals(moduleName, ignoreCase = true) &&
                    candidate.canonicalFile.parentFile == directory
            }

            val script = when (matches.size) {
                1 -> matches.single()
                0 -> throw IOException(
                    "The System-NNN project module '$moduleName' has no sibling SPT file."
                )
                else -> throw IOException(
                    "The System-NNN project module '$moduleName' matches multiple SPT files."
                )
            }
            SystemNnnProjectModule(moduleName, script)
        }
    }

    companion object {
        private const val MAX_PROJECT_FILE_BYTES = 64 * 1024L
        private const val MAX_MODULES = 256
        private val MODULE_NAME_PATTERN = Regex("[A-Za-z0-9_]{1,64}")

        @Throws(IOException::class)
        fun load(projectFile: File): SystemNnnProjectCatalog {
            if (!projectFile.isFile || Files.isSymbolicLink(projectFile.toPath())) {
                throw IOException("The System-NNN project manifest is not a regular file.")
            }
            if (projectFile.length() > MAX_PROJECT_FILE_BYTES) {
                throw IOException("The System-NNN project manifest exceeds the size limit.")
            }

            val bytes = projectFile.readBytes()
            if (bytes.size.toLong() > MAX_PROJECT_FILE_BYTES) {
                throw IOException("The System-NNN project manifest exceeds the size limit.")
            }

            val text = try {
                StandardCharsets.US_ASCII.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (failure: Exception) {
                throw IOException("The System-NNN project manifest is not valid ASCII.", failure)
            }

            val names = ArrayList<String>()
            val seen = HashSet<String>()
            text.split('\n').forEachIndexed { index, rawLine ->
                val name = rawLine.removeSuffix("\r")
                if (name.isEmpty()) return@forEachIndexed
                if (!MODULE_NAME_PATTERN.matches(name)) {
                    throw IOException(
                        "The System-NNN project manifest contains an invalid module name on line ${index + 1}."
                    )
                }

                val key = name.lowercase(Locale.ROOT)
                if (!seen.add(key)) {
                    throw IOException(
                        "The System-NNN project manifest contains a duplicate module name '$name'."
                    )
                }
                names += name
                if (names.size > MAX_MODULES) {
                    throw IOException("The System-NNN project manifest contains too many modules.")
                }
            }

            if (names.isEmpty()) {
                throw IOException("The System-NNN project manifest contains no modules.")
            }
            return SystemNnnProjectCatalog(names.toList())
        }
    }
}
