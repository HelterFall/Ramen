package io.github.helterfall.ramen.runtime.systemnnn

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.compose.material3.MaterialTheme
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import io.github.helterfall.ramen.parser.SystemNnnScriptLoader
import io.github.helterfall.ramen.parser.SystemNnnProjectCatalog
import io.github.helterfall.ramen.parser.SystemNnnScript
import io.github.helterfall.ramen.parser.SystemNnnCommandStreamReader

/**
 * Launches one imported System-NNN game from the application's private library.
 *
 * Script and archive parsing run off the main thread. The SPT decoder and the
 * matching GTB/GPK resolver are injected into the Compose player on the main
 * thread after all files have been validated.
 */
class SystemNnnPlayerActivity : ComponentActivity() {

    private data class PreparedAudioRepository(
        val repository: SystemNnnVpkAudioRepository
    )

    private data class PreparedScriptLaunch(
        val projectModules: List<SystemNnnPlayerModuleProgram>?,
        val standaloneScript: SystemNnnScript?,
        val initialCommandOffset: Long
    )

    private lateinit var playerViewModel: SystemNnnPlayerViewModel
    private lateinit var startupExecutor: ExecutorService
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        playerViewModel = ViewModelProvider(this)[SystemNnnPlayerViewModel::class.java]
        playerViewModel.showStartupLoading()

        setContent {
            MaterialTheme {
                SystemNnnPlayerScreen(
                    state = playerViewModel.uiState,
                    onScreenTap = playerViewModel::onNextTap,
                    onChoiceSelected = playerViewModel::onChoiceSelected,
                    onTypewriterProgress = playerViewModel::onTypewriterProgress,
                    onTypewriterFinished = playerViewModel::onTypewriterFinished,
                    onBitmapDisposed = playerViewModel::onBitmapDisposed
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
            var assetResolver: SystemNnnGpkBitmapRepository? = null
            var preparedAudio: PreparedAudioRepository? = null
            try {
                val gameDirectory = resolvePrivateGameDirectory(gameId)
                val commandStreamReader = SystemNnnCommandStreamReader()
                val launch = prepareScriptLaunch(
                    gameDirectory = gameDirectory,
                    scriptLoader = SystemNnnScriptLoader(),
                    commandStreamReader = commandStreamReader
                )
                val resolverToTransfer = SystemNnnGpkBitmapRepository.forGameDirectory(gameDirectory)
                assetResolver = resolverToTransfer
                preparedAudio = prepareAudioRepository(gameDirectory)

                mainHandler.post {
                    if (isFinishing || isDestroyed) {
                        resolverToTransfer.close()
                        preparedAudio?.repository?.close()
                    } else {
                        val decoderFactory: (SystemNnnPlayerModuleProgram) -> SystemNnnCommandDecoder = {
                            SystemNnnCommandDecoderImpl(
                                gtbResourceCount = resolverToTransfer.resourceCount,
                                streamReader = commandStreamReader
                            )
                        }
                        if (launch.projectModules != null) {
                            playerViewModel.startProject(
                                modules = launch.projectModules,
                                controlModuleName = "sys",
                                assetResolver = resolverToTransfer,
                                decoderFactory = decoderFactory,
                                audioRepository = preparedAudio?.repository
                            )
                        } else {
                            playerViewModel.start(
                                script = checkNotNull(launch.standaloneScript),
                                commandDecoder = SystemNnnCommandDecoderImpl(
                                    gtbResourceCount = resolverToTransfer.resourceCount,
                                    streamReader = commandStreamReader
                                ),
                                assetResolver = resolverToTransfer,
                                initialCommandOffset = launch.initialCommandOffset,
                                audioRepository = preparedAudio?.repository
                            )
                        }
                    }
                }
            } catch (error: Exception) {
                assetResolver?.close()
                preparedAudio?.repository?.close()
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
                val startOffset = if (module.name.equals("sys", ignoreCase = true)) {
                    stream.recordStartOffset
                } else {
                    commandStreamReader.filmStartOffset(script, 0)
                }
                SystemNnnPlayerModuleProgram(
                    name = module.name,
                    script = script,
                    initialCommandOffset = startOffset
                )
            }
            return PreparedScriptLaunch(
                projectModules = programs,
                standaloneScript = null,
                initialCommandOffset = 0L
            )
        }

        val standaloneFile = findStandaloneSystemNnnScript(gameDirectory)
        val script = scriptLoader.load(standaloneFile)
        return PreparedScriptLaunch(
            projectModules = null,
            standaloneScript = script,
            initialCommandOffset = commandStreamReader.filmStartOffset(script, 0)
        )
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

        if (projectFiles.size > 1) {
            throw IOException("В импортированной папке найдено несколько project.xtx; проект неоднозначен.")
        }
        if (projectFiles.isEmpty()) return null

        val projectFile = projectFiles.single()
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

    /**
     * Opens the indexed VTB catalog and its matching VAW (preferred) or VPK archive.
     * Individual voice tracks are staged when their dialogue step becomes active.
     */
    private fun prepareAudioRepository(gameDirectory: File): PreparedAudioRepository? {
        val indexFiles = gameDirectory.walkTopDown()
            .maxDepth(MAX_GAME_SCAN_DEPTH)
            .filter { file ->
                file.isFile && file.extension.equals("vtb", ignoreCase = true)
            }
            .take(MAX_DISCOVERED_FILES)
            .toList()
            .sortedWith(
                compareBy<File> {
                    val path = it.relativeTo(gameDirectory).invariantSeparatorsPath
                    if (path.split("/").any { segment ->
                            segment.equals("vaw", ignoreCase = true)
                        }
                    ) 0 else 1
                }.thenBy { it.relativeTo(gameDirectory).invariantSeparatorsPath }
            )

        for (indexFile in indexFiles) {
            val archiveFiles = indexFile.parentFile
                ?.listFiles()
                ?.filter { candidate ->
                    candidate.isFile &&
                        candidate.extension.lowercase() in AUDIO_ARCHIVE_EXTENSIONS &&
                        candidate.nameWithoutExtension.equals(
                            indexFile.nameWithoutExtension,
                            ignoreCase = true
                        )
                }
                ?.sortedBy { if (it.extension.equals("vaw", ignoreCase = true)) 0 else 1 }
                .orEmpty()

            for (archiveFile in archiveFiles) {
                val repository = try {
                    SystemNnnVpkAudioRepository(
                        indexFile = indexFile,
                        archiveFile = archiveFile,
                        cacheDirectory = File(cacheDir, "systemnnn-audio")
                    )
                } catch (_: Exception) {
                    continue
                }

                if (repository.entries.isNotEmpty()) {
                    return PreparedAudioRepository(repository)
                }
                repository.close()
            }
        }
        return null
    }

    override fun onDestroy() {
        if (::startupExecutor.isInitialized) {
            startupExecutor.shutdownNow()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_GAME_ID = "io.github.helterfall.ramen.extra.SYSTEM_NNN_GAME_ID"
        private const val MAX_GAME_SCAN_DEPTH = 12
        private const val MAX_DISCOVERED_FILES = 10_000
        private val AUDIO_ARCHIVE_EXTENSIONS = setOf("vaw", "vpk")
    }
}
