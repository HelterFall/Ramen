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
                val scriptFile = findSystemNnnScript(gameDirectory)
                val script = SystemNnnScriptLoader().load(scriptFile)
                val commandStreamReader = SystemNnnCommandStreamReader()
                val initialCommandOffset = commandStreamReader.filmStartOffset(script, 0)
                val resolverToTransfer = SystemNnnGpkBitmapRepository.forGameDirectory(gameDirectory)
                assetResolver = resolverToTransfer
                preparedAudio = prepareAudioRepository(gameDirectory)

                mainHandler.post {
                    if (isFinishing || isDestroyed) {
                        resolverToTransfer.close()
                        preparedAudio?.repository?.close()
                    } else {
                        playerViewModel.start(
                            script = script,
                            commandDecoder = SystemNnnCommandDecoderImpl(
                                gtbResourceCount = resolverToTransfer.resourceCount,
                                streamReader = commandStreamReader
                            ),
                            assetResolver = resolverToTransfer,
                            initialCommandOffset = initialCommandOffset,
                            audioRepository = preparedAudio?.repository
                        )
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

    private fun findSystemNnnScript(gameDirectory: File): File {
        val candidates = gameDirectory.walkTopDown()
            .maxDepth(MAX_GAME_SCAN_DEPTH)
            .filter { file ->
                file.isFile && file.extension.equals("spt", ignoreCase = true)
            }
            .take(MAX_DISCOVERED_FILES)
            .toList()
            .sortedWith(
                compareBy<File> { if (it.name.equals("01.spt", ignoreCase = true)) 0 else 1 }
                    .thenBy { it.relativeTo(gameDirectory).invariantSeparatorsPath }
            )

        return candidates.firstOrNull { candidate -> SystemNnnScriptLoader.hasValidHeader(candidate) }
            ?: throw IOException("В импортированной папке не найден корректный System-NNN .spt сценарий.")
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
