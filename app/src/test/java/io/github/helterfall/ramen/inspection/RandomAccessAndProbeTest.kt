package io.github.helterfall.ramen.inspection

import java.io.EOFException
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RandomAccessAndProbeTest {
    @Test
    fun fileSourceReadsAtIndependentOffsets() {
        val directory = Files.createTempDirectory("ramen-random-access").toFile()
        try {
            val file = File(directory, "sample.bin")
            file.writeBytes("0123456789".toByteArray())

            FileRandomAccessSource(file).use { source ->
                val first = ByteArray(3)
                val second = ByteArray(4)

                assertEquals(3, source.readAt(2, first))
                assertEquals(4, source.readAt(6, second))
                assertEquals("234", first.toString(Charsets.US_ASCII))
                assertEquals("6789", second.toString(Charsets.US_ASCII))
                assertEquals(10L, source.sizeBytes)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun readFullyRejectsRangePastEnd() {
        val directory = Files.createTempDirectory("ramen-random-access-eof").toFile()
        try {
            val file = File(directory, "sample.bin")
            file.writeBytes(byteArrayOf(1, 2, 3))

            FileRandomAccessSource(file).use { source ->
                val target = ByteArray(2)
                val error = runCatching { source.readFullyAt(2, target) }.exceptionOrNull()
                assertTrue(error is EOFException)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun sha256IsIncrementalAndMatchesKnownDigest() {
        val directory = Files.createTempDirectory("ramen-sha256").toFile()
        try {
            val file = File(directory, "sample.txt")
            file.writeText("abc")

            val result = Sha256FileHasher(bufferSizeBytes = 2).fingerprint(file)

            assertEquals(3L, result.sizeBytes)
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                result.sha256Hex
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun emptyFileHasStandardEmptySha256() {
        val directory = Files.createTempDirectory("ramen-sha256-empty").toFile()
        try {
            val file = File(directory, "empty.bin")
            file.writeBytes(byteArrayOf())

            val result = Sha256FileHasher().fingerprint(file)

            assertEquals(0L, result.sizeBytes)
            assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                result.sha256Hex
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun fixedSignatureProbeDistinguishesMatchNoMatchAndMalformed() {
        val directory = Files.createTempDirectory("ramen-container-probes").toFile()
        try {
            val matching = File(directory, "match.bin").apply {
                writeBytes(byteArrayOf(0x00, 0x50, 0x4B, 0x03, 0x04))
            }
            val nonMatching = File(directory, "other.bin").apply {
                writeBytes(byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44))
            }
            val truncated = File(directory, "short.bin").apply {
                writeBytes(byteArrayOf(0x00, 0x50, 0x4B))
            }
            val probe = FixedSignatureProbe(
                formatId = "zip-local-header",
                signature = byteArrayOf(0x50, 0x4B, 0x03, 0x04),
                offset = 1
            )

            FileRandomAccessSource(matching).use { source ->
                assertEquals(ContainerProbeStatus.MATCH, probe.probe(source).status)
            }
            FileRandomAccessSource(nonMatching).use { source ->
                assertEquals(ContainerProbeStatus.NO_MATCH, probe.probe(source).status)
            }
            FileRandomAccessSource(truncated).use { source ->
                assertEquals(ContainerProbeStatus.MALFORMED, probe.probe(source).status)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun runnerRetainsEvidenceForEachProbe() {
        val directory = Files.createTempDirectory("ramen-probe-runner").toFile()
        try {
            val file = File(directory, "image.bin")
            file.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
            val runner = ContainerProbeRunner(
                listOf(
                    FixedSignatureProbe("png", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)),
                    FixedSignatureProbe("zip", byteArrayOf(0x50, 0x4B, 0x03, 0x04))
                )
            )

            FileRandomAccessSource(file).use { source ->
                val results = runner.run(source)
                assertEquals(2, results.size)
                assertEquals(ContainerProbeStatus.MATCH, results[0].status)
                assertEquals(ContainerProbeStatus.NO_MATCH, results[1].status)
                assertFalse(results[0].evidence.isBlank())
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
