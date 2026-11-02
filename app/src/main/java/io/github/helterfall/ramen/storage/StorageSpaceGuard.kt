package io.github.helterfall.ramen.storage

import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Checks writes against the storage that is actually available on the target volume.
 *
 * This deliberately has no per-file size ceiling. It preserves headroom for the OS
 * and other app work instead of rejecting a valid game because it crosses an
 * arbitrary fixed number of megabytes.
 */
class StorageSpaceGuard(
    private val directory: File,
    private val availableSpaceBytes: () -> Long = { directory.usableSpace }
) {
    fun requireCapacity(bytesToWrite: Long) {
        require(bytesToWrite >= 0L) { "bytesToWrite must not be negative." }
        if (bytesToWrite == 0L) return

        val available = availableSpaceBytes().coerceAtLeast(0L)
        val reserve = minOf(
            MAX_RESERVE_BYTES,
            maxOf(MIN_RESERVE_BYTES, available / RESERVE_DIVISOR)
        )
        val writable = (available - reserve).coerceAtLeast(0L)
        if (bytesToWrite > writable) {
            throw IOException(
                "Недостаточно свободного места для продолжения. " +
                    "Освободи место на устройстве и повтори импорт."
            )
        }
    }

    companion object {
        private const val MIN_RESERVE_BYTES = 16L * 1024L * 1024L
        private const val MAX_RESERVE_BYTES = 256L * 1024L * 1024L
        private const val RESERVE_DIVISOR = 20L
    }
}

/**
 * Buffers small writes and checks available storage immediately before each
 * bounded batch reaches the destination. The buffer avoids a filesystem-space
 * query for every 8–64 KiB read made by importers.
 */
class StorageGuardedOutputStream(
    private val delegate: OutputStream,
    private val guard: StorageSpaceGuard
) : OutputStream() {
    private val buffer = ByteArray(CHECK_BUFFER_BYTES)
    private var bufferedBytes = 0

    override fun write(value: Int) {
        if (bufferedBytes == buffer.size) flushBuffer()
        buffer[bufferedBytes++] = value.toByte()
    }

    override fun write(source: ByteArray, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset > source.size - length) {
            throw IndexOutOfBoundsException("Invalid source range.")
        }
        var sourceOffset = offset
        var remaining = length
        while (remaining > 0) {
            if (bufferedBytes == buffer.size) flushBuffer()
            val count = minOf(remaining, buffer.size - bufferedBytes)
            source.copyInto(
                destination = buffer,
                destinationOffset = bufferedBytes,
                startIndex = sourceOffset,
                endIndex = sourceOffset + count
            )
            bufferedBytes += count
            sourceOffset += count
            remaining -= count
        }
    }

    override fun flush() {
        flushBuffer()
        delegate.flush()
    }

    override fun close() {
        try {
            flush()
        } finally {
            delegate.close()
        }
    }

    private fun flushBuffer() {
        if (bufferedBytes == 0) return
        guard.requireCapacity(bufferedBytes.toLong())
        delegate.write(buffer, 0, bufferedBytes)
        bufferedBytes = 0
    }

    companion object {
        private const val CHECK_BUFFER_BYTES = 256 * 1024
    }
}
