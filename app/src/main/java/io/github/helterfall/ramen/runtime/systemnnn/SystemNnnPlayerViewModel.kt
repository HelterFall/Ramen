package io.github.helterfall.ramen.runtime.systemnnn

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.github.helterfall.ramen.parser.SystemNnnScript
import io.github.helterfall.ramen.parser.SystemNnnCommandStream
import io.github.helterfall.ramen.parser.SystemNnnCommandStreamReader
import io.github.helterfall.ramen.parser.SystemNnnSceneRoute
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

data class SystemNnnNativeRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

data class SystemNnnImageResourceMetadata(
    val resourceName: String,
    val widthPixels: Int,
    val heightPixels: Int,
    val packType: Int?,
    val format: String?,
    val hasAlphaMask: Boolean,
    val blockOffset: Long? = null
)

data class SystemNnnSpriteBitmap(
    val resourceId: String,
    val bitmap: Bitmap,
    val centerXFraction: Float = 0.5f,
    val widthFraction: Float = 0.62f,
    val heightFraction: Float = 0.82f,
    val contentDescription: String? = null,
    val layerIndex: Int = 0,
    /** Native destination rectangle; null is used by legacy centered sprites. */
    val nativeRect: SystemNnnNativeRect? = null,
    val nativeBankNamespace: String? = null,
    val nativeBankSlot: Int? = null,
    val nativeRectFrom: SystemNnnNativeRect? = null,
    val nativeEffectId: Int? = null,
    val nativeEffectParameters: List<Int> = emptyList(),
    val nativeEffectCommandOffset: Long? = null,
    val sourceMetadata: SystemNnnImageResourceMetadata? = null
)

data class SystemNnnChoiceOption(
    val id: String,
    val label: String,
    val targetCommandOffset: Long
)

enum class SystemNnnScreenCapturePurpose {
    GET_BUFFER,
    PREPARE_OVERRAP,
    OVERRAP_TARGET
}

data class SystemNnnNativeRuntimeState(
    val messageWindowPrintFlag: Boolean = true,
    val nextMessageType: Int = 3,
    val nextMessageWindowPrintMode: Int = -1,
    val lastMessageId: Int = -1,
    val messageHaveVoiceFlag: Boolean = false,
    val autoMessage: Int = 0,
    val cannotClick: Int = 0,
    val cannotSkip: Int = 0,
    val optionOff: Int = 0,
    val dontPlayMode: Int = 0,
    val configMask: Int = 0,
    val frameTimeMillis: Int = 50,
    val cgFlags: Set<Pair<Int, Int>> = emptySet(),
    val faceControlGeneration: Long = 0L
)

data class SystemNnnPlayerUiState(
    /** Changes only when a dialogue/choice frame is published. */
    val frameId: Long = 0L,
    /** Monotonic SPT command execution counter, independent of visible dialogue frames. */
    val sceneStepNumber: Long = 0L,
    val lastCommandOffset: Long? = null,
    /** Monotonic identifier for native DRAW events; never restarts typewriter text. */
    val drawEventId: Long = 0L,
    val isLoading: Boolean = false,
    /** One-based module-local film number from the latest validated SETFILM record. */
    val currentFilmNumber: Int? = null,
    val backgroundBitmap: Bitmap? = null,
    val backgroundResourceId: String? = null,
    val backgroundMetadata: SystemNnnImageResourceMetadata? = null,
    val sprites: List<SystemNnnSpriteBitmap> = emptyList(),
    val speakerName: String? = null,
    val dialogueText: String = "",
    val visibleCharacterCount: Int = 0,
    val isTextComplete: Boolean = true,
    val forceReveal: Boolean = false,
    val choices: List<SystemNnnChoiceOption> = emptyList(),
    val isEnded: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val audioWarning: String? = null,
    /** Active effect rendered against the whole game viewport (native screen layer 31). */
    val screenEffectId: Int? = null,
    val screenEffectParameters: List<Int> = emptyList(),
    val screenEffectCommandOffset: Long? = null,
    /** Number of native DRAW frames to present before advancing the SPT stream. */
    val pendingDrawFrameCount: Int? = null,
    /** A one-shot request for the Compose scene's actual viewport pixels. */
    val screenCaptureRequestId: Long = 0L,
    val screenCapturePurpose: SystemNnnScreenCapturePurpose? = null,
    /** Native CEffectGet buffer, exposed only while PUT restores it. */
    val savedGetScreenBufferBitmap: Bitmap? = null,
    /** The pair of full-scene snapshots blended during a native OVERRAP. */
    val overrapFromBitmap: Bitmap? = null,
    val overrapToBitmap: Bitmap? = null,
    val overrapTransitionId: Long = 0L,
    val overrapTransitionType: Int = 0,
    val overrapTransitionDurationFrames: Int = 0,
    val overrapOptionButtonPrintFlag: Int = 1,
    val overrapSceneButtonPrintFlag: Int = 1,
    val nativeRuntime: SystemNnnNativeRuntimeState = SystemNnnNativeRuntimeState()
)

/**
 * Resource lookup contract used by scene commands. Scene resources may be
 * requested by GTB name, by an explicit GTB table index, or by block offset.
 */
fun interface SystemNnnSceneAssetResolver : Closeable {
    @Throws(IOException::class)
    fun loadBitmap(resourceId: String): Bitmap?

    /** Resolves a zero-based row in the already-validated GTB catalog. */
    @Throws(IOException::class)
    fun loadBitmapByIndex(index: Long): Bitmap? = null

    @Throws(IOException::class)
    fun imageMetadataByIndex(index: Long): SystemNnnImageResourceMetadata? = null


    /** Resolves a named image in one explicit native DWQ namespace/slot. */
    @Throws(IOException::class)
    fun loadBitmapFromNativeBank(namespace: String, slot: Int, resourceId: String): Bitmap? = null

    /** Metadata for the same resource lookup, without decoding or allocating its bitmap. */
    @Throws(IOException::class)
    fun imageMetadata(resourceId: String): SystemNnnImageResourceMetadata? = null

    @Throws(IOException::class)
    fun imageMetadata(
        namespace: String,
        slot: Int,
        resourceId: String
    ): SystemNnnImageResourceMetadata? = imageMetadata(resourceId)

    @Throws(IOException::class)
    fun imageMetadataByNativeBankIndex(
        namespace: String,
        slot: Int,
        index: Long
    ): SystemNnnImageResourceMetadata? = imageMetadataByIndex(index)

    /** Resolves a row in one explicit native DWQ namespace/slot. */
    @Throws(IOException::class)
    fun loadBitmapByNativeBankIndex(namespace: String, slot: Int, index: Long): Bitmap? = null

    /** Called only after the matching Compose Image node has left composition. */
    fun releaseBitmap(bitmap: Bitmap) {
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    override fun close() = Unit
}

/**
 * The observed file layout does not yet define a verified System-NNN opcode
 * and operand grammar. Keep bytecode interpretation behind this interface:
 * implementations must consume bounded command bytes and return the next
 * command boundary, rather than treating each byte as an independent opcode.
 */
fun interface SystemNnnCommandDecoder {
    @Throws(IOException::class)
    fun decodeNext(
        script: SystemNnnScript,
        commandOffset: Long
    ): SystemNnnDecodedCommand
}

data class SystemNnnPlayerModuleProgram(
    val name: String,
    val script: SystemNnnScript,
    val commandStream: SystemNnnCommandStream,
    val initialCommandOffset: Long,
    /** Film starts indexed by scene.dat's zero-based filmIndex. */
    val filmStartOffsets: List<Long> = emptyList()
)

data class SystemNnnSceneReplayFilm(
    val filmIndex: Int,
    val commandOffset: Long
)

enum class SystemNnnModuleTransferKind {
    SCRIPT,
    SUBSCRIPT,
    SCRIPTJUMP
}

sealed interface SystemNnnDecodedCommand {
    data class Dialogue(
        val nextCommandOffset: Long,
        val textEntryOffset: Long,
        val speakerName: String? = null,
        val backgroundResourceId: String? = null,
        val sprites: List<SystemNnnSpriteReference> = emptyList(),
        val backgroundResourceIndex: Long? = null,
        val dialogueStepIndex: Long? = null,
        /** Optional explicit DWQ bank identity; both values are required together. */
        val backgroundBankNamespace: String? = null,
        val backgroundBankSlot: Int? = null,
        /** Exact VTB resource name or loose VAW/WGQ path; never inferred from text order. */
        val voiceResourceId: String? = null
    ) : SystemNnnDecodedCommand

    data class SceneUpdate(
        val nextCommandOffset: Long,
        val backgroundResourceId: String? = null,
        val sprites: List<SystemNnnSpriteReference> = emptyList(),
        val backgroundResourceIndex: Long? = null,
        /** Optional explicit DWQ bank identity; both values are required together. */
        val backgroundBankNamespace: String? = null,
        val backgroundBankSlot: Int? = null
    ) : SystemNnnDecodedCommand

    data class LoadImageLayer(
        val nextCommandOffset: Long,
        val layerIndex: Int,
        val resourceId: String
    ) : SystemNnnDecodedCommand

    data class ClearImageLayer(
        val nextCommandOffset: Long,
        val layerIndex: Int
    ) : SystemNnnDecodedCommand

    data class ClearAllImageLayers(
        val nextCommandOffset: Long
    ) : SystemNnnDecodedCommand

    data class Music(
        val nextCommandOffset: Long,
        /** Native MUSIC track number: positive is one-based, -1 stops, -2 changes volume only. */
        val musicNumber: Int,
        val loopCount: Int = 0
    ) : SystemNnnDecodedCommand

    data class Voice(
        val nextCommandOffset: Long,
        val resourceId: String?,
        val channel: Int
    ) : SystemNnnDecodedCommand

    data class Sound(
        val nextCommandOffset: Long,
        /** Native SE name-list slot; -1 stops this channel. */
        val soundNumber: Int,
        val channel: Int
    ) : SystemNnnDecodedCommand

    data class SetImageLayerEffect(
        val nextCommandOffset: Long,
        val layerIndex: Int,
        val effectId: Int,
        val parameters: List<Int> = emptyList()
    ) : SystemNnnDecodedCommand

    data class SetImageLayerRect(
        val nextCommandOffset: Long,
        val layerIndex: Int,
        val fromRect: SystemNnnNativeRect,
        val toRect: SystemNnnNativeRect
    ) : SystemNnnDecodedCommand

    data class Draw(
        val nextCommandOffset: Long,
        /** Native DRAW count: zero continues immediately; a nonzero value yields a draw boundary. */
        val frameCount: Int,
        /** Present when ExecScript returns an OVERRAP transition boundary. */
        val overrapParameters: List<Int>? = null
    ) : SystemNnnDecodedCommand

    data class NativeSystemFunction(
        val nextCommandOffset: Long,
        val functionId: Int,
        val parameters: List<Int> = emptyList(),
        val textValue: String? = null
    ) : SystemNnnDecodedCommand

    data class Skip(
        val nextCommandOffset: Long,
        val reason: String? = null,
        val unsupported: Boolean = false,
        val rawRecordIdentifier: Long? = null,
        val rawCode: Long? = null,
        val recordKind: String? = null
    ) : SystemNnnDecodedCommand

    /** A validated non-sequential local control transfer within the current SPT. */
    data class Jump(
        val targetCommandOffset: Long
    ) : SystemNnnDecodedCommand

    data class SetFilm(
        val nextCommandOffset: Long,
        val filmNumber: Int
    ) : SystemNnnDecodedCommand

    data class EndFilm(
        val nextCommandOffset: Long
    ) : SystemNnnDecodedCommand

    data class ModuleTransfer(
        val nextCommandOffset: Long,
        val targetModuleName: String,
        val kind: SystemNnnModuleTransferKind,
    ) : SystemNnnDecodedCommand

    data object ModuleReturn : SystemNnnDecodedCommand

    data object ControllerReset : SystemNnnDecodedCommand

    data class Choice(
        val promptTextEntryOffset: Long?,
        val options: List<SystemNnnChoiceOption>,
        val speakerName: String? = null,
        val promptText: String? = null
    ) : SystemNnnDecodedCommand

    data object End : SystemNnnDecodedCommand
}

data class SystemNnnSpriteReference(
    val resourceId: String,
    val centerXFraction: Float = 0.5f,
    val widthFraction: Float = 0.62f,
    val heightFraction: Float = 0.82f,
    val contentDescription: String? = null,
    /** Optional explicit DWQ bank identity; both values are required together. */
    val nativeBankNamespace: String? = null,
    val nativeBankSlot: Int? = null,
    /** Native effect-layer index; -1 means use the containing list order. */
    val layerIndex: Int = -1,
    val nativeRect: SystemNnnNativeRect? = null,
    val nativeEffectId: Int? = null,
    val nativeRectFrom: SystemNnnNativeRect? = null,
    val nativeEffectParameters: List<Int> = emptyList(),
    val nativeEffectCommandOffset: Long? = null
)

/**
 * Lifecycle-aware player state machine.
 *
 * A verified [SystemNnnCommandDecoder] is injected by the engine adapter.
 * Without one, the ViewModel intentionally refuses to advance guessed opcodes.
 */
class SystemNnnPlayerViewModel : ViewModel() {
    var uiState by mutableStateOf(SystemNnnPlayerUiState())
        private set

    private var nativeRuntimeState = SystemNnnNativeRuntimeState()
    private var script: SystemNnnScript? = null
    private var commandStream: SystemNnnCommandStream? = null
    private var decoder: SystemNnnCommandDecoder? = null
    private var assets: SystemNnnSceneAssetResolver? = null
    private val bitmapOwners = IdentityHashMap<Bitmap, SystemNnnSceneAssetResolver>()
    private var nextCommandOffset: Long = 0L
    private var frameCounter: Long = 0L
    private var traceStepCounter: Long = 0L
    private var drawEventCounter: Long = 0L
    private var screenCaptureRequestCounter: Long = 0L
    private var savedGetScreenBufferBitmap: Bitmap? = null
    private var preparedOverrapBitmap: Bitmap? = null
    private var overrapPrepared: Boolean = false
    private var pendingOverrapType: Int = 0
    private var pendingOverrapLengthFrames: Int = DEFAULT_OVERRAP_LENGTH_FRAMES
    private var currentModuleName: String? = null
    private var currentFilmNumber: Int? = null
    private var projectModules: Map<String, SystemNnnPlayerModuleProgram> = emptyMap()
    private var moduleDecoderFactory: ((SystemNnnPlayerModuleProgram) -> SystemNnnCommandDecoder)? = null
    private var controllerProgram: SystemNnnPlayerModuleProgram? = null
    private val moduleCallStack = ArrayList<ModuleCallFrame>()
    private var sceneReplayModuleName: String? = null
    private var sceneReplayFilms: List<SystemNnnSceneReplayFilm> = emptyList()
    private var sceneReplayFilmCursor: Int = 0
    private val visualLayerConfigurationStore = SystemNnnVisualLayerConfigurationStore()
    private var diagnosticLog: SystemNnnDiagnosticLog? = null

    private data class ModuleCallFrame(
        val moduleName: String,
        val script: SystemNnnScript,
        val commandStream: SystemNnnCommandStream,
        val decoder: SystemNnnCommandDecoder,
        val nextCommandOffset: Long,
        val currentFilmNumber: Int?
    )
    private var audioRepository: SystemNnnAudioResourceRepository? = null
    /** Dedicated player for background music / optional launch track. */
    private var audioPlayer: SystemNnnAudioPlayer? = null
    private val channelAudioPlayers = LinkedHashMap<String, SystemNnnAudioPlayer>()
    private val channelAudioRequestIds = HashMap<String, Long>()
    private var audioRequestId: Long = 0L
    private var musicRequestId: Long = 0L
    private var audioPausedForLifecycle: Boolean = false
    private val voiceExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler by lazy(LazyThreadSafetyMode.NONE) {
        Handler(Looper.getMainLooper())
    }

    fun setDiagnosticLog(log: SystemNnnDiagnosticLog) {
        diagnosticLog = log
        diagnosticLog?.record("player", "diagnostic recorder attached")
    }

    private fun traceParameters16(parameters: List<Int>): List<Int?> =
        (0 until 16).map { index -> parameters.getOrNull(index) }

    private fun traceRect(rect: SystemNnnNativeRect?): Map<String, Int>? = rect?.let {
        linkedMapOf("x" to it.x, "y" to it.y, "w" to it.width, "h" to it.height)
    }

    private fun traceEffect(
        effectId: Int,
        parameters: List<Int>,
        frame: Int? = null,
        layer: Int? = null
    ): Map<String, Any?> = linkedMapOf(
        "layer" to layer,
        "effect_id" to effectId,
        "name" to SystemNnnVisualEffectMath.observedEffectName(effectId),
        "parameters_raw" to parameters,
        "para_0_to_15" to traceParameters16(parameters),
        "currentFrame" to frame
    )

    private fun traceCommandDetails(command: SystemNnnDecodedCommand): Map<String, Any?> =
        when (command) {
            is SystemNnnDecodedCommand.SetImageLayerEffect -> linkedMapOf(
                "layer" to command.layerIndex,
                "effect_id" to command.effectId,
                "effect_name" to SystemNnnVisualEffectMath.observedEffectName(command.effectId),
                "parameters_raw" to command.parameters,
                "para_0_to_15" to traceParameters16(command.parameters),
                "knownEffect" to (SystemNnnVisualEffectMath.observedParameterCount(command.effectId) != null),
                "nativeCommandAccepted" to SystemNnnVisualEffectMath.shouldApplyNativeLayerEffectCommand(command.effectId),
                "rendererPath" to SystemNnnVisualEffectMath.hasNativeLayerEffectPath(command.effectId, command.parameters)
            )
            is SystemNnnDecodedCommand.NativeSystemFunction -> linkedMapOf(
                "function_id" to command.functionId,
                "function_id_hex" to "0x" + command.functionId.toString(16),
                "parameters_raw" to command.parameters,
                "para_0_to_15" to traceParameters16(command.parameters),
                "textValue" to command.textValue
            )
            is SystemNnnDecodedCommand.Skip -> linkedMapOf(
                "reason" to command.reason,
                "unsupported" to command.unsupported,
                "rawRecordIdentifier" to command.rawRecordIdentifier,
                "rawCode" to command.rawCode,
                "recordKind" to command.recordKind
            )
            is SystemNnnDecodedCommand.Draw -> linkedMapOf(
                "frameCount" to command.frameCount,
                "overrapParameters" to command.overrapParameters
            )
            is SystemNnnDecodedCommand.Dialogue -> linkedMapOf(
                "textEntryOffset" to command.textEntryOffset,
                "speaker" to command.speakerName,
                "voiceResourceId" to command.voiceResourceId,
                "dialogueStepIndex" to command.dialogueStepIndex
            )
            is SystemNnnDecodedCommand.Choice -> linkedMapOf(
                "promptTextEntryOffset" to command.promptTextEntryOffset,
                "optionCount" to command.options.size,
                "options" to command.options.map {
                    mapOf("id" to it.id, "targetCommandOffset" to it.targetCommandOffset, "label" to it.label)
                }
            )
            is SystemNnnDecodedCommand.LoadImageLayer -> linkedMapOf(
                "layer" to command.layerIndex, "resourceName" to command.resourceId
            )
            is SystemNnnDecodedCommand.ClearImageLayer -> mapOf("layer" to command.layerIndex)
            is SystemNnnDecodedCommand.SetImageLayerRect -> mapOf(
                "layer" to command.layerIndex,
                "srcRect" to traceRect(command.fromRect),
                "dstRect" to traceRect(command.toRect)
            )
            is SystemNnnDecodedCommand.SceneUpdate -> mapOf(
                "backgroundResourceId" to command.backgroundResourceId,
                "backgroundResourceIndex" to command.backgroundResourceIndex,
                "spriteCount" to command.sprites.size
            )
            is SystemNnnDecodedCommand.Music -> mapOf(
                "musicNumber" to command.musicNumber, "loopCount" to command.loopCount
            )
            is SystemNnnDecodedCommand.Voice -> mapOf(
                "resourceId" to command.resourceId, "channel" to command.channel
            )
            is SystemNnnDecodedCommand.Sound -> mapOf(
                "soundNumber" to command.soundNumber, "channel" to command.channel
            )
            is SystemNnnDecodedCommand.Jump -> mapOf("targetCommandOffset" to command.targetCommandOffset)
            is SystemNnnDecodedCommand.SetFilm -> mapOf("filmNumber" to command.filmNumber)
            is SystemNnnDecodedCommand.EndFilm -> mapOf("nextCommandOffset" to command.nextCommandOffset)
            is SystemNnnDecodedCommand.ModuleTransfer -> mapOf(
                "targetModuleName" to command.targetModuleName, "kind" to command.kind.name
            )
            else -> emptyMap()
        }

    private fun traceLayer(
        sprite: SystemNnnSpriteBitmap,
        logicalWidth: Int,
        logicalHeight: Int
    ): Map<String, Any?> {
        val bitmap = sprite.bitmap
        val fallbackWidth = (logicalWidth * sprite.widthFraction.coerceIn(0.1f, 1f))
            .roundToInt().coerceAtLeast(1)
        val fallbackHeight = (logicalHeight * sprite.heightFraction.coerceIn(0.1f, 1f))
            .roundToInt().coerceAtLeast(1)
        val effectId = sprite.nativeEffectId
        val dst = when {
            effectId in setOf(27, 39, 56, 63, 102) ->
                SystemNnnNativeRect(0, 0, logicalWidth, logicalHeight)
            effectId == 64 || effectId == 65 ->
                SystemNnnNativeRect(
                    (logicalWidth - bitmap.width) / 2,
                    (logicalHeight - bitmap.height) / 2,
                    bitmap.width,
                    bitmap.height
                )
            sprite.nativeRect != null -> sprite.nativeRect
            else -> SystemNnnNativeRect(
                x = (logicalWidth * sprite.centerXFraction - fallbackWidth / 2f).roundToInt(),
                y = logicalHeight - fallbackHeight,
                width = fallbackWidth,
                height = fallbackHeight
            )
        }
        val metadata = sprite.sourceMetadata
        return linkedMapOf(
            "layerIndex" to sprite.layerIndex,
            "resourceName" to sprite.resourceId,
            "bankNamespace" to sprite.nativeBankNamespace,
            "bankSlot" to sprite.nativeBankSlot,
            "sourceSize" to mapOf("width" to bitmap.width, "height" to bitmap.height),
            "declaredSize" to metadata?.let {
                mapOf("width" to it.widthPixels, "height" to it.heightPixels)
            },
            "dstRect" to traceRect(dst),
            "dstRectEstimated" to (sprite.nativeRect == null),
            "positioningMode" to when {
                effectId in setOf(27, 39, 56, 63, 102) -> "full_viewport"
                effectId == 64 || effectId == 65 -> "centered_bitmap"
                sprite.nativeRect != null -> "native_rect"
                else -> "bottom_center_default"
            },
            "srcRect" to traceRect(sprite.nativeRectFrom),
            "nativeRectWasExplicit" to (sprite.nativeRect != null),
            "packType" to metadata?.packType,
            "format" to metadata?.format,
            "hasAlphaMask" to metadata?.hasAlphaMask,
            "hasPerPixelAlpha" to bitmap.hasAlpha(),
            "alphaPremultiplied" to bitmap.isPremultiplied,
            "blendMode" to "SRC_OVER",
            "globalOpacity" to 1.0,
            "effect" to sprite.nativeEffectId?.let {
                traceEffect(it, sprite.nativeEffectParameters, frame = null, layer = sprite.layerIndex)
            },
            "effectCommandOffset" to sprite.nativeEffectCommandOffset
        )
    }

    private fun queryImageMetadata(
        resolver: SystemNnnSceneAssetResolver,
        resourceId: String?,
        resourceIndex: Long?,
        namespace: String?,
        slot: Int?
    ): SystemNnnImageResourceMetadata? {
        return runCatching {
            when {
                namespace != null && slot != null && resourceId != null ->
                    resolver.imageMetadata(namespace, slot, resourceId)
                namespace != null && slot != null && resourceIndex != null ->
                    resolver.imageMetadataByNativeBankIndex(namespace, slot, resourceIndex)
                resourceId != null -> resolver.imageMetadata(resourceId)
                resourceIndex != null -> resolver.imageMetadataByIndex(resourceIndex)
                else -> null
            }
        }.onFailure { failure ->
            diagnosticLog?.recordException(
                "image_metadata_lookup_failure",
                failure,
                "resource=${resourceId ?: "none"} index=${resourceIndex ?: "none"} " +
                    "bank=${namespace ?: "none"} slot=${slot ?: "none"}"
            )
        }.getOrNull()
    }

    private fun recordSceneTrace(
        event: String,
        commandOffset: Long? = null,
        commandName: String? = null,
        commandDetails: Map<String, Any?> = emptyMap(),
        backgroundBitmap: Bitmap? = uiState.backgroundBitmap,
        backgroundResourceId: String? = uiState.backgroundResourceId,
        backgroundMetadata: SystemNnnImageResourceMetadata? = uiState.backgroundMetadata,
        sprites: List<SystemNnnSpriteBitmap> = uiState.sprites,
        screenEffectId: Int? = uiState.screenEffectId,
        screenEffectParameters: List<Int> = uiState.screenEffectParameters
    ) {
        val logicalWidth = (backgroundBitmap?.width ?: backgroundMetadata?.widthPixels
            ?: sprites.firstOrNull()?.bitmap?.width ?: 800).coerceAtLeast(1)
        val logicalHeight = (backgroundBitmap?.height ?: backgroundMetadata?.heightPixels
            ?: sprites.firstOrNull()?.bitmap?.height ?: 600).coerceAtLeast(1)
        val anomalies = ArrayList<Map<String, Any?>>()
        if (backgroundBitmap != null && backgroundMetadata != null &&
            (backgroundBitmap.width != backgroundMetadata.widthPixels ||
                backgroundBitmap.height != backgroundMetadata.heightPixels)
        ) {
            anomalies += mapOf(
                "type" to "background_declared_size_mismatch",
                "resourceName" to backgroundResourceId,
                "bitmap" to "${backgroundBitmap.width}x${backgroundBitmap.height}",
                "declared" to "${backgroundMetadata.widthPixels}x${backgroundMetadata.heightPixels}"
            )
        }
        sprites.forEach { sprite ->
            val bitmap = sprite.bitmap
            val metadata = sprite.sourceMetadata
            if (metadata != null &&
                (metadata.widthPixels != bitmap.width || metadata.heightPixels != bitmap.height)
            ) {
                anomalies += mapOf(
                    "type" to "layer_declared_size_mismatch",
                    "layerIndex" to sprite.layerIndex,
                    "resourceName" to sprite.resourceId,
                    "bitmap" to "${bitmap.width}x${bitmap.height}",
                    "declared" to "${metadata.widthPixels}x${metadata.heightPixels}"
                )
            }
            val destination = sprite.nativeRect
            if (destination != null) {
                if (destination.width <= 0 || destination.height <= 0) {
                    anomalies += mapOf(
                        "type" to "invalid_dst_rect",
                        "layerIndex" to sprite.layerIndex,
                        "dstRect" to traceRect(destination)
                    )
                }
                if (destination.x < 0 || destination.y < 0 ||
                    destination.x.toLong() + destination.width.toLong() > logicalWidth.toLong() ||
                    destination.y.toLong() + destination.height.toLong() > logicalHeight.toLong()
                ) {
                    anomalies += mapOf(
                        "type" to "dst_rect_out_of_bounds",
                        "layerIndex" to sprite.layerIndex,
                        "dstRect" to traceRect(destination),
                        "logicalResolution" to mapOf("width" to logicalWidth, "height" to logicalHeight)
                    )
                }
            }
            val sourceRect = sprite.nativeRectFrom
            if (sourceRect != null && (
                sourceRect.x < 0 || sourceRect.y < 0 || sourceRect.width <= 0 || sourceRect.height <= 0 ||
                    sourceRect.x.toLong() + sourceRect.width.toLong() > bitmap.width.toLong() ||
                    sourceRect.y.toLong() + sourceRect.height.toLong() > bitmap.height.toLong()
                )
            ) {
                anomalies += mapOf(
                    "type" to "src_rect_out_of_bounds",
                    "layerIndex" to sprite.layerIndex,
                    "srcRect" to traceRect(sourceRect),
                    "sourceSize" to "${bitmap.width}x${bitmap.height}"
                )
            }
        }
        val effectList = sprites.mapNotNull { sprite ->
            sprite.nativeEffectId?.let {
                traceEffect(it, sprite.nativeEffectParameters, frame = null, layer = sprite.layerIndex)
            }
        }.toMutableList()
        if (screenEffectId != null) {
            effectList += traceEffect(screenEffectId, screenEffectParameters, frame = null, layer = SCREEN_EFFECT_LAYER_INDEX)
            if (SystemNnnVisualEffectMath.observedParameterCount(screenEffectId) == null) {
                anomalies += mapOf("type" to "unknown_screen_effect", "effect_id" to screenEffectId)
            }
        }
        val savedBuffer = uiState.savedGetScreenBufferBitmap
        val from = uiState.overrapFromBitmap
        val to = uiState.overrapToBitmap
        diagnosticLog?.recordTrace(
            event,
            linkedMapOf(
                "step" to traceStepCounter,
                "frameId" to uiState.frameId,
                "drawEventId" to uiState.drawEventId,
                "commandOffset" to commandOffset,
                "commandName" to commandName,
                "module" to currentModuleName,
                "film" to currentFilmNumber,
                "command" to commandDetails,
                "dialogue" to linkedMapOf(
                    "speaker" to uiState.speakerName,
                    "text" to uiState.dialogueText,
                    "visibleCharacters" to uiState.visibleCharacterCount,
                    "isComplete" to uiState.isTextComplete
                ),
                "logicalResolution" to mapOf("width" to logicalWidth, "height" to logicalHeight),
                "canvas" to null,
                "viewport" to null,
                "scaleMatrix" to null,
                "background" to backgroundBitmap?.let {
                    mapOf(
                        "resourceName" to backgroundResourceId,
                        "sourceSize" to mapOf("width" to it.width, "height" to it.height),
                        "packType" to backgroundMetadata?.packType,
                        "format" to backgroundMetadata?.format,
                        "hasAlphaMask" to backgroundMetadata?.hasAlphaMask,
                        "hasPerPixelAlpha" to it.hasAlpha(),
                        "alphaPremultiplied" to it.isPremultiplied
                    )
                },
                "layers" to sprites.map { traceLayer(it, logicalWidth, logicalHeight) },
                "activeEffects" to effectList,
                "screenBuffer" to linkedMapOf(
                    "screenEffectId" to screenEffectId,
                    "screenEffectParameters" to traceParameters16(screenEffectParameters),
                    "screenEffectRawParameters" to screenEffectParameters,
                    "screenEffectFrame" to null,
                    "savedGetBuffer" to savedBuffer?.let {
                        mapOf("width" to it.width, "height" to it.height, "recycled" to it.isRecycled)
                    }
                ),
                "overrap" to linkedMapOf(
                    "transitionId" to uiState.overrapTransitionId,
                    "transitionType" to uiState.overrapTransitionType,
                    "durationFrames" to uiState.overrapTransitionDurationFrames,
                    "fromBitmap" to from?.let { "${it.width}x${it.height}" },
                    "toBitmap" to to?.let { "${it.width}x${it.height}" },
                    "progress" to null,
                    "prepared" to overrapPrepared,
                    "optionButtonPrintFlag" to uiState.overrapOptionButtonPrintFlag,
                    "sceneButtonPrintFlag" to uiState.overrapSceneButtonPrintFlag
                ),
                "pendingDrawFrameCount" to uiState.pendingDrawFrameCount,
                "anomalies" to anomalies
            ),
            summary = "step=$traceStepCounter module=${currentModuleName ?: "standalone"} " +
                "film=${currentFilmNumber ?: "none"} offset=${commandOffset?.let { "0x" + it.toString(16) } ?: "none"} " +
                "command=${commandName ?: event} layers=${sprites.size} effects=${effectList.size} anomalies=${anomalies.size}"
        )
        anomalies.forEach { anomaly ->
            diagnosticLog?.recordTrace(
                "scene_anomaly",
                mapOf(
                    "step" to traceStepCounter,
                    "commandOffset" to commandOffset,
                    "anomaly" to anomaly
                ),
                summary = anomaly.toString()
            )
        }
    }

    private fun updateDiagnosticSnapshot(commandOffset: Long, commandName: String) {
        val sprites = uiState.sprites.joinToString(separator = ";") { sprite ->
            "L${sprite.layerIndex}:${sprite.resourceId}[${sprite.bitmap.width}x${sprite.bitmap.height}]" +
                " effect=${sprite.nativeEffectId ?: "none"} params=${sprite.nativeEffectParameters.joinToString(",")}"
        }
        diagnosticLog?.updateRuntimeSnapshot(
            "module=${currentModuleName ?: "standalone"} film=${currentFilmNumber ?: "none"} " +
                "command=$commandName offset=0x${commandOffset.toString(16)} frame=$frameCounter " +
                "screenEffect=${uiState.screenEffectId ?: "none"} " +
                "screenParams=${uiState.screenEffectParameters.joinToString(",")} " +
                "background=${uiState.backgroundBitmap?.let { "${it.width}x${it.height}" } ?: "none"} " +
                "sprites=$sprites"
        )
    }

    private fun recordDecodedBitmap(resourceId: String, bitmap: Bitmap) {
        diagnosticLog?.record(
            "bitmap_decoded",
            "resource=$resourceId size=${bitmap.width}x${bitmap.height} " +
                "config=${bitmap.config} hasAlpha=${bitmap.hasAlpha()} alphaPremultiplied=${bitmap.isPremultiplied}"
        )
    }

    fun showStartupLoading() {
        uiState = SystemNnnPlayerUiState(isLoading = true)
    }

    fun showStartupError(message: String) {
        releaseAssets()
        uiState = SystemNnnPlayerUiState(
            isLoading = false,
            errorMessage = message
        )
    }

    fun start(
        script: SystemNnnScript,
        commandDecoder: SystemNnnCommandDecoder?,
        assetResolver: SystemNnnSceneAssetResolver,
        initialCommandOffset: Long = script.commandStartOffset,
        audioRepository: SystemNnnAudioResourceRepository? = null,
        initialAudioTrack: File? = null,
        initialAudioResourceName: String? = null,
        moduleName: String? = null,
        modulePrograms: Collection<SystemNnnPlayerModuleProgram> = emptyList(),
        decoderFactory: ((SystemNnnPlayerModuleProgram) -> SystemNnnCommandDecoder)? = null,
        controllerModuleName: String? = null,
        initialFilmNumber: Int? = null,
        sceneReplayModuleName: String? = null,
        sceneReplayFilms: List<SystemNnnSceneReplayFilm> = emptyList()
    ) {
        releaseAssets()
        nativeRuntimeState = SystemNnnNativeRuntimeState()
        visualLayerConfigurationStore.clear()

        moduleCallStack.clear()
        this.sceneReplayModuleName = null
        this.sceneReplayFilms = emptyList()
        sceneReplayFilmCursor = 0
        currentModuleName = moduleName
        currentFilmNumber = initialFilmNumber
        this.sceneReplayModuleName = sceneReplayModuleName
        this.sceneReplayFilms = sceneReplayFilms
        this.sceneReplayFilmCursor = if (sceneReplayFilms.isNotEmpty()) 1 else 0
        if (moduleName == null) {
            projectModules = emptyMap()
            moduleDecoderFactory = null
            controllerProgram = null
        } else {
            val mappedPrograms = modulePrograms.associateBy {
                it.name.lowercase(Locale.ROOT)
            }
            if (mappedPrograms.size != modulePrograms.size) {
                uiState = SystemNnnPlayerUiState(errorMessage = "В проекте повторяются имена модулей.")
                runCatching { assetResolver.close() }
                runCatching { audioRepository?.close() }
                return
            }
            projectModules = mappedPrograms
            moduleDecoderFactory = decoderFactory
            controllerProgram = mappedPrograms[
                (controllerModuleName ?: moduleName).lowercase(Locale.ROOT)
            ]
            if (controllerProgram == null || decoderFactory == null) {
                uiState = SystemNnnPlayerUiState(errorMessage = "Не удалось подготовить управляющий модуль проекта.")
                runCatching { assetResolver.close() }
                runCatching { audioRepository?.close() }
                return
            }
        }
        val parsedCommandStream = try {
            if (moduleName != null) {
                modulePrograms.firstOrNull {
                    it.name.equals(moduleName, ignoreCase = true)
                }?.commandStream
                    ?: throw IOException("В активном модуле '$moduleName' отсутствует разобранный поток команд.")
            } else {
                SystemNnnCommandStreamReader().read(script)
            }
        } catch (error: Exception) {
            runCatching { assetResolver.close() }
            runCatching { audioRepository?.close() }
            uiState = SystemNnnPlayerUiState(
                errorMessage = error.message ?: "Не удалось разобрать командный поток SPT."
            )
            return
        }

        this.script = script
        this.commandStream = parsedCommandStream
        this.decoder = commandDecoder
        this.assets = assetResolver
        this.audioRepository = audioRepository
        this.nextCommandOffset = if (initialCommandOffset == script.commandStartOffset) {
            parsedCommandStream.recordStartOffset
        } else {
            initialCommandOffset
        }
        this.frameCounter = 0L
        this.traceStepCounter = 0L
        this.audioRequestId += 1L

        uiState = SystemNnnPlayerUiState(isLoading = true)
        startAudio(initialAudioTrack, initialAudioResourceName)

        if (commandDecoder == null) {
            uiState = uiState.copy(
                isLoading = false,
                statusMessage = "SPT загружен: текстовые пулы прочитаны, " +
                    parsedCommandStream.records.size + " командных записей проверено. " +
                    "Для воспроизведения нужен декодер команд System-NNN."
            )
            return
        }

        advanceToVisibleEvent()
    }

    fun startProject(
        modules: Collection<SystemNnnPlayerModuleProgram>,
        controlModuleName: String,
        assetResolver: SystemNnnSceneAssetResolver,
        decoderFactory: (SystemNnnPlayerModuleProgram) -> SystemNnnCommandDecoder,
        audioRepository: SystemNnnAudioResourceRepository? = null,
        initialSceneRoute: SystemNnnSceneRoute? = null
    ) {
        val orderedModules = modules.toList()
        val moduleMap = orderedModules.associateBy { it.name.lowercase(Locale.ROOT) }
        val control = moduleMap[controlModuleName.lowercase(Locale.ROOT)]
        if (control == null) {
            runCatching { assetResolver.close() }
            runCatching { audioRepository?.close() }
            showStartupError("В project.xtx отсутствует управляющий модуль '$controlModuleName'.")
            return
        }

        val startingProgram = if (initialSceneRoute == null) {
            control
        } else {
            val routedModule = orderedModules.getOrNull(initialSceneRoute.moduleIndex)
                ?: run {
                    runCatching { assetResolver.close() }
                    runCatching { audioRepository?.close() }
                    showStartupError(
                        "scene.dat указывает на module index ${initialSceneRoute.moduleIndex} " +
                            "за пределами project.xtx."
                    )
                    return
                }
            if (!routedModule.name.equals(initialSceneRoute.moduleName, ignoreCase = true)) {
                runCatching { assetResolver.close() }
                runCatching { audioRepository?.close() }
                showStartupError(
                    "scene.dat module index ${initialSceneRoute.moduleIndex} должен указывать на " +
                        "'${initialSceneRoute.moduleName}', но project.xtx содержит '${routedModule.name}'."
                )
                return
            }
            routedModule
        }
        val replayFilms = if (initialSceneRoute == null) {
            emptyList()
        } else {
            initialSceneRoute.filmIndices
                .drop(initialSceneRoute.filmChoiceIndex)
                .map { filmIndex ->
                    val offset = startingProgram.filmStartOffsets.getOrNull(filmIndex)
                        ?: run {
                            runCatching { assetResolver.close() }
                            runCatching { audioRepository?.close() }
                            showStartupError(
                                "scene.dat указывает на отсутствующий film slot $filmIndex " +
                                    "в модуле '${startingProgram.name}'."
                            )
                            return
                        }
                    SystemNnnSceneReplayFilm(filmIndex, offset)
                }
        }
        val startOffset = replayFilms.firstOrNull()?.commandOffset
            ?: startingProgram.initialCommandOffset

        start(
            script = startingProgram.script,
            commandDecoder = decoderFactory(startingProgram),
            assetResolver = assetResolver,
            initialCommandOffset = startOffset,
            audioRepository = audioRepository,
            moduleName = startingProgram.name,
            modulePrograms = modules,
            decoderFactory = decoderFactory,
            controllerModuleName = control.name,
            initialFilmNumber = replayFilms.firstOrNull()?.filmIndex?.plus(1),
            sceneReplayModuleName = initialSceneRoute?.moduleName,
            sceneReplayFilms = replayFilms
        )
    }

    /** Alias used by the player screen to advance to the next frame. */
    fun onNextTap() = onScreenTap()

    /** Native global AUTO and SKIP advance without passing through manual click gates. */
    fun onAutomaticAdvance() {
        val current = uiState
        if (current.isLoading || current.isEnded || current.choices.isNotEmpty() ||
            current.pendingDrawFrameCount != null || !current.isTextComplete
        ) return
        advanceToVisibleEvent()
    }

    /** AUTOMESSAGE has its own message timer, separate from NNNPARAM_AUTOMODE. */
    fun onAutoMessageTimerElapsed() {
        val current = uiState
        if (current.nativeRuntime.autoMessage <= 0 || current.isLoading ||
            current.isEnded || current.choices.isNotEmpty() ||
            current.pendingDrawFrameCount != null || !current.isTextComplete
        ) return
        advanceToVisibleEvent()
    }

    /** Mirrors CGameCallBack::SetDontPlay and NNNPARAM_AUTOMODE. */
    fun setNativeAutoMode(enabled: Boolean) {
        nativeRuntimeState = nativeRuntimeState.copy(dontPlayMode = if (enabled) 1 else 0)
        uiState = uiState.copy(nativeRuntime = nativeRuntimeState)
    }

    /**
     * A tap first completes the current typewriter line. Only a subsequent tap
     * asks the command decoder for the next event.
     */
    fun onScreenTap() {
        val current = uiState
        diagnosticLog?.recordTrace(
            "screen_tap",
            mapOf(
                "step" to traceStepCounter,
                "commandOffset" to nextCommandOffset,
                "frameId" to current.frameId,
                "dialogue" to current.dialogueText,
                "visibleCharacters" to current.visibleCharacterCount
            )
        )
        if (current.nativeRuntime.cannotClick > 0 || current.nativeRuntime.autoMessage > 0 ||
            current.isLoading || current.isEnded || current.choices.isNotEmpty() ||
            current.pendingDrawFrameCount != null
        ) return

        if (!current.isTextComplete) {
            uiState = current.copy(
                visibleCharacterCount = current.dialogueText.length,
                isTextComplete = true,
                forceReveal = true
            )
            return
        }

        advanceToVisibleEvent()
    }

    /**
     * Called by the screen after presenting the requested number of native DRAW frames.
     * The next SPT command is not executed until this boundary has been displayed.
     */
    fun onDrawFrameCompleted() {
        val current = uiState
        diagnosticLog?.recordTrace(
            "draw_frame_presented",
            mapOf(
                "step" to current.sceneStepNumber,
                "commandOffset" to current.lastCommandOffset,
                "drawEventId" to current.drawEventId,
                "pendingFrames" to current.pendingDrawFrameCount
            )
        )
        if (current.pendingDrawFrameCount == null || current.isLoading || current.isEnded) return
        uiState = current.copy(pendingDrawFrameCount = null)
        advanceToVisibleEvent()
    }

    /** Applies the optional native init/Overrap.xtx button flags to the current player. */
    fun setOverrapConfig(config: SystemNnnOverrapConfig) {
        uiState = uiState.copy(
            overrapOptionButtonPrintFlag = config.optionButtonPrintFlag,
            overrapSceneButtonPrintFlag = config.sceneButtonPrintFlag
        )
        diagnosticLog?.record(
            "overrap_setup",
            "configured=${config.isConfigured} optionButtonPrintFlag=${config.optionButtonPrintFlag} " +
                "sceneButtonPrintFlag=${config.sceneButtonPrintFlag}"
        )
    }

    /** Requests a frame capture and pauses the interpreter until Compose returns it. */
    private fun requestScreenCapture(
        purpose: SystemNnnScreenCapturePurpose,
        overrapParameters: List<Int> = emptyList()
    ) {
        if (purpose == SystemNnnScreenCapturePurpose.OVERRAP_TARGET) {
            pendingOverrapType = overrapParameters.getOrNull(0) ?: 0
            pendingOverrapLengthFrames = (overrapParameters.getOrNull(1)
                ?: DEFAULT_OVERRAP_LENGTH_FRAMES).coerceIn(1, 9_999)
        }
        screenCaptureRequestCounter += 1L
        uiState = uiState.copy(
            isLoading = false,
            pendingDrawFrameCount = null,
            screenCaptureRequestId = screenCaptureRequestCounter,
            screenCapturePurpose = purpose
        )
    }

    /** Completes GET/PREPAREOVERRAP/OVERRAP using the exact scene pixels recorded by Compose. */
    fun onScreenCaptureReady(requestId: Long, bitmap: Bitmap?) {
        val current = uiState
        if (requestId == 0L || requestId != current.screenCaptureRequestId) {
            bitmap?.takeIf { !it.isRecycled }?.recycle()
            return
        }
        val purpose = current.screenCapturePurpose
        if (bitmap == null || bitmap.isRecycled) {
            diagnosticLog?.recordTrace(
                "screen_capture_error",
                mapOf("requestId" to requestId, "purpose" to purpose?.name),
                summary = "Compose screen capture returned no bitmap"
            )
            uiState = current.copy(
                isLoading = false,
                screenCaptureRequestId = 0L,
                screenCapturePurpose = null,
                errorMessage = "Не удалось получить снимок буфера System-NNN."
            )
            when (purpose) {
                SystemNnnScreenCapturePurpose.OVERRAP_TARGET -> {
                    overrapPrepared = false
                    preparedOverrapBitmap = null
                    advanceToVisibleEvent()
                }
                else -> advanceToVisibleEvent()
            }
            return
        }

        when (purpose) {
            SystemNnnScreenCapturePurpose.GET_BUFFER -> {
                savedGetScreenBufferBitmap = bitmap
                uiState = current.copy(
                    isLoading = false,
                    screenCaptureRequestId = 0L,
                    screenCapturePurpose = null,
                    savedGetScreenBufferBitmap = bitmap
                )
                advanceToVisibleEvent()
            }
            SystemNnnScreenCapturePurpose.PREPARE_OVERRAP -> {
                preparedOverrapBitmap = bitmap
                overrapPrepared = true
                uiState = current.copy(
                    isLoading = false,
                    screenCaptureRequestId = 0L,
                    screenCapturePurpose = null
                )
                advanceToVisibleEvent()
            }
            SystemNnnScreenCapturePurpose.OVERRAP_TARGET -> {
                val from = preparedOverrapBitmap
                preparedOverrapBitmap = null
                overrapPrepared = false
                if (from == null || from.isRecycled) {
                    uiState = current.copy(
                        isLoading = false,
                        screenCaptureRequestId = 0L,
                        screenCapturePurpose = null,
                        pendingDrawFrameCount = 1
                    )
                } else {
                    uiState = current.copy(
                        isLoading = false,
                        screenCaptureRequestId = 0L,
                        screenCapturePurpose = null,
                        overrapFromBitmap = from,
                        overrapToBitmap = bitmap,
                        overrapTransitionId = current.overrapTransitionId + 1L,
                        overrapTransitionType = pendingOverrapType,
                        overrapTransitionDurationFrames = pendingOverrapLengthFrames,
                        pendingDrawFrameCount = null
                    )
                }
            }
            null -> {
                bitmap.recycle()
                uiState = current.copy(
                    isLoading = false,
                    screenCaptureRequestId = 0L,
                    screenCapturePurpose = null
                )
                advanceToVisibleEvent()
            }
        }
    }

    /** Called by Compose after the two native overrap snapshots reach their final mix. */
    fun onOverrapTransitionFinished(transitionId: Long) {
        val current = uiState
        if (transitionId == 0L || transitionId != current.overrapTransitionId ||
            current.overrapFromBitmap == null || current.overrapToBitmap == null
        ) return
        uiState = current.copy(
            overrapFromBitmap = null,
            overrapToBitmap = null,
            overrapTransitionDurationFrames = 0
        )
        advanceToVisibleEvent()
    }

    /**
     * Compose calls this after the corresponding image leaves composition.
     *
     * Do not recycle here: Compose/Android may still hold the Bitmap in a
     * painter or recorded display list after the node is disposed. The bitmap
     * becomes collectible once those rendering references are released.
     */
    fun onBitmapDisposed(bitmap: Bitmap) {
        bitmapOwners.remove(bitmap)
    }

    /**
     * Only for decoded bitmaps that were never published to Compose. These
     * have no UI painter or in-flight rendering operation that can retain them.
     */
    private fun releaseUnpublishedBitmap(bitmap: Bitmap) {
        val owner = bitmapOwners.remove(bitmap)
        runCatching {
            if (owner != null) owner.releaseBitmap(bitmap)
            else if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun loadBitmap(
        resourceId: String,
        resolver: SystemNnnSceneAssetResolver
    ): Bitmap? = resolver.loadBitmap(resourceId)?.also { bitmap ->
        bitmapOwners[bitmap] = resolver
        recordDecodedBitmap(resourceId, bitmap)
    }

    /** Pauses all active native audio channels while the player Activity is not visible. */
    fun onHostPaused() {
        if (audioPausedForLifecycle) return
        audioPausedForLifecycle = true
        audioPlayer?.pause()
        channelAudioPlayers.values.forEach(SystemNnnAudioPlayer::pause)
    }

    /** Resumes only players that were paused by the Activity lifecycle. */
    fun onHostResumed() {
        if (!audioPausedForLifecycle) return
        audioPausedForLifecycle = false
        audioPlayer?.resume()
        channelAudioPlayers.values.forEach(SystemNnnAudioPlayer::resume)
    }

    private fun pauseAudioIfNeeded(player: SystemNnnAudioPlayer) {
        if (audioPausedForLifecycle) player.pause()
    }

    private fun startAudio(track: File?, resourceName: String?) {
        audioPlayer = SystemNnnAudioPlayer { status ->
            if (status.state == SystemNnnAudioPlaybackState.ERROR) {
                uiState = uiState.copy(
                    audioWarning = "Не удалось запустить фоновый трек: " +
                        (status.message ?: "ошибка воспроизведения.")
                )
            }
        }
        if (track != null) {
            runCatching {
                audioPlayer?.let { player ->
                    player.play(track, resourceName ?: track.name)
                    pauseAudioIfNeeded(player)
                }
            }.onFailure { error ->
                uiState = uiState.copy(
                    audioWarning = "Не удалось запустить фоновый трек: " +
                        (error.message ?: "ошибка подготовки.")
                )
            }
        }
    }

    private fun loadBitmapByIndex(
        index: Long,
        resolver: SystemNnnSceneAssetResolver
    ): Bitmap? = resolver.loadBitmapByIndex(index)?.also { bitmap ->
        bitmapOwners[bitmap] = resolver
        recordDecodedBitmap("index:$index", bitmap)
    }

    private fun loadBitmapFromNativeBank(
        namespace: String,
        slot: Int,
        resourceId: String,
        resolver: SystemNnnSceneAssetResolver
    ): Bitmap? = resolver.loadBitmapFromNativeBank(namespace, slot, resourceId)?.also { bitmap ->
        bitmapOwners[bitmap] = resolver
        recordDecodedBitmap("$namespace$slot/$resourceId", bitmap)
    }

    private fun loadBitmapByNativeBankIndex(
        namespace: String,
        slot: Int,
        index: Long,
        resolver: SystemNnnSceneAssetResolver
    ): Bitmap? = resolver.loadBitmapByNativeBankIndex(namespace, slot, index)?.also { bitmap ->
        bitmapOwners[bitmap] = resolver
        recordDecodedBitmap("$namespace$slot/index:$index", bitmap)
    }

    private fun validateNativeBankIdentity(namespace: String?, slot: Int?) {
        if ((namespace == null) != (slot == null) ||
            (namespace != null && (namespace.isBlank() || slot == null || slot < 0))
        ) {
            throw IOException("A native image bank identity requires a non-blank namespace and non-negative slot.")
        }
    }

    private fun resolveBackground(
        resourceId: String,
        namespace: String?,
        slot: Int?,
        resolver: SystemNnnSceneAssetResolver
    ): Bitmap {
        validateNativeBankIdentity(namespace, slot)
        if (namespace == null) {
            return loadBitmap(resourceId, resolver)
                ?: throw IOException("Background resource was not found.")
        }
        return loadBitmapFromNativeBank(namespace, checkNotNull(slot), resourceId, resolver)
            ?: throw IOException("Native background resource was not found in " + namespace + slot + ".")
    }

    private fun resolveBackgroundIndex(
        index: Long,
        namespace: String?,
        slot: Int?,
        resolver: SystemNnnSceneAssetResolver
    ): Bitmap {
        validateNativeBankIdentity(namespace, slot)
        if (namespace == null) {
            return loadBitmapByIndex(index, resolver)
                ?: throw IOException("Background GTB resource index " + index + " was not found.")
        }
        return loadBitmapByNativeBankIndex(namespace, checkNotNull(slot), index, resolver)
            ?: throw IOException(
                "Native background index " + index + " was not found in " + namespace + slot + "."
            )
    }

    private fun nextAudioRequestId(): Long = ++audioRequestId

    private fun channelAudioPlayer(key: String): SystemNnnAudioPlayer =
        channelAudioPlayers.getOrPut(key) {
            SystemNnnAudioPlayer { status ->
                if (status.state == SystemNnnAudioPlaybackState.ERROR) {
                    uiState = uiState.copy(
                        audioWarning = "Не удалось воспроизвести '" + status.resourceName + "': " +
                            (status.message ?: "ошибка аудио.")
                    )
                }
            }
        }

    private fun playChannelResource(key: String, resourceId: String) {
        val repository = audioRepository ?: return
        val normalizedId = resourceId.trim().takeIf { it.isNotEmpty() } ?: return
        val requestId = nextAudioRequestId()
        channelAudioRequestIds[key] = requestId
        val player = channelAudioPlayer(key)

        voiceExecutor.execute {
            val staged = runCatching { repository.materialize(normalizedId) }
            mainHandler.post {
                if (channelAudioRequestIds[key] != requestId) {
                    staged.getOrNull()?.delete()
                    return@post
                }
                staged.onSuccess { file ->
                    runCatching {
                        player.play(file, normalizedId)
                        pauseAudioIfNeeded(player)
                    }.onFailure { error ->
                            uiState = uiState.copy(
                                audioWarning = "Не удалось запустить '" + normalizedId + "': " +
                                    (error.message ?: "ошибка воспроизведения.")
                            )
                        }
                }.onFailure { error ->
                    uiState = uiState.copy(
                        audioWarning = "Не удалось подготовить '" + normalizedId + "': " +
                            (error.message ?: "ресурс не найден.")
                    )
                }
            }
        }
    }

    private fun playChannelIndex(key: String, index: Long) {
        val repository = audioRepository ?: return
        if (index < 0L) return
        val requestId = nextAudioRequestId()
        channelAudioRequestIds[key] = requestId
        val player = channelAudioPlayer(key)
        voiceExecutor.execute {
            val staged = runCatching { repository.materializeByIndex(index) }
            mainHandler.post {
                if (channelAudioRequestIds[key] != requestId) {
                    staged.getOrNull()?.delete()
                    return@post
                }
                staged.onSuccess { file ->
                    runCatching {
                        player.play(file, "voice-index-" + index)
                        pauseAudioIfNeeded(player)
                    }.onFailure { error ->
                            uiState = uiState.copy(
                                audioWarning = "Не удалось запустить голосовую дорожку: " +
                                    (error.message ?: "ошибка воспроизведения.")
                            )
                        }
                }.onFailure { error ->
                    uiState = uiState.copy(
                        audioWarning = "Не удалось подготовить голосовую дорожку: " +
                            (error.message ?: "индекс не найден.")
                    )
                }
            }
        }
    }

    private fun stopChannelResource(key: String) {
        channelAudioRequestIds[key] = nextAudioRequestId()
        channelAudioPlayers[key]?.stop()
    }

    private fun playMusicResource(resourceId: String, repeatCount: Int) {
        val repository = audioRepository ?: return
        val player = audioPlayer ?: return
        val requestId = ++musicRequestId
        player.stop()
        voiceExecutor.execute {
            val staged = runCatching { repository.materializeMusic(resourceId, repeatCount) }
            mainHandler.post {
                if (requestId != musicRequestId) {
                    staged.getOrNull()?.files?.forEach { it.delete() }
                    return@post
                }
                staged.onSuccess { playlist ->
                    runCatching {
                        player.playPlaylist(
                            files = playlist.files,
                            resourceName = resourceId,
                            repeatCount = playlist.repeatCount,
                            infiniteLoopStartIndex = playlist.infiniteLoopStartIndex
                        )
                        pauseAudioIfNeeded(player)
                    }.onFailure { error ->
                        playlist.files.forEach { it.delete() }
                        uiState = uiState.copy(
                            audioWarning = "Не удалось запустить фоновую музыку '" + resourceId + "': " +
                                (error.message ?: "ошибка воспроизведения.")
                        )
                    }
                }.onFailure { error ->
                    uiState = uiState.copy(
                        audioWarning = "Не удалось подготовить музыку '" + resourceId + "': " +
                            (error.message ?: "ресурс не найден.")
                    )
                }
            }
        }
    }

    private fun playSoundResource(key: String, resourceId: String) {
        val repository = audioRepository ?: return
        val normalizedId = resourceId.trim().takeIf { it.isNotEmpty() } ?: return
        val requestId = nextAudioRequestId()
        channelAudioRequestIds[key] = requestId
        val player = channelAudioPlayer(key)
        voiceExecutor.execute {
            val staged = runCatching { repository.materializeSound(normalizedId) }
            mainHandler.post {
                if (channelAudioRequestIds[key] != requestId) {
                    staged.getOrNull()?.delete()
                    return@post
                }
                staged.onSuccess { file ->
                    runCatching {
                        player.play(file, normalizedId)
                        pauseAudioIfNeeded(player)
                    }.onFailure { error ->
                            file.delete()
                            uiState = uiState.copy(
                                audioWarning = "Не удалось запустить SE '" + normalizedId + "': " +
                                    (error.message ?: "ошибка воспроизведения.")
                            )
                        }
                }.onFailure { error ->
                    uiState = uiState.copy(
                        audioWarning = "Не удалось подготовить SE '" + normalizedId + "': " +
                            (error.message ?: "ресурс не найден.")
                    )
                }
            }
        }
    }

    private fun stopAllAudio() {
        musicRequestId += 1L
        audioPlayer?.stop()
        channelAudioPlayers.keys.toList().forEach(::stopChannelResource)
    }

    /** Uses the actual VOICE Pool 2 name when available; otherwise no guessed index is played. */
    private fun playVoice(dialogueStepIndex: Long?, voiceResourceId: String?) {
        val exactResourceId = voiceResourceId?.trim()?.takeIf { it.isNotEmpty() }
        when {
            exactResourceId != null -> playChannelResource("voice:0", exactResourceId)
            dialogueStepIndex != null -> playChannelIndex("voice:0", dialogueStepIndex)
        }
    }

    fun onTypewriterProgress(visibleCharacters: Int) {
        val current = uiState
        if (current.isTextComplete || current.forceReveal) return
        uiState = current.copy(
            visibleCharacterCount = visibleCharacters.coerceIn(0, current.dialogueText.length)
        )
    }

    fun onTypewriterFinished() {
        val current = uiState
        if (current.isTextComplete) return
        uiState = current.copy(
            visibleCharacterCount = current.dialogueText.length,
            isTextComplete = true
        )
    }

    fun onChoiceSelected(choiceId: String) {
        if (uiState.nativeRuntime.cannotClick > 0 || uiState.nativeRuntime.autoMessage > 0) return
        val choice = uiState.choices.firstOrNull { it.id == choiceId } ?: return
        val currentScript = script ?: return
        if (!isValidCommandOffset(currentScript, choice.targetCommandOffset)) {
            uiState = uiState.copy(
                errorMessage = "Вариант выбора указывает за пределы командной области."
            )
            return
        }

        nextCommandOffset = choice.targetCommandOffset
        uiState = uiState.copy(choices = emptyList(), errorMessage = null, isLoading = true)
        advanceToVisibleEvent()
    }

    private fun applyNativeSystemFunction(functionId: Int, parameters: List<Int>): Boolean {
        val current = nativeRuntimeState
        val first = parameters.firstOrNull() ?: 0
        val normalizedMinusOne = if (first == -1) 0 else first

        nativeRuntimeState = when (functionId) {
            0x0017 -> current.copy(messageWindowPrintFlag = true)
            0x0018 -> {
                val player = parameters.getOrNull(0) ?: return true
                val cgNumber = parameters.getOrNull(1) ?: return true
                if (player <= 0 || cgNumber <= 0) current
                else current.copy(cgFlags = current.cgFlags + ((player - 1) to (cgNumber - 1)))
            }
            0x001B -> {
                val messageId = parameters.getOrNull(0)
                val nextMode = when {
                    parameters.size > 1 -> when (parameters[1]) {
                        0 -> -1
                        1 -> 0
                        2 -> 1
                        else -> current.nextMessageWindowPrintMode
                    }
                    parameters.size == 1 -> -1
                    else -> current.nextMessageWindowPrintMode
                }
                current.copy(
                    lastMessageId = messageId?.takeIf { it >= 0 } ?: current.lastMessageId,
                    nextMessageWindowPrintMode = nextMode,
                    messageHaveVoiceFlag = false
                )
            }
            0x001C -> {
                val base = current.copy(faceControlGeneration = current.faceControlGeneration + 1L)
                if (parameters.isEmpty()) {
                    base
                } else {
                    val nextType = parameters.getOrNull(1) ?: 3
                    val nextWindowMode = parameters.getOrNull(2) ?: -1
                    var windowVisible = nextType == 0
                    if (nextWindowMode == 1) windowVisible = false
                    if (nextWindowMode == 2) windowVisible = true
                    base.copy(
                        nextMessageType = nextType,
                        nextMessageWindowPrintMode = nextWindowMode,
                        messageWindowPrintFlag = windowVisible
                    )
                }
            }
            0x001E -> current.copy(dontPlayMode = if (first == 2) 0 else first)
            0x0028 -> when {
                first > 0 -> current.copy(frameTimeMillis = first)
                first == 0 -> current.copy(frameTimeMillis = DEFAULT_NATIVE_FRAME_TIME_MILLIS)
                else -> current
            }
            0x0053 -> when {
                first > 0 -> current.copy(configMask = first)
                first < 0 -> current.copy(configMask = 0)
                else -> current
            }
            0x0057 -> current.copy(autoMessage = normalizedMinusOne)
            0x0058 -> current.copy(cannotClick = normalizedMinusOne)
            0x0059 -> current.copy(cannotSkip = normalizedMinusOne)
            0x005A -> current.copy(optionOff = normalizedMinusOne)
            else -> return false
        }

        uiState = uiState.copy(nativeRuntime = nativeRuntimeState)
        return true
    }

    private fun advanceToVisibleEvent() {
        val resolver = assets

        if (script == null || decoder == null || resolver == null) {
            uiState = uiState.copy(
                isLoading = false,
                errorMessage = "Игровая сессия не инициализирована."
            )
            return
        }

        uiState = uiState.copy(isLoading = true, errorMessage = null)

        try {
            var sceneBackground = uiState.backgroundBitmap
            var sceneBackgroundResourceId = uiState.backgroundResourceId
            var sceneBackgroundMetadata = uiState.backgroundMetadata
            var sceneSprites = uiState.sprites
            var pendingBackgroundResourceId: String? = null
            var pendingBackgroundResourceIndex: Long? = null
            var pendingBackgroundBankNamespace: String? = null
            var pendingBackgroundBankSlot: Int? = null
            var pendingScreenEffectId = uiState.screenEffectId
            var pendingScreenEffectParameters = uiState.screenEffectParameters
            var pendingScreenEffectCommandOffset = uiState.screenEffectCommandOffset
            val pendingSpriteLayers = LinkedHashMap<Int, SystemNnnSpriteReference>()
            val pendingLayerVisuals = visualLayerConfigurationStore.snapshot()
            sceneSprites.forEach { sprite ->
                pendingSpriteLayers[sprite.layerIndex] = SystemNnnSpriteReference(
                    resourceId = sprite.resourceId,
                    centerXFraction = sprite.centerXFraction,
                    widthFraction = sprite.widthFraction,
                    heightFraction = sprite.heightFraction,
                    contentDescription = sprite.contentDescription,
                    nativeBankNamespace = sprite.nativeBankNamespace,
                    nativeBankSlot = sprite.nativeBankSlot,
                    layerIndex = sprite.layerIndex,
                    nativeRect = sprite.nativeRect,
                    nativeRectFrom = sprite.nativeRectFrom,
                    nativeEffectId = sprite.nativeEffectId,
                    nativeEffectParameters = sprite.nativeEffectParameters,
                    nativeEffectCommandOffset = sprite.nativeEffectCommandOffset
                )
            }
            var pendingSpriteLayersChanged = false
            var imageStatusMessage: String? = null

            fun clearPendingSceneVisuals() {
                sceneBackground = null
                sceneBackgroundResourceId = null
                sceneBackgroundMetadata = null
                pendingBackgroundResourceId = null
                pendingBackgroundResourceIndex = null
                pendingBackgroundBankNamespace = null
                pendingBackgroundBankSlot = null
                pendingScreenEffectId = null
                pendingScreenEffectParameters = emptyList()
                pendingScreenEffectCommandOffset = null
                pendingSpriteLayers.clear()
                pendingLayerVisuals.clear()
                pendingSpriteLayersChanged = true
            }

            fun appendImageStatusMessage(message: String?) {
                if (message.isNullOrBlank()) return
                message.lines().filter { it.isNotBlank() }.forEach { warning ->
                    diagnosticLog?.recordTrace(
                        "scene_warning",
                        mapOf(
                            "step" to traceStepCounter,
                            "module" to currentModuleName,
                            "film" to currentFilmNumber,
                            "commandOffset" to nextCommandOffset,
                            "message" to warning
                        ),
                        summary = warning
                    )
                }
                imageStatusMessage = listOfNotNull(imageStatusMessage, message)
                    .joinToString(separator = "\n")
            }

            var commandsWithoutVisibleOutput = 0

            while (commandsWithoutVisibleOutput < MAX_SCENE_COMMANDS_PER_ADVANCE) {
                val currentScript = script ?: throw IOException("Текущий модуль не загружен.")
                val currentDecoder = decoder ?: throw IOException("Декодер текущего модуля не загружен.")
                val commandOffset = nextCommandOffset
                if (!isValidCommandCursor(currentScript, commandOffset)) {
                    throw IOException("Command cursor is not on a validated SPT record boundary.")
                }

                val command = currentDecoder.decodeNext(currentScript, commandOffset)
                traceStepCounter += 1L
                diagnosticLog?.record(
                    "command",
                    "module=${currentModuleName ?: "standalone"} film=${currentFilmNumber ?: "none"} " +
                        "step=$traceStepCounter offset=0x${commandOffset.toString(16)} type=${command.javaClass.simpleName}"
                )
                recordSceneTrace(
                    event = "script_step",
                    commandOffset = commandOffset,
                    commandName = command.javaClass.simpleName,
                    commandDetails = traceCommandDetails(command)
                )
                updateDiagnosticSnapshot(commandOffset, command.javaClass.simpleName)
                when (command) {
                    is SystemNnnDecodedCommand.SceneUpdate -> {
                        validateNextOffset(currentScript, commandOffset, command.nextCommandOffset)
                        nextCommandOffset = command.nextCommandOffset
                        clearPendingSceneVisuals()
                        validateNativeBankIdentity(
                            command.backgroundBankNamespace,
                            command.backgroundBankSlot
                        )
                        if (command.backgroundResourceIndex != null) {
                            if (command.backgroundResourceId != null) {
                                throw IOException("Scene update has conflicting image resource references.")
                            }
                            pendingBackgroundResourceIndex = command.backgroundResourceIndex
                            pendingBackgroundResourceId = null
                            pendingBackgroundBankNamespace = command.backgroundBankNamespace
                            pendingBackgroundBankSlot = command.backgroundBankSlot
                        } else if (command.backgroundResourceId != null) {
                            pendingBackgroundResourceId = command.backgroundResourceId
                            pendingBackgroundResourceIndex = null
                            pendingBackgroundBankNamespace = command.backgroundBankNamespace
                            pendingBackgroundBankSlot = command.backgroundBankSlot
                        } else if (command.backgroundBankNamespace != null) {
                            throw IOException("A native image bank identity requires a background resource.")
                        }
                        // A scene snapshot replaces the complete visual layer set.
                        command.sprites.forEachIndexed { index, reference ->
                            val layer = if (reference.layerIndex in 0 until MAX_IMAGE_LAYERS) {
                                reference.layerIndex
                            } else {
                                index
                            }
                            if (layer in 0 until MAX_IMAGE_LAYERS) {
                                pendingSpriteLayers[layer] = reference.copy(layerIndex = layer)
                            }
                        }
                        pendingSpriteLayersChanged = true
                        commandsWithoutVisibleOutput += 1
                    }

                    is SystemNnnDecodedCommand.LoadImageLayer -> {
                        diagnosticLog?.record(
                            "layer_load",
                            "module=${currentModuleName ?: "standalone"} offset=0x${commandOffset.toString(16)} " +
                                "layer=${command.layerIndex} resource=${command.resourceId}"
                        )
                        validateNextOffset(currentScript, commandOffset, command.nextCommandOffset)
                        if (command.layerIndex !in 0 until MAX_IMAGE_LAYERS) {
                            throw IOException("LOADDWQ supplied an invalid effect layer.")
                        }
                        nextCommandOffset = command.nextCommandOffset
                        val layerVisuals = pendingLayerVisuals[command.layerIndex]
                        pendingSpriteLayers[command.layerIndex] = SystemNnnSpriteReference(
                            resourceId = command.resourceId,
                            centerXFraction = 0.5f,
                            widthFraction = 1f,
                            heightFraction = 1f,
                            contentDescription = "Image layer " + command.layerIndex,
                            layerIndex = command.layerIndex,
                            nativeRect = layerVisuals?.destinationRect,
                            nativeEffectId = layerVisuals?.effectId,
                            nativeRectFrom = layerVisuals?.sourceRect,
                            nativeEffectParameters = layerVisuals?.effectParameters.orEmpty(),
                            nativeEffectCommandOffset = layerVisuals?.effectCommandOffset
                        )
                        pendingSpriteLayersChanged = true
                        commandsWithoutVisibleOutput += 1
                    }

                    is SystemNnnDecodedCommand.ClearImageLayer -> {
                        diagnosticLog?.record(
                            "layer_clear",
                            "module=${currentModuleName ?: "standalone"} offset=0x${commandOffset.toString(16)} layer=${command.layerIndex}"
                        )
                        validateNextOffset(currentScript, commandOffset, command.nextCommandOffset)
                        if (command.layerIndex !in 0 until MAX_IMAGE_LAYERS) {
                            throw IOException("CLEAREFFECTLAYER supplied an invalid layer.")
                        }
                        nextCommandOffset = command.nextCommandOffset
                        pendingLayerVisuals.remove(command.layerIndex)
                        if (command.layerIndex == SCREEN_EFFECT_LAYER_INDEX) {
                            pendingScreenEffectId = null
                            pendingScreenEffectParameters = emptyList()
                            pendingScreenEffectCommandOffset = null
                            if (pendingSpriteLayers.remove(command.layerIndex) != null) {
                                pendingSpriteLayersChanged = true
                            }
                        } else {
                            pendingSpriteLayers.remove(command.layerIndex)
                            pendingSpriteLayersChanged = true
                        }
                        commandsWithoutVisibleOutput += 1
                    }

                    is SystemNnnDecodedCommand.ClearAllImageLayers -> {
                        validateNextOffset(currentScript, commandOffset, command.nextCommandOffset)
                        nextCommandOffset = command.nextCommandOffset
                        pendingSpriteLayers.clear()
                        pendingLayerVisuals.clear()
                        pendingScreenEffectId = null
                        pendingScreenEffectParameters = emptyList()
                        pendingScreenEffectCommandOffset = null
                        pendingSpriteLayersChanged = true
                        commandsWithoutVisibleOutput += 1
                    }

                    is SystemNnnDecodedCommand.SetImageLayerRect -> {
                        diagnosticLog?.record(
                            "layer_rect",
                            "offset=0x${commandOffset.toString(16)} layer=${command.layerIndex} " +
                                "from=${command.fromRect} to=${command.toRect}"
                        )
                        validateNextOffset(currentScript, commandOffset, command.nextCommandOffset)
                        nextCommandOffset = command.nextCommandOffset
                        val currentLayerVisuals = pendingLayerVisuals[command.layerIndex]
                            ?: SystemNnnVisualLayerConfiguration()
                        pendingLayerVisuals[command.layerIndex] = currentLayerVisuals.copy(
                            sourceRect = command.fromRect,
                            destinationRect = command.toRect
                        )
                        pendingSpriteLayers[command.layerIndex]?.let { reference ->
                            pendingSpriteLayers[command.layerIndex] = reference.copy(
                                nativeRectFrom = command.fromRect,
                                nativeRect = command.toRect
                            )
                        }
                        pendingSpriteLayersChanged = true
                        commandsWithoutVisibleOutput += 1
                    }

                    is SystemNnnDecodedCommand.SetImageLayerEffect -> {
                        val previousEffect = pendingLayerVisuals[command.layerIndex]?.effectId
                        diagnosticLog?.record(
                            "effect_set",
                            "module=${currentModuleName ?: "standalone"} film=${currentFilmNumber ?: "none"} " +
                                "offset=0x${commandOffset.toString(16)} layer=${command.layerIndex} " +
                                "id=${command.effectId} params=${command.parameters.joinToString(",")} " +
                                "previous=${previousEffect ?: "none"} accepted=" +
                                SystemNnnVisualEffectMath.shouldApplyNativeLayerEffectCommand(command.effectId)
                        )
                        val effectKnown = SystemNnnVisualEffectMath.observedParameterCount(command.effectId) != null
                        val effectCommandAccepted = SystemNnnVisualEffectMath.shouldApplyNativeLayerEffectCommand(command.effectId)
                        val effectRendererPath = SystemNnnVisualEffectMath.hasNativeLayerEffectPath(
                            command.effectId, command.parameters
                        )
                        if (!effectKnown || !effectCommandAccepted) {
                            appendImageStatusMessage(
                                "UNSUPPORTED EFFECT id=${command.effectId} name=${SystemNnnVisualEffectMath.observedEffectName(command.effectId)} " +
                                    "layer=${command.layerIndex} parameters=${command.parameters}"
                            )
                        } else if (!effectRendererPath) {
                            appendImageStatusMessage(
                                "FALLBACK EFFECT id=${command.effectId} name=${SystemNnnVisualEffectMath.observedEffectName(command.effectId)} " +
                                    "layer=${command.layerIndex}: no complete native renderer path"
                            )
                        }

                        validateNextOffset(currentScript, commandOffset, command.nextCommandOffset)
                        nextCommandOffset = command.nextCommandOffset

                        // GET (73) copies the actual composed viewport. The script
                        // cannot continue until the frame has been captured by Compose.
                        if (command.effectId == 73) {
                            requestScreenCapture(SystemNnnScreenCapturePurpose.GET_BUFFER)
                            return
                        }

                        // These native effects operate on the shared screen buffer,
                        // rather than on the bitmap assigned to one image layer.
                        if (SystemNnnVisualEffectMath.shouldApplyNativeLayerEffectCommand(command.effectId)) {
                            val screenWideHaikeiYure = command.effectId == 100 &&
                                (command.parameters.getOrNull(5) ?: 0) == 1
                            if (command.effectId == 1 && command.layerIndex == SCREEN_EFFECT_LAYER_INDEX) {
                                pendingScreenEffectId = null
                                pendingScreenEffectParameters = emptyList()
                                pendingScreenEffectCommandOffset = null
                            } else if (command.effectId in SCREEN_BUFFER_EFFECT_IDS || screenWideHaikeiYure) {
                                pendingScreenEffectId = command.effectId
                                pendingScreenEffectParameters = command.parameters
                                pendingScreenEffectCommandOffset = command.nextCommandOffset
                            } else {
                                val currentLayerVisuals = pendingLayerVisuals[command.layerIndex]
                                    ?: SystemNnnVisualLayerConfiguration()
                                pendingLayerVisuals[command.layerIndex] = currentLayerVisuals.copy(
                                    effectId = command.effectId,
                                    effectParameters = command.parameters,
                                    effectCommandOffset = command.nextCommandOffset
                                )
                                pendingSpriteLayers[command.layerIndex]?.let { reference ->
                                    pendingSpriteLayers[command.layerIndex] = reference.copy(
                                        nativeEffectId = command.effectId,
                                        nativeEffectParameters = command.parameters,
                                        nativeEffectCommandOffset = command.nextCommandOffset
                                    )
                                }
                                if (command.effectId == 40 && command.parameters.getOrNull(1) == null) {
                                    appendImageStatusMessage(
                                        "CHANGE (40) on layer " + command.layerIndex +
                                            " has no second-layer operand."
                                    )
                                }
                                pendingSpriteLayersChanged = true
                            }
                            if (command.effectId == 132) {
                                appendImageStatusMessage(
                                    "CHARABURE (132) uses repeatable jitter; its random sequence does not match the original runtime."
                                )
                            }

                        }
                        commandsWithoutVisibleOutput += 1
                    }

                    is SystemNnnDecodedCommand.Music -> {
                        validateNextOffset(currentScript, commandOffset, command.nextCommandOffset)
                        nextCommandOffset = command.nextCommandOffset
                        when {
                            command.musicNumber == -1 -> {
                                musicRequestId += 1L
                                audioPlayer?.stop()
                            }
                            command.musicNumber == -2 -> {
                                uiState = uiState.copy(
                                    audioWarning = "Изменение громкости музыки отдельной командой пока не поддерживается."
                                )
                            }
                            else -> {
                                val resourceId = audioRepository?.musicResourceName(command.musicNumber)
                                if (resourceId.isNullOrBlank()) {
                                    uiState = uiState.copy(
                                        audioWarning = "В spt/bgmlist.xtx не найден музыкальный слот " + command.musicNumber + "."
                                    )
                                } else {
                                    playMusicResource(resourceId, command.loopCount)
                                }
                            }
                        }
                        commandsWithoutVisibleOutput += 1
                    }

                    is SystemNnnDecodedCommand.Voice -> {
                        validateNextOffset(currentScript, commandOffset, command.nextCommandOffset)
                        nextCommandOffset = command.nextCommandOffset
                        val key = "voice:" + command.channel
                        if (command.resourceId == null) stopChannelResource(key)
                        else playChannelResource(key, command.resourceId)
                        commandsWithoutVisibleOutput += 1
                    }

                    is SystemNnnDecodedCommand.Sound -> {
                        validateNextOffset(currentScript, commandOffset, command.nextCommandOffset)
                        nextCommandOffset = command.nextCommandOffset
                        val key = "sound:" + command.channel
                        if (command.soundNumber == -1) {
                            stopChannelResource(key)
                        } else {
                            val resourceId = audioRepository?.soundResourceName(command.soundNumber)
                            if (resourceId.isNullOrBlank()) {
                                uiState = uiState.copy(
                                    audioWarning = "В spt/selist.xtx не найден звуковой слот " + command.soundNumber + "."
                                )
                            } else {
                                playSoundResource(key, resourceId)
                            }
                        }
                        commandsWithoutVisibleOutput += 1
                    }

                    is SystemNnnDecodedCommand.NativeSystemFunction -> {
                        validateNextOffset(currentScript, commandOffset, command.nextCommandOffset)
                        nextCommandOffset = command.nextCommandOffset
                        diagnosticLog?.record(
                            "native_system_function",
                            "module=" + (currentModuleName ?: "standalone") + " film=" + (currentFilmNumber ?: "none") +
                                " offset=0x" + commandOffset.toString(16) + " code=0x" + command.functionId.toString(16) +
                                " params=" + command.parameters.joinToString(";") + " text=" + (command.textValue ?: "none")
                        )
                        if (command.functionId == 0x0011) {
                            requestScreenCapture(SystemNnnScreenCapturePurpose.PREPARE_OVERRAP)
                            return
                        } else if (!applyNativeSystemFunction(command.functionId, command.parameters)) {
                            val functionName = when (command.functionId) {
                                0x0050 -> "FILMNAME"
                                0x0051 -> "STORYNAME"
                                0x0052 -> "FILMTYPETIME"
                                else -> "UNKNOWN"
                            }
                            diagnosticLog?.recordTrace(
                                "fallback_system_function",
                                mapOf(
                                    "step" to traceStepCounter,
                                    "commandOffset" to commandOffset,
                                    "functionId" to command.functionId,
                                    "functionIdHex" to "0x" + command.functionId.toString(16),
                                    "functionName" to functionName,
                                    "parameters" to command.parameters,
                                    "textValue" to command.textValue,
                                    "reason" to "No player-side behavior is implemented for this opcode"
                                ),
                                summary = "System function fallback " + functionName +
                                    " (0x" + command.functionId.toString(16) + ")"
                            )
                            appendImageStatusMessage(
                                "FALLBACK SYSTEM FUNCTION " + functionName +
                                    " (0x" + command.functionId.toString(16) + ") is decoded but not applied."
                            )
                        }
                        commandsWithoutVisibleOutput += 1
                    }

                    is SystemNnnDecodedCommand.Skip -> {
                        validateNextOffset(currentScript, commandOffset, command.nextCommandOffset)
                        nextCommandOffset = command.nextCommandOffset
                        if (command.unsupported) {
                            diagnosticLog?.recordTrace(
                                "unsupported_command",
                                mapOf(
                                    "step" to traceStepCounter,
                                    "commandOffset" to commandOffset,
                                    "reason" to command.reason,
                                    "rawRecordIdentifier" to command.rawRecordIdentifier,
                                    "rawCode" to command.rawCode,
                                    "recordKind" to command.recordKind
                                ),
                                summary = "Unsupported SPT record at 0x" + commandOffset.toString(16) +
                                    ": " + (command.reason ?: "unknown reason")
                            )
                            appendImageStatusMessage(
                                "UNSUPPORTED COMMAND at 0x" + commandOffset.toString(16) +
                                    ": " + (command.reason ?: "unknown record")
                            )
                        } else if (!command.reason.isNullOrBlank()) {
                            diagnosticLog?.recordTrace(
                                "skipped_command",
                                mapOf("step" to traceStepCounter, "commandOffset" to commandOffset, "reason" to command.reason)
                            )
                        }
                        commandsWithoutVisibleOutput += 1
                    }

                    is SystemNnnDecodedCommand.Jump -> {
                        if (!isValidCommandOffset(currentScript, command.targetCommandOffset)) {
                            throw IOException("Local control transfer target is not a validated SPT record boundary.")
                        }
                        nextCommandOffset = command.targetCommandOffset
                        commandsWithoutVisibleOutput += 1
                    }

                    is SystemNnnDecodedCommand.SetFilm -> {
                        validateNextOffset(currentScript, commandOffset, command.nextCommandOffset)
                        if (command.filmNumber <= 0) {
                            throw IOException("SETFILM supplied a non-positive film number.")
                        }
                        nextCommandOffset = command.nextCommandOffset
                        currentFilmNumber = command.filmNumber
                        commandsWithoutVisibleOutput += 1
                    }

                    is SystemNnnDecodedCommand.ModuleTransfer -> {
                        validateNextOffset(currentScript, commandOffset, command.nextCommandOffset)
                        switchModule(
                            targetModuleName = command.targetModuleName,
                            kind = command.kind,
                            callerContinuation = command.nextCommandOffset
                        )
                        commandsWithoutVisibleOutput += 1
                    }

                    is SystemNnnDecodedCommand.EndFilm -> {
                        val replayOwner = sceneReplayModuleName
                        val activeModule = currentModuleName
                        val sceneReplayOwnsCurrentFilm =
                            replayOwner != null &&
                                activeModule != null &&
                                activeModule.equals(replayOwner, ignoreCase = true) &&
                                moduleCallStack.isEmpty()

                        if (sceneReplayOwnsCurrentFilm) {
                            advanceSceneReplayFilm()
                        } else {
                            validateNextOffset(currentScript, commandOffset, command.nextCommandOffset)
                            nextCommandOffset = command.nextCommandOffset
                        }
                        commandsWithoutVisibleOutput += 1
                    }

                    SystemNnnDecodedCommand.ModuleReturn -> {
                        returnToCaller()
                        commandsWithoutVisibleOutput += 1
                    }

                    SystemNnnDecodedCommand.ControllerReset -> {
                        resetControllerOrEnd()
                        commandsWithoutVisibleOutput += 1
                    }

                    is SystemNnnDecodedCommand.Draw -> {
                        validateNextOffset(currentScript, commandOffset, command.nextCommandOffset)
                        nextCommandOffset = command.nextCommandOffset

                        // A zero count never reaches this branch: native ExecScript
                        // consumes it inline. A negative nonzero count still yields
                        // once because the native back-exec scheduler prints at least
                        // one frame before continuing.
                        val drawFrames = command.frameCount.coerceAtLeast(1).coerceAtMost(9_999)
                        validateNativeBankIdentity(
                            pendingBackgroundBankNamespace,
                            pendingBackgroundBankSlot
                        )
                        if (pendingBackgroundResourceId != null) {
                            sceneBackgroundResourceId = pendingBackgroundResourceId
                            sceneBackgroundMetadata = queryImageMetadata(
                                resolver, pendingBackgroundResourceId, null,
                                pendingBackgroundBankNamespace, pendingBackgroundBankSlot
                            )
                            sceneBackground = resolveBackground(
                                pendingBackgroundResourceId!!,
                                pendingBackgroundBankNamespace,
                                pendingBackgroundBankSlot,
                                resolver
                            )
                        } else if (pendingBackgroundResourceIndex != null) {
                            sceneBackgroundResourceId = "index:" + pendingBackgroundResourceIndex
                            sceneBackgroundMetadata = queryImageMetadata(
                                resolver, null, pendingBackgroundResourceIndex,
                                pendingBackgroundBankNamespace, pendingBackgroundBankSlot
                            )
                            sceneBackground = resolveBackgroundIndex(
                                pendingBackgroundResourceIndex!!,
                                pendingBackgroundBankNamespace,
                                pendingBackgroundBankSlot,
                                resolver
                            )
                        }

                        if (pendingSpriteLayersChanged) {
                            val resolution = resolveSprites(
                                references = pendingSpriteLayers.values.sortedBy { it.layerIndex },
                                existingSprites = sceneSprites,
                                resolver = resolver
                            )
                            sceneSprites = resolution.sprites
                            appendImageStatusMessage(resolution.statusMessage)
                            pendingSpriteLayersChanged = false
                        }

                        // These visual commands are accumulated until DRAW. Publish
                        // that exact scene now, rather than running ahead into the
                        // CLEAR commands that commonly follow the animation frames.
                        visualLayerConfigurationStore.replaceWith(pendingLayerVisuals)
                        pendingBackgroundResourceId = null
                        pendingBackgroundResourceIndex = null
                        pendingBackgroundBankNamespace = null
                        pendingBackgroundBankSlot = null
                        val drawEventId = ++drawEventCounter
                        diagnosticLog?.record(
                            "draw_frame",
                            "module=${currentModuleName ?: "standalone"} film=${currentFilmNumber ?: "none"} " +
                                "offset=0x${commandOffset.toString(16)} frames=$drawFrames"
                        )
                        updateDiagnosticSnapshot(command.nextCommandOffset, if (command.overrapParameters == null) "DrawFrame" else "OverrapBoundary")
                        uiState = uiState.copy(
                            sceneStepNumber = traceStepCounter,
                            lastCommandOffset = commandOffset,
                            drawEventId = drawEventId,
                            isLoading = false,
                            backgroundBitmap = sceneBackground,
                            backgroundResourceId = sceneBackgroundResourceId,
                            backgroundMetadata = sceneBackgroundMetadata,
                            sprites = sceneSprites,
                            currentFilmNumber = currentFilmNumber,
                            statusMessage = imageStatusMessage ?: uiState.statusMessage,
                            screenEffectId = pendingScreenEffectId,
                            screenEffectParameters = pendingScreenEffectParameters,
                            screenEffectCommandOffset = pendingScreenEffectCommandOffset,
                            pendingDrawFrameCount = if (command.overrapParameters != null && overrapPrepared) {
                                null
                            } else {
                                drawFrames
                            }
                        )
                        recordSceneTrace(
                            event = "scene_frame",
                            commandOffset = commandOffset,
                            commandName = if (command.overrapParameters == null) "Draw" else "DrawOverrap",
                            commandDetails = traceCommandDetails(command),
                            backgroundBitmap = sceneBackground,
                            backgroundResourceId = sceneBackgroundResourceId,
                            backgroundMetadata = sceneBackgroundMetadata,
                            sprites = sceneSprites,
                            screenEffectId = pendingScreenEffectId,
                            screenEffectParameters = pendingScreenEffectParameters
                        )
                        if (command.overrapParameters != null && overrapPrepared) {
                            requestScreenCapture(
                                SystemNnnScreenCapturePurpose.OVERRAP_TARGET,
                                command.overrapParameters
                            )
                        }
                        return
                    }

                    is SystemNnnDecodedCommand.Dialogue -> {
                        validateNextOffset(currentScript, commandOffset, command.nextCommandOffset)
                        val text = currentScript.textEntryAtOffset(command.textEntryOffset)?.text
                            ?: throw IOException(
                                "Dialogue points to an unknown Shift-JIS text-pool offset."
                            )
                        nextCommandOffset = command.nextCommandOffset

                        validateNativeBankIdentity(
                            command.backgroundBankNamespace,
                            command.backgroundBankSlot
                        )
                        if (command.backgroundResourceIndex != null) {
                            if (command.backgroundResourceId != null) {
                                throw IOException("Dialogue has conflicting image resource references.")
                            }
                            pendingBackgroundResourceIndex = command.backgroundResourceIndex
                            pendingBackgroundResourceId = null
                            pendingBackgroundBankNamespace = command.backgroundBankNamespace
                            pendingBackgroundBankSlot = command.backgroundBankSlot
                        } else if (command.backgroundResourceId != null) {
                            pendingBackgroundResourceId = command.backgroundResourceId
                            pendingBackgroundResourceIndex = null
                            pendingBackgroundBankNamespace = command.backgroundBankNamespace
                            pendingBackgroundBankSlot = command.backgroundBankSlot
                        } else if (command.backgroundBankNamespace != null) {
                            throw IOException("A native image bank identity requires a background resource.")
                        }
                        if (pendingBackgroundResourceId != null) {
                            sceneBackgroundResourceId = pendingBackgroundResourceId
                            sceneBackgroundMetadata = queryImageMetadata(
                                resolver, pendingBackgroundResourceId, null,
                                pendingBackgroundBankNamespace, pendingBackgroundBankSlot
                            )
                            sceneBackground = resolveBackground(
                                pendingBackgroundResourceId!!,
                                pendingBackgroundBankNamespace,
                                pendingBackgroundBankSlot,
                                resolver
                            )
                        } else {
                            val backgroundIndex =
                                pendingBackgroundResourceIndex ?: command.backgroundResourceIndex
                            if (backgroundIndex != null) {
                                sceneBackgroundResourceId = "index:" + backgroundIndex
                                sceneBackgroundMetadata = queryImageMetadata(
                                    resolver, null, backgroundIndex,
                                    pendingBackgroundBankNamespace, pendingBackgroundBankSlot
                                )
                                sceneBackground = resolveBackgroundIndex(
                                    backgroundIndex,
                                    pendingBackgroundBankNamespace,
                                    pendingBackgroundBankSlot,
                                    resolver
                                )
                            }
                        }
                        if (command.sprites.isNotEmpty()) {
                            pendingSpriteLayers.clear()
                            command.sprites.forEachIndexed { index, reference ->
                                val layer = if (reference.layerIndex in 0 until MAX_IMAGE_LAYERS) {
                                    reference.layerIndex
                                } else {
                                    index
                                }
                                if (layer in 0 until MAX_IMAGE_LAYERS) {
                                    pendingSpriteLayers[layer] = reference.copy(layerIndex = layer)
                                }
                            }
                            pendingSpriteLayersChanged = true
                        }
                        if (pendingSpriteLayersChanged) {
                            val resolution = resolveSprites(
                                references = pendingSpriteLayers.values.sortedBy { it.layerIndex },
                                existingSprites = sceneSprites,
                                resolver = resolver
                            )
                            sceneSprites = resolution.sprites
                            appendImageStatusMessage(resolution.statusMessage)
                        }

                        visualLayerConfigurationStore.replaceWith(pendingLayerVisuals)
                        val dialogueStepIndex = command.dialogueStepIndex
                        val pendingWindowMode = nativeRuntimeState.nextMessageWindowPrintMode
                        if (pendingWindowMode != -1) {
                            nativeRuntimeState = nativeRuntimeState.copy(
                                messageWindowPrintFlag = when (pendingWindowMode) {
                                    0 -> false
                                    1 -> true
                                    else -> nativeRuntimeState.messageWindowPrintFlag
                                },
                                nextMessageWindowPrintMode = -1
                            )
                        }
                        frameCounter += 1L
                        uiState = SystemNnnPlayerUiState(
                            frameId = frameCounter,
                            sceneStepNumber = traceStepCounter,
                            lastCommandOffset = commandOffset,
                            isLoading = false,
                            backgroundBitmap = sceneBackground,
                            backgroundResourceId = sceneBackgroundResourceId,
                            backgroundMetadata = sceneBackgroundMetadata,
                            sprites = sceneSprites,
                            speakerName = command.speakerName,
                            dialogueText = text,
                            visibleCharacterCount = 0,
                            isTextComplete = text.isEmpty(),
                            forceReveal = false,
                            currentFilmNumber = currentFilmNumber,
                            statusMessage = imageStatusMessage,
                            screenEffectId = pendingScreenEffectId,
                            screenEffectParameters = pendingScreenEffectParameters,
                            screenEffectCommandOffset = pendingScreenEffectCommandOffset,
                            nativeRuntime = nativeRuntimeState
                        )
                        recordSceneTrace(
                            event = "dialogue_frame",
                            commandOffset = commandOffset,
                            commandName = "Dialogue",
                            commandDetails = traceCommandDetails(command),
                            backgroundBitmap = sceneBackground,
                            backgroundResourceId = sceneBackgroundResourceId,
                            backgroundMetadata = sceneBackgroundMetadata,
                            sprites = sceneSprites,
                            screenEffectId = pendingScreenEffectId,
                            screenEffectParameters = pendingScreenEffectParameters
                        )
                        playVoice(dialogueStepIndex, command.voiceResourceId)
                        return
                    }

                    is SystemNnnDecodedCommand.Choice -> {
                        val promptText = command.promptText ?: command.promptTextEntryOffset?.let { offset ->
                            currentScript.textEntryAtOffset(offset)?.text
                                ?: throw IOException(
                                    "Choice prompt points to an unknown Shift-JIS text-pool offset."
                                )
                        }.orEmpty()
                        if (command.options.isEmpty()) {
                            throw IOException("Choice command has no selectable options.")
                        }
                        if (command.options.map { it.id }.distinct().size != command.options.size) {
                            throw IOException("Choice command contains duplicate option IDs.")
                        }
                        command.options.forEach { option ->
                            if (!isValidCommandOffset(currentScript, option.targetCommandOffset)) {
                                throw IOException("Choice target is outside the command region.")
                            }
                        }

                        if (pendingBackgroundResourceIndex != null) {
                            sceneBackgroundResourceId = "index:" + pendingBackgroundResourceIndex
                            sceneBackgroundMetadata = queryImageMetadata(
                                resolver, null, pendingBackgroundResourceIndex,
                                pendingBackgroundBankNamespace, pendingBackgroundBankSlot
                            )
                            sceneBackground = resolveBackgroundIndex(
                                pendingBackgroundResourceIndex!!,
                                pendingBackgroundBankNamespace,
                                pendingBackgroundBankSlot,
                                resolver
                            )
                        } else if (pendingBackgroundResourceId != null) {
                            sceneBackgroundResourceId = pendingBackgroundResourceId
                            sceneBackgroundMetadata = queryImageMetadata(
                                resolver, pendingBackgroundResourceId, null,
                                pendingBackgroundBankNamespace, pendingBackgroundBankSlot
                            )
                            sceneBackground = resolveBackground(
                                pendingBackgroundResourceId!!,
                                pendingBackgroundBankNamespace,
                                pendingBackgroundBankSlot,
                                resolver
                            )
                        }
                        if (pendingSpriteLayersChanged) {
                            val resolution = resolveSprites(
                                references = pendingSpriteLayers.values.sortedBy { it.layerIndex },
                                existingSprites = sceneSprites,
                                resolver = resolver
                            )
                            sceneSprites = resolution.sprites
                            appendImageStatusMessage(resolution.statusMessage)
                        }

                        visualLayerConfigurationStore.replaceWith(pendingLayerVisuals)
                        frameCounter += 1L
                        uiState = SystemNnnPlayerUiState(
                            frameId = frameCounter,
                            sceneStepNumber = traceStepCounter,
                            lastCommandOffset = commandOffset,
                            isLoading = false,
                            backgroundBitmap = sceneBackground,
                            backgroundResourceId = sceneBackgroundResourceId,
                            backgroundMetadata = sceneBackgroundMetadata,
                            sprites = sceneSprites,
                            speakerName = command.speakerName,
                            dialogueText = promptText,
                            visibleCharacterCount = promptText.length,
                            isTextComplete = true,
                            forceReveal = true,
                            choices = command.options,
                            currentFilmNumber = currentFilmNumber,
                            statusMessage = imageStatusMessage,
                            screenEffectId = pendingScreenEffectId,
                            screenEffectParameters = pendingScreenEffectParameters,
                            screenEffectCommandOffset = pendingScreenEffectCommandOffset,
                            nativeRuntime = nativeRuntimeState
                        )
                        recordSceneTrace(
                            event = "choice_frame",
                            commandOffset = commandOffset,
                            commandName = "Choice",
                            commandDetails = traceCommandDetails(command),
                            backgroundBitmap = sceneBackground,
                            backgroundResourceId = sceneBackgroundResourceId,
                            backgroundMetadata = sceneBackgroundMetadata,
                            sprites = sceneSprites,
                            screenEffectId = pendingScreenEffectId,
                            screenEffectParameters = pendingScreenEffectParameters
                        )
                        return
                    }

                    SystemNnnDecodedCommand.End -> {
                        stopAllAudio()
                        uiState = SystemNnnPlayerUiState(
                            frameId = frameCounter + 1L,
                            sceneStepNumber = traceStepCounter,
                            lastCommandOffset = commandOffset,
                            isEnded = true,
                            currentFilmNumber = currentFilmNumber,
                            nativeRuntime = nativeRuntimeState
                        )
                        recordSceneTrace(
                            event = "scene_end",
                            commandOffset = commandOffset,
                            commandName = "End",
                            commandDetails = traceCommandDetails(command)
                        )
                        return
                    }
                }
            }

            throw IOException("Too many scene-only commands without a dialogue or choice.")
        } catch (error: Exception) {
            diagnosticLog?.recordException(
                "scene_advance_failure",
                error,
                "module=${currentModuleName ?: "standalone"} film=${currentFilmNumber ?: "none"} " +
                    "nextOffset=0x${nextCommandOffset.toString(16)}"
            )
            updateDiagnosticSnapshot(nextCommandOffset, "failure")
            releaseUnpublishedBitmaps()
            uiState = uiState.copy(
                isLoading = false,
                errorMessage = error.message ?: "Не удалось декодировать следующую команду."
            )
        }
    }


    private fun switchModule(
        targetModuleName: String,
        kind: SystemNnnModuleTransferKind,
        callerContinuation: Long
    ) {
        val currentScript = script ?: throw IOException("Нет активного SPT для межмодульного перехода.")
        val currentStream = commandStream ?: throw IOException("Нет командного потока активного SPT.")
        val currentDecoder = decoder ?: throw IOException("Нет декодера активного SPT.")
        val currentName = currentModuleName ?: throw IOException("Межмодульный переход недоступен вне project.xtx.")
        val factory = moduleDecoderFactory
            ?: throw IOException("Для этого одиночного SPT не загружен каталог модулей.")

        val target = projectModules[targetModuleName.lowercase(Locale.ROOT)]
            ?: throw IOException("Модуль '$targetModuleName' отсутствует в project.xtx.")

        val targetStream = target.commandStream
        if (target.initialCommandOffset != targetStream.recordEndOffsetExclusive &&
            targetStream.records.binarySearchBy(target.initialCommandOffset) { it.offset } < 0
        ) {
            throw IOException("Точка входа модуля '${target.name}' не является границей команды.")
        }
        val targetDecoder = factory(target)

        if (kind != SystemNnnModuleTransferKind.SCRIPTJUMP) {
            // sys.spt is native run-mode slot 0; a direct scene route starts in
            // the main-script slot 1. Slot 2 is the final nested SPT executor.
            val maxCallDepth = if (sceneReplayModuleName != null) {
                MAX_SCENE_REPLAY_MODULE_CALL_DEPTH
            } else {
                MAX_NESTED_MODULE_CALL_DEPTH
            }
            if (moduleCallStack.size >= maxCallDepth) {
                throw IOException("Превышена максимальная глубина вложенных SPT-модулей.")
            }
            moduleCallStack += ModuleCallFrame(
                moduleName = currentName,
                script = currentScript,
                commandStream = currentStream,
                decoder = currentDecoder,
                nextCommandOffset = callerContinuation,
                currentFilmNumber = currentFilmNumber
            )
        }

        script = target.script
        commandStream = targetStream
        decoder = targetDecoder
        nextCommandOffset = target.initialCommandOffset
        currentModuleName = target.name
        currentFilmNumber = null
    }

    private fun returnToCaller() {
        val caller = moduleCallStack.removeLastOrNull()
        if (caller == null) {
            if (sceneReplayModuleName != null) {
                finishSceneReplay()
                return
            }
            throw IOException("NEXT встретился без активного родительского SPT-модуля.")
        }
        script = caller.script
        commandStream = caller.commandStream
        decoder = caller.decoder
        nextCommandOffset = caller.nextCommandOffset
        currentModuleName = caller.moduleName
        currentFilmNumber = caller.currentFilmNumber
    }


    private fun advanceSceneReplayFilm() {
        val next = sceneReplayFilms.getOrNull(sceneReplayFilmCursor)
        if (next == null) {
            finishSceneReplay()
            return
        }
        val moduleName = sceneReplayModuleName
            ?: throw IOException("Scene replay has no owning SPT module.")
        val program = projectModules[moduleName.lowercase(Locale.ROOT)]
            ?: throw IOException("Scene replay module '$moduleName' is absent from project.xtx.")
        val factory = moduleDecoderFactory
            ?: throw IOException("Scene replay has no project decoder factory.")

        // ENDFILM ends this film at the outer scheduler. Each next film starts
        // at its own descriptor-3 entry with a fresh per-module local SUB stack.
        moduleCallStack.clear()
        script = program.script
        commandStream = program.commandStream
        decoder = factory(program)
        nextCommandOffset = next.commandOffset
        currentModuleName = program.name
        currentFilmNumber = next.filmIndex + 1
        sceneReplayFilmCursor += 1
    }

    private fun finishSceneReplay() {
        sceneReplayModuleName = null
        sceneReplayFilms = emptyList()
        sceneReplayFilmCursor = 0
        moduleCallStack.clear()
        stopAllAudio()
        uiState = SystemNnnPlayerUiState(
            frameId = frameCounter + 1L,
            isEnded = true,
            currentFilmNumber = currentFilmNumber,
            nativeRuntime = nativeRuntimeState
        )
    }

    private fun resetControllerOrEnd() {
        if (sceneReplayModuleName != null) {
            finishSceneReplay()
            return
        }

        val controller = controllerProgram
        val factory = moduleDecoderFactory
        if (controller == null || factory == null) {
            stopAllAudio()
            uiState = SystemNnnPlayerUiState(
                frameId = frameCounter + 1L,
                isEnded = true,
                currentFilmNumber = currentFilmNumber,
                nativeRuntime = nativeRuntimeState
            )
            return
        }

        moduleCallStack.clear()
        sceneReplayModuleName = null
        sceneReplayFilms = emptyList()
        sceneReplayFilmCursor = 0
        val stream = controller.commandStream
        script = controller.script
        commandStream = stream
        decoder = factory(controller)
        nextCommandOffset = controller.initialCommandOffset
        currentModuleName = controller.name
        currentFilmNumber = null
    }

    /**
     * Releases resources decoded for a frame that was never published. The
     * current UI state remains the owner of its displayed background/sprites.
     */
    private fun releaseUnpublishedBitmaps() {
        val published = IdentityHashMap<Bitmap, Boolean>()
        uiState.backgroundBitmap?.let { published[it] = true }
        uiState.sprites.forEach { published[it.bitmap] = true }

        bitmapOwners.keys.toList().forEach { bitmap ->
            if (!published.containsKey(bitmap)) {
                releaseUnpublishedBitmap(bitmap)
            }
        }
    }

    private data class SpriteResolution(
        val sprites: List<SystemNnnSpriteBitmap>,
        val statusMessage: String?
    )

    /**
     * Resolves only changed layers. A missing or ambiguous image removes that
     * layer but never blocks the next dialogue line.
     */
    private fun resolveSprites(
        references: List<SystemNnnSpriteReference>,
        existingSprites: List<SystemNnnSpriteBitmap>,
        resolver: SystemNnnSceneAssetResolver
    ): SpriteResolution {
        val existingByLayer = existingSprites.associateBy { it.layerIndex }
        val resolved = ArrayList<SystemNnnSpriteBitmap>(references.size)
        val warnings = ArrayList<String>()
        var activePixelCount = 0L

        references.forEach { reference ->
            val layer = reference.layerIndex
            if (layer !in 0 until MAX_IMAGE_LAYERS) {
                warnings += "Invalid image layer $layer was ignored."
                return@forEach
            }
            val existing = existingByLayer[layer]
            if (
                existing != null &&
                existing.resourceId == reference.resourceId &&
                existing.centerXFraction == reference.centerXFraction &&
                existing.widthFraction == reference.widthFraction &&
                existing.heightFraction == reference.heightFraction &&
                existing.nativeBankNamespace == reference.nativeBankNamespace &&
                existing.nativeBankSlot == reference.nativeBankSlot &&
                existing.nativeRect == reference.nativeRect &&
                existing.nativeRectFrom == reference.nativeRectFrom &&
                existing.nativeEffectId == reference.nativeEffectId &&
                existing.nativeEffectParameters == reference.nativeEffectParameters &&
                existing.nativeEffectCommandOffset == reference.nativeEffectCommandOffset &&
                existing.sourceMetadata == queryImageMetadata(
                    resolver,
                    reference.resourceId,
                    null,
                    reference.nativeBankNamespace,
                    reference.nativeBankSlot
                )
            ) {
                diagnosticLog?.record(
                    "layer_render_state",
                    "layer=${layer} resource=${existing.resourceId} effect=${existing.nativeEffectId ?: "none"} " +
                        "params=${existing.nativeEffectParameters.joinToString(",")} " +
                        "rawBitmapBlit=${SystemNnnVisualEffectMath.shouldDrawPlainLayerBitmap(existing.nativeEffectId, existing.nativeEffectParameters)} " +
                        "bitmap=${existing.bitmap.width}x${existing.bitmap.height} " +
                        "nativeRect=${existing.nativeRect}"
                )
                resolved += existing
                activePixelCount += existing.bitmap.width.toLong() * existing.bitmap.height.toLong()
                return@forEach
            }

            val metadata = queryImageMetadata(
                resolver,
                reference.resourceId,
                null,
                reference.nativeBankNamespace,
                reference.nativeBankSlot
            )
            if (metadata == null) {
                diagnosticLog?.recordTrace(
                    "image_metadata_unavailable",
                    mapOf(
                        "layerIndex" to layer,
                        "resourceName" to reference.resourceId,
                        "bankNamespace" to reference.nativeBankNamespace,
                        "bankSlot" to reference.nativeBankSlot,
                        "reason" to "Resolver supplied no indexed DWQ metadata; bitmap dimensions remain authoritative"
                    )
                )
            }
            val bitmap = runCatching {
                validateNativeBankIdentity(reference.nativeBankNamespace, reference.nativeBankSlot)
                if (reference.nativeBankNamespace == null) {
                    loadBitmap(reference.resourceId, resolver)
                } else {
                    loadBitmapFromNativeBank(
                        reference.nativeBankNamespace,
                        checkNotNull(reference.nativeBankSlot),
                        reference.resourceId,
                        resolver
                    )
                }
            }.getOrElse { failure ->
                diagnosticLog?.recordException(
                    "image_decode_failure",
                    failure,
                    "layer=$layer resource=${reference.resourceId} " +
                        "bank=${reference.nativeBankNamespace ?: "default"} slot=${reference.nativeBankSlot ?: "none"}"
                )
                warnings += "Layer $layer ('${reference.resourceId}'): " +
                    (failure.message ?: failure.javaClass.simpleName)
                null
            }

            if (bitmap == null) {
                if (warnings.none { it.contains("Layer $layer") }) {
                    warnings += "Layer $layer resource '${reference.resourceId}' was not found."
                }
                return@forEach
            }
            if (metadata != null &&
                (metadata.widthPixels != bitmap.width || metadata.heightPixels != bitmap.height)
            ) {
                diagnosticLog?.recordTrace(
                    "scene_anomaly",
                    mapOf(
                        "type" to "decoded_image_size_mismatch",
                        "layerIndex" to layer,
                        "resourceName" to reference.resourceId,
                        "declared" to "${metadata.widthPixels}x${metadata.heightPixels}",
                        "decoded" to "${bitmap.width}x${bitmap.height}"
                    )
                )
            }
            val bitmapPixels = bitmap.width.toLong() * bitmap.height.toLong()
            if (bitmapPixels > MAX_ACTIVE_SPRITE_PIXELS - activePixelCount) {
                bitmapOwners.remove(bitmap)
                runCatching { resolver.releaseBitmap(bitmap) }.onFailure { failure ->
                    diagnosticLog?.recordException(
                        "bitmap_release_failure",
                        failure,
                        "layer=$layer resource=${reference.resourceId}"
                    )
                }
                val warning = "Layer " + layer + " ('" + reference.resourceId + "') was skipped: active sprite pixel budget exceeded."
                diagnosticLog?.recordTrace(
                    "scene_fallback",
                    mapOf("kind" to "active_sprite_pixel_budget", "layerIndex" to layer, "resourceName" to reference.resourceId, "pixels" to bitmapPixels)
                )
                warnings += warning
                return@forEach
            }
            activePixelCount += bitmapPixels
            diagnosticLog?.record(
                "layer_render_state",
                "layer=${layer} resource=${reference.resourceId} effect=${reference.nativeEffectId ?: "none"} " +
                    "params=${reference.nativeEffectParameters.joinToString(",")} " +
                    "rawBitmapBlit=${SystemNnnVisualEffectMath.shouldDrawPlainLayerBitmap(reference.nativeEffectId, reference.nativeEffectParameters)} " +
                    "bitmap=${bitmap.width}x${bitmap.height} nativeRect=${reference.nativeRect}"
            )
            resolved += SystemNnnSpriteBitmap(
                resourceId = reference.resourceId,
                bitmap = bitmap,
                centerXFraction = reference.centerXFraction,
                widthFraction = reference.widthFraction,
                heightFraction = reference.heightFraction,
                contentDescription = reference.contentDescription,
                layerIndex = layer,
                nativeRect = reference.nativeRect ?: if (reference.contentDescription?.startsWith("Image layer ") == true) {
                    SystemNnnNativeRect(0, 0, bitmap.width, bitmap.height)
                } else null,
                nativeBankNamespace = reference.nativeBankNamespace,
                nativeBankSlot = reference.nativeBankSlot,
                nativeRectFrom = reference.nativeRectFrom,
                nativeEffectId = reference.nativeEffectId,
                nativeEffectParameters = reference.nativeEffectParameters,
                nativeEffectCommandOffset = reference.nativeEffectCommandOffset,
                sourceMetadata = metadata
            )
        }

        val resolvedByLayer = resolved.associateBy { it.layerIndex }
        references.filter { it.nativeEffectId == 52 }.forEach { reference ->
            if (reference.nativeRectFrom == null || reference.nativeRect == null) {
                warnings += "MOVE (52) on layer ${reference.layerIndex} has no SETEFFECTRECT destination and cannot move yet."
            }
        }
        references.filter { it.nativeEffectId == 40 }.forEach { reference ->
            val partnerLayer = reference.nativeEffectParameters.getOrNull(1)
            val partner = partnerLayer?.let(resolvedByLayer::get)
            val primary = resolvedByLayer[reference.layerIndex]
            when {
                partnerLayer == null -> Unit // Reported when the command is decoded.
                partnerLayer == reference.layerIndex -> warnings +=
                    "CHANGE (40) on layer " + reference.layerIndex + " cannot use the same layer as both transition images."
                partner == null -> warnings +=
                    "CHANGE (40) on layer ${reference.layerIndex} cannot run: second layer ${partnerLayer} is missing."
                primary != null && (
                    partner.bitmap.width != primary.bitmap.width ||
                        partner.bitmap.height != primary.bitmap.height
                ) -> warnings +=
                    "CHANGE (40) on layer ${reference.layerIndex} cannot run: both layer images must have the same dimensions."
            }
        }
        warnings.forEach { warning ->
            diagnosticLog?.recordTrace(
                "scene_fallback",
                mapOf("step" to traceStepCounter, "warning" to warning),
                summary = warning
            )
        }
        return SpriteResolution(
            sprites = resolved.sortedBy { it.layerIndex },
            statusMessage = warnings.takeIf { it.isNotEmpty() }
                ?.joinToString(separator = "\n")
        )
    }

    private fun validateNextOffset(
        currentScript: SystemNnnScript,
        currentOffset: Long,
        nextOffset: Long
    ) {
        if (!isValidCommandOffset(currentScript, currentOffset) ||
            nextOffset == currentOffset ||
            !isValidCommandCursor(currentScript, nextOffset)
        ) {
            throw IOException("Decoded command target is not a valid SPT record boundary.")
        }
    }

    private fun isValidCommandCursor(currentScript: SystemNnnScript, offset: Long): Boolean {
        val stream = commandStream ?: return false
        if (script !== currentScript) return false
        if (offset == stream.recordEndOffsetExclusive) return true
        return stream.records.binarySearchBy(offset) { it.offset } >= 0
    }

    private fun isValidCommandOffset(currentScript: SystemNnnScript, offset: Long): Boolean {
        val stream = commandStream ?: return false
        return offset != stream.recordEndOffsetExclusive &&
            isValidCommandCursor(currentScript, offset)
    }

    private fun releaseAssets() {
        commandStream = null
        moduleCallStack.clear()
        sceneReplayModuleName = null
        sceneReplayFilms = emptyList()
        sceneReplayFilmCursor = 0
        audioRequestId += 1L
        musicRequestId += 1L
        val player = audioPlayer
        audioPlayer = null
        runCatching { player?.close() }.onFailure { failure ->
            diagnosticLog?.recordException("audio_player_close_failure", failure)
        }
        channelAudioPlayers.values.forEach { channelPlayer ->
            runCatching { channelPlayer.close() }.onFailure { failure ->
                diagnosticLog?.recordException("channel_audio_player_close_failure", failure)
            }
        }
        channelAudioPlayers.clear()
        channelAudioRequestIds.clear()

        val audio = audioRepository
        audioRepository = null
        runCatching { audio?.close() }.onFailure { failure ->
            diagnosticLog?.recordException("audio_repository_close_failure", failure)
        }

        runCatching { assets?.close() }.onFailure { failure ->
            diagnosticLog?.recordException("asset_resolver_close_failure", failure)
        }
        assets = null
    }

    override fun onCleared() {
        uiState = SystemNnnPlayerUiState()
        releaseAssets()
        voiceExecutor.shutdownNow()
        super.onCleared()
    }

    companion object {
        private const val MAX_SCENE_COMMANDS_PER_ADVANCE = 2_048
        private const val MAX_IMAGE_LAYERS = 32
        private const val MAX_IMAGE_WARNING_COUNT = 4
        private const val SCREEN_EFFECT_LAYER_INDEX = 31
        private val SCREEN_BUFFER_EFFECT_IDS = setOf(
            2, 10, 11, 18, 20, 22, 29, 31, 43, 50, 54, 55, 69, 71, 74
        )
        private const val DEFAULT_NATIVE_FRAME_TIME_MILLIS = 50
        private const val DEFAULT_OVERRAP_LENGTH_FRAMES = 18
        private const val MAX_ACTIVE_SPRITE_PIXELS = 24_000_000L
        // Native gss.exe exposes run-mode slots 0..2: sys/controller, main,
        // and one nested SPT executor. A direct scene starts in slot 1.
        private const val MAX_NESTED_MODULE_CALL_DEPTH = 2
        private const val MAX_SCENE_REPLAY_MODULE_CALL_DEPTH = 1
    }
}
