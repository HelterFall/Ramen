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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.draw.drawWithContent
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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
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
    onAutomaticAdvance: () -> Unit,
    onAutoMessageTimerElapsed: () -> Unit,
    onAutoModeChanged: (Boolean) -> Unit,
    onDrawFrameCompleted: () -> Unit,
    onScreenCaptureReady: (Long, Bitmap?) -> Unit,
    onOverrapTransitionFinished: (Long) -> Unit,
    onChoiceSelected: (String) -> Unit,
    onTypewriterProgress: (Int) -> Unit,
    onTypewriterFinished: () -> Unit,
    onBitmapDisposed: (Bitmap) -> Unit,
    onExportDiagnostics: () -> Unit,
    onExportTrace: () -> Unit,
    onQuickAction: (SystemNnnPlayerQuickAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val autoMode = state.nativeRuntime.dontPlayMode != 0
    var skipMode by remember { mutableStateOf(false) }
    var debugOverlayVisible by remember { mutableStateOf(true) }
    var diagnosticTail by remember { mutableStateOf(emptyList<String>()) }
    var canvasSizePx by remember { mutableStateOf(IntSize.Zero) }
    var viewportSizePx by remember { mutableStateOf(IntSize.Zero) }
    val viewportCaptureLayer = rememberGraphicsLayer()
    var screenEffectSourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val screenEffectKey = listOf(
        state.screenEffectId, state.screenEffectParameters, state.screenEffectCommandOffset,
        state.drawEventId, state.frameId
    )
    var screenEffectFrame by remember(screenEffectKey) { mutableIntStateOf(0) }
    var overrapProgress by remember(state.overrapTransitionId) { mutableFloatStateOf(0f) }

    // The recorder writes on its own thread. Poll the immutable tail slowly so
    // per-animation-frame trace events cannot trigger a recomposition storm.
    LaunchedEffect(state.nativeRuntime.cannotSkip, state.nativeRuntime.autoMessage, autoMode) {
        if (state.nativeRuntime.cannotSkip > 0 || state.nativeRuntime.autoMessage > 0 || autoMode) {
            skipMode = false
        }
    }

    LaunchedEffect(debugOverlayVisible) {
        if (!debugOverlayVisible) return@LaunchedEffect
        while (true) {
            diagnosticTail = SystemNnnDiagnosticLog.active?.recentEntries.orEmpty()
            delay(DEBUG_OVERLAY_REFRESH_MILLIS)
        }
    }

    LaunchedEffect(state.errorMessage, state.audioWarning, state.statusMessage) {
        state.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
            SystemNnnDiagnosticLog.active?.recordTrace(
                "visible_runtime_error",
                mapOf("step" to state.sceneStepNumber, "commandOffset" to state.lastCommandOffset, "message" to message),
                summary = message
            )
        }
        state.audioWarning?.takeIf { it.isNotBlank() }?.let { message ->
            SystemNnnDiagnosticLog.active?.recordTrace(
                "visible_audio_warning",
                mapOf("step" to state.sceneStepNumber, "commandOffset" to state.lastCommandOffset, "message" to message),
                summary = message
            )
        }
        state.statusMessage?.takeIf { it.isNotBlank() }?.let { message ->
            SystemNnnDiagnosticLog.active?.recordTrace(
                "visible_status_warning",
                mapOf("step" to state.sceneStepNumber, "commandOffset" to state.lastCommandOffset, "message" to message),
                summary = message
            )
        }
    }

    LaunchedEffect(state.screenCaptureRequestId) {
        val requestId = state.screenCaptureRequestId
        if (requestId == 0L) return@LaunchedEffect
        withFrameNanos { }
        val captured = runCatching {
            viewportCaptureLayer.toImageBitmap().asAndroidBitmap()
                .copy(Bitmap.Config.ARGB_8888, true)
        }.onFailure { failure ->
            SystemNnnDiagnosticLog.active?.recordException(
                "viewport_capture_failure",
                failure,
                "purpose=${state.screenCapturePurpose} requestId=$requestId"
            )
        }.getOrNull()
        if (captured == null) {
            SystemNnnDiagnosticLog.active?.recordTrace(
                "viewport_capture_missing",
                mapOf("requestId" to requestId, "purpose" to state.screenCapturePurpose?.name),
                summary = "Compose did not produce a viewport capture"
            )
        }
        onScreenCaptureReady(requestId, captured)
    }

    LaunchedEffect(screenEffectKey) {
        val effectId = state.screenEffectId
        val parameters = state.screenEffectParameters
        val needsCapturedScreen = SystemNnnVisualEffectMath.requiresNativeScreenBufferCapture(
            effectId, parameters
        )
        if (!needsCapturedScreen) {
            screenEffectSourceBitmap = null
            return@LaunchedEffect
        }
        screenEffectSourceBitmap = null

        var captured: Bitmap? = null
        var lastFailure: Throwable? = null
        repeat(3) {
            if (captured == null) {
                withFrameNanos { }
                val attempt = runCatching {
                    val viewport = viewportCaptureLayer.toImageBitmap().asAndroidBitmap()
                    if (viewport.width > 0 && viewport.height > 0) {
                        viewport.copy(Bitmap.Config.ARGB_8888, true)
                    } else {
                        null
                    }
                }
                if (attempt.isFailure) lastFailure = attempt.exceptionOrNull()
                captured = attempt.getOrNull()
            }
        }
        lastFailure?.let { failure ->
            SystemNnnDiagnosticLog.active?.recordException(
                "screen_effect_capture_failure",
                failure,
                "effectId=$effectId frame=$screenEffectFrame"
            )
        }
        screenEffectSourceBitmap = captured
        if (captured == null) {
            SystemNnnDiagnosticLog.active?.recordTrace(
                "screen_effect_capture_missing",
                mapOf("effectId" to effectId, "frame" to screenEffectFrame, "attempts" to 3)
            )
        }
    }

    LaunchedEffect(
        state.sceneStepNumber,
        state.frameId,
        state.drawEventId,
        state.visibleCharacterCount,
        state.dialogueText,
        state.screenEffectId,
        state.screenEffectParameters,
        screenEffectFrame,
        overrapProgress,
        canvasSizePx,
        viewportSizePx
    ) {
        val logicalWidth = state.logicalWidth.coerceAtLeast(1)
        val logicalHeight = state.logicalHeight.coerceAtLeast(1)
        val renderedSprites = (state.bufferedSprites + state.sprites)
            .associateBy { it.layerIndex }.values.sortedBy { it.layerIndex }
        val viewportWidth = viewportSizePx.width
        val viewportHeight = viewportSizePx.height
        // The game configuration defines the viewport; auxiliary layers may be smaller.
        val rendererReferenceWidth = logicalWidth
        val rendererReferenceHeight = logicalHeight
        val scaleX = if (viewportWidth > 0) viewportWidth.toFloat() / rendererReferenceWidth else null
        val scaleY = if (viewportHeight > 0) viewportHeight.toFloat() / rendererReferenceHeight else null
        val offsetX = if (canvasSizePx.width > 0 && viewportWidth > 0) {
            (canvasSizePx.width - viewportWidth) / 2f
        } else null
        val offsetY = if (canvasSizePx.height > 0 && viewportHeight > 0) {
            (canvasSizePx.height - viewportHeight) / 2f
        } else null
        val shake = when (state.screenEffectId) {
            18 -> SystemNnnVisualEffectMath.shakeOffset(
                frame = screenEffectFrame,
                frameCount = (state.screenEffectParameters.firstOrNull() ?: 20).coerceAtLeast(1),
                vertical = (state.screenEffectParameters.getOrNull(1) ?: 0) != 0
            )
            100 -> if ((state.screenEffectParameters.getOrNull(5) ?: 0) == 1) {
                // Native CEffectHaikeiYure::Print calls MoveScreen for para[5]==1.
                // This is trace metadata; screen-buffer pixels are shifted by the renderer.
                SystemNnnVisualEffectMath.haikeiYureOffset(screenEffectFrame, state.screenEffectParameters)
            } else {
                SystemNnnVisualOffset(0f, 0f)
            }
            else -> SystemNnnVisualOffset(0f, 0f)
        }
        val shakePxX = if (scaleX != null) shake.x * scaleX else 0f
        val shakePxY = if (scaleY != null) shake.y * scaleY else 0f
        val matrix = if (scaleX != null && scaleY != null && offsetX != null && offsetY != null) {
            listOf(
                scaleX, 0f, offsetX + shakePxX,
                0f, scaleY, offsetY + shakePxY,
                0f, 0f, 1f
            )
        } else null
        val bitmapLayerStates = renderedSprites.map { sprite ->
            val effectId = sprite.nativeEffectId
            val usesFullViewport = effectId in setOf(27, 39, 56, 63, 102)
            val usesCenteredBitmap = effectId == 64 || effectId == 65
            val defaultWidth = (rendererReferenceWidth * sprite.widthFraction.coerceIn(0.1f, 1f))
                .roundToInt().coerceAtLeast(1)
            val defaultHeight = (rendererReferenceHeight * sprite.heightFraction.coerceIn(0.1f, 1f))
                .roundToInt().coerceAtLeast(1)
            val dst = when {
                usesFullViewport -> SystemNnnNativeRect(0, 0, rendererReferenceWidth, rendererReferenceHeight)
                usesCenteredBitmap -> SystemNnnNativeRect(
                    (rendererReferenceWidth - sprite.bitmap.width) / 2,
                    (rendererReferenceHeight - sprite.bitmap.height) / 2,
                    sprite.bitmap.width,
                    sprite.bitmap.height
                )
                sprite.nativeRect != null -> sprite.nativeRect
                else -> SystemNnnNativeRect(
                    (rendererReferenceWidth * sprite.centerXFraction - defaultWidth / 2f).roundToInt(),
                    rendererReferenceHeight - defaultHeight,
                    defaultWidth,
                    defaultHeight
                )
            }
            linkedMapOf<String, Any?>(
                "layerIndex" to sprite.layerIndex,
                "resourceName" to sprite.resourceId,
                "sourceSize" to mapOf("width" to sprite.bitmap.width, "height" to sprite.bitmap.height),
                "declaredSize" to sprite.sourceMetadata?.let {
                    mapOf("width" to it.widthPixels, "height" to it.heightPixels)
                },
                "dstRect" to mapOf("x" to dst.x, "y" to dst.y, "w" to dst.width, "h" to dst.height),
                "dstRectEstimated" to (sprite.nativeRect == null),
                "positioningMode" to when {
                    usesFullViewport -> "full_viewport"
                    usesCenteredBitmap -> "centered_bitmap"
                    sprite.nativeRect != null -> "native_rect"
                    else -> "bottom_center_default"
                },
                "dstRectFrom" to sprite.nativeDestinationRectFrom?.let {
                    mapOf("x" to it.x, "y" to it.y, "w" to it.width, "h" to it.height)
                },
                "packType" to sprite.sourceMetadata?.packType,
                "blendMode" to "SRC_OVER",
                "hasAlpha" to sprite.bitmap.hasAlpha(),
                "alphaPremultiplied" to sprite.bitmap.isPremultiplied,
                "effect" to sprite.nativeEffectId?.let { effectId ->
                    mapOf(
                        "effect_id" to effectId,
                        "name" to SystemNnnVisualEffectMath.observedEffectName(effectId),
                        "parameters_raw" to sprite.nativeEffectParameters,
                        "para_0_to_15" to (0 until 16).map { sprite.nativeEffectParameters.getOrNull(it) },
                        "currentFrame" to null
                    )
                }
            )
        }
        val effectOnlyLayerStates = state.layerEffects
            .filter { effect ->
                SystemNnnVisualEffectMath.hasStandaloneLayerPainter(effect.effectId) &&
                    renderedSprites.none { it.layerIndex == effect.layerIndex }
            }
            .map { effect ->
                val rect = when (effect.effectId) {
                    29 -> SystemNnnNativeRect(
                        (effect.parameters.getOrNull(6) ?: 0).coerceIn(0, logicalWidth),
                        (effect.parameters.getOrNull(7) ?: 0).coerceIn(0, logicalHeight),
                        (effect.parameters.getOrNull(8) ?: logicalWidth).coerceAtLeast(0),
                        (effect.parameters.getOrNull(9) ?: logicalHeight).coerceAtLeast(0)
                    )
                    55 -> SystemNnnNativeRect(
                        (effect.parameters.getOrNull(1) ?: 0).coerceIn(0, logicalWidth),
                        (effect.parameters.getOrNull(2) ?: 0).coerceIn(0, logicalHeight),
                        (effect.parameters.getOrNull(3) ?: logicalWidth).coerceAtLeast(0),
                        (effect.parameters.getOrNull(4) ?: logicalHeight).coerceAtLeast(0)
                    )
                    else -> SystemNnnNativeRect(0, 0, logicalWidth, logicalHeight)
                }
                linkedMapOf<String, Any?>(
                    "layerIndex" to effect.layerIndex,
                    "resourceName" to null,
                    "sourceSize" to null,
                    "dstRect" to mapOf("x" to rect.x, "y" to rect.y, "w" to rect.width, "h" to rect.height),
                    "dstRectEstimated" to false,
                    "positioningMode" to "effect_only",
                    "blendMode" to "SRC_OVER",
                    "hasBitmap" to false,
                    "effect" to mapOf(
                        "effect_id" to effect.effectId,
                        "name" to SystemNnnVisualEffectMath.observedEffectName(effect.effectId),
                        "parameters_raw" to effect.parameters,
                        "para_0_to_15" to (0 until 16).map { effect.parameters.getOrNull(it) },
                        "currentFrame" to null
                    )
                )
            }
        val layerStates = (bitmapLayerStates + effectOnlyLayerStates)
            .sortedBy { it["layerIndex"] as Int }
        val effectLayersByIndex = LinkedHashMap<Int, SystemNnnActiveLayerEffect>()
        state.layerEffects.forEach { effect -> effectLayersByIndex[effect.layerIndex] = effect }
        state.sprites.forEach { sprite ->
            val effectId = sprite.nativeEffectId ?: return@forEach
            if (sprite.layerIndex !in effectLayersByIndex) {
                effectLayersByIndex[sprite.layerIndex] = SystemNnnActiveLayerEffect(
                    sprite.layerIndex, effectId, sprite.nativeEffectParameters, sprite.nativeEffectCommandOffset
                )
            }
        }
        val effects = effectLayersByIndex.values.sortedBy { it.layerIndex }.map { effect ->
            mapOf(
                "layer" to effect.layerIndex,
                "effect_id" to effect.effectId,
                "name" to SystemNnnVisualEffectMath.observedEffectName(effect.effectId),
                "parameters_raw" to effect.parameters,
                "para_0_to_15" to (0 until 16).map { effect.parameters.getOrNull(it) },
                "currentFrame" to null
            )
        }.toMutableList()
        state.screenEffectId?.let { effectId ->
            effects += mapOf(
                "layer" to 31,
                "effect_id" to effectId,
                "name" to SystemNnnVisualEffectMath.observedEffectName(effectId),
                "parameters_raw" to state.screenEffectParameters,
                "para_0_to_15" to (0 until 16).map { state.screenEffectParameters.getOrNull(it) },
                "currentFrame" to screenEffectFrame
            )
        }
        val composeAnomalies = ArrayList<Map<String, Any?>>()
        if (canvasSizePx.width <= 0 || canvasSizePx.height <= 0) {
            composeAnomalies += mapOf("type" to "invalid_canvas_size", "width" to canvasSizePx.width, "height" to canvasSizePx.height)
        }
        if (viewportWidth <= 0 || viewportHeight <= 0) {
            composeAnomalies += mapOf("type" to "invalid_viewport_size", "width" to viewportWidth, "height" to viewportHeight)
        }
        if (scaleX != null && scaleY != null && kotlin.math.abs(scaleX - scaleY) > 0.002f) {
            composeAnomalies += mapOf("type" to "non_uniform_viewport_scale", "scaleX" to scaleX, "scaleY" to scaleY)
        }
        if ((canvasSizePx.width > 0 && viewportWidth > canvasSizePx.width) ||
            (canvasSizePx.height > 0 && viewportHeight > canvasSizePx.height)
        ) {
            composeAnomalies += mapOf(
                "type" to "viewport_exceeds_canvas",
                "canvasWidth" to canvasSizePx.width,
                "canvasHeight" to canvasSizePx.height,
                "viewportWidth" to viewportWidth,
                "viewportHeight" to viewportHeight
            )
        }
        renderedSprites.forEach { sprite ->
            val dst = sprite.nativeRect
            if (dst != null && (
                dst.width <= 0 || dst.height <= 0 ||
                    SystemNnnVisualEffectMath.isNativeRectFullyOutsideViewport(
                        dst, rendererReferenceWidth, rendererReferenceHeight
                    )
                )
            ) {
                composeAnomalies += mapOf(
                    "type" to "compose_dst_rect_out_of_bounds",
                    "layerIndex" to sprite.layerIndex,
                    "resourceName" to sprite.resourceId,
                    "dstRect" to mapOf("x" to dst.x, "y" to dst.y, "w" to dst.width, "h" to dst.height)
                )
            }
        }
        SystemNnnDiagnosticLog.active?.recordTrace(
            "compose_frame",
            linkedMapOf(
                "step" to state.sceneStepNumber,
                "frameId" to state.frameId,
                "drawEventId" to state.drawEventId,
                "commandOffset" to state.lastCommandOffset,
                "dialogue" to mapOf(
                    "speaker" to state.speakerName,
                    "text" to state.dialogueText,
                    "visibleCharacters" to state.visibleCharacterCount
                ),
                "logicalResolution" to mapOf("width" to logicalWidth, "height" to logicalHeight),
                "rendererReferenceResolution" to mapOf(
                    "width" to rendererReferenceWidth,
                    "height" to rendererReferenceHeight
                ),
                "canvas" to mapOf("widthPx" to canvasSizePx.width, "heightPx" to canvasSizePx.height),
                "viewport" to mapOf("widthPx" to viewportWidth, "heightPx" to viewportHeight),
                "scale" to mapOf("x" to scaleX, "y" to scaleY),
                "viewportOffsetPx" to mapOf("x" to offsetX, "y" to offsetY),
                "shakeOffsetPx" to mapOf("x" to shakePxX, "y" to shakePxY),
                "scaleMatrix3x3" to matrix,
                "anomalies" to composeAnomalies,
                "layers" to layerStates,
                "activeEffects" to effects,
                "screenBuffer" to mapOf(
                    "screenEffectId" to state.screenEffectId,
                    "screenEffectFrame" to if (state.screenEffectId == null) null else screenEffectFrame,
                    "screenEffectParameters" to state.screenEffectParameters,
                    "savedGetBufferSize" to state.savedGetScreenBufferBitmap?.let {
                        mapOf("width" to it.width, "height" to it.height)
                    }
                ),
                "overrap" to mapOf(
                    "transitionId" to state.overrapTransitionId,
                    "type" to state.overrapTransitionType,
                    "durationFrames" to state.overrapTransitionDurationFrames,
                    "progress" to overrapProgress,
                    "fromSize" to state.overrapFromBitmap?.let { "${it.width}x${it.height}" },
                    "toSize" to state.overrapToBitmap?.let { "${it.width}x${it.height}" }
                )
            ),
            summary = "compose step=${state.sceneStepNumber} frame=${state.frameId} draw=${state.drawEventId} " +
                "canvas=${canvasSizePx.width}x${canvasSizePx.height} viewport=${viewportWidth}x${viewportHeight}"
        )
        composeAnomalies.forEach { anomaly ->
            SystemNnnDiagnosticLog.active?.recordTrace(
                "scene_anomaly",
                mapOf("step" to state.sceneStepNumber, "frameId" to state.frameId,
                    "commandOffset" to state.lastCommandOffset, "anomaly" to anomaly),
                summary = anomaly.toString()
            )
        }
    }

    LaunchedEffect(state.overrapTransitionId, state.overrapFromBitmap, state.overrapToBitmap) {
        val transitionId = state.overrapTransitionId
        // Zero is the sentinel for "no active OVERRAP". The initial UI state
        // intentionally has no snapshot bitmaps, so never run the transition
        // body or checkNotNull() until a real transition ID has been assigned.
        if (transitionId == 0L) return@LaunchedEffect

        val from = state.overrapFromBitmap
        val to = state.overrapToBitmap
        val skipReason = when {
            from == null -> "OVERRAP source bitmap is missing"
            to == null -> "OVERRAP destination bitmap is missing"
            from.isRecycled -> "OVERRAP source bitmap was recycled"
            to.isRecycled -> "OVERRAP destination bitmap was recycled"
            from.width != to.width || from.height != to.height ->
                "OVERRAP bitmap dimensions differ: ${from.width}x${from.height} vs ${to.width}x${to.height}"
            else -> null
        }
        if (skipReason != null) {
            SystemNnnDiagnosticLog.active?.recordTrace(
                "overrap_transition_fallback",
                mapOf(
                    "transitionId" to transitionId,
                    "transitionType" to state.overrapTransitionType,
                    "fromSize" to from?.let { "${it.width}x${it.height}" },
                    "toSize" to to?.let { "${it.width}x${it.height}" },
                    "fromRecycled" to from?.isRecycled,
                    "toRecycled" to to?.isRecycled,
                    "reason" to skipReason
                ),
                summary = skipReason
            )
            return@LaunchedEffect
        }
        val validFrom = checkNotNull(from)
        val validTo = checkNotNull(to)
        val duration = state.overrapTransitionDurationFrames.coerceIn(1, 9_999)
        for (frame in 0..duration) {
            if (validFrom.isRecycled || validTo.isRecycled) {
                SystemNnnDiagnosticLog.active?.recordTrace(
                    "overrap_transition_interrupted",
                    mapOf("transitionId" to transitionId, "frame" to frame, "reason" to "Bitmap recycled during transition")
                )
                return@LaunchedEffect
            }
            overrapProgress = frame.toFloat() / duration.toFloat()
            if (frame < duration) delay(state.nativeRuntime.frameTimeMillis.coerceAtLeast(1).toLong())
        }
        onOverrapTransitionFinished(transitionId)
    }

    LaunchedEffect(screenEffectKey) {
        val effectId = state.screenEffectId ?: return@LaunchedEffect
        val parameters = state.screenEffectParameters
        val duration = if (effectId in setOf(55, 71)) 9_999
            else (parameters.firstOrNull() ?: 20).coerceIn(1, 9_999)
        val repeats = effectId in setOf(55, 71) ||
            (effectId == 18 && (parameters.getOrNull(2) ?: 0) != 0)
        if (repeats) {
            while (true) {
                delay(state.nativeRuntime.frameTimeMillis.coerceAtLeast(1).toLong())
                screenEffectFrame = (screenEffectFrame + 1) % duration
            }
        } else {
            for (frame in 0..duration) {
                screenEffectFrame = frame
                if (frame < duration) delay(state.nativeRuntime.frameTimeMillis.coerceAtLeast(1).toLong())
            }
        }
    }

    LaunchedEffect(
        state.drawEventId,
        state.pendingDrawFrameCount,
        state.nativeRuntime.frameTimeMillis,
        skipMode,
        state.nativeRuntime.cannotSkip
    ) {
        val frames = state.pendingDrawFrameCount ?: return@LaunchedEffect
        if (skipMode && state.nativeRuntime.cannotSkip <= 0) {
            onDrawFrameCompleted()
            return@LaunchedEffect
        }
        delay(frames.coerceIn(1, 9_999) * state.nativeRuntime.frameTimeMillis.coerceAtLeast(1).toLong())
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
        autoMode, skipMode, state.nativeRuntime.autoMessage, state.frameId, state.drawEventId,
        state.isTextComplete, state.choices, state.isEnded, state.isLoading, state.pendingDrawFrameCount
    ) {
        if ((!autoMode && !skipMode) || state.nativeRuntime.autoMessage > 0 ||
            state.pendingDrawFrameCount != null ||
            !state.isTextComplete ||
            state.choices.isNotEmpty() ||
            state.isEnded ||
            state.isLoading
        ) {
            return@LaunchedEffect
        }

        delay(if (skipMode) SKIP_ADVANCE_DELAY_MILLIS else AUTO_ADVANCE_DELAY_MILLIS)
        onAutomaticAdvance()
    }

    // AUTOMESSAGE's native default autoMessageLastWait is 2000 ms.
    LaunchedEffect(
        state.nativeRuntime.autoMessage, state.frameId, state.drawEventId,
        state.isTextComplete, state.choices, state.isEnded, state.isLoading, state.pendingDrawFrameCount
    ) {
        if (state.nativeRuntime.autoMessage <= 0 ||
            state.pendingDrawFrameCount != null ||
            !state.isTextComplete ||
            state.choices.isNotEmpty() ||
            state.isEnded ||
            state.isLoading
        ) {
            return@LaunchedEffect
        }

        delay(SCRIPT_AUTO_MESSAGE_WAIT_MILLIS)
        onAutoMessageTimerElapsed()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { canvasSizePx = it }
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = state.nativeRuntime.cannotClick <= 0 &&
                    state.nativeRuntime.autoMessage <= 0 &&
                    state.choices.isEmpty() && !state.isEnded &&
                    !state.isLoading && state.pendingDrawFrameCount == null,
                onClick = onScreenTap
            )
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val sceneAspectRatio = state.logicalWidth.toFloat() /
                state.logicalHeight.coerceAtLeast(1).toFloat()
            val availableAspectRatio = maxWidth.value / maxHeight.value
            val viewportModifier = if (availableAspectRatio > sceneAspectRatio) {
                Modifier.fillMaxHeight().aspectRatio(sceneAspectRatio)
            } else {
                Modifier.fillMaxWidth().aspectRatio(sceneAspectRatio)
            }

            BoxWithConstraints(
                modifier = viewportModifier.align(Alignment.Center).clipToBounds()
                    .onSizeChanged { viewportSizePx = it }
            ) {
                val viewportDpWidth = maxWidth
                val viewportDpHeight = maxHeight
                val referenceWidth = state.logicalWidth.coerceAtLeast(1).toFloat()
                val referenceHeight = state.logicalHeight.coerceAtLeast(1).toFloat()
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
                    modifier = Modifier.fillMaxSize()
                        .drawWithContent {
                            viewportCaptureLayer.record {
                                this@drawWithContent.drawContent()
                            }
                            drawContent()
                        }
                        .graphicsLayer {
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
                    // Live layers and previously blitted outputs share one native layer order.
                    val spritesByLayer = (state.bufferedSprites + state.sprites)
                        .associateBy { it.layerIndex }
                    val standaloneLayerEffects = state.layerEffects.filter { effect ->
                        SystemNnnVisualEffectMath.hasStandaloneLayerPainter(effect.effectId) &&
                            spritesByLayer[effect.layerIndex]?.nativeEffectId != effect.effectId
                    }
                    val renderLayers = SystemNnnVisualEffectMath.nativeLayerRenderOrder(
                        bitmapLayerIndices = spritesByLayer.keys,
                        effectLayers = standaloneLayerEffects.map { it.layerIndex to it.effectId }
                    )
                    renderLayers.forEach { layerIndex ->
                        val sprite = spritesByLayer[layerIndex]
                        if (sprite != null && layerIndex !in hiddenPartnerLayers) {
                            key("sprite-$layerIndex") {
                                SystemNnnAnimatedSpriteLayer(
                                    sprite = sprite,
                                    stepNumber = state.sceneStepNumber,
                                    changePartner = changePartners[layerIndex],
                                    viewportWidth = viewportDpWidth,
                                    viewportHeight = viewportDpHeight,
                                    referenceWidth = referenceWidth,
                                    referenceHeight = referenceHeight,
                                    frameTimeMillis = state.nativeRuntime.frameTimeMillis,
                                    onBitmapDisposed = onBitmapDisposed
                                )
                            }
                        }
                        standaloneLayerEffects.filter { it.layerIndex == layerIndex }.forEach { effect ->
                            key("layer-effect-${effect.layerIndex}-${effect.effectId}-${effect.commandOffset ?: 0L}") {
                                when (effect.effectId) {
                                    10, 11 -> SystemNnnStandaloneLayerFade(
                                        effect = effect,
                                        frameTimeMillis = state.nativeRuntime.frameTimeMillis
                                    )
                                    17 -> SystemNnnStandaloneLayerFlash(
                                        effect = effect,
                                        frameTimeMillis = state.nativeRuntime.frameTimeMillis
                                    )
                                    29 -> SystemNnnStandaloneLayerSuna(
                                        effect = effect,
                                        frameTimeMillis = state.nativeRuntime.frameTimeMillis,
                                        referenceWidth = referenceWidth,
                                        referenceHeight = referenceHeight
                                    )
                                    54 -> SystemNnnStandaloneLayerFill(effect = effect)
                                    55 -> SystemNnnStandaloneLayerOldFilm(
                                        effect = effect,
                                        frameTimeMillis = state.nativeRuntime.frameTimeMillis,
                                        referenceWidth = referenceWidth,
                                        referenceHeight = referenceHeight
                                    )
                                }
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
                        sourceBitmap = screenEffectSourceBitmap,
                        savedGetBufferBitmap = state.savedGetScreenBufferBitmap,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                if (SystemNnnVisualEffectMath.shouldCoverViewportDuringOverrapTargetCapture(
                        state.screenCaptureRequestId, state.screenCapturePurpose
                    )
                ) {
                    // Capture is read from viewportCaptureLayer above; this cover is display-only.
                    Box(Modifier.fillMaxSize().background(Color.Black))
                }
            }
        }

        val overrapFrameBitmap = remember(
            state.overrapTransitionId,
            state.overrapTransitionType,
            state.overrapTransitionDurationFrames,
            state.overrapFromBitmap,
            state.overrapToBitmap,
            overrapProgress
        ) {
            val from = state.overrapFromBitmap
            val to = state.overrapToBitmap
            if (from == null || to == null || from.isRecycled || to.isRecycled ||
                from.width != to.width || from.height != to.height
            ) {
                null
            } else {
                runCatching {
                    val fromPixels = IntArray(from.width * from.height)
                    val toPixels = IntArray(to.width * to.height)
                    from.getPixels(fromPixels, 0, from.width, 0, 0, from.width, from.height)
                    to.getPixels(toPixels, 0, to.width, 0, 0, to.width, to.height)
                    val duration = state.overrapTransitionDurationFrames.coerceAtLeast(1)
                    val frame = (overrapProgress.coerceIn(0f, 1f) * duration).roundToInt()
                    val mixed = SystemNnnNativeEffectRenderer.renderOverrapFrame(
                        start = fromPixels,
                        end = toPixels,
                        transitionType = state.overrapTransitionType,
                        frame = frame,
                        length = duration,
                        width = from.width,
                        height = from.height
                    ) ?: return@runCatching null
                    Bitmap.createBitmap(mixed, from.width, from.height, Bitmap.Config.ARGB_8888)
                }.onFailure { failure ->
                    SystemNnnDiagnosticLog.active?.recordException(
                        "overrap_frame_render_failure",
                        failure,
                        "transitionId=${state.overrapTransitionId} type=${state.overrapTransitionType}"
                    )
                }.getOrNull()
            }
        }
        DisposableEffect(overrapFrameBitmap) {
            onDispose {
                if (overrapFrameBitmap != null && !overrapFrameBitmap.isRecycled) {
                    overrapFrameBitmap.recycle()
                }
            }
        }
        DisposableEffect(state.overrapFromBitmap, state.overrapToBitmap) {
            val from = state.overrapFromBitmap
            val to = state.overrapToBitmap
            onDispose {
                if (from != null && !from.isRecycled) from.recycle()
                if (to != null && to !== from && !to.isRecycled) to.recycle()
            }
        }
        overrapFrameBitmap?.let { bitmap ->
            Canvas(Modifier.fillMaxSize()) {
                val viewport = SystemNnnVisualEffectMath.aspectFitViewportBounds(
                    canvasWidth = size.width.roundToInt(),
                    canvasHeight = size.height.roundToInt(),
                    sceneWidth = state.logicalWidth,
                    sceneHeight = state.logicalHeight
                )
                // Opaque replacement prevents the live dialogue/text from showing
                // through the captured OVERrap frame.
                drawRect(
                    color = Color.Black,
                    topLeft = Offset(viewport.x.toFloat(), viewport.y.toFloat()),
                    size = Size(viewport.width.toFloat(), viewport.height.toFloat())
                )
                if (viewport.width > 0 && viewport.height > 0) {
                    drawImage(
                        image = bitmap.asImageBitmap(),
                        dstOffset = IntOffset(viewport.x, viewport.y),
                        dstSize = IntSize(viewport.width, viewport.height)
                    )
                }
            }
        }

        QuickMenu(
            autoEnabled = autoMode,
            skipEnabled = skipMode,
            optionsEnabled = state.nativeRuntime.optionOff <= 0 && state.nativeRuntime.autoMessage <= 0,
            skipAllowed = state.nativeRuntime.cannotSkip <= 0 && state.nativeRuntime.autoMessage <= 0,
            onSave = { onQuickAction(SystemNnnPlayerQuickAction.SAVE) },
            onLoad = { onQuickAction(SystemNnnPlayerQuickAction.LOAD) },
            onAutoToggle = {
                val enableAuto = !autoMode
                onAutoModeChanged(enableAuto)
                if (enableAuto) skipMode = false
            },
            onSkipToggle = {
                skipMode = !skipMode
                if (skipMode) onAutoModeChanged(false)
            },
            onExportDiagnostics = onExportDiagnostics,
            onExportTrace = onExportTrace,
            debugVisible = debugOverlayVisible,
            onDebugToggle = { debugOverlayVisible = !debugOverlayVisible },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(14.dp)
        )

        if (debugOverlayVisible) {
            Surface(
                color = Color(0xEE101216),
                contentColor = Color(0xFFE8EDF4),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Color(0x885C718C)),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 132.dp, end = 14.dp)
                    .width(340.dp)
                    .height(220.dp)
            ) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = "SCENE TRACE · step ${state.sceneStepNumber} · " +
                            (state.lastCommandOffset?.let { "0x" + it.toString(16) } ?: "—"),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "frame=${state.frameId} draw=${state.drawEventId} " +
                            "layers=${state.sprites.size} effect=${state.screenEffectId ?: "none"}",
                        style = MaterialTheme.typography.labelSmall
                    )
                    diagnosticTail.takeLast(18).forEach { entry ->
                        Text(text = entry, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        if (!state.isEnded) {
            DialogueBox(
                speakerName = state.speakerName,
                dialogue = state.dialogueText.take(
                    state.visibleCharacterCount.coerceIn(0, state.dialogueText.length)
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 18.dp),
                windowVisible = state.nativeRuntime.messageWindowPrintFlag,
                showContinuePrompt = state.nativeRuntime.messageWindowPrintFlag &&
                    state.pendingDrawFrameCount == null &&
                    state.isTextComplete &&
                    state.choices.isEmpty() &&
                    state.nativeRuntime.autoMessage <= 0 &&
                    state.nativeRuntime.cannotClick <= 0 &&
                    !autoMode && !skipMode && !state.isLoading
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
                clicksEnabled = state.nativeRuntime.cannotClick <= 0 && state.nativeRuntime.autoMessage <= 0,
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
    modifier: Modifier = Modifier,
    windowVisible: Boolean = true,
    showContinuePrompt: Boolean = true
) {
    val windowModifier = if (windowVisible) {
        modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xE6101216))
    } else {
        modifier
    }
    Column(
        modifier = windowModifier.padding(horizontal = 20.dp, vertical = 16.dp),
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
        if (showContinuePrompt) {
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
}

/** Save/load UI actions are forwarded to the Activity; persistence is not part of this screen. */


/**
 * Draws one native DWQ layer. SETEFFECTRECT interpolates between two
 * destination RECTs; neither RECT is a source-texture crop.
 */
@Composable
private fun BoxScope.SystemNnnAnimatedSpriteLayer(
    sprite: SystemNnnSpriteBitmap,
    stepNumber: Long,
    changePartner: SystemNnnSpriteBitmap?,
    viewportWidth: androidx.compose.ui.unit.Dp,
    viewportHeight: androidx.compose.ui.unit.Dp,
    referenceWidth: Float,
    referenceHeight: Float,
    frameTimeMillis: Int,
    onBitmapDisposed: (Bitmap) -> Unit
) {
    DisposableEffect(sprite.bitmap) { onDispose { onBitmapDisposed(sprite.bitmap) } }
    if (changePartner != null && changePartner.bitmap !== sprite.bitmap) {
        DisposableEffect(changePartner.bitmap) { onDispose { onBitmapDisposed(changePartner.bitmap) } }
    }

    val effectId = sprite.nativeEffectId
    val parameters = sprite.nativeEffectParameters
    val isKe = effectId == 27
    val nativeRect = sprite.nativeRect
    val fromRect = sprite.nativeDestinationRectFrom
    // Native blit receives RECT right/bottom as dimensions. Preserve invalid
    // extents in decoded state but do not submit a non-positive destination to Compose.
    if (nativeRect != null && (nativeRect.width <= 0 || nativeRect.height <= 0)) return
    val nativeFrameMillis = frameTimeMillis.coerceAtLeast(1)
    val hasRectMotion = fromRect != null && nativeRect != null && effectId != null
    val defaultDurationFrames = if (effectId == 62 || effectId == 119) 1 else 20
    val durationFrames = SystemNnnVisualEffectMath.nativeEffectDurationFrames(
        effectId, parameters, defaultDurationFrames
    )
    val isHaikeiYure = effectId == 100 && (parameters.getOrNull(5) ?: 0) == 0
    val animationKey = listOf(sprite.resourceId, sprite.layerIndex, sprite.nativeEffectCommandOffset,
        fromRect, nativeRect, effectId, parameters)
    val progress = remember(animationKey, nativeFrameMillis) {
        Animatable(if (hasRectMotion || isHaikeiYure ||
            effectId in setOf(9, 17, 29, 39, 40, 52, 62, 64, 65, 99, 100, 119)
        ) 0f else 1f)
    }
    LaunchedEffect(animationKey, nativeFrameMillis) {
        if (hasRectMotion || isHaikeiYure ||
            effectId in setOf(9, 17, 29, 39, 40, 52, 62, 64, 65, 99, 119)
        ) {
            val durationMillis = (durationFrames.toLong() * nativeFrameMillis).coerceIn(
                nativeFrameMillis.toLong(), 600_000L
            ).toInt()
            progress.animateTo(1f, tween(durationMillis, easing = LinearEasing))
        }
    }
    val cycleFrames = when (effectId) {
        39 -> (parameters.firstOrNull() ?: 20).coerceIn(1, 9_999)
        56 -> (parameters.firstOrNull()?.takeIf { it > 0 } ?: 9_999).coerceIn(1, 9_999)
        63 -> (parameters.getOrNull(14)?.takeIf { it > 0 } ?: durationFrames).coerceIn(1, 9_999)
        102 -> ((parameters.getOrNull(2) ?: 500) - (parameters.getOrNull(3) ?: 20)).coerceAtLeast(1)
        132 -> (parameters.getOrNull(3) ?: 8).coerceIn(1, 60)
        else -> 1
    }
    val cycle = remember(animationKey) { Animatable(0f) }
    LaunchedEffect(animationKey, nativeFrameMillis) {
        if ((effectId == 39 && (parameters.getOrNull(3) ?: 0) != 0) ||
            effectId in setOf(56, 63, 102, 132)
        ) {
            val cycleMillis = (cycleFrames.toLong() * nativeFrameMillis).coerceIn(
                nativeFrameMillis.toLong(), 600_000L
            ).toInt()
            while (true) {
                cycle.animateTo(1f, tween(cycleMillis, easing = LinearEasing))
                cycle.snapTo(0f)
            }
        } else cycle.snapTo(1f)
    }

    var keFrame by remember(animationKey) { mutableIntStateOf(0) }
    LaunchedEffect(animationKey, nativeFrameMillis, isKe) {
        if (isKe) {
            while (true) {
                delay(nativeFrameMillis.toLong())
                // CEffectKe::Calcu resets its counter after it exceeds 10,000.
                keFrame = if (keFrame >= 10_000) 0 else keFrame + 1
            }
        }
    }
    val t = progress.value.coerceIn(0f, 1f)
    val frame = if (isKe) keFrame else
        (t * durationFrames).roundToInt().coerceIn(0, durationFrames)
    val keParticleStamps = if (isKe) {
        SystemNnnNativeEffectRenderer.keParticles(
            bitmapWidth = sprite.bitmap.width,
            bitmapHeight = sprite.bitmap.height,
            frame = frame,
            parameters = parameters,
            screenWidth = referenceWidth.roundToInt().coerceAtLeast(1),
            screenHeight = referenceHeight.roundToInt().coerceAtLeast(1)
        )
    } else emptyList()
    val cycleFrame = (cycle.value * cycleFrames).roundToInt().coerceIn(0, cycleFrames)
    val movedRect = if (fromRect != null && nativeRect != null) {
        SystemNnnVisualEffectMath.moveRect(frame, durationFrames, fromRect, nativeRect)
    } else nativeRect
    val rectX = movedRect?.x ?: 0
    val rectY = movedRect?.y ?: 0
    val rectWidth = movedRect?.width ?: sprite.bitmap.width
    val rectHeight = movedRect?.height ?: sprite.bitmap.height

    val alpha = SystemNnnVisualEffectMath.layerOpacity(effectId, frame, parameters)
    val isTurn = effectId == 64
    val isKomaAnime = effectId == 63
    val isSuna = effectId == 29
    val isRain2 = effectId == 56
    val isRain4 = effectId == 102
    val isFlash = effectId == 17
    val isCloud = effectId == 82 &&
        (parameters.getOrNull(0) ?: 9_999) == 0 &&
        (parameters.getOrNull(8) ?: 0) == 0
    val haikeiOffset = if (isHaikeiYure) {
        SystemNnnVisualEffectMath.haikeiYureOffset(frame, parameters)
    } else SystemNnnVisualOffset(0f, 0f)
    val flashState = if (isFlash) {
        SystemNnnVisualEffectMath.flashState(frame, parameters)
    } else null
    val sunaFrameBitmap = remember(sprite.bitmap, parameters, frame, isSuna) {
        if (isSuna && !sprite.bitmap.isRecycled) {
            runCatching {
                val sourcePixels = IntArray(sprite.bitmap.width * sprite.bitmap.height)
                sprite.bitmap.getPixels(
                    sourcePixels, 0, sprite.bitmap.width,
                    0, 0, sprite.bitmap.width, sprite.bitmap.height
                )
                val output = SystemNnnNativeEffectRenderer.sunaNoise(
                    source = sourcePixels,
                    width = sprite.bitmap.width,
                    height = sprite.bitmap.height,
                    frame = frame,
                    duration = durationFrames,
                    parameters = parameters
                )
                Bitmap.createBitmap(
                    output, sprite.bitmap.width, sprite.bitmap.height, Bitmap.Config.ARGB_8888
                )
            }.onFailure { failure ->
                SystemNnnDiagnosticLog.active?.recordException(
                    "suna_layer_render_failure", failure,
                    "layer=${sprite.layerIndex} resource=${sprite.resourceId} params=$parameters"
                )
            }.getOrNull()
        } else null
    }
    DisposableEffect(sunaFrameBitmap) {
        onDispose {
            if (sunaFrameBitmap != null && !sunaFrameBitmap.isRecycled) sunaFrameBitmap.recycle()
        }
    }

    var cloudFrame by remember(animationKey) { mutableIntStateOf(0) }
    LaunchedEffect(animationKey, nativeFrameMillis, isCloud) {
        if (isCloud) {
            while (true) {
                delay(nativeFrameMillis.toLong())
                cloudFrame = if (cloudFrame == Int.MAX_VALUE) 0 else cloudFrame + 1
            }
        }
    }

    val cloudBitmap = remember(
        sprite.bitmap, parameters, referenceWidth, referenceHeight, isCloud, cloudFrame
    ) {
        if (isCloud && !sprite.bitmap.isRecycled) {
            runCatching {
                val sourcePixels = IntArray(sprite.bitmap.width * sprite.bitmap.height)
                sprite.bitmap.getPixels(
                    sourcePixels, 0, sprite.bitmap.width,
                    0, 0, sprite.bitmap.width, sprite.bitmap.height
                )
                val warped = SystemNnnVisualEffectMath.cloudWarpPixels(
                    frame = cloudFrame.toLong(),
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
            }.onFailure { failure ->
                SystemNnnDiagnosticLog.active?.recordException(
                    "cloud_layer_render_failure",
                    failure,
                    "layer=${sprite.layerIndex} resource=${sprite.resourceId} params=$parameters"
                )
            }.getOrNull()
        } else null
    }
    DisposableEffect(cloudBitmap) {
        onDispose {
            if (cloudBitmap != null && !cloudBitmap.isRecycled) cloudBitmap.recycle()
        }
    }
    LaunchedEffect(isCloud, cloudBitmap) {
        if (isCloud && cloudBitmap == null) {
            SystemNnnDiagnosticLog.active?.recordTrace(
                "scene_fallback",
                mapOf(
                    "kind" to "cloud_layer_bitmap_fallback",
                    "layerIndex" to sprite.layerIndex,
                    "resourceName" to sprite.resourceId,
                    "parameters" to parameters
                ),
                summary = "CLOUD layer did not produce a warped bitmap"
            )
        }
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

    LaunchedEffect(
        animationKey,
        frame,
        cycleFrame,
        layerAlpha,
        movedRect,
        stretchAlpha
    ) {
        if (effectId != null) {
            SystemNnnDiagnosticLog.active?.recordTrace(
                "layer_animation_frame",
                mapOf(
                    "step" to stepNumber,
                    "layerIndex" to sprite.layerIndex,
                    "resourceName" to sprite.resourceId,
                    "effect_id" to effectId,
                    "effectName" to SystemNnnVisualEffectMath.observedEffectName(effectId),
                    "parameters_raw" to parameters,
                    "para_0_to_15" to (0 until 16).map { parameters.getOrNull(it) },
                    "currentFrame" to frame,
                    "cycleFrame" to cycleFrame,
                    "durationFrames" to durationFrames,
                    "dstRect" to movedRect?.let { mapOf("x" to it.x, "y" to it.y, "w" to it.width, "h" to it.height) },
                    "srcRect" to fromRect?.let { mapOf("x" to it.x, "y" to it.y, "w" to it.width, "h" to it.height) },
                    "particleCount" to if (isKe) (parameters.getOrNull(3) ?: 1) else null,
                    "visibleParticleCount" to if (isKe) keParticleStamps.size else null,
                    "particleSourceRects" to if (isKe) keParticleStamps.map { particle ->
                        mapOf(
                            "x" to particle.sourceRect.left,
                            "y" to particle.sourceRect.top,
                            "w" to particle.sourceRect.width,
                            "h" to particle.sourceRect.height
                        )
                    } else null,
                    "particleCenters" to if (isKe) keParticleStamps.map { particle ->
                        mapOf("x" to particle.x, "y" to particle.y)
                    } else null,
                    "alpha" to layerAlpha,
                    "stretchAlpha" to stretchAlpha,
                    "blendMode" to "SRC_OVER",
                    "bitmapHasAlpha" to sprite.bitmap.hasAlpha(),
                    "alphaPremultiplied" to sprite.bitmap.isPremultiplied
                ),
                summary = "layer=${sprite.layerIndex} effect=$effectId frame=$frame cycle=$cycleFrame alpha=$layerAlpha"
            )
        }
    }

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
        isKe || isKomaAnime || isSuna || isRain2 || isRain4 -> Modifier.fillMaxSize()
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
        // Keep per-pixel DWQ alpha masks isolated before source-over composition.
        this.compositingStrategy = if (sprite.bitmap.hasAlpha() || layerAlpha < 1f) {
            CompositingStrategy.Offscreen
        } else {
            CompositingStrategy.Auto
        }
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
            isKe -> {
                val particles = keParticleStamps
                Canvas(Modifier.fillMaxSize()) {
                    val image = sprite.bitmap.asImageBitmap()
                    particles.forEach { particle ->
                        val sourceRect = particle.sourceRect
                        val dstWidth = (sourceRect.width * size.width / safeReferenceWidth * particle.scale).roundToInt().coerceAtLeast(1)
                        val dstHeight = (sourceRect.height * size.height / safeReferenceHeight * particle.scale).roundToInt().coerceAtLeast(1)
                        val centerX = particle.x * size.width / safeReferenceWidth
                        val centerY = particle.y * size.height / safeReferenceHeight
                        drawImage(
                            image = image,
                            srcOffset = IntOffset(sourceRect.left, sourceRect.top),
                            srcSize = IntSize(sourceRect.width, sourceRect.height),
                            dstOffset = IntOffset((centerX - dstWidth / 2f).roundToInt(), (centerY - dstHeight / 2f).roundToInt()),
                            dstSize = IntSize(dstWidth, dstHeight),
                            alpha = particle.alpha
                        )
                    }
                }
            }
            isKomaAnime -> {
                val sourceRect = SystemNnnNativeEffectRenderer.komaAnimeSourceRect(
                    sprite.bitmap.width, sprite.bitmap.height, cycleFrame, parameters
                )
                val frameDenominator = (durationFrames - 1).coerceAtLeast(1)
                val positionProgress = (frame.toFloat() / frameDenominator).coerceIn(0f, 1f)
                val x1 = parameters.getOrNull(6) ?: 0
                val y1 = parameters.getOrNull(7) ?: 0
                val x2 = parameters.getOrNull(4) ?: x1
                val y2 = parameters.getOrNull(5) ?: y1
                val putX = x1 + ((x2 - x1) * positionProgress)
                val putY = y1 + ((y2 - y1) * positionProgress)
                val transparencyMode = parameters.getOrNull(8) ?: 1
                val alphaStart = (parameters.getOrNull(11) ?: 100).coerceIn(0, 100)
                val alphaEnd = (parameters.getOrNull(10) ?: 100).coerceIn(0, 100)
                val partAlpha = if (transparencyMode == 2) {
                    (alphaStart + (alphaEnd - alphaStart) * positionProgress) / 100f
                } else {
                    1f
                }
                Canvas(Modifier.fillMaxSize()) {
                    if (sourceRect.width > 0 && sourceRect.height > 0) {
                        val dstWidth = (sourceRect.width * size.width / safeReferenceWidth).roundToInt().coerceAtLeast(1)
                        val dstHeight = (sourceRect.height * size.height / safeReferenceHeight).roundToInt().coerceAtLeast(1)
                        drawImage(
                            image = sprite.bitmap.asImageBitmap(),
                            srcOffset = IntOffset(sourceRect.left, sourceRect.top),
                            srcSize = IntSize(sourceRect.width, sourceRect.height),
                            dstOffset = IntOffset(
                                (putX * size.width / safeReferenceWidth).roundToInt(),
                                (putY * size.height / safeReferenceHeight).roundToInt()
                            ),
                            dstSize = IntSize(dstWidth, dstHeight),
                            alpha = partAlpha
                        )
                    }
                }
            }
            isSuna -> {
                Image(
                    bitmap = (sunaFrameBitmap ?: sprite.bitmap).asImageBitmap(),
                    contentDescription = sprite.contentDescription,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                    alignment = Alignment.TopStart
                )
            }
            isRain2 -> {
                val offsets = SystemNnnNativeEffectRenderer.rain2Offsets(
                    frame = cycleFrame, parameters = parameters,
                    screenWidth = safeReferenceWidth.roundToInt().coerceAtLeast(1),
                    screenHeight = safeReferenceHeight.roundToInt().coerceAtLeast(1),
                    bitmapWidth = sprite.bitmap.width, bitmapHeight = sprite.bitmap.height
                )
                Canvas(Modifier.fillMaxSize()) {
                    val image = sprite.bitmap.asImageBitmap()
                    val dstWidth = (sprite.bitmap.width * size.width / safeReferenceWidth).roundToInt().coerceAtLeast(1)
                    val dstHeight = (sprite.bitmap.height * size.height / safeReferenceHeight).roundToInt().coerceAtLeast(1)
                    offsets.forEach { offset ->
                        val x = (offset.x * size.width / safeReferenceWidth).roundToInt()
                        val y = (offset.y * size.height / safeReferenceHeight).roundToInt()
                        val firstColumn = if (offset.x > 0) -1 else 0
                        val firstRow = if (offset.y > 0) -1 else 0
                        val lastColumn = if (x + dstWidth < size.width) {
                            (size.width.toInt() - (x + dstWidth) + dstWidth - 1) / dstWidth
                        } else 0
                        val lastRow = if (y + dstHeight < size.height) {
                            (size.height.toInt() - (y + dstHeight) + dstHeight - 1) / dstHeight
                        } else 0
                        for (row in firstRow..lastRow) {
                            for (column in firstColumn..lastColumn) {
                                drawImage(
                                    image = image,
                                    dstOffset = IntOffset(x + column * dstWidth, y + row * dstHeight),
                                    dstSize = IntSize(dstWidth, dstHeight),
                                    alpha = offset.alpha
                                )
                            }
                        }
                    }
                }
            }
            isRain4 -> {
                val copies = SystemNnnNativeEffectRenderer.rain4Copies(
                    bitmapWidth = sprite.bitmap.width, bitmapHeight = sprite.bitmap.height,
                    frame = cycleFrame, parameters = parameters,
                    screenWidth = safeReferenceWidth.roundToInt().coerceAtLeast(1),
                    screenHeight = safeReferenceHeight.roundToInt().coerceAtLeast(1)
                )
                Canvas(Modifier.fillMaxSize()) {
                    val image = sprite.bitmap.asImageBitmap()
                    copies.forEach { copy ->
                        val dstWidth = (sprite.bitmap.width * copy.scale * size.width / safeReferenceWidth).roundToInt().coerceAtLeast(1)
                        val dstHeight = (sprite.bitmap.height * copy.scale * size.height / safeReferenceHeight).roundToInt().coerceAtLeast(1)
                        val centerX = copy.x * size.width / safeReferenceWidth
                        val centerY = copy.y * size.height / safeReferenceHeight
                        drawImage(
                            image = image,
                            dstOffset = IntOffset((centerX - dstWidth / 2f).roundToInt(), (centerY - dstHeight / 2f).roundToInt()),
                            dstSize = IntSize(dstWidth, dstHeight),
                            alpha = copy.alpha
                        )
                    }
                }
            }
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
            val opaqueImageFilter = additiveColorFilter(0, 0, 0, forceOpaque = true)
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
                        drawImage(image, dstSize = targetSize, colorFilter = opaqueImageFilter)
                    }
                } else {
                    drawRect(
                        color = fillColor,
                        topLeft = Offset(clippedLeft, clippedTop),
                        size = Size(clippedRight - clippedLeft, clippedBottom - clippedTop)
                    )
                }
            }

            drawImage(image = image, dstOffset = IntOffset(dxInt, dyInt), dstSize = targetSize, colorFilter = opaqueImageFilter)
            if (dyInt > 0) fillGap(0f, 0f, size.width, dy)
            if (dyInt < 0) fillGap(0f, size.height + dy, size.width, size.height)

            val sideTop = if (dyInt > 0) dy else 0f
            val sideBottom = if (dyInt < 0) size.height + dy else size.height
            if (dxInt > 0) fillGap(0f, sideTop, dx, sideBottom)
            if (dxInt < 0) fillGap(size.width + dx, sideTop, size.width, sideBottom)
        }
    }

    // The observed CLOUD vector has para[0] == 0 and no DWQ mask. Recompute
    // its perspective sample with native m_x/m_y advances on each effect frame.
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

@Composable
private fun BoxScope.SystemNnnStandaloneLayerFade(
    effect: SystemNnnActiveLayerEffect,
    frameTimeMillis: Int
) {
    val parameters = effect.parameters
    val duration = SystemNnnVisualEffectMath.nativeEffectDurationFrames(
        effect.effectId, parameters
    )
    val animationKey = listOf(effect.layerIndex, effect.effectId, effect.commandOffset, parameters)
    var frame by remember(animationKey) { mutableIntStateOf(0) }
    LaunchedEffect(animationKey, frameTimeMillis) {
        for (currentFrame in 0..duration) {
            frame = currentFrame
            if (currentFrame < duration) delay(frameTimeMillis.coerceAtLeast(1).toLong())
        }
    }
    val fade = checkNotNull(SystemNnnVisualEffectMath.screenFade(effect.effectId, frame, parameters))
    Box(
        Modifier.fillMaxSize().background(
            Color(
                fade.color.red / 255f,
                fade.color.green / 255f,
                fade.color.blue / 255f,
                fade.alpha
            )
        )
    )
}

@Composable
private fun BoxScope.SystemNnnStandaloneLayerFill(
    effect: SystemNnnActiveLayerEffect
) {
    val color = SystemNnnVisualEffectMath.nativeScreenColor(effect.parameters)
    Box(
        Modifier.fillMaxSize().background(
            Color(color.red / 255f, color.green / 255f, color.blue / 255f, 1f)
        )
    )
}

@Composable
private fun BoxScope.SystemNnnStandaloneLayerSuna(
    effect: SystemNnnActiveLayerEffect,
    frameTimeMillis: Int,
    referenceWidth: Float,
    referenceHeight: Float
) {
    val parameters = effect.parameters
    val animationKey = listOf(effect.layerIndex, effect.effectId, effect.commandOffset, parameters)
    var frame by remember(animationKey) { mutableIntStateOf(0) }
    LaunchedEffect(animationKey, frameTimeMillis) {
        while (true) {
            delay(frameTimeMillis.coerceAtLeast(1).toLong())
            frame = if (frame == Int.MAX_VALUE) 0 else frame + 1
        }
    }
    val bitmap = remember(animationKey, frame, referenceWidth, referenceHeight) {
        val width = referenceWidth.roundToInt().coerceAtLeast(1)
        val height = referenceHeight.roundToInt().coerceAtLeast(1)
        runCatching {
            val pixels = SystemNnnNativeEffectRenderer.sunaNoiseOverlay(
                width = width,
                height = height,
                frame = frame,
                parameters = parameters
            )
            if (pixels.size == width * height) {
                Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            } else null
        }.onFailure { failure ->
            SystemNnnDiagnosticLog.active?.recordException(
                "suna_layer_overlay_failure",
                failure,
                "layer=" + effect.layerIndex + " params=" + parameters + " frame=" + frame
            )
        }.getOrNull()
    }
    DisposableEffect(bitmap) {
        onDispose {
            if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
        }
    }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = "SUNA layer noise",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
            alignment = Alignment.TopStart
        )
    }
}

@Composable
private fun BoxScope.SystemNnnStandaloneLayerOldFilm(
    effect: SystemNnnActiveLayerEffect,
    frameTimeMillis: Int,
    referenceWidth: Float,
    referenceHeight: Float
) {
    val animationKey = listOf(effect.layerIndex, effect.effectId, effect.commandOffset, effect.parameters)
    var frame by remember(animationKey) { mutableIntStateOf(0) }
    LaunchedEffect(animationKey, frameTimeMillis) {
        while (true) {
            delay(frameTimeMillis.coerceAtLeast(1).toLong())
            frame = if (frame == Int.MAX_VALUE) 0 else frame + 1
        }
    }
    val marks = SystemNnnVisualEffectMath.oldFilmMarks(
        frame = frame.toLong(),
        parameters = effect.parameters,
        screenWidth = referenceWidth.roundToInt().coerceAtLeast(1),
        screenHeight = referenceHeight.roundToInt().coerceAtLeast(1),
        seed = effect.layerIndex xor (effect.commandOffset?.toInt() ?: 0)
    )
    if (marks.isNotEmpty()) {
        Canvas(Modifier.fillMaxSize()) {
            val scaleX = size.width / referenceWidth.coerceAtLeast(1f)
            val scaleY = size.height / referenceHeight.coerceAtLeast(1f)
            marks.forEach { mark ->
                drawRect(
                    color = Color(
                        mark.color.red / 255f,
                        mark.color.green / 255f,
                        mark.color.blue / 255f,
                        mark.alpha
                    ),
                    topLeft = Offset(mark.x * scaleX, mark.y * scaleY),
                    size = Size(mark.width * scaleX, mark.height * scaleY)
                )
            }
        }
    }
}

@Composable
private fun BoxScope.SystemNnnStandaloneLayerFlash(
    effect: SystemNnnActiveLayerEffect,
    frameTimeMillis: Int
) {
    val parameters = effect.parameters
    val duration = (parameters.getOrNull(0) ?: 20).coerceIn(0, 9_999)
    val animationKey = listOf(effect.layerIndex, effect.effectId, effect.commandOffset, parameters)
    var frame by remember(animationKey) { mutableIntStateOf(0) }
    LaunchedEffect(animationKey, frameTimeMillis) {
        for (currentFrame in 0..duration) {
            frame = currentFrame
            if (currentFrame < duration) {
                delay(frameTimeMillis.coerceAtLeast(1).toLong())
            }
        }
    }
    val flash = SystemNnnVisualEffectMath.flashState(frame, parameters)
    if (flash.drawsFill) {
        Box(
            Modifier.fillMaxSize().background(
                Color(
                    flash.color.red / 255f,
                    flash.color.green / 255f,
                    flash.color.blue / 255f,
                    1f
                )
            )
        )
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

private val NATIVE_SCREEN_BUFFER_RENDER_EFFECTS = setOf(2, 20, 22, 29, 31, 43, 50, 69, 71, 74, 100)

@Composable
private fun SystemNnnScreenEffectOverlay(
    effectId: Int,
    parameters: List<Int>,
    frame: Int,
    referenceWidth: Float,
    referenceHeight: Float,
    sourceBitmap: Bitmap?,
    savedGetBufferBitmap: Bitmap?,
    modifier: Modifier = Modifier
) {
    val source = sourceBitmap ?: savedGetBufferBitmap
    val sourcePixels = remember(source) {
        source?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }?.let { bitmap ->
            runCatching {
                IntArray(bitmap.width * bitmap.height).also { pixels ->
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                }
            }.onFailure { failure ->
                SystemNnnDiagnosticLog.active?.recordException(
                    "screen_effect_source_read_failure", failure, "effectId=$effectId frame=$frame"
                )
            }.getOrNull()
        }
    }
    val savedPixels = remember(savedGetBufferBitmap) {
        savedGetBufferBitmap?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }?.let { bitmap ->
            runCatching {
                IntArray(bitmap.width * bitmap.height).also { pixels ->
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                }
            }.onFailure { failure ->
                SystemNnnDiagnosticLog.active?.recordException(
                    "screen_effect_saved_buffer_read_failure", failure, "effectId=$effectId frame=$frame"
                )
            }.getOrNull()
        }
    }
    val renderedBitmap = remember(
        effectId, parameters, frame, source, sourcePixels, savedGetBufferBitmap, savedPixels
    ) {
        if (effectId !in NATIVE_SCREEN_BUFFER_RENDER_EFFECTS || source == null ||
            source.isRecycled || sourcePixels == null
        ) {
            null
        } else {
            runCatching {
                val pixels = SystemNnnNativeEffectRenderer.renderScreenBufferEffect(
                    effectId = effectId,
                    frame = frame,
                    parameters = parameters,
                    source = sourcePixels,
                    width = source.width,
                    height = source.height,
                    savedGetBuffer = savedPixels,
                    transitionSource = savedPixels
                ) ?: return@runCatching null
                Bitmap.createBitmap(pixels, source.width, source.height, Bitmap.Config.ARGB_8888)
            }.onFailure { failure ->
                SystemNnnDiagnosticLog.active?.recordException(
                    "screen_effect_render_failure",
                    failure,
                    "effectId=$effectId frame=$frame params=$parameters"
                )
            }.getOrNull()
        }
    }
    LaunchedEffect(effectId, parameters, frame, renderedBitmap, source, sourcePixels) {
        if (renderedBitmap == null) {
            val composeImplementedEffect = effectId == 18
            val supportedPainterFallback = effectId in setOf(10, 11, 54, 55) || composeImplementedEffect
            val sourceAvailable = source != null && !source.isRecycled && sourcePixels != null
            val waitingForSource = SystemNnnVisualEffectMath.isWaitingForNativeScreenBufferSource(
                effectId, parameters, sourceAvailable
            )
            val fallbackReason = when {
                waitingForSource -> "Waiting for a captured viewport frame"
                composeImplementedEffect ->
                    "Rendered by the viewport shake transform, not by a bitmap overlay"
                else -> "No native bitmap renderer produced an output"
            }
            val diagnosticEvent = when {
                waitingForSource -> "screen_effect_waiting_for_source"
                supportedPainterFallback -> "screen_effect_fallback"
                else -> "screen_effect_unsupported"
            }
            SystemNnnDiagnosticLog.active?.recordTrace(
                diagnosticEvent,
                mapOf(
                    "effect_id" to effectId,
                    "effectName" to SystemNnnVisualEffectMath.observedEffectName(effectId),
                    "parameters_raw" to parameters,
                    "para_0_to_15" to (0 until 16).map { parameters.getOrNull(it) },
                    "currentFrame" to frame,
                    "sourceAvailable" to sourceAvailable,
                    "rendererAvailable" to (effectId in NATIVE_SCREEN_BUFFER_RENDER_EFFECTS),
                    "fallbackPainterAvailable" to supportedPainterFallback,
                    "reason" to fallbackReason
                ),
                summary = "screen effect $effectId frame $frame has no rendered bitmap"
            )
        }
    }
    DisposableEffect(renderedBitmap) {
        onDispose {
            if (renderedBitmap != null && renderedBitmap !== sourceBitmap &&
                renderedBitmap !== savedGetBufferBitmap && !renderedBitmap.isRecycled
            ) {
                renderedBitmap.recycle()
            }
        }
    }
    if (renderedBitmap != null) {
        Image(
            bitmap = renderedBitmap.asImageBitmap(),
            contentDescription = "System-NNN screen-buffer effect",
            modifier = modifier,
            contentScale = ContentScale.FillBounds,
            alignment = Alignment.TopStart
        )
        return
    }

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
    optionsEnabled: Boolean,
    skipAllowed: Boolean,
    onSave: () -> Unit,
    onLoad: () -> Unit,
    onAutoToggle: () -> Unit,
    onSkipToggle: () -> Unit,
    onExportDiagnostics: () -> Unit,
    onExportTrace: () -> Unit,
    debugVisible: Boolean,
    onDebugToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color(0xD9101216),
        contentColor = Color.White,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color(0x665F6875)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            if (optionsEnabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    QuickMenuButton(label = "SAVE", selected = false, onClick = onSave)
                    QuickMenuButton(label = "LOAD", selected = false, onClick = onLoad)
                    QuickMenuButton(label = "AUTO", selected = autoEnabled, onClick = onAutoToggle)
                    QuickMenuButton(label = "SKIP", selected = skipEnabled, onClick = onSkipToggle, enabled = skipAllowed)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                QuickMenuButton(label = "LOG", selected = false, onClick = onExportDiagnostics)
                QuickMenuButton(label = "TRACE", selected = false, onClick = onExportTrace)
                QuickMenuButton(label = "DBG", selected = debugVisible, onClick = onDebugToggle)
            }
        }
    }
}

@Composable
private fun QuickMenuButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
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
    modifier: Modifier = Modifier,
    clicksEnabled: Boolean = true
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
                    enabled = clicksEnabled,
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

private const val DEBUG_OVERLAY_REFRESH_MILLIS = 500L
private const val TYPEWRITER_DELAY_MILLIS = 24L
private const val AUTO_ADVANCE_DELAY_MILLIS = 1_350L
private const val SCRIPT_AUTO_MESSAGE_WAIT_MILLIS = 2_000L
private const val SKIP_ADVANCE_DELAY_MILLIS = 160L
