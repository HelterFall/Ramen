package io.github.helterfall.ramen.runtime.systemnnn

import android.media.MediaPlayer
import java.io.Closeable
import java.io.File
import java.io.IOException

enum class SystemNnnAudioPlaybackState { PREPARING, PLAYING, PAUSED, COMPLETED, STOPPED, ERROR }

data class SystemNnnAudioPlaybackStatus(
    val state: SystemNnnAudioPlaybackState,
    val resourceName: String,
    val message: String? = null
)

/** Tracks lifecycle pause requests that may arrive before MediaPlayer finishes preparing. */
internal class SystemNnnAudioPlaybackGate {
    private var prepared = false
    private var pauseRequested = false

    fun beginPreparation() {
        prepared = false
    }

    fun markPrepared(): Boolean {
        prepared = true
        return !pauseRequested
    }

    fun requestPause() {
        pauseRequested = true
    }

    fun requestResume() {
        pauseRequested = false
    }

    fun isPrepared(): Boolean = prepared

    fun shouldStartPreparedPlayer(): Boolean = prepared && !pauseRequested

    fun reset() {
        prepared = false
        pauseRequested = false
    }
}

/** Plays native WGQ Ogg segments in order and owns every staged file. */
class SystemNnnAudioPlayer(
    private val onStatusChanged: (SystemNnnAudioPlaybackStatus) -> Unit = {}
) : Closeable {
    private var mediaPlayer: MediaPlayer? = null
    private val playbackGate = SystemNnnAudioPlaybackGate()
    private var currentFile: File? = null
    private var playlistFiles: List<File> = emptyList()
    private var playlistIndex = 0
    private var completedPasses = 1
    private var repeatCount = 1
    private var infiniteLoopStartIndex: Int? = null
    private var currentName: String = ""

    @Throws(IOException::class)
    fun play(oggFile: File, resourceName: String = oggFile.name) {
        playPlaylist(listOf(oggFile), resourceName, repeatCount = 1)
    }

    /** repeatCount=0 means repeat the full playlist forever. */
    @Throws(IOException::class)
    fun playPlaylist(
        files: List<File>,
        resourceName: String,
        repeatCount: Int = 0,
        infiniteLoopStartIndex: Int? = null
    ) {
        stop()
        if (files.isEmpty()) throw IOException("The staged BGM playlist is empty.")
        if (repeatCount < 0) throw IOException("The native BGM repeat count is invalid.")
        if (infiniteLoopStartIndex != null && infiniteLoopStartIndex !in files.indices) {
            throw IOException("The native MML loop points outside the playlist.")
        }
        val missing = files.firstOrNull { !it.isFile }
        if (missing != null) {
            files.forEach { it.delete() }
            throw IOException("Staged WGQ segment does not exist: " + missing.name)
        }
        playlistFiles = files.toList()
        playlistIndex = 0
        completedPasses = 1
        this.repeatCount = repeatCount
        this.infiniteLoopStartIndex = infiniteLoopStartIndex
        currentName = resourceName
        try {
            startCurrentSegment()
        } catch (error: Exception) {
            clearPlaylist(deleteFiles = true)
            if (error is IOException) throw error
            throw IOException("Could not prepare the native BGM playlist.", error)
        }
    }

    private fun startCurrentSegment() {
        val file = playlistFiles.getOrNull(playlistIndex)
            ?: throw IOException("The BGM playlist cursor is invalid.")
        if (!file.isFile) throw IOException("Staged WGQ segment disappeared: " + file.name)
        currentFile = file
        val player = MediaPlayer()
        mediaPlayer = player

        playbackGate.beginPreparation()
        player.setOnPreparedListener { prepared ->
            if (mediaPlayer === prepared && playbackGate.markPrepared()) {
                runCatching { prepared.start() }
                    .onSuccess { publish(SystemNnnAudioPlaybackState.PLAYING) }
                    .onFailure { error -> fail(error.message) }
            } else if (mediaPlayer === prepared) {
                publish(SystemNnnAudioPlaybackState.PAUSED)
            }
        }
        player.setOnCompletionListener { completed ->
            if (mediaPlayer === completed) advanceAfterSegment()
        }
        player.setOnErrorListener { failed, what, extra ->
            if (mediaPlayer === failed) fail("Android MediaPlayer error " + what + "/" + extra + ".")
            true
        }

        try {
            player.setDataSource(file.absolutePath)
            publish(SystemNnnAudioPlaybackState.PREPARING)
            player.prepareAsync()
        } catch (error: Exception) {
            releaseCurrent(deleteFile = true)
            throw IOException("Could not prepare Android MediaPlayer.", error)
        }
    }

    private fun advanceAfterSegment() {
        // Keep completed segment files until the whole playlist ends. A later
        // finite repeat or an MML [0:...] loop may need to play them again.
        releaseCurrent(deleteFile = false)
        when {
            playlistIndex < playlistFiles.lastIndex -> playlistIndex++
            infiniteLoopStartIndex != null -> playlistIndex = checkNotNull(infiniteLoopStartIndex)
            repeatCount == 0 || completedPasses < repeatCount -> {
                completedPasses++
                playlistIndex = 0
            }
            else -> {
                playlistFiles.forEach { runCatching { it.delete() } }
                playlistFiles = emptyList()
                playlistIndex = 0
                infiniteLoopStartIndex = null
                publish(SystemNnnAudioPlaybackState.COMPLETED)
                return
            }
        }
        try {
            startCurrentSegment()
        } catch (error: Exception) {
            fail(error.message)
        }
    }

    fun pause() {
        val player = mediaPlayer ?: return
        playbackGate.requestPause()
        if (!playbackGate.isPrepared()) return
        runCatching {
            if (player.isPlaying) player.pause()
            publish(SystemNnnAudioPlaybackState.PAUSED)
        }.onFailure { error -> fail(error.message) }
    }

    fun resume() {
        val player = mediaPlayer ?: return
        playbackGate.requestResume()
        if (!playbackGate.isPrepared()) return
        runCatching {
            if (!player.isPlaying) player.start()
            publish(SystemNnnAudioPlaybackState.PLAYING)
        }.onFailure { error -> fail(error.message) }
    }

    fun stop() {
        val active = mediaPlayer != null || playlistFiles.isNotEmpty()
        clearPlaylist(deleteFiles = true)
        if (active) publish(SystemNnnAudioPlaybackState.STOPPED)
    }

    private fun fail(message: String?) {
        clearPlaylist(deleteFiles = true)
        publish(SystemNnnAudioPlaybackState.ERROR, message)
    }

    private fun clearPlaylist(deleteFiles: Boolean) {
        releaseCurrent(deleteFile = deleteFiles)
        if (deleteFiles) playlistFiles.forEach { runCatching { it.delete() } }
        playlistFiles = emptyList()
        playlistIndex = 0
        completedPasses = 1
        repeatCount = 1
        infiniteLoopStartIndex = null
        playbackGate.reset()
    }

    private fun releaseCurrent(deleteFile: Boolean) {
        val player = mediaPlayer
        mediaPlayer = null
        if (player != null) {
            runCatching { player.setOnPreparedListener(null) }
            runCatching { player.setOnCompletionListener(null) }
            runCatching { player.setOnErrorListener(null) }
            runCatching { if (player.isPlaying) player.stop() }
            runCatching { player.reset() }
            runCatching { player.release() }
        }
        val file = currentFile
        currentFile = null
        if (deleteFile) file?.let { runCatching { it.delete() } }
    }

    private fun publish(state: SystemNnnAudioPlaybackState, message: String? = null) {
        onStatusChanged(SystemNnnAudioPlaybackStatus(state, currentName, message))
    }

    override fun close() = stop()
}
