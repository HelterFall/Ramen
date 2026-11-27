package io.github.helterfall.ramen.nscripter

import java.io.IOException

data class NscripterInstruction(
    val command: String,
    val arguments: String,
    val sourceLine: Int,
    val speaker: String? = null
)

data class NscripterScriptProgram(
    val instructions: List<NscripterInstruction>,
    val labels: Map<String, Int>,
    val defineInstruction: Int?,
    val startInstruction: Int,
    val screenWidth: Int,
    val screenHeight: Int
) {
    companion object {
        val supportedCommands = setOf(
            "bg", "lsp", "lsph", "csp", "print", "windowback", "windoweffect",
            "setwindow", "btn", "spbtn", "exbtn", "exbtn_d", "btnwait",
            "textbtnwait", "csel", "rmenu", "dwave", "dwaveloop", "dwavestop",
            "stop", "numalias", "intlimit", "add", "if", "for", "next", "goto",
            "gosub", "return", "wait", "caption", "game", "end", "nsa",
            "savefileexist", "savenumber", "saveon", "savetime"
        )

        @Throws(IOException::class)
        fun parse(source: String): NscripterScriptProgram {
            val instructions = ArrayList<NscripterInstruction>()
            val labels = LinkedHashMap<String, Int>()
            var defineInstruction: Int? = null
            var startInstruction = -1
            var speaker: String? = null

            source.lineSequence().forEachIndexed { lineIndex, original ->
                var line = original.trim()
                if (line.isBlank() || line.startsWith(";")) return@forEachIndexed
                if (line.startsWith("#") && !line.startsWith("#(")) {
                    speaker = line.drop(1).trim().takeIf { it.isNotBlank() }
                    return@forEachIndexed
                }
                if (line.startsWith("*")) {
                    val label = line.drop(1).trim().substringBefore(' ').lowercase()
                    if (label.isNotBlank()) {
                        if (labels.put(label, instructions.size) != null) {
                            throw IOException("Duplicate NScripter label '*" + label + "' at line " + (lineIndex + 1))
                        }
                        if (label == "define") defineInstruction = instructions.size
                        if (label == "start") startInstruction = instructions.size
                    }
                    return@forEachIndexed
                }

                val inline = line.startsWith(":")
                if (inline) line = line.drop(1)
                val statements = if (inline) splitStatements(line) else listOf(line)
                for (statementValue in statements) {
                    var statement = statementValue.trim()
                    if (statement.isEmpty() || statement.startsWith(";")) continue
                    val forced = statement.startsWith("@")
                    if (forced) statement = statement.drop(1).trim()
                    val match = Regex("^([A-Za-z_][A-Za-z0-9_]*)(?:\\s+(.*))?$")
                        .matchEntire(statement)
                    val command = match?.groupValues?.get(1)?.lowercase()
                    if (command != null && command in supportedCommands) {
                        instructions += NscripterInstruction(
                            command,
                            match.groupValues.getOrElse(2) { "" }.trim(),
                            lineIndex + 1,
                            speaker
                        )
                    } else if (forced) {
                        throw IOException("Unsupported NScripter command at line " + (lineIndex + 1) + ": " + statement)
                    } else {
                        if (statement.startsWith(">")) statement = statement.drop(1)
                        if (statement.endsWith("@")) statement = statement.dropLast(1)
                        if (statement.isNotBlank()) {
                            instructions += NscripterInstruction("text", statement, lineIndex + 1, speaker)
                        }
                    }
                }
            }

            if (instructions.isEmpty()) throw IOException("NScripter script contains no executable lines.")
            val mode800 = Regex("(?im)^\\s*;mode800\\b").containsMatchIn(source)
            return NscripterScriptProgram(
                instructions = instructions,
                labels = labels,
                defineInstruction = defineInstruction,
                startInstruction = if (startInstruction >= 0) startInstruction else 0,
                screenWidth = if (mode800) 800 else 640,
                screenHeight = if (mode800) 600 else 480
            )
        }

        private fun splitStatements(value: String): List<String> {
            val parts = ArrayList<String>()
            var quote: Char? = null
            var escaped = false
            var start = 0
            value.forEachIndexed { index, char ->
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (quote != null && char == quote) quote = null
                else if (quote == null && (char == '"' || char.code == 96)) quote = char
                else if (quote == null && char == ':') {
                    parts += value.substring(start, index)
                    start = index + 1
                }
            }
            parts += value.substring(start)
            return parts
        }
    }
}

internal fun splitNscripterArguments(value: String): List<String> {
    if (value.isBlank()) return emptyList()
    val result = ArrayList<String>()
    var quote: Char? = null
    var depth = 0
    var escaped = false
    var start = 0
    value.forEachIndexed { index, char ->
        if (escaped) escaped = false
        else if (char == '\\') escaped = true
        else if (quote != null && char == quote) quote = null
        else if (quote == null && (char == '"' || char.code == 96)) quote = char
        else if (quote == null && char == '(') depth++
        else if (quote == null && char == ')') depth = (depth - 1).coerceAtLeast(0)
        else if (quote == null && depth == 0 && char == ',') {
            result += value.substring(start, index).trim()
            start = index + 1
        }
    }
    result += value.substring(start).trim()
    return result
}

internal fun nscripterUnquote(value: String): String {
    val trimmed = value.trim()
    return if (trimmed.length >= 2 &&
        ((trimmed.first() == '"' && trimmed.last() == '"') ||
            (trimmed.first().code == 96 && trimmed.last().code == 96))
    ) trimmed.substring(1, trimmed.length - 1) else trimmed
}
