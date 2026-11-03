package io.github.helterfall.ramen.runtime.systemnnn

import android.media.MediaPlayer
import java.io.Closeable
import java.io.File
import java.io.IOException

enum class SystemNnnAudioPlaybackState {
    PREPARING,
    PLAYING,
    PAUSED,
    COMPLETED,
    STOPPED,
    ERROR
}

data class SystemNnnAudioPlaybackStatus(
    val state: SystemNnnAudioPlaybackState,
    val resourceName: String,
    val message: String? = null
)

/**
 * MediaPlayer adapter for Ogg files staged by SystemNnnVpkAudioRepository.
 * The player takes ownership of the staged file and deletes it on stop,
 * completion, error, or close. Call its control methods on the main thread.
 */
class SystemNnnAudioPlayer(
    private val onStatusChanged: (SystemNnnAudioPlaybackStatus) -> Unit = {}
) : Closeable {

    private var mediaPlayer: MediaPlayer? = null
    private var stagedFile: File? = null
    private var currentName: String = ""

    @Throws(IOException::class)
    fun play(oggFile: File, resourceName: String = oggFile.name) {
        stop()
        if (!oggFile.isFile) throw IOException("Staged Ogg file does not exist.")

        currentName = resourceName
        stagedFile = oggFile
        val player = MediaPlayer()
        mediaPlayer = player

        player.setOnPreparedListener { prepared ->
            if (mediaPlayer === prepared) {
                runCatching { prepared.start() }
                    .onSuccess {
                        publish(SystemNnnAudioPlaybackState.PLAYING)
                    }
                    .onFailure { error ->
                        release(deleteFile = true)
                        publish(SystemNnnAudioPlaybackState.ERROR, error.message)
                    }
            }
        }
        player.setOnCompletionListener { completed ->
            if (mediaPlayer === completed) {
                release(deleteFile = true)
                publish(SystemNnnAudioPlaybackState.COMPLETED)
            }
        }
        player.setOnErrorListener { failed, what, extra ->
            if (mediaPlayer === failed) {
                release(deleteFile = true)
                publish(
                    SystemNnnAudioPlaybackState.ERROR,
                    "Android MediaPlayer error $what/$extra."
                )
            }
            true
        }

        try {
            player.setDataSource(oggFile.absolutePath)
            publish(SystemNnnAudioPlaybackState.PREPARING)
            player.prepareAsync()
        } catch (error: Exception) {
            release(deleteFile = true)
            publish(SystemNnnAudioPlaybackState.ERROR, error.message)
            throw IOException("Could not prepare Android MediaPlayer.", error)
        }
    }

    fun pause() {
        val player = mediaPlayer ?: return
        runCatching {
            if (player.isPlaying) {
                player.pause()
                publish(SystemNnnAudioPlaybackState.PAUSED)
            }
        }.onFailure { error ->
            release(deleteFile = true)
            publish(SystemNnnAudioPlaybackState.ERROR, error.message)
        }
    }

    fun resume() {
        val player = mediaPlayer ?: return
        runCatching {
            if (!player.isPlaying) {
                player.start()
                publish(SystemNnnAudioPlaybackState.PLAYING)
            }
        }.onFailure { error ->
            release(deleteFile = true)
            publish(SystemNnnAudioPlaybackState.ERROR, error.message)
        }
    }

    fun stop() {
        val hadPlayback = mediaPlayer != null || stagedFile != null
        release(deleteFile = true)
        if (hadPlayback) publish(SystemNnnAudioPlaybackState.STOPPED)
    }

    private fun release(deleteFile: Boolean) {
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
        if (deleteFile) {
            stagedFile?.let { file -> runCatching { file.delete() } }
            stagedFile = null
        }
    }

    private fun publish(state: SystemNnnAudioPlaybackState, message: String? = null) {
        onStatusChanged(SystemNnnAudioPlaybackStatus(state, currentName, message))
    }

    override fun close() = stop()
}
