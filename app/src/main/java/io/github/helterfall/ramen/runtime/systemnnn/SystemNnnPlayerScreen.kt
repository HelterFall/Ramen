package io.github.helterfall.ramen.runtime.systemnnn

import androidx.compose.foundation.BorderStroke 
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.matchParentSize
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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

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
                    key(sprite.layerIndex) {
                        SystemNnnAnimatedSpriteLayer(
                            sprite = sprite,
                            viewportWidth = maxWidth,
                            viewportHeight = maxHeight,
                            referenceWidth = referenceWidth,
                            referenceHeight = referenceHeight,
                            onBitmapDisposed = onBitmapDisposed
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


/**
 * Draws one native DWQ layer. The reference compositor advances SETEFFECTRECT's
 * left/top from source to destination while keeping right/bottom as dimensions.
 */
@Composable
private fun BoxScope.SystemNnnAnimatedSpriteLayer(
    sprite: SystemNnnSpriteBitmap,
    viewportWidth: androidx.compose.ui.unit.Dp,
    viewportHeight: androidx.compose.ui.unit.Dp,
    referenceWidth: Float,
    referenceHeight: Float,
    onBitmapDisposed: (Bitmap) -> Unit
) {
    DisposableEffect(sprite.bitmap) {
        onDispose { onBitmapDisposed(sprite.bitmap) }
    }

    val effectId = sprite.nativeEffectId
    val parameters = sprite.nativeEffectParameters
    val nativeRect = sprite.nativeRect
    val fromRect = sprite.nativeRectFrom
    val hasRectMotion = fromRect != null && nativeRect != null && effectId != null
    val durationFrames = when (effectId) {
        8 -> 1
        55 -> 9_999
        else -> (parameters.firstOrNull() ?: 20).coerceIn(1, 9_999)
    }
    val animationKey = listOf(
        sprite.resourceId,
        sprite.layerIndex,
        sprite.nativeEffectCommandOffset,
        fromRect,
        nativeRect,
        effectId,
        parameters
    )

    val progress = remember(animationKey) {
        Animatable(if (hasRectMotion || effectId in setOf(9, 10, 11, 39, 40, 62, 119)) 0f else 1f)
    }
    LaunchedEffect(animationKey) {
        if (hasRectMotion || effectId in setOf(9, 10, 11, 39, 40, 62, 119)) {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = (durationFrames * 16).coerceIn(16, 60_000),
                    easing = LinearEasing
                )
            )
        }
    }

    val effectCycleFrames = when (effectId) {
        55 -> 24
        132 -> (parameters.getOrNull(3) ?: 8).coerceIn(1, 60)
        18 -> (parameters.getOrNull(0) ?: 20).coerceIn(1, 60)
        39 -> (parameters.firstOrNull() ?: 20).coerceIn(1, 60)
        else -> 1
    }
    val effectCycle = remember(animationKey) { Animatable(0f) }
    LaunchedEffect(animationKey) {
        if (effectId == 55 || effectId == 132 ||
            (effectId == 18 && parameters.getOrNull(2) != 0) ||
            (effectId == 39 && parameters.getOrNull(3) != 0)
        ) {
            while (true) {
                effectCycle.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = (effectCycleFrames * 16).coerceIn(50, 2_000),
                        easing = LinearEasing
                    )
                )
                effectCycle.snapTo(0f)
            }
        } else {
            effectCycle.snapTo(1f)
        }
    }

    val t = progress.value.coerceIn(0f, 1f)
    val cycle = effectCycle.value.coerceIn(0f, 1f)
    val rectX = if (fromRect != null && nativeRect != null) {
        (fromRect.x + (nativeRect.x - fromRect.x) * t).roundToInt()
    } else nativeRect?.x ?: 0
    val rectY = if (fromRect != null && nativeRect != null) {
        (fromRect.y + (nativeRect.y - fromRect.y) * t).roundToInt()
    } else nativeRect?.y ?: 0
    // Native PrintLayers uses right/bottom as sizes, not Win32 absolute edges.
    val rectWidth = if (fromRect != null && nativeRect != null && t < 1f) {
        fromRect.width
    } else nativeRect?.width ?: sprite.bitmap.width
    val rectHeight = if (fromRect != null && nativeRect != null && t < 1f) {
        fromRect.height
    } else nativeRect?.height ?: sprite.bitmap.height

    val alpha = when (effectId) {
        8 -> ((parameters.getOrNull(1) ?: 50) / 100f).coerceIn(0f, 1f)
        9 -> {
            val start = (parameters.getOrNull(2) ?: 100).coerceIn(0, 100)
            val end = (parameters.getOrNull(1) ?: 50).coerceIn(0, 100)
            (start + (end - start) * t) / 100f
        }
        10 -> t
        11, 40 -> 1f - t
        else -> 1f
    }.coerceIn(0f, 1f)

    val shakeX = when (effectId) {
        132 -> sin(cycle * Math.PI * 4.0).toFloat() *
            (parameters.getOrNull(1) ?: 4).coerceIn(0, 64)
        18 -> sin(cycle * Math.PI * 4.0).toFloat() * 16f
        else -> 0f
    }
    val shakeY = when (effectId) {
        132 -> cos(cycle * Math.PI * 4.0).toFloat() *
            (parameters.getOrNull(2) ?: 4).coerceIn(0, 64)
        18 -> if (parameters.getOrNull(1) == 1) {
            cos(cycle * Math.PI * 4.0).toFloat() * 12f
        } else 0f
        else -> 0f
    }

    var moveX = 0f
    var moveY = 0f
    when (effectId) {
        39 -> {
            val scrollT = if (parameters.getOrNull(3) != 0) cycle else t
            moveX = (parameters.getOrNull(1) ?: 0) * durationFrames * scrollT
            moveY = (parameters.getOrNull(2) ?: 0) * durationFrames * scrollT
        }
        62 -> {
            if (parameters.size >= 9) {
                val u = t
                val inv = 1f - u
                val x1 = parameters[1].toFloat()
                val y1 = parameters[2].toFloat()
                val x2 = parameters[3].toFloat()
                val y2 = parameters[4].toFloat()
                val x3 = parameters[5].toFloat()
                val y3 = parameters[6].toFloat()
                val x4 = parameters[7].toFloat()
                val y4 = parameters[8].toFloat()
                moveX = inv * inv * inv * x1 +
                    3f * inv * inv * u * x2 +
                    3f * inv * u * u * x3 +
                    u * u * u * x4
                moveY = inv * inv * inv * y1 +
                    3f * inv * inv * u * y2 +
                    3f * inv * u * u * y3 +
                    u * u * u * y4
            }
        }
        119 -> {
            if (parameters.size >= 7) {
                val u = t
                val inv = 1f - u
                moveX = inv * inv * parameters[1] +
                    2f * inv * u * parameters[3] + u * u * parameters[5]
                moveY = inv * inv * parameters[2] +
                    2f * inv * u * parameters[4] + u * u * parameters[6]
            }
        }
    }

    val safeReferenceWidth = referenceWidth.coerceAtLeast(1f)
    val safeReferenceHeight = referenceHeight.coerceAtLeast(1f)
    val xOffset = viewportWidth * ((rectX + shakeX + moveX) / safeReferenceWidth)
    val yOffset = viewportHeight * ((rectY + shakeY + moveY) / safeReferenceHeight)
    val width = viewportWidth * (rectWidth.coerceAtLeast(1).toFloat() / safeReferenceWidth)
    val height = viewportHeight * (rectHeight.coerceAtLeast(1).toFloat() / safeReferenceHeight)

    val spriteModifier = if (nativeRect != null) {
        Modifier
            .align(Alignment.TopStart)
            .offset(x = xOffset, y = yOffset)
            .width(width)
            .height(height)
    } else {
        Modifier
            .align(Alignment.BottomCenter)
            .width(viewportWidth * sprite.widthFraction.coerceIn(0.1f, 1f))
            .height(viewportHeight * sprite.heightFraction.coerceIn(0.1f, 1f))
            .offset(
                x = viewportWidth * (sprite.centerXFraction.coerceIn(0f, 1f) - 0.5f) +
                    viewportWidth * ((shakeX + moveX) / safeReferenceWidth),
                y = viewportHeight * ((shakeY + moveY) / safeReferenceHeight)
            )
    }

    Box(
        modifier = spriteModifier.graphicsLayer {
            this.alpha = alpha
        }
    ) {
        Image(
            bitmap = sprite.bitmap.asImageBitmap(),
            contentDescription = sprite.contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = if (nativeRect != null) ContentScale.FillBounds else ContentScale.Fit,
            alignment = Alignment.TopStart
        )

        // OLDFILM is persistent in the family renderer and emits moving scratches.
        if (effectId == 55) {
            val lineCount = (parameters.getOrNull(5) ?: 3).coerceIn(1, 10)
            val lineWidth = (parameters.getOrNull(6) ?: 1).coerceIn(1, 4)
            val lineAlpha = ((parameters.getOrNull(13) ?: 100).coerceIn(0, 100) / 100f) * 0.16f
            Canvas(modifier = Modifier.matchParentSize()) {
                repeat(lineCount) { index ->
                    val phase = (cycle + index.toFloat() / lineCount) % 1f
                    val x = phase * size.width
                    drawRect(
                        color = Color.White.copy(alpha = lineAlpha),
                        topLeft = Offset(x, 0f),
                        size = Size(lineWidth.dp.toPx(), size.height)
                    )
                }
                var y = 2.dp.toPx()
                while (y < size.height) {
                    drawLine(
                        color = Color.Black.copy(alpha = 0.045f),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 0.6.dp.toPx()
                    )
                    y += 3.dp.toPx()
                }
            }
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
