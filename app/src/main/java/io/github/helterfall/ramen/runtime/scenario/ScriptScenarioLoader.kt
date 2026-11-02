package io.github.helterfall.ramen.runtime.scenario

import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Loads the explicitly synthetic Ramen Script v1 format into the engine-neutral
 * ScenarioProject model. It does not decode any external/proprietary script dialect.
 */
object ScriptScenarioLoader {
    private val MAGIC = byteArrayOf(
        0xEB.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        0xFE.toByte(), 0xFF.toByte(), 0x99.toByte(), 0x99.toByte()
    )

    private const val HEADER_SIZE = 8L
    private const val MAX_COMMANDS = 10_000
    private const val MAX_ADDRESS = 0xFFFF_FFFFL

    /**
     * The eight-byte magic is the complete file header. Command decoding starts
     * immediately after it; the following byte is an opcode, not a version or
     * encoding selector. The caller supplies the text charset because the stream
     * does not carry an encoding field.
     */
    fun load(stream: InputStream): ScenarioProject =
        load(stream, StandardCharsets.UTF_8)

    fun load(
        stream: InputStream,
        textCharset: Charset
    ): ScenarioProject {
        readAndValidateMagic(stream)

        val parser = ScriptCommandParser(textCharset, initialOffset = HEADER_SIZE)
        val instructions = mutableListOf<Instruction>()

        while (true) {
            val address = parser.nextCommandOffset
            val command = parser.parseNextCommand(stream) ?: break
            if (instructions.size >= MAX_COMMANDS) {
                throw ScriptParseException(address, "Script exceeds the $MAX_COMMANDS command limit.")
            }
            if (address > MAX_ADDRESS) {
                throw ScriptParseException(address, "Command address exceeds the 32-bit address space.")
            }
            instructions += Instruction(address, command)
        }

        if (instructions.none { it.command != NitroStartSceneCommand }) {
            throw ScriptParseException(HEADER_SIZE, "Script contains no executable commands.")
        }

        val knownAddresses = instructions.mapTo(mutableSetOf()) { it.address }
        instructions.forEach { instruction ->
            val jump = instruction.command as? JumpToSceneCommand
            if (jump != null && jump.targetAddress !in knownAddresses) {
                throw ScriptParseException(
                    instruction.address,
                    "Jump target ${jump.targetAddress} does not point to a command boundary."
                )
            }
        }

        val scenes = linkedMapOf<String, ScenarioScene>()
        instructions.forEachIndexed { index, instruction ->
            val sceneId = sceneId(instruction.address)
            val nextSceneId = instructions.getOrNull(index + 1)?.let { sceneId(it.address) }
            val commands = when (val command = instruction.command) {
                is DisplayDialogueCommand -> buildList<ScenarioCommand> {
                    add(ScenarioCommand.Dialogue(speaker = "", text = command.text))
                    if (nextSceneId != null) add(ScenarioCommand.Jump(nextSceneId))
                }

                is ChangeBackgroundCommand -> buildList<ScenarioCommand> {
                    add(ScenarioCommand.SetBackground(command.imagePath))
                    if (nextSceneId != null) add(ScenarioCommand.Jump(nextSceneId))
                }

                is JumpToSceneCommand -> listOf(
                    ScenarioCommand.Jump(sceneId(command.targetAddress))
                )

                NitroStartSceneCommand -> nextSceneId?.let {
                    listOf(ScenarioCommand.Jump(it))
                } ?: emptyList()
            }
            scenes[sceneId] = ScenarioScene(sceneId, commands)
        }

        return ScenarioProject(
            title = "Ramen binary Script",
            startSceneId = sceneId(instructions.first().address),
            scenes = scenes
        )
    }

    private fun sceneId(address: Long): String = "addr_$address"

    private fun readAndValidateMagic(stream: InputStream) {
        MAGIC.forEachIndexed { index, expected ->
            val actual = stream.read()
            if (actual < 0) {
                throw ScriptParseException(index.toLong(), "Unexpected EOF inside the 8-byte Script magic.")
            }
            if (actual != (expected.toInt() and 0xFF)) {
                throw ScriptParseException(index.toLong(), "Script magic does not match.")
            }
        }
    }

    private data class Instruction(
        val address: Long,
        val command: TextScriptCommand
    )
}
