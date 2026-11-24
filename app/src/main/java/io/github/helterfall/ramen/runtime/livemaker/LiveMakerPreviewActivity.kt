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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.github.helterfall.ramen.inspection.ReadOnlyRandomAccessSource
import io.github.helterfall.ramen.runtime.systemnnn.SystemNnnAudioPlayer
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

private data class LiveMakerPreviewUiState(
    val isLoading: Boolean = true,
    val pageReference: String? = null,
    val scriptVersion: Int? = null,
    val commandCount: Int? = null,
    val eventDescription: String = "Подготовка LiveMaker-сессии…",
    val canContinue: Boolean = false,
    val assets: List<LiveMakerMediaAsset> = emptyList(),
    val imageBitmap: Bitmap? = null,
    val selectedAssetName: String? = null,
    val audioStatus: String? = null,
    val errorMessage: String? = null
)

/**
 * Limited LiveMaker runtime preview. It loads an LSB session and media assets,
 * but deliberately keeps TpWord/TextIns payloads opaque rather than rendering text.
 */
class LiveMakerPreviewActivity : ComponentActivity() {
    private val uiState = mutableStateOf(LiveMakerPreviewUiState())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var liveSession: LiveMakerGameSession? = null
    private var currentEvent: LiveMakerSessionEvent? = null
    private var mediaRepository: LiveMakerMediaAssetRepository? = null
    private var audioPlayer: SystemNnnAudioPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                LiveMakerPreviewScreen(
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
                val firstEvent = nextEvent(bootstrap.session)

                liveSession = bootstrap.session
                mediaRepository = assets
                currentEvent = firstEvent
                val details = describeEvent(firstEvent)
                mainHandler.post {
                    if (isFinishing || isDestroyed) {
                        bootstrap.session.close()
                        assets.close()
                    } else {
                        uiState.value = LiveMakerPreviewUiState(
                            isLoading = false,
                            pageReference = bootstrap.pageReference,
                            scriptVersion = bootstrap.scriptVersion,
                            commandCount = bootstrap.commandCount,
                            eventDescription = details.first,
                            canContinue = details.second,
                            assets = assets.listAssets()
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
                            errorMessage = error.message ?: "Не удалось открыть LiveMaker-предпросмотр."
                        )
                    }
                }
            }
        }
    }

    private fun continueSession() {
        worker.execute {
            val session = liveSession ?: return@execute
            val event = currentEvent
            when (event) {
                is LiveMakerSessionEvent.EngineEvent -> when (val engineEvent = event.event) {
                    is LiveMakerVmEvent.TextBlock -> {
                        if (engineEvent.shouldWait) session.resume()
                    }
                    is LiveMakerVmEvent.Delay -> session.resume()
                    else -> return@execute
                }
                else -> return@execute
            }

            try {
                val next = nextEvent(session)
                currentEvent = next
                val details = describeEvent(next)
                mainHandler.post {
                    if (!isFinishing && !isDestroyed) {
                        uiState.value = uiState.value.copy(
                            eventDescription = details.first,
                            canContinue = details.second,
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
        repeat(MAX_AUTOMATIC_PAGE_TRANSFERS) {
            val event = session.runUntilEvent()
            if (event !is LiveMakerSessionEvent.PageEntered) return event
        }
        throw IOException("LiveMaker reached too many consecutive page transfers.")
    }

    private fun describeEvent(event: LiveMakerSessionEvent): Pair<String, Boolean> =
        when (event) {
            is LiveMakerSessionEvent.PageEntered ->
                ("Открыта страница " + event.pageReference + ".") to false
            is LiveMakerSessionEvent.PageResolutionRequired ->
                ("Страница " + event.pageReference + " отсутствует в доступных LSB.") to false
            is LiveMakerSessionEvent.EngineEvent -> when (val engineEvent = event.event) {
                is LiveMakerVmEvent.TextBlock ->
                    ("Найден TextIns в команде " + engineEvent.commandIndex + " (" +
                        engineEvent.tpWordBytes.size + " байт TpWord). Блок остаётся непрозрачным; " +
                        "тап продолжит VM без отображения содержимого.") to true
                is LiveMakerVmEvent.Delay ->
                    ("Сценарий запросил паузу " + engineEvent.milliseconds +
                        " мс. Тап продолжит предпросмотр.") to true
                is LiveMakerVmEvent.HostCommandRequired ->
                    ("Остановка на команде сцены " + engineEvent.command.typeId +
                        ": её семантика ещё не подключена к Android-хосту.") to false
                is LiveMakerVmEvent.UnsupportedExpression ->
                    ("Остановка на неподдерживаемом выражении в команде " +
                        engineEvent.commandIndex + ": " + engineEvent.reason) to false
                LiveMakerVmEvent.AwaitingHost ->
                    "Виртуальная машина ожидает обработку со стороны хоста." to false
                LiveMakerVmEvent.Running ->
                    "Виртуальная машина выполняется." to false
                LiveMakerVmEvent.Completed ->
                    "Достигнут конец стартового сценария." to false
            }
        }

    private fun decodeImage(bytes: ByteArray, extension: String): Bitmap {
        if (extension == "gal") {
            return ByteArrayRandomAccessSource(bytes).use { source ->
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
                    errorMessage = error.message ?: "Ошибка LiveMaker-предпросмотра."
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
        super.onDestroy()
    }

    companion object {
        const val EXTRA_GAME_ID = "io.github.helterfall.ramen.extra.LIVEMAKER_GAME_ID"
        private const val MAX_AUTOMATIC_PAGE_TRANSFERS = 32
        private const val MAX_IMAGE_PIXELS = 16_000_000L
    }
}

@Composable
private fun LiveMakerPreviewScreen(
    state: LiveMakerPreviewUiState,
    onContinue: () -> Unit,
    onAssetSelected: (LiveMakerMediaAsset) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101216))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "LiveMaker · предпросмотр",
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

            Surface(color = Color(0xFF1A1E25), modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(state.eventDescription, color = Color(0xFFF4F1EA))
                    if (state.canContinue) {
                        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                            Text("Продолжить предпросмотр")
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
            if (state.assets.isEmpty()) {
                Text("GAL/PNG и OGG/WAV ресурсы не найдены.", color = Color(0xFFA8ADB7))
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

private class ByteArrayRandomAccessSource(
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
