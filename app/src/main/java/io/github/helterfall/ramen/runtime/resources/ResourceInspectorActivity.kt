package io.github.helterfall.ramen.runtime.resources

import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.helterfall.ramen.inspection.AndroidGpkImageDecoder
import io.github.helterfall.ramen.inspection.FileRandomAccessSource
import io.github.helterfall.ramen.inspection.GpkGtbArchiveParser
import io.github.helterfall.ramen.inspection.GpkImageResource
import io.github.helterfall.ramen.inspection.VpkAudioResource
import io.github.helterfall.ramen.inspection.VpkOggCacheMaterializer
import io.github.helterfall.ramen.inspection.VpkVtbArchiveParser
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

private val InspectorBackground = Color(0xFF101216)
private val InspectorSurface = Color(0xFF1A1E25)
private val InspectorRaised = Color(0xFF222832)
private val InspectorAccent = Color(0xFFF2B766)
private val InspectorText = Color(0xFFF4F1EA)
private val InspectorMuted = Color(0xFFA8ADB7)

private enum class ResourcePairKind(
    val firstExtension: String,
    val secondExtension: String,
    val title: String
) {
    IMAGES("gpk", "gtb", "GPK + GTB"),
    AUDIO("vpk", "vtb", "VPK + VTB")
}

private enum class ResourceTab {
    IMAGES,
    AUDIO
}

private data class SelectedDocument(
    val uri: Uri,
    val displayName: String,
    val extension: String,
    val stem: String
)

private data class ImageInspectorState(
    val entries: List<GpkImageResource> = emptyList(),
    val selected: GpkImageResource? = null,
    val bitmap: Bitmap? = null,
    val isBusy: Boolean = false,
    val message: String = "Выбери пару GPK + GTB, чтобы просмотреть изображения.",
    val archiveName: String? = null
)

private data class AudioInspectorState(
    val entries: List<VpkAudioResource> = emptyList(),
    val selected: VpkAudioResource? = null,
    val isBusy: Boolean = false,
    val isPlaying: Boolean = false,
    val isReady: Boolean = false,
    val message: String = "Выбери пару VPK + VTB, чтобы прослушать ресурсы.",
    val archiveName: String? = null
)

/**
 * Diagnostic resource browser for the sample-backed GPK/GTB and VPK/VTB formats.
 *
 * Documents are copied to app-private cache before parsing, so positional reads are
 * performed on seekable files and do not depend on a provider keeping a URI open.
 */
class ResourceInspectorActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val sessionFiles = CopyOnWriteArraySet<File>()
    private var imageFiles: Pair<File, File>? = null // GTB index, GPK archive
    private var audioFiles: Pair<File, File>? = null // VTB index, VPK archive
    private var mediaPlayer: MediaPlayer? = null
    private var currentOggCache: File? = null

    private val imageState = mutableStateOf(ImageInspectorState())
    private val audioState = mutableStateOf(AudioInspectorState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ResourceInspectorScreen(
                imageState = imageState.value,
                audioState = audioState.value,
                onBack = { finish() },
                onSelectImagePair = { uris -> loadImagePair(uris) },
                onSelectAudioPair = { uris -> loadAudioPair(uris) },
                onSelectImage = { resource -> decodeImage(resource) },
                onPlayAudio = { resource -> playAudio(resource) },
                onTogglePlayback = { togglePlayback() },
                onStopPlayback = { stopPlayback() }
            )
        }
    }

    override fun onStop() {
        super.onStop()
        val player = mediaPlayer
        if (player != null && runCatching { player.isPlaying }.getOrDefault(false)) {
            runCatching { player.pause() }
            audioState.value = audioState.value.copy(
                isPlaying = false,
                message = "Воспроизведение приостановлено."
            )
        }
    }

    override fun onDestroy() {
        stopPlayback()
        worker.shutdownNow()
        sessionFiles.forEach { file -> runCatching { file.delete() } }
        sessionFiles.clear()
        imageState.value.bitmap?.let { bitmap -> runCatching { bitmap.recycle() } }
        super.onDestroy()
    }

    private fun loadImagePair(uris: List<Uri>) {
        loadPair(ResourcePairKind.IMAGES, uris)
    }

    private fun loadAudioPair(uris: List<Uri>) {
        loadPair(ResourcePairKind.AUDIO, uris)
    }

    private fun loadPair(kind: ResourcePairKind, uris: List<Uri>) {
        if (uris.size != 2) {
            updatePairMessage(kind, "Нужно выбрать ровно два файла одной пары.")
            return
        }

        updatePairBusy(kind, true, "Проверяю выбранные файлы…")
        worker.execute {
            val copiedFiles = mutableListOf<File>()
            try {
                val documents = uris.map { uri ->
                    SelectedDocument(
                        uri = uri,
                        displayName = queryDisplayName(uri),
                        extension = extensionOf(queryDisplayName(uri)),
                        stem = stemOf(queryDisplayName(uri))
                    )
                }
                val expected = setOf(kind.firstExtension, kind.secondExtension)
                if (documents.map { it.extension }.toSet() != expected ||
                    documents.any { it.stem.isBlank() } ||
                    documents.map { it.stem.lowercase(Locale.ROOT) }.distinct().size != 1
                ) {
                    throw IOException(
                        "Выбери файлы с одинаковым именем: .${kind.firstExtension} и " +
                            ".${kind.secondExtension}."
                    )
                }

                val first = documents.single { it.extension == kind.firstExtension }
                val second = documents.single { it.extension == kind.secondExtension }
                val firstFile = copyDocumentToCache(first).also(copiedFiles::add)
                val secondFile = copyDocumentToCache(second).also(copiedFiles::add)

                when (kind) {
                    ResourcePairKind.IMAGES -> {
                        val parsed = FileRandomAccessSource(firstFile).use { archive ->
                            FileRandomAccessSource(secondFile).use { index ->
                                GpkGtbArchiveParser().parse(index, archive)
                            }
                        }
                        if (parsed.entries.isEmpty()) {
                            throw IOException("В GPK/GTB не найдено изображений.")
                        }
                        val initial = parsed.entries.first()
                        val bitmap = FileRandomAccessSource(secondFile).use { source ->
                            AndroidGpkImageDecoder().decode(source, initial)
                        }
                        runOnUiThread {
                            imageFiles?.let { old ->
                                sessionFiles.remove(old.first)
                                sessionFiles.remove(old.second)
                                old.first.delete()
                                old.second.delete()
                            }
                            imageFiles = firstFile to secondFile
                            copiedFiles.forEach(sessionFiles::add)
                            val oldBitmap = imageState.value.bitmap
                            imageState.value = ImageInspectorState(
                                entries = parsed.entries,
                                selected = initial,
                                bitmap = bitmap,
                                message = "Найдено изображений: ${parsed.entries.size}.",
                                archiveName = first.displayName.removeSuffix(".${kind.firstExtension}")
                            )
                            if (oldBitmap != null && oldBitmap !== bitmap) {
                                runCatching { oldBitmap.recycle() }
                            }
                        }
                    }
                    ResourcePairKind.AUDIO -> {
                        val parsed = FileRandomAccessSource(firstFile).use { archive ->
                            FileRandomAccessSource(secondFile).use { index ->
                                VpkVtbArchiveParser().parse(index, archive)
                            }
                        }
                        if (parsed.entries.isEmpty()) {
                            throw IOException("В VPK/VTB не найдено аудиоресурсов.")
                        }
                        runOnUiThread {
                            audioFiles?.let { old ->
                                sessionFiles.remove(old.first)
                                sessionFiles.remove(old.second)
                                old.first.delete()
                                old.second.delete()
                            }
                            audioFiles = firstFile to secondFile
                            copiedFiles.forEach(sessionFiles::add)
                            audioState.value = AudioInspectorState(
                                entries = parsed.entries,
                                message = "Найдено аудиоресурсов: ${parsed.entries.size}" +
                                    if (parsed.hasTerminalIndexSentinel) " (служебный терминатор учтён)." else ".",
                                archiveName = first.displayName.removeSuffix(".${kind.firstExtension}")
                            )
                        }
                    }
                }
            } catch (error: Exception) {
                copiedFiles.forEach { file ->
                    sessionFiles.remove(file)
                    runCatching { file.delete() }
                }
                updatePairMessage(
                    kind,
                    error.message ?: "Не удалось открыть выбранную пару."
                )
            } finally {
                updatePairBusy(kind, false, null)
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        val cursor: Cursor? = contentResolver.query(uri, projection, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) it.getString(index)?.takeIf(String::isNotBlank)?.let { name ->
                    return name
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
            ?: throw IOException("Android не сообщил имя выбранного файла.")
    }

    private fun copyDocumentToCache(document: SelectedDocument): File {
        val target = File.createTempFile(
            "ramen-${document.extension}-",
            ".${document.extension}",
            cacheDir
        )
        try {
            val input = contentResolver.openInputStream(document.uri)
                ?: throw IOException("Не удалось открыть ${document.displayName}.")
            input.use { source ->
                target.outputStream().buffered(COPY_BUFFER_BYTES).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
                        if (total > MAX_ARCHIVE_COPY_BYTES) {
                            throw IOException(
                                "Файл ${document.displayName} превышает лимит 512 МБ."
                            )
                        }
                        output.write(buffer, 0, count)
                    }
                    if (total == 0L) {
                        throw IOException("Файл ${document.displayName} пуст.")
                    }
                }
            }
            return target
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }

    private fun decodeImage(resource: GpkImageResource) {
        val files = imageFiles ?: return
        imageState.value = imageState.value.copy(
            selected = resource,
            message = "Декодирую ${resource.name}…"
        )
        worker.execute {
            try {
                val bitmap = FileRandomAccessSource(files.second).use { source ->
                    AndroidGpkImageDecoder().decode(source, resource)
                }
                runOnUiThread {
                    val previous = imageState.value.bitmap
                    imageState.value = imageState.value.copy(
                        selected = resource,
                        bitmap = bitmap,
                        message = "${resource.format} · ${resource.widthPixels} × ${resource.heightPixels}"
                    )
                    if (previous != null && previous !== bitmap) {
                        runCatching { previous.recycle() }
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    imageState.value = imageState.value.copy(
                        selected = resource,
                        message = error.message ?: "Не удалось декодировать изображение."
                    )
                }
            }
        }
    }

    private fun playAudio(resource: VpkAudioResource) {
        val files = audioFiles ?: return
        stopPlayback()
        audioState.value = audioState.value.copy(
            selected = resource,
            isBusy = true,
            isPlaying = false,
            message = "Подготавливаю ${resource.name}…"
        )
        worker.execute {
            var staged: File? = null
            try {
                staged = FileRandomAccessSource(files.second).use { source ->
                    VpkOggCacheMaterializer().materialize(source, resource, cacheDir)
                }
                sessionFiles.add(staged)
                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        staged?.let { file ->
                            sessionFiles.remove(file)
                            file.delete()
                        }
                        return@runOnUiThread
                    }
                    startMediaPlayer(staged!!, resource)
                }
            } catch (error: Exception) {
                staged?.let { file ->
                    sessionFiles.remove(file)
                    file.delete()
                }
                runOnUiThread {
                    audioState.value = audioState.value.copy(
                        isBusy = false,
                        isPlaying = false,
                        message = error.message ?: "Не удалось подготовить Ogg-поток."
                    )
                }
            }
        }
    }

    private fun startMediaPlayer(file: File, resource: VpkAudioResource) {
        stopPlayback()
        val player = MediaPlayer()
        mediaPlayer = player
        currentOggCache = file

        player.setOnPreparedListener { prepared ->
            if (mediaPlayer === prepared) {
                prepared.start()
                audioState.value = audioState.value.copy(
                    selected = resource,
                    isBusy = false,
                    isPlaying = true,
                    isReady = true,
                    message = "Воспроизводится ${resource.name}."
                )
            }
        }
        player.setOnCompletionListener { completed ->
            if (mediaPlayer === completed) {
                audioState.value = audioState.value.copy(
                    isBusy = false,
                    isPlaying = false,
                    message = "Воспроизведение завершено."
                )
            }
        }
        player.setOnErrorListener { failed, what, extra ->
            if (mediaPlayer === failed) {
                releasePlayer(deleteCache = true)
                audioState.value = audioState.value.copy(
                    isBusy = false,
                    isPlaying = false,
                    isReady = false,
                    message = "Android не смог воспроизвести этот Ogg-поток (ошибка $what/$extra)."
                )
            }
            true
        }

        try {
            player.setDataSource(file.absolutePath)
            player.prepareAsync()
        } catch (error: Exception) {
            releasePlayer(deleteCache = true)
            audioState.value = audioState.value.copy(
                isBusy = false,
                isPlaying = false,
                message = error.message ?: "Не удалось запустить Android-медиаплеер."
            )
        }
    }

    private fun togglePlayback() {
        val player = mediaPlayer ?: return
        runCatching {
            if (player.isPlaying) {
                player.pause()
                audioState.value = audioState.value.copy(
                    isPlaying = false,
                    message = "Воспроизведение приостановлено."
                )
            } else {
                player.start()
                audioState.value = audioState.value.copy(
                    isPlaying = true,
                    message = "Воспроизведение продолжается."
                )
            }
        }.onFailure { error ->
            audioState.value = audioState.value.copy(
                isPlaying = false,
                message = error.message ?: "Не удалось изменить состояние плеера."
            )
        }
    }

    private fun stopPlayback() {
        releasePlayer(deleteCache = true)
        audioState.value = audioState.value.copy(
            isBusy = false,
            isPlaying = false,
            isReady = false
        )
    }

    private fun releasePlayer(deleteCache: Boolean) {
        val player = mediaPlayer
        mediaPlayer = null
        if (player != null) {
            runCatching { player.setOnPreparedListener(null) }
            runCatching { player.setOnCompletionListener(null) }
            runCatching { player.setOnErrorListener(null) }
            runCatching { player.stop() }
            runCatching { player.reset() }
            runCatching { player.release() }
        }
        if (deleteCache) {
            currentOggCache?.let { file ->
                sessionFiles.remove(file)
                runCatching { file.delete() }
            }
            currentOggCache = null
        }
    }

    private fun updatePairBusy(kind: ResourcePairKind, busy: Boolean, message: String?) {
        runOnUiThread {
            when (kind) {
                ResourcePairKind.IMAGES -> imageState.value = imageState.value.copy(
                    isBusy = busy,
                    message = message ?: imageState.value.message
                )
                ResourcePairKind.AUDIO -> audioState.value = audioState.value.copy(
                    isBusy = busy,
                    message = message ?: audioState.value.message
                )
            }
        }
    }

    private fun updatePairMessage(kind: ResourcePairKind, message: String) {
        runOnUiThread {
            when (kind) {
                ResourcePairKind.IMAGES -> imageState.value = imageState.value.copy(
                    isBusy = false,
                    message = message
                )
                ResourcePairKind.AUDIO -> audioState.value = audioState.value.copy(
                    isBusy = false,
                    message = message
                )
            }
        }
    }

    private fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    private fun stemOf(name: String): String =
        name.substringBeforeLast('.', name).trim()

    companion object {
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val MAX_ARCHIVE_COPY_BYTES = 512L * 1024L * 1024L
    }
}

@Composable
private fun ResourceInspectorScreen(
    imageState: ImageInspectorState,
    audioState: AudioInspectorState,
    onBack: () -> Unit,
    onSelectImagePair: (List<Uri>) -> Unit,
    onSelectAudioPair: (List<Uri>) -> Unit,
    onSelectImage: (GpkImageResource) -> Unit,
    onPlayAudio: (VpkAudioResource) -> Unit,
    onTogglePlayback: () -> Unit,
    onStopPlayback: () -> Unit
) {
    var selectedTab by androidx.compose.runtime.remember { mutableStateOf(ResourceTab.IMAGES) }
    var pendingPair by androidx.compose.runtime.remember { mutableStateOf(ResourcePairKind.IMAGES) }

    val pairPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        when (pendingPair) {
            ResourcePairKind.IMAGES -> onSelectImagePair(uris)
            ResourcePairKind.AUDIO -> onSelectAudioPair(uris)
        }
    }

    val colors = darkColorScheme(
        primary = InspectorAccent,
        onPrimary = Color(0xFF24190B),
        secondary = Color(0xFFB8CBBE),
        background = InspectorBackground,
        surface = InspectorSurface,
        surfaceVariant = InspectorRaised,
        onSurface = InspectorText,
        onSurfaceVariant = InspectorMuted
    )

    MaterialTheme(colorScheme = colors) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(InspectorBackground)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onBack) { Text("Назад") }
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "ИНСПЕКТОР РЕСУРСОВ",
                        color = InspectorText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Text(
                        "GPK · GTB · VPK · VTB",
                        color = InspectorMuted,
                        fontSize = 10.sp
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Button(
                    onClick = {
                        pendingPair = ResourcePairKind.IMAGES
                        pairPicker.launch(arrayOf("*/*"))
                    },
                    enabled = !imageState.isBusy,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = InspectorAccent)
                ) {
                    Text(
                        if (imageState.isBusy) "Загрузка…" else "Выбрать GPK + GTB",
                        color = Color(0xFF24190B),
                        fontSize = 11.sp
                    )
                }
                Button(
                    onClick = {
                        pendingPair = ResourcePairKind.AUDIO
                        pairPicker.launch(arrayOf("*/*"))
                    },
                    enabled = !audioState.isBusy,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = InspectorAccent)
                ) {
                    Text(
                        if (audioState.isBusy) "Загрузка…" else "Выбрать VPK + VTB",
                        color = Color(0xFF24190B),
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(Modifier.height(13.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selectedTab == ResourceTab.IMAGES) {
                    Button(onClick = { selectedTab = ResourceTab.IMAGES }) {
                        Text("Изображения · ${imageState.entries.size}")
                    }
                } else {
                    OutlinedButton(onClick = { selectedTab = ResourceTab.IMAGES }) {
                        Text("Изображения · ${imageState.entries.size}")
                    }
                }
                if (selectedTab == ResourceTab.AUDIO) {
                    Button(onClick = { selectedTab = ResourceTab.AUDIO }) {
                        Text("Аудио · ${audioState.entries.size}")
                    }
                } else {
                    OutlinedButton(onClick = { selectedTab = ResourceTab.AUDIO }) {
                        Text("Аудио · ${audioState.entries.size}")
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            when (selectedTab) {
                ResourceTab.IMAGES -> {
                    ImagePreviewPanel(imageState, onSelectImage)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        imageState.message,
                        color = InspectorMuted,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(imageState.entries, key = { _, item -> item.name }) { _, item ->
                            ResourceRow(
                                title = item.name,
                                subtitle = "${item.format} · ${item.widthPixels} × ${item.heightPixels} · ${formatBytes(item.payloadSizeBytes)}",
                                selected = imageState.selected?.name == item.name,
                                onClick = { onSelectImage(item) }
                            )
                        }
                    }
                }
                ResourceTab.AUDIO -> {
                    AudioControlPanel(audioState, onTogglePlayback, onStopPlayback)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        audioState.message,
                        color = InspectorMuted,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(audioState.entries, key = { _, item -> item.name }) { _, item ->
                            ResourceRow(
                                title = item.name,
                                subtitle = "${item.sampleRateHz} Гц · ${item.channels} ch · ${item.bitsPerSample} bit · ${item.oggPageCount} Ogg-стр.",
                                selected = audioState.selected?.name == item.name,
                                onClick = { onPlayAudio(item) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImagePreviewPanel(
    state: ImageInspectorState,
    onSelectImage: (GpkImageResource) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = InspectorSurface),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(11.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            val bitmap = state.bitmap
            if (bitmap != null && !bitmap.isRecycled) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = state.selected?.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0A0C0F)),
                    contentScale = ContentScale.Fit
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(InspectorRaised),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Нет изображения", color = InspectorMuted, fontSize = 12.sp)
                }
            }
            Text(
                state.selected?.name ?: "Ресурс не выбран",
                color = InspectorText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (state.selected != null) {
                Text(
                    "${state.selected.widthPixels} × ${state.selected.heightPixels} · ${formatBytes(state.selected.payloadSizeBytes)}",
                    color = InspectorMuted,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun AudioControlPanel(
    state: AudioInspectorState,
    onTogglePlayback: () -> Unit,
    onStopPlayback: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = InspectorSurface),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                state.selected?.name ?: "Аудиоресурс не выбран",
                color = InspectorText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                state.selected?.let {
                    "${it.sampleRateHz} Гц · ${it.channels} канал(а) · ${it.bitsPerSample} бит"
                } ?: "Выбери аудиоресурс из списка ниже.",
                color = InspectorMuted,
                fontSize = 11.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onTogglePlayback,
                    enabled = state.isReady && !state.isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = InspectorAccent)
                ) {
                    Text(
                        if (state.isPlaying) "Пауза" else "Продолжить",
                        color = Color(0xFF24190B)
                    )
                }
                OutlinedButton(
                    onClick = onStopPlayback,
                    enabled = state.isReady
                ) {
                    Text("Стоп")
                }
            }
        }
    }
}

@Composable
private fun ResourceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = if (selected) Color(0xFF34302A) else InspectorSurface,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                title,
                color = InspectorText,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                subtitle,
                color = InspectorMuted,
                fontSize = 10.sp
            )
        }
    }
}

private fun formatBytes(value: Long): String = when {
    value >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MiB", value / (1024.0 * 1024.0))
    value >= 1024L -> String.format(Locale.ROOT, "%.1f KiB", value / 1024.0)
    else -> "$value B"
}
