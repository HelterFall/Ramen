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

/**
 * Launches one imported System-NNN game from the application's private library.
 *
 * Script and archive parsing run off the main thread. The SPT decoder and the
 * matching GTB/GPK resolver are injected into the Compose player on the main
 * thread after all files have been validated.
 */
class SystemNnnPlayerActivity : ComponentActivity() {

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
                    onScreenTap = playerViewModel::onScreenTap,
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
            try {
                val gameDirectory = resolvePrivateGameDirectory(gameId)
                val scriptFile = findSystemNnnScript(gameDirectory)
                val script = SystemNnnScriptLoader().load(scriptFile)
                val resourcePair = findGpkGtbPair(gameDirectory)
                val resolverToTransfer = SystemNnnGpkBitmapRepository(
                    indexFile = resourcePair.first,
                    archiveFile = resourcePair.second
                )
                assetResolver = resolverToTransfer

                mainHandler.post {
                    if (isFinishing || isDestroyed) {
                        resolverToTransfer.close()
                    } else {
                        playerViewModel.start(
                            script = script,
                            commandDecoder = SystemNnnCommandDecoderImpl(
                                gtbResourceCount = resolverToTransfer.entries.size
                            ),
                            assetResolver = resolverToTransfer
                        )
                    }
                }
            } catch (error: Exception) {
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

    /** Returns the matching pair in parser argument order: GTB index, GPK archive. */
    private fun findGpkGtbPair(gameDirectory: File): Pair<File, File> {
        val indexes = gameDirectory.walkTopDown()
            .maxDepth(MAX_GAME_SCAN_DEPTH)
            .filter { file ->
                file.isFile && file.extension.equals("gtb", ignoreCase = true)
            }
            .take(MAX_DISCOVERED_FILES)
            .sortedBy { it.relativeTo(gameDirectory).invariantSeparatorsPath }
            .toList()

        for (indexFile in indexes) {
            val archiveFile = indexFile.parentFile
                ?.listFiles()
                ?.firstOrNull { candidate ->
                    candidate.isFile &&
                        candidate.extension.equals("gpk", ignoreCase = true) &&
                        candidate.nameWithoutExtension.equals(
                            indexFile.nameWithoutExtension,
                            ignoreCase = true
                        )
                }
            if (archiveFile != null) return indexFile to archiveFile
        }

        throw IOException("Не найдена пара файлов .gtb/.gpk с одинаковым именем для ресурсов сцены.")
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
    }
}
