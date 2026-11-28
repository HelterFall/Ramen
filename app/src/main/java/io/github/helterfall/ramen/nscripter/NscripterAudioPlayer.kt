package io.github.helterfall.ramen.nscripter

import android.content.Context
import android.media.MediaPlayer
import java.io.Closeable

class NscripterAudioPlayer(
    context: Context,
    private val resources: NscripterResourceRepository
) : Closeable {
    private val appContext = context.applicationContext
    private val channels = HashMap<Int, MediaPlayer>()

    fun apply(actions: List<NscripterAudioAction>) {
        actions.forEach { action ->
            if (action.stop) {
                if (action.channel < 0) stopAll() else stop(action.channel)
            } else {
                val file = action.fileName ?: return@forEach
                play(action.channel, file, action.loop)
            }
        }
    }

    private fun play(channel: Int, name: String, loop: Boolean) {
        stop(channel)
        val file = resources.materializeAudio(name) ?: return
        val player = MediaPlayer()
        channels[channel] = player
        player.setOnPreparedListener {
            it.isLooping = loop
            it.start()
        }
        player.setOnCompletionListener { completed ->
            if (channels[channel] === completed) {
                channels.remove(channel)
                completed.release()
            }
        }
        player.setOnErrorListener { failed, _, _ ->
            if (channels[channel] === failed) channels.remove(channel)
            failed.release()
            true
        }
        runCatching {
            player.setDataSource(file.absolutePath)
            player.prepareAsync()
        }.onFailure {
            channels.remove(channel)
            player.release()
        }
    }

    private fun stop(channel: Int) {
        channels.remove(channel)?.let { player ->
            runCatching { if (player.isPlaying) player.stop() }
            player.release()
        }
    }

    private fun stopAll() = channels.keys.toList().forEach(::stop)
    override fun close() = stopAll()
}
