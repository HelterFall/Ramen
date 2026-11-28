package io.github.helterfall.ramen.nscripter

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

class NscripterPlayerActivity : ComponentActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val startupExecutor = Executors.newSingleThreadExecutor()
    private var vm: NscripterVirtualMachine? = null
    private var resources: NscripterResourceRepository? = null
    private var audioPlayer: NscripterAudioPlayer? = null
    private var scene by mutableStateOf(NscripterSceneState())
    private var screenWidth by mutableStateOf(640)
    private var screenHeight by mutableStateOf(480)
    private var loading by mutableStateOf(true)
    private var ended by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var timedWait by mutableStateOf<Int?>(null)
    private var menuVisible by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BackHandler(enabled = scene.menu.isNotEmpty() && !ended) {
                menuVisible = !menuVisible
            }
            androidx.compose.material3.MaterialTheme {
                NscripterPlayerScreen(
                    scene = scene,
                    resources = resources,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    timedWaitMillis = timedWait,
                    isLoading = loading,
                    isEnded = ended,
                    errorMessage = error,
                    onTap = { vm?.acceptTap(); advance() },
                    onButton = { vm?.acceptButton(it); advance() },
                    onChoice = { vm?.acceptChoice(it); advance() },
                    onMenu = { action ->
                        vm?.performMenuAction(action)
                        menuVisible = false
                        advance()
                    },
                    menuVisible = menuVisible,
                    onToggleMenu = { menuVisible = !menuVisible },
                    onTimedWaitComplete = {
                        vm?.completeTimedWait()
                        timedWait = null
                        advance()
                    }
                )
            }
        }

        val gameId = intent.getStringExtra(EXTRA_GAME_ID)
        if (gameId.isNullOrBlank()) {
            fail("Не передан идентификатор игры.")
            return
        }
        startupExecutor.execute {
            try {
                val directory = resolveGameDirectory(gameId)
                val decoder = NscripterScriptDecoder()
                val sources = decoder.loadScripts(directory).ifEmpty {
                    loadScriptsFromArchives(directory, decoder)
                }
                if (sources.isEmpty()) throw IOException("Не найден сценарий .utf/.txt/nscript.dat в папке или arc.nsa.")
                val program = NscripterScriptProgram.parse(sources.joinToString("\n") { it.text })
                val resourceRepository = NscripterResourceRepository(
                    directory,
                    File(cacheDir, "nscripter-$gameId")
                )
                val saveStore = FileNscripterSaveStore(File(filesDir, "saves/nscripter/$gameId"))
                val virtualMachine = NscripterVirtualMachine(program, saveStore)
                val audio = NscripterAudioPlayer(this, resourceRepository)
                mainHandler.post {
                    if (isFinishing || isDestroyed) {
                        resourceRepository.close()
                        audio.close()
                    } else {
                        resources = resourceRepository
                        screenWidth = program.screenWidth
                        screenHeight = program.screenHeight
                        vm = virtualMachine
                        audioPlayer = audio
                        loading = false
                        menuVisible = false
                        advance()
                    }
                }
            } catch (exception: Exception) {
                fail(exception.message ?: "Не удалось запустить NScripter.")
            }
        }
    }

    private fun loadScriptsFromArchives(
        directory: File,
        decoder: NscripterScriptDecoder
    ): List<NscripterScriptSource> {
        val archives = directory.listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals("nsa", true) }
            .sortedWith(compareBy<File> { if (it.name.equals("arc.nsa", true)) 0 else 1 }.thenBy { it.name })
        val reader = NscripterArchiveReader()
        for (archive in archives) {
            val index = reader.readIndex(archive)
            val candidates = index.entries.filter { entry ->
                entry.name.substringAfterLast('/').lowercase().let { name ->
                    name == "0.utf" || name == "00.utf" || name == "0.txt" ||
                        name == "00.txt" || name == "nscript.dat" ||
                        Regex("^(?:[1-9]|[1-9][0-9])\\.utf$").matches(name) ||
                        Regex("^(?:[1-9]|[1-9][0-9])\\.txt$").matches(name)
                }
            }.sortedWith(compareBy<NscripterArchiveEntry> {
                when (it.name.substringAfterLast('/').lowercase()) {
                    "0.utf" -> 0
                    "00.utf" -> 1
                    "0.txt" -> 2
                    "00.txt" -> 3
                    "nscript.dat" -> 4
                    else -> 5 + (it.name.substringAfterLast('/').substringBefore('.').toIntOrNull() ?: 99)
                }
            })
            if (candidates.isEmpty()) continue
            val entryFiles = candidates.map { entry ->
                val bytes = reader.readEntry(archive, entry)
                val decoded = decoder.decodeScriptBytes(entry.name, bytes)
                NscripterScriptSource(
                    relativePath = archive.name + "/" + entry.name,
                    text = decoded.text,
                    encoding = decoded.encoding,
                    mode = decoded.mode
                )
            }
            return entryFiles
        }
        return emptyList()
    }

    private fun advance() {
        if (loading || ended) return
        val engine = vm ?: return
        timedWait = null
        try {
            when (val yield = engine.resume()) {
                is NscripterVmYield.Frame -> {
                    scene = yield.scene
                    audioPlayer?.apply(engine.takeAudioActions())
                    mainHandler.postDelayed({ advance() }, 24L)
                }
                is NscripterVmYield.Dialogue -> {
                    scene = yield.scene
                    audioPlayer?.apply(engine.takeAudioActions())
                }
                is NscripterVmYield.TimedWait -> {
                    scene = yield.scene
                    timedWait = yield.milliseconds
                    audioPlayer?.apply(engine.takeAudioActions())
                }
                is NscripterVmYield.ButtonWait -> {
                    scene = yield.scene
                    audioPlayer?.apply(engine.takeAudioActions())
                }
                is NscripterVmYield.ChoiceWait -> {
                    scene = yield.scene
                    audioPlayer?.apply(engine.takeAudioActions())
                }
                is NscripterVmYield.Finished -> {
                    scene = yield.scene
                    ended = true
                    audioPlayer?.apply(engine.takeAudioActions())
                }
            }
            title = scene.caption
        } catch (exception: Exception) {
            error = exception.message ?: "Ошибка выполнения сценария."
            loading = false
        }
    }

    private fun resolveGameDirectory(gameId: String): File {
        if (gameId.any { it.isISOControl() || it == '/' || it == '\\' }) {
            throw IOException("Некорректный идентификатор игры.")
        }
        val root = File(filesDir, "games").canonicalFile
        val candidate = File(root, gameId).canonicalFile
        if (!candidate.toPath().startsWith(root.toPath()) || !candidate.isDirectory) {
            throw IOException("Папка игры отсутствует в библиотеке.")
        }
        return candidate
    }

    private fun fail(message: String) {
        mainHandler.post {
            loading = false
            error = message
        }
    }

    override fun onDestroy() {
        startupExecutor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        audioPlayer?.close()
        resources?.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_GAME_ID = "io.github.helterfall.ramen.extra.NSCRIPTER_GAME_ID"
    }
}
