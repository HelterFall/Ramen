package io.github.helterfall.ramen.runtime.systemnnn

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.github.helterfall.ramen.parser.SystemNnnScript
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.IdentityHashMap

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

enum class NovelState {
    Preparing,
    Executing,
    PlaybackWaiting,
    ChoiceWaiting,
    Ended,
    Failed
}

data class SystemNnnPlayerUiState(
    val frameId: Long = 0L,
    val novelState: NovelState = NovelState.Preparing,
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
    val errorMessage: String? = null,
    val audioWarning: String? = null
)

/**
 * Resource lookup contract used by scene commands. Scene resources may be
 * requested by GTB name, by an explicit GTB table index, or by block offset.
 */
fun interface SystemNnnSceneAssetResolver : Closeable {
    @Throws(IOException::class)
    fun loadBitmap(resourceId: String): Bitmap?

    /** Resolves a zero-based row in the already-validated GTB catalog. */
    @Throws(IOException::class)
    fun loadBitmapByIndex(index: Long): Bitmap? = null

    /** Called only after the matching Compose Image node has left composition. */
    fun releaseBitmap(bitmap: Bitmap) {
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    override fun close() = Unit
}

/**
 * The observed file layout does not yet define a verified System-NNN opcode
 * and operand grammar. Keep bytecode interpretation behind this interface:
 * implementations must consume bounded command bytes and return the next
 * command boundary, rather than treating each byte as an independent opcode.
 */
fun interface SystemNnnCommandDecoder {
    val usesSequentialTextPool: Boolean
        get() = false

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
        val sprites: List<SystemNnnSpriteReference> = emptyList(),
        val backgroundResourceIndex: Long? = null
    ) : SystemNnnDecodedCommand

    data class Skip(
        val nextCommandOffset: Long
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
    private val bitmapOwners = IdentityHashMap<Bitmap, SystemNnnSceneAssetResolver>()
    private var nextCommandOffset: Long = 0L
    private var frameCounter: Long = 0L
    private var nextTextPoolIndex: Int = 0
    private var activeTextPoolIndex: Int = -1
    private var audioRepository: SystemNnnVpkAudioRepository? = null
    private var audioPlayer: SystemNnnAudioPlayer? = null

    fun showStartupLoading() {
        uiState = SystemNnnPlayerUiState(
            isLoading = true,
            novelState = NovelState.Preparing
        )
    }

    fun showStartupError(message: String) {
        releaseAssets()
        uiState = SystemNnnPlayerUiState(
            isLoading = false,
            novelState = NovelState.Failed,
            errorMessage = message
        )
    }

    fun start(
        script: SystemNnnScript,
        commandDecoder: SystemNnnCommandDecoder?,
        assetResolver: SystemNnnSceneAssetResolver,
        initialCommandOffset: Long = script.commandStartOffset,
        audioRepository: SystemNnnVpkAudioRepository? = null,
        initialAudioTrack: File? = null,
        initialAudioResourceName: String? = null
    ) {
        releaseAssets()
        this.script = script
        this.decoder = commandDecoder
        this.assets = assetResolver
        this.audioRepository = audioRepository
        this.nextCommandOffset = initialCommandOffset
        this.frameCounter = 0L
        this.nextTextPoolIndex = 0
        this.activeTextPoolIndex = -1

        val initialBackground = loadInitialBackground(assetResolver)
        uiState = SystemNnnPlayerUiState(
            isLoading = true,
            novelState = NovelState.Executing,
            backgroundBitmap = initialBackground
        )
        startAudio(initialAudioTrack, initialAudioResourceName)

        if (commandDecoder == null) {
            uiState = uiState.copy(
                isLoading = false,
                novelState = NovelState.Failed,
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
    /**
     * The only event that can resume instruction execution after a dialogue
     * has been presented. Repeated taps while executing are ignored.
     */
    fun onNextTap() {
        val current = uiState
        if (current.novelState != NovelState.PlaybackWaiting ||
            current.isLoading ||
            current.isEnded ||
            current.choices.isNotEmpty()
        ) {
            return
        }

        if (!current.isTextComplete) {
            uiState = current.copy(
                visibleCharacterCount = current.dialogueText.length,
                isTextComplete = true,
                forceReveal = true
            )
            return
        }

        if (decoder?.usesSequentialTextPool == true && activeTextPoolIndex >= 0) {
            nextTextPoolIndex = (activeTextPoolIndex + 1)
                .coerceAtMost(script?.textPool?.size ?: Int.MAX_VALUE)
        }

        uiState = current.copy(
            isLoading = true,
            novelState = NovelState.Executing,
            errorMessage = null
        )
        advanceToVisibleEvent()
    }

    /** Compatibility entry point for the Compose screen click callback. */
    fun onScreenTap() = onNextTap()

    /**
     * Compose calls this after the corresponding image is removed. The bitmap
     * can then be recycled without leaving a live Image node pointing at it.
     */
    fun onBitmapDisposed(bitmap: Bitmap) {
        val owner = bitmapOwners.remove(bitmap)
        runCatching {
            if (owner != null) owner.releaseBitmap(bitmap)
            else if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun loadBitmap(
        resourceId: String,
        resolver: SystemNnnSceneAssetResolver
    ): Bitmap? = resolver.loadBitmap(resourceId)?.also { bitmap ->
        bitmapOwners[bitmap] = resolver
    }

    private fun loadInitialBackground(resolver: SystemNnnSceneAssetResolver): Bitmap? {
        for (index in 0L..1L) {
            try {
                loadBitmapByIndex(index, resolver)?.let { return it }
            } catch (_: Exception) {
                // One bad first row must not prevent trying the next GTB row.
            }
        }
        return null
    }

    private fun startAudio(track: File?, resourceName: String?) {
        audioPlayer = SystemNnnAudioPlayer { status ->
            if (status.state == SystemNnnAudioPlaybackState.ERROR) {
                uiState = uiState.copy(
                    audioWarning = "Не удалось запустить фоновый трек: " +
                        (status.message ?: "ошибка воспроизведения.")
                )
            }
        }
        if (track != null) {
            runCatching {
                audioPlayer?.play(track, resourceName ?: track.name)
            }.onFailure { error ->
                uiState = uiState.copy(
                    audioWarning = "Не удалось запустить фоновый трек: " +
                        (error.message ?: "ошибка подготовки.")
                )
            }
        }
    }

    private fun loadBitmapByIndex(
        index: Long,
        resolver: SystemNnnSceneAssetResolver
    ): Bitmap? = resolver.loadBitmapByIndex(index)?.also { bitmap ->
        bitmapOwners[bitmap] = resolver
    }

    fun onTypewriterProgress(visibleCharacters: Int) {
        val current = uiState
        if (current.novelState != NovelState.PlaybackWaiting ||
            current.isTextComplete ||
            current.forceReveal
        ) return
        uiState = current.copy(
            visibleCharacterCount = visibleCharacters.coerceIn(0, current.dialogueText.length)
        )
    }

    fun onTypewriterFinished() {
        val current = uiState
        if (current.novelState != NovelState.PlaybackWaiting || current.isTextComplete) return
        uiState = current.copy(
            visibleCharacterCount = current.dialogueText.length,
            isTextComplete = true
        )
    }

    fun onChoiceSelected(choiceId: String) {
        if (uiState.novelState != NovelState.ChoiceWaiting) return
        val choice = uiState.choices.firstOrNull { it.id == choiceId } ?: return
        val currentScript = script ?: return
        if (!isValidCommandOffset(currentScript, choice.targetCommandOffset)) {
            uiState = uiState.copy(
                errorMessage = "Вариант выбора указывает за пределы командной области."
            )
            return
        }

        nextCommandOffset = choice.targetCommandOffset
        uiState = uiState.copy(
            choices = emptyList(),
            errorMessage = null,
            isLoading = true,
            novelState = NovelState.Executing
        )
        advanceToVisibleEvent()
    }

    private fun advanceToVisibleEvent() {
        if (uiState.novelState != NovelState.Executing) return

        val currentScript = script
        val currentDecoder = decoder
        val resolver = assets

        if (currentScript == null || currentDecoder == null || resolver == null) {
            uiState = uiState.copy(
                isLoading = false,
                novelState = NovelState.Failed,
                errorMessage = "Игровая сессия не инициализирована."
            )
            return
        }

        uiState = uiState.copy(isLoading = true, errorMessage = null)

        try {
            val mainTable = currentScript.offsetTables.firstOrNull { it.name == MAIN_TABLE_NAME }
                ?: throw IOException("The SPT main transition table is missing.")
            val tableEndOffset = mainCommandTableEndOffset(currentScript)
            val maxDecodeAttempts = mainTable.entryCount.toLong() + 1L
            var decodeAttempts = 0L
            var sceneBackground = uiState.backgroundBitmap
            var sceneSprites = uiState.sprites

            while (decodeAttempts < maxDecodeAttempts) {
                val commandOffset = nextCommandOffset
                if (!isValidCommandCursor(currentScript, commandOffset)) {
                    throw IOException("Command cursor left the declared SPT transition table.")
                }

                val command = currentDecoder.decodeNext(currentScript, commandOffset)
                decodeAttempts += 1L
                when (command) {
                    is SystemNnnDecodedCommand.SceneUpdate -> {
                        validateNextOffset(currentScript, commandOffset, command.nextCommandOffset)
                        nextCommandOffset = command.nextCommandOffset
                        if (command.backgroundResourceIndex != null) {
                            sceneBackground = loadBitmapByIndex(
                                command.backgroundResourceIndex,
                                resolver
                            ) ?: throw IOException(
                                "Background GTB resource index " +
                                    command.backgroundResourceIndex + " was not found."
                            )
                        } else if (command.backgroundResourceId != null) {
                            sceneBackground = loadBitmap(command.backgroundResourceId, resolver)
                                ?: throw IOException("Background resource was not found.")
                        }
                        sceneSprites = resolveSprites(command.sprites, resolver)
                        commandsWithoutVisibleOutput += 1
                    }

                    is SystemNnnDecodedCommand.Skip -> {
                        validateNextOffset(currentScript, commandOffset, command.nextCommandOffset)
                        nextCommandOffset = command.nextCommandOffset
                        commandsWithoutVisibleOutput += 1
                    }

                    is SystemNnnDecodedCommand.Dialogue -> {
                        validateNextOffset(currentScript, commandOffset, command.nextCommandOffset)
                        val textEntry = if (currentDecoder.usesSequentialTextPool) {
                            val selectedIndex = nextTextPoolIndex
                            currentScript.textPool.getOrNull(selectedIndex)?.also {
                                activeTextPoolIndex = selectedIndex
                            } ?: throw IOException("The sequential Shift-JIS text-pool cursor is exhausted.")
                        } else {
                            currentScript.textEntryAtOffset(command.textEntryOffset)?.also { entry ->
                                activeTextPoolIndex = currentScript.textPool.indexOf(entry)
                                if (activeTextPoolIndex >= 0) nextTextPoolIndex = activeTextPoolIndex + 1
                            } ?: throw IOException(
                                "Dialogue points to an unknown Shift-JIS text-pool offset."
                            )
                        }
                        val text = textEntry.text
                        nextCommandOffset = command.nextCommandOffset

                        if (command.backgroundResourceId != null) {
                            sceneBackground = loadBitmap(command.backgroundResourceId, resolver)
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
                            dialogueText = text,
                            visibleCharacterCount = 0,
                            isTextComplete = text.isEmpty(),
                            forceReveal = false,
                            novelState = NovelState.PlaybackWaiting
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
                            choices = command.options,
                            novelState = NovelState.ChoiceWaiting
                        )
                        return
                    }

                    SystemNnnDecodedCommand.End -> {
                        if (commandOffset != tableEndOffset) {
                            throw IOException(
                                "The decoder reported End before the declared SPT transition-table boundary."
                            )
                        }
                        // The command table ends before the SPT file itself:
                        // the remaining bytes contain the Shift-JIS text pool.
                        uiState = SystemNnnPlayerUiState(
                            frameId = frameCounter + 1L,
                            novelState = NovelState.Ended,
                            isEnded = true
                        )
                        return
                    }
                }
            }

            throw IOException(
                "The SPT transition table ended without a valid terminal boundary."
            )
        } catch (error: Exception) {
            uiState = uiState.copy(
                isLoading = false,
                novelState = NovelState.Failed,
                errorMessage = error.message ?: "Не удалось декодировать следующую команду."
            )
        }
    }

    private fun resolveSprites(
        references: List<SystemNnnSpriteReference>,
        resolver: SystemNnnSceneAssetResolver
    ): List<SystemNnnSpriteBitmap> = references.map { reference ->
        val bitmap = loadBitmap(reference.resourceId, resolver)
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
        if (!isValidCommandCursor(currentScript, nextOffset) || nextOffset == currentOffset) {
            throw IOException("Decoded command has an invalid next offset.")
        }
    }

    /**
     * The SPT header declares a UInt32 transition table. Its logical end is
     * distinct from physical EOF because the text pool follows the table.
     */
    private fun mainCommandTableEndOffset(currentScript: SystemNnnScript): Long {
        val mainTable = currentScript.offsetTables.firstOrNull { it.name == MAIN_TABLE_NAME }
            ?: throw IOException("The SPT main transition table is missing.")
        val start = mainTable.offset.toLong()
        val entryCount = mainTable.entryCount.toLong()
        if (entryCount <= 0L || start != currentScript.commandStartOffset) {
            throw IOException("The SPT main transition table descriptor is invalid.")
        }
        val end = start + entryCount * UINT32_BYTES
        if (end < start ||
            end > currentScript.commandEndOffset ||
            end > currentScript.fileSizeBytes
        ) {
            throw IOException("The SPT main transition table exceeds its validated file bounds.")
        }
        return end
    }

    /** Allows the one-past-last cursor solely for the decoder's End result. */
    private fun isValidCommandCursor(currentScript: SystemNnnScript, offset: Long): Boolean {
        val end = runCatching { mainCommandTableEndOffset(currentScript) }.getOrNull() ?: return false
        val relativeOffset = offset - currentScript.commandStartOffset
        return offset >= currentScript.commandStartOffset &&
            offset <= end &&
            relativeOffset % UINT32_BYTES == 0L
    }

    /** Choice targets must point at an actual table entry. */
    private fun isValidCommandOffset(currentScript: SystemNnnScript, offset: Long): Boolean {
        val end = runCatching { mainCommandTableEndOffset(currentScript) }.getOrNull() ?: return false
        return isValidCommandCursor(currentScript, offset) && offset < end
    }

    private fun releaseAssets() {
        val player = audioPlayer
        audioPlayer = null
        runCatching { player?.close() }

        val audio = audioRepository
        audioRepository = null
        runCatching { audio?.close() }

        runCatching { assets?.close() }
        assets = null
    }

    override fun onCleared() {
        uiState = SystemNnnPlayerUiState()
        releaseAssets()
        super.onCleared()
    }

    companion object {
        private const val MAIN_TABLE_NAME = "main"
        private const val UINT32_BYTES = 4L
    }
}
