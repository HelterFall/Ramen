package io.github.helterfall.ramen.nscripter

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.TreeMap
import java.util.stream.Collectors

data class NscripterArchiveSummary(
    val path: String,
    val format: String,
    val entryCount: Int,
    val compressedEntriesByType: Map<Int, Int>,
    val decodedSpbBitmapCount: Int = 0
)

data class NscripterUnsupportedDirective(
    val directive: String,
    val file: String,
    val count: Int,
    val lineNumbers: List<Int>
)

data class NscripterScanReport(
    val gameRoot: String,
    val scannedFileCount: Int,
    val scriptFiles: List<String>,
    val archives: List<NscripterArchiveSummary>,
    val commandCounts: Map<String, Int>,
    val graphicsFormats: Map<String, Int>,
    val audioFormats: Map<String, Int>,
    val unsupportedDirectives: List<NscripterUnsupportedDirective>,
    val parseErrors: List<String>
)

/**
 * Read-only static auditor. It does not execute scripts or load rendering/audio
 * components. Command frequencies come from command lines; resource formats are
 * inventoried from loose files and archive directory entries.
 */
class NscripterStaticScanner(
    private val scriptDecoder: NscripterScriptDecoder = NscripterScriptDecoder(),
    private val archiveReader: NscripterArchiveReader = NscripterArchiveReader()
) {
    fun scan(gameDirectory: File): NscripterScanReport {
        if (!gameDirectory.isDirectory || Files.isSymbolicLink(gameDirectory.toPath())) {
            throw IOException("The NScripter game directory is unavailable or is a symbolic link.")
        }

        val files = collectFiles(gameDirectory)
        val commandCounts = TreeMap<String, Int>()
        val graphicsFormats = TreeMap<String, Int>()
        val audioFormats = TreeMap<String, Int>()
        val archives = mutableListOf<NscripterArchiveSummary>()
        val indexedArchives = mutableListOf<Pair<File, NscripterArchiveIndex>>()
        val unsupported = linkedMapOf<Pair<String, String>, MutableList<Int>>()
        val parseErrors = mutableListOf<String>()

        for (file in files) {
            countResourceFormat(
                extension = file.extension,
                graphicsFormats = graphicsFormats,
                audioFormats = audioFormats
            )

            val extension = file.extension.lowercase(Locale.ROOT)
            val relativePath = relativePath(gameDirectory, file)
            if (extension == "___" && ENCRYPTED_NSA_FILE_PATTERN.matches(file.name.lowercase(Locale.ROOT))) {
                parseErrors += "$relativePath: encrypted NSA archive variant is recognized but not decoded."
                continue
            }
            if (extension != "nsa" && extension != "sar") continue
            try {
                val index = archiveReader.readIndex(file)
                indexedArchives += file to index
                val compressedCounts = TreeMap<Int, Int>()
                var decodedSpbBitmapCount = 0
                for (entry in index.entries) {
                    if (entry.compressionType != 0) {
                        compressedCounts[entry.compressionType] =
                            (compressedCounts[entry.compressionType] ?: 0) + 1
                    }
                    if (entry.compressionType == SPB_COMPRESSION &&
                        entry.name.substringAfterLast('.', "").equals("bmp", ignoreCase = true)
                    ) {
                        try {
                            archiveReader.readEntry(file, entry)
                            decodedSpbBitmapCount++
                        } catch (error: Exception) {
                            parseErrors += "$relativePath!/${entry.name}: SPB bitmap decompression failed: " +
                                (error.message ?: error.javaClass.simpleName)
                        }
                    }
                    countResourceFormat(
                        extension = entry.name.substringAfterLast('.', ""),
                        graphicsFormats = graphicsFormats,
                        audioFormats = audioFormats
                    )
                }
                archives += NscripterArchiveSummary(
                    path = relativePath,
                    format = index.format,
                    entryCount = index.entries.size,
                    compressedEntriesByType = compressedCounts,
                    decodedSpbBitmapCount = decodedSpbBitmapCount
                )
            } catch (error: Exception) {
                parseErrors += "$relativePath: archive index parsing failed: " +
                    (error.message ?: error.javaClass.simpleName)
            }
        }

        val scriptSources = mutableListOf<NscripterScriptSource>()
        val looseScriptFiles = try {
            scriptDecoder.findScriptFiles(gameDirectory)
        } catch (error: Exception) {
            parseErrors += "script discovery failed: ${error.message ?: error.javaClass.simpleName}"
            emptyList()
        }

        if (looseScriptFiles.isNotEmpty()) {
            for (scriptFile in looseScriptFiles) {
                val path = relativePath(gameDirectory, scriptFile)
                try {
                    val decoded = scriptDecoder.decodeScriptBytes(
                        scriptFile.name,
                        scriptFile.readBytes()
                    )
                    scriptSources += NscripterScriptSource(
                        relativePath = path,
                        text = decoded.text,
                        encoding = decoded.encoding,
                        mode = decoded.mode
                    )
                } catch (error: Exception) {
                    parseErrors += "$path: script decoding/parsing failed: " +
                        (error.message ?: error.javaClass.simpleName)
                }
            }
        } else {
            scriptSources += readScriptSourcesFromArchives(
                gameDirectory = gameDirectory,
                indexedArchives = indexedArchives,
                parseErrors = parseErrors
            )
        }

        for (source in scriptSources) {
            countCommands(
                sourcePath = source.relativePath,
                text = source.text,
                commandCounts = commandCounts,
                unsupported = unsupported
            )
        }

        val unsupportedReport = unsupported.entries
            .map { (key, lineNumbers) ->
                NscripterUnsupportedDirective(
                    directive = key.second,
                    file = key.first,
                    count = lineNumbers.size,
                    lineNumbers = lineNumbers.toList()
                )
            }
            .sortedWith(compareBy({ it.file }, { it.directive }))

        return NscripterScanReport(
            gameRoot = gameDirectory.name.ifBlank { "game" },
            scannedFileCount = files.size,
            scriptFiles = scriptSources.map { it.relativePath },
            archives = archives.sortedBy { it.path },
            commandCounts = commandCounts,
            graphicsFormats = graphicsFormats,
            audioFormats = audioFormats,
            unsupportedDirectives = unsupportedReport,
            parseErrors = parseErrors.sorted()
        )
    }

    fun toJson(report: NscripterScanReport): String = buildString {
        append("{\n")
        append("  \"engine\": \"NScripter/ONScripter\",\n")
        append("  \"gameRoot\": ")
        appendJsonString(report.gameRoot)
        append(",\n  \"scannedFileCount\": ${report.scannedFileCount},\n")

        append("  \"scriptFiles\": ")
        appendStringArray(report.scriptFiles)
        append(",\n")

        append("  \"archives\": [")
        if (report.archives.isNotEmpty()) append('\n')
        report.archives.forEachIndexed { index, archive ->
            append("    {\"path\": ")
            appendJsonString(archive.path)
            append(", \"format\": ")
            appendJsonString(archive.format)
            append(", \"entryCount\": ${archive.entryCount}, \"compressedEntriesByType\": ")
            appendIntIntMap(archive.compressedEntriesByType)
            append(", \"decodedSpbBitmapCount\": ${archive.decodedSpbBitmapCount}")
            append("}")
            if (index != report.archives.lastIndex) append(',')
            append('\n')
        }
        if (report.archives.isNotEmpty()) append("  ")
        append("],\n")

        append("  \"commandFrequency\": ")
        appendStringIntMap(report.commandCounts)
        append(",\n")

        append("  \"graphicsFormats\": ")
        appendStringIntMap(report.graphicsFormats)
        append(",\n")

        append("  \"audioFormats\": ")
        appendStringIntMap(report.audioFormats)
        append(",\n")

        append("  \"unsupportedDirectives\": [")
        if (report.unsupportedDirectives.isNotEmpty()) append('\n')
        report.unsupportedDirectives.forEachIndexed { index, directive ->
            append("    {\"directive\": ")
            appendJsonString(directive.directive)
            append(", \"file\": ")
            appendJsonString(directive.file)
            append(", \"count\": ${directive.count}, \"lines\": ")
            appendIntArray(directive.lineNumbers)
            append("}")
            if (index != report.unsupportedDirectives.lastIndex) append(',')
            append('\n')
        }
        if (report.unsupportedDirectives.isNotEmpty()) append("  ")
        append("],\n")

        append("  \"parseErrors\": ")
        appendStringArray(report.parseErrors)
        append('\n')
        append("}\n")
    }

    @Throws(IOException::class)
    fun writeJson(gameDirectory: File, outputFile: File): NscripterScanReport {
        val report = scan(gameDirectory)
        val parent = outputFile.absoluteFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Could not create JSON report directory: ${parent.path}")
        }

        val temporary = File(parent ?: File("."), outputFile.name + ".tmp")
        try {
            temporary.writeText(toJson(report), StandardCharsets.UTF_8)
            if (!temporary.renameTo(outputFile)) {
                outputFile.writeText(toJson(report), StandardCharsets.UTF_8)
                if (!temporary.delete()) temporary.deleteOnExit()
            }
        } catch (error: Exception) {
            temporary.delete()
            throw IOException("Could not write the NScripter audit JSON: ${error.message}", error)
        }
        return report
    }


    private fun readScriptSourcesFromArchives(
        gameDirectory: File,
        indexedArchives: List<Pair<File, NscripterArchiveIndex>>,
        parseErrors: MutableList<String>
    ): List<NscripterScriptSource> {
        val orderedArchives = indexedArchives.sortedWith(
            compareBy<Pair<File, NscripterArchiveIndex>>(
                { if (relativePath(gameDirectory, it.first).startsWith("patch/", ignoreCase = true)) 0 else 1 },
                { archivePriority(it.first.name) },
                { relativePath(gameDirectory, it.first) }
            )
        )

        for ((archiveFile, index) in orderedArchives) {
            val archivePath = relativePath(gameDirectory, archiveFile)
            val scriptEntries = index.entries.map { entry ->
                val normalizedName = entry.name.replace('\\\\', '/').trimStart('/')
                entry to normalizedName.substringAfterLast('/').lowercase(Locale.ROOT)
            }

            val entryPriority = listOf("0.utf", "00.utf", "0.txt", "00.txt", "nscript.dat")
            val mainEntry = entryPriority.firstNotNullOfOrNull { candidate ->
                scriptEntries.firstOrNull { (_, leafName) -> leafName == candidate }?.first
            } ?: continue

            val mainLeaf = mainEntry.name.replace('\\\\', '/').substringAfterLast('/').lowercase(Locale.ROOT)
            val extension = when {
                mainLeaf.endsWith(".utf") -> "utf"
                mainLeaf.endsWith(".txt") -> "txt"
                else -> "dat"
            }
            val selectedEntries = buildList {
                add(mainEntry)
                if (extension != "dat") {
                    for (number in 1..99) {
                        val unpadded = "$number.$extension"
                        val padded = number.toString().padStart(2, '0') + ".$extension"
                        val segment = scriptEntries.firstOrNull { (_, leafName) ->
                            leafName == unpadded
                        }?.first ?: scriptEntries.firstOrNull { (_, leafName) ->
                            leafName == padded
                        }?.first
                        if (segment != null) add(segment)
                    }
                }
            }

            val sources = mutableListOf<NscripterScriptSource>()
            for (entry in selectedEntries) {
                val sourcePath = "$archivePath!/${entry.name}"
                try {
                    val bytes = archiveReader.readEntry(archiveFile, entry)
                    val decoded = scriptDecoder.decodeScriptBytes(entry.name, bytes)
                    sources += NscripterScriptSource(
                        relativePath = sourcePath,
                        text = decoded.text,
                        encoding = decoded.encoding,
                        mode = decoded.mode
                    )
                } catch (error: Exception) {
                    parseErrors += "$sourcePath: archived script decoding/parsing failed: " +
                        (error.message ?: error.javaClass.simpleName)
                }
            }
            return sources
        }
        return emptyList()
    }

    private fun archivePriority(fileName: String): Int {
        if (fileName.equals("arc.nsa", ignoreCase = true)) return 0
        val match = EXTRA_ARCHIVE_NAME_PATTERN.matchEntire(fileName)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 100
    }

    private fun countCommands(
        sourcePath: String,
        text: String,
        commandCounts: MutableMap<String, Int>,
        unsupported: MutableMap<Pair<String, String>, MutableList<Int>>
    ) {
        text.lineSequence().forEachIndexed { lineIndex, sourceLine ->
            val line = removeComment(sourceLine).trim()
            if (line.isEmpty() || line.startsWith("*")) return@forEachIndexed

            var body = line
            var explicitDirective = false
            when (body.first()) {
                ':', '@' -> {
                    explicitDirective = true
                    body = body.substring(1).trimStart()
                }
                '#' -> {
                    explicitDirective = true
                    body = body.substring(1).trimStart()
                }
            }

            val token = body.takeWhile { it.isLetterOrDigit() || it == '_' || it == '!' }
            if (token.isEmpty()) return@forEachIndexed
            val command = token.lowercase(Locale.ROOT)
            if (command in KNOWN_COMMANDS) {
                commandCounts[command] = (commandCounts[command] ?: 0) + 1
            } else if (explicitDirective) {
                val key = sourcePath to command
                unsupported.getOrPut(key) { mutableListOf() } += lineIndex + 1
            }
        }
    }

    /**
     * Semicolon starts a comment only outside a quoted string. Backtick and
     * backslash escapes protect the following character inside strings.
     */
    private fun removeComment(line: String): String {
        var inQuote = false
        var escaped = false
        for (index in line.indices) {
            val character = line[index]
            if (inQuote) {
                if (escaped) {
                    escaped = false
                } else if (character == '\\' || character.toInt() == 96) {
                    escaped = true
                } else if (character == '"') {
                    inQuote = false
                }
            } else {
                when (character) {
                    '"' -> inQuote = true
                    ';' -> return line.substring(0, index)
                }
            }
        }
        return line
    }

    private fun collectFiles(root: File): List<File> {
        return Files.walk(root.toPath()).use { paths ->
            paths
                .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .map { it.toFile() }
                .sorted()
                .collect(Collectors.toList())
        }
    }

    private fun relativePath(root: File, file: File): String =
        file.relativeTo(root).invariantSeparatorsPath

    private fun countResourceFormat(
        extension: String,
        graphicsFormats: MutableMap<String, Int>,
        audioFormats: MutableMap<String, Int>
    ) {
        val normalized = extension.trim().trimStart('.').lowercase(Locale.ROOT)
        if (normalized in GRAPHICS_EXTENSIONS) {
            graphicsFormats[normalized] = (graphicsFormats[normalized] ?: 0) + 1
        } else if (normalized in AUDIO_EXTENSIONS) {
            audioFormats[normalized] = (audioFormats[normalized] ?: 0) + 1
        }
    }

    private fun StringBuilder.appendStringArray(values: List<String>) {
        append('[')
        values.forEachIndexed { index, value ->
            if (index > 0) append(", ")
            appendJsonString(value)
        }
        append(']')
    }

    private fun StringBuilder.appendIntArray(values: List<Int>) {
        append('[')
        values.forEachIndexed { index, value ->
            if (index > 0) append(", ")
            append(value)
        }
        append(']')
    }

    private fun StringBuilder.appendStringIntMap(values: Map<String, Int>) {
        append('{')
        values.entries.sortedBy { it.key }.forEachIndexed { index, entry ->
            if (index > 0) append(", ")
            appendJsonString(entry.key)
            append(": ${entry.value}")
        }
        append('}')
    }

    private fun StringBuilder.appendIntIntMap(values: Map<Int, Int>) {
        append('{')
        values.entries.sortedBy { it.key }.forEachIndexed { index, entry ->
            if (index > 0) append(", ")
            appendJsonString(entry.key.toString())
            append(": ${entry.value}")
        }
        append('}')
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (character.toInt() < 0x20) {
                        append("\\u")
                        append(character.toInt().toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
        }
        append('"')
    }

    companion object {
        private const val SPB_COMPRESSION = 1
        private val EXTRA_ARCHIVE_NAME_PATTERN = Regex("^arc([1-9])?\\.nsa$", RegexOption.IGNORE_CASE)
        private val GRAPHICS_EXTENSIONS = setOf(
            "bmp", "jpg", "jpeg", "png", "spb", "pdt", "gif", "tif", "tiff",
            "webp", "qnt", "msk"
        )
        private val ENCRYPTED_NSA_FILE_PATTERN = Regex("^arc[1-9]?\\.___$")
        private val AUDIO_EXTENSIONS = setOf(
            "wav", "ogg", "mp3", "mid", "midi", "flac", "m4a", "aac",
            "wma", "opus", "mp2"
        )
        private val KNOWN_COMMANDS = setOf(
            "add", "addf", "and", "avi", "aviout", "bg", "bgcopy", "bgm",
            "bgmfadein", "bgmfadeout", "bgmloop", "bgmonce", "bgmstop",
            "bgoverlay", "break", "btn", "btnwait", "caption", "cell", "csel",
            "csp", "csp2", "deffont", "definereset", "delay", "delete", "div",
            "dwave", "dwaveloop", "dwaveloop2", "dwaveload", "dwaveplay",
            "dwavestop", "dwavestopfadeout", "dwaveseek", "dwavevol", "else",
            "endif", "end", "endloop", "exbtn", "exbtn_d", "fileexist",
            "filelog", "for", "game", "getcursor", "getmousepos", "getparam",
            "getreg", "getversion", "goto", "gosub", "gosubstr", "if", "inc",
            "intlimit", "itoa", "itoa2", "labelexist", "len", "linepage",
            "loadgame", "locate", "lookbackcolor", "lookbackflush", "lookbackoff",
            "lookbackon", "lsp", "lsp2", "lsp2sub", "lspfont", "lspstr", "lsph",
            "msp", "msp2", "mul", "n", "next", "nsadir", "numalias", "ofscopy",
            "open", "page", "play", "playonce", "playstop", "print", "print2",
            "printbutton", "printstr", "prnum", "prnumclear", "puttext", "quake",
            "readfile", "readkey", "readreg", "readstr", "reset", "return", "rmenu",
            "rnd", "savefileexist", "savegame", "saveoff", "saveon", "savenumber",
            "savename", "savepoint", "savetime", "se", "seplay", "select",
            "selgosub", "setcursor", "setwindow", "setwindow3", "shell", "skip",
            "spbtn", "spchar", "spfont", "spstr", "stop", "stralias", "strcat",
            "strcmp", "strcpy", "strsp", "strsub", "sub", "textbtnwait", "title",
            "transmode", "transbtn", "tateyoko", "timer", "trap", "voicedelay",
            "wait", "waittimer", "wave", "waveloop", "wavestop", "windowerase",
            "windowback", "windoweffect", "windowlock", "windowunlock", "writefile",
            "writestr", "yesnobox", "zenkakko", "br", "cl", "clreload", "color",
            "cont", "deldigit", "delsp", "dim", "draw", "drawsp", "edit",
            "font", "fontcolor", "humanorder", "logsp", "lookbackbutton",
            "lookbacksp", "lookbackvoice", "nsa", "numalias2", "save"
        )
    }
}