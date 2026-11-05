package io.github.helterfall.ramen.runtime.systemnnn

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.github.helterfall.ramen.parser.SystemNnnScript
import io.github.helterfall.ramen.parser.SystemNnnCommandStream
import io.github.helterfall.ramen.parser.SystemNnnCommandStreamReader
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.IdentityHashMap
import java.util.concurrent.Executors

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
    /** One-based module-local film number from the latest validated SETFILM record. */
    val currentFilmNumber: Int? = null,
    val backgroundBitmap: Bitmap? = null,
    val sprites: List<SystemNnnSpriteBitmap> = emptyList(),
    val speakerName: String? = null,
    val dialogueText: String = "",
    val visibleCharacterCount: Int = 0,
    val isTextComplete: Boolean = true,
    val forceReveal: Boolean = false,
    val choices: List<SystemNnnChoiceOption> = emptyList(),
    val isEnded: Boolean = false,
    val statusMessage: String? = null,
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
        val sprites: List<SystemNnnSpriteReference> = emptyList(),
        val backgroundResourceIndex: Long? = null,
        val dialogueStepIndex: Long? = null
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

    /** A validated non-sequential local control transfer within the current SPT. */
    data class Jump(
        val targetCommandOffset: Long
    ) : SystemNnnDecodedCommand

    data class SetFilm(
        val nextCommandOffset: Long,
        val filmNumber: Int
    ) : SystemNnnDecodedCommand

    data class Choice(
        val promptTextEntryOffset: Long?,
        val options: List<SystemNnnChoiceOption>,
        val speakerName: String? = null,
        val promptText: String? = null
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
    private var commandStream: SystemNnnCommandStream? = null
    private var decoder: SystemNnnCommandDecoder? = null
    private var assets: SystemNnnSceneAssetResolver? = null
    private val bitmapOwners = IdentityHashMap<Bitmap, SystemNnnSceneAssetResolver>()
    private var nextCommandOffset: Long = 0L
    private var frameCounter: Long = 0L
    private var audioRepository: SystemNnnVpkAudioRepository? = null
    private var audioPlayer: SystemNnnAudioPlayer? = null
    private var audioRequestId: Long = 0L
    private val voiceExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler by lazy(LazyThreadSafetyMode.NONE) {
        Handler(Looper.getMainLooper())
    }

    fun showStartupLoading() {
        uiState = SystemNnnPlayerUiState(isLoading = true)
    }

    fun showStartupError(message: String) {
        releaseAssets()
        uiState = SystemNnnPlayerUiState(
            isLoading = false,
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
        val parsedCommandStream = try {
            SystemNnnCommandStreamReader().read(script)
        } catch (error: Exception) {
            runCatching { assetResolver.close() }
            runCatching { audioRepository?.close() }
            uiState = SystemNnnPlayerUiState(
                errorMessage = error.message ?: "Не удалось разобрать командный поток SPT."
            )
            return
        }

        this.script = script
        this.commandStream = parsedCommandStream
        this.decoder = commandDecoder
        this.assets = assetResolver
        this.audioRepository = audioRepository
        this.nextCommandOffset = if (initialCommandOffset == script.commandStartOffset) {
            parsedCommandStream.recordStartOffset
        } else {
            initialCommandOffset
        }
        this.frameCounter = 0L
        this.audioRequestId += 1L

        uiState = SystemNnnPlayerUiState(isLoading = true)
        startAudio(initialAudioTrack, initialAudioResourceName)

        if (commandDecoder == null) {
            uiState = uiState.copy(
                isLoading = false,
                statusMessage = "SPT загружен: текстовые пулы прочитаны, " +
                    parsedCommandStream.records.size + " командных записей проверено. " +
                    "Для воспроизведения нужен декодер команд System-NNN."
            )
            return
        }

        advanceToVisibleEvent()
    }

    /** Alias used by the player screen to advance to the next frame. */
    fun onNextTap() = onScreenTap()

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

    /**
     * Replaces the previous voice for every visible dialogue. File extraction runs
     * off the UI thread; stale extraction results are discarded before playback.
     */
    private fun playVoice(dialogueStepIndex: Long?) {
        val repository = audioRepository ?: return
        val player = audioPlayer ?: return

        val requestId = ++audioRequestId
        player.stop()

        if (dialogueStepIndex == null) return
        if (dialogueStepIndex < 0L || dialogueStepIndex > Int.MAX_VALUE.toLong()) {
            uiState = uiState.copy(audioWarning = "Индекс голосового ресурса выходит за допустимый диапазон.")
            return
        }

        val resource = repository.entries.getOrNull(dialogueStepIndex.toInt())
        if (resource == null) {
            uiState = uiState.copy(
                audioWarning = "Для диалога №" + (dialogueStepIndex + 1L) +
                    " нет соответствующей записи VAW/VTB."
            )
            return
        }

        voiceExecutor.execute {
            val staged = runCatching { repository.materializeByIndex(dialogueStepIndex) }
            mainHandler.post {
                if (requestId != audioRequestId) {
                    staged.getOrNull()?.delete()
                    return@post
                }
                staged.onSuccess { file ->
                    runCatching { player.play(file, resource.name) }
                        .onFailure { error ->
                            uiState = uiState.copy(
                                audioWarning = "Не удалось запустить голосовую дорожку: " +
                                    (error.message ?: "ошибка воспроизведения.")
                            )
                        }
                }.onFailure { error ->
                    uiState = uiState.copy(
                        audioWarning = "Не удалось подготовить голосовую дорожку: " +
                            (error.message ?: "ошибка чтения архива.")
                    )
                }
            }
        }
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
            var pendingBackgroundResourceId: String? = null
            var pendingBackgroundResourceIndex: Long? = null
            var pendingSpriteReferences: List<SystemNnnSpriteReference>? = null
            var currentFilmNumber = uiState.currentFilmNumber
            var commandsWithoutVisibleOutput = 0

            while (commandsWithoutVisibleOutput < MAX_SCENE_COMMANDS_PER_ADVANCE) {
                val commandOffset = nextCommandOffset
                if (!isValidCommandCursor(currentScript, commandOffset)) {
                    throw IOException("Command cursor is not on a validated SPT record boundary.")
                }

                when (val command = currentDecoder.decodeNext(currentScript, commandOffset)) {
                    is SystemNnnDecodedCommand.SceneUpdate -> {
                        validateNextOffset(currentScript, commandOffset, command.nextCommandOffset)
                        nextCommandOffset = command.nextCommandOffset
                        if (command.backgroundResourceIndex != null) {
                            pendingBackgroundResourceIndex = command.backgroundResourceIndex
                            pendingBackgroundResourceId = null
                        } else if (command.backgroundResourceId != null) {
                            pendingBackgroundResourceId = command.backgroundResourceId
                            pendingBackgroundResourceIndex = null
                        }
                        // Scene updates can be numerous before the next dialogue.
                        // Keep only the final resource descriptors and decode them
                        // once, immediately before publishing the visible frame.
                        pendingSpriteReferences = command.sprites
                        commandsWithoutVisibleOutput += 1
                    }

                    is SystemNnnDecodedCommand.Skip -> {
                        validateNextOffset(currentScript, commandOffset, command.nextCommandOffset)
                        nextCommandOffset = command.nextCommandOffset
                        commandsWithoutVisibleOutput += 1
                    }

                    is SystemNnnDecodedCommand.Jump -> {
                        if (!isValidCommandOffset(currentScript, command.targetCommandOffset)) {
                            throw IOException("Local control transfer target is not a validated SPT record boundary.")
                        }
                        nextCommandOffset = command.targetCommandOffset
                        commandsWithoutVisibleOutput += 1
                    }

                    is SystemNnnDecodedCommand.SetFilm -> {
                        validateNextOffset(currentScript, commandOffset, command.nextCommandOffset)
                        if (command.filmNumber <= 0) {
                            throw IOException("SETFILM supplied a non-positive film number.")
                        }
                        nextCommandOffset = command.nextCommandOffset
                        currentFilmNumber = command.filmNumber
                        commandsWithoutVisibleOutput += 1
                    }

                    is SystemNnnDecodedCommand.Dialogue -> {
                        validateNextOffset(currentScript, commandOffset, command.nextCommandOffset)
                        val text = currentScript.textEntryAtOffset(command.textEntryOffset)?.text
                            ?: throw IOException(
                                "Dialogue points to an unknown Shift-JIS text-pool offset."
                            )
                        nextCommandOffset = command.nextCommandOffset

                        if (command.backgroundResourceId != null) {
                            pendingBackgroundResourceId = command.backgroundResourceId
                            pendingBackgroundResourceIndex = null
                        }
                        if (pendingBackgroundResourceId != null) {
                            sceneBackground = loadBitmap(pendingBackgroundResourceId!!, resolver)
                                ?: throw IOException("Background resource was not found.")
                        } else {
                            val backgroundIndex =
                                pendingBackgroundResourceIndex ?: command.backgroundResourceIndex
                            if (backgroundIndex != null) {
                                sceneBackground = loadBitmapByIndex(backgroundIndex, resolver)
                                    ?: throw IOException(
                                        "Background GTB resource index " + backgroundIndex + " was not found."
                                    )
                            }
                        }
                        if (command.sprites.isNotEmpty()) {
                            pendingSpriteReferences = command.sprites
                        }
                        if (pendingSpriteReferences != null) {
                            sceneSprites = resolveSprites(pendingSpriteReferences!!, resolver)
                        }

                        val dialogueStepIndex = command.dialogueStepIndex
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
                            currentFilmNumber = currentFilmNumber
                        )
                        playVoice(dialogueStepIndex)
                        return
                    }

                    is SystemNnnDecodedCommand.Choice -> {
                        val promptText = command.promptText ?: command.promptTextEntryOffset?.let { offset ->
                            currentScript.textEntryAtOffset(offset)?.text
                                ?: throw IOException(
                                    "Choice prompt points to an unknown Shift-JIS text-pool offset."
                                )
                        }.orEmpty()
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

                        if (pendingBackgroundResourceIndex != null) {
                            val index = pendingBackgroundResourceIndex!!
                            sceneBackground = loadBitmapByIndex(index, resolver)
                                ?: throw IOException("Background GTB resource index " + index + " was not found.")
                        } else if (pendingBackgroundResourceId != null) {
                            sceneBackground = loadBitmap(pendingBackgroundResourceId!!, resolver)
                                ?: throw IOException("Background resource was not found.")
                        }
                        if (pendingSpriteReferences != null) {
                            sceneSprites = resolveSprites(pendingSpriteReferences!!, resolver)
                        }

                        frameCounter += 1L
                        uiState = SystemNnnPlayerUiState(
                            frameId = frameCounter,
                            isLoading = false,
                            backgroundBitmap = sceneBackground,
                            sprites = sceneSprites,
                            speakerName = command.speakerName,
                            dialogueText = promptText,
                            visibleCharacterCount = promptText.length,
                            isTextComplete = true,
                            forceReveal = true,
                            choices = command.options,
                            currentFilmNumber = currentFilmNumber
                        )
                        return
                    }

                    SystemNnnDecodedCommand.End -> {
                        audioPlayer?.stop()
                        uiState = SystemNnnPlayerUiState(
                            frameId = frameCounter + 1L,
                            isEnded = true,
                            currentFilmNumber = currentFilmNumber
                        )
                        return
                    }
                }
            }

            throw IOException("Too many scene-only commands without a dialogue or choice.")
        } catch (error: Exception) {
            releaseUnpublishedBitmaps()
            uiState = uiState.copy(
                isLoading = false,
                errorMessage = error.message ?: "Не удалось декодировать следующую команду."
            )
        }
    }

    /**
     * Releases resources decoded for a frame that was never published. The
     * current UI state remains the owner of its displayed background/sprites.
     */
    private fun releaseUnpublishedBitmaps() {
        val published = IdentityHashMap<Bitmap, Boolean>()
        uiState.backgroundBitmap?.let { published[it] = true }
        uiState.sprites.forEach { published[it.bitmap] = true }

        bitmapOwners.keys.toList().forEach { bitmap ->
            if (!published.containsKey(bitmap)) {
                onBitmapDisposed(bitmap)
            }
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
        if (!isValidCommandOffset(currentScript, currentOffset) ||
            nextOffset == currentOffset ||
            !isValidCommandCursor(currentScript, nextOffset)
        ) {
            throw IOException("Decoded command target is not a valid SPT record boundary.")
        }
    }

    private fun isValidCommandCursor(currentScript: SystemNnnScript, offset: Long): Boolean {
        val stream = commandStream ?: return false
        if (script !== currentScript) return false
        if (offset == stream.recordEndOffsetExclusive) return true
        return stream.records.binarySearchBy(offset) { it.offset } >= 0
    }

    private fun isValidCommandOffset(currentScript: SystemNnnScript, offset: Long): Boolean {
        val stream = commandStream ?: return false
        return offset != stream.recordEndOffsetExclusive &&
            isValidCommandCursor(currentScript, offset)
    }

    private fun releaseAssets() {
        commandStream = null
        audioRequestId += 1L
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
        voiceExecutor.shutdownNow()
        super.onCleared()
    }

    companion object {
        private const val MAX_SCENE_COMMANDS_PER_ADVANCE = 2_048
    }
}
