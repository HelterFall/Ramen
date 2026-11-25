package io.github.helterfall.ramen.runtime.livemaker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.github.helterfall.ramen.inspection.ReadOnlyRandomAccessSource
import io.github.helterfall.ramen.runtime.systemnnn.SystemNnnAudioPlayer
import io.github.helterfall.ramen.runtime.systemnnn.SystemNnnAudioPlaybackState
import io.github.helterfall.ramen.runtime.systemnnn.SystemNnnAudioPlaybackStatus
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

private data class LiveMakerPlayerUiState(
    val isLoading: Boolean = true,
    val pageReference: String? = null,
    val scriptVersion: Int? = null,
    val commandCount: Int? = null,
    val eventDescription: String = "Подготовка LiveMaker-сессии…",
    val canContinue: Boolean = false,
    val dialogueText: String = "",
    val speakerName: String? = null,
    val messageWindowVisible: Boolean = true,
    /** Virtual LiveMaker stage size used by all scene layers and system variables. */
    val sceneCanvasWidthPx: Float = 1f,
    val sceneCanvasHeightPx: Float = 1f,
    val waitingForTap: Boolean = false,
    val sceneImages: List<LiveMakerSceneBitmap> = emptyList(),
    val sceneComponents: List<LiveMakerSceneComponent> = emptyList(),
    val historyEntries: List<LiveMakerTextHistoryEntry> = emptyList(),
    val historyFormatName: String? = null,
    val historyVisible: Boolean = false,
    val resourcesVisible: Boolean = false,
    val isCompleted: Boolean = false,
    val assets: List<LiveMakerMediaAsset> = emptyList(),
    val mediaDiagnostics: LiveMakerMediaIndexDiagnostics? = null,
    val imageBitmap: Bitmap? = null,
    val selectedAssetName: String? = null,
    val audioStatus: String? = null,
    val errorMessage: String? = null,
    val errorLog: String? = null
)

private data class LiveMakerSceneBitmap(
    val objectName: String,
    val resourcePath: String,
    val bitmap: Bitmap,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val alpha: Float,
    val priority: Float,
    val isBackground: Boolean,
    val visible: Boolean = true,
    val transitionFromAlpha: Float? = null,
    val transitionDurationMillis: Int = 0,
    val transitionToken: Long = 0L,
    val transitionMoveType: String = "N"
)

private data class LiveMakerTextPlayback(
    val event: LiveMakerVmEvent.TextBlock,
    val segments: List<LiveMakerTextSegment>,
    var nextSegmentIndex: Int = 0,
    var currentText: String = "",
    var waitingForTap: Boolean = false
)

private data class LiveMakerEventPresentation(
    val description: String,
    val canContinue: Boolean,
    val dialogueText: String = "",
    val waitingForTap: Boolean = false,
    /** Return to the Android event loop and continue the VM on the next frame. */
    val autoAdvanceOnFrame: Boolean = false,
    /** Continue after a blocking visual transition has finished. */
    val autoAdvanceDelayMs: Long? = null
)

/** Android host for the compiled LiveMaker LSB runtime. */
class LiveMakerPlayerActivity : ComponentActivity() {
    private val uiState = mutableStateOf(LiveMakerPlayerUiState())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val automaticAdvanceScheduled = AtomicBoolean(false)
    private var flipTransitionSerial = 0L
    @Volatile private var pointerPulsePendingRelease = false
    @Volatile private var lastPointerX = 0
    @Volatile private var lastPointerY = 0
    private var liveSession: LiveMakerGameSession? = null
    private var runtimeEnvironment: LiveMakerSystemEnvironment? = null
    private var currentEvent: LiveMakerSessionEvent? = null
    private var mediaRepository: LiveMakerMediaAssetRepository? = null
    private var audioPlayer: SystemNnnAudioPlayer? = null
    private var textPlayback: LiveMakerTextPlayback? = null
    private val historyHost = LiveMakerTextHistoryHostAdapter()
    private var historyRequested = false
    private val sceneObjects = linkedMapOf<String, LiveMakerSceneBitmap>()
    private val soundObjects = linkedMapOf<String, LiveMakerMediaAsset>()
    private val audioStatusMonitor = Object()
    @Volatile private var lastAudioStatus: SystemNnnAudioPlaybackStatus? = null
    @Volatile private var currentAudioChannel: String? = null
    @Volatile private var currentAudioResourceName: String? = null
    @Volatile private var waitingForTpWordPlayback = false
    @Volatile private var tpWordPlaybackCanSkip = false
    @Volatile private var skipTpWordPlaybackRequested = false
    @Volatile private var messageWindowVisible = true
    @Volatile private var currentSpeakerName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyImmersiveGameMode()
        uiState.value = uiState.value.copy(
            sceneCanvasWidthPx = resources.displayMetrics.widthPixels.toFloat().coerceAtLeast(1f),
            sceneCanvasHeightPx = resources.displayMetrics.heightPixels.toFloat().coerceAtLeast(1f)
        )
        setContent {
            MaterialTheme {
                LiveMakerPlayerScreen(
                    state = uiState.value,
                    onContinue = ::continueSession,
                    onCanvasTap = ::onCanvasTap,
                    onAssetSelected = ::selectAsset,
                    onHistoryClick = ::showHistory,
                    onHistoryDismiss = ::dismissHistory,
                    onResourcesClick = ::showResources,
                    onResourcesDismiss = ::dismissResources,
                    onCopyErrorLog = ::copyErrorLog
                )
            }
        }

        val gameId = intent.getStringExtra(EXTRA_GAME_ID)
        if (gameId.isNullOrBlank()) {
            uiState.value = uiState.value.copy(
                isLoading = false,
                errorMessage = "Не передан идентификатор игры из библиотеки."
            )
            return
        }

        worker.execute {
            var preparedSession: LiveMakerGameSession? = null
            var preparedAssets: LiveMakerMediaAssetRepository? = null
            try {
                val gameDirectory = resolvePrivateGameDirectory(gameId)
                val systemEnvironment = LiveMakerSystemEnvironment(
                    screenWidthPx = resources.displayMetrics.widthPixels,
                    screenHeightPx = resources.displayMetrics.heightPixels,
                    title = applicationInfo.loadLabel(packageManager).toString(),
                    isDebug = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                )
                systemEnvironment.pcTickCountResolver = { android.os.SystemClock.elapsedRealtime() }
                runtimeEnvironment = systemEnvironment
                val bootstrap = LiveMakerStartupScriptResolver(gameDirectory).openSession(
                    systemEnvironment = systemEnvironment,
                    textHistoryHost = historyHost
                )
                preparedSession = bootstrap.session
                val assets = LiveMakerMediaAssetRepository(gameDirectory)
                preparedAssets = assets
                bootstrap.session.setAdditionalFileExistsResolver { assets.containsResource(it) }
                // Host-command dispatch needs the repositories before the first VM step.
                liveSession = bootstrap.session
                mediaRepository = assets
                val first = nextPresentation(bootstrap.session)
                val firstEvent = first.first
                val presentation = first.second
                val historySnapshot = historyHost.snapshot()
                mainHandler.post {
                    if (isFinishing || isDestroyed) {
                        bootstrap.session.close()
                        assets.close()
                    } else {
                        uiState.value = LiveMakerPlayerUiState(
                            isLoading = false,
                            pageReference = bootstrap.pageReference,
                            scriptVersion = bootstrap.scriptVersion,
                            commandCount = bootstrap.commandCount,
                            eventDescription = presentation.description,
                            canContinue = presentation.canContinue,
                            dialogueText = presentation.dialogueText,
                            speakerName = currentSpeakerName,
                            messageWindowVisible = messageWindowVisible,
                            sceneCanvasWidthPx = systemEnvironment.screenWidthPx.toFloat().coerceAtLeast(1f),
                            sceneCanvasHeightPx = systemEnvironment.screenHeightPx.toFloat().coerceAtLeast(1f),
                            waitingForTap = presentation.waitingForTap,
                            sceneImages = sceneObjects.values.sortedBy { it.priority },
                            sceneComponents = bootstrap.session.objectStore.snapshotAll()
                                .map(LiveMakerSceneComponent::from)
                                .filter { it.isRenderable },
                            historyEntries = historySnapshot.entries,
                            historyFormatName = historySnapshot.activeFormat?.name,
                            historyVisible = historyRequested,
                            isCompleted = isCompleted(firstEvent),
                            assets = assets.listAssets(),
                            mediaDiagnostics = assets.diagnostics
                        )
                        schedulePresentationContinuation(presentation)
                    }
                }
            } catch (error: Exception) {
                // Media indexing is eager and may succeed before the first TpWord image fails
                // to decode. Keep its inventory and diagnostics visible on the error screen.
                val indexedAssets = preparedAssets?.listAssets().orEmpty()
                val mediaDiagnostics = preparedAssets?.diagnostics
                preparedSession?.close()
                preparedAssets?.close()
                mainHandler.post {
                    if (!isFinishing && !isDestroyed) {
                        uiState.value = uiState.value.copy(
                            isLoading = false,
                            eventDescription = "Запуск остановлен до первого события VM.",
                            canContinue = false,
                            waitingForTap = false,
                            assets = indexedAssets,
                            mediaDiagnostics = mediaDiagnostics,
                            errorMessage = error.message ?: error.javaClass.simpleName,
                            errorLog = runCatching { error.stackTraceToString() }
                                .getOrDefault(error.toString()).take(MAX_ERROR_LOG_CHARS)
                        )
                    }
                }
            }
        }
    }

    private fun continueSession() {
        // WAITPLAY with the CLICK option is skippable. Set this on the caller thread:
        // the single VM worker may currently be blocked waiting for MediaPlayer.
        if (waitingForTpWordPlayback && tpWordPlaybackCanSkip) {
            skipTpWordPlaybackRequested = true
            synchronized(audioStatusMonitor) { audioStatusMonitor.notifyAll() }
            return
        }
        worker.execute {
            val session = liveSession ?: return@execute
            try {
                advanceSession(session)
            } catch (error: Exception) {
                publishError(error)
            }
        }
    }

    /** Handles a user tap on the virtual stage, including LiveMaker's global mouse state. */
    private fun onCanvasTap(x: Float, y: Float) {
        if (waitingForTpWordPlayback && tpWordPlaybackCanSkip) {
            skipTpWordPlaybackRequested = true
            synchronized(audioStatusMonitor) { audioStatusMonitor.notifyAll() }
            return
        }
        worker.execute {
            val session = liveSession ?: return@execute
            val pointerX = x.roundToInt().coerceAtLeast(0)
            val pointerY = y.roundToInt().coerceAtLeast(0)
            lastPointerX = pointerX
            lastPointerY = pointerY
            pointerPulsePendingRelease = true
            session.updatePointerState(pointerX, pointerY, leftClick = true, leftPush = true)
            try {
                if (uiState.value.canContinue) {
                    advanceSession(session)
                } else {
                    // Bitmap components (ImgNew) are rendered separately from their model
                    // objects. Hit-test the shared object store so invisible hotspots and
                    // image-backed buttons still receive their configured event handlers.
                    val target = session.objectStore.snapshotAll()
                        .asSequence()
                        .filter { snapshot ->
                            val component = LiveMakerSceneComponent.from(snapshot)
                            component.visible && component.clickable &&
                                component.width > 0f && component.height > 0f &&
                                x >= component.left && y >= component.top &&
                                x <= component.left + component.width &&
                                y <= component.top + component.height
                        }
                        .maxByOrNull { snapshot ->
                            LiveMakerSceneComponent.from(snapshot).priority
                        }

                    if (target != null) {
                        session.setObjectProperty(target.name, LiveMakerObjectStore.PR_SELECTED, true)
                        session.triggerObjectEvent(target.name, LiveMakerObjectStore.PR_ONLBTNDOWN) ||
                            session.triggerObjectEvent(target.name, LiveMakerObjectStore.PR_ONSELECT) ||
                            session.triggerObjectEvent(target.name, LiveMakerObjectStore.PR_ONLBTNUP)
                    }

                    // If we were parked at DoEvent/Running, resume the VM with the
                    // touch pulse still asserted so helper-page While loops can observe it.
                    when ((currentEvent as? LiveMakerSessionEvent.EngineEvent)?.event) {
                        is LiveMakerVmEvent.ProcessEvents -> session.resume()
                        LiveMakerVmEvent.Running -> Unit
                        else -> Unit
                    }
                    val (next, presentation) = nextPresentation(session)
                    if (!presentation.autoAdvanceOnFrame) releasePointerPulse(session)
                    publishPresentation(session, next, presentation)
                }
            } catch (error: Exception) {
                publishError(error)
            } finally {
                // Keep a click pulse alive across a DoEvent/Running frame boundary.
                // advanceSession releases it after the next VM slice has observed input.
                if (currentEvent !is LiveMakerSessionEvent.EngineEvent ||
                    (currentEvent as LiveMakerSessionEvent.EngineEvent).event !is LiveMakerVmEvent.ProcessEvents &&
                    (currentEvent as LiveMakerSessionEvent.EngineEvent).event != LiveMakerVmEvent.Running
                ) {
                    releasePointerPulse(session)
                }
            }
        }
    }

    private fun advanceSession(session: LiveMakerGameSession) {
        val playback = textPlayback
        if (playback != null && playback.waitingForTap) {
            playback.waitingForTap = false
            advanceTextPlayback(playback, session)
            if (playback.waitingForTap) {
                mainHandler.post {
                    if (!isFinishing && !isDestroyed) {
                        uiState.value = uiState.value.copy(
                            dialogueText = playback.currentText,
                            speakerName = currentSpeakerName,
                            messageWindowVisible = messageWindowVisible,
                            waitingForTap = true,
                            canContinue = true,
                            errorMessage = null
                        )
                    }
                }
                return
            }
            textPlayback = null
            session.resume()
        } else {
            when (val event = currentEvent) {
                is LiveMakerSessionEvent.EngineEvent -> when (event.event) {
                    is LiveMakerVmEvent.TextBlock,
                    is LiveMakerVmEvent.Delay,
                    is LiveMakerVmEvent.ProcessEvents,
                    is LiveMakerVmEvent.FlipTransition,
                    is LiveMakerVmEvent.PropertyMotion -> session.resume()
                    LiveMakerVmEvent.Running -> Unit
                    else -> return
                }
                else -> return
            }
        }

        val (next, presentation) = nextPresentation(session)
        if (pointerPulsePendingRelease && !presentation.autoAdvanceOnFrame) {
            releasePointerPulse(session)
        }
        publishPresentation(session, next, presentation)
    }

    private fun publishPresentation(
        session: LiveMakerGameSession,
        next: LiveMakerSessionEvent,
        presentation: LiveMakerEventPresentation
    ) {
        val images = sceneObjects.values.sortedBy { it.priority }
        val components = session.objectStore.snapshotAll()
            .map(LiveMakerSceneComponent::from)
            .filter { it.isRenderable }
        mainHandler.post {
            if (!isFinishing && !isDestroyed) {
                uiState.value = uiState.value.copy(
                    eventDescription = presentation.description,
                    canContinue = presentation.canContinue,
                    dialogueText = presentation.dialogueText,
                    speakerName = currentSpeakerName,
                    messageWindowVisible = messageWindowVisible,
                    waitingForTap = presentation.waitingForTap,
                    sceneImages = images,
                    sceneComponents = components,
                    isCompleted = isCompleted(next),
                    errorMessage = null
                )
                schedulePresentationContinuation(presentation)
            }
        }
    }

    /**
     * A DoEvent/Running boundary is a cooperative yield, not a player wait. Return to
     * Android for a frame so input, Compose drawing and animation time can progress.
     */
    private fun schedulePresentationContinuation(presentation: LiveMakerEventPresentation) {
        when {
            presentation.autoAdvanceDelayMs != null ->
                scheduleAutomaticAdvance(presentation.autoAdvanceDelayMs)
            presentation.autoAdvanceOnFrame -> scheduleAutomaticAdvance()
        }
    }

    private fun scheduleAutomaticAdvance(delayMillis: Long = EVENT_PUMP_FRAME_DELAY_MS) {
        if (!automaticAdvanceScheduled.compareAndSet(false, true)) return
        mainHandler.postDelayed({
            automaticAdvanceScheduled.set(false)
            if (!isFinishing && !isDestroyed) continueSession()
        }, delayMillis.coerceAtLeast(1L))
    }

    private fun releasePointerPulse(session: LiveMakerGameSession) {
        if (!pointerPulsePendingRelease) return
        pointerPulsePendingRelease = false
        session.updatePointerState(lastPointerX, lastPointerY, leftClick = false, leftPush = false)
    }

    private fun showHistory() {
        val snapshot = historyHost.snapshot()
        uiState.value = uiState.value.copy(
            historyEntries = snapshot.entries,
            historyFormatName = snapshot.activeFormat?.name,
            historyVisible = true
        )
    }

    private fun dismissHistory() {
        uiState.value = uiState.value.copy(historyVisible = false)
    }

    private fun showResources() {
        uiState.value = uiState.value.copy(resourcesVisible = true)
    }

    private fun dismissResources() {
        uiState.value = uiState.value.copy(resourcesVisible = false)
    }

    private fun publishHistory() {
        val snapshot = historyHost.snapshot()
        mainHandler.post {
            if (!isFinishing && !isDestroyed) {
                uiState.value = uiState.value.copy(
                    historyEntries = snapshot.entries,
                    historyFormatName = snapshot.activeFormat?.name
                )
            }
        }
    }

    /**
     * Consumes non-blocking state-only TextIns blocks and VM delays internally.
     * A TextIns whose LSB Wait flag is set is surfaced as an input boundary even when
     * its TpWord contains no visible characters; event-only blocks must not become dialogue.
     */
    private fun nextPresentation(
        session: LiveMakerGameSession
    ): Pair<LiveMakerSessionEvent, LiveMakerEventPresentation> {
        val recentSteps = ArrayDeque<String>()
        repeat(MAX_AUTOMATIC_HOST_COMMANDS) {
            val beforePage = session.currentPageReference().orEmpty()
            val beforeCommand = session.currentCommandIndex()
            val event = nextEvent(session)
            currentEvent = event
            val presentation = presentEvent(event, session)
            val engineEvent = (event as? LiveMakerSessionEvent.EngineEvent)?.event
            recentSteps.addLast(
                "$beforePage#$beforeCommand -> " +
                    (engineEvent?.javaClass?.simpleName ?: event.javaClass.simpleName)
            )
            if (recentSteps.size > 12) recentSteps.removeFirst()

            // Running means a long script/While loop consumed one VM slice. Yield
            // to Android now instead of burning all 512 presentation transitions.
            if (engineEvent == LiveMakerVmEvent.Running) {
                return event to LiveMakerEventPresentation(
                    description = "",
                    canContinue = false,
                    dialogueText = "",
                    waitingForTap = false,
                    autoAdvanceOnFrame = true
                )
            }

            if (engineEvent is LiveMakerVmEvent.ProcessEvents) {
                // DoEvent is a real event-pump boundary. The Android host gets a frame,
                // then resumes the VM after queued touch/draw work has been processed.
                return event to LiveMakerEventPresentation(
                    description = "",
                    canContinue = false,
                    dialogueText = "",
                    waitingForTap = false,
                    autoAdvanceOnFrame = true
                )
            }

            if (engineEvent is LiveMakerVmEvent.FlipTransition) {
                if (engineEvent.stopEventProcessing) {
                    return event to LiveMakerEventPresentation(
                        description = "",
                        canContinue = false,
                        autoAdvanceDelayMs = engineEvent.durationMillis
                    )
                }
                // The effect continues asynchronously while the scenario advances.
                session.resume()
                return@repeat
            }

            if (engineEvent is LiveMakerVmEvent.PropertyMotion) {
                if (engineEvent.paused == true) {
                    return event to LiveMakerEventPresentation(
                        description = "",
                        canContinue = false,
                        autoAdvanceDelayMs = engineEvent.durationMillis
                    )
                }
                // Non-blocking alpha motions run on the Compose renderer while the VM continues.
                session.resume()
                return@repeat
            }

            if (engineEvent is LiveMakerVmEvent.Delay) {
                if (engineEvent.milliseconds <= 0L) {
                    // Time=0 is an unbounded LiveMaker wait. Present an invisible input
                    // boundary on the virtual stage; do not resume into a busy loop.
                    return event to LiveMakerEventPresentation(
                        description = "",
                        canContinue = true,
                        dialogueText = "",
                        waitingForTap = true
                    )
                }
                Thread.sleep(engineEvent.milliseconds.coerceAtMost(MAX_TPWORD_WAIT_MS))
                session.resume()
                return@repeat
            }

            if (engineEvent is LiveMakerVmEvent.TextBlock &&
                presentation.dialogueText.isBlank() &&
                !presentation.waitingForTap
            ) {
                // CREATECG/MESON/audio/other controls have already been processed.
                // Resume automatically instead of displaying the command list.
                textPlayback = null
                session.resume()
                return@repeat
            }

            return event to presentation
        }
        throw IOException(
            "LiveMaker exceeded the automatic presentation budget after " +
                MAX_AUTOMATIC_HOST_COMMANDS + " internal transitions. Current page=" +
                session.currentPageReference() + ", command=" + session.currentCommandIndex() +
                ". Recent VM steps: " + recentSteps.joinToString(" | ")
        )
    }

    /**
     * Runs LiveNovel's FADE transition on named scene layers. Other wipe names currently
     * use a safe alpha cross-fade fallback instead of leaving an image permanently static.
     */
    private fun startFlipTransition(
        transition: LiveMakerVmEvent.FlipTransition,
        session: LiveMakerGameSession
    ) {
        val effect = transition.effect.uppercase()
        if (effect == "NONE") return

        val names = transition.targetNames.ifEmpty { sceneObjects.keys.toList() }
        if (names.isEmpty()) return

        val duration = transition.durationMillis.coerceIn(0L, MAX_TPWORD_WAIT_MS).toInt()
        // Act controls whether the affected object stays (FL_STAY/fade-in) or
        // leaves the screen (FL_OUT/fade-out). Reverse changes wipe direction, not Act.
        val fadeOut = transition.action.equals("FL_OUT", ignoreCase = true) ||
            transition.action.endsWith("_OUT", ignoreCase = true)
        val removeAfter = fadeOut || transition.deleteAfter
        val tokens = linkedMapOf<String, Long>()

        names.forEach { name ->
            val image = sceneObjects[name] ?: return@forEach
            val token = ++flipTransitionSerial
            val startAlpha = if (fadeOut) image.alpha else 0f
            val endAlpha = if (fadeOut) 0f else image.alpha.coerceIn(0f, 1f)
            sceneObjects[name] = image.copy(
                alpha = endAlpha,
                transitionFromAlpha = startAlpha,
                transitionDurationMillis = duration,
                transitionToken = token
            )
            session.setObjectProperty(
                name,
                LiveMakerObjectStore.PR_ALPHA,
                (endAlpha * 255f).roundToInt().coerceIn(0, 255)
            )
            tokens[name] = token
        }

        publishSceneState(session)
        if (duration == 0) {
            if (removeAfter) {
                tokens.keys.forEach { name ->
                    sceneObjects.remove(name)
                    session.deleteObject(name)
                }
                publishSceneState(session)
            }
            return
        }

        mainHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            worker.execute {
                tokens.forEach { (name, token) ->
                    val current = sceneObjects[name] ?: return@forEach
                    // A later Flip or image replacement owns this layer now.
                    if (current.transitionToken != token) return@forEach
                    if (removeAfter) {
                        sceneObjects.remove(name)
                        session.deleteObject(name)
                    } else {
                        sceneObjects[name] = current.copy(
                            transitionFromAlpha = null,
                            transitionDurationMillis = 0
                        )
                    }
                }
                publishSceneState(session)
            }
        }, duration.toLong())
    }

    /** Animates the alpha property of an image object without blocking the VM. */
    private fun startPropertyMotion(
        motion: LiveMakerVmEvent.PropertyMotion,
        session: LiveMakerGameSession
    ) {
        if (motion.propertyId != LiveMakerObjectStore.PR_ALPHA) {
            publishSceneState(session)
            return
        }
        val image = sceneObjects[motion.objectName]
        if (image == null) {
            publishSceneState(session)
            return
        }

        val startAlpha = sceneAlpha(motion.startValue, image.alpha)
        val targetAlpha = sceneAlpha(motion.targetValue, image.alpha)
        val duration = motion.durationMillis.coerceIn(0L, MAX_TPWORD_WAIT_MS).toInt()
        val token = ++flipTransitionSerial
        sceneObjects[motion.objectName] = image.copy(
            alpha = targetAlpha,
            transitionFromAlpha = startAlpha,
            transitionDurationMillis = duration,
            transitionToken = token,
            transitionMoveType = motion.moveType
        )
        publishSceneState(session)

        if (duration == 0) {
            sceneObjects[motion.objectName]?.let { current ->
                if (current.transitionToken == token) {
                    sceneObjects[motion.objectName] = current.copy(
                        transitionFromAlpha = null,
                        transitionDurationMillis = 0
                    )
                }
            }
            publishSceneState(session)
            return
        }

        mainHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            worker.execute {
                val current = sceneObjects[motion.objectName]
                if (current?.transitionToken == token) {
                    sceneObjects[motion.objectName] = current.copy(
                        transitionFromAlpha = null,
                        transitionDurationMillis = 0
                    )
                    publishSceneState(session)
                }
            }
        }, duration.toLong())
    }

    private fun sceneAlpha(value: Any?, fallback: Float): Float {
        val number = (value as? Number)?.toFloat() ?: return fallback
        return (if (number > 1f) number / 255f else number).coerceIn(0f, 1f)
    }

    private fun isCompleted(event: LiveMakerSessionEvent): Boolean =
        event is LiveMakerSessionEvent.EngineEvent &&
            event.event == LiveMakerVmEvent.Completed

    private fun selectAsset(asset: LiveMakerMediaAsset) {
        worker.execute {
            val repository = mediaRepository ?: return@execute
            try {
                when (asset.extension) {
                    "gal", "png" -> {
                        val bitmap = decodeImage(repository.readAsset(asset), asset.extension)
                        mainHandler.post {
                            if (isFinishing || isDestroyed) {
                                // Drop the reference; Compose may still be finishing a draw.
                            } else {
                                uiState.value = uiState.value.copy(
                                    imageBitmap = bitmap,
                                    selectedAssetName = asset.path,
                                    errorMessage = null
                                )
                            }
                        }
                    }
                    "ogg", "wav" -> {
                        val staged = repository.stageAudio(asset, File(cacheDir, "livemaker-audio"))
                        mainHandler.post {
                            if (isFinishing || isDestroyed) {
                                staged.delete()
                            } else {
                                val player = audioPlayer ?: SystemNnnAudioPlayer { status ->
                                    mainHandler.post {
                                        if (!isFinishing && !isDestroyed) {
                                            uiState.value = uiState.value.copy(
                                                audioStatus = status.state.name.lowercase() +
                                                    ": " + status.resourceName
                                            )
                                        }
                                    }
                                }.also { audioPlayer = it }
                                try {
                                    player.play(staged, asset.path)
                                    uiState.value = uiState.value.copy(
                                        audioStatus = "Подготовка: " + asset.path,
                                        errorMessage = null
                                    )
                                } catch (error: Exception) {
                                    uiState.value = uiState.value.copy(
                                        errorMessage = error.message ?: "Не удалось воспроизвести аудио."
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                publishError(error)
            }
        }
    }

    private fun nextEvent(session: LiveMakerGameSession): LiveMakerSessionEvent {
        repeat(MAX_AUTOMATIC_HOST_COMMANDS) {
            val event = session.runUntilEvent()
            publishSceneState(session)
            if (event is LiveMakerSessionEvent.PageEntered) return@repeat

            val hostCommand = (event as? LiveMakerSessionEvent.EngineEvent)
                ?.event as? LiveMakerVmEvent.HostCommandRequired
            if (hostCommand != null && handleHostCommand(hostCommand.command, session)) {
                session.resume()
                return@repeat
            }
            return event
        }
        throw IOException("LiveMaker exceeded the automatic host-command budget.")
    }

    /**
     * Handles LiveMaker Android scene/audio commands with verified command and property IDs.
     * Any unknown command is returned to the VM host surface instead of being skipped.
     */
    private fun handleHostCommand(
        command: io.github.helterfall.ramen.inspection.LiveMakerCommand,
        session: LiveMakerGameSession
    ): Boolean {
        val repository = mediaRepository

        if (historyHost.handle(command, session::evaluateExpression)) {
            publishHistory()
            return true
        }
        if (command.typeId == 29) {
            historyRequested = true
            mainHandler.post {
                if (!isFinishing && !isDestroyed) {
                    val snapshot = historyHost.snapshot()
                    uiState.value = uiState.value.copy(
                        historyEntries = snapshot.entries,
                        historyFormatName = snapshot.activeFormat?.name,
                        historyVisible = true
                    )
                }
            }
            return true
        }

        if (command.typeId in LiveMakerSceneCommandAdapter.requestedSceneCommandIds &&
            command.typeId !in LiveMakerSceneCommandAdapter.androidHandledSceneCommandIds
        ) {
            // VM-owned scene commands must never be accepted as host callbacks.
            return false
        }

        val values = LiveMakerSceneCommandAdapter.registerComponent(
            command,
            session.objectStore,
            session::evaluateExpression
        )

        fun number(value: Any?, fallback: Float): Float = when (value) {
            is Number -> value.toFloat()
            is String -> value.toFloatOrNull() ?: fallback
            else -> fallback
        }

        when (command.typeId) {
            // ImgNew. PR_NAME=1, PR_SOURCE=3, PR_LEFT=4, PR_TOP=5, PR_WIDTH=6,
            // PR_HEIGHT=7, PR_ALPHA=12, PR_PRIORITY=13, PR_VISIBLE=48.
            9 -> {
                val source = values[3]?.toString()?.takeIf { it.isNotBlank() }.orEmpty()
                val objectName = values[1]?.toString()?.takeIf { it.isNotBlank() }
                    ?: source.ifBlank { "image-${command.index}" }
                if (!session.objectStore.contains(objectName)) {
                    session.createObject(
                        objectName,
                        command.typeId,
                        values + (LiveMakerObjectStore.PR_NAME to objectName)
                    )
                }

                // Keep VM execution moving and render the object geometry when its asset
                // is unavailable. A decoded archive bitmap replaces the placeholder.
                if (repository == null || source.isBlank()) {
                    publishSceneState(session)
                    return true
                }
                val asset = repository.findAsset(source)
                if (asset == null || asset.extension !in setOf("gal", "png")) {
                    publishSceneState(session)
                    mainHandler.post {
                        if (!isFinishing && !isDestroyed) {
                            uiState.value = uiState.value.copy(
                                errorMessage = "Image resource '$source' is unavailable; showing a placeholder."
                            )
                        }
                    }
                    return true
                }

                val bitmap = runCatching {
                    decodeImage(repository.readAsset(asset), asset.extension)
                }.getOrElse { error ->
                    publishSceneState(session)
                    mainHandler.post {
                        if (!isFinishing && !isDestroyed) {
                            uiState.value = uiState.value.copy(
                                errorMessage = error.message ?: "Could not decode image '$source'; showing a placeholder."
                            )
                        }
                    }
                    return true
                }
                val width = number(values[6], bitmap.width.toFloat()).coerceAtLeast(1f)
                val height = number(values[7], bitmap.height.toFloat()).coerceAtLeast(1f)
                val alphaValue = number(values[12], 255f)
                val alpha = (if (alphaValue > 1f) alphaValue / 255f else alphaValue)
                    .coerceIn(0f, 1f)
                val visible = when (val flag = values[48]) {
                    is Boolean -> flag
                    is Number -> flag.toInt() != 0
                    is String -> flag.isNotBlank() && flag != "0"
                    else -> true
                }
                val isBackground = isLiveMakerBackgroundLayer(objectName, asset.path)
                if (isBackground) {
                    runtimeEnvironment?.screenWidthPx = bitmap.width
                    runtimeEnvironment?.screenHeightPx = bitmap.height
                }

                val image = LiveMakerSceneBitmap(
                    objectName = objectName,
                    resourcePath = asset.path,
                    bitmap = bitmap,
                    left = number(values[4], 0f),
                    top = number(values[5], 0f),
                    width = width,
                    height = height,
                    alpha = alpha,
                    priority = number(values[13], sceneObjects.size.toFloat()),
                    isBackground = isBackground,
                    visible = visible
                )
                sceneObjects.put(objectName, image)
                publishSceneState(session)
                return true
            }

            // Sound creates a named media object; MediaPlay starts it.
            42 -> {
                if (repository == null) return false
                val source = values[3]?.toString()?.takeIf { it.isNotBlank() } ?: return false
                val asset = repository.findAsset(source)
                    ?: throw IOException(
                        "LiveMaker audio resource '" + source + "' was not found or is ambiguous."
                    )
                if (asset.extension !in setOf("ogg", "wav")) {
                    throw IOException("Sound resource '" + source + "' is not OGG or WAV.")
                }
                val name = values[1]?.toString()?.takeIf { it.isNotBlank() } ?: source
                soundObjects[name] = asset
                return true
            }

            // MediaPlay.Target is a LiveParser expression containing the media object name.
            55 -> {
                if (repository == null) return false
                val targetExpression = command.expressions.firstOrNull() ?: return false
                val target = session.evaluateExpression(targetExpression)?.toString().orEmpty()
                val asset = soundObjects[target] ?: repository.findAsset(target) ?: return false
                startTpWordAudio(
                    repository.stageAudio(asset, File(cacheDir, "livemaker-audio")),
                    asset,
                    target
                )
                return true
            }

            // ObjDel(Target) and TextClr(Target).
            19, 28 -> {
                val expression = command.expressions.firstOrNull() ?: return false
                val target = session.evaluateExpression(expression)?.toString().orEmpty()
                if (command.typeId == 19) {
                    sceneObjects.remove(target)
                    soundObjects.remove(target)
                    session.deleteObject(target)
                    publishSceneState(session)
                } else {
                    session.setObjectProperty(target, LiveMakerObjectStore.PR_TEXT, "")
                    publishSceneState(session)
                    mainHandler.post {
                        if (!isFinishing && !isDestroyed) {
                            uiState.value = uiState.value.copy(dialogueText = "")
                        }
                    }
                }
                return true
            }

            21 -> {
                synchronized(audioStatusMonitor) {
                    lastAudioStatus = SystemNnnAudioPlaybackStatus(
                        SystemNnnAudioPlaybackState.STOPPED,
                        currentAudioResourceName.orEmpty()
                    )
                    audioStatusMonitor.notifyAll()
                }
                mainHandler.post { audioPlayer?.stop() }
                return true
            }
            // Non-bitmap scene components render from their shared object-store properties.
            8, 10, 11, 12, 23, 24, 25, 30, 36, 37, 43, 44,
            48, 49, 50, 51, 52, 53, 54, 56 -> {
                publishSceneState(session)
                return true
            }
            // DoEvent/ClrRead are consumed by the VM/runtime loop.
            46, 47 -> {
                publishSceneState(session)
                return true
            }
            else -> return false
        }
    }

    /**
     * Re-projects mutable LiveMakerObjectStore values onto the Android scene canvas.
     * VM-level SetProp/GetProp and PropMotion changes therefore update geometry,
     * alpha, z-order, and visibility without asking the host to re-run the command.
     */
    private fun publishSceneState(session: LiveMakerGameSession) {
        val snapshots = session.objectStore.snapshotAll()
        val byName = snapshots.associateBy { it.name }
        sceneObjects.entries.toList().forEach { (name, image) ->
            val properties = byName[name]?.properties ?: return@forEach
            fun number(propertyId: Int, fallback: Float): Float =
                (properties[propertyId] as? Number)?.toFloat() ?: fallback
            fun visible(propertyId: Int, fallback: Boolean): Boolean =
                when (val value = properties[propertyId]) {
                    is Boolean -> value
                    is Number -> value.toInt() != 0
                    is String -> value.isNotBlank() && value != "0"
                    else -> fallback
                }
            val alphaValue = number(LiveMakerObjectStore.PR_ALPHA, 255f)
            sceneObjects[name] = image.copy(
                left = number(LiveMakerObjectStore.PR_LEFT, image.left),
                top = number(LiveMakerObjectStore.PR_TOP, image.top),
                width = number(LiveMakerObjectStore.PR_WIDTH, image.width).coerceAtLeast(1f),
                height = number(LiveMakerObjectStore.PR_HEIGHT, image.height).coerceAtLeast(1f),
                alpha = (if (alphaValue > 1f) alphaValue / 255f else alphaValue).coerceIn(0f, 1f),
                priority = number(LiveMakerObjectStore.PR_PRIORITY, image.priority),
                visible = visible(LiveMakerObjectStore.PR_VISIBLE, image.visible)
            )
        }

        val imageSnapshot = sceneObjects.values.sortedBy { it.priority }
        val componentSnapshot = snapshots
            .map(LiveMakerSceneComponent::from)
            .filter { it.isRenderable }
            .sortedBy { it.priority }
        mainHandler.post {
            if (!isFinishing && !isDestroyed) {
                uiState.value = uiState.value.copy(
                    sceneImages = imageSnapshot,
                    sceneComponents = componentSnapshot,
                    sceneCanvasWidthPx = runtimeEnvironment?.screenWidthPx
                        ?.toFloat()?.coerceAtLeast(1f) ?: uiState.value.sceneCanvasWidthPx,
                    sceneCanvasHeightPx = runtimeEnvironment?.screenHeightPx
                        ?.toFloat()?.coerceAtLeast(1f) ?: uiState.value.sceneCanvasHeightPx
                )
            }
        }
    }

    private fun presentEvent(
        event: LiveMakerSessionEvent,
        session: LiveMakerGameSession
    ): LiveMakerEventPresentation =
        when (event) {
            is LiveMakerSessionEvent.PageEntered ->
                LiveMakerEventPresentation("Открыта страница " + event.pageReference + ".", false)
            is LiveMakerSessionEvent.PageResolutionRequired ->
                LiveMakerEventPresentation(
                    "Страница " + event.pageReference + " отсутствует в доступных LSB.",
                    false
                )
            is LiveMakerSessionEvent.EngineEvent -> when (val engineEvent = event.event) {
                is LiveMakerVmEvent.TextBlock -> {
                    val decoded = LiveMakerTpWordDecoder().decode(engineEvent.tpWordBytes) { name ->
                        session.variablesSnapshot()[name]
                    }
                    val includeInHistory = when (
                        val historyFlag = session.evaluateExpression(engineEvent.historyExpression)
                    ) {
                        null -> false
                        is Boolean -> historyFlag
                        is Number -> historyFlag.toDouble() != 0.0
                        is String -> historyFlag.isNotEmpty()
                        else -> false
                    }
                    if (historyHost.addText(decoded.plainText, includeInHistory)) {
                        publishHistory()
                    }
                    // The containing LSB TextIns Wait flag is authoritative even
                    // when TpWord contains only system events and zero visible characters.
                    // Otherwise the host skips the game's menu/title input boundary and
                    // can execute hundreds of subsequent commands in one worker turn.
                    val segments = applyLiveMakerTextBlockWait(
                        decoded.segments,
                        engineEvent.shouldWait
                    )
                    textPlayback = LiveMakerTextPlayback(engineEvent, segments)
                    advanceTextPlayback(textPlayback!!, session)
                    LiveMakerEventPresentation(
                        // Event names are internal bytecode, not player-facing content.
                        description = if (decoded.plainText.isNotBlank()) {
                            "Диалог · TpWord v" + decoded.version
                        } else {
                            ""
                        },
                        canContinue = textPlayback?.waitingForTap == true,
                        dialogueText = textPlayback?.currentText.orEmpty(),
                        waitingForTap = textPlayback?.waitingForTap == true
                    )
                }
                is LiveMakerVmEvent.Delay ->
                    LiveMakerEventPresentation(
                        description = "",
                        canContinue = engineEvent.milliseconds <= 0L,
                        dialogueText = "",
                        waitingForTap = engineEvent.milliseconds <= 0L
                    )
                is LiveMakerVmEvent.ProcessEvents ->
                    LiveMakerEventPresentation(
                        description = "",
                        canContinue = false,
                        autoAdvanceOnFrame = true
                    )
                is LiveMakerVmEvent.FlipTransition -> {
                    startFlipTransition(engineEvent, session)
                    LiveMakerEventPresentation(
                        description = "",
                        canContinue = false
                    )
                }
                is LiveMakerVmEvent.PropertyMotion -> {
                    startPropertyMotion(engineEvent, session)
                    LiveMakerEventPresentation(
                        description = "",
                        canContinue = false
                    )
                }
                is LiveMakerVmEvent.HostCommandRequired ->
                    LiveMakerEventPresentation(
                        "Команда сцены " + engineEvent.command.typeName +
                            " (" + engineEvent.command.typeId + ") ожидает host adapter.",
                        false
                    )
                is LiveMakerVmEvent.UnsupportedExpression ->
                    LiveMakerEventPresentation(
                        "Неподдерживаемое выражение в команде " +
                            engineEvent.commandIndex + ": " + engineEvent.reason,
                        false
                    )
                LiveMakerVmEvent.AwaitingHost ->
                    LiveMakerEventPresentation("Виртуальная машина ожидает обработку хоста.", false)
                LiveMakerVmEvent.Running ->
                    LiveMakerEventPresentation("Виртуальная машина выполняется.", false)
                LiveMakerVmEvent.Completed ->
                    LiveMakerEventPresentation("Достигнут конец сценария.", false)
                is LiveMakerVmEvent.ScriptTransfer ->
                    LiveMakerEventPresentation(
                        "VM ожидает переход на страницу " + engineEvent.page + ".",
                        false
                    )
            }
        }

    /**
     * Applies system events embedded in a compiled TpWord stream. Serialized arguments are
     * CRLF-separated and may be positional or NAME=value fields.
     */
    private fun handleTpWordEvent(event: LiveMakerTpWordEvent, session: LiveMakerGameSession) {
        val args = LiveMakerTpWordArguments(event.arguments)

        // NAMELABEL is intentionally a non-system TpWord event. It sets the speaker
        // label for following dialogue until the next system NAMELABEL clears it.
        if (event.name.equals("NAMELABEL", ignoreCase = true)) {
            currentSpeakerName = if (event.isSystem) null else args.get(0, "NAME", "LABEL")
            return
        }

        when (event.name.uppercase()) {
            "CREATECG", "CHANGECG" -> {
                val repository = mediaRepository ?: return
                val objectName = args.get(0, "NAME", "OBJECT", "TARGET", "ID")
                    ?: throw IOException(event.name + " is missing its layer name.")
                var source = args.get(1, "SOURCE", "SRC", "FILE", "CG", "IMAGE", "PATH")
                    ?: throw IOException(event.name + " is missing its image source.")

                if (source.startsWith("?")) {
                    val variableName = source.drop(1)
                    source = session.variablesSnapshot()[variableName]?.toString()
                        ?: throw IOException("TpWord CG variable '" + variableName + "' has no value.")
                }
                source = decodeLiveMakerEventEscapes(source)

                val previous = sceneObjects[objectName]
                val bitmap: Bitmap
                val resourcePath: String
                val asset = repository.findAsset(source)
                if (asset != null && asset.extension in setOf("gal", "png")) {
                    bitmap = decodeImage(repository.readAsset(asset), asset.extension)
                    resourcePath = asset.path
                } else {
                    // LiveNovel also permits a solid-color CG source such as "$202020".
                    val colorBitmap = decodeLiveMakerSolidColor(source)
                    if (colorBitmap == null) {
                        throw IOException(
                            "TpWord CG resource '" + source + "' was not found or is ambiguous."
                        )
                    }
                    bitmap = colorBitmap
                    resourcePath = source
                }

                val isCreate = event.name.equals("CREATECG", ignoreCase = true)
                val isBackground = isLiveMakerBackgroundLayer(objectName, resourcePath)
                // Scene commands and @SCRWIDTH/@SCRHEIGHT must share one virtual canvas.
                // If a background is already present, its native dimensions define that canvas.
                val existingBackground = sceneObjects.values.firstOrNull { it.isBackground }
                val stageWidth = if (isBackground) {
                    bitmap.width.toFloat()
                } else {
                    existingBackground?.bitmap?.width?.toFloat()
                        ?: runtimeEnvironment?.screenWidthPx?.toFloat()
                        ?: resources.displayMetrics.widthPixels.toFloat()
                }
                val stageHeight = if (isBackground) {
                    bitmap.height.toFloat()
                } else {
                    existingBackground?.bitmap?.height?.toFloat()
                        ?: runtimeEnvironment?.screenHeightPx?.toFloat()
                        ?: resources.displayMetrics.heightPixels.toFloat()
                }
                if (isBackground) {
                    runtimeEnvironment?.screenWidthPx = bitmap.width
                    runtimeEnvironment?.screenHeightPx = bitmap.height
                }
                val xToken = if (isCreate) args.get(4, "X", "LEFT") else null
                val yToken = if (isCreate) args.get(5, "Y", "TOP") else null
                val left = when {
                    xToken != null -> parseLiveMakerStageCoordinate(
                        xToken, stageWidth, bitmap.width.toFloat(), horizontal = true
                    )
                    previous != null -> previous.left
                    else -> 0f
                }
                val top = when {
                    yToken != null -> parseLiveMakerStageCoordinate(
                        yToken, stageHeight, bitmap.height.toFloat(), horizontal = false
                    )
                    previous != null -> previous.top
                    else -> 0f
                }
                // In LiveNovel CREATECG, args 2/3 are mode/parent and args 4/5 are
                // coordinates. They are not width/height. CGs use their native size.
                val alpha = previous?.alpha ?: 1f
                val priority = if (isCreate) {
                    args.get(6, "Z", "PRIORITY")?.toFloatOrNull()
                        ?: previous?.priority ?: sceneObjects.size.toFloat()
                } else {
                    previous?.priority ?: sceneObjects.size.toFloat()
                }
                val image = LiveMakerSceneBitmap(
                    objectName = objectName,
                    resourcePath = resourcePath,
                    bitmap = bitmap,
                    left = left,
                    top = top,
                    width = bitmap.width.toFloat().coerceAtLeast(1f),
                    height = bitmap.height.toFloat().coerceAtLeast(1f),
                    alpha = alpha,
                    priority = priority,
                    isBackground = isBackground,
                    visible = previous?.visible ?: true
                )

                // Compose can still hold the old Bitmap in a recorded draw; just replace
                // the reference and let Android reclaim it, never call Bitmap.recycle().
                sceneObjects[objectName] = image
                val alphaValue = (image.alpha * 255f).coerceIn(0f, 255f)
                session.createObject(
                    objectName,
                    null,
                    mapOf(
                        LiveMakerObjectStore.PR_NAME to objectName,
                        LiveMakerObjectStore.PR_SOURCE to resourcePath,
                        LiveMakerObjectStore.PR_LEFT to image.left,
                        LiveMakerObjectStore.PR_TOP to image.top,
                        LiveMakerObjectStore.PR_WIDTH to image.width,
                        LiveMakerObjectStore.PR_HEIGHT to image.height,
                        LiveMakerObjectStore.PR_ALPHA to alphaValue,
                        LiveMakerObjectStore.PR_PRIORITY to image.priority,
                        LiveMakerObjectStore.PR_VISIBLE to image.visible
                    )
                )
                publishSceneState(session)
            }

            "DELETECG" -> {
                // DELETECG's first parameter is a comma-delimited layer list; following
                // parameters are wipe/duration values and must not be treated as names.
                args.csv(0).forEach { target ->
                    sceneObjects.remove(target)
                    session.deleteObject(target)
                }
                publishSceneState(session)
            }

            "PLAYSND" -> {
                val repository = mediaRepository ?: return
                val source = args.get(0, "SOURCE", "SRC", "FILE", "NAME", "SOUND", "AUDIO", "PATH")
                    ?: throw IOException("PLAYSND is missing its audio source.")
                val channel = args.get(1, "CHANNEL", "BUS") ?: "SE"
                val resolvedSource = decodeLiveMakerEventEscapes(
                    if (source.startsWith("?")) {
                        session.variablesSnapshot()[source.drop(1)]?.toString()
                            ?: throw IOException("TpWord audio variable '" + source.drop(1) + "' has no value.")
                    } else source
                )
                val asset = soundObjects[resolvedSource] ?: repository.findAsset(resolvedSource)
                    ?: throw IOException(
                        "TpWord sound resource '" + resolvedSource + "' was not found or is ambiguous."
                    )
                if (asset.extension !in setOf("ogg", "wav")) {
                    throw IOException("TpWord sound resource '" + resolvedSource + "' is not OGG or WAV.")
                }
                startTpWordAudio(repository.stageAudio(asset, File(cacheDir, "livemaker-audio")), asset, channel)
            }

            "REPLAYSND" -> {
                val repository = mediaRepository ?: return
                val channel = args.get(0, "CHANNEL", "BUS") ?: "SE"
                val source = decodeLiveMakerEventEscapes(
                    args.get(1, "SOURCE", "SRC", "FILE", "NAME")
                        ?: currentAudioResourceName
                        ?: return
                )
                val asset = soundObjects[source] ?: repository.findAsset(source)
                    ?: throw IOException("TpWord sound resource '" + source + "' was not found or is ambiguous.")
                startTpWordAudio(repository.stageAudio(asset, File(cacheDir, "livemaker-audio")), asset, channel)
            }

            "STOPSND" -> {
                val channel = args.get(0, "CHANNEL", "BUS") ?: currentAudioChannel
                if (channel == null || channel.equals(currentAudioChannel, ignoreCase = true)) {
                    synchronized(audioStatusMonitor) {
                        lastAudioStatus = SystemNnnAudioPlaybackStatus(
                            SystemNnnAudioPlaybackState.STOPPED,
                            currentAudioResourceName.orEmpty()
                        )
                        audioStatusMonitor.notifyAll()
                    }
                    mainHandler.post { audioPlayer?.stop() }
                }
            }

            "WAITPLAY" -> {
                val target = args.get(0, "TARGET", "CHANNEL", "NAME") ?: return
                val clickSkippable = args.get(1, "SKIP", "MODE")
                    ?.equals("CLICK", ignoreCase = true) == true
                awaitTpWordPlayback(target, clickSkippable)
            }

            "WAIT" -> {
                val milliseconds = args.get(0, "TIME", "MS", "DURATION", "MILLISECONDS")
                    ?.toLongOrNull() ?: 0L
                if (milliseconds > 0L) {
                    Thread.sleep(milliseconds.coerceAtMost(MAX_TPWORD_WAIT_MS))
                }
                // TIME=0 is an unbounded user-click pause and is represented as a
                // waitForTap segment by LiveMakerTpWordDecoder.
            }

            "MESON" -> {
                messageWindowVisible = true
                mainHandler.post {
                    if (!isFinishing && !isDestroyed) {
                        uiState.value = uiState.value.copy(messageWindowVisible = true)
                    }
                }
            }

            "MESOFF", "MESEND" -> {
                messageWindowVisible = false
                if (event.name.equals("MESEND", ignoreCase = true)) {
                    currentSpeakerName = null
                    textPlayback?.currentText = ""
                }
                mainHandler.post {
                    if (!isFinishing && !isDestroyed) {
                        uiState.value = uiState.value.copy(
                            messageWindowVisible = false,
                            speakerName = currentSpeakerName,
                            dialogueText = if (event.name.equals("MESEND", ignoreCase = true)) "" else uiState.value.dialogueText
                        )
                    }
                }
            }

            "PAUSE", "PS", "PG", "PAGE", "CLR", "CLEAR",
            "MENUENABLED", "MENUDISABLED" -> Unit

            else -> Unit
        }
    }

    private fun decodeLiveMakerSolidColor(source: String): Bitmap? {
        val hex = source.trim().removePrefix("$").removePrefix("#")
        if (hex.length != 6 || hex.any { it.digitToIntOrNull(16) == null }) return null
        val color = runCatching { android.graphics.Color.parseColor("#" + hex) }.getOrNull()
            ?: return null
        return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    }

    private fun startTpWordAudio(staged: File, asset: LiveMakerMediaAsset, channel: String) {
        currentAudioChannel = channel
        currentAudioResourceName = asset.path
        synchronized(audioStatusMonitor) {
            lastAudioStatus = SystemNnnAudioPlaybackStatus(
                SystemNnnAudioPlaybackState.PREPARING,
                asset.path
            )
        }
        mainHandler.post {
            if (isFinishing || isDestroyed) {
                staged.delete()
                synchronized(audioStatusMonitor) {
                    lastAudioStatus = SystemNnnAudioPlaybackStatus(
                        SystemNnnAudioPlaybackState.STOPPED,
                        asset.path
                    )
                    audioStatusMonitor.notifyAll()
                }
                return@post
            }
            val player = audioPlayer ?: SystemNnnAudioPlayer { status ->
                synchronized(audioStatusMonitor) {
                    lastAudioStatus = status
                    audioStatusMonitor.notifyAll()
                }
                mainHandler.post {
                    if (!isFinishing && !isDestroyed) {
                        uiState.value = uiState.value.copy(
                            audioStatus = status.state.name.lowercase() + ": " + status.resourceName
                        )
                    }
                }
            }.also { audioPlayer = it }
            runCatching { player.play(staged, asset.path) }
                .onFailure { error ->
                    staged.delete()
                    synchronized(audioStatusMonitor) {
                        lastAudioStatus = SystemNnnAudioPlaybackStatus(
                            SystemNnnAudioPlaybackState.ERROR,
                            asset.path,
                            error.message
                        )
                        audioStatusMonitor.notifyAll()
                    }
                    uiState.value = uiState.value.copy(
                        errorMessage = error.message ?: "Could not play TpWord audio."
                    )
                }
        }
    }

    private fun awaitTpWordPlayback(target: String, clickSkippable: Boolean) {
        val expectedChannel = currentAudioChannel
        val expectedResource = currentAudioResourceName
        if (expectedResource.isNullOrBlank()) return
        val targetMatches = target.equals(expectedChannel, ignoreCase = true) ||
            target.equals(expectedResource, ignoreCase = true) ||
            target.equals(expectedResource.substringAfterLast('/'), ignoreCase = true)
        if (!targetMatches) return

        waitingForTpWordPlayback = true
        tpWordPlaybackCanSkip = clickSkippable
        skipTpWordPlaybackRequested = false
        if (clickSkippable) {
            mainHandler.post {
                if (!isFinishing && !isDestroyed) {
                    uiState.value = uiState.value.copy(
                        canContinue = true,
                        waitingForTap = true,
                        dialogueText = ""
                    )
                }
            }
        }

        val startedAt = android.os.SystemClock.elapsedRealtime()
        val timeout = MAX_TPWORD_PLAYBACK_WAIT_MS
        try {
            synchronized(audioStatusMonitor) {
                while (!skipTpWordPlaybackRequested) {
                    val status = lastAudioStatus
                    if (status == null ||
                        !status.resourceName.equals(expectedResource, ignoreCase = true) ||
                        status.state == SystemNnnAudioPlaybackState.COMPLETED ||
                        status.state == SystemNnnAudioPlaybackState.STOPPED ||
                        status.state == SystemNnnAudioPlaybackState.ERROR
                    ) {
                        break
                    }
                    val remaining = timeout - (android.os.SystemClock.elapsedRealtime() - startedAt)
                    if (remaining <= 0L) break
                    audioStatusMonitor.wait(minOf(remaining, 250L))
                }
            }
        } finally {
            waitingForTpWordPlayback = false
            tpWordPlaybackCanSkip = false
            skipTpWordPlaybackRequested = false
        }
    }

    /** Advances through non-blocking TpWord controls until a user tap is required. */
    private fun advanceTextPlayback(
        playback: LiveMakerTextPlayback,
        session: LiveMakerGameSession
    ): String? {
        while (playback.nextSegmentIndex < playback.segments.size) {
            val segment = playback.segments[playback.nextSegmentIndex++]
            // Execute embedded system events at their position in the TpWord stream,
            // rather than eagerly running later events before earlier dialogue.
            segment.eventsBefore.forEach { handleTpWordEvent(it, session) }
            if (segment.clearBefore) playback.currentText = ""
            playback.currentText += segment.text
            if (segment.waitForTap) {
                playback.waitingForTap = true
                return playback.currentText
            }
        }
        playback.waitingForTap = false
        return null
    }

    private fun decodeImage(bytes: ByteArray, extension: String): Bitmap {
        if (extension == "gal") {
            return LiveMakerImageByteArraySource(bytes).use { source ->
                LiveMakerGalDecoder(maxDecodedPixels = MAX_IMAGE_PIXELS)
                    .decode(source)
                    .toBitmap()
            }
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("PNG dimensions could not be read.")
        }
        val pixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
        var sampleSize = 1
        while (pixels / (sampleSize.toLong() * sampleSize.toLong()) > MAX_IMAGE_PIXELS) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IOException("Android could not decode the PNG resource.")
    }

    private fun resolvePrivateGameDirectory(gameId: String): File {
        if (gameId.any { it.isISOControl() || it == '/' || it == '\\' }) {
            throw IOException("Некорректный идентификатор игры.")
        }
        val gamesRoot = File(filesDir, "games").canonicalFile
        val gameDirectory = File(gamesRoot, gameId).canonicalFile
        if (gameDirectory == gamesRoot ||
            !gameDirectory.toPath().startsWith(gamesRoot.toPath()) ||
            !gameDirectory.isDirectory
        ) {
            throw IOException("Папка игры отсутствует в приватной библиотеке Ramen.")
        }
        return gameDirectory
    }

    private fun publishError(error: Throwable) {
        val stackTrace = runCatching { error.stackTraceToString() }
            .getOrElse { error.javaClass.name + ": " + (error.message ?: "unknown error") }
            .take(MAX_ERROR_LOG_CHARS)
        mainHandler.post {
            if (!isFinishing && !isDestroyed) {
                uiState.value = uiState.value.copy(
                    isLoading = false,
                    eventDescription = "Виртуальная машина остановлена из-за ошибки.",
                    canContinue = false,
                    waitingForTap = false,
                    errorMessage = error.message ?: error.javaClass.simpleName,
                    errorLog = stackTrace
                )
            }
        }
    }

    private fun copyErrorLog() {
        val log = uiState.value.errorLog ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Ramen LiveMaker crash log", log))
        Toast.makeText(this, "Лог скопирован", Toast.LENGTH_SHORT).show()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveGameMode()
    }

    @Suppress("DEPRECATION")
    private fun applyImmersiveGameMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    override fun onDestroy() {
        worker.shutdownNow()
        liveSession?.close()
        mediaRepository?.close()
        audioPlayer?.close()
        // Compose may retain Bitmap references through painters and recorded display lists.
        // Never recycle scene/preview bitmaps manually; dropping references lets Android reclaim them.
        super.onDestroy()
    }

    companion object {
        const val EXTRA_GAME_ID = "io.github.helterfall.ramen.extra.LIVEMAKER_GAME_ID"
        private const val MAX_AUTOMATIC_HOST_COMMANDS = 512
        private const val MAX_IMAGE_PIXELS = 16_000_000L
        private const val MAX_TPWORD_WAIT_MS = 60_000L
        private const val MAX_TPWORD_PLAYBACK_WAIT_MS = 10 * 60 * 1000L
        private const val EVENT_PUMP_FRAME_DELAY_MS = 16L
        private const val MAX_ERROR_LOG_CHARS = 128_000
    }
}

@Composable
private fun LiveMakerPlayerScreen(
    state: LiveMakerPlayerUiState,
    onContinue: () -> Unit,
    onCanvasTap: (Float, Float) -> Unit,
    onAssetSelected: (LiveMakerMediaAsset) -> Unit,
    onHistoryClick: () -> Unit,
    onHistoryDismiss: () -> Unit,
    onResourcesClick: () -> Unit,
    onResourcesDismiss: () -> Unit,
    onCopyErrorLog: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
        ) {
            val canvasWidth = state.sceneCanvasWidthPx.coerceAtLeast(1f)
            val canvasHeight = state.sceneCanvasHeightPx.coerceAtLeast(1f)
            val stageAspectRatio = (canvasWidth / canvasHeight).coerceIn(0.1f, 10f)
            val viewportAspectRatio = maxWidth.value / maxHeight.value.coerceAtLeast(1f)
            // Fit the authored LiveMaker stage into the device viewport. Never stretch
            // a 4:3/16:9 title screen to the phone's ultrawide aspect ratio.
            val stageWidth = if (viewportAspectRatio > stageAspectRatio) {
                maxHeight * stageAspectRatio
            } else {
                maxWidth
            }
            val stageHeight = if (viewportAspectRatio > stageAspectRatio) {
                maxHeight
            } else {
                maxWidth / stageAspectRatio
            }

            val density = LocalDensity.current
            val stageWidthPx = with(density) { stageWidth.toPx() }.coerceAtLeast(1f)
            val stageHeightPx = with(density) { stageHeight.toPx() }.coerceAtLeast(1f)
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(stageWidth)
                    .height(stageHeight)
                    .pointerInput(
                        state.canContinue,
                        state.historyVisible,
                        state.resourcesVisible,
                        stageWidthPx,
                        stageHeightPx
                    ) {
                        detectTapGestures { offset ->
                            if (!state.historyVisible && !state.resourcesVisible) {
                                onCanvasTap(
                                    (offset.x / stageWidthPx * canvasWidth).coerceIn(0f, canvasWidth),
                                    (offset.y / stageHeightPx * canvasHeight).coerceIn(0f, canvasHeight)
                                )
                            }
                        }
                    }
            ) {
                state.sceneImages.filter { it.isBackground }.forEach { image ->
                    val animatedAlpha = remember(image.objectName, image.transitionToken) {
                        Animatable(image.transitionFromAlpha ?: image.alpha)
                    }
                    LaunchedEffect(image.objectName, image.transitionToken, image.alpha) {
                        if (image.transitionFromAlpha != null && image.transitionDurationMillis > 0) {
                            val easing = when (image.transitionMoveType.uppercase()) {
                                "I" -> FastOutLinearInEasing
                                "D" -> LinearOutSlowInEasing
                                else -> LinearEasing
                            }
                            animatedAlpha.animateTo(
                                targetValue = image.alpha,
                                animationSpec = tween(
                                    durationMillis = image.transitionDurationMillis,
                                    easing = easing
                                )
                            )
                        } else {
                            animatedAlpha.snapTo(image.alpha)
                        }
                    }
                    Image(
                        bitmap = image.bitmap.asImageBitmap(),
                        contentDescription = image.resourcePath,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(alpha = if (image.visible) animatedAlpha.value else 0f),
                        contentScale = ContentScale.FillBounds
                    )
                }

                state.sceneImages
                    .filterNot { it.isBackground }
                    .forEach { image ->
                        val animatedAlpha = remember(image.objectName, image.transitionToken) {
                            Animatable(image.transitionFromAlpha ?: image.alpha)
                        }
                        LaunchedEffect(image.objectName, image.transitionToken, image.alpha) {
                            if (image.transitionFromAlpha != null && image.transitionDurationMillis > 0) {
                                val easing = when (image.transitionMoveType.uppercase()) {
                                    "I" -> FastOutLinearInEasing
                                    "D" -> LinearOutSlowInEasing
                                    else -> LinearEasing
                                }
                                animatedAlpha.animateTo(
                                    targetValue = image.alpha,
                                    animationSpec = tween(
                                        durationMillis = image.transitionDurationMillis,
                                        easing = easing
                                    )
                                )
                            } else {
                                animatedAlpha.snapTo(image.alpha)
                            }
                        }
                        Image(
                            bitmap = image.bitmap.asImageBitmap(),
                            contentDescription = image.resourcePath,
                            modifier = Modifier
                                .offset(
                                    x = stageWidth * (image.left / canvasWidth),
                                    y = stageHeight * (image.top / canvasHeight)
                                )
                                .width(stageWidth * (image.width / canvasWidth).coerceIn(0.01f, 2f))
                                .height(stageHeight * (image.height / canvasHeight).coerceIn(0.01f, 2f))
                                .graphicsLayer(alpha = if (image.visible) animatedAlpha.value else 0f),
                            contentScale = ContentScale.Fit
                        )
                    }

                state.sceneComponents
                    .filter { component ->
                        component.isRenderable && component.visible &&
                            (!component.isImage || state.sceneImages.none {
                                it.objectName == component.objectName
                            })
                    }
                    .forEach { component ->
                        val componentModifier = Modifier
                            .offset(
                                x = stageWidth * (component.left / canvasWidth),
                                y = stageHeight * (component.top / canvasHeight)
                            )
                            .width(stageWidth * (component.width / canvasWidth).coerceIn(0.01f, 2f))
                            .height(stageHeight * (component.height / canvasHeight).coerceIn(0.01f, 2f))
                            .graphicsLayer(alpha = component.alpha)
                            .background(Color(component.color), RoundedCornerShape(2.dp))
                            .then(
                                if (component.borderWidth > 0f) {
                                    Modifier.border(
                                        component.borderWidth.dp,
                                        Color(component.borderColor),
                                        RoundedCornerShape(2.dp)
                                    )
                                } else {
                                    Modifier
                                }
                            )
                            .padding(3.dp)
                        Box(modifier = componentModifier) {
                            if (component.text.isNotBlank()) {
                                Text(
                                    text = component.text,
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
            }
        }

        val toolsMenuExpanded = remember { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
        ) {
            IconButton(onClick = { toolsMenuExpanded.value = true }) {
                Text(
                    text = "⋮",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge
                )
            }
            DropdownMenu(
                expanded = toolsMenuExpanded.value,
                onDismissRequest = { toolsMenuExpanded.value = false }
            ) {
                DropdownMenuItem(
                    text = { Text("История · ${state.historyEntries.size}") },
                    onClick = {
                        toolsMenuExpanded.value = false
                        onHistoryClick()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Ресурсы · ${state.assets.size}") },
                    onClick = {
                        toolsMenuExpanded.value = false
                        onResourcesClick()
                    }
                )
            }
        }

        val diagnosticMessage = state.errorMessage ?: state.eventDescription.takeIf {
            it.startsWith("Неподдерживаемое выражение") ||
                it.startsWith("Страница ") ||
                it.contains("ожидает host adapter") ||
                it.contains("ожидает обработку хоста") ||
                it.startsWith("VM ожидает переход")
        }
        // The tap target remains the whole viewport during an empty WAIT, but
        // an empty script control is not dialogue and must not create a fake panel.
        val showMessagePanel = !state.isCompleted && (
            state.isLoading ||
                (state.dialogueText.isNotEmpty() && state.messageWindowVisible)
            )
        if (showMessagePanel) {
            Surface(
                color = Color(0xE6101216),
                contentColor = Color.White,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 18.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = state.canContinue &&
                            !state.historyVisible && !state.resourcesVisible,
                        onClick = onContinue
                    )
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (state.isLoading) {
                        Text(
                            text = "Загрузка игры…",
                            color = Color(0xFFF4F1EA),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    } else {
                        state.speakerName?.takeIf {
                            state.messageWindowVisible && it.isNotBlank()
                        }?.let { speaker ->
                            Text(
                                text = speaker,
                                color = Color(0xFFF2B766),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        if (state.dialogueText.isNotEmpty() && state.messageWindowVisible) {
                            Text(
                                text = state.dialogueText,
                                color = Color(0xFFF4F1EA),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }

                    if (state.canContinue) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Text(
                                text = "КОСНИТЕСЬ ДЛЯ ПРОДОЛЖЕНИЯ",
                                color = Color(0xFFF2B766),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
        diagnosticMessage?.let { message ->
            Surface(
                color = Color(0xB8101216),
                contentColor = Color(0xFFFFB4AB),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            ) {
                Text(
                    text = message,
                    color = Color(0xFFFFB4AB),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
        state.errorLog?.let { log ->
            Surface(
                color = Color(0xF0181B22),
                contentColor = Color.White,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.94f)
                    .padding(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Ошибка запуска LiveMaker",
                        color = Color(0xFFFFB4AB),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = log,
                        color = Color(0xFFE6E1D9),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState())
                    )
                    Button(onClick = onCopyErrorLog, modifier = Modifier.fillMaxWidth()) {
                        Text("Скопировать лог")
                    }
                }
            }
        }

        state.audioStatus?.let { status ->
            Surface(
                color = Color(0xB8101216),
                contentColor = Color(0xFFA8ADB7),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(8.dp)
            ) {
                Text(
                    text = status,
                    color = Color(0xFFA8ADB7),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        if (state.isCompleted) {
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

        if (state.isLoading && state.dialogueText.isEmpty()) {
            Surface(
                color = Color(0xCC101216),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Text(
                    text = "Запуск LiveMaker…",
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
                )
            }
        }
    }

    if (state.historyVisible) {
        Dialog(
            onDismissRequest = onHistoryDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                color = Color(0xFF171B22),
                contentColor = Color.White,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .heightIn(max = 620.dp)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("История", style = MaterialTheme.typography.titleLarge)
                            state.historyFormatName?.let { format ->
                                Text(
                                    text = "Формат: $format",
                                    color = Color(0xFFA8ADB7),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                        Button(onClick = onHistoryDismiss) { Text("Закрыть") }
                    }
                    if (state.historyEntries.isEmpty()) {
                        Text(
                            "История пока пуста.",
                            color = Color(0xFFA8ADB7),
                            modifier = Modifier.padding(vertical = 18.dp)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 520.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(state.historyEntries.asReversed().withIndex().toList()) { indexed ->
                                val entry = indexed.value
                                Surface(
                                    color = Color(0xFF222832),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        entry.formatName?.let { format ->
                                            Text(
                                                format,
                                                color = Color(0xFFF2B766),
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                        Text(entry.text, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.resourcesVisible) {
        Dialog(
            onDismissRequest = onResourcesDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                color = Color(0xFF171B22),
                contentColor = Color.White,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth(0.96f)
                    .heightIn(max = 700.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Ресурсы · ${state.assets.size}",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Button(onClick = onResourcesDismiss) { Text("Закрыть") }
                    }
                    state.mediaDiagnostics?.let { diagnostics ->
                        val summary = diagnostics.indexedByExtension.entries
                            .sortedBy { it.key }
                            .joinToString { it.key.uppercase() + ": " + it.value }
                            .ifBlank { "нет" }
                        Text(
                            text = "Проверено: ${diagnostics.filesScanned} · архивы: " +
                                "${diagnostics.archivesParsed}/${diagnostics.archiveCandidates} · $summary",
                            color = Color(0xFFA8ADB7),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    state.imageBitmap?.let { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = state.selectedAssetName,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 170.dp),
                            contentScale = ContentScale.Fit
                        )
                        state.selectedAssetName?.let { name ->
                            Text(name, color = Color(0xFFA8ADB7), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (state.assets.isEmpty()) {
                        Text("Ресурсы не найдены.", color = Color(0xFFA8ADB7))
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(state.assets, key = { it.id }) { asset ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF222832), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(asset.path, style = MaterialTheme.typography.bodySmall)
                                        Text(
                                            asset.storageDescription,
                                            color = Color(0xFFA8ADB7),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                    Button(onClick = { onAssetSelected(asset) }) {
                                        Text(
                                            if (asset.extension == "gal" || asset.extension == "png") {
                                                "Открыть"
                                            } else {
                                                "Играть"
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


private class LiveMakerImageByteArraySource(
    private val bytes: ByteArray
) : ReadOnlyRandomAccessSource {
    override val sizeBytes: Long
        get() = bytes.size.toLong()

    override fun readAt(
        position: Long,
        target: ByteArray,
        targetOffset: Int,
        length: Int
    ): Int {
        require(position >= 0L)
        require(targetOffset >= 0 && targetOffset <= target.size)
        require(length >= 0 && length <= target.size - targetOffset)
        if (length == 0) return 0
        if (position >= bytes.size.toLong()) return -1
        val count = minOf(length, bytes.size - position.toInt())
        System.arraycopy(bytes, position.toInt(), target, targetOffset, count)
        return count
    }

    override fun close() = Unit
}
