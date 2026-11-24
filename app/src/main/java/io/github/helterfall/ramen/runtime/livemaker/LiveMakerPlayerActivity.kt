package io.github.helterfall.ramen.runtime.livemaker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.github.helterfall.ramen.inspection.ReadOnlyRandomAccessSource
import io.github.helterfall.ramen.runtime.systemnnn.SystemNnnAudioPlayer
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

private data class LiveMakerPlayerUiState(
    val isLoading: Boolean = true,
    val pageReference: String? = null,
    val scriptVersion: Int? = null,
    val commandCount: Int? = null,
    val eventDescription: String = "Подготовка LiveMaker-сессии…",
    val canContinue: Boolean = false,
    val dialogueText: String = "",
    val waitingForTap: Boolean = false,
    val sceneImages: List<LiveMakerSceneBitmap> = emptyList(),
    val assets: List<LiveMakerMediaAsset> = emptyList(),
    val mediaDiagnostics: LiveMakerMediaIndexDiagnostics? = null,
    val imageBitmap: Bitmap? = null,
    val selectedAssetName: String? = null,
    val audioStatus: String? = null,
    val errorMessage: String? = null
)

private data class LiveMakerSceneBitmap(
    val objectName: String,
    val resourcePath: String,
    val bitmap: Bitmap,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val alpha: Float,
    val priority: Float,
    val isBackground: Boolean
)

private data class LiveMakerTextPlayback(
    val event: LiveMakerVmEvent.TextBlock,
    val segments: List<LiveMakerTextSegment>,
    var nextSegmentIndex: Int = 0,
    var currentText: String = "",
    var waitingForTap: Boolean = false
)

private data class LiveMakerEventPresentation(
    val description: String,
    val canContinue: Boolean,
    val dialogueText: String = "",
    val waitingForTap: Boolean = false
)

/** Android host for the compiled LiveMaker LSB runtime. */
class LiveMakerPlayerActivity : ComponentActivity() {
    private val uiState = mutableStateOf(LiveMakerPlayerUiState())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var liveSession: LiveMakerGameSession? = null
    private var currentEvent: LiveMakerSessionEvent? = null
    private var mediaRepository: LiveMakerMediaAssetRepository? = null
    private var audioPlayer: SystemNnnAudioPlayer? = null
    private var textPlayback: LiveMakerTextPlayback? = null
    private val sceneObjects = linkedMapOf<String, LiveMakerSceneBitmap>()
    private val soundObjects = linkedMapOf<String, LiveMakerMediaAsset>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                LiveMakerPlayerScreen(
                    state = uiState.value,
                    onContinue = ::continueSession,
                    onAssetSelected = ::selectAsset
                )
            }
        }

        val gameId = intent.getStringExtra(EXTRA_GAME_ID)
        if (gameId.isNullOrBlank()) {
            uiState.value = uiState.value.copy(
                isLoading = false,
                errorMessage = "Не передан идентификатор игры из библиотеки."
            )
            return
        }

        worker.execute {
            var preparedSession: LiveMakerGameSession? = null
            var preparedAssets: LiveMakerMediaAssetRepository? = null
            try {
                val gameDirectory = resolvePrivateGameDirectory(gameId)
                val bootstrap = LiveMakerStartupScriptResolver(gameDirectory).openSession()
                preparedSession = bootstrap.session
                val assets = LiveMakerMediaAssetRepository(gameDirectory)
                preparedAssets = assets
                // Host-command dispatch needs the repositories before the first VM step.
                liveSession = bootstrap.session
                mediaRepository = assets
                val firstEvent = nextEvent(bootstrap.session)

                currentEvent = firstEvent
                val presentation = presentEvent(firstEvent, bootstrap.session)
                mainHandler.post {
                    if (isFinishing || isDestroyed) {
                        bootstrap.session.close()
                        assets.close()
                    } else {
                        uiState.value = LiveMakerPlayerUiState(
                            isLoading = false,
                            pageReference = bootstrap.pageReference,
                            scriptVersion = bootstrap.scriptVersion,
                            commandCount = bootstrap.commandCount,
                            eventDescription = presentation.description,
                            canContinue = presentation.canContinue,
                            dialogueText = presentation.dialogueText,
                            waitingForTap = presentation.waitingForTap,
                            assets = assets.listAssets(),
                            mediaDiagnostics = assets.diagnostics
                        )
                    }
                }
            } catch (error: Exception) {
                preparedSession?.close()
                preparedAssets?.close()
                mainHandler.post {
                    if (!isFinishing && !isDestroyed) {
                        uiState.value = uiState.value.copy(
                            isLoading = false,
                            eventDescription = "Запуск остановлен до первого события VM.",
                            canContinue = false,
                            waitingForTap = false,
                            errorMessage = error.message ?: "Не удалось открыть LiveMaker-плеер."
                        )
                    }
                }
            }
        }
    }

    private fun continueSession() {
        worker.execute {
            val session = liveSession ?: return@execute
            try {
                val playback = textPlayback
                if (playback != null && playback.waitingForTap) {
                    playback.waitingForTap = false
                    val nextSegment = advanceTextPlayback(playback)
                    if (nextSegment != null) {
                        mainHandler.post {
                            if (!isFinishing && !isDestroyed) {
                                uiState.value = uiState.value.copy(
                                    dialogueText = playback.currentText,
                                    waitingForTap = playback.waitingForTap,
                                    canContinue = playback.waitingForTap,
                                    errorMessage = null
                                )
                            }
                        }
                        return@execute
                    }
                    textPlayback = null
                    session.resume()
                } else {
                    when (val event = currentEvent) {
                        is LiveMakerSessionEvent.EngineEvent -> when (event.event) {
                            is LiveMakerVmEvent.TextBlock,
                            is LiveMakerVmEvent.Delay -> session.resume()
                            else -> return@execute
                        }
                        else -> return@execute
                    }
                }

                val next = nextEvent(session)
                currentEvent = next
                val presentation = presentEvent(next, session)
                mainHandler.post {
                    if (!isFinishing && !isDestroyed) {
                        uiState.value = uiState.value.copy(
                            eventDescription = presentation.description,
                            canContinue = presentation.canContinue,
                            dialogueText = presentation.dialogueText,
                            waitingForTap = presentation.waitingForTap,
                            errorMessage = null
                        )
                    }
                }
            } catch (error: Exception) {
                publishError(error)
            }
        }
    }

    private fun selectAsset(asset: LiveMakerMediaAsset) {
        worker.execute {
            val repository = mediaRepository ?: return@execute
            try {
                when (asset.extension) {
                    "gal", "png" -> {
                        val bitmap = decodeImage(repository.readAsset(asset), asset.extension)
                        mainHandler.post {
                            if (isFinishing || isDestroyed) {
                                bitmap.recycle()
                            } else {
                                val previous = uiState.value.imageBitmap
                                if (previous != null && previous !== bitmap && !previous.isRecycled) {
                                    previous.recycle()
                                }
                                uiState.value = uiState.value.copy(
                                    imageBitmap = bitmap,
                                    selectedAssetName = asset.path,
                                    errorMessage = null
                                )
                            }
                        }
                    }
                    "ogg", "wav" -> {
                        val staged = repository.stageAudio(asset, File(cacheDir, "livemaker-audio"))
                        mainHandler.post {
                            if (isFinishing || isDestroyed) {
                                staged.delete()
                            } else {
                                val player = audioPlayer ?: SystemNnnAudioPlayer { status ->
                                    mainHandler.post {
                                        if (!isFinishing && !isDestroyed) {
                                            uiState.value = uiState.value.copy(
                                                audioStatus = status.state.name.lowercase() +
                                                    ": " + status.resourceName
                                            )
                                        }
                                    }
                                }.also { audioPlayer = it }
                                try {
                                    player.play(staged, asset.path)
                                    uiState.value = uiState.value.copy(
                                        audioStatus = "Подготовка: " + asset.path,
                                        errorMessage = null
                                    )
                                } catch (error: Exception) {
                                    uiState.value = uiState.value.copy(
                                        errorMessage = error.message ?: "Не удалось воспроизвести аудио."
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                publishError(error)
            }
        }
    }

    private fun nextEvent(session: LiveMakerGameSession): LiveMakerSessionEvent {
        repeat(MAX_AUTOMATIC_HOST_COMMANDS) {
            val event = session.runUntilEvent()
            if (event is LiveMakerSessionEvent.PageEntered) return@repeat

            val hostCommand = (event as? LiveMakerSessionEvent.EngineEvent)
                ?.event as? LiveMakerVmEvent.HostCommandRequired
            if (hostCommand != null && handleHostCommand(hostCommand.command, session)) {
                session.resume()
                return@repeat
            }
            return event
        }
        throw IOException("LiveMaker exceeded the automatic host-command budget.")
    }

    /**
     * Handles LiveMaker Android scene/audio commands with verified command and property IDs.
     * Any unknown command is returned to the VM host surface instead of being skipped.
     */
    private fun handleHostCommand(
        command: io.github.helterfall.ramen.inspection.LiveMakerCommand,
        session: LiveMakerGameSession
    ): Boolean {
        val repository = mediaRepository

        fun componentValues(): Map<Int, Any?> {
            val result = linkedMapOf<Int, Any?>()
            command.componentPropertyIds.forEachIndexed { index, propertyId ->
                val expression = command.expressions.getOrNull(index) ?: return@forEachIndexed
                result[propertyId] = session.evaluateExpression(expression)
            }
            return result
        }

        fun number(value: Any?, fallback: Float): Float = when (value) {
            is Number -> value.toFloat()
            is String -> value.toFloatOrNull() ?: fallback
            else -> fallback
        }

        when (command.typeId) {
            // ImgNew. PR_NAME=1, PR_SOURCE=3, PR_LEFT=4, PR_TOP=5, PR_WIDTH=6,
            // PR_HEIGHT=7, PR_ALPHA=12, PR_PRIORITY=13, PR_VISIBLE=48.
            9 -> {
                if (repository == null) return false
                val values = componentValues()
                val source = values[3]?.toString()?.takeIf { it.isNotBlank() } ?: return false
                val asset = repository.findAsset(source)
                    ?: throw IOException(
                        "LiveMaker image resource '" + source + "' was not found or is ambiguous."
                    )
                if (asset.extension !in setOf("gal", "png")) {
                    throw IOException("ImgNew resource '" + source + "' is not a GAL or PNG image.")
                }

                val objectName = values[1]?.toString()?.takeIf { it.isNotBlank() } ?: source
                if (number(values[48], 1f) == 0f) {
                    sceneObjects.remove(objectName)
                    publishSceneImages()
                    return true
                }

                val bitmap = decodeImage(repository.readAsset(asset), asset.extension)
                val width = number(values[6], bitmap.width.toFloat()).coerceAtLeast(1f)
                val height = number(values[7], bitmap.height.toFloat()).coerceAtLeast(1f)
                val alphaValue = number(values[12], 255f)
                val alpha = (if (alphaValue > 1f) alphaValue / 255f else alphaValue)
                    .coerceIn(0f, 1f)
                val lowerName = objectName.lowercase()
                val lowerPath = asset.path.lowercase()
                val isBackground = lowerName.contains("bg") ||
                    lowerName.contains("back") ||
                    lowerPath.contains("background") ||
                    lowerPath.substringAfterLast('/').startsWith("bg")

                val image = LiveMakerSceneBitmap(
                    objectName = objectName,
                    resourcePath = asset.path,
                    bitmap = bitmap,
                    left = number(values[4], 0f),
                    top = number(values[5], 0f),
                    width = width,
                    height = height,
                    alpha = alpha,
                    priority = number(values[13], sceneObjects.size.toFloat()),
                    isBackground = isBackground
                )
                sceneObjects.put(objectName, image)
                publishSceneImages()
                return true
            }

            // Sound creates a named media object; MediaPlay starts it.
            42 -> {
                if (repository == null) return false
                val values = componentValues()
                val source = values[3]?.toString()?.takeIf { it.isNotBlank() } ?: return false
                val asset = repository.findAsset(source)
                    ?: throw IOException(
                        "LiveMaker audio resource '" + source + "' was not found or is ambiguous."
                    )
                if (asset.extension !in setOf("ogg", "wav")) {
                    throw IOException("Sound resource '" + source + "' is not OGG or WAV.")
                }
                val name = values[1]?.toString()?.takeIf { it.isNotBlank() } ?: source
                soundObjects[name] = asset
                return true
            }

            // MediaPlay.Target is a LiveParser expression containing the media object name.
            55 -> {
                if (repository == null) return false
                val targetExpression = command.expressions.firstOrNull() ?: return false
                val target = session.evaluateExpression(targetExpression)?.toString().orEmpty()
                val asset = soundObjects[target] ?: repository.findAsset(target) ?: return false
                val staged = repository.stageAudio(asset, File(cacheDir, "livemaker-audio"))
                mainHandler.post {
                    if (isFinishing || isDestroyed) {
                        staged.delete()
                    } else {
                        val player = audioPlayer ?: SystemNnnAudioPlayer { status ->
                            mainHandler.post {
                                if (!isFinishing && !isDestroyed) {
                                    uiState.value = uiState.value.copy(
                                        audioStatus = status.state.name.lowercase() +
                                            ": " + status.resourceName
                                    )
                                }
                            }
                        }.also { audioPlayer = it }
                        runCatching { player.play(staged, asset.path) }
                            .onFailure { error ->
                                staged.delete()
                                uiState.value = uiState.value.copy(
                                    errorMessage = error.message ?: "Could not play LiveMaker audio."
                                )
                            }
                    }
                }
                return true
            }

            // ObjDel(Target) and TextClr(Target).
            19, 28 -> {
                val expression = command.expressions.firstOrNull() ?: return false
                val target = session.evaluateExpression(expression)?.toString().orEmpty()
                if (command.typeId == 19) {
                    sceneObjects.remove(target)
                    soundObjects.remove(target)
                    publishSceneImages()
                } else {
                    mainHandler.post {
                        if (!isFinishing && !isDestroyed) {
                            uiState.value = uiState.value.copy(dialogueText = "")
                        }
                    }
                }
                return true
            }

            21 -> {
                mainHandler.post { audioPlayer?.stop() }
                return true
            }
            // ClrHist / DoEvent / ClrRead do not require a visible scene change here.
            22, 46, 47 -> return true
            else -> return false
        }
    }

    private fun publishSceneImages() {
        val snapshot = sceneObjects.values.sortedBy { it.priority }
        mainHandler.post {
            if (!isFinishing && !isDestroyed) {
                uiState.value = uiState.value.copy(sceneImages = snapshot)
            } else {
                snapshot.forEach { image ->
                    if (!image.bitmap.isRecycled) image.bitmap.recycle()
                }
            }
        }
    }

    private fun presentEvent(
        event: LiveMakerSessionEvent,
        session: LiveMakerGameSession
    ): LiveMakerEventPresentation =
        when (event) {
            is LiveMakerSessionEvent.PageEntered ->
                LiveMakerEventPresentation("Открыта страница " + event.pageReference + ".", false)
            is LiveMakerSessionEvent.PageResolutionRequired ->
                LiveMakerEventPresentation(
                    "Страница " + event.pageReference + " отсутствует в доступных LSB.",
                    false
                )
            is LiveMakerSessionEvent.EngineEvent -> when (val engineEvent = event.event) {
                is LiveMakerVmEvent.TextBlock -> {
                    val decoded = LiveMakerTpWordDecoder().decode(engineEvent.tpWordBytes) { name ->
                        session.variablesSnapshot()[name]
                    }
                    val segments = decoded.segments.toMutableList()
                    if (segments.isEmpty()) {
                        segments += LiveMakerTextSegment("", waitForTap = engineEvent.shouldWait)
                    } else {
                        val last = segments.last()
                        val mustWaitForText = engineEvent.shouldWait || decoded.plainText.isNotEmpty()
                        if (mustWaitForText && !last.waitForTap) {
                            segments[segments.lastIndex] = last.copy(waitForTap = true)
                        }
                    }
                    textPlayback = LiveMakerTextPlayback(engineEvent, segments)
                    val waiting = advanceTextPlayback(textPlayback!!)
                    LiveMakerEventPresentation(
                        description = if (decoded.inlineImages.isEmpty()) {
                            "TextIns · " + decoded.version + " · CP932/TpWord · команда " +
                                engineEvent.commandIndex
                        } else {
                            "TextIns · команда " + engineEvent.commandIndex +
                                " · inline images: " + decoded.inlineImages.joinToString()
                        },
                        canContinue = textPlayback?.waitingForTap == true,
                        dialogueText = textPlayback?.currentText.orEmpty(),
                        waitingForTap = textPlayback?.waitingForTap == true
                    )
                }
                is LiveMakerVmEvent.Delay ->
                    LiveMakerEventPresentation(
                        "Сценарий запросил паузу " + engineEvent.milliseconds + " мс.",
                        true
                    )
                is LiveMakerVmEvent.HostCommandRequired ->
                    LiveMakerEventPresentation(
                        "Команда сцены " + engineEvent.command.typeName +
                            " (" + engineEvent.command.typeId + ") ожидает host adapter.",
                        false
                    )
                is LiveMakerVmEvent.UnsupportedExpression ->
                    LiveMakerEventPresentation(
                        "Неподдерживаемое выражение в команде " +
                            engineEvent.commandIndex + ": " + engineEvent.reason,
                        false
                    )
                LiveMakerVmEvent.AwaitingHost ->
                    LiveMakerEventPresentation("Виртуальная машина ожидает обработку хоста.", false)
                LiveMakerVmEvent.Running ->
                    LiveMakerEventPresentation("Виртуальная машина выполняется.", false)
                LiveMakerVmEvent.Completed ->
                    LiveMakerEventPresentation("Достигнут конец сценария.", false)
                is LiveMakerVmEvent.ScriptTransfer ->
                    LiveMakerEventPresentation(
                        "VM ожидает переход на страницу " + engineEvent.page + ".",
                        false
                    )
            }
        }

    /** Advances through non-blocking TpWord controls until a user tap is required. */
    private fun advanceTextPlayback(playback: LiveMakerTextPlayback): String? {
        while (playback.nextSegmentIndex < playback.segments.size) {
            val segment = playback.segments[playback.nextSegmentIndex++]
            if (segment.clearBefore) playback.currentText = ""
            playback.currentText += segment.text
            if (segment.waitForTap) {
                playback.waitingForTap = true
                return playback.currentText
            }
        }
        playback.waitingForTap = false
        return null
    }

    private fun decodeImage(bytes: ByteArray, extension: String): Bitmap {
        if (extension == "gal") {
            return LiveMakerImageByteArraySource(bytes).use { source ->
                LiveMakerGalDecoder(maxDecodedPixels = MAX_IMAGE_PIXELS)
                    .decode(source)
                    .toBitmap()
            }
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("PNG dimensions could not be read.")
        }
        val pixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
        var sampleSize = 1
        while (pixels / (sampleSize.toLong() * sampleSize.toLong()) > MAX_IMAGE_PIXELS) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IOException("Android could not decode the PNG resource.")
    }

    private fun resolvePrivateGameDirectory(gameId: String): File {
        if (gameId.any { it.isISOControl() || it == '/' || it == '\\' }) {
            throw IOException("Некорректный идентификатор игры.")
        }
        val gamesRoot = File(filesDir, "games").canonicalFile
        val gameDirectory = File(gamesRoot, gameId).canonicalFile
        if (gameDirectory == gamesRoot ||
            !gameDirectory.toPath().startsWith(gamesRoot.toPath()) ||
            !gameDirectory.isDirectory
        ) {
            throw IOException("Папка игры отсутствует в приватной библиотеке Ramen.")
        }
        return gameDirectory
    }

    private fun publishError(error: Exception) {
        mainHandler.post {
            if (!isFinishing && !isDestroyed) {
                uiState.value = uiState.value.copy(
                    isLoading = false,
                    eventDescription = "Виртуальная машина остановлена из-за ошибки.",
                    canContinue = false,
                    waitingForTap = false,
                    errorMessage = error.message ?: "Ошибка LiveMaker-плеера."
                )
            }
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        liveSession?.close()
        mediaRepository?.close()
        audioPlayer?.close()
        uiState.value.imageBitmap?.let { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        sceneObjects.values.forEach { image ->
            if (!image.bitmap.isRecycled) image.bitmap.recycle()
        }
        sceneObjects.clear()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_GAME_ID = "io.github.helterfall.ramen.extra.LIVEMAKER_GAME_ID"
        private const val MAX_AUTOMATIC_HOST_COMMANDS = 512
        private const val MAX_IMAGE_PIXELS = 16_000_000L
    }
}

@Composable
private fun LiveMakerPlayerScreen(
    state: LiveMakerPlayerUiState,
    onContinue: () -> Unit,
    onAssetSelected: (LiveMakerMediaAsset) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101216))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = state.waitingForTap,
                onClick = onContinue
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "LiveMaker Player",
            color = Color(0xFFF4F1EA),
            style = MaterialTheme.typography.headlineSmall
        )
        if (state.isLoading) {
            Text("Загрузка стартового LSB и ресурсов…", color = Color(0xFFA8ADB7))
        } else {
            state.pageReference?.let { reference ->
                Text("Стартовая страница: " + reference, color = Color(0xFFF2B766))
            }
            state.scriptVersion?.let { version ->
                Text(
                    "LSB v" + version + " · команд: " + (state.commandCount ?: 0),
                    color = Color(0xFFA8ADB7)
                )
            }

            if (state.sceneImages.isNotEmpty()) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                ) {
                    val background = state.sceneImages.firstOrNull { it.isBackground }
                    val canvasWidth = (
                        background?.bitmap?.width?.toFloat()
                            ?: state.sceneImages.maxOf { it.left + it.width }
                        ).coerceAtLeast(1f)
                    val canvasHeight = (
                        background?.bitmap?.height?.toFloat()
                            ?: state.sceneImages.maxOf { it.top + it.height }
                        ).coerceAtLeast(1f)

                    background?.let { image ->
                        DisposableEffect(image.bitmap) {
                            onDispose {
                                if (!image.bitmap.isRecycled) image.bitmap.recycle()
                            }
                        }
                        Image(
                            bitmap = image.bitmap.asImageBitmap(),
                            contentDescription = image.resourcePath,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds
                        )
                    }

                    state.sceneImages.filterNot { it.isBackground }.forEach { image ->
                        DisposableEffect(image.bitmap) {
                            onDispose {
                                if (!image.bitmap.isRecycled) image.bitmap.recycle()
                            }
                        }
                        Image(
                            bitmap = image.bitmap.asImageBitmap(),
                            contentDescription = image.resourcePath,
                            modifier = Modifier
                                .offset(
                                    x = maxWidth * (image.left / canvasWidth),
                                    y = maxHeight * (image.top / canvasHeight)
                                )
                                .width(maxWidth * (image.width / canvasWidth).coerceIn(0.01f, 2f))
                                .height(maxHeight * (image.height / canvasHeight).coerceIn(0.01f, 2f))
                                .graphicsLayer(alpha = image.alpha),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            } else {
                state.imageBitmap?.let { bitmap ->
                    DisposableEffect(bitmap) {
                        onDispose {
                            if (!bitmap.isRecycled) bitmap.recycle()
                        }
                    }
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = state.selectedAssetName,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp),
                        contentScale = ContentScale.Fit
                    )
                    state.selectedAssetName?.let {
                        Text(it, color = Color(0xFFA8ADB7), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Surface(color = Color(0xFF1A1E25), modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(state.eventDescription, color = Color(0xFFF4F1EA))
                    if (state.dialogueText.isNotEmpty()) {
                        Text(
                            text = state.dialogueText,
                            color = Color(0xFFF4F1EA),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    if (state.waitingForTap) {
                        Text(
                            "Коснись экрана, чтобы продолжить",
                            color = Color(0xFFF2B766),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (state.canContinue) {
                        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                            Text("Продолжить")
                        }
                    }
                }
            }

            state.audioStatus?.let {
                Text(it, color = Color(0xFFA8ADB7), style = MaterialTheme.typography.bodySmall)
            }
            state.errorMessage?.let {
                Text(it, color = Color(0xFFFF9D9D), style = MaterialTheme.typography.bodySmall)
            }

            Text(
                text = "Медиафайлы (" + state.assets.size + ")",
                color = Color(0xFFF2B766),
                style = MaterialTheme.typography.titleMedium
            )
            state.mediaDiagnostics?.let { diagnostics ->
                val extensionSummary = diagnostics.indexedByExtension.entries
                    .sortedBy { it.key }
                    .joinToString { it.key.uppercase() + ": " + it.value }
                    .ifBlank { "нет" }
                Text(
                    "Проверено файлов: " + diagnostics.filesScanned +
                        " · VF/EXE: " + diagnostics.archivesParsed + "/" +
                        diagnostics.archiveCandidates +
                        " · записей: " + diagnostics.archiveEntriesVisited,
                    color = Color(0xFFA8ADB7),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Из архива: " + diagnostics.archiveAssetCount +
                        " · отдельно: " + diagnostics.looseAssetCount +
                        " · по форматам: " + extensionSummary,
                    color = Color(0xFFA8ADB7),
                    style = MaterialTheme.typography.bodySmall
                )
                if (diagnostics.archiveParseFailures > 0 ||
                    diagnostics.rejectedArchivePaths > 0 ||
                    diagnostics.scanLimitReached
                ) {
                    Text(
                        "Диагностика: ошибок разбора архивов " +
                            diagnostics.archiveParseFailures +
                            " · отклонённых путей " + diagnostics.rejectedArchivePaths +
                            if (diagnostics.scanLimitReached) " · достигнут лимит сканирования" else "",
                        color = Color(0xFFFFC27A),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            if (state.assets.isEmpty()) {
                val diagnostics = state.mediaDiagnostics
                val message = when {
                    diagnostics == null -> "Индекс ресурсов ещё не построен."
                    diagnostics.archivesParsed > 0 ->
                        "VF-архив прочитан (" + diagnostics.archiveEntriesVisited +
                            " записей), но GAL/PNG/OGG/WAV не попали в индекс."
                    diagnostics.archiveCandidates > 0 ->
                        "Найдено VF/EXE-кандидатов: " + diagnostics.archiveCandidates +
                            ", успешно разобрано: " + diagnostics.archivesParsed +
                            ". Проверьте диагностику ошибок выше."
                    else ->
                        "В приватной папке игры не найдено VF/EXE и отдельных GAL/PNG/OGG/WAV."
                }
                Text(message, color = Color(0xFFA8ADB7))
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(state.assets, key = { it.id }) { asset ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF1A1E25))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(asset.path, color = Color(0xFFF4F1EA))
                                Text(
                                    asset.storageDescription,
                                    color = Color(0xFFA8ADB7),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Button(onClick = { onAssetSelected(asset) }) {
                                Text(if (asset.extension == "gal" || asset.extension == "png") "Открыть" else "Играть")
                            }
                        }
                    }
                }
            }
        }
    }
}

private class LiveMakerImageByteArraySource(
    private val bytes: ByteArray
) : ReadOnlyRandomAccessSource {
    override val sizeBytes: Long
        get() = bytes.size.toLong()

    override fun readAt(
        position: Long,
        target: ByteArray,
        targetOffset: Int,
        length: Int
    ): Int {
        require(position >= 0L)
        require(targetOffset >= 0 && targetOffset <= target.size)
        require(length >= 0 && length <= target.size - targetOffset)
        if (length == 0) return 0
        if (position >= bytes.size.toLong()) return -1
        val count = minOf(length, bytes.size - position.toInt())
        System.arraycopy(bytes, position.toInt(), target, targetOffset, count)
        return count
    }

    override fun close() = Unit
}
