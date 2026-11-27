package io.github.helterfall.ramen.runtime.hsp3

import android.media.MediaPlayer
import java.io.File
import java.io.IOException

/** Minimal Android audio-bank implementation for HSP mmload/mmplay/mmstop. */
class AndroidHsp3AudioPlayer(private val cacheDirectory: File) : Hsp3AudioPlayer {
    private data class Bank(val player: MediaPlayer, val stagedFile: File)
    private val banks = linkedMapOf<Int, Bank>()
    private var closed = false

    @Synchronized
    override fun load(bank: Int, encodedAudio: ByteArray, mode: Int) {
        checkOpen()
        if (bank < 0) throw IOException("HSP mmload bank ID cannot be negative.")
        if (encodedAudio.isEmpty()) throw IOException("HSP mmload received an empty audio resource.")
        if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) {
            throw IOException("Could not create the HSP audio cache directory.")
        }
        val stagedFile = File.createTempFile("ramen_hsp3_${bank}_", ".audio", cacheDirectory)
        val player = MediaPlayer()
        try {
            stagedFile.writeBytes(encodedAudio)
            player.setDataSource(stagedFile.absolutePath)
            player.isLooping = false
            player.prepare()
            releaseBank(bank)
            banks[bank] = Bank(player, stagedFile)
        } catch (error: Exception) {
            runCatching { player.release() }
            stagedFile.delete()
            throw IOException("HSP mmload failed for bank $bank: ${error.message ?: error.javaClass.simpleName}", error)
        }
    }

    @Synchronized
    override fun play(bank: Int) {
        checkOpen()
        val player = banks[bank]?.player
            ?: throw IOException("HSP mmplay references unloaded audio bank $bank.")
        try {
            player.seekTo(0)
            player.start()
        } catch (error: Exception) {
            throw IOException("HSP mmplay failed for bank $bank: ${error.message ?: error.javaClass.simpleName}", error)
        }
    }

    @Synchronized
    override fun stop(bank: Int) {
        checkOpen()
        if (bank < 0) banks.values.forEach { stopPlayer(it.player) }
        else stopPlayer(banks[bank]?.player
            ?: throw IOException("HSP mmstop references unloaded audio bank $bank."))
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        banks.values.forEach(::release)
        banks.clear()
    }

    private fun stopPlayer(player: MediaPlayer) {
        try {
            if (player.isPlaying) player.pause()
            player.seekTo(0)
        } catch (error: Exception) {
            throw IOException("Could not stop an HSP audio bank: ${error.message ?: error.javaClass.simpleName}", error)
        }
    }

    private fun releaseBank(bank: Int) {
        banks.remove(bank)?.let(::release)
    }

    private fun release(bank: Bank) {
        runCatching { bank.player.release() }
        bank.stagedFile.delete()
    }

    private fun checkOpen() {
        if (closed) throw IOException("The HSP Android audio player is closed.")
    }
}
