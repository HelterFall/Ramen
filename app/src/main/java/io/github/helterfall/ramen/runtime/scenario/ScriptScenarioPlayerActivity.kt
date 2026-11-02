package io.github.helterfall.ramen.runtime.scenario

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.helterfall.ramen.runtime.engine.DirectoryGameSource
import io.github.helterfall.ramen.runtime.resources.ScenarioResourceManager
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Player for the documented Ramen Script v1 fixture only. Unsupported/proprietary SPT
 * files fail through the load-error UI; no unknown opcode reaches the Compose layer.
 */
class ScriptScenarioPlayerActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val backgroundRequest = AtomicInteger(0)

    private lateinit var project: ScenarioProject
    private var session: ScenarioSession? = null
    private var resourceManager: ScenarioResourceManager? = null

    private var gameTitle by mutableStateOf("Script v1")
    private var currentFrame by mutableStateOf<ScenarioFrame?>(null)
    private var backgroundBitmap by mutableStateOf<Bitmap?>(null)
    private var errorMessage by mutableStateOf<String?>(null)
    private var statusMessage by mutableStateOf("Поиск стартового сценария…")
    private var requestedBackgroundKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gameTitle = intent.getStringExtra(EXTRA_GAME_TITLE)?.take(100) ?: "Script v1"

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFF2B766),
                    background = Color(0xFF101216),
                    surface = Color(0xFF1A1E25)
                )
            ) {
                ScriptScenarioScreen(
                    title = gameTitle,
                    frame = currentFrame,
                    background = backgroundBitmap,
                    errorMessage = errorMessage,
                    statusMessage = statusMessage,
                    onContinue = ::continueStory,
                    onRestart = ::restart,
                    onExit = ::finish
                )
            }
        }

        val gameRoot = resolveImportedGameRoot(
            intent.getStringExtra(EXTRA_GAME_RELATIVE_DIRECTORY)
        )
        if (gameRoot == null) {
            errorMessage = "Импортированная папка не найдена во внутренней библиотеке Ramen."
        } else {
            worker.execute { loadScript(gameRoot) }
        }
    }

    override fun onDestroy() {
        backgroundRequest.incrementAndGet()
        worker.shutdownNow()
        backgroundBitmap?.let { bitmap -> runCatching { bitmap.recycle() } }
        backgroundBitmap = null
        super.onDestroy()
    }

    private fun resolveImportedGameRoot(relativeDirectory: String?): File? {
        if (relativeDirectory.isNullOrBlank()) return null
        val segments = relativeDirectory.split('/')
        if (!relativeDirectory.startsWith("games/") ||
            segments.size < 2 ||
            segments.any { it.isBlank() || it == "." || it == ".." } ||
            File(relativeDirectory).isAbsolute
        ) {
            return null
        }

        return runCatching {
            val gamesRoot = File(filesDir, "games").canonicalFile
            val gameRoot = File(filesDir, relativeDirectory).canonicalFile
            gameRoot.takeIf {
                it.isDirectory &&
                    it != gamesRoot &&
                    it.toPath().startsWith(gamesRoot.toPath())
            }
        }.getOrNull()
    }

    private fun loadScript(gameRoot: File) {
        try {
            val source = DirectoryGameSource(
                rootDirectory = gameRoot,
                sourceId = "script-player-${gameRoot.name}"
            )
            val entry = ScriptEntryPointResolver().select(source)
                ?: throw IOException(
                    "В папке не найден стартовый Script v1 (.spt или файл с сигнатурой Ramen Script v1)."
                )
            val loadedProject = source.openRead(entry.relativePath).use { input ->
                ScriptScenarioLoader.load(input)
            }
            val startedSession = ScenarioSession.start(loadedProject)
            resourceManager = ScenarioResourceManager(gameRoot)

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                project = loadedProject
                session = startedSession
                statusMessage = "Сценарий: ${entry.relativePath}"
                publishFrame(startedSession.frame())
            }
        } catch (error: Exception) {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                errorMessage = error.message ?: "Не удалось загрузить бинарный сценарий."
                statusMessage = "Загрузка сценария остановлена."
            }
        }
    }

    private fun continueStory() {
        val activeSession = session ?: return
        runCatching { activeSession.continueStory() }
            .onSuccess {
                statusMessage = "Сценарий продолжается."
                publishFrame(it)
            }
            .onFailure { error ->
                statusMessage = error.message ?: "Не удалось перейти к следующей реплике."
            }
    }

    private fun restart() {
        if (!::project.isInitialized) return
        val restarted = ScenarioSession.start(project)
        session = restarted
        errorMessage = null
        statusMessage = "Сценарий начат сначала."
        publishFrame(restarted.frame())
    }

    private fun publishFrame(frame: ScenarioFrame) {
        currentFrame = frame
        requestBackground(frame.backgroundResourceKey)
    }

    private fun requestBackground(resourceKey: String?) {
        if (resourceKey == requestedBackgroundKey) return
        requestedBackgroundKey = resourceKey
        val requestId = backgroundRequest.incrementAndGet()

        val previous = backgroundBitmap
        backgroundBitmap = null
        previous?.let { bitmap -> runCatching { bitmap.recycle() } }

        if (resourceKey == null) return

        statusMessage = "Загружаю фон: $resourceKey"
        worker.execute {
            val result = runCatching {
                val manager = resourceManager
                    ?: throw IOException("Менеджер ресурсов ещё не инициализирован.")
                manager.loadBackground(resourceKey)
            }

            runOnUiThread {
                val loadedBitmap = result.getOrNull()
                if (isFinishing || isDestroyed || requestId != backgroundRequest.get()) {
                    loadedBitmap?.let { bitmap -> runCatching { bitmap.recycle() } }
                    return@runOnUiThread
                }

                if (loadedBitmap != null) {
                    backgroundBitmap = loadedBitmap
                    statusMessage = "Фон загружен: $resourceKey"
                } else {
                    statusMessage = "Фон '$resourceKey' не загружен: " +
                        (result.exceptionOrNull()?.message ?: "ресурс недоступен")
                }
            }
        }
    }

    companion object {
        const val EXTRA_GAME_RELATIVE_DIRECTORY = "script_game_relative_directory"
        const val EXTRA_GAME_TITLE = "script_game_title"
    }
}

@Composable
private fun ScriptScenarioScreen(
    title: String,
    frame: ScenarioFrame?,
    background: Bitmap?,
    errorMessage: String?,
    statusMessage: String,
    onContinue: () -> Unit,
    onRestart: () -> Unit,
    onExit: () -> Unit
) {
    BackHandler(onBack = onExit)

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF101216)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "RAMEN • BINARY SCRIPT V1",
                        color = Color(0xFFF2B766),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = title,
                        color = Color(0xFFF4F1EA),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                TextButton(onClick = onExit) { Text("Закрыть") }
            }

            if (errorMessage != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF3A2525),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Сценарий не запущен", color = Color.White, fontWeight = FontWeight.Bold)
                        Text(errorMessage, color = Color(0xFFFFD6D6), fontSize = 13.sp)
                        Text(
                            "Файл с неизвестным форматом или опкодом изолирован как Malformed.",
                            color = Color(0xFFFFD6D6),
                            fontSize = 11.sp
                        )
                    }
                }
            } else if (frame == null) {
                Text("Читаю стартовый бинарный сценарий…", color = Color(0xFFA8ADB7))
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(270.dp)
                        .background(Color(0xFF293441), RoundedCornerShape(18.dp))
                ) {
                    if (background != null) {
                        Image(
                            bitmap = background.asImageBitmap(),
                            contentDescription = "Фон сценария",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Text(
                            text = frame.backgroundResourceKey?.let { "Фон: $it" } ?: "Фон не задан",
                            modifier = Modifier.align(Alignment.Center).padding(16.dp),
                            color = Color.White,
                            fontSize = 15.sp
                        )
                    }
                    Text(
                        text = frame.sceneId,
                        modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 10.sp
                    )
                }

                frame.dialogue?.let { dialogue ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFF1A1E25),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(9.dp)
                        ) {
                            Text(
                                text = "ТЕКСТ СЦЕНАРИЯ",
                                color = Color(0xFFF2B766),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = dialogue.text,
                                color = Color(0xFFF4F1EA),
                                fontSize = 17.sp,
                                lineHeight = 25.sp
                            )
                        }
                    }
                    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                        Text("Дальше")
                    }
                }

                if (frame.ended) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFF1A1E25),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("История завершена", color = Color.White, fontWeight = FontWeight.Bold)
                            Button(onClick = onRestart) { Text("Пройти ещё раз") }
                        }
                    }
                }

                if (!frame.ended && frame.dialogue == null) {
                    Text("Сценарий обрабатывает следующий переход…", color = Color(0xFFA8ADB7))
                }
            }

            Text(statusMessage, color = Color(0xFFA8ADB7), fontSize = 11.sp)
            Text(
                "Поддерживается документированный тестовый формат Ramen Script v1. Это не интерпретатор произвольных или проприетарных SPT.",
                color = Color(0xFF818895),
                fontSize = 10.sp,
                lineHeight = 15.sp
            )
        }
    }
}
