package io.github.helterfall.ramen.runtime.systemnnn

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemNnnLooseAudioFormatTest {

    @Test
    fun packTypeZeroStagesWavePayloadAfterTheNativeHeader() {
        val root = Files.createTempDirectory("ramen-loose-vaw-wave").toFile()
        val source = File(root, "vaw/se/ambient.vaw")
        val cache = File(root, "cache")
        try {
            source.parentFile?.mkdirs()
            val header = ByteArray(0x40) { ' '.code.toByte() }
            "PACKTYPE=0      ".toByteArray(Charsets.US_ASCII).copyInto(header, 0x30)
            val wave = ByteArray(48)
            "RIFF".toByteArray(Charsets.US_ASCII).copyInto(wave, 0)
            "WAVE".toByteArray(Charsets.US_ASCII).copyInto(wave, 8)
            "fmt ".toByteArray(Charsets.US_ASCII).copyInto(wave, 12)
            source.writeBytes(header + wave)

            val repository = SystemNnnLooseAudioRepository(root, listOf(source), cache)
            assertEquals(1, repository.resources.size)
            assertEquals(0, repository.resources.single().packType)
            assertEquals("WAVE", repository.resources.single().mediaFormat)

            val staged = repository.materialize("ambient")
            assertEquals(wave.toList(), staged.readBytes().toList())
            repository.close()
            assertFalse(staged.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun packTypeOneDecodesDifferentialPcmIntoWaveOutput() {
        val root = Files.createTempDirectory("ramen-loose-vaw-delta").toFile()
        val source = File(root, "vaw/sys/ambient.vaw")
        val cache = File(root, "cache")
        try {
            source.parentFile?.mkdirs()
            val wrapper = ByteArray(0x40) { ' '.code.toByte() }
            "PACKTYPE=1      ".toByteArray(Charsets.US_ASCII).copyInto(wrapper, 0x30)

            val waveHeader = ByteArray(0x14 + 16)
            "RIFF".toByteArray(Charsets.US_ASCII).copyInto(waveHeader, 0)
            putU32(waveHeader, 4, 30) // 36-byte WAVE header + one 16-bit sample.
            "WAVE".toByteArray(Charsets.US_ASCII).copyInto(waveHeader, 8)
            "fmt ".toByteArray(Charsets.US_ASCII).copyInto(waveHeader, 12)
            putU32(waveHeader, 16, 16)
            val packedSample = byteArrayOf(0) // zero-bit sample code decodes to a zero delta.
            source.writeBytes(wrapper + waveHeader + packedSample)

            val repository = SystemNnnLooseAudioRepository(root, listOf(source), cache)
            val staged = repository.materialize("vaw/sys/ambient.vaw")
            assertEquals((waveHeader + byteArrayOf(0, 0)).toList(), staged.readBytes().toList())
            repository.close()
            assertFalse(staged.exists())
        } finally {
            root.deleteRecursively()
        }
    }


    @Test
    fun packTypeOneRejectsAStreamMissingTheFinalSampleHeaderNibble() {
        val root = Files.createTempDirectory("ramen-loose-vaw-truncated-delta").toFile()
        val source = File(root, "vaw/sys/truncated.vaw")
        val cache = File(root, "cache")
        try {
            source.parentFile?.mkdirs()
            val wrapper = ByteArray(0x40) { ' '.code.toByte() }
            "PACKTYPE=1      ".toByteArray(Charsets.US_ASCII).copyInto(wrapper, 0x30)

            val waveHeader = ByteArray(0x14 + 16)
            "RIFF".toByteArray(Charsets.US_ASCII).copyInto(waveHeader, 0)
            putU32(waveHeader, 4, 34) // 36-byte WAVE header + three 16-bit samples.
            "WAVE".toByteArray(Charsets.US_ASCII).copyInto(waveHeader, 8)
            "fmt ".toByteArray(Charsets.US_ASCII).copyInto(waveHeader, 12)
            putU32(waveHeader, 16, 16)
            // One byte contains two 4-bit sample headers, but the WAVE header
            // declares three samples. The old decoder silently invented a
            // zero-delta third sample when it reached EOF.
            source.writeBytes(wrapper + waveHeader + byteArrayOf(0))

            val repository = SystemNnnLooseAudioRepository(root, listOf(source), cache)
            try {
                assertThrows(IOException::class.java) {
                    repository.materialize("truncated")
                }
            } finally {
                repository.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun packTypeTwoSkipsTheNativeWaveDescriptorBeforeOgg() {
        val root = Files.createTempDirectory("ramen-loose-vaw-ogg").toFile()
        val source = File(root, "vaw/sys/music.vaw")
        try {
            source.parentFile?.mkdirs()
            val header = ByteArray(0x40) { ' '.code.toByte() }
            "PACKTYPE=2      ".toByteArray(Charsets.US_ASCII).copyInto(header, 0x30)
            val descriptor = ByteArray(0x2C)
            val ogg = "OggSafter-descriptor".toByteArray(Charsets.US_ASCII)
            source.writeBytes(header + descriptor + ogg)

            val repository = SystemNnnLooseAudioRepository(root, listOf(source), File(root, "cache"))
            assertEquals(0x6CL, repository.resources.single().payloadOffsetBytes)
            val staged = repository.materialize("music")
            assertEquals(ogg.toList(), staged.readBytes().toList())
            repository.close()
            assertFalse(staged.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun packTypeSixRequiresOggMarkerAndStagesOnlyTheStream() {
        val root = Files.createTempDirectory("ramen-loose-wgq-ogg").toFile()
        val source = File(root, "wgq/theme.wgq")
        val cache = File(root, "cache")
        try {
            source.parentFile?.mkdirs()
            val header = ByteArray(0x40) { ' '.code.toByte() }
            "OGG ".toByteArray(Charsets.US_ASCII).copyInto(header, 0x10)
            "PACKTYPE=6      ".toByteArray(Charsets.US_ASCII).copyInto(header, 0x30)
            val ogg = "OggSsynthetic".toByteArray(Charsets.US_ASCII)
            source.writeBytes(header + ogg)

            val repository = SystemNnnLooseAudioRepository(root, listOf(source), cache)
            assertTrue(repository.matches("theme").size == 1)
            assertEquals(6, repository.resources.single().packType)
            val staged = repository.materialize("theme.wgq")
            assertEquals(ogg.toList(), staged.readBytes().toList())
            repository.close()
            assertFalse(staged.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun putU32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }
}
