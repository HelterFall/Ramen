package io.github.helterfall.ramen.runtime.hsp3

import io.github.helterfall.ramen.parser.Hsp3Program
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

data class Hsp3ScannedOpcode(val tokenType: Int, val opcodeId: Long, val count: Int)
data class Hsp3ScannedUnsupportedCommand(
    val tokenType: Int, val opcodeId: Int, val codeOffset: Int, val detail: String
)
data class Hsp3AxScanReport(
    val format: String,
    val hspVersion: Long,
    val opcodeCount: Int,
    val opcodes: List<Hsp3ScannedOpcode>,
    val unsupportedCommands: List<Hsp3ScannedUnsupportedCommand>
) {
    fun toJson(): String = buildString {
        append("{\n  \"format\": ").append(jsonString(format))
        append(",\n  \"hspVersion\": ").append(hspVersion)
        append(",\n  \"opcodeCount\": ").append(opcodeCount)
        append(",\n  \"opcodes\": [")
        opcodes.forEachIndexed { index, opcode ->
            if (index > 0) append(',')
            append("\n    {\"tokenType\": ").append(opcode.tokenType)
            append(", \"id\": ").append(opcode.opcodeId)
            append(", \"count\": ").append(opcode.count).append('}')
        }
        if (opcodes.isNotEmpty()) append("\n  ")
        append("],\n  \"unsupportedCommands\": [")
        unsupportedCommands.forEachIndexed { index, command ->
            if (index > 0) append(',')
            append("\n    {\"tokenType\": ").append(command.tokenType)
            append(", \"id\": ").append(command.opcodeId)
            append(", \"codeOffset\": ").append(command.codeOffset)
            append(", \"detail\": ").append(jsonString(command.detail)).append('}')
        }
        if (unsupportedCommands.isNotEmpty()) append("\n  ")
        append("]\n}\n")
    }

    @Throws(IOException::class)
    fun writeJson(file: File) {
        val parent = file.absoluteFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Could not create HSP SCAN output directory.")
        }
        file.writeText(toJson(), StandardCharsets.UTF_8)
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else append(character)
            }
        }
        append('"')
    }
}

object Hsp3AxStaticScanner {
    private const val EXFLG_1 = 0x2000
    private const val EXFLG_2 = 0x4000

    /** Static-only opcode inventory. No VM code executes and no assets are opened. */
    @Throws(IOException::class)
    fun scan(program: Hsp3Program): Hsp3AxScanReport {
        val tokens = try { program.tokens().toList() }
        catch (error: Exception) {
            throw IOException("HSP SCAN could not read the CS token stream: ${error.message}", error)
        }
        val heads = tokens.filter { token ->
            token.extensionFlags and EXFLG_1 != 0 && token.extensionFlags and EXFLG_2 == 0 &&
                token.type in setOf(
                    Hsp3BytecodeDecoder.TYPE_VAR,
                    Hsp3BytecodeDecoder.TYPE_INTCMD,
                    Hsp3BytecodeDecoder.TYPE_EXTCMD,
                    Hsp3BytecodeDecoder.TYPE_CMPCMD,
                    Hsp3BytecodeDecoder.TYPE_PROGCMD
                )
        }
        val counts = heads.groupingBy { it.type to it.value }.eachCount()
        val instructions = Hsp3BytecodeDecoder().decode(program)
        val unsupported = instructions.filterIsInstance<Hsp3Instruction.Unsupported>()
            .map { instruction ->
                Hsp3ScannedUnsupportedCommand(
                    instruction.commandType, instruction.commandId, instruction.codeOffset,
                    instruction.detail.ifBlank {
                        "Unsupported opcode type=${instruction.commandType} id=0x${instruction.commandId.toString(16)}"
                    }
                )
            }
            .distinctBy { Triple(it.tokenType, it.opcodeId, it.codeOffset) }
            .sortedWith(compareBy({ it.codeOffset }, { it.tokenType }, { it.opcodeId }))
        val opcodes = counts.entries.map { (key, count) ->
            Hsp3ScannedOpcode(key.first, key.second, count)
        }.sortedWith(compareBy({ it.tokenType }, { it.opcodeId }))
        return Hsp3AxScanReport("ramen-hsp3-scan-v1", program.header.version, heads.size, opcodes, unsupported)
    }
}
