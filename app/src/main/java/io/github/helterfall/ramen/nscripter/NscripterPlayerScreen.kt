package io.github.helterfall.ramen.nscripter

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun NscripterPlayerScreen(
    scene: NscripterSceneState,
    resources: NscripterResourceRepository?,
    screenWidth: Int,
    screenHeight: Int,
    timedWaitMillis: Int?,
    isLoading: Boolean,
    isEnded: Boolean,
    errorMessage: String?,
    onTap: () -> Unit,
    onButton: (Int) -> Unit,
    onChoice: (Int) -> Unit,
    onMenu: (String) -> Unit,
    menuVisible: Boolean,
    onToggleMenu: () -> Unit,
    onTimedWaitComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(timedWaitMillis) {
        val wait = timedWaitMillis
        if (wait != null) {
            delay(wait.toLong().coerceAtLeast(0L))
            onTimedWaitComplete()
        }
    }

    val screenInteractionSource = remember { MutableInteractionSource() }
    val textInteractionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(scene.backgroundColor?.let(::parseSceneColor) ?: 0xFF000000.toInt()))
            .clickable(
                interactionSource = screenInteractionSource,
                indication = null,
                enabled = scene.buttons.isEmpty() && scene.choices.isEmpty() &&
                    timedWaitMillis == null && !isEnded,
                onClick = onTap
            )
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val background = remember(scene.background, resources) {
                scene.background?.let { resources?.loadBitmap(it) }
            }
            if (background != null) {
                DisposableEffect(background) { onDispose { background.recycleIfNeeded() } }
                Image(
                    bitmap = background.asImageBitmap(),
                    contentDescription = "NScripter background",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds
                )
            }

            scene.sprites.filter { it.visible }.forEach { sprite ->
                val bitmap = remember(sprite, resources) {
                    resources?.loadBitmap(
                        sprite.image,
                        sprite.transparency,
                        sprite.maskFile,
                        sprite.colorKey
                    )
                }
                if (bitmap != null) {
                    DisposableEffect(bitmap) { onDispose { bitmap.recycleIfNeeded() } }
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "NScripter sprite " + sprite.number,
                        modifier = Modifier
                            .offset(
                                x = maxWidth * (sprite.x.toFloat() / screenWidth.coerceAtLeast(1)),
                                y = maxHeight * (sprite.y.toFloat() / screenHeight.coerceAtLeast(1))
                            )
                            .size(
                                width = maxWidth * (bitmap.width.toFloat() / screenWidth.coerceAtLeast(1)),
                                height = maxHeight * (bitmap.height.toFloat() / screenHeight.coerceAtLeast(1))
                            ),
                        contentScale = ContentScale.Fit
                    )
                }
            }

            scene.buttons.forEach { button ->
                val sprite = button.spriteNumber?.let { number ->
                    scene.sprites.firstOrNull { it.number == number }
                }
                val bitmap = sprite?.let {
                    remember(it, resources) {
                        resources?.loadBitmap(it.image, it.transparency, it.maskFile, it.colorKey)
                    }
                }
                if (bitmap != null) {
                    DisposableEffect(bitmap) { onDispose { bitmap.recycleIfNeeded() } }
                }
                val width = if (button.width > 0) button.width else bitmap?.width ?: 1
                val height = if (button.height > 0) button.height else bitmap?.height ?: 1
                Box(
                    modifier = Modifier
                        .offset(
                            x = maxWidth * (button.x.toFloat() / screenWidth.coerceAtLeast(1)),
                            y = maxHeight * (button.y.toFloat() / screenHeight.coerceAtLeast(1))
                        )
                        .size(
                            width = maxWidth * (width.toFloat() / screenWidth.coerceAtLeast(1)),
                            height = maxHeight * (height.toFloat() / screenHeight.coerceAtLeast(1))
                        )
                        .clip(RoundedCornerShape(3.dp))
                        .clickable { onButton(button.buttonNumber) }
                        .background(Color(0x223F96D4)),
                    contentAlignment = Alignment.Center
                ) {
                    if (button.label.isNotBlank()) {
                        Text(button.label, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (scene.textWindowVisible && scene.dialogue.isNotEmpty()) {
            val window = scene.textWindow
            BoxWithConstraints(Modifier.fillMaxSize()) {
                Surface(
                    color = if (scene.windowBack) Color(0xEE101216) else Color(0xCC101216),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .offset(
                            x = maxWidth * (window.x.toFloat() / screenWidth.coerceAtLeast(1)),
                            y = maxHeight * (window.y.toFloat() / screenHeight.coerceAtLeast(1))
                        )
                        .size(
                            width = maxWidth * (window.width.toFloat() / screenWidth.coerceAtLeast(1)),
                            height = maxHeight * (window.height.toFloat() / screenHeight.coerceAtLeast(1))
                        )
                        .clickable(
                            interactionSource = textInteractionSource,
                            indication = null,
                            enabled = timedWaitMillis == null,
                            onClick = onTap
                        )
                ) {
                    Column(
                        Modifier.fillMaxSize().padding(
                            horizontal = (window.textX.coerceAtLeast(8) / 2).dp,
                            vertical = (window.textY.coerceAtLeast(8) / 2).dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        scene.speaker?.takeIf { it.isNotBlank() }?.let {
                            Text(it, color = Color(0xFFF2B766), fontWeight = FontWeight.Bold)
                        }
                        Text(
                            scene.dialogue,
                            color = runCatching { Color(android.graphics.Color.parseColor(window.color)) }
                                .getOrDefault(Color(0xFFF4F1EA)),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            "НАЖМИТЕ ДЛЯ ПРОДОЛЖЕНИЯ",
                            color = Color(0xFFA8ADB7),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.align(Alignment.End)
                        )
                    }
                }
            }
        }

        if (scene.menu.isNotEmpty()) {
            Column(
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(onClick = onToggleMenu) {
                    Text("МЕНЮ")
                }
                if (menuVisible) {
                    scene.menu.forEach { item ->
                        Surface(color = Color(0xE6222832), shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.clickable { onMenu(item.action) }) {
                            Text(item.label, color = Color.White, modifier = Modifier.padding(10.dp))
                        }
                    }
                }
            }
        }

        if (scene.choices.isNotEmpty()) {
            Surface(
                color = Color(0xF0181C23),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(24.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Выберите вариант", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    scene.choices.forEachIndexed { index, choice ->
                        Button(onClick = { onChoice(index) }, modifier = Modifier.fillMaxWidth()) {
                            Text(choice.label)
                        }
                    }
                }
            }
        }

        if (isLoading) {
            Surface(color = Color(0xDD101216), shape = RoundedCornerShape(12.dp),
                modifier = Modifier.align(Alignment.Center)) {
                Text("Загрузка сценария…", color = Color.White, modifier = Modifier.padding(18.dp))
            }
        }

        if (isEnded) {
            Surface(color = Color(0xE6101216), shape = RoundedCornerShape(16.dp),
                modifier = Modifier.align(Alignment.Center)) {
                Text("Конец сценария", color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp))
            }
        }

        errorMessage?.let { message ->
            Surface(color = Color(0xE62A1717), shape = RoundedCornerShape(10.dp),
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp)) {
                Text(message, color = Color.White, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

private fun parseSceneColor(value: String): Int =
    runCatching { android.graphics.Color.parseColor(value) }.getOrDefault(android.graphics.Color.BLACK)

private fun Bitmap.recycleIfNeeded() {
    if (!isRecycled) recycle()
}
