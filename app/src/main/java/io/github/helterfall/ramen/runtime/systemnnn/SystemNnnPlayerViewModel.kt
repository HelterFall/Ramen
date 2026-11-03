package io.github.helterfall.ramen.runtime.systemnnn

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.github.helterfall.ramen.parser.SystemNnnScript
import java.io.Closeable
import java.io.IOException

data class SystemNnnSpriteBitmap(
    val resourceId: String,
    val bitmap: Bitmap,
    val centerXFraction: Float = 0.5f,
    val widthFraction: Float = 0.62f,
    val heightFraction: Float = 0.82f,
    val contentDescription: String? = null
)

data class SystemNnnChoiceOption(
    val id: String,
    val label: String,
    val targetCommandOffset: Long
)

data class SystemNnnPlayerUiState(
    val frameId: Long = 0L,
    val isLoading: Boolean = false,
    val backgroundBitmap: Bitmap? = null,
    val sprites: List<SystemNnnSpriteBitmap> = emptyList(),
    val speakerName: String? = null,
    val dialogueText: String = "",
    val visibleCharacterCount: Int = 0,
    val isTextComplete: Boolean = true,
    val forceReveal: Boolean = false,
    val choices: List<SystemNnnChoiceOption> = emptyList(),
    val isEnded: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Resource lookup contract used by scene commands. The GPK implementation
 * resolves a GTB name, table index, or absolute GPK block offset to a Bitmap.
 */
fun interface SystemNnnSceneAssetResolver : Closeable {
    @Throws(IOException::class)
    fun loadBitmap(resourceId: String): Bitmap?

    override fun close() = Unit
}

/**
 * The observed file layout does not yet define a verified System-NNN opcode
 * and operand grammar. Keep bytecode interpretation behind this interface:
 * implementations must consume bounded command bytes and return the next
 * command boundary, rather than treating each byte as an independent opcode.
 */
fun interface SystemNnnCommandDecoder {
    @Throws(IOException::class)
    fun decodeNext(
        script: SystemNnnScript,
        commandOffset: Long
    ): SystemNnnDecodedCommand
}

sealed interface SystemNnnDecodedCommand {
    data class Dialogue(
        val nextCommandOffset: Long,
        val textEntryOffset: Long,
        val speakerName: String? = null,
        val backgroundResourceId: String? = null,
        val sprites: List<SystemNnnSpriteReference> = emptyList()
    ) : SystemNnnDecodedCommand

    data class SceneUpdate(
        val nextCommandOffset: Long,
        val backgroundResourceId: String? = null,
        val sprites: List<SystemNnnSpriteReference> = emptyList()
    ) : SystemNnnDecodedCommand

    data class Choice(
        val promptTextEntryOffset: Long,
        val options: List<SystemNnnChoiceOption>,
        val speakerName: String? = null
    ) : SystemNnnDecodedCommand

    data object End : SystemNnnDecodedCommand
}

data class SystemNnnSpriteReference(
    val resourceId: String,
    val centerXFraction: Float = 0.5f,
    val widthFraction: Float = 0.62f,
    val heightFraction: Float = 0.82f,
    val contentDescription: String? = null
)

/**
 * Lifecycle-aware player state machine.
 *
 * A verified [SystemNnnCommandDecoder] is injected by the engine adapter.
 * Without one, the ViewModel intentionally refuses to advance guessed opcodes.
 */
class SystemNnnPlayerViewModel : ViewModel() {
    var uiState by mutableStateOf(SystemNnnPlayerUiState())
        private set

    private var script: SystemNnnScript? = null
    private var decoder: SystemNnnCommandDecoder? = null
    private var assets: SystemNnnSceneAssetResolver? = null
    private var nextCommandOffset: Long = 0L
    private var frameCounter: Long = 0L

    fun start(
        script: SystemNnnScript,
        commandDecoder: SystemNnnCommandDecoder?,
        assetResolver: SystemNnnSceneAssetResolver,
        initialCommandOffset: Long = script.commandStartOffset
    ) {
        releaseAssets()
        this.script = script
        this.decoder = commandDecoder
        this.assets = assetResolver
        this.nextCommandOffset = initialCommandOffset
        this.frameCounter = 0L

        uiState = SystemNnnPlayerUiState(isLoading = true)

        if (commandDecoder == null) {
            uiState = uiState.copy(
                isLoading = false,
                errorMessage = "Загрузчик SPT прочитал заголовок и текстовый пул, " +
                    "но проверенный декодер команд System-NNN ещё не подключён."
            )
            return
        }

        advanceToVisibleEvent()
    }

    /**
     * A tap first completes the current typewriter line. Only a subsequent tap
     * asks the command decoder for the next event.
     */
    fun onScreenTap() {
        val current = uiState
        if (current.isLoading || current.isEnded || current.choices.isNotEmpty()) return

        if (!current.isTextComplete) {
            uiState = current.copy(
                visibleCharacterCount = current.dialogueText.length,
                isTextComplete = true,
                forceReveal = true
            )
            return
        }

        advanceToVisibleEvent()
    }

    fun onTypewriterProgress(visibleCharacters: Int) {
        val current = uiState
        if (current.isTextComplete || current.forceReveal) return
        uiState = current.copy(
            visibleCharacterCount = visibleCharacters.coerceIn(0, current.dialogueText.length)
        )
    }

    fun onTypewriterFinished() {
        val current = uiState
        if (current.isTextComplete) return
        uiState = current.copy(
            visibleCharacterCount = current.dialogueText.length,
            isTextComplete = true
        )
    }

    fun onChoiceSelected(choiceId: String) {
        val choice = uiState.choices.firstOrNull { it.id == choiceId } ?: return
        val currentScript = script ?: return
        if (!isValidCommandOffset(currentScript, choice.targetCommandOffset)) {
            uiState = uiState.copy(
                errorMessage = "Вариант выбора указывает за пределы командной области."
            )
            return
        }

        nextCommandOffset = choice.targetCommandOffset
        uiState = uiState.copy(choices = emptyList(), errorMessage = null, isLoading = true)
        advanceToVisibleEvent()
    }

    private fun advanceToVisibleEvent() {
        val currentScript = script
        val currentDecoder = decoder
        val resolver = assets

        if (currentScript == null || currentDecoder == null || resolver == null) {
            uiState = uiState.copy(
                isLoading = false,
                errorMessage = "Игровая сессия не инициализирована."
            )
            return
        }

        uiState = uiState.copy(isLoading = true, errorMessage = null)

        try {
            var sceneBackground = uiState.backgroundBitmap
            var sceneSprites = uiState.sprites
            var commandsWithoutVisibleOutput = 0

            while (commandsWithoutVisibleOutput < MAX_SCENE_COMMANDS_PER_ADVANCE) {
                val commandOffset = nextCommandOffset
                if (!isValidCommandOffset(currentScript, commandOffset)) {
                    throw IOException("Command cursor left the validated SPT command region.")
                }

                when (val command = currentDecoder.decodeNext(currentScript, commandOffset)) {
                    is SystemNnnDecodedCommand.SceneUpdate -> {
                        validateNextOffset(currentScript, commandOffset, command.nextCommandOffset)
                        nextCommandOffset = command.nextCommandOffset
                        sceneBackground = command.backgroundResourceId?.let(resolver::loadBitmap)
                            ?: if (command.backgroundResourceId == null) sceneBackground else {
                                throw IOException("Background resource was not found.")
                            }
                        sceneSprites = resolveSprites(command.sprites, resolver)
                        commandsWithoutVisibleOutput += 1
                    }

                    is SystemNnnDecodedCommand.Dialogue -> {
                        validateNextOffset(currentScript, commandOffset, command.nextCommandOffset)
                        val text = currentScript.textEntryAtOffset(command.textEntryOffset)
                            ?: throw IOException(
                            "Dialogue points to an unknown Shift-JIS text-pool offset."
                        )
                        nextCommandOffset = command.nextCommandOffset

                        if (command.backgroundResourceId != null) {
                            sceneBackground = resolver.loadBitmap(command.backgroundResourceId)
                                ?: throw IOException("Background resource was not found.")
                        }
                        if (command.sprites.isNotEmpty()) {
                            sceneSprites = resolveSprites(command.sprites, resolver)
                        }

                        frameCounter += 1L
                        uiState = SystemNnnPlayerUiState(
                            frameId = frameCounter,
                            isLoading = false,
                            backgroundBitmap = sceneBackground,
                            sprites = sceneSprites,
                            speakerName = command.speakerName,
                            dialogueText = text.text,
                            visibleCharacterCount = 0,
                            isTextComplete = text.text.isEmpty(),
                            forceReveal = false
                        )
                        return
                    }

                    is SystemNnnDecodedCommand.Choice -> {
                        val prompt = currentScript.textEntryAtOffset(command.promptTextEntryOffset)
                            ?: throw IOException(
                            "Choice prompt points to an unknown Shift-JIS text-pool offset."
                        )
                        if (command.options.isEmpty()) {
                            throw IOException("Choice command has no selectable options.")
                        }
                        if (command.options.map { it.id }.distinct().size != command.options.size) {
                            throw IOException("Choice command contains duplicate option IDs.")
                        }
                        command.options.forEach { option ->
                            if (!isValidCommandOffset(currentScript, option.targetCommandOffset)) {
                                throw IOException("Choice target is outside the command region.")
                            }
                        }

                        frameCounter += 1L
                        uiState = SystemNnnPlayerUiState(
                            frameId = frameCounter,
                            isLoading = false,
                            backgroundBitmap = sceneBackground,
                            sprites = sceneSprites,
                            speakerName = command.speakerName,
                            dialogueText = prompt.text,
                            visibleCharacterCount = prompt.text.length,
                            isTextComplete = true,
                            forceReveal = true,
                            choices = command.options
                        )
                        return
                    }

                    SystemNnnDecodedCommand.End -> {
                        uiState = uiState.copy(
                            isLoading = false,
                            isEnded = true,
                            choices = emptyList(),
                            errorMessage = null
                        )
                        return
                    }
                }
            }

            throw IOException("Too many scene-only commands without a dialogue or choice.")
        } catch (error: Exception) {
            uiState = uiState.copy(
                isLoading = false,
                errorMessage = error.message ?: "Не удалось декодировать следующую команду."
            )
        }
    }

    private fun resolveSprites(
        references: List<SystemNnnSpriteReference>,
        resolver: SystemNnnSceneAssetResolver
    ): List<SystemNnnSpriteBitmap> = references.map { reference ->
        val bitmap = resolver.loadBitmap(reference.resourceId)
            ?: throw IOException("Character sprite resource was not found.")
        SystemNnnSpriteBitmap(
            resourceId = reference.resourceId,
            bitmap = bitmap,
            centerXFraction = reference.centerXFraction,
            widthFraction = reference.widthFraction,
            heightFraction = reference.heightFraction,
            contentDescription = reference.contentDescription
        )
    }

    private fun validateNextOffset(
        currentScript: SystemNnnScript,
        currentOffset: Long,
        nextOffset: Long
    ) {
        if (!isValidCommandOffset(currentScript, nextOffset) || nextOffset == currentOffset) {
            throw IOException("Decoded command has an invalid next offset.")
        }
    }

    private fun isValidCommandOffset(currentScript: SystemNnnScript, offset: Long): Boolean =
        offset >= currentScript.commandStartOffset && offset < currentScript.commandEndOffset

    private fun releaseAssets() {
        runCatching { assets?.close() }
        assets = null
    }

    override fun onCleared() {
        releaseAssets()
        super.onCleared()
    }

    companion object {
        private const val MAX_SCENE_COMMANDS_PER_ADVANCE = 2_048
    }
}
