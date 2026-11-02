package io.github.helterfall.ramen.runtime.scenario

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

class ScenarioDemoActivity : ComponentActivity() {
    private lateinit var project: ScenarioProject
    private lateinit var session: ScenarioSession
    private var currentFrame by mutableStateOf<ScenarioFrame?>(null)
    private var statusMessage by mutableStateOf("Это оригинальный тестовый сценарий Ramen.")
    private val saveFile: File by lazy { File(filesDir, "scenario-demo.save") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        runCatching {
            project = ScenarioParser.parse(ScenarioDemoContent.SOURCE)
            session = ScenarioSession.start(project)
            currentFrame = session.frame()
        }.onFailure {
            finish()
            return
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFF2B766),
                    background = Color(0xFF101216),
                    surface = Color(0xFF1A1E25)
                )
            ) {
                val frame = currentFrame
                if (frame != null) {
                    ScenarioDemoScreen(
                        title = project.title,
                        frame = frame,
                        statusMessage = statusMessage,
                        canLoad = saveFile.isFile,
                        onContinue = ::continueStory,
                        onChoose = ::chooseOption,
                        onSave = ::saveProgress,
                        onLoad = ::loadProgress,
                        onRestart = ::restart,
                        onExit = ::finish
                    )
                }
            }
        }
    }

    private fun continueStory() {
        runCatching { session.continueStory() }
            .onSuccess {
                currentFrame = it
                statusMessage = "История продолжается."
            }
            .onFailure {
                statusMessage = it.message ?: "Не удалось продолжить историю."
            }
    }

    private fun chooseOption(index: Int) {
        runCatching { session.choose(index) }
            .onSuccess {
                currentFrame = it
                statusMessage = "Выбор принят."
            }
            .onFailure {
                statusMessage = it.message ?: "Не удалось применить выбор."
            }
    }

    private fun saveProgress() {
        runCatching {
            val bytes = ScenarioSaveCodec.encode(session.saveState())
            val temp = File(filesDir, "scenario-demo.save.tmp")
            temp.writeBytes(bytes)
            if (saveFile.exists() && !saveFile.delete()) {
                throw IllegalStateException("Не удалось заменить старое сохранение.")
            }
            if (!temp.renameTo(saveFile)) {
                temp.delete()
                throw IllegalStateException("Не удалось записать файл сохранения.")
            }
        }.onSuccess {
            statusMessage = "Прогресс сохранён на устройстве."
        }.onFailure {
            statusMessage = it.message ?: "Не удалось сохранить прогресс."
        }
    }

    private fun loadProgress() {
        runCatching {
            val bytes = saveFile.readBytes()
            ScenarioSession.restore(project, ScenarioSaveCodec.decode(bytes))
        }.onSuccess {
            session = it
            currentFrame = it.frame()
            statusMessage = "Сохранение загружено."
        }.onFailure {
            statusMessage = it.message ?: "Не удалось загрузить сохранение."
        }
    }

    private fun restart() {
        session = ScenarioSession.start(project)
        currentFrame = session.frame()
        statusMessage = "Начата новая демо-история."
    }
}

@Composable
private fun ScenarioDemoScreen(
    title: String,
    frame: ScenarioFrame,
    statusMessage: String,
    canLoad: Boolean,
    onContinue: () -> Unit,
    onChoose: (Int) -> Unit,
    onSave: () -> Unit,
    onLoad: () -> Unit,
    onRestart: () -> Unit,
    onExit: () -> Unit
) {
    BackHandler(onBack = onExit)
    val stageColor = when (frame.backgroundResourceKey) {
        "coast" -> Color(0xFF24485A)
        "map_table" -> Color(0xFF68533E)
        "cliff" -> Color(0xFF3E4A55)
        "lighthouse" -> Color(0xFF4B5960)
        "sunset" -> Color(0xFF755044)
        else -> Color(0xFF303943)
    }

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
                        text = "RAMEN • SCENARIO RUNTIME",
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TextButton(onClick = onSave) { Text("Сохранить") }
                TextButton(onClick = onLoad, enabled = canLoad) { Text("Загрузить") }
                TextButton(onClick = onRestart) { Text("Сначала") }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(230.dp)
                    .background(stageColor, RoundedCornerShape(20.dp))
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.align(Alignment.TopStart)) {
                    Text(
                        text = "ФОН",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = frame.backgroundResourceKey ?: "не задан",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Bottom
                ) {
                    frame.characters.sortedBy { it.position.ordinal }.forEach { character ->
                        Surface(
                            color = Color(0xFF171C22).copy(alpha = 0.86f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(
                                text = character.characterId,
                                modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                                color = Color(0xFFF4F1EA),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
                Text(
                    text = "СЦЕНА: " + frame.sceneId,
                    modifier = Modifier.align(Alignment.BottomStart),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 9.sp
                )
            }

            if (frame.dialogue != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF1A1E25),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(17.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Text(
                            text = frame.dialogue.speaker,
                            color = Color(0xFFF2B766),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = frame.dialogue.text,
                            color = Color(0xFFF4F1EA),
                            fontSize = 16.sp,
                            lineHeight = 24.sp
                        )
                    }
                }
                Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                    Text("Дальше")
                }
            }

            if (frame.choices.isNotEmpty()) {
                Text(
                    text = "КАК ПОСТУПИТЬ?",
                    color = Color(0xFFF2B766),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                frame.choices.forEachIndexed { index, choice ->
                    Button(
                        onClick = { onChoose(index) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(13.dp)
                    ) {
                        Text(choice.label)
                    }
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
                        Text(
                            text = "История завершена",
                            color = Color(0xFFF4F1EA),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Button(onClick = onRestart) { Text("Пройти ещё раз") }
                    }
                }
            }

            Text(text = statusMessage, color = Color(0xFFA8ADB7), fontSize = 11.sp)
            Text(
                text = "Демо использует ключи ресурсов-заглушки; подключение реальных изображений и звука — следующий слой.",
                color = Color(0xFF818895),
                fontSize = 10.sp,
                lineHeight = 15.sp
            )
        }
    }
}
