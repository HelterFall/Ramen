package io.github.helterfall.ramen.runtime.scenario

class ScenarioParseException(
    val lineNumber: Int,
    message: String
) : IllegalArgumentException("Строка $lineNumber: $message")

object ScenarioParser {
    private const val MAX_SOURCE_CHARS = 1_000_000
    private const val MAX_LINES = 20_000
    private const val MAX_SCENES = 1_000
    private const val MAX_COMMANDS_PER_SCENE = 10_000
    private const val MAX_TEXT_LENGTH = 4_096
    private const val MAX_CHOICE_LABEL_LENGTH = 160
    private const val MAX_CHOICES = 12
    private const val IDENTIFIER = "[A-Za-z][A-Za-z0-9_-]{0,63}"
    private val identifierRegex = Regex("^$IDENTIFIER$")
    private val setFlagRegex = Regex("^($IDENTIFIER)\\s*=\\s*(true|false)$")
    private val branchRegex = Regex("^($IDENTIFIER)\\s*==\\s*(true|false)\\s*->\\s*($IDENTIFIER)$")

    fun parse(source: String): ScenarioProject {
        if (source.length > MAX_SOURCE_CHARS) throw ScenarioParseException(1, "Сценарий превышает лимит размера.")
        val lines = source.split('\n')
        if (lines.size > MAX_LINES) throw ScenarioParseException(1, "Слишком много строк.")

        var title: String? = null
        var startSceneId: String? = null
        var currentSceneId: String? = null
        var currentCommands: MutableList<ScenarioCommand>? = null
        val pendingChoices = mutableListOf<ScenarioChoice>()
        val scenes = linkedMapOf<String, ScenarioScene>()
        val seenSceneIds = mutableSetOf<String>()

        fun fail(lineNumber: Int, message: String): Nothing =
            throw ScenarioParseException(lineNumber, message)

        fun flushChoices(lineNumber: Int) {
            if (pendingChoices.isEmpty()) return
            if (pendingChoices.size > MAX_CHOICES) {
                fail(lineNumber, "В одной точке выбора допускается не более $MAX_CHOICES вариантов.")
            }
            val commands = currentCommands ?: fail(lineNumber, "Варианты выбора должны находиться внутри сцены.")
            if (commands.size >= MAX_COMMANDS_PER_SCENE) fail(lineNumber, "В сцене слишком много команд.")
            commands += ScenarioCommand.Choices(pendingChoices.toList())
            pendingChoices.clear()
        }

        fun addCommand(command: ScenarioCommand, lineNumber: Int) {
            val commands = currentCommands ?: fail(lineNumber, "Команда должна находиться внутри сцены.")
            if (commands.size >= MAX_COMMANDS_PER_SCENE) fail(lineNumber, "В сцене слишком много команд.")
            commands += command
        }

        for (index in lines.indices) {
            val lineNumber = index + 1
            val line = lines[index].trim()
            if (line.isEmpty() || line.startsWith("#")) continue

            val command = line.substringBefore(' ').lowercase()
            val arguments = if (line.contains(' ')) line.substringAfter(' ').trim() else ""

            when (command) {
                "title" -> {
                    if (currentSceneId != null) fail(lineNumber, "title должен быть объявлен до первой сцены.")
                    if (title != null) fail(lineNumber, "Повторное объявление title.")
                    title = parseOneQuoted(arguments, lineNumber).also {
                        if (it.isBlank() || it.length > MAX_TEXT_LENGTH) fail(lineNumber, "Некорректная длина названия.")
                    }
                }
                "start" -> {
                    if (currentSceneId != null) fail(lineNumber, "start должен быть объявлен до первой сцены.")
                    if (startSceneId != null) fail(lineNumber, "Повторное объявление start.")
                    startSceneId = parseIdentifier(arguments, lineNumber)
                }
                "scene" -> {
                    flushChoices(lineNumber)
                    val sceneId = parseIdentifier(arguments, lineNumber)
                    if (!seenSceneIds.add(sceneId)) fail(lineNumber, "Повторная сцена '$sceneId'.")
                    if (seenSceneIds.size >= MAX_SCENES) fail(lineNumber, "Слишком много сцен.")
                    currentSceneId?.let { oldId ->
                        scenes[oldId] = ScenarioScene(oldId, currentCommands.orEmpty().toList())
                    }
                    currentSceneId = sceneId
                    currentCommands = mutableListOf()
                }
                "choice" -> {
                    if (currentSceneId == null) fail(lineNumber, "choice должна находиться внутри сцены.")
                    val (label, remainder) = parseQuoted(arguments, 0, lineNumber)
                    if (label.isBlank() || label.length > MAX_CHOICE_LABEL_LENGTH) {
                        fail(lineNumber, "Некорректная длина текста выбора.")
                    }
                    val arrowAndTarget = remainder.trim()
                    if (!arrowAndTarget.startsWith("->")) {
                        fail(lineNumber, "Ожидается формат: choice \"текст\" -> scene_id.")
                    }
                    val target = parseIdentifier(arrowAndTarget.drop(2).trim(), lineNumber)
                    pendingChoices += ScenarioChoice(label, target)
                    if (pendingChoices.size > MAX_CHOICES) {
                        fail(lineNumber, "В одной точке выбора допускается не более $MAX_CHOICES вариантов.")
                    }
                }
                else -> {
                    flushChoices(lineNumber)
                    if (currentSceneId == null) fail(lineNumber, "Сначала объяви сцену командой scene.")
                    val parsed = when (command) {
                        "background" -> {
                            val key = parseOneQuoted(arguments, lineNumber)
                            validateResourceKey(key, lineNumber)
                            ScenarioCommand.SetBackground(key)
                        }
                        "show" -> {
                            val (characterId, remainder) = parseQuoted(arguments, 0, lineNumber)
                            validateIdentifier(characterId, lineNumber)
                            val match = Regex("^\\s+at\\s+(left|center|right)$", RegexOption.IGNORE_CASE)
                                .matchEntire(remainder)
                                ?: fail(lineNumber, "Ожидается формат: show \"character\" at left|center|right.")
                            val position = when (match.groupValues[1].lowercase()) {
                                "left" -> CharacterPosition.LEFT
                                "center" -> CharacterPosition.CENTER
                                else -> CharacterPosition.RIGHT
                            }
                            ScenarioCommand.ShowCharacter(characterId, position)
                        }
                        "hide" -> {
                            val characterId = parseOneQuoted(arguments, lineNumber)
                            validateIdentifier(characterId, lineNumber)
                            ScenarioCommand.HideCharacter(characterId)
                        }
                        "say" -> {
                            val (speaker, afterSpeaker) = parseQuoted(arguments, 0, lineNumber)
                            val (dialogueText, remainder) = parseQuoted(afterSpeaker, 0, lineNumber)
                            if (speaker.isBlank() || speaker.length > 80) fail(lineNumber, "Некорректное имя говорящего.")
                            if (dialogueText.isBlank() || dialogueText.length > MAX_TEXT_LENGTH) fail(lineNumber, "Некорректная длина реплики.")
                            if (remainder.isNotBlank()) fail(lineNumber, "Лишние данные после say.")
                            ScenarioCommand.Dialogue(speaker, dialogueText)
                        }
                        "set" -> {
                            val match = setFlagRegex.matchEntire(arguments)
                                ?: fail(lineNumber, "Ожидается формат: set flag_name = true|false.")
                            ScenarioCommand.SetFlag(match.groupValues[1], match.groupValues[2].toBoolean())
                        }
                        "if" -> {
                            val match = branchRegex.matchEntire(arguments)
                                ?: fail(lineNumber, "Ожидается формат: if flag_name == true -> scene_id.")
                            ScenarioCommand.ConditionalJump(
                                match.groupValues[1],
                                match.groupValues[2].toBoolean(),
                                match.groupValues[3]
                            )
                        }
                        "jump" -> ScenarioCommand.Jump(parseIdentifier(arguments, lineNumber))
                        "end" -> {
                            if (arguments.isNotBlank()) fail(lineNumber, "Команда end не принимает аргументы.")
                            ScenarioCommand.End
                        }
                        else -> fail(lineNumber, "Неизвестная команда '$command'.")
                    }
                    addCommand(parsed, lineNumber)
                }
            }
        }

        flushChoices(lines.size.coerceAtLeast(1))
        currentSceneId?.let { sceneId ->
            scenes[sceneId] = ScenarioScene(sceneId, currentCommands.orEmpty().toList())
        }

        val finalTitle = title ?: "Без названия"
        val finalStart = startSceneId
            ?: throw ScenarioParseException(1, "Не указана стартовая сцена командой start.")
        if (scenes.isEmpty()) throw ScenarioParseException(1, "Сценарий не содержит сцен.")
        if (!scenes.containsKey(finalStart)) {
            throw ScenarioParseException(1, "Стартовая сцена '$finalStart' не существует.")
        }

        scenes.values.forEach { scene ->
            scene.commands.forEach { item ->
                val targets = when (item) {
                    is ScenarioCommand.Choices -> item.options.map { it.targetSceneId }
                    is ScenarioCommand.ConditionalJump -> listOf(item.targetSceneId)
                    is ScenarioCommand.Jump -> listOf(item.targetSceneId)
                    else -> emptyList()
                }
                targets.forEach { target ->
                    if (!scenes.containsKey(target)) {
                        throw ScenarioParseException(
                            1,
                            "Сцена '" + scene.id + "' ссылается на отсутствующую '" + target + "'."
                        )
                    }
                }
            }
        }

        return ScenarioProject(finalTitle, finalStart, scenes.toMap())
    }

    private fun parseIdentifier(value: String, lineNumber: Int): String {
        val trimmed = value.trim()
        validateIdentifier(trimmed, lineNumber)
        return trimmed
    }

    private fun validateIdentifier(value: String, lineNumber: Int) {
        if (!identifierRegex.matches(value)) {
            throw ScenarioParseException(lineNumber, "Некорректный идентификатор '$value'.")
        }
    }

    private fun validateResourceKey(value: String, lineNumber: Int) {
        val valid = value.matches(Regex("^[A-Za-z0-9_.-]{1,64}(/[A-Za-z0-9_.-]{1,64})*$")) &&
            value.split('/').none { it == "." || it == ".." }
        if (!valid) throw ScenarioParseException(lineNumber, "Некорректный ключ ресурса.")
    }

    private fun parseOneQuoted(value: String, lineNumber: Int): String {
        val (parsed, remainder) = parseQuoted(value, 0, lineNumber)
        if (remainder.isNotBlank()) {
            throw ScenarioParseException(lineNumber, "Лишние данные после строкового аргумента.")
        }
        return parsed
    }

    private fun parseQuoted(input: String, start: Int, lineNumber: Int): Pair<String, String> {
        var index = start
        while (index < input.length && input[index].isWhitespace()) index++
        if (index >= input.length || input[index] != '"') {
            throw ScenarioParseException(lineNumber, "Ожидается строка в двойных кавычках.")
        }
        index++
        val result = StringBuilder()
        var closed = false

        while (index < input.length) {
            val char = input[index++]
            when (char) {
                '"' -> {
                    closed = true
                    break
                }
                '\\' -> {
                    if (index >= input.length) {
                        throw ScenarioParseException(lineNumber, "Незавершённая escape-последовательность.")
                    }
                    when (val escaped = input[index++]) {
                        '"' -> result.append('"')
                        '\\' -> result.append('\\')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        else -> throw ScenarioParseException(
                            lineNumber,
                            "Неподдерживаемая escape-последовательность: \\$escaped."
                        )
                    }
                }
                else -> result.append(char)
            }
            if (result.length > MAX_TEXT_LENGTH) {
                throw ScenarioParseException(lineNumber, "Строковый аргумент слишком длинный.")
            }
        }

        if (!closed) throw ScenarioParseException(lineNumber, "Не закрыта строка в кавычках.")
        return result.toString() to input.substring(index)
    }
}
