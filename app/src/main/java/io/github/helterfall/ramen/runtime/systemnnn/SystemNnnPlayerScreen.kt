package io.github.helterfall.ramen.runtime.systemnnn

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Presentational SPT scene. Scene commands and resource IDs are decoded by the
 * runtime ViewModel; this composable only renders already-resolved Bitmaps.
 */
@Composable
fun SystemNnnPlayerScreen(
    state: SystemNnnPlayerUiState,
    onScreenTap: () -> Unit,
    onChoiceSelected: (String) -> Unit,
    onTypewriterProgress: (Int) -> Unit,
    onTypewriterFinished: () -> Unit,
    onBitmapDisposed: (Bitmap) -> Unit,
    onQuickAction: (SystemNnnPlayerQuickAction) -> Unit,
    modifier: Modifier = Modifier
) {
    var autoMode by remember { mutableStateOf(false) }
    var skipMode by remember { mutableStateOf(false) }

    LaunchedEffect(state.frameId, state.forceReveal, skipMode) {
        if (state.dialogueText.isEmpty() || state.forceReveal) {
            return@LaunchedEffect
        }
        if (skipMode) {
            onTypewriterProgress(state.dialogueText.length)
            onTypewriterFinished()
            return@LaunchedEffect
        }

        for (visibleCount in 1..state.dialogueText.length) {
            delay(TYPEWRITER_DELAY_MILLIS)
            onTypewriterProgress(visibleCount)
        }
        onTypewriterFinished()
    }

    LaunchedEffect(autoMode, skipMode, state.frameId, state.isTextComplete, state.choices, state.isEnded, state.isLoading) {
        if ((!autoMode && !skipMode) ||
            !state.isTextComplete ||
            state.choices.isNotEmpty() ||
            state.isEnded ||
            state.isLoading
        ) {
            return@LaunchedEffect
        }

        delay(if (skipMode) SKIP_ADVANCE_DELAY_MILLIS else AUTO_ADVANCE_DELAY_MILLIS)
        onScreenTap()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = state.choices.isEmpty() && !state.isEnded,
                onClick = onScreenTap
            )
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val sceneAspectRatio = state.backgroundBitmap?.let { bitmap ->
                bitmap.width.toFloat() / bitmap.height.toFloat()
            } ?: state.sprites.firstOrNull()?.bitmap?.let { bitmap ->
                bitmap.width.toFloat() / bitmap.height.toFloat()
            } ?: (4f / 3f)
            val availableAspectRatio = maxWidth.value / maxHeight.value
            val viewportModifier = if (availableAspectRatio > sceneAspectRatio) {
                Modifier.fillMaxHeight().aspectRatio(sceneAspectRatio)
            } else {
                Modifier.fillMaxWidth().aspectRatio(sceneAspectRatio)
            }

            BoxWithConstraints(
                modifier = viewportModifier.align(Alignment.Center)
            ) {
                val referenceWidth = state.backgroundBitmap?.width?.toFloat()
                    ?: state.sprites.firstOrNull()?.bitmap?.width?.toFloat()
                    ?: 800f
                val referenceHeight = state.backgroundBitmap?.height?.toFloat()
                    ?: state.sprites.firstOrNull()?.bitmap?.height?.toFloat()
                    ?: 600f

                state.backgroundBitmap?.let { background ->
                    DisposableEffect(background) {
                        onDispose { onBitmapDisposed(background) }
                    }
                    Image(
                        bitmap = background.asImageBitmap(),
                        contentDescription = "Scene background",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds,
                        alignment = Alignment.TopStart
                    )
                }

                state.sprites.forEach { sprite ->
                    DisposableEffect(sprite.bitmap) {
                        onDispose { onBitmapDisposed(sprite.bitmap) }
                    }
                    val nativeRect = sprite.nativeRect
                    if (nativeRect != null && nativeRect.width > 0 && nativeRect.height > 0) {
                        val leftFraction = (nativeRect.x / referenceWidth).coerceIn(-2f, 2f)
                        val topFraction = (nativeRect.y / referenceHeight).coerceIn(-2f, 2f)
                        val widthFraction = (nativeRect.width / referenceWidth).coerceIn(0f, 2f)
                        val heightFraction = (nativeRect.height / referenceHeight).coerceIn(0f, 2f)
                        Image(
                            bitmap = sprite.bitmap.asImageBitmap(),
                            contentDescription = sprite.contentDescription,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .offset(x = maxWidth * leftFraction, y = maxHeight * topFraction)
                                .width(maxWidth * widthFraction)
                                .fillMaxHeight(heightFraction),
                            contentScale = ContentScale.FillBounds,
                            alignment = Alignment.TopStart
                        )
                    } else {
                        val widthFraction = sprite.widthFraction.coerceIn(0.1f, 1.0f)
                        val heightFraction = sprite.heightFraction.coerceIn(0.1f, 1.0f)
                        Image(
                            bitmap = sprite.bitmap.asImageBitmap(),
                            contentDescription = sprite.contentDescription,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .width(maxWidth * widthFraction)
                                .fillMaxHeight(heightFraction)
                                .offset(
                                    x = maxWidth * (sprite.centerXFraction.coerceIn(0f, 1f) - 0.5f)
                                ),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }
        }

        QuickMenu(
            autoEnabled = autoMode,
            skipEnabled = skipMode,
            onSave = { onQuickAction(SystemNnnPlayerQuickAction.SAVE) },
            onLoad = { onQuickAction(SystemNnnPlayerQuickAction.LOAD) },
            onAutoToggle = {
                autoMode = !autoMode
                if (autoMode) skipMode = false
            },
            onSkipToggle = {
                skipMode = !skipMode
                if (skipMode) autoMode = false
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(14.dp)
        )

        if (!state.isEnded) {
            DialogueBox(
                speakerName = state.speakerName,
                dialogue = state.dialogueText.take(
                    state.visibleCharacterCount.coerceIn(0, state.dialogueText.length)
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 18.dp)
            )
        } else {
            Surface(
                color = Color(0xE6101216),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Text(
                    text = "Конец сценария",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp)
                )
            }
        }

        val failureMessage = state.errorMessage ?: state.audioWarning
        if (failureMessage != null) {
            Surface(
                color = Color(0xE62A1717),
                contentColor = Color.White,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFFB85C5C)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                Text(
                    text = failureMessage,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }
        } else {
            state.statusMessage?.let { message ->
                Surface(
                    color = Color(0xE6252A30),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFF8B7658)),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(14.dp)
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        if (state.isLoading) {
            Surface(
                color = Color(0xCC101216),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Text(
                    text = "Загрузка сцены…",
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp)
                )
            }
        }

        if (state.choices.isNotEmpty()) {
            ChoiceOverlay(
                choices = state.choices,
                onChoiceSelected = onChoiceSelected,
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 26.dp)
            )
        }
    }
}

@Composable
private fun DialogueBox(
    speakerName: String?,
    dialogue: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xE6101216))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!speakerName.isNullOrBlank()) {
            Text(
                text = speakerName,
                color = Color(0xFFF2B766),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = dialogue,
            color = Color(0xFFF4F1EA),
            style = MaterialTheme.typography.bodyLarge,
            minLines = 2
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Text(
                text = "НАЖМИТЕ ДЛЯ ПРОДОЛЖЕНИЯ",
                color = Color(0xFFA8ADB7),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

/** Save/load UI actions are forwarded to the Activity; persistence is not part of this screen. */
enum class SystemNnnPlayerQuickAction {
    SAVE,
    LOAD
}

@Composable
private fun QuickMenu(
    autoEnabled: Boolean,
    skipEnabled: Boolean,
    onSave: () -> Unit,
    onLoad: () -> Unit,
    onAutoToggle: () -> Unit,
    onSkipToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color(0xD9101216),
        contentColor = Color.White,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color(0x665F6875)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            QuickMenuButton(label = "SAVE", selected = false, onClick = onSave)
            QuickMenuButton(label = "LOAD", selected = false, onClick = onLoad)
            QuickMenuButton(label = "AUTO", selected = autoEnabled, onClick = onAutoToggle)
            QuickMenuButton(label = "SKIP", selected = skipEnabled, onClick = onSkipToggle)
        }
    }
}

@Composable
private fun QuickMenuButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(9.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color(0xFFB8793D) else Color(0xFF303741),
            contentColor = Color.White
        )
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }
}


@Composable
private fun ChoiceOverlay(
    choices: List<SystemNnnChoiceOption>,
    onChoiceSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color(0xF0181C23),
        contentColor = Color.White,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color(0xFF555D69)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Выберите вариант",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            choices.forEach { choice ->
                Button(
                    onClick = { onChoiceSelected(choice.id) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF343C49),
                        contentColor = Color.White
                    )
                ) {
                    Text(choice.label)
                }
            }
        }
    }
}

private const val TYPEWRITER_DELAY_MILLIS = 24L
private const val AUTO_ADVANCE_DELAY_MILLIS = 1_350L
private const val SKIP_ADVANCE_DELAY_MILLIS = 160L
