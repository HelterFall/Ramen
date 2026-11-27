package io.github.helterfall.ramen.runtime.hsp3

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.helterfall.ramen.library.GameRecord
import io.github.helterfall.ramen.library.LibraryRepository
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

private data class Hsp3AxScanUiState(
    val loading: Boolean = true,
    val message: String = "Подготовка статического анализа start.ax…",
    val json: String? = null,
    val fileName: String? = null,
    val opcodeCount: Int = 0,
    val unsupportedCount: Int = 0
)

/** Static .ax scanner for the game card. No HSP VM, audio or rendering is started. */
class Hsp3AxScanActivity : ComponentActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanState = mutableStateOf(Hsp3AxScanUiState())
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val gameId = intent.getStringExtra(EXTRA_GAME_ID)
        val game = gameId?.let { id ->
            LibraryRepository(applicationContext).load().firstOrNull { it.id == id }
        }
        if (game == null || !isScannableGame(game)) {
            finish()
            return
        }

        setContent {
            MaterialTheme {
                Hsp3AxScanScreen(
                    title = game.title,
                    state = scanState.value,
                    onCopy = { copyReportToClipboard(it) },
                    onExit = ::finish
                )
            }
        }

        executor.execute {
            try {
                val gameDirectory = resolvePrivateGameDirectory(game)
                val prepared = prepareGame(gameDirectory)
                val report = Hsp3AxStaticScanner.scan(prepared.program)
                val output = File(cacheDir, "hsp3-scan/${game.id}-start.ax.json")
                report.writeJson(output)
                val json = output.readText(Charsets.UTF_8)
                mainHandler.post {
                    if (!isFinishing && !isDestroyed) {
                        scanState.value = Hsp3AxScanUiState(
                            loading = false,
                            message = "SCAN завершён для ${prepared.executable.name}",
                            json = json,
                            fileName = output.name,
                            opcodeCount = report.opcodeCount,
                            unsupportedCount = report.unsupportedCommands.size
                        )
                    }
                }
            } catch (error: Exception) {
                mainHandler.post {
                    if (!isFinishing && !isDestroyed) {
                        scanState.value = Hsp3AxScanUiState(
                            loading = false,
                            message = error.message ?: "Не удалось просканировать HSP3 start.ax."
                        )
                    }
                }
            }
        }
    }

    private fun resolvePrivateGameDirectory(game: GameRecord): File {
        val id = game.id
        if (!id.matches(Regex("[A-Za-z0-9-]{1,64}"))) {
            throw IOException("Некорректный идентификатор игры.")
        }
        val gamesRoot = File(filesDir, "games").canonicalFile
        val gameDirectory = File(filesDir, game.relativeDirectory).canonicalFile
        if (game.relativeDirectory != "games/$id" ||
            gameDirectory == gamesRoot ||
            !gameDirectory.toPath().startsWith(gamesRoot.toPath()) ||
            gameDirectory != File(gamesRoot, id).canonicalFile ||
            !gameDirectory.isDirectory
        ) {
            throw IOException("Папка игры отсутствует в приватной библиотеке Ramen.")
        }
        return gameDirectory
    }

    private fun prepareGame(gameDirectory: File): Hsp3PreparedGame {
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
        if (executables.isEmpty()) {
            throw IOException("В папке игры не найден PE32 .exe с HSP/DPMX.")
        }

        var lastError: Exception? = null
        for (executable in executables) {
            try {
                // prepare() decrypts and validates start.ax without constructing or running a VM.
                return Hsp3RuntimeAdapter().prepare(executable)
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw IOException(
            lastError?.message ?: "Ни один PE32/DPMX файл не прошёл проверку HSP3.",
            lastError
        )
    }

    private fun copyReportToClipboard(json: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("HSP3 SCAN JSON", json))
        Toast.makeText(this, "Полный JSON-отчёт скопирован", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_GAME_ID = "io.github.helterfall.ramen.extra.HSP3_SCAN_GAME_ID"
        private const val MAX_GAME_SCAN_DEPTH = 12
        private const val MAX_DISCOVERED_FILES = 10_000

        private fun isScannableGame(game: GameRecord): Boolean =
            game.id.matches(Regex("[A-Za-z0-9-]{1,64}")) &&
                game.relativeDirectory == "games/${game.id}" &&
                game.engineId == Hsp3RuntimeAdapter.ENGINE_ID &&
                game.compatibilityStatus == "PARSER_AVAILABLE"
    }
}

@Composable
private fun Hsp3AxScanScreen(
    title: String,
    state: Hsp3AxScanUiState,
    onCopy: (String) -> Unit,
    onExit: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF101216)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "HSP3 STATIC SCAN",
                        color = Color(0xFFF2B766),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    Text(
                        title,
                        color = Color(0xFFF4F1EA),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp
                    )
                }
                OutlinedButton(onClick = onExit) { Text("Закрыть") }
            }

            Text(
                state.message,
                color = if (state.json != null) Color(0xFF9FD5A5) else Color(0xFFA8ADB7),
                fontSize = 12.sp
            )

            if (state.loading) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFFF2B766))
                        Spacer(Modifier.size(12.dp))
                        Text(
                            "Расшифровка и разбор .ax. Код игры не выполняется.",
                            color = Color(0xFFA8ADB7),
                            fontSize = 12.sp
                        )
                    }
                }
            } else if (state.json != null) {
                Text(
                    "Опкодов: ${state.opcodeCount} · неподдержанных/ошибочных команд: ${state.unsupportedCount}",
                    color = Color(0xFFA8ADB7),
                    fontSize = 12.sp
                )
                Text(
                    "JSON сохранён в кэше: ${state.fileName}",
                    color = Color(0xFFA8ADB7),
                    fontSize = 11.sp
                )
                Button(
                    onClick = { onCopy(state.json) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Скопировать полный JSON")
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color(0xFF1A1E25))
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp)
                ) {
                    Text(
                        state.json,
                        color = Color(0xFFE4E1D9),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 13.sp
                    )
                }
            } else {
                Text(
                    state.message,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    color = Color(0xFFFFB4AB),
                    fontSize = 13.sp
                )
            }
        }
    }
}
