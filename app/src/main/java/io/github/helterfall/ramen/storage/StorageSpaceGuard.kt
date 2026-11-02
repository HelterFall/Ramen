package io.github.helterfall.ramen.storage

import java.io.IOException
import java.io.OutputStream
import java.io.File

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

/** Output stream that rechecks available storage before every write. */
class StorageGuardedOutputStream(
    private val delegate: OutputStream,
    private val guard: StorageSpaceGuard
) : OutputStream() {
    override fun write(value: Int) {
        guard.requireCapacity(1L)
        delegate.write(value)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (length == 0) return
        guard.requireCapacity(length.toLong())
        delegate.write(buffer, offset, length)
    }

    override fun flush() = delegate.flush()

    override fun close() = delegate.close()
}
