package io.github.helterfall.ramen.runtime.resources

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
import io.github.helterfall.ramen.runtime.nativeaudio.AndroidGpkImageDecoder
import io.github.helterfall.ramen.inspection.FileRandomAccessSource
import io.github.helterfall.ramen.inspection.GpkGtbArchiveParser
import io.github.helterfall.ramen.inspection.GpkImageResource
import io.github.helterfall.ramen.inspection.VpkAudioResource
import io.github.helterfall.ramen.inspection.VpkOggCacheMaterializer
import io.github.helterfall.ramen.storage.StorageGuardedOutputStream
import io.github.helterfall.ramen.storage.StorageSpaceGuard
import io.github.helterfall.ramen.parser.SystemNnnScriptLoader
import io.github.helterfall.ramen.parser.SystemNnnCommandStreamReader
import io.github.helterfall.ramen.parser.SystemNnnProjectCatalog
import io.github.helterfall.ramen.parser.SystemNnnModuleReferenceScanner
import io.github.helterfall.ramen.parser.SystemNnnScriptTransferScanner
import io.github.helterfall.ramen.parser.SystemNnnModuleReferenceReportWriter
import io.github.helterfall.ramen.parser.SystemNnnModuleReferenceProjectReport

import io.github.helterfall.ramen.inspection.VpkVtbArchiveParser
import java.io.File
import java.io.IOException
import java.nio.file.Files
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
    AUDIO,
    TEXT
}

private data class SelectedDocument(
    val uri: Uri,
    val displayName: String,
    val extension: String,
    val stem: String
)

private data class ResourceArchivePair(
    val kind: ResourcePairKind,
    val archiveFile: File,
    val indexFile: File,
    val displayName: String
)

private data class InitialImagePreview(
    val entries: List<GpkImageResource>,
    val selected: GpkImageResource?,
    val bitmap: Bitmap?
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

private data class TextPoolMetadata(
    val name: String,
    val recordCount: Int,
    val decodingErrorCount: Int,
    val rawBytes: Long,
    val addressTableOffset: Long?,
    val dataOffset: Long?,
    val endOffset: Long?
)

private data class TextModuleMetadata(
    val name: String,
    val fileSizeBytes: Long,
    val commandBytes: Int,
    val pools: List<TextPoolMetadata>,
    val scanError: String? = null
)

private data class TextInspectorState(
    val modules: List<TextModuleMetadata> = emptyList(),
    val message: String = "Открой инспектор из импортированной игры, чтобы просмотреть структуру SPT.",
    val projectManifestCount: Int = 0,
    val isExportingModuleReferences: Boolean = false,
    val exportMessage: String? = null
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
    private var imageFiles: Pair<File, File>? = null // GPK archive, GTB index
    private var audioFiles: Pair<File, File>? = null // VPK archive, VTB index
    private var mediaPlayer: MediaPlayer? = null
    private var currentOggCache: File? = null

    private val imageState = mutableStateOf(ImageInspectorState())
    private val audioState = mutableStateOf(AudioInspectorState())
    private val textState = mutableStateOf(TextInspectorState())
    private val discoveredPairsState = mutableStateOf<List<ResourceArchivePair>>(emptyList())
    private val sourceFolderMessageState = mutableStateOf<String?>(null)
    @Volatile
    private var importedGameRoot: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ResourceInspectorScreen(
                imageState = imageState.value,
                audioState = audioState.value,
                textState = textState.value,
                discoveredPairs = discoveredPairsState.value,
                sourceFolderMessage = sourceFolderMessageState.value,
                onBack = { finish() },
                onSelectImagePair = { uris -> loadImagePair(uris) },
                onSelectAudioPair = { uris -> loadAudioPair(uris) },
                onSelectArchivePair = { pair -> loadImportedPair(pair) },
                onSelectImage = { resource -> decodeImage(resource) },
                onPlayAudio = { resource -> playAudio(resource) },
                onTogglePlayback = { togglePlayback() },
                onStopPlayback = { stopPlayback() },
                onExportModuleReferences = { uri -> saveModuleReferenceReport(uri) }
            )
        }

        intent.getStringExtra(EXTRA_GAME_RELATIVE_DIRECTORY)?.let(::scanImportedGameFolder)
    }

    override fun onStop() {
        super.onStop()
        // This screen is a foreground inspector, not a background audio service.
        // Releasing also prevents an asynchronous prepare callback from starting playback
        // after the Activity has moved behind another screen.
        stopPlayback()
    }

    override fun onDestroy() {
        stopPlayback()
        worker.shutdownNow()
        sessionFiles.forEach { file -> runCatching { file.delete() } }
        sessionFiles.clear()
        imageState.value.bitmap?.let { bitmap -> runCatching { bitmap.recycle() } }
        super.onDestroy()
    }

    private fun decodeFirstAvailableImage(
        archiveFile: File,
        resources: List<GpkImageResource>
    ): InitialImagePreview {
        val updatedEntries = resources.toMutableList()

        for (index in updatedEntries.indices) {
            val resource = updatedEntries[index]
            if (!resource.isValid) continue

            try {
                val bitmap = FileRandomAccessSource(archiveFile).use { source ->
                    AndroidGpkImageDecoder().decode(source, resource)
                }
                return InitialImagePreview(updatedEntries, resource, bitmap)
            } catch (error: Exception) {
                updatedEntries[index] = resource.copy(
                    validationError = error.message ?: "Не удалось декодировать этот ресурс."
                )
            }
        }

        return InitialImagePreview(updatedEntries, null, null)
    }

    private fun loadImagePair(uris: List<Uri>) {
        loadPair(ResourcePairKind.IMAGES, uris)
    }

    private fun loadAudioPair(uris: List<Uri>) {
        loadPair(ResourcePairKind.AUDIO, uris)
    }

    /**
     * Opens resources directly from a game that has already been imported into
     * Ramen's private games/ directory. This avoids requiring the document picker
     * to expose proprietary extensions as standalone import types.
     */
    private fun scanImportedGameFolder(relativeDirectory: String) {
        sourceFolderMessageState.value = "Ищу пары ресурсов в импортированной папке…"
        worker.execute {
            try {
                val segments = relativeDirectory.split('/')
                if (!relativeDirectory.startsWith("games/") ||
                    segments.size < 2 ||
                    segments.any { it.isBlank() || it == "." || it == ".." } ||
                    File(relativeDirectory).isAbsolute
                ) {
                    throw IOException("Некорректный путь к импортированной игре.")
                }

                val gamesRoot = File(filesDir, "games").canonicalFile
                val gameRoot = File(filesDir, relativeDirectory).canonicalFile
                if (!gameRoot.isDirectory ||
                    !gameRoot.path.startsWith(gamesRoot.path + File.separator)
                ) {
                    throw IOException("Папка игры не найдена в приватном хранилище Ramen.")
                }

                importedGameRoot = gameRoot
                val pairs = discoverResourcePairs(gameRoot)
                val textModules = discoverScriptModules(gameRoot)
                val projectFiles = discoverProjectCatalogFiles(gameRoot)
                runOnUiThread {
                    discoveredPairsState.value = pairs
                    textState.value = TextInspectorState(
                        modules = textModules,
                        projectManifestCount = projectFiles.size,
                        message = if (textModules.isEmpty()) {
                            "В импортированной папке не найдены корректные SPT-модули."
                        } else {
                            "Найдено SPT-модулей: ${textModules.size}. Ниже показана структура пулов без текста сценария."
                        }
                    )
                    sourceFolderMessageState.value = when {
                        pairs.isNotEmpty() && textModules.isNotEmpty() ->
                            "Найдено пар ресурсов: ${pairs.size}; SPT-модулей: ${textModules.size}."
                        pairs.isNotEmpty() ->
                            "Найдено пар ресурсов: ${pairs.size}. Выбери нужную пару ниже."
                        textModules.isNotEmpty() ->
                            "Пары GPK/GTB и VPK/VTB не найдены; SPT-модулей: ${textModules.size}."
                        else ->
                            "В импортированной папке не найдены пары ресурсов или корректные SPT-модули."
                    }
                }
            } catch (error: Exception) {
                importedGameRoot = null
                runOnUiThread {
                    discoveredPairsState.value = emptyList()
                    textState.value = TextInspectorState(
                        message = error.message ?: "Не удалось просканировать SPT-модули."
                    )
                    sourceFolderMessageState.value =
                        error.message ?: "Не удалось просканировать папку игры."
                }
            }
        }
    }


    private fun discoverProjectCatalogFiles(root: File): List<File> {
        val found = root.walkTopDown()
            .maxDepth(MAX_SCRIPT_SCAN_DEPTH)
            .filter { file ->
                file.isFile &&
                    !Files.isSymbolicLink(file.toPath()) &&
                    file.name.equals("project.xtx", ignoreCase = true)
            }
            .take(MAX_PROJECT_MANIFESTS + 1)
            .toList()

        if (found.size > MAX_PROJECT_MANIFESTS) {
            throw IOException("В игре найдено слишком много project.xtx для диагностического экспорта.")
        }
        return found.sortedBy { it.relativeTo(root).invariantSeparatorsPath }
    }

    private fun saveModuleReferenceReport(uri: Uri) {
        val gameRoot = importedGameRoot
        if (gameRoot == null) {
            textState.value = textState.value.copy(
                exportMessage = "Импортированная папка игры больше недоступна."
            )
            return
        }

        textState.value = textState.value.copy(
            isExportingModuleReferences = true,
            exportMessage = "Собираю ссылки на имена модулей и соседние записи…"
        )
        worker.execute {
            try {
                val projectFiles = discoverProjectCatalogFiles(gameRoot)
                if (projectFiles.isEmpty()) {
                    throw IOException("В импортированной игре не найден project.xtx.")
                }

                val scriptLoader = SystemNnnScriptLoader()
                val streamReader = SystemNnnCommandStreamReader()
                val scanner = SystemNnnModuleReferenceScanner(streamReader)
                val transferScanner = SystemNnnScriptTransferScanner(streamReader)
                val reports = projectFiles.map { projectFile ->
                    val projectPath = projectFile.relativeTo(gameRoot).invariantSeparatorsPath
                    try {
                        val catalog = SystemNnnProjectCatalog.load(projectFile)
                        val modules = catalog.resolveModules(projectFile)
                        val candidates =
                            ArrayList<io.github.helterfall.ramen.parser.SystemNnnModuleReferenceCandidate>()
                        val scriptTransfers =
                            ArrayList<io.github.helterfall.ramen.parser.SystemNnnScriptTransferCandidate>()
                        var unresolvedTransferTargets = 0
                        val warnings = ArrayList<String>()

                        modules.forEach { module ->
                            try {
                                val script = scriptLoader.load(module.scriptFile)
                                val stream = streamReader.read(script)
                                candidates += scanner.scan(
                                    sourceModuleName = module.name,
                                    script = script,
                                    stream = stream,
                                    projectModuleNames = catalog.moduleNames
                                )
                                val transferResult = transferScanner.scan(
                                    sourceModuleName = module.name,
                                    script = script,
                                    stream = stream,
                                    projectModuleNames = catalog.moduleNames
                                )
                                scriptTransfers += transferResult.candidates
                                unresolvedTransferTargets += transferResult.unresolvedTargetCount
                            } catch (error: Exception) {
                                warnings += "${module.name}: ${error.message ?: "ошибка чтения SPT"}"
                            }
                        }

                        SystemNnnModuleReferenceProjectReport(
                            projectPath = projectPath,
                            moduleCount = modules.size,
                            candidates = candidates,
                            warnings = warnings,
                            scriptTransfers = scriptTransfers,
                            unresolvedScriptTransferTargetCount = unresolvedTransferTargets
                        )
                    } catch (error: Exception) {
                        SystemNnnModuleReferenceProjectReport(
                            projectPath = projectPath,
                            moduleCount = 0,
                            candidates = emptyList(),
                            warnings = listOf(error.message ?: "Не удалось прочитать project.xtx.")
                        )
                    }
                }

                val candidateCount = reports.sumOf { it.candidates.size }
                val transferCount = reports.sumOf { it.scriptTransfers.size }
                val warningCount = reports.sumOf { it.warnings.size }
                val output = contentResolver.openOutputStream(uri)
                    ?: throw IOException("Не удалось открыть выбранный документ для записи.")
                output.use { stream ->
                    SystemNnnModuleReferenceReportWriter().write(reports, stream)
                }

                runOnUiThread {
                    textState.value = textState.value.copy(
                        isExportingModuleReferences = false,
                        exportMessage = "Отчёт сохранён: переходов $transferCount · обычных ссылок $candidateCount · предупреждений $warningCount."
                    )
                }
            } catch (error: Exception) {
                runOnUiThread {
                    textState.value = textState.value.copy(
                        isExportingModuleReferences = false,
                        exportMessage = error.message ?: "Не удалось сохранить карту ссылок."
                    )
                }
            }
        }
    }

    private fun discoverResourcePairs(root: File): List<ResourceArchivePair> =
        ImportedResourcePairScanner().scan(root).map { discovered ->
            ResourceArchivePair(
                kind = when (discovered.family) {
                    ResourceArchiveFamily.IMAGES -> ResourcePairKind.IMAGES
                    ResourceArchiveFamily.AUDIO -> ResourcePairKind.AUDIO
                },
                archiveFile = discovered.archiveFile,
                indexFile = discovered.indexFile,
                displayName = discovered.relativePath
            )
        }

    /**
     * Builds a metadata-only inventory of the SPT pools for the Text Data tab.
     * Scenario strings are not copied into UI state or rendered.
     */
    private fun discoverScriptModules(root: File): List<TextModuleMetadata> =
        root.walkTopDown()
            .maxDepth(MAX_SCRIPT_SCAN_DEPTH)
            .filter { file ->
                file.isFile && file.extension.equals("spt", ignoreCase = true)
            }
            .take(MAX_DISCOVERED_SCRIPTS)
            .map { file ->
                val relativeName = file.relativeTo(root).invariantSeparatorsPath
                try {
                    val script = SystemNnnScriptLoader().load(file)
                    TextModuleMetadata(
                        name = relativeName,
                        fileSizeBytes = script.fileSizeBytes,
                        commandBytes = script.commandByteCount,
                        pools = script.stringPools.map { pool ->
                            TextPoolMetadata(
                                name = pool.name,
                                recordCount = pool.entries.size,
                                decodingErrorCount = pool.entries.count { it.hasDecodingErrors },
                                rawBytes = pool.entries.sumOf { it.rawBytes.size.toLong() },
                                addressTableOffset = pool.addressTableOffset,
                                dataOffset = pool.dataOffset,
                                endOffset = pool.endOffset
                            )
                        }
                    )
                } catch (error: Exception) {
                    TextModuleMetadata(
                        name = relativeName,
                        fileSizeBytes = file.length(),
                        commandBytes = 0,
                        pools = emptyList(),
                        scanError = error.message ?: "Не удалось прочитать SPT."
                    )
                }
            }
            .toList()
            .sortedBy(TextModuleMetadata::name)

    private fun loadImportedPair(pair: ResourceArchivePair) {
        if (pair.kind == ResourcePairKind.AUDIO) stopPlayback()
        updatePairBusy(pair.kind, true, "Проверяю ${pair.displayName}…")
        worker.execute {
            try {
                when (pair.kind) {
                    ResourcePairKind.IMAGES -> {
                        val parsed = FileRandomAccessSource(pair.archiveFile).use { archive ->
                            FileRandomAccessSource(pair.indexFile).use { index ->
                                GpkGtbArchiveParser().parse(index, archive)
                            }
                        }
                        if (parsed.entries.isEmpty()) {
                            throw IOException("В GPK/GTB не найдено записей.")
                        }
                        val preview = decodeFirstAvailableImage(pair.archiveFile, parsed.entries)

                        runOnUiThread {
                            releaseSessionPair(imageFiles)
                            imageFiles = pair.archiveFile to pair.indexFile
                            val oldBitmap = imageState.value.bitmap
                            imageState.value = ImageInspectorState(
                                entries = preview.entries,
                                selected = preview.selected,
                                bitmap = preview.bitmap,
                                message = imageSummary(preview.entries),
                                archiveName = pair.displayName
                            )
                            if (oldBitmap != null && oldBitmap !== preview.bitmap) {
                                runCatching { oldBitmap.recycle() }
                            }
                        }
                    }
                    ResourcePairKind.AUDIO -> {
                        val parsed = FileRandomAccessSource(pair.archiveFile).use { archive ->
                            FileRandomAccessSource(pair.indexFile).use { index ->
                                VpkVtbArchiveParser().parse(index, archive)
                            }
                        }
                        if (parsed.entries.isEmpty()) {
                            throw IOException("В VPK/VTB не найдено аудиоресурсов.")
                        }

                        runOnUiThread {
                            releaseSessionPair(audioFiles)
                            audioFiles = pair.archiveFile to pair.indexFile
                            audioState.value = AudioInspectorState(
                                entries = parsed.entries,
                                message = "Найдено аудиоресурсов: ${parsed.entries.size}" +
                                    if (parsed.hasTerminalIndexSentinel) " (служебный терминатор учтён)." else ".",
                                archiveName = pair.displayName
                            )
                        }
                    }
                }
            } catch (error: Exception) {
                updatePairMessage(
                    pair.kind,
                    error.message ?: "Не удалось прочитать пару ресурсов."
                )
            } finally {
                updatePairBusy(pair.kind, false, null)
            }
        }
    }

    private fun releaseSessionPair(files: Pair<File, File>?) {
        files?.let { pair ->
            listOf(pair.first, pair.second).forEach { file ->
                if (sessionFiles.remove(file)) {
                    runCatching { file.delete() }
                }
            }
        }
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
                    val displayName = queryDisplayName(uri)
                    SelectedDocument(
                        uri = uri,
                        displayName = displayName,
                        extension = extensionOf(displayName),
                        stem = stemOf(displayName)
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
                            throw IOException("В GPK/GTB не найдено записей.")
                        }
                        val preview = decodeFirstAvailableImage(firstFile, parsed.entries)
                        runOnUiThread {
                            releaseSessionPair(imageFiles)
                            imageFiles = firstFile to secondFile
                            copiedFiles.forEach(sessionFiles::add)
                            val oldBitmap = imageState.value.bitmap
                            imageState.value = ImageInspectorState(
                                entries = preview.entries,
                                selected = preview.selected,
                                bitmap = preview.bitmap,
                                message = imageSummary(preview.entries),
                                archiveName = first.displayName.removeSuffix(".${kind.firstExtension}")
                            )
                            if (oldBitmap != null && oldBitmap !== preview.bitmap) {
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
                            releaseSessionPair(audioFiles)
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
            val storageGuard = StorageSpaceGuard(cacheDir)
            input.use { source ->
                StorageGuardedOutputStream(
                    delegate = target.outputStream().buffered(COPY_BUFFER_BYTES),
                    guard = storageGuard
                ).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
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

    private fun imageSummary(entries: List<GpkImageResource>): String {
        val invalidCount = entries.count { !it.isValid }
        return "Записей: ${entries.size}; доступны: ${entries.size - invalidCount}; с ошибками: $invalidCount."
    }

    private fun decodeImage(resource: GpkImageResource) {
        val files = imageFiles ?: return
        if (!resource.isValid) {
            imageState.value = imageState.value.copy(
                selected = resource,
                message = resource.validationError ?: "Ресурс повреждён."
            )
            return
        }
        imageState.value = imageState.value.copy(
            selected = resource,
            message = "Декодирую ${resource.name}…"
        )
        worker.execute {
            try {
                val bitmap = FileRandomAccessSource(files.first).use { source ->
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
                    val failed = resource.copy(
                        validationError = error.message ?: "Не удалось декодировать изображение."
                    )
                    val previous = imageState.value.bitmap
                    if (previous != null && !previous.isRecycled) {
                        runCatching { previous.recycle() }
                    }
                    imageState.value = imageState.value.copy(
                        entries = imageState.value.entries.map { entry ->
                            if (entry.blockOffset == resource.blockOffset) failed else entry
                        },
                        selected = failed,
                        bitmap = null,
                        message = failed.validationError ?: "Не удалось декодировать изображение."
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
                staged = FileRandomAccessSource(files.first).use { source ->
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
        const val EXTRA_GAME_RELATIVE_DIRECTORY =
            "io.github.helterfall.ramen.extra.GAME_RELATIVE_DIRECTORY"

        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val MAX_SCRIPT_SCAN_DEPTH = 12
        private const val MAX_DISCOVERED_SCRIPTS = 10_000
        private const val MAX_PROJECT_MANIFESTS = 32
    }
}

@Composable
private fun ResourceInspectorScreen(
    imageState: ImageInspectorState,
    audioState: AudioInspectorState,
    textState: TextInspectorState,
    discoveredPairs: List<ResourceArchivePair>,
    sourceFolderMessage: String?,
    onBack: () -> Unit,
    onSelectImagePair: (List<Uri>) -> Unit,
    onSelectAudioPair: (List<Uri>) -> Unit,
    onSelectArchivePair: (ResourceArchivePair) -> Unit,
    onSelectImage: (GpkImageResource) -> Unit,
    onPlayAudio: (VpkAudioResource) -> Unit,
    onTogglePlayback: () -> Unit,
    onStopPlayback: () -> Unit,
    onExportModuleReferences: (Uri) -> Unit
) {
    var selectedTab by androidx.compose.runtime.remember { mutableStateOf(ResourceTab.IMAGES) }
    var pendingPair by androidx.compose.runtime.remember { mutableStateOf(ResourcePairKind.IMAGES) }

    val pairPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        when (pendingPair) {
            ResourcePairKind.IMAGES -> {
                selectedTab = ResourceTab.IMAGES
                onSelectImagePair(uris)
            }
            ResourcePairKind.AUDIO -> {
                selectedTab = ResourceTab.AUDIO
                onSelectAudioPair(uris)
            }
        }
    }

    val moduleReportPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) onExportModuleReferences(uri)
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
                        "GPK · GTB · VPK · VTB · SPT",
                        color = InspectorMuted,
                        fontSize = 10.sp
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            if (sourceFolderMessage != null) {
                Text(
                    sourceFolderMessage,
                    color = InspectorMuted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
            if (discoveredPairs.isNotEmpty()) {
                Text(
                    "ПАРЫ ИЗ ИМПОРТИРОВАННОЙ ИГРЫ",
                    color = InspectorAccent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    discoveredPairs.forEach { pair ->
                        ResourceRow(
                            title = "${pair.kind.title} · ${File(pair.displayName).name}",
                            subtitle = pair.displayName,
                            selected = when (pair.kind) {
                                ResourcePairKind.IMAGES -> imageState.archiveName == pair.displayName
                                ResourcePairKind.AUDIO -> audioState.archiveName == pair.displayName
                            },
                            onClick = {
                                selectedTab = when (pair.kind) {
                                    ResourcePairKind.IMAGES -> ResourceTab.IMAGES
                                    ResourcePairKind.AUDIO -> ResourceTab.AUDIO
                                }
                                onSelectArchivePair(pair)
                            }
                        )
                    }
                }
            } else {
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
                            if (imageState.isBusy) "Загрузка…" else "Открыть GPK + GTB",
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
                            if (audioState.isBusy) "Загрузка…" else "Открыть VPK + VTB",
                            color = Color(0xFF24190B),
                            fontSize = 11.sp
                        )
                    }
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
            Spacer(Modifier.height(7.dp))
            if (selectedTab == ResourceTab.TEXT) {
                Button(
                    onClick = { selectedTab = ResourceTab.TEXT },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Текстовые данные · ${textState.modules.size}")
                }
            } else {
                OutlinedButton(
                    onClick = { selectedTab = ResourceTab.TEXT },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Текстовые данные · ${textState.modules.size}")
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
                                subtitle = item.validationError?.let { "БИТЫЙ РЕСУРС · $it" }
                                    ?: "${item.format} · ${item.widthPixels} × ${item.heightPixels} · ${formatBytes(item.payloadSizeBytes)}",
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
                ResourceTab.TEXT -> TextMetadataPanel(
                    state = textState,
                    onExportModuleReferences = {
                        moduleReportPicker.launch("system-nnn-module-references.txt")
                    }
                )
            }
        }
    }
}

@Composable
private fun TextMetadataPanel(
    state: TextInspectorState,
    onExportModuleReferences: () -> Unit
) {
    val poolCount = state.modules.sumOf { it.pools.size }
    val recordCount = state.modules.sumOf { module -> module.pools.sumOf { it.recordCount } }
    val decodingErrors = state.modules.sumOf { module ->
        module.pools.sumOf { it.decodingErrorCount }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = InspectorSurface),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "СТРУКТУРА ТЕКСТОВЫХ ДАННЫХ",
                    color = InspectorAccent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    state.message,
                    color = InspectorText,
                    fontSize = 12.sp
                )
                Text(
                    "Модулей: ${state.modules.size} · пулов: $poolCount · записей: $recordCount · ошибок кодировки: $decodingErrors",
                    color = InspectorMuted,
                    fontSize = 10.sp
                )
                Text(
                    "Показываются смещения, размеры и статус кодировки. Содержимое сценарных строк не выводится.",
                    color = InspectorMuted,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
                if (state.projectManifestCount > 0) {
                    Button(
                        onClick = onExportModuleReferences,
                        enabled = !state.isExportingModuleReferences,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (state.isExportingModuleReferences) "Собираю карту ссылок…"
                            else "Сохранить карту переходов и ссылок (.txt)"
                        )
                    }
                } else {
                    Text(
                        "Для карты ссылок требуется project.xtx рядом с модулями SPT.",
                        color = InspectorMuted,
                        fontSize = 10.sp
                    )
                }
                state.exportMessage?.let { message ->
                    Text(
                        message,
                        color = InspectorMuted,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(state.modules, key = { _, module -> module.name }) { _, module ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = InspectorSurface),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            module.name,
                            color = InspectorText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Файл: ${formatBytes(module.fileSizeBytes)} · область команд: ${formatBytes(module.commandBytes.toLong())}",
                            color = InspectorMuted,
                            fontSize = 10.sp
                        )
                        if (module.scanError != null) {
                            Text(
                                "Ошибка разбора: ${module.scanError}",
                                color = Color(0xFFFF8A80),
                                fontSize = 10.sp
                            )
                        }
                        module.pools.forEach { pool ->
                            Text(
                                "${pool.name}: ${pool.recordCount} записей · ${formatBytes(pool.rawBytes)} · ошибок кодировки: ${pool.decodingErrorCount}",
                                color = InspectorText,
                                fontSize = 10.sp
                            )
                            Text(
                                "Таблица ${pool.addressTableOffset?.let { "0x" + it.toString(16).uppercase(Locale.ROOT) } ?: "—"} · данные ${pool.dataOffset?.let { "0x" + it.toString(16).uppercase(Locale.ROOT) } ?: "—"} · конец ${pool.endOffset?.let { "0x" + it.toString(16).uppercase(Locale.ROOT) } ?: "—"}",
                                color = InspectorMuted,
                                fontSize = 9.sp
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
