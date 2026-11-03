package io.github.helterfall.ramen.storage

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StorageSpaceGuardTest {
    @Test
    fun allowsWritesLargerThanFormer512MiBCeilingWhenStorageIsAvailable() {
        val directory = Files.createTempDirectory("ramen-storage-guard").toFile()
        try {
            val available = 2L * 1024L * 1024L * 1024L
            val guard = StorageSpaceGuard(directory) { available }

            // The former 512 MiB limit is no longer a parser/import policy.
            guard.requireCapacity(600L * 1024L * 1024L)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsWritesThatWouldConsumeTheReservedFreeSpace() {
        val directory = Files.createTempDirectory("ramen-storage-reserve").toFile()
        try {
            val guard = StorageSpaceGuard(directory) { 64L * 1024L * 1024L }

            assertThrows(IOException::class.java) {
                guard.requireCapacity(50L * 1024L * 1024L)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rechecksFreeSpaceAsTheDestinationFills() {
        val directory = Files.createTempDirectory("ramen-storage-recheck").toFile()
        try {
            var available = 2L * 1024L * 1024L * 1024L
            val guard = StorageSpaceGuard(directory) { available }

            guard.requireCapacity(64L * 1024L)
            available = 32L * 1024L * 1024L
            assertThrows(IOException::class.java) {
                guard.requireCapacity(20L * 1024L * 1024L)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun guardedOutputStreamChecksEachWriteAndPreservesBytes() {
        val directory = Files.createTempDirectory("ramen-guarded-stream").toFile()
        try {
            val sink = ByteArrayOutputStream()
            val guard = StorageSpaceGuard(directory) { 128L * 1024L * 1024L }
            StorageGuardedOutputStream(sink, guard).use { output ->
                output.write(byteArrayOf(1, 2, 3, 4))
            }

            assertEquals(listOf<Byte>(1, 2, 3, 4), sink.toByteArray().toList())
        } finally {
            directory.deleteRecursively()
        }
    }
}
