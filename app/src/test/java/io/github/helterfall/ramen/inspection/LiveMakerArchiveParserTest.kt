package io.github.helterfall.ramen.inspection

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveMakerArchiveParserTest {
    @Test
    fun parsesStandaloneVf102AndCopiesCp932StoredEntry() {
        val payload = "LiveMaker script payload".toByteArray(Charsets.UTF_8)
        val bytes = LiveMakerArchiveFixture.standalone(
            listOf(
                LiveMakerFixtureFile(
                    name = "シナリオ/main.lsb",
                    payload = payload,
                    compressionFlag = LiveMakerCompressionType.NONE.flag
                )
            )
        )

        withSource(bytes) { source ->
            val archive = LiveMakerArchiveParser().parse(source)
            assertEquals(0L, archive.archiveOffset)
            assertEquals(102, archive.version)
            assertEquals(1, archive.entries.size)
            assertEquals("シナリオ/main.lsb", archive.entries.single().name)
            assertEquals(payload.size.toLong(), archive.entries.single().compressedSizeBytes)
            assertEquals("シナリオ/main.lsb", LiveMakerArchiveParser().safeRelativePath(archive.entries.single()))

            val output = ByteArrayOutputStream()
            val copied = LiveMakerArchiveParser().copyEntryTo(
                source = source,
                archive = archive,
                entry = archive.entries.single(),
                output = output
            )
            assertEquals(payload.size.toLong(), copied)
            assertTrue(output.toByteArray().contentEquals(payload))
        }
    }

    @Test
    fun parsesMZExecutableTrailerAndCopiesZlibEntry() {
        val payload = ByteArray(32_000) { index -> (index * 31).toByte() }
        val compressed = LiveMakerArchiveFixture.zlib(payload)
        val bytes = LiveMakerArchiveFixture.executable(
            listOf(
                LiveMakerFixtureFile(
                    name = "data/resource.bin",
                    payload = compressed,
                    compressionFlag = LiveMakerCompressionType.ZLIB.flag
                )
            ),
            archiveOffset = 48
        )

        withSource(bytes) { source ->
            val parser = LiveMakerArchiveParser()
            val archive = parser.parse(source)
            val entry = archive.entries.single()

            assertEquals(48L, archive.archiveOffset)
            assertEquals("data/resource.bin", entry.name)
            assertEquals(compressed.size.toLong(), entry.compressedSizeBytes)

            val output = ByteArrayOutputStream()
            val copied = parser.copyEntryTo(source, archive, entry, output)
            assertEquals(payload.size.toLong(), copied)
            assertTrue(output.toByteArray().contentEquals(payload))
        }
    }

    @Test
    fun rejectsExecutableWithoutLiveMakerTrailer() {
        val bytes = LiveMakerArchiveFixture.executable(
            listOf(LiveMakerFixtureFile("main.lsb", byteArrayOf(1, 2, 3)))
        ).also { it[it.lastIndex] = 'x'.code.toByte() }

        withSource(bytes) { source ->
            val failure = runCatching { LiveMakerArchiveParser().parse(source) }
                .exceptionOrNull()
            assertTrue(failure is IOException)
            assertTrue(failure?.message.orEmpty().contains("trailer"))
        }
    }

    @Test
    fun rejectsTraversalEntryWhenCreatingExtractionPath() {
        val bytes = LiveMakerArchiveFixture.standalone(
            listOf(LiveMakerFixtureFile("../outside.txt", byteArrayOf(1)))
        )

        withSource(bytes) { source ->
            val parser = LiveMakerArchiveParser()
            val entry = parser.parse(source).entries.single()
            val failure = runCatching { parser.safeRelativePath(entry) }.exceptionOrNull()
            assertTrue(failure is IOException)
        }
    }

    private fun withSource(bytes: ByteArray, block: (ReadOnlyRandomAccessSource) -> Unit) {
        val directory = Files.createTempDirectory("ramen-livemaker-test").toFile()
        try {
            val file = File(directory, "fixture.bin").apply { writeBytes(bytes) }
            FileRandomAccessSource(file).use(block)
        } finally {
            directory.deleteRecursively()
        }
    }
}
