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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Presentational SPT scene. Scene commands and resource IDs are decoded by the
 * runtime ViewModel; this composable only renders already-resolved Bitmaps.
 */
@Composable
fun SystemNnnPlayerScreen(
    state: SystemNnnPlayerUiState,
    onScreenTap: () -> Unit,
    onDrawFrameCompleted: () -> Unit,
    onChoiceSelected: (String) -> Unit,
    onTypewriterProgress: (Int) -> Unit,
    onTypewriterFinished: () -> Unit,
    onBitmapDisposed: (Bitmap) -> Unit,
    onExportDiagnostics: () -> Unit,
    onQuickAction: (SystemNnnPlayerQuickAction) -> Unit,
    modifier: Modifier = Modifier
) {
    var autoMode by remember { mutableStateOf(false) }
    var skipMode by remember { mutableStateOf(false) }
    val screenEffectKey = listOf(state.screenEffectId, state.screenEffectParameters, state.screenEffectCommandOffset)
    var screenEffectFrame by remember(screenEffectKey) { mutableIntStateOf(0) }

    LaunchedEffect(screenEffectKey) {
        val effectId = state.screenEffectId ?: return@LaunchedEffect
        val parameters = state.screenEffectParameters
        val duration = if (effectId == 55) 9_999
            else (parameters.firstOrNull() ?: 20).coerceIn(1, 9_999)
        val repeats = effectId == 55 || (effectId == 18 && (parameters.getOrNull(2) ?: 0) != 0)
        if (repeats) {
            while (true) {
                delay(16L)
                screenEffectFrame = (screenEffectFrame + 1) % duration
            }
        } else {
            for (frame in 0..duration) {
                screenEffectFrame = frame
                if (frame < duration) delay(16L)
            }
        }
    }

    LaunchedEffect(state.drawEventId, state.pendingDrawFrameCount) {
        val frames = state.pendingDrawFrameCount ?: return@LaunchedEffect
        delay(frames.coerceIn(1, 9_999) * 16L)
        onDrawFrameCompleted()
    }

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

    LaunchedEffect(
        autoMode, skipMode, state.frameId, state.drawEventId, state.isTextComplete,
        state.choices, state.isEnded, state.isLoading, state.pendingDrawFrameCount
    ) {
        if ((!autoMode && !skipMode) ||
            state.pendingDrawFrameCount != null ||
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
                enabled = state.choices.isEmpty() && !state.isEnded &&
                    !state.isLoading && state.pendingDrawFrameCount == null,
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
                modifier = viewportModifier.align(Alignment.Center).clipToBounds()
            ) {
                val viewportDpWidth = maxWidth
                val viewportDpHeight = maxHeight
                val referenceWidth = (state.backgroundBitmap?.width
                    ?: state.sprites.firstOrNull()?.bitmap?.width ?: 800).toFloat()
                val referenceHeight = (state.backgroundBitmap?.height
                    ?: state.sprites.firstOrNull()?.bitmap?.height ?: 600).toFloat()
                val screenShake = if (state.screenEffectId == 18) {
                    SystemNnnVisualEffectMath.shakeOffset(
                        frame = screenEffectFrame,
                        frameCount = (state.screenEffectParameters.firstOrNull() ?: 20).coerceAtLeast(1),
                        vertical = (state.screenEffectParameters.getOrNull(1) ?: 0) != 0
                    )
                } else SystemNnnVisualOffset(0f, 0f)

                val changePartners = state.sprites.mapNotNull { sprite ->
                    if (sprite.nativeEffectId != 40) return@mapNotNull null
                    val partnerLayer = sprite.nativeEffectParameters.getOrNull(1) ?: return@mapNotNull null
                    if (partnerLayer == sprite.layerIndex) return@mapNotNull null
                    val partner = state.sprites.firstOrNull { it.layerIndex == partnerLayer } ?: return@mapNotNull null
                    if (partner.bitmap.width != sprite.bitmap.width || partner.bitmap.height != sprite.bitmap.height) {
                        return@mapNotNull null
                    }
                    sprite.layerIndex to partner
                }.toMap()
                val hiddenPartnerLayers = changePartners.values.map { it.layerIndex }.toSet()

                Box(
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        translationX = viewportDpWidth.toPx() * (screenShake.x / referenceWidth.coerceAtLeast(1f))
                        translationY = viewportDpHeight.toPx() * (screenShake.y / referenceHeight.coerceAtLeast(1f))
                    }
                ) {
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
                        if (sprite.layerIndex !in hiddenPartnerLayers) {
                            key(sprite.layerIndex) {
                                SystemNnnAnimatedSpriteLayer(
                                    sprite = sprite,
                                    changePartner = changePartners[sprite.layerIndex],
                                    viewportWidth = viewportDpWidth,
                                    viewportHeight = viewportDpHeight,
                                    referenceWidth = referenceWidth,
                                    referenceHeight = referenceHeight,
                                    onBitmapDisposed = onBitmapDisposed
                                )
                            }
                        }
                    }
                }
                state.screenEffectId?.let { effectId ->
                    SystemNnnScreenEffectOverlay(
                        effectId = effectId,
                        parameters = state.screenEffectParameters,
                        frame = screenEffectFrame,
                        referenceWidth = referenceWidth,
                        referenceHeight = referenceHeight,
                        modifier = Modifier.fillMaxSize()
                    )
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
            onExportDiagnostics = onExportDiagnostics,
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
    changePartner: SystemNnnSpriteBitmap?,
    viewportWidth: androidx.compose.ui.unit.Dp,
    viewportHeight: androidx.compose.ui.unit.Dp,
    referenceWidth: Float,
    referenceHeight: Float,
    onBitmapDisposed: (Bitmap) -> Unit
) {
    DisposableEffect(sprite.bitmap) { onDispose { onBitmapDisposed(sprite.bitmap) } }
    if (changePartner != null && changePartner.bitmap !== sprite.bitmap) {
        DisposableEffect(changePartner.bitmap) { onDispose { onBitmapDisposed(changePartner.bitmap) } }
    }

    val effectId = sprite.nativeEffectId
    val parameters = sprite.nativeEffectParameters
    val nativeRect = sprite.nativeRect
    val fromRect = sprite.nativeRectFrom
    val hasRectMotion = fromRect != null && nativeRect != null && effectId != null
    val defaultDurationFrames = if (effectId == 62 || effectId == 119) 1 else 20
    val durationFrames = (parameters.firstOrNull() ?: defaultDurationFrames).coerceIn(1, 9_999)
    val animationKey = listOf(sprite.resourceId, sprite.layerIndex, sprite.nativeEffectCommandOffset,
        fromRect, nativeRect, effectId, parameters)
    val progress = remember(animationKey) {
        Animatable(if (hasRectMotion || effectId in setOf(9, 17, 39, 40, 52, 62, 64, 65, 99, 100, 119)) 0f else 1f)
    }
    LaunchedEffect(animationKey) {
        if (hasRectMotion || effectId in setOf(9, 39, 40, 52, 62, 64, 65, 99, 119)) {
            progress.animateTo(1f, tween((durationFrames * 16).coerceIn(16, 60_000), easing = LinearEasing))
        }
    }
    val cycleFrames = when (effectId) {
        39 -> (parameters.firstOrNull() ?: 20).coerceIn(1, 9_999)
        132 -> (parameters.getOrNull(3) ?: 8).coerceIn(1, 60)
        else -> 1
    }
    val cycle = remember(animationKey) { Animatable(0f) }
    LaunchedEffect(animationKey) {
        if ((effectId == 39 && (parameters.getOrNull(3) ?: 0) != 0) || effectId == 132) {
            while (true) {
                cycle.animateTo(1f, tween((cycleFrames * 16).coerceIn(16, 60_000), easing = LinearEasing))
                cycle.snapTo(0f)
            }
        } else cycle.snapTo(1f)
    }

    val t = progress.value.coerceIn(0f, 1f)
    val frame = (t * durationFrames).roundToInt().coerceIn(0, durationFrames)
    val cycleFrame = (cycle.value * cycleFrames).roundToInt().coerceIn(0, cycleFrames)
    val movedRect = if (fromRect != null && nativeRect != null) {
        SystemNnnVisualEffectMath.moveRect(frame, durationFrames, fromRect, nativeRect)
    } else nativeRect
    val rectX = movedRect?.x ?: 0
    val rectY = movedRect?.y ?: 0
    val rectWidth = movedRect?.width ?: sprite.bitmap.width
    val rectHeight = movedRect?.height ?: sprite.bitmap.height

    val alpha = when (effectId) {
        8 -> ((parameters.getOrNull(1) ?: 50) / 100f).coerceIn(0f, 1f)
        9 -> SystemNnnVisualEffectMath.nativeSpeedValue(
            frame, durationFrames,
            (parameters.getOrNull(2) ?: 100).coerceIn(0, 100),
            (parameters.getOrNull(1) ?: 50).coerceIn(0, 100),
            parameters.getOrNull(4) ?: 0,
            parameters.getOrNull(3) ?: 0
        ).coerceIn(0, 100) / 100f
        else -> 1f
    }
    val isTurn = effectId == 64
    val isFlash = effectId == 17
    val isHaikeiYure = effectId == 100 && (parameters.getOrNull(5) ?: 0) == 0
    val isCloud = effectId == 82 &&
        (parameters.getOrNull(0) ?: 9_999) == 0 &&
        (parameters.getOrNull(8) ?: 0) == 0
    val haikeiOffset = if (isHaikeiYure) {
        SystemNnnVisualEffectMath.haikeiYureOffset(frame, parameters)
    } else SystemNnnVisualOffset(0f, 0f)
    val flashState = if (isFlash) {
        SystemNnnVisualEffectMath.flashState(frame, parameters)
    } else null
    val cloudBitmap = remember(sprite.bitmap, parameters, referenceWidth, referenceHeight, isCloud) {
        if (isCloud && !sprite.bitmap.isRecycled) {
            runCatching {
                val sourcePixels = IntArray(sprite.bitmap.width * sprite.bitmap.height)
                sprite.bitmap.getPixels(
                    sourcePixels, 0, sprite.bitmap.width,
                    0, 0, sprite.bitmap.width, sprite.bitmap.height
                )
                val warped = SystemNnnVisualEffectMath.cloudWarpPixels(
                    frame = 0L,
                    parameters = parameters,
                    sourcePixels = sourcePixels,
                    sourceWidth = sprite.bitmap.width,
                    sourceHeight = sprite.bitmap.height,
                    screenWidth = referenceWidth.roundToInt().coerceAtLeast(1),
                    screenHeight = referenceHeight.roundToInt().coerceAtLeast(1)
                ) ?: return@runCatching null
                Bitmap.createBitmap(
                    warped.pixels, warped.width, warped.height, Bitmap.Config.ARGB_8888
                )
            }.getOrNull()
        } else null
    }
    val turnPivot = if (isTurn) SystemNnnVisualEffectMath.turnPivotOffset(parameters)
        else SystemNnnVisualOffset(0f, 0f)
    val turnOffset = if (isTurn) {
        SystemNnnVisualEffectMath.turnCenteredOffset(
            frame, parameters, rectX, rectY
        )
    } else SystemNnnVisualOffset(0f, 0f)
    val turnRotation = if (isTurn) SystemNnnVisualEffectMath.turnRotationDegrees(frame, parameters) else 0f
    val turnScale = if (isTurn) SystemNnnVisualEffectMath.turnScale(frame, parameters) else 1f
    val layerAlpha = if (isTurn) SystemNnnVisualEffectMath.turnAlpha(frame, parameters) else alpha
    val isColorAddSub = effectId == 99
    val colorAddSub = if (isColorAddSub) {
        SystemNnnVisualEffectMath.colorAddSubOffset(frame, parameters)
    } else SystemNnnVisualColor(0, 0, 0)
    val colorAddSubFilter = if (isColorAddSub) {
        additiveColorFilter(
            colorAddSub.red,
            colorAddSub.green,
            colorAddSub.blue,
            forceOpaque = (parameters.getOrNull(7) ?: 1) == 0
        )
    } else null
    val isStretchBlt = effectId == 65
    val stretchPivot = if (isStretchBlt) {
        SystemNnnVisualEffectMath.stretchBltPivotOffset(parameters)
    } else SystemNnnVisualOffset(0f, 0f)
    val stretchScaleX = if (isStretchBlt) {
        SystemNnnVisualEffectMath.stretchBltScale(frame, parameters, horizontal = true)
    } else 1f
    val stretchScaleY = if (isStretchBlt) {
        SystemNnnVisualEffectMath.stretchBltScale(frame, parameters, horizontal = false)
    } else 1f
    val stretchAlpha = if (isStretchBlt) {
        SystemNnnVisualEffectMath.stretchBltAlpha(frame, parameters)
    } else 1f
    val stretchColor = if (isStretchBlt) {
        SystemNnnVisualEffectMath.stretchBltColorOffset(frame, parameters)
    } else SystemNnnVisualColor(0, 0, 0)
    val stretchColorFilter = if (isStretchBlt) {
        additiveColorFilter(
            stretchColor.red,
            stretchColor.green,
            stretchColor.blue,
            forceOpaque = (parameters.getOrNull(9) ?: 0) == 0
        )
    } else null
    val ctransColor = if (effectId == 9) {
        SystemNnnVisualEffectMath.ctransColorOffset(frame, parameters)
    } else null
    val ctransClip = if (effectId == 9) {
        SystemNnnVisualEffectMath.ctransClipRect(
            frame, parameters, referenceWidth.roundToInt(), referenceHeight.roundToInt()
        )
    } else null
    val characterJitter = if (effectId == 132) {
        SystemNnnVisualEffectMath.charaBureOffset(
            cycleFrame.toLong(), parameters,
            sprite.resourceId.hashCode() xor (sprite.nativeEffectCommandOffset?.toInt() ?: 0)
        )
    } else SystemNnnVisualOffset(0f, 0f)
    val pathOffset = when (effectId) {
        62 -> SystemNnnVisualEffectMath.bezierOffset(frame, parameters)
        119 -> SystemNnnVisualEffectMath.bSpline2Offset(frame, parameters)
        else -> SystemNnnVisualOffset(0f, 0f)
    }
    val safeReferenceWidth = referenceWidth.coerceAtLeast(1f)
    val safeReferenceHeight = referenceHeight.coerceAtLeast(1f)
    val isScroll = effectId == 39
    val xOffset = viewportWidth * ((rectX + characterJitter.x + pathOffset.x) / safeReferenceWidth)
    val yOffset = viewportHeight * ((rectY + characterJitter.y + pathOffset.y) / safeReferenceHeight)
    val width = viewportWidth * (rectWidth.coerceAtLeast(1).toFloat() / safeReferenceWidth)
    val height = viewportHeight * (rectHeight.coerceAtLeast(1).toFloat() / safeReferenceHeight)
    val spriteModifier = when {
        isTurn -> Modifier.align(Alignment.Center)
            .offset(
                x = viewportWidth * (turnOffset.x / safeReferenceWidth),
                y = viewportHeight * (turnOffset.y / safeReferenceHeight)
            )
            .width(viewportWidth * (sprite.bitmap.width.toFloat() / safeReferenceWidth))
            .height(viewportHeight * (sprite.bitmap.height.toFloat() / safeReferenceHeight))
        isStretchBlt -> Modifier.align(Alignment.Center)
            .offset(
                x = viewportWidth * ((rectX - stretchPivot.x) / safeReferenceWidth),
                y = viewportHeight * ((rectY - stretchPivot.y) / safeReferenceHeight)
            )
            .width(viewportWidth * (sprite.bitmap.width.toFloat() / safeReferenceWidth))
            .height(viewportHeight * (sprite.bitmap.height.toFloat() / safeReferenceHeight))
        isScroll -> Modifier.align(Alignment.TopStart).fillMaxSize()
        nativeRect != null -> Modifier.align(Alignment.TopStart).offset(xOffset, yOffset).width(width).height(height)
        else -> Modifier.align(Alignment.BottomCenter)
            .width(viewportWidth * sprite.widthFraction.coerceIn(0.1f, 1f))
            .height(viewportHeight * sprite.heightFraction.coerceIn(0.1f, 1f))
            .offset(
                x = viewportWidth * (sprite.centerXFraction.coerceIn(0f, 1f) - 0.5f) +
                    viewportWidth * ((characterJitter.x + pathOffset.x) / safeReferenceWidth),
                y = viewportHeight * ((characterJitter.y + pathOffset.y) / safeReferenceHeight)
            )
    }

    val turnColorFilter = if (isTurn) {
        val addSubEnabled = (parameters.getOrNull(16) ?: 0) != 0
        val endR = parameters.getOrNull(17) ?: 0
        val endG = parameters.getOrNull(18) ?: endR
        val endB = parameters.getOrNull(19) ?: endG
        val startR = parameters.getOrNull(20) ?: endR
        val startG = parameters.getOrNull(21) ?: endG
        val startB = parameters.getOrNull(22) ?: endB
        val r = if (addSubEnabled) SystemNnnVisualEffectMath.nativeSpeedValue(frame, durationFrames, startR, endR,
            parameters.getOrNull(24) ?: 0, parameters.getOrNull(23) ?: 0) else 0
        val g = if (addSubEnabled) SystemNnnVisualEffectMath.nativeSpeedValue(frame, durationFrames, startG, endG,
            parameters.getOrNull(24) ?: 0, parameters.getOrNull(23) ?: 0) else 0
        val b = if (addSubEnabled) SystemNnnVisualEffectMath.nativeSpeedValue(frame, durationFrames, startB, endB,
            parameters.getOrNull(24) ?: 0, parameters.getOrNull(23) ?: 0) else 0
        // TURN mode 0 ignores the DWQ alpha mask; mode 2 uses the decoded mask.
        additiveColorFilter(r, g, b, forceOpaque = (parameters.getOrNull(7) ?: 0) == 0)
    } else null
    val ctransColorFilter = ctransColor?.let { additiveColorFilter(it.red, it.green, it.blue) }

    Box(modifier = spriteModifier.graphicsLayer {
        this.alpha = layerAlpha
        if (isTurn) {
            // The native pixel renderer maps destination coordinates back into source space.
            rotationZ = -turnRotation
            scaleX = turnScale
            scaleY = turnScale
            transformOrigin = TransformOrigin(
                (sprite.bitmap.width / 2f + turnPivot.x) / sprite.bitmap.width,
                (sprite.bitmap.height / 2f + turnPivot.y) / sprite.bitmap.height
            )
        }
        if (isStretchBlt) {
            this.alpha = stretchAlpha
            scaleX = stretchScaleX
            scaleY = stretchScaleY
            transformOrigin = TransformOrigin(
                (sprite.bitmap.width / 2f + stretchPivot.x) / sprite.bitmap.width,
                (sprite.bitmap.height / 2f + stretchPivot.y) / sprite.bitmap.height
            )
        }
    }) {
        when {
            isTurn -> {
                Image(
                    bitmap = sprite.bitmap.asImageBitmap(),
                    contentDescription = sprite.contentDescription,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                    alignment = Alignment.TopStart,
                    colorFilter = turnColorFilter
                )
            }
            isColorAddSub -> {
                Image(
                    bitmap = sprite.bitmap.asImageBitmap(),
                    contentDescription = sprite.contentDescription,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = if (nativeRect != null) ContentScale.FillBounds else ContentScale.Fit,
                    alignment = Alignment.TopStart,
                    colorFilter = colorAddSubFilter
                )
            }
            isStretchBlt -> {
                Image(
                    bitmap = sprite.bitmap.asImageBitmap(),
                    contentDescription = sprite.contentDescription,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                    alignment = Alignment.TopStart,
                    colorFilter = stretchColorFilter
                )
            }
            isScroll -> {
                val wrapped = SystemNnnVisualEffectMath.scrollOffset(
                    if (SystemNnnVisualEffectMath.scrollUsesLoopFrame(parameters)) cycleFrame else frame,
                    parameters, safeReferenceWidth.roundToInt().coerceAtLeast(1),
                    safeReferenceHeight.roundToInt().coerceAtLeast(1)
                )
                val dx = viewportWidth * (wrapped.x / safeReferenceWidth)
                val dy = viewportHeight * (wrapped.y / safeReferenceHeight)
                for (xRepeat in 0..1) for (yRepeat in 0..1) {
                    Image(
                        bitmap = sprite.bitmap.asImageBitmap(),
                        contentDescription = sprite.contentDescription,
                        modifier = Modifier.offset(dx - viewportWidth * xRepeat, dy - viewportHeight * yRepeat)
                            .width(viewportWidth).height(viewportHeight),
                        contentScale = ContentScale.FillBounds,
                        alignment = Alignment.TopStart
                    )
                }
            }
            effectId == 40 && changePartner != null -> {
                val blend = SystemNnnVisualEffectMath.changeBlend(frame, parameters)
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // Blend both source images offscreen before composing over the scene.
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                ) {
                    val dst = IntSize(size.width.roundToInt(), size.height.roundToInt())
                    drawImage(sprite.bitmap.asImageBitmap(), dstSize = dst, alpha = blend.primaryAlpha)
                    drawImage(changePartner.bitmap.asImageBitmap(), dstSize = dst,
                        alpha = blend.secondaryAlpha, blendMode = BlendMode.Plus)
                }
            }
            else -> {
                if (SystemNnnVisualEffectMath.shouldDrawPlainLayerBitmap(effectId, parameters) || (isCloud && cloudBitmap == null)) {
                    if (effectId == 9 && ctransClip != null && nativeRect != null) {
                        if (nativeRect.width > 0 && nativeRect.height > 0 &&
                            ctransClip.width > 0 && ctransClip.height > 0
                        ) {
                            Canvas(Modifier.fillMaxSize()) {
                                val left = (ctransClip.x - nativeRect.x).toFloat() * size.width / nativeRect.width
                                val top = (ctransClip.y - nativeRect.y).toFloat() * size.height / nativeRect.height
                                val right = (ctransClip.x + ctransClip.width - nativeRect.x).toFloat() *
                                    size.width / nativeRect.width
                                val bottom = (ctransClip.y + ctransClip.height - nativeRect.y).toFloat() *
                                    size.height / nativeRect.height
                                clipRect(left, top, right, bottom) {
                                    drawImage(
                                        sprite.bitmap.asImageBitmap(),
                                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                                        colorFilter = ctransColorFilter
                                    )
                                }
                            }
                        }
                    } else {
                        Image(
                            bitmap = sprite.bitmap.asImageBitmap(),
                            contentDescription = sprite.contentDescription,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = if (nativeRect != null) ContentScale.FillBounds else ContentScale.Fit,
                            alignment = Alignment.TopStart,
                            colorFilter = ctransColorFilter
                        )
                    }
                }
            }
        }
    }
}

private fun additiveColorFilter(
    red: Int,
    green: Int,
    blue: Int,
    forceOpaque: Boolean = false
): ColorFilter =
    ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, red.coerceIn(-255, 255) / 255f,
                0f, 1f, 0f, 0f, green.coerceIn(-255, 255) / 255f,
                0f, 0f, 1f, 0f, blue.coerceIn(-255, 255) / 255f,
                0f, 0f, 0f, if (forceOpaque) 0f else 1f, if (forceOpaque) 1f else 0f
            )
        )
    )

private fun visualEffectRandom(value: Int): Int {
    var mixed = value
    mixed = (mixed xor (mixed ushr 16)) * 0x7feb352d
    mixed = (mixed xor (mixed ushr 15)) * 0x846ca68b.toInt()
    mixed = mixed xor (mixed ushr 16)
    return mixed and Int.MAX_VALUE
}

@Composable
private fun SystemNnnScreenEffectOverlay(
    effectId: Int,
    parameters: List<Int>,
    frame: Int,
    referenceWidth: Float,
    referenceHeight: Float,
    modifier: Modifier = Modifier
) {
    when (effectId) {
        10, 11 -> {
            val fade = checkNotNull(SystemNnnVisualEffectMath.screenFade(effectId, frame, parameters))
            val color = fade.color
            Box(
                modifier = modifier.background(
                    Color(
                        color.red / 255f,
                        color.green / 255f,
                        color.blue / 255f,
                        fade.alpha
                    )
                )
            )
        }
        54 -> {
            val color = SystemNnnVisualEffectMath.nativeScreenColor(parameters)
            Box(
                modifier = modifier.background(
                    Color(color.red / 255f, color.green / 255f, color.blue / 255f, 1f)
                )
            )
        }
        55 -> {
            val left = (parameters.getOrNull(1) ?: 0).coerceIn(0, referenceWidth.toInt())
            val top = (parameters.getOrNull(2) ?: 0).coerceIn(0, referenceHeight.toInt())
            val width = (parameters.getOrNull(3) ?: referenceWidth.toInt()).coerceAtLeast(1)
            val height = (parameters.getOrNull(4) ?: referenceHeight.toInt()).coerceAtLeast(1)
            val lineCount = (parameters.getOrNull(5) ?: 3).coerceIn(1, 10)
            val lineWidth = (parameters.getOrNull(6) ?: 1).coerceIn(1, 4)
            val red = (parameters.getOrNull(7) ?: 128).coerceIn(0, 255) / 255f
            val green = (parameters.getOrNull(8) ?: 128).coerceIn(0, 255) / 255f
            val blue = (parameters.getOrNull(9) ?: 128).coerceIn(0, 255) / 255f
            val baseLifetime = (parameters.getOrNull(10) ?: 200).coerceIn(1, 10_000)
            val lifetimeRandom = (parameters.getOrNull(11) ?: 80).coerceIn(0, 100)
            val printRandom = (parameters.getOrNull(12) ?: 30).coerceIn(0, 100)
            val minTransparency = (parameters.getOrNull(13) ?: 100).coerceIn(0, 100)
            val maxTransparency = (parameters.getOrNull(14) ?: 100).coerceIn(0, 100)
            val lowTransparency = minOf(minTransparency, maxTransparency)
            val highTransparency = maxOf(minTransparency, maxTransparency)
            val variation = (baseLifetime * lifetimeRandom / 100).coerceAtLeast(1)
            val minLifetime = (baseLifetime - variation).coerceAtLeast(1)
            val maxLifetime = (baseLifetime + variation).coerceAtMost(20_000)
            Canvas(modifier) {
                val sx = size.width / referenceWidth.coerceAtLeast(1f)
                val sy = size.height / referenceHeight.coerceAtLeast(1f)
                val x = left * sx
                val y = top * sy
                val w = (width * sx).coerceAtMost(size.width - x)
                val h = (height * sy).coerceAtMost(size.height - y)
                if (w <= 0f || h <= 0f) return@Canvas
                repeat(lineCount) { index ->
                    val lineSeed = visualEffectRandom(index * 0x45d9f3b)
                    val lifeRange = (maxLifetime - minLifetime + 1).coerceAtLeast(1)
                    val lifetime = minLifetime + lineSeed % lifeRange
                    val lifeCycle = frame / lifetime
                    val age = frame % lifetime
                    val seed = visualEffectRandom(index * 0x119de1f3 xor (lifeCycle * 0x3449f5))
                    val xSpan = (width - lineWidth).coerceAtLeast(1)
                    var linePosition = (seed % xSpan).toFloat()
                    var remaining = age
                    var segment = 0
                    while (remaining > 0 && segment < 512) {
                        val segmentSeed = visualEffectRandom(seed xor (segment * 0x27d4eb2d))
                        val segmentLength = minOf(remaining, 10 + segmentSeed % 20)
                        val speed = 0.5f + (segmentSeed % 1_024) / 256f
                        val direction = if ((segmentSeed and 0x1000) == 0) 1f else -1f
                        linePosition += direction * speed * segmentLength
                        val period = (xSpan * 2).coerceAtLeast(1)
                        val folded = Math.floorMod(linePosition.roundToInt(), period)
                        linePosition = if (folded <= xSpan) folded.toFloat()
                            else (period - folded).toFloat()
                        remaining -= segmentLength
                        segment++
                    }
                    val drawSeed = visualEffectRandom(seed xor frame xor 0x5f356495)
                    if (drawSeed % 100 >= printRandom) return@repeat
                    val alphaRange = (highTransparency - lowTransparency + 1).coerceAtLeast(1)
                    val transparency = lowTransparency + drawSeed / 100 % alphaRange
                    drawRect(
                        color = Color(red, green, blue, transparency / 100f),
                        topLeft = Offset(x + linePosition * sx, y),
                        size = Size(lineWidth * sx, h)
                    )
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
    onExportDiagnostics: () -> Unit,
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
            QuickMenuButton(label = "LOG", selected = false, onClick = onExportDiagnostics)
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

    // FLASH (17) inherits the source-picture blit, then fills the entire screen
    // during each on-pulse; after countMax native changes the command to FILL.
    flashState?.takeIf { it.drawsFill }?.let { state ->
        Box(
            Modifier.fillMaxSize().background(
                Color(
                    state.color.red / 255f,
                    state.color.green / 255f,
                    state.color.blue / 255f,
                    1f
                )
            )
        )
    }

    // HAIKEIYURE (100), native layer-local mode only. The original layer picture
    // is suppressed, shifted across the screen, and exposed strips are restored
    // from the source picture or filled with the configured RGB color.
    if (isHaikeiYure && !sprite.bitmap.isRecycled) {
        Canvas(Modifier.fillMaxSize()) {
            val targetSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
            val dx = haikeiOffset.x * size.width / safeReferenceWidth
            val dy = haikeiOffset.y * size.height / safeReferenceHeight
            val dxInt = dx.roundToInt()
            val dyInt = dy.roundToInt()
            val image = sprite.bitmap.asImageBitmap()
            val fillType = parameters.getOrNull(7) ?: 0
            val fillColor = Color(
                (parameters.getOrNull(8) ?: 0).coerceIn(0, 255) / 255f,
                (parameters.getOrNull(9) ?: 0).coerceIn(0, 255) / 255f,
                (parameters.getOrNull(10) ?: 0).coerceIn(0, 255) / 255f,
                1f
            )

            fun fillGap(left: Float, top: Float, right: Float, bottom: Float) {
                val clippedLeft = left.coerceIn(0f, size.width)
                val clippedTop = top.coerceIn(0f, size.height)
                val clippedRight = right.coerceIn(0f, size.width)
                val clippedBottom = bottom.coerceIn(0f, size.height)
                if (clippedRight <= clippedLeft || clippedBottom <= clippedTop) return
                if (fillType == 0) {
                    clipRect(clippedLeft, clippedTop, clippedRight, clippedBottom) {
                        drawImage(image, dstSize = targetSize)
                    }
                } else {
                    drawRect(
                        color = fillColor,
                        topLeft = Offset(clippedLeft, clippedTop),
                        size = Size(clippedRight - clippedLeft, clippedBottom - clippedTop)
                    )
                }
            }

            drawImage(image = image, dstOffset = IntOffset(dxInt, dyInt), dstSize = targetSize)
            if (dyInt > 0) fillGap(0f, 0f, size.width, dy)
            if (dyInt < 0) fillGap(0f, size.height + dy, size.width, size.height)

            val sideTop = if (dyInt > 0) dy else 0f
            val sideBottom = if (dyInt < 0) size.height + dy else size.height
            if (dxInt > 0) fillGap(0f, sideTop, dx, sideBottom)
            if (dxInt < 0) fillGap(size.width + dx, sideTop, size.width, sideBottom)
        }
    }

    // The observed CLOUD vector has para[0] == 0 and no DWQ mask. This is a
    // static software perspective pass; cache the warped bitmap instead of
    // recycling or replacing pixels while Compose may still be drawing them.
    cloudBitmap?.let { warped ->
        Canvas(Modifier.fillMaxSize()) {
            val outputHeight = (
                size.height * warped.height.toFloat() / safeReferenceHeight
            ).roundToInt().coerceAtLeast(1)
            drawImage(
                image = warped.asImageBitmap(),
                dstSize = IntSize(size.width.roundToInt(), outputHeight)
            )
        }
    }
}

private const val TYPEWRITER_DELAY_MILLIS = 24L
private const val AUTO_ADVANCE_DELAY_MILLIS = 1_350L
private const val SKIP_ADVANCE_DELAY_MILLIS = 160L
