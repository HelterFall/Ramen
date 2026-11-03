package io.github.helterfall.ramen.ui

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.helterfall.ramen.importer.HtmlFileImporter
import io.github.helterfall.ramen.importer.SafFolderImporter
import io.github.helterfall.ramen.importer.ZipGameImporter
import io.github.helterfall.ramen.runtime.html5.Html5PlayerActivity
import io.github.helterfall.ramen.runtime.resources.ResourceInspectorActivity
import io.github.helterfall.ramen.library.GameRecord
import io.github.helterfall.ramen.library.LibraryRepository
import java.io.File
import java.util.concurrent.Executors

private val RamenBackground = Color(0xFF101216)
private val RamenSurface = Color(0xFF1A1E25)
private val RamenSurfaceRaised = Color(0xFF222832)
private val RamenAccent = Color(0xFFF2B766)
private val RamenText = Color(0xFFF4F1EA)
private val RamenMuted = Color(0xFFA8ADB7)

@Composable
fun RamenApp() {
    val context = LocalContext.current
    val repository = remember { LibraryRepository(context.applicationContext) }
    val games = remember {
        mutableStateListOf<GameRecord>().apply {
            addAll(repository.load())
        }
    }
    var isImporting by remember { mutableStateOf(false) }
    var statusMessage by remember {
        mutableStateOf("Добавь ZIP, HTML-файл или выбери папку с визуальной новеллой.")
    }

    val executor = remember { Executors.newSingleThreadExecutor() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(executor) {
        onDispose {
            executor.shutdown()
        }
    }

    fun enqueueImport(
        uri: Uri,
        progressMessage: String,
        releaseReadPermission: Boolean = false,
        importOperation: (Uri) -> GameRecord
    ) {
        if (isImporting) return

        isImporting = true
        statusMessage = progressMessage

        executor.execute {
            var importedGame: GameRecord? = null

            try {
                val game = importOperation(uri)
                importedGame = game

                val updatedLibrary = repository.load().toMutableList().apply {
                    add(0, game)
                }
                repository.save(updatedLibrary)

                mainHandler.post {
                    games.clear()
                    games.addAll(updatedLibrary)
                    isImporting = false
                    statusMessage = "Добавлено в библиотеку: " + game.title
                }
            } catch (error: Exception) {
                importedGame?.let { game ->
                    File(context.filesDir, game.relativeDirectory).deleteRecursively()
                }

                mainHandler.post {
                    isImporting = false
                    statusMessage = error.message ?: "Не удалось импортировать игру."
                }
            } finally {
                if (releaseReadPermission) {
                    runCatching {
                        context.contentResolver.releasePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                }
            }
        }
    }

    val documentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            statusMessage = "Импорт ZIP отменён."
        } else {
            enqueueImport(
                uri = uri,
                progressMessage = "Проверяю и распаковываю ZIP…"
            ) { selectedUri ->
                ZipGameImporter(context.applicationContext).import(selectedUri)
            }
        }
    }

    val htmlFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            statusMessage = "Импорт HTML отменён."
        } else {
            enqueueImport(
                uri = uri,
                progressMessage = "Копирую выбранный HTML-файл…"
            ) { selectedUri ->
                HtmlFileImporter(context.applicationContext).import(selectedUri)
            }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            statusMessage = "Выбор папки отменён."
        } else if (isImporting) {
            statusMessage = "Дождись окончания текущего импорта."
        } else {
            val permissionTaken = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }.isSuccess

            if (!permissionTaken) {
                statusMessage = "Android не предоставил постоянный доступ к папке."
            } else {
                enqueueImport(
                    uri = uri,
                    progressMessage = "Копирую выбранную папку в приватное хранилище…",
                    releaseReadPermission = true
                ) { selectedUri ->
                    SafFolderImporter(context.applicationContext).import(selectedUri)
                }
            }
        }
    }

    val onImportClick: () -> Unit = {
        if (!isImporting) {
            documentPicker.launch(
                arrayOf(
                    "application/zip",
                    "application/x-zip-compressed",
                    "application/x-zip",
                    "application/octet-stream"
                )
            )
        }
    }

    val onImportFolderClick: () -> Unit = {
        if (!isImporting) {
            folderPicker.launch(null)
        }
    }

    val onImportHtmlClick: () -> Unit = {
        if (!isImporting) {
            // Some providers label .html as text/plain or octet-stream,
            // so accept the selection broadly and validate it in HtmlFileImporter.
            htmlFilePicker.launch(arrayOf("*/*"))
        }
    }

    val colors = darkColorScheme(
        primary = RamenAccent,
        onPrimary = Color(0xFF2B1D0A),
        secondary = Color(0xFFB8CBBE),
        background = RamenBackground,
        surface = RamenSurface,
        surfaceVariant = RamenSurfaceRaised,
        onSurface = RamenText,
        onSurfaceVariant = RamenMuted
    )

    MaterialTheme(colorScheme = colors) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = colors.background
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(
                    horizontal = 22.dp,
                    vertical = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                item { BrandHeader() }
                item {
                    ResourceInspectorShortcut {
                        context.startActivity(
                            Intent(context, ResourceInspectorActivity::class.java)
                        )
                    }
                }

                if (games.isEmpty()) {
                    item {
                        EmptyLibraryCard(
                            isImporting = isImporting,
                            onImportClick = onImportClick,
                            onImportFolderClick = onImportFolderClick,
                            onImportHtmlClick = onImportHtmlClick
                        )
                    }
                } else {
                    item {
                        LibrarySummaryCard(
                            gameCount = games.size,
                            isImporting = isImporting,
                            onImportClick = onImportClick,
                            onImportFolderClick = onImportFolderClick,
                            onImportHtmlClick = onImportHtmlClick
                        )
                    }
                    item { SectionHeading("ТВОЯ БИБЛИОТЕКА") }
                    items(games, key = { it.id }) { game ->
                        GameCard(
                            game = game,
                            onInspectResources = {
                                val intent = Intent(context, ResourceInspectorActivity::class.java)
                                    .putExtra(
                                        ResourceInspectorActivity.EXTRA_GAME_RELATIVE_DIRECTORY,
                                        game.relativeDirectory
                                    )
                                context.startActivity(intent)
                            },
                            onLaunch = {
                                val intent = Intent(context, Html5PlayerActivity::class.java)
                                    .putExtra(Html5PlayerActivity.EXTRA_GAME_ID, game.id)
                                context.startActivity(intent)
                            }
                        )
                    }
                }

                item {
                    ImportStatusCard(
                        message = statusMessage,
                        isImporting = isImporting
                    )
                }
                item { ComingNextSection() }
                item { BuildFooter() }
            }
        }
    }
}


@Composable
private fun ResourceInspectorShortcut(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(17.dp)),
        color = RamenSurfaceRaised,
        shape = RoundedCornerShape(17.dp)
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 15.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "ИНСПЕКТОР РЕСУРСОВ",
                    color = RamenAccent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Проверка GPK/GTB и VPK/VTB",
                    color = RamenText,
                    fontSize = 12.sp
                )
            }
            Text(
                text = "ОТКРЫТЬ  ›",
                color = RamenAccent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun BrandHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(RamenAccent),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "R",
                color = Color(0xFF24190B),
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }

        Spacer(modifier = Modifier.size(13.dp))

        Column {
            Text(
                text = "RAMEN",
                color = RamenText,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Text(
                text = "VISUAL NOVEL PLAYER",
                color = RamenMuted,
                fontSize = 10.sp,
                letterSpacing = 1.3.sp
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Surface(
            color = RamenSurfaceRaised,
            shape = RoundedCornerShape(50)
        ) {
            Text(
                text = "0.1.0",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                color = RamenMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun EmptyLibraryCard(
    isImporting: Boolean,
    onImportClick: () -> Unit,
    onImportFolderClick: () -> Unit,
    onImportHtmlClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = RamenSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(23.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "ТВОЯ БИБЛИОТЕКА",
                color = RamenAccent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )

            Text(
                text = "Истории\nначинаются здесь.",
                color = RamenText,
                fontSize = 30.sp,
                lineHeight = 35.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Добавь ZIP, отдельный HTML-файл или выбери папку целиком. Для игры с CSS, JavaScript, изображениями и звуком лучше импортировать всю папку.",
                color = RamenMuted,
                fontSize = 14.sp,
                lineHeight = 21.sp
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(RamenSurfaceRaised)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "0",
                        color = RamenText,
                        fontSize = 25.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "игр добавлено",
                        color = RamenMuted,
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = "ZIP\nIMPORT",
                    color = RamenAccent,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onImportClick,
                    enabled = !isImporting,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RamenAccent,
                        contentColor = Color(0xFF24190B)
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = if (isImporting) "Импорт…" else "ZIP-архив",
                        fontWeight = FontWeight.Bold
                    )
                }
                OutlinedButton(
                    onClick = onImportFolderClick,
                    enabled = !isImporting,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Выбрать папку")
                }
            }

            OutlinedButton(
                onClick = onImportHtmlClick,
                enabled = !isImporting,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("HTML-файл (только одна страница)")
            }
        }
    }
}

@Composable
private fun LibrarySummaryCard(
    gameCount: Int,
    isImporting: Boolean,
    onImportClick: () -> Unit,
    onImportFolderClick: () -> Unit,
    onImportHtmlClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = RamenSurface,
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(
                        text = gameCount.toString(),
                        color = RamenText,
                        fontSize = 25.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (gameCount == 1) "игра в библиотеке" else "игр в библиотеке",
                        color = RamenMuted,
                        fontSize = 12.sp
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onImportClick,
                    enabled = !isImporting,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RamenAccent,
                        contentColor = Color(0xFF24190B)
                    ),
                    shape = RoundedCornerShape(13.dp)
                ) {
                    Text(if (isImporting) "Импорт…" else "ZIP")
                }
                OutlinedButton(
                    onClick = onImportFolderClick,
                    enabled = !isImporting,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(13.dp)
                ) {
                    Text("Папка")
                }
            }

            OutlinedButton(
                onClick = onImportHtmlClick,
                enabled = !isImporting,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(13.dp)
            ) {
                Text("HTML-файл")
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        color = RamenAccent,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp
    )
}

@Composable
private fun GameCard(
    game: GameRecord,
    onInspectResources: () -> Unit,
    onLaunch: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = RamenSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(17.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = game.title,
                color = RamenText,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = game.engineLabel,
                color = RamenAccent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = when (game.compatibilityStatus) {
                    "SUPPORTED_LIMITED" ->
                        "Доступен ограниченный автономный HTML5-запуск."
                    "UNKNOWN" ->
                        "Движок пока не определён. Запуск недоступен."
                    else ->
                        "Движок распознан, но игровой модуль пока не подключён."
                },
                color = RamenMuted,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
            Text(
                text = game.evidence,
                color = RamenMuted,
                fontSize = 10.sp,
                lineHeight = 14.sp
            )
            OutlinedButton(
                onClick = onInspectResources,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(13.dp)
            ) {
                Text("Просмотреть GPK/GTB и VPK/VTB")
            }
            if (game.compatibilityStatus == "SUPPORTED_LIMITED") {
                Button(
                    onClick = onLaunch,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RamenAccent,
                        contentColor = Color(0xFF24190B)
                    )
                ) {
                    Text("Запустить", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ImportStatusCard(
    message: String,
    isImporting: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = RamenSurfaceRaised,
        shape = RoundedCornerShape(15.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isImporting) "…" else "•",
                color = RamenAccent,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.size(10.dp))
            Text(
                text = message,
                color = RamenMuted,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
    }
}

@Composable
private fun ComingNextSection() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Что появится дальше",
            color = RamenText,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold
        )

        FeatureRow(
            number = "01",
            title = "Игровые модули",
            description = "Запуск распознанных форматов"
        )
        FeatureRow(
            number = "02",
            title = "Сохранения и прогресс",
            description = "Возвращайся к истории с нужного места"
        )
    }
}

@Composable
private fun FeatureRow(
    number: String,
    title: String,
    description: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = RamenSurface,
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier.padding(15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(RamenSurfaceRaised),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = number,
                    color = RamenAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.size(13.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = RamenText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = description,
                    color = RamenMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }

            Text(
                text = "СКОРО",
                color = RamenMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
        }
    }
}

@Composable
private fun BuildFooter() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(RamenSurfaceRaised)
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "СДЕЛАНО ДЛЯ ИСТОРИЙ",
            color = RamenMuted,
            fontSize = 9.sp,
            letterSpacing = 1.8.sp
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RamenAppPreview() {
    RamenApp()
}
