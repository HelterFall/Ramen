package io.github.helterfall.ramen.runtime.systemnnn

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.compose.material3.MaterialTheme
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import io.github.helterfall.ramen.parser.SystemNnnScriptLoader
import io.github.helterfall.ramen.parser.SystemNnnProjectCatalog
import io.github.helterfall.ramen.parser.SystemNnnScript
import io.github.helterfall.ramen.parser.SystemNnnSceneCatalogReader
import io.github.helterfall.ramen.parser.SystemNnnSceneData
import io.github.helterfall.ramen.parser.SystemNnnSceneVoiceCatalog
import io.github.helterfall.ramen.parser.SystemNnnSceneRoute
import io.github.helterfall.ramen.parser.SystemNnnVariableCatalogReader
import io.github.helterfall.ramen.parser.SystemNnnCommandStreamReader

/**
 * Launches an imported System-NNN project from the application's private library.
 *
 * A project.xtx catalog is loaded as a set of sibling SPT modules and execution
 * begins in the canonical sys controller. Script and asset-bank parsing run
 * off the main thread; the module router and project-level asset manager are
 * injected after the declared SPT modules have been validated.
 */
class SystemNnnPlayerActivity : ComponentActivity() {

    private data class PreparedScriptLaunch(
        val projectModules: List<SystemNnnPlayerModuleProgram>?,
        val standaloneScript: SystemNnnScript?,
        val initialCommandOffset: Long,
        /** Directory containing the selected install's spt/, dwq/, vaw/, wgq/, etc. */
        val assetRootDirectory: File,
        val sceneData: SystemNnnSceneData? = null,
        val sceneVoiceCatalog: SystemNnnSceneVoiceCatalog? = null,
        val initialVariables: Map<Int, Int> = emptyMap()
    )

    private lateinit var playerViewModel: SystemNnnPlayerViewModel
    private lateinit var startupExecutor: ExecutorService
    private lateinit var diagnosticLog: SystemNnnDiagnosticLog
    private var crashHandlerRegistration: AutoCloseable? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val diagnosticsExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null && ::diagnosticLog.isInitialized) {
            exportDiagnostics(uri)
        }
    }

    private fun exportDiagnostics(destination: Uri) {
        try {
            val archive = diagnosticLog.createArchive(File(cacheDir, "ramen-system-nnn-diagnostics.zip"))
            val output = contentResolver.openOutputStream(destination)
                ?: throw IOException("Android did not provide an output stream for the report.")
            output.use { stream ->
                archive.inputStream().buffered().use { input -> input.copyTo(stream) }
            }
            android.widget.Toast.makeText(
                this,
                "Диагностика сохранена. Прикрепи ZIP-файл к сообщению.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        } catch (failure: Exception) {
            diagnosticLog.recordException("diagnostic_export_failure", failure)
            android.widget.Toast.makeText(
                this,
                "Не удалось сохранить отчёт: " + (failure.message ?: "ошибка файла."),
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val decoderLogFile = File(filesDir, "ramen_debug.log")
        val previousDecoderLogFile = File(filesDir, "ramen_debug.previous.log")
        if (decoderLogFile.isFile) {
            previousDecoderLogFile.delete()
            if (!decoderLogFile.renameTo(previousDecoderLogFile)) {
                runCatching { decoderLogFile.copyTo(previousDecoderLogFile, overwrite = true) }
                decoderLogFile.writeText("", Charsets.UTF_8)
            }
        }
        diagnosticLog = SystemNnnDiagnosticLog(
            directory = File(filesDir, "system-nnn-diagnostics"),
            decoderLogFile = decoderLogFile,
            previousDecoderLogFile = previousDecoderLogFile
        )
        val appVersion = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        }.getOrDefault("unknown")
        val metrics = resources.displayMetrics
        diagnosticLog.record(
            "session_start",
            "appVersion=$appVersion sdk=${Build.VERSION.SDK_INT} manufacturer=${Build.MANUFACTURER} " +
                "model=${Build.MODEL} densityDpi=${metrics.densityDpi} " +
                "screenPixels=${metrics.widthPixels}x${metrics.heightPixels}"
        )
        SystemNnnDiagnosticLog.active = diagnosticLog
        crashHandlerRegistration = diagnosticLog.installUncaughtExceptionHandler()

        playerViewModel = ViewModelProvider(this)[SystemNnnPlayerViewModel::class.java]
        playerViewModel.setDiagnosticLog(diagnosticLog)
        playerViewModel.showStartupLoading()

        setContent {
            MaterialTheme {
                SystemNnnPlayerScreen(
                    state = playerViewModel.uiState,
                    onScreenTap = playerViewModel::onNextTap,
                    onDrawFrameCompleted = playerViewModel::onDrawFrameCompleted,
                    onChoiceSelected = playerViewModel::onChoiceSelected,
                    onTypewriterProgress = playerViewModel::onTypewriterProgress,
                    onTypewriterFinished = playerViewModel::onTypewriterFinished,
                    onBitmapDisposed = playerViewModel::onBitmapDisposed,
                    onExportDiagnostics = {
                        diagnosticsExportLauncher.launch("ramen-system-nnn-diagnostics.zip")
                    },
                    onQuickAction = { action ->
                        val message = when (action) {
                            SystemNnnPlayerQuickAction.SAVE ->
                                "Слоты сохранения пока не подключены."
                            SystemNnnPlayerQuickAction.LOAD ->
                                "Загрузка сохранений пока не подключена."
                        }
                        android.widget.Toast.makeText(
                            this,
                            message,
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            }
        }

        startupExecutor = Executors.newSingleThreadExecutor()
        val gameId = intent.getStringExtra(EXTRA_GAME_ID)
        if (gameId.isNullOrBlank()) {
            playerViewModel.showStartupError("Не передан идентификатор игры из библиотеки.")
            return
        }

        startupExecutor.execute {
            var assetResolver: SystemNnnAssetManager? = null
            try {
                val gameDirectory = resolvePrivateGameDirectory(gameId)
                val commandStreamReader = SystemNnnCommandStreamReader()
                val launch = prepareScriptLaunch(
                    gameDirectory = gameDirectory,
                    scriptLoader = SystemNnnScriptLoader(),
                    commandStreamReader = commandStreamReader
                )
                val initialSceneRoute = resolveRequestedSceneRoute(launch)
                val assetManagerToTransfer = SystemNnnAssetManager.forGameDirectory(
                    gameDirectory = launch.assetRootDirectory,
                    cacheDirectory = File(cacheDir, "systemnnn-audio")
                )
                assetResolver = assetManagerToTransfer

                mainHandler.post {
                    if (isFinishing || isDestroyed) {
                        assetManagerToTransfer.close()
                    } else {
                        val projectVariables = launch.initialVariables.toMutableMap()
                        val decoderFactory: (SystemNnnPlayerModuleProgram) -> SystemNnnCommandDecoder = {
                            SystemNnnCommandDecoderImpl(
                                gtbResourceCount = assetManagerToTransfer.resourceCount,
                                streamReader = commandStreamReader,
                                variables = projectVariables,
                                debugLogFile = File(filesDir, "ramen_debug.log"),
                                moduleName = it.name
                            )
                        }
                        if (launch.projectModules != null) {
                            playerViewModel.startProject(
                                modules = launch.projectModules,
                                controlModuleName = "sys",
                                assetResolver = assetManagerToTransfer,
                                decoderFactory = decoderFactory,
                                audioRepository = assetManagerToTransfer,
                                initialSceneRoute = initialSceneRoute
                            )
                        } else {
                            playerViewModel.start(
                                script = checkNotNull(launch.standaloneScript),
                                commandDecoder = SystemNnnCommandDecoderImpl(
                                    gtbResourceCount = assetManagerToTransfer.resourceCount,
                                    streamReader = commandStreamReader,
                                    debugLogFile = File(filesDir, "ramen_debug.log"),
                                    moduleName = "standalone"
                                ),
                                assetResolver = assetManagerToTransfer,
                                initialCommandOffset = launch.initialCommandOffset,
                                audioRepository = assetManagerToTransfer
                            )
                        }
                    }
                }
            } catch (error: Exception) {
                diagnosticLog.recordException("startup_failure", error)
                assetResolver?.close()
                mainHandler.post {
                    if (!isFinishing && !isDestroyed) {
                        playerViewModel.showStartupError(
                            error.message ?: "Не удалось подготовить рантайм System-NNN."
                        )
                    }
                }
            }
        }
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

    private fun prepareScriptLaunch(
        gameDirectory: File,
        scriptLoader: SystemNnnScriptLoader,
        commandStreamReader: SystemNnnCommandStreamReader
    ): PreparedScriptLaunch {
        val projectModules = findSystemNnnProjectModules(gameDirectory)
        if (projectModules != null) {
            val programs = projectModules.map { module ->
                val script = scriptLoader.load(module.scriptFile)
                val stream = commandStreamReader.read(script)
                // NNNLib starts every loaded SPT at PC 0. In the target file
                // the first command record begins at stream.recordStartOffset;
                // module-local bootstrap SUB/IF/NEXT logic lives before film 0.
                val startOffset = stream.recordStartOffset
                val filmEntries = commandStreamReader.filmTableEntries(script, stream)
                    .filter { it.filmNumber != null }
                    .sortedBy { it.filmNumber }
                val filmStartOffsets = filmEntries.map { it.targetCommandOffset }
                if (filmEntries.indices.any { index -> filmEntries[index].filmNumber != index }) {
                    throw IOException("Модуль '${module.name}' имеет разреженную нумерацию film slots.")
                }
                SystemNnnPlayerModuleProgram(
                    name = module.name,
                    script = script,
                    commandStream = stream,
                    initialCommandOffset = startOffset,
                    filmStartOffsets = filmStartOffsets
                )
            }
            val sptDirectory = projectModules.first().scriptFile.parentFile
                ?: throw IOException("Каталог SPT-модулей не имеет родительской папки.")
            val variableFile = File(sptDirectory, "var.fxf")
            val initialVariables = if (variableFile.isFile) {
                SystemNnnVariableCatalogReader().read(variableFile).newGameDefaults()
            } else {
                emptyMap()
            }
            val sceneDataFile = File(sptDirectory, "scene.dat")
            val sceneData = if (sceneDataFile.isFile) {
                SystemNnnSceneCatalogReader().readSceneData(sceneDataFile)
            } else {
                null
            }
            val sptParent = sptDirectory.parentFile
                ?: throw IOException("Каталог установки System-NNN не найден.")
            // Some distributions wrap the alternate script set in "!eng files".
            // Its shared data (nya/, dwq/, vaw/, wgq/, movie/) remains beside it.
            val installRoot = if (sptParent.name.equals(ALTERNATE_PROJECT_DIRECTORY, ignoreCase = true)) {
                sptParent.parentFile ?: sptParent
            } else {
                sptParent
            }
            val sceneVoiceFile = File(installRoot, "nya/scenevoice.xtx")
            val sceneVoiceCatalog = if (sceneVoiceFile.isFile) {
                SystemNnnSceneCatalogReader().readSceneVoiceCatalog(sceneVoiceFile)
            } else {
                null
            }
            if (sceneData != null) {
                validateSceneDataRoutes(
                    sceneData = sceneData,
                    modulePrograms = programs
                )
            }
            return PreparedScriptLaunch(
                projectModules = programs,
                standaloneScript = null,
                initialCommandOffset = 0L,
                assetRootDirectory = installRoot,
                sceneData = sceneData,
                sceneVoiceCatalog = sceneVoiceCatalog,
                initialVariables = initialVariables
            )
        }

        val standaloneFile = findStandaloneSystemNnnScript(gameDirectory)
        val script = scriptLoader.load(standaloneFile)
        val scriptParent = standaloneFile.parentFile
        val standaloneSptParent = if (scriptParent?.name.equals("spt", ignoreCase = true)) {
            scriptParent.parentFile ?: scriptParent
        } else {
            scriptParent ?: gameDirectory
        }
        val standaloneAssetRoot = if (
            standaloneSptParent.name.equals(ALTERNATE_PROJECT_DIRECTORY, ignoreCase = true)
        ) {
            standaloneSptParent.parentFile ?: standaloneSptParent
        } else {
            standaloneSptParent
        }
        return PreparedScriptLaunch(
            projectModules = null,
            standaloneScript = script,
            initialCommandOffset = commandStreamReader.filmStartOffset(script, 0),
            assetRootDirectory = standaloneAssetRoot
        )
    }

    private fun validateSceneDataRoutes(
        sceneData: SystemNnnSceneData,
        modulePrograms: List<SystemNnnPlayerModuleProgram>
    ) {
        val modulesByIndex = modulePrograms
        sceneData.characters.forEachIndexed { characterIndex, character ->
            character.scenes.forEachIndexed { sceneIndex, scene ->
                val module = modulesByIndex.getOrNull(scene.moduleIndex)
                    ?: throw IOException(
                        "scene.dat[$characterIndex][$sceneIndex] module index ${scene.moduleIndex} " +
                            "is outside project.xtx."
                    )
                scene.filmIndices.forEachIndexed { choiceIndex, filmIndex ->
                    if (module.filmStartOffsets.getOrNull(filmIndex) == null) {
                        throw IOException(
                            "scene.dat[$characterIndex][$sceneIndex] film choice $choiceIndex " +
                                "points to missing slot $filmIndex in module '${module.name}'."
                        )
                    }
                }
            }
        }
    }

    private fun resolveRequestedSceneRoute(
        launch: PreparedScriptLaunch
    ): SystemNnnSceneRoute? {
        val hasCharacter = intent.hasExtra(EXTRA_SCENE_CHARACTER_INDEX)
        val hasScene = intent.hasExtra(EXTRA_SCENE_INDEX)
        val hasFilmChoice = intent.hasExtra(EXTRA_SCENE_FILM_CHOICE_INDEX)
        if (!hasCharacter && !hasScene && !hasFilmChoice) return null
        if (!hasCharacter || !hasScene || launch.projectModules == null || launch.sceneData == null) {
            throw IOException(
                "Для запуска сцены нужны project.xtx, scene.dat, индекс персонажа и индекс сцены."
            )
        }

        val reader = SystemNnnSceneCatalogReader()
        val route = reader.resolveRoute(
            sceneData = launch.sceneData,
            characterIndex = intent.getIntExtra(EXTRA_SCENE_CHARACTER_INDEX, -1),
            sceneIndex = intent.getIntExtra(EXTRA_SCENE_INDEX, -1),
            filmChoiceIndex = intent.getIntExtra(EXTRA_SCENE_FILM_CHOICE_INDEX, 0),
            projectModuleNames = launch.projectModules.map { it.name }
        )
        val targetModule = launch.projectModules.getOrNull(route.moduleIndex)
            ?: throw IOException(
                "scene.dat module index ${route.moduleIndex} is outside project.xtx."
            )
        if (!targetModule.name.equals(route.moduleName, ignoreCase = true)) {
            throw IOException(
                "scene.dat module index ${route.moduleIndex} resolves to '${targetModule.name}', " +
                    "not '${route.moduleName}'."
            )
        }
        if (targetModule.filmStartOffsets.getOrNull(route.filmIndex) == null) {
            throw IOException(
                "scene.dat resolved to film slot ${route.filmIndex} outside '${targetModule.name}'."
            )
        }
        return route
    }

    private fun findSystemNnnProjectModules(
        gameDirectory: File
    ): List<io.github.helterfall.ramen.parser.SystemNnnProjectModule>? {
        val projectFiles = gameDirectory.walkTopDown()
            .maxDepth(MAX_GAME_SCAN_DEPTH)
            .filter { file ->
                file.isFile && file.name.equals("project.xtx", ignoreCase = true)
            }
            .take(MAX_DISCOVERED_FILES)
            .toList()

        if (projectFiles.isEmpty()) return null

        // The cleaned Steam project is the canonical spt/project.xtx. The
        // archive also carries an alternate "!eng files/spt/project.xtx";
        // prefer the main module set instead of whichever walk order returns.
        val primaryProjectFiles = projectFiles.filter { file ->
            val segments = file.relativeTo(gameDirectory).invariantSeparatorsPath.split('/')
            segments.size >= 2 &&
                segments.takeLast(2).joinToString("/").equals(
                    PRIMARY_PROJECT_MANIFEST_SUFFIX,
                    ignoreCase = true
                ) &&
                segments.none { it.equals(ALTERNATE_PROJECT_DIRECTORY, ignoreCase = true) }
        }
        val shallowestPrimaryDepth = primaryProjectFiles.minOfOrNull { file ->
            file.relativeTo(gameDirectory).invariantSeparatorsPath.count { it == '/' }
        }
        val shallowestPrimaryFiles = if (shallowestPrimaryDepth == null) {
            emptyList()
        } else {
            primaryProjectFiles.filter { file ->
                file.relativeTo(gameDirectory).invariantSeparatorsPath.count { it == '/' } ==
                    shallowestPrimaryDepth
            }
        }
        val projectFile = when {
            shallowestPrimaryFiles.size == 1 -> shallowestPrimaryFiles.single()
            shallowestPrimaryFiles.size > 1 ->
                throw IOException("Найдено несколько основных spt/project.xtx.")
            projectFiles.size == 1 -> projectFiles.single()
            else -> throw IOException(
                "Найдено несколько project.xtx, но основной каталог SPT не определён."
            )
        }
        val modules = SystemNnnProjectCatalog.load(projectFile).resolveModules(projectFile)
        val invalidModules = modules.filterNot { module ->
            SystemNnnScriptLoader.hasValidHeader(module.scriptFile)
        }
        if (invalidModules.isNotEmpty()) {
            val names = invalidModules.joinToString(", ") { it.name }
            throw IOException("В project.xtx есть модули без корректного SPT-заголовка: $names.")
        }
        if (modules.none { it.name.equals("sys", ignoreCase = true) }) {
            throw IOException("В project.xtx отсутствует управляющий модуль sys.")
        }
        return modules
    }

    private fun findStandaloneSystemNnnScript(gameDirectory: File): File {
        val validCandidates = gameDirectory.walkTopDown()
            .maxDepth(MAX_GAME_SCAN_DEPTH)
            .filter { file ->
                file.isFile &&
                    file.extension.equals("spt", ignoreCase = true) &&
                    SystemNnnScriptLoader.hasValidHeader(file)
            }
            .take(MAX_DISCOVERED_FILES)
            .toList()

        return when (validCandidates.size) {
            1 -> validCandidates.single()
            0 -> throw IOException("Не найден корректный System-NNN .spt сценарий или project.xtx.")
            else -> throw IOException(
                "Найдено несколько System-NNN .spt сценариев без project.xtx; " +
                    "стартовый модуль не может быть выбран автоматически."
            )
        }
    }

    override fun onPause() {
        if (::diagnosticLog.isInitialized) diagnosticLog.record("lifecycle", "activity_paused")
        if (::playerViewModel.isInitialized) {
            playerViewModel.onHostPaused()
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::diagnosticLog.isInitialized) diagnosticLog.record("lifecycle", "activity_resumed")
        if (::playerViewModel.isInitialized) {
            playerViewModel.onHostResumed()
        }
    }

    override fun onDestroy() {
        if (::diagnosticLog.isInitialized) {
            diagnosticLog.record(
                "session_end",
                "isFinishing=$isFinishing isChangingConfigurations=$isChangingConfigurations"
            )
        }
        if (::startupExecutor.isInitialized) {
            startupExecutor.shutdownNow()
        }
        runCatching { crashHandlerRegistration?.close() }
        crashHandlerRegistration = null
        if (SystemNnnDiagnosticLog.active === diagnosticLog) {
            SystemNnnDiagnosticLog.active = null
        }
        super.onDestroy()
    }

    companion object {
        /**
         * Creates a launch request for a specific scene.dat row. The optional
         * film choice is the zero-based position inside that row; playback
         * continues with the following film entries until ENDFILM ends the row.
         */
        fun createSceneIntent(
            context: Context,
            gameId: String,
            characterIndex: Int,
            sceneIndex: Int,
            filmChoiceIndex: Int = 0
        ): Intent {
            require(gameId.isNotBlank()) { "gameId must not be blank." }
            require(characterIndex >= 0) { "characterIndex must not be negative." }
            require(sceneIndex >= 0) { "sceneIndex must not be negative." }
            require(filmChoiceIndex >= 0) { "filmChoiceIndex must not be negative." }
            return Intent(context, SystemNnnPlayerActivity::class.java)
                .putExtra(EXTRA_GAME_ID, gameId)
                .putExtra(EXTRA_SCENE_CHARACTER_INDEX, characterIndex)
                .putExtra(EXTRA_SCENE_INDEX, sceneIndex)
                .putExtra(EXTRA_SCENE_FILM_CHOICE_INDEX, filmChoiceIndex)
        }

        const val EXTRA_GAME_ID = "io.github.helterfall.ramen.extra.SYSTEM_NNN_GAME_ID"
        const val EXTRA_SCENE_CHARACTER_INDEX = "io.github.helterfall.ramen.extra.SYSTEM_NNN_SCENE_CHARACTER_INDEX"
        const val EXTRA_SCENE_INDEX = "io.github.helterfall.ramen.extra.SYSTEM_NNN_SCENE_INDEX"
        const val EXTRA_SCENE_FILM_CHOICE_INDEX = "io.github.helterfall.ramen.extra.SYSTEM_NNN_SCENE_FILM_CHOICE_INDEX"
        private const val MAX_GAME_SCAN_DEPTH = 12
        private const val MAX_DISCOVERED_FILES = 10_000
        private const val PRIMARY_PROJECT_MANIFEST_SUFFIX = "spt/project.xtx"
        private const val ALTERNATE_PROJECT_DIRECTORY = "!eng files"
    }
}
