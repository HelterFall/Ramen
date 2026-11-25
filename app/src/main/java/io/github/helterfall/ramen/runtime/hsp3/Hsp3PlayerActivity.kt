package io.github.helterfall.ramen.runtime.hsp3

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.helterfall.ramen.library.GameRecord
import io.github.helterfall.ramen.library.LibraryRepository
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Native Android host for imported PE32/DPMX HSP3 games. */
class Hsp3PlayerActivity : ComponentActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionState = mutableStateOf<Hsp3RuntimeSession?>(null)
    private val errorState = mutableStateOf<String?>(null)
    private val frameCounter = mutableStateOf(0)
    private val inputController = Hsp3TouchInputController()
    private lateinit var startupExecutor: java.util.concurrent.ExecutorService

    private val frameRunner = object : Runnable {
        override fun run() {
            val session = sessionState.value ?: return
            if (isFinishing || isDestroyed) return
            val result = session.runSlice(instructionBudget = VM_FRAME_BUDGET)
            frameCounter.value += 1
            when (result.status) {
                Hsp3VmStatus.ENDED -> mainHandler.removeCallbacks(this)
                Hsp3VmStatus.FAILED -> {
                    errorState.value = result.errorMessage ?: "Ошибка выполнения HSP3."
                    mainHandler.removeCallbacks(this)
                }
                Hsp3VmStatus.READY, Hsp3VmStatus.YIELDED -> {
                    val delay = result.yieldDelayMillis.takeIf { it > 0L } ?: FRAME_INTERVAL_MS
                    mainHandler.postDelayed(this, delay.coerceAtMost(MAX_HOST_DELAY_MS))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val gameId = intent.getStringExtra(EXTRA_GAME_ID)
        val game = gameId?.let { id ->
            LibraryRepository(applicationContext).load().firstOrNull { it.id == id }
        }
        if (game == null || !isLaunchableGame(game)) {
            finish()
            return
        }

        setContent {
            MaterialTheme {
                Hsp3PlayerScreen(
                    title = game.title,
                    session = sessionState.value,
                    error = errorState.value,
                    frameCounter = frameCounter.value,
                    input = inputController,
                    onExit = ::finish
                )
            }
        }

        startupExecutor = Executors.newSingleThreadExecutor()
        startupExecutor.execute {
            try {
                val gameDirectory = resolvePrivateGameDirectory(game)
                val session = prepareSession(gameDirectory)
                mainHandler.post {
                    if (isFinishing || isDestroyed) {
                        session.close()
                    } else {
                        sessionState.value?.close()
                        sessionState.value = session
                        mainHandler.removeCallbacks(frameRunner)
                        mainHandler.post(frameRunner)
                    }
                }
            } catch (error: Exception) {
                mainHandler.post {
                    if (!isFinishing && !isDestroyed) {
                        errorState.value = error.message ?: "Не удалось запустить HSP3."
                    }
                }
            }
        }
    }

    private fun resolvePrivateGameDirectory(game: GameRecord): File {
        val id = game.id
        if (id.isBlank() || id.any { it.isISOControl() || it == '/' || it == '\\' }) {
            throw IOException("Некорректный идентификатор игры.")
        }
        val gamesRoot = File(filesDir, "games").canonicalFile
        val gameDirectory = File(filesDir, game.relativeDirectory).canonicalFile
        if (gameDirectory == gamesRoot ||
            !gameDirectory.toPath().startsWith(gamesRoot.toPath()) ||
            gameDirectory != File(gamesRoot, id).canonicalFile ||
            !gameDirectory.isDirectory
        ) {
            throw IOException("Папка игры отсутствует в приватной библиотеке Ramen.")
        }
        return gameDirectory
    }

    private fun prepareSession(gameDirectory: File): Hsp3RuntimeSession {
        val executables = gameDirectory.walkTopDown()
            .maxDepth(MAX_GAME_SCAN_DEPTH)
            .filter { it.isFile && it.extension.equals("exe", ignoreCase = true) }
            .take(MAX_DISCOVERED_FILES)
            .toList()
            .sortedWith(
                compareBy<File> {
                    if (it.name.equals("Demonophobia.exe", ignoreCase = true)) 0 else 1
                }.thenBy { it.relativeTo(gameDirectory).invariantSeparatorsPath }
            )
        if (executables.isEmpty()) throw IOException("В папке игры не найден PE32 .exe с HSP/DPMX.")

        var lastError: Exception? = null
        for (executable in executables) {
            try {
                return Hsp3RuntimeAdapter().createSession(
                    executable = executable,
                    instructionDecoder = Hsp3BytecodeDecoder(),
                    input = inputController
                )
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw IOException(lastError?.message ?: "Ни один PE32/DPMX файл не прошёл проверку HSP3.")
    }

    override fun onPause() {
        mainHandler.removeCallbacks(frameRunner)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (sessionState.value != null && errorState.value == null) {
            mainHandler.removeCallbacks(frameRunner)
            mainHandler.post(frameRunner)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(frameRunner)
        if (::startupExecutor.isInitialized) startupExecutor.shutdownNow()
        sessionState.value?.close()
        sessionState.value = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_GAME_ID = "io.github.helterfall.ramen.extra.HSP3_GAME_ID"
        private const val MAX_GAME_SCAN_DEPTH = 12
        private const val MAX_DISCOVERED_FILES = 10_000
        private const val VM_FRAME_BUDGET = 2_000
        private const val FRAME_INTERVAL_MS = 16L
        private const val MAX_HOST_DELAY_MS = 60_000L

        private fun isLaunchableGame(game: GameRecord): Boolean =
            game.id.matches(Regex("[A-Za-z0-9-]{1,64}")) &&
                game.relativeDirectory == "games/${game.id}" &&
                game.engineId == Hsp3RuntimeAdapter.ENGINE_ID &&
                game.compatibilityStatus == "PARSER_AVAILABLE"
    }
}

private class Hsp3TouchInputController : Hsp3InputProvider {
    private val mask = AtomicInteger(0)

    override fun stickMask(): Int = mask.get()

    fun setPressed(bits: Int, pressed: Boolean) {
        while (true) {
            val previous = mask.get()
            val next = if (pressed) previous or bits else previous and bits.inv()
            if (mask.compareAndSet(previous, next)) return
        }
    }

    override fun isKeyDown(keyCode: Int): Boolean {
        val bit = when (keyCode) {
            37 -> STICK_LEFT
            38 -> STICK_UP
            39 -> STICK_RIGHT
            40 -> STICK_DOWN
            13 -> STICK_ENTER
            27 -> STICK_ESCAPE
            32 -> STICK_SPACE
            90 -> STICK_Z
            88 -> STICK_X
            else -> 0
        }
        return bit != 0 && mask.get() and bit != 0
    }

    companion object {
        const val STICK_LEFT = 1
        const val STICK_UP = 2
        const val STICK_RIGHT = 4
        const val STICK_DOWN = 8
        const val STICK_SPACE = 16
        const val STICK_ENTER = 32
        const val STICK_ESCAPE = 128
        const val STICK_Z = 2048
        const val STICK_X = 4096
    }
}

@Composable
private fun Hsp3PlayerScreen(
    title: String,
    session: Hsp3RuntimeSession?,
    error: String?,
    frameCounter: Int,
    input: Hsp3TouchInputController,
    onExit: () -> Unit
) {
    BackHandler(onBack = onExit)
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF08090C)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF191D24))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = Color(0xFFF4F1EA),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Text(
                        text = "HSP3.1 • 800×600 • ${if (session == null) "Подготовка" else "Запуск"}",
                        color = Color(0xFFA8ADB7),
                        fontSize = 10.sp
                    )
                }
                Text(
                    text = "Закрыть",
                    color = Color(0xFFF2B766),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF292E37))
                        .pointerInput(Unit) { detectTapGestures(onTap = { onExit() }) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawIntoCanvas { canvas ->
                        session?.renderer?.draw(canvas.nativeCanvas)
                            ?: canvas.nativeCanvas.drawColor(android.graphics.Color.BLACK)
                    }
                }
                if (session == null && error == null) {
                    Text(
                        text = "Подготавливаю HSP3…",
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                if (error != null) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .background(Color(0xE6222630))
                            .padding(14.dp)
                    ) {
                        Text(
                            text = "Остановка HSP3",
                            color = Color(0xFFFFC5A8),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = error,
                            color = Color.White,
                            fontSize = 11.sp
                        )
                    }
                }
                GamepadOverlay(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    input = input
                )
            }
        }
    }
}

@Composable
private fun GamepadOverlay(
    modifier: Modifier,
    input: Hsp3TouchInputController
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0x8020242D))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ControlKey("▲", Hsp3TouchInputController.STICK_UP, input)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ControlKey("◀", Hsp3TouchInputController.STICK_LEFT, input)
                ControlKey("▼", Hsp3TouchInputController.STICK_DOWN, input)
                ControlKey("▶", Hsp3TouchInputController.STICK_RIGHT, input)
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ControlKey("Enter / Z", Hsp3TouchInputController.STICK_ENTER or Hsp3TouchInputController.STICK_Z, input)
            ControlKey("Esc / X", Hsp3TouchInputController.STICK_ESCAPE or Hsp3TouchInputController.STICK_X, input)
            ControlKey("Space", Hsp3TouchInputController.STICK_SPACE, input)
        }
    }
}

@Composable
private fun ControlKey(
    label: String,
    bits: Int,
    input: Hsp3TouchInputController
) {
    Box(
        modifier = Modifier
            .size(width = if (label.length > 2) 66.dp else 46.dp, height = 42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xD938414E))
            .pointerInput(bits) {
                detectTapGestures(
                    onPress = {
                        input.setPressed(bits, true)
                        try {
                            tryAwaitRelease()
                        } finally {
                            input.setPressed(bits, false)
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color(0xFFF4F1EA),
            fontSize = if (label.length > 2) 9.sp else 18.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
