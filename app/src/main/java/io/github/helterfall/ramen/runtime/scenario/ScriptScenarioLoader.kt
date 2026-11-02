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
     * Loads only the documented synthetic Ramen Script v1 fixture. This is not
     * a parser for the proprietary Black Cyc SPT container/header.
     */
    fun load(stream: InputStream): ScenarioProject =
        load(stream, StandardCharsets.UTF_8, commandStreamLengthBytes = null)

    fun load(
        stream: InputStream,
        textCharset: Charset
    ): ScenarioProject = load(stream, textCharset, commandStreamLengthBytes = null)

    /**
     * Optionally bounds the synthetic command region when its end is independently
     * known. This parameter does not infer or parse a proprietary SPT offset table.
     */
    fun load(
        stream: InputStream,
        textCharset: Charset,
        commandStreamLengthBytes: Long?
    ): ScenarioProject {
        require(commandStreamLengthBytes == null || commandStreamLengthBytes >= 0L) {
            "Command stream length must not be negative."
        }
        readAndValidateMagic(stream)

        val boundedCommandInput = commandStreamLengthBytes?.let {
            BoundedCommandInputStream(stream, it)
        }
        val commandInput = boundedCommandInput ?: stream
        val parser = ScriptCommandParser(
            textCharset,
            initialOffset = HEADER_SIZE
        )
        val instructions = mutableListOf<Instruction>()

        while (true) {
            val address = parser.nextCommandOffset
            val command = parser.parseNextCommand(commandInput) ?: break
            if (instructions.size >= MAX_COMMANDS) {
                throw ScriptParseException(address, "Script exceeds the $MAX_COMMANDS command limit.")
            }
            if (address > MAX_ADDRESS) {
                throw ScriptParseException(address, "Command address exceeds the 32-bit address space.")
            }
            instructions += Instruction(address, command)
        }
        if (boundedCommandInput != null && boundedCommandInput.remainingBytes != 0L) {
            throw ScriptParseException(
                parser.nextCommandOffset,
                "Command section ended before its declared byte length."
            )
        }

        if (instructions.isEmpty()) {
            throw ScriptParseException(HEADER_SIZE, "Script contains no commands.")
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

    /**
     * Limits only reads from the command section and deliberately does not close
     * the caller-owned source. The first byte after the limit remains unread.
     */
    private class BoundedCommandInputStream(
        private val source: InputStream,
        byteLimit: Long
    ) : InputStream() {
        var remainingBytes: Long = byteLimit
            private set

        override fun read(): Int {
            if (remainingBytes == 0L) return -1
            val value = source.read()
            if (value >= 0) remainingBytes--
            return value
        }

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remainingBytes == 0L) return -1
            val boundedLength = minOf(length.toLong(), remainingBytes).toInt()
            val count = source.read(target, offset, boundedLength)
            if (count > 0) remainingBytes -= count.toLong()
            return count
        }

        override fun skip(count: Long): Long {
            if (count <= 0L || remainingBytes == 0L) return 0L
            val skipped = source.skip(minOf(count, remainingBytes))
            remainingBytes -= skipped
            return skipped
        }

        override fun available(): Int =
            minOf(source.available().toLong(), remainingBytes, Int.MAX_VALUE.toLong()).toInt()
    }

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
