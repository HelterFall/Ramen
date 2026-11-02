package io.github.helterfall.ramen.runtime.scenario

data class ScenarioProject(
    val title: String,
    val startSceneId: String,
    val scenes: Map<String, ScenarioScene>
)

data class ScenarioScene(val id: String, val commands: List<ScenarioCommand>)

enum class CharacterPosition { LEFT, CENTER, RIGHT }

data class ScenarioChoice(val label: String, val targetSceneId: String)

sealed interface ScenarioCommand {
    data class SetBackground(val resourceKey: String) : ScenarioCommand
    data class ShowCharacter(val characterId: String, val position: CharacterPosition) : ScenarioCommand
    data class HideCharacter(val characterId: String) : ScenarioCommand
    data class Dialogue(val speaker: String, val text: String) : ScenarioCommand
    data class Choices(val options: List<ScenarioChoice>) : ScenarioCommand
    data class SetFlag(val name: String, val value: Boolean) : ScenarioCommand
    data class ConditionalJump(
        val flagName: String,
        val expectedValue: Boolean,
        val targetSceneId: String
    ) : ScenarioCommand
    data class Jump(val targetSceneId: String) : ScenarioCommand
    data object End : ScenarioCommand
}

data class VisibleCharacter(val characterId: String, val position: CharacterPosition)

data class ScenarioFrame(
    val sceneId: String,
    val backgroundResourceKey: String?,
    val characters: List<VisibleCharacter>,
    val dialogue: ScenarioCommand.Dialogue?,
    val choices: List<ScenarioChoice>,
    val ended: Boolean
)

data class ScenarioSaveState(
    val sceneId: String,
    val commandIndex: Int,
    val flags: Map<String, Boolean>,
    val backgroundResourceKey: String?,
    val characters: List<VisibleCharacter>,
    val dialogue: ScenarioCommand.Dialogue?,
    val choices: List<ScenarioChoice>,
    val ended: Boolean
)
