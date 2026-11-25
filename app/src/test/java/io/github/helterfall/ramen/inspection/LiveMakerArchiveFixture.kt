package io.github.helterfall.ramen.inspection

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.DeflaterOutputStream

internal data class LiveMakerFixtureFile(
    val name: String,
    val payload: ByteArray,
    val compressionFlag: Int = 1
)

/**
 * Creates minimal VF v102 fixtures that use pylivemaker's documented directory transforms.
 * This is test-only data; no external game files are needed.
 */
internal object LiveMakerArchiveFixture {
    private const val MASK = 0xFFFF_FFFFL
    private const val SIGN_BIT = 0x8000_0000L
    private const val SEED = 0x75D6EE39L
    private val CP932 = Charset.forName("windows-31j")

    fun standalone(files: List<LiveMakerFixtureFile>): ByteArray =
        build(files, embedded = false, archiveOffset = 0)

    fun executable(
        files: List<LiveMakerFixtureFile>,
        archiveOffset: Int = 32
    ): ByteArray {
        require(archiveOffset >= 2)
        return build(files, embedded = true, archiveOffset = archiveOffset)
    }

    fun zlib(payload: ByteArray): ByteArray =
        ByteArrayOutputStream().also { buffer ->
            DeflaterOutputStream(buffer).use { it.write(payload) }
        }.toByteArray()

    private fun build(
        files: List<LiveMakerFixtureFile>,
        embedded: Boolean,
        archiveOffset: Int
    ): ByteArray {
        require(files.isNotEmpty())
        require(files.all { it.compressionFlag in 0..3 })

        val names = files.map { it.name.toByteArray(CP932) }
        val directorySize = 10L +
            names.sumOf { 4L + it.size.toLong() } +
            (files.size + 1L) * 8L +
            files.size.toLong() +
            files.size.toLong() * 4L +
            files.size.toLong() * 4L +
            files.size.toLong()

        val offsets = LongArray(files.size + 1)
        var runningOffset = directorySize
        files.forEachIndexed { index, file ->
            offsets[index] = runningOffset
            runningOffset += file.payload.size.toLong()
        }
        offsets[files.size] = runningOffset

        val directoryAndData = ByteArrayOutputStream()
        directoryAndData.write('v'.code)
        directoryAndData.write('f'.code)
        writeUInt32(directoryAndData, 102L)
        writeUInt32(directoryAndData, files.size.toLong())

        var nameState = 0L
        names.forEach { name ->
            writeUInt32(directoryAndData, name.size.toLong())
            name.forEach { byte ->
                nameState = nextKey(nameState)
                directoryAndData.write(
                    (byte.toInt() xor (nameState and 0xFFL).toInt()) and 0xFF
                )
            }
        }

        var lowState = 0L
        var highState = 0L
        offsets.forEach { offset ->
            lowState = nextKey(lowState)
            val low = (offset and MASK) xor lowState
            writeUInt32(directoryAndData, low)

            highState = nextKey(highState)
            val highWord = (offset ushr 32) and MASK
            val transformedHigh = highWord xor highState
            val encodedHigh = if ((transformedHigh and SIGN_BIT) != 0L) MASK else 0L
            writeUInt32(directoryAndData, encodedHigh)
        }

        files.forEach { directoryAndData.write(it.compressionFlag) }
        repeat(files.size) { writeUInt32(directoryAndData, 0L) } // unk1
        repeat(files.size) { writeUInt32(directoryAndData, 0L) } // checksum
        repeat(files.size) { directoryAndData.write(0) } // encrypt_flag
        files.forEach { directoryAndData.write(it.payload) }

        val vfBytes = directoryAndData.toByteArray()
        check(vfBytes.size.toLong() == offsets.last())

        if (!embedded) return vfBytes

        val executable = ByteArrayOutputStream()
        executable.write('M'.code)
        executable.write('Z'.code)
        executable.write(ByteArray(archiveOffset - 2))
        executable.write(vfBytes)
        writeUInt32(executable, archiveOffset.toLong())
        executable.write('l'.code)
        executable.write('v'.code)
        return executable.toByteArray()
    }

    private fun nextKey(previous: Long): Long =
        ((previous shl 2) + previous + SEED) and MASK

    private fun writeUInt32(output: ByteArrayOutputStream, value: Long) {
        repeat(4) { index ->
            output.write(((value ushr (index * 8)) and 0xFFL).toInt())
        }
    }
}
