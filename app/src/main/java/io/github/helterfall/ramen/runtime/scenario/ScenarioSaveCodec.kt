package io.github.helterfall.ramen.runtime.scenario

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

object ScenarioSaveCodec {
    private const val MAGIC = 0x52414D53 // RAMS
    private const val VERSION = 1
    private const val MAX_SAVE_BYTES = 128 * 1024
    private const val MAX_FLAGS = 256
    private const val MAX_CHARACTERS = 128
    private const val MAX_CHOICES = 12
    private const val MAX_STRING_LENGTH = 4_096

    fun encode(state: ScenarioSaveState): ByteArray {
        require(state.flags.size <= MAX_FLAGS) { "Слишком много флагов для сохранения." }
        require(state.characters.size <= MAX_CHARACTERS) { "Слишком много персонажей для сохранения." }
        require(state.choices.size <= MAX_CHOICES) { "Слишком много вариантов для сохранения." }

        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeUTF(state.sceneId)
            output.writeInt(state.commandIndex)

            output.writeInt(state.flags.size)
            state.flags.toSortedMap().forEach { (name, value) ->
                output.writeUTF(name)
                output.writeBoolean(value)
            }

            output.writeNullableString(state.backgroundResourceKey)

            output.writeInt(state.characters.size)
            state.characters.forEach { character ->
                output.writeUTF(character.characterId)
                output.writeInt(character.position.ordinal)
            }

            output.writeBoolean(state.dialogue != null)
            state.dialogue?.let { item ->
                output.writeUTF(item.speaker)
                output.writeUTF(item.text)
            }

            output.writeInt(state.choices.size)
            state.choices.forEach { choice ->
                output.writeUTF(choice.label)
                output.writeUTF(choice.targetSceneId)
            }
            output.writeBoolean(state.ended)
        }

        val result = bytes.toByteArray()
        require(result.size <= MAX_SAVE_BYTES) { "Файл сохранения превышает допустимый размер." }
        return result
    }

    fun decode(bytes: ByteArray): ScenarioSaveState {
        require(bytes.size <= MAX_SAVE_BYTES) { "Файл сохранения слишком большой." }
        require(bytes.size >= 12) { "Файл сохранения слишком короткий." }

        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC) { "Неверная сигнатура сохранения." }
            require(input.readInt() == VERSION) { "Неподдерживаемая версия сохранения." }

            val sceneId = input.readUTF().checkedLength()
            val commandIndex = input.readInt()
            require(commandIndex >= 0) { "Некорректная позиция в сохранении." }

            val flagCount = input.readInt()
            require(flagCount in 0..MAX_FLAGS) { "Некорректное число флагов." }
            val flags = linkedMapOf<String, Boolean>()
            repeat(flagCount) {
                val key = input.readUTF().checkedLength()
                require(!flags.containsKey(key)) { "Повторяющийся флаг в сохранении." }
                flags[key] = input.readBoolean()
            }

            val background = input.readNullableString()?.checkedLength()

            val characterCount = input.readInt()
            require(characterCount in 0..MAX_CHARACTERS) { "Некорректное число персонажей." }
            val characters = mutableListOf<VisibleCharacter>()
            repeat(characterCount) {
                val id = input.readUTF().checkedLength()
                val positionIndex = input.readInt()
                val position = CharacterPosition.entries.getOrNull(positionIndex)
                    ?: throw IllegalArgumentException("Некорректное положение персонажа.")
                characters += VisibleCharacter(id, position)
            }

            val dialogue = if (input.readBoolean()) {
                ScenarioCommand.Dialogue(
                    input.readUTF().checkedLength(),
                    input.readUTF().checkedLength()
                )
            } else {
                null
            }

            val choiceCount = input.readInt()
            require(choiceCount in 0..MAX_CHOICES) { "Некорректное число вариантов выбора." }
            val choices = mutableListOf<ScenarioChoice>()
            repeat(choiceCount) {
                choices += ScenarioChoice(
                    input.readUTF().checkedLength(),
                    input.readUTF().checkedLength()
                )
            }
            val ended = input.readBoolean()
            require(input.available() == 0) { "В сохранении обнаружены лишние данные." }

            return ScenarioSaveState(
                sceneId = sceneId,
                commandIndex = commandIndex,
                flags = flags,
                backgroundResourceKey = background,
                characters = characters,
                dialogue = dialogue,
                choices = choices,
                ended = ended
            )
        }
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeUTF(value)
    }

    private fun DataInputStream.readNullableString(): String? =
        if (readBoolean()) readUTF() else null

    private fun String.checkedLength(): String {
        require(length <= MAX_STRING_LENGTH) { "Строка в сохранении слишком длинная." }
        return this
    }
}
