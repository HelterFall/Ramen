package io.github.helterfall.ramen.runtime.resources

import io.github.helterfall.ramen.inspection.FileRandomAccessSource
import io.github.helterfall.ramen.inspection.ReadOnlyRandomAccessSource
import io.github.helterfall.ramen.runtime.engine.DirectoryGameSource
import io.github.helterfall.ramen.runtime.engine.BinarySignatureProbe
import io.github.helterfall.ramen.runtime.engine.EngineAdapterRegistry
import io.github.helterfall.ramen.runtime.engine.EngineProbeResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackArchiveReaderTest {
    @Test
    fun parsesIndexAndExtractsOnlyTheRequestedResource() {
        val archiveBytes = buildArchive(
            listOf(
                "images/title.bin" to byteArrayOf(1, 2, 3, 4),
                "audio/voice.ogg" to byteArrayOf(9, 8, 7, 6, 5)
            )
        )
        withArchiveFile(archiveBytes) { file ->
            FileRandomAccessSource(file).use { source ->
                val reader = PackArchiveReader(source)
                val index = reader.openArchive()

                assertEquals(1, index.version)
                assertEquals(2, index.entries.size)
                assertNotNull(index.find("images/title.bin"))
                assertEquals(4L, index.find("images/title.bin")?.size)
                assertArrayEquals(
                    byteArrayOf(1, 2, 3, 4),
                    reader.extractFile("images/title.bin")
                )
                assertArrayEquals(
                    byteArrayOf(9, 8, 7, 6, 5),
                    reader.openFile("audio/voice.ogg").use { it.readBytes() }
                )
            }
        }
    }

    @Test
    fun streamingReadIsBoundedToOnePayloadAndDoesNotCloseArchiveSource() {
        val first = ByteArray(32 * 1024) { (it % 251).toByte() }
        val second = byteArrayOf(99, 98, 97)
        withArchiveFile(buildArchive(listOf("large.dat" to first, "next.dat" to second))) { file ->
            FileRandomAccessSource(file).use { source ->
                val reader = PackArchiveReader(source)
                reader.openArchive()

                val stream = reader.openFile("large.dat")
                val prefix = ByteArray(31)
                assertEquals(31, stream.read(prefix))
                assertArrayEquals(first.copyOfRange(0, 31), prefix)
                stream.close()

                // Closing a resource stream must not close the shared positional source.
                assertArrayEquals(second, reader.extractFile("next.dat"))
            }
        }
    }

    @Test
    fun openArchiveReadsOnlyHeaderAndIndexBeforeAnyPayloadIsRequested() {
        val payload = ByteArray(256 * 1024) { (it % 239).toByte() }
        withArchiveFile(buildArchive(listOf("large.bin" to payload))) { file ->
            FileRandomAccessSource(file).use { delegate ->
                val source = RecordingSource(delegate)
                val reader = PackArchiveReader(source)
                val index = reader.openArchive()

                assertTrue(
                    source.requests.all { request ->
                        request.position + request.length <= index.dataStartOffset
                    }
                )

                source.requests.clear()
                val entry = index.find("large.bin") ?: throw AssertionError("Entry missing.")
                reader.openFile("large.bin").use { stream ->
                    val prefix = ByteArray(17)
                    assertEquals(17, stream.read(prefix))
                    assertArrayEquals(payload.copyOfRange(0, 17), prefix)
                }
                assertEquals(listOf(ReadRequest(entry.offset, 17)), source.requests)
            }
        }
    }

    @Test
    fun emptyArchiveHasAValidEmptyIndex() {
        withArchiveFile(buildArchive(emptyList())) { file ->
            FileRandomAccessSource(file).use { source ->
                val index = PackArchiveReader(source).openArchive()
                assertTrue(index.entries.isEmpty())
            }
        }
    }

    @Test
    fun byteArrayExtractionHasAnExplicitMemoryLimit() {
        withArchiveFile(buildArchive(listOf("large.bin" to byteArrayOf(1, 2, 3)))) { file ->
            FileRandomAccessSource(file).use { source ->
                val reader = PackArchiveReader(source, maxExtractBytes = 2)
                reader.openArchive()

                val exception = expectIOException { reader.extractFile("large.bin") }
                assertTrue(exception.message.orEmpty().contains("openFile"))
            }
        }
    }

    @Test
    fun malformedMagicAndUnsupportedVersionAreRejected() {
        val valid = buildArchive(listOf("one.bin" to byteArrayOf(1)))
        val wrongMagic = valid.copyOf().also { it[0] = 0x00 }
        val wrongVersion = valid.copyOf().also { writeInt(it, 8, 2) }

        assertMalformed(wrongMagic)
        assertMalformed(wrongVersion)
    }

    @Test
    fun truncatedHeaderAndIndexAreRejected() {
        assertMalformed(byteArrayOf(0x49, 0x46, 0x20, 0x50))
        val valid = buildArchive(listOf("one.bin" to byteArrayOf(1, 2, 3)))
        val truncatedIndex = valid.copyOf().also { writeInt(it, 16, 0x7FFF_FFF0) }
        assertMalformed(truncatedIndex)
    }

    @Test
    fun unsafeAndDuplicatePathsAreRejected() {
        assertMalformed(buildArchive(listOf("../outside.bin" to byteArrayOf(1))))
        assertMalformed(
            buildArchive(
                listOf(
                    "same.bin" to byteArrayOf(1),
                    "same.bin" to byteArrayOf(2)
                )
            )
        )
    }

    @Test
    fun payloadOutsideArchiveAndOverlappingPayloadRangesAreRejected() {
        val outside = buildArchive(listOf("one.bin" to byteArrayOf(1, 2, 3)))
            .also { writeLong(it, firstRecordOffsetField("one.bin"), Long.MAX_VALUE) }
        assertMalformed(outside)

        val overlapping = buildArchive(
            listOf(
                "first.bin" to byteArrayOf(1, 2, 3, 4),
                "second.bin" to byteArrayOf(5, 6, 7, 8)
            )
        )
        val firstOffset = readLong(overlapping, firstRecordOffsetField("first.bin"))
        writeLong(overlapping, secondRecordOffsetField("first.bin", "second.bin"), firstOffset + 1L)
        assertMalformed(overlapping)
    }

    @Test
    fun byteSignatureProbeValidatesResourceContainerIndex() {
        val root = Files.createTempDirectory("ramen-pack-probe").toFile()
        try {
            File(root, "arbitrary.extension").writeBytes(
                buildArchive(listOf("images/cover.bin" to byteArrayOf(4, 3, 2, 1)))
            )
            val source = DirectoryGameSource(root, sourceId = "pack-probe-valid")

            val result = BinarySignatureProbe().probeFile(source, "arbitrary.extension")

            assertTrue(result is EngineProbeResult.Match)
            val match = result as EngineProbeResult.Match
            assertEquals("resource-container", match.evidence.single().resourceType)
            assertTrue(match.evidence.single().description.contains("1 entries"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun byteSignatureProbeDetectsIfPacktMagicWhenTheIndexIsNotSupported() {
        val root = Files.createTempDirectory("ramen-pack-probe-bad").toFile()
        try {
            val broken = buildArchive(listOf("images/cover.bin" to byteArrayOf(4, 3, 2, 1)))
                .also { writeInt(it, 12, 500) }
            File(root, "broken.pack").writeBytes(broken)
            val source = DirectoryGameSource(root, sourceId = "pack-probe-invalid")

            val adapter = BinarySignatureProbe()
            val result = adapter.probeFile(source, "broken.pack")
            assertTrue(result is EngineProbeResult.Match)
            val match = result as EngineProbeResult.Match
            assertEquals("resource-container", match.evidence.single().resourceType)
            assertTrue(match.evidence.single().description.contains("did not validate"))
            assertEquals(null, match.formatVersion)

            val summary = EngineAdapterRegistry(listOf(adapter)).probe(source)
            assertEquals(1, summary.matches.size)
            assertTrue(summary.malformed.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun assertMalformed(bytes: ByteArray) {
        withArchiveFile(bytes) { file ->
            FileRandomAccessSource(file).use { source ->
                val exception = expectIOException { PackArchiveReader(source).openArchive() }
                assertTrue(exception is PackArchiveException)
            }
        }
    }

    private fun expectIOException(block: () -> Unit): IOException {
        try {
            block()
        } catch (exception: IOException) {
            return exception
        }
        throw AssertionError("Expected IOException.")
    }

    private fun withArchiveFile(bytes: ByteArray, block: (File) -> Unit) {
        val file = Files.createTempFile("ramen-pack-archive", ".pack").toFile()
        try {
            file.writeBytes(bytes)
            block(file)
        } finally {
            file.delete()
        }
    }

    private fun buildArchive(entries: List<Pair<String, ByteArray>>): ByteArray {
        val index = ByteArrayOutputStream()
        var nextPayloadOffset = HEADER_SIZE.toLong() +
            entries.sumOf { pathAndPayload ->
                U16_BYTES + pathAndPayload.first.toByteArray(Charsets.UTF_8).size +
                    U64_BYTES + U64_BYTES
            }.toLong()

        entries.forEach { (path, payload) ->
            val pathBytes = path.toByteArray(Charsets.UTF_8)
            index.write(littleEndianShort(pathBytes.size))
            index.write(pathBytes)
            index.write(littleEndianLong(nextPayloadOffset))
            index.write(littleEndianLong(payload.size.toLong()))
            nextPayloadOffset += payload.size.toLong()
        }

        val indexBytes = index.toByteArray()
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .put("IF PACKT".toByteArray(Charsets.US_ASCII))
            .putInt(VERSION)
            .putInt(entries.size)
            .putInt(indexBytes.size)
            .array()

        return ByteArrayOutputStream().apply {
            write(header)
            write(indexBytes)
            entries.forEach { (_, payload) -> write(payload) }
        }.toByteArray()
    }

    private fun littleEndianShort(value: Int): ByteArray =
        ByteBuffer.allocate(U16_BYTES).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()

    private fun littleEndianLong(value: Long): ByteArray =
        ByteBuffer.allocate(U64_BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
        ByteBuffer.wrap(bytes, offset, Int.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(value)
    }

    private fun readLong(bytes: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(bytes, offset, U64_BYTES).order(ByteOrder.LITTLE_ENDIAN).long

    private fun writeLong(bytes: ByteArray, offset: Int, value: Long) {
        ByteBuffer.wrap(bytes, offset, U64_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(value)
    }

    private fun firstRecordOffsetField(path: String): Int =
        HEADER_SIZE + U16_BYTES + path.toByteArray(Charsets.UTF_8).size

    private fun secondRecordOffsetField(firstPath: String, secondPath: String): Int =
        firstRecordOffsetField(firstPath) + U64_BYTES + U64_BYTES +
            U16_BYTES + secondPath.toByteArray(Charsets.UTF_8).size

    private companion object {
        const val HEADER_SIZE = 20
        const val VERSION = 1
        const val U16_BYTES = 2
        const val U64_BYTES = 8
    }

    private data class ReadRequest(val position: Long, val length: Int)

    private class RecordingSource(
        private val delegate: ReadOnlyRandomAccessSource
    ) : ReadOnlyRandomAccessSource {
        val requests = mutableListOf<ReadRequest>()

        override val sizeBytes: Long
            get() = delegate.sizeBytes

        override fun readAt(
            position: Long,
            target: ByteArray,
            targetOffset: Int,
            length: Int
        ): Int {
            requests += ReadRequest(position, length)
            return delegate.readAt(position, target, targetOffset, length)
        }

        override fun close() {
            delegate.close()
        }
    }

}
