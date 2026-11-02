package io.github.helterfall.ramen.runtime.scenario

class ScenarioSession private constructor(
    private val project: ScenarioProject,
    restoredState: ScenarioSaveState?
) {
    private var sceneId: String = project.startSceneId
    private var commandIndex: Int = 0
    private val flags = linkedMapOf<String, Boolean>()
    private var backgroundResourceKey: String? = null
    private val characters = linkedMapOf<String, CharacterPosition>()
    private var dialogue: ScenarioCommand.Dialogue? = null
    private var choices: List<ScenarioChoice> = emptyList()
    private var ended: Boolean = false

    init {
        if (restoredState == null) {
            runUntilPause()
        } else {
            restoreFrom(restoredState)
        }
    }

    fun frame(): ScenarioFrame = ScenarioFrame(
        sceneId = sceneId,
        backgroundResourceKey = backgroundResourceKey,
        characters = characters.map { VisibleCharacter(it.key, it.value) },
        dialogue = dialogue,
        choices = choices.toList(),
        ended = ended
    )

    fun continueStory(): ScenarioFrame {
        check(!ended) { "История уже завершена." }
        check(choices.isEmpty()) { "Сначала нужно выбрать вариант." }
        check(dialogue != null) { "Сейчас нет реплики для продолжения." }
        dialogue = null
        runUntilPause()
        return frame()
    }

    fun choose(optionIndex: Int): ScenarioFrame {
        check(!ended) { "История уже завершена." }
        val selected = choices.getOrNull(optionIndex)
            ?: throw IllegalArgumentException("Такого варианта выбора нет.")
        choices = emptyList()
        sceneId = selected.targetSceneId
        commandIndex = 0
        runUntilPause()
        return frame()
    }

    fun saveState(): ScenarioSaveState = ScenarioSaveState(
        sceneId = sceneId,
        commandIndex = commandIndex,
        flags = flags.toMap(),
        backgroundResourceKey = backgroundResourceKey,
        characters = characters.map { VisibleCharacter(it.key, it.value) },
        dialogue = dialogue,
        choices = choices.toList(),
        ended = ended
    )

    private fun restoreFrom(state: ScenarioSaveState) {
        require(project.scenes.containsKey(state.sceneId)) { "Сохранение ссылается на неизвестную сцену." }
        val commands = project.scenes.getValue(state.sceneId).commands
        require(state.commandIndex in 0..commands.size) { "Некорректная позиция в сцене." }
        require(state.flags.size <= MAX_FLAGS) { "В сохранении слишком много флагов." }
        require(state.characters.size <= MAX_CHARACTERS) { "В сохранении слишком много персонажей." }
        require(state.characters.map { it.characterId }.distinct().size == state.characters.size) {
            "В сохранении повторяются персонажи."
        }
        require(state.dialogue == null || state.choices.isEmpty()) {
            "Сохранение не может одновременно ожидать реплику и выбор."
        }
        require(state.choices.size <= MAX_CHOICES) { "В сохранении слишком много вариантов выбора." }
        require(!state.ended || (state.dialogue == null && state.choices.isEmpty())) {
            "Завершённое сохранение не должно содержать ожидающих действий."
        }
        state.dialogue?.let { savedDialogue ->
            require(commands.getOrNull(state.commandIndex - 1) == savedDialogue) {
                "Сохранение содержит несовпадающую реплику."
            }
        }
        if (state.choices.isNotEmpty()) {
            val previous = commands.getOrNull(state.commandIndex - 1)
            require(previous is ScenarioCommand.Choices && previous.options == state.choices) {
                "Сохранение содержит несовпадающие варианты выбора."
            }
        }
        state.backgroundResourceKey?.let(::validateResourceKey)
        state.characters.forEach {
            require(it.characterId.matches(IDENTIFIER_REGEX)) { "Некорректный персонаж в сохранении." }
        }

        sceneId = state.sceneId
        commandIndex = state.commandIndex
        flags.putAll(state.flags)
        backgroundResourceKey = state.backgroundResourceKey
        state.characters.forEach { characters[it.characterId] = it.position }
        dialogue = state.dialogue
        choices = state.choices.toList()
        ended = state.ended
    }

    private fun runUntilPause() {
        var executed = 0
        while (!ended) {
            executed++
            check(executed <= MAX_AUTOMATIC_COMMANDS) {
                "Слишком длинная цепочка автоматических переходов."
            }

            val commands = project.scenes.getValue(sceneId).commands
            if (commandIndex >= commands.size) {
                ended = true
                return
            }

            when (val command = commands[commandIndex++]) {
                is ScenarioCommand.SetBackground -> backgroundResourceKey = command.resourceKey
                is ScenarioCommand.ShowCharacter -> characters[command.characterId] = command.position
                is ScenarioCommand.HideCharacter -> characters.remove(command.characterId)
                is ScenarioCommand.Dialogue -> {
                    dialogue = command
                    return
                }
                is ScenarioCommand.Choices -> {
                    choices = command.options
                    return
                }
                is ScenarioCommand.SetFlag -> flags[command.name] = command.value
                is ScenarioCommand.ConditionalJump -> {
                    if ((flags[command.flagName] ?: false) == command.expectedValue) {
                        sceneId = command.targetSceneId
                        commandIndex = 0
                    }
                }
                is ScenarioCommand.Jump -> {
                    sceneId = command.targetSceneId
                    commandIndex = 0
                }
                ScenarioCommand.End -> {
                    ended = true
                    return
                }
            }
        }
    }

    private fun validateResourceKey(value: String) {
        require(value.matches(RESOURCE_KEY_REGEX)) { "Некорректный ключ ресурса в сохранении." }
        require(value.split('/').none { it == "." || it == ".." }) {
            "Недопустимый путь ресурса в сохранении."
        }
    }

    companion object {
        private const val MAX_AUTOMATIC_COMMANDS = 10_000
        private const val MAX_FLAGS = 256
        private const val MAX_CHARACTERS = 128
        private const val MAX_CHOICES = 12
        private val IDENTIFIER_REGEX = Regex("^[A-Za-z][A-Za-z0-9_-]{0,63}$")
        private val RESOURCE_KEY_REGEX = Regex("^[\\p{L}\\p{N}_.-]{1,64}(/[\\p{L}\\p{N}_.-]{1,64})*$")

        fun start(project: ScenarioProject): ScenarioSession = ScenarioSession(project, null)

        fun restore(
            project: ScenarioProject,
            state: ScenarioSaveState
        ): ScenarioSession = ScenarioSession(project, state)
    }
}
