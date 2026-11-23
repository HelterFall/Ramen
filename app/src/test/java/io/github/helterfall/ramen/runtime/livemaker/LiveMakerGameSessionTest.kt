package io.github.helterfall.ramen.runtime.livemaker

import io.github.helterfall.ramen.inspection.FileRandomAccessSource
import io.github.helterfall.ramen.inspection.LiveMakerArchiveFixture
import io.github.helterfall.ramen.inspection.LiveMakerFixtureFile
import io.github.helterfall.ramen.inspection.LiveMakerLsbFixture
import io.github.helterfall.ramen.inspection.LiveMakerLsbParser
import io.github.helterfall.ramen.inspection.ReadOnlyRandomAccessSource
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveMakerGameSessionTest {
    @Test
    fun resolvesCallPageFromDirectoryAndMapsLineNoToCommandIndex() {
        val root = Files.createTempDirectory("ramen-livemaker-session").toFile()
        try {
            val callArgument = LiveMakerLsbFixture.expression(
                LiveMakerLsbFixture.operation(
                    type = 0,
                    name = "____arg",
                    operands = listOf(LiveMakerLsbFixture.paramInt(23))
                )
            )
            val callerBytes = LiveMakerLsbFixture.build(
                commands = listOf(
                    LiveMakerLsbFixture.callPage(
                        page = "chapter_b.lsc",
                        labelIndex = 0,
                        resultVariable = "callResult",
                        arguments = listOf(callArgument)
                    ),
                    LiveMakerLsbFixture.exitWhenTrue()
                )
            )
            val childBytes = LiveMakerLsbFixture.build(
                commands = listOf(
                    LiveMakerLsbFixture.command(
                        type = 3,
                        body = LiveMakerLsbFixture.emptyString(),
                        lineNo = 77
                    ),
                    LiveMakerLsbFixture.variableNew("childValue", 9)
                )
            )
            File(root, "main.lsb").writeBytes(callerBytes)
            File(root, "chapter_b.lsb".replace(".lsc", ".lsb")).writeBytes(childBytes)

            val caller = parse(callerBytes)
            val repository = DirectoryLiveMakerScriptRepository(root)
            val session = LiveMakerGameSession(caller, "main.lsb", repository)

            val entered = session.runUntilEvent() as LiveMakerSessionEvent.PageEntered
            assertEquals("chapter_b.lsc", entered.pageReference)
            assertEquals(77L, entered.targetLineNo)
            assertEquals(0, entered.commandIndex)
            assertTrue(entered.isCall)
            assertEquals(1, entered.callArguments.size)
            assertEquals(23, session.evaluateExpression(entered.callArguments.single()))
            assertEquals("chapter_b.lsc", session.currentPageReference())

            val finished = session.runUntilEvent() as LiveMakerSessionEvent.EngineEvent
            assertTrue(finished.event is LiveMakerVmEvent.Completed)
            assertEquals("main.lsb", session.currentPageReference())
            assertEquals(9, session.variablesSnapshot()["childValue"])
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun resolvesCompiledPageDirectlyFromVfArchiveWithoutExtractingWholeArchive() {
        val root = Files.createTempDirectory("ramen-livemaker-vf-pages").toFile()
        try {
            val childBytes = LiveMakerLsbFixture.build(
                commands = listOf(
                    LiveMakerLsbFixture.command(
                        type = 3,
                        body = LiveMakerLsbFixture.emptyString(),
                        lineNo = 123
                    ),
                    LiveMakerLsbFixture.variableNew("archiveLoaded", 5)
                )
            )
            val archiveBytes = LiveMakerArchiveFixture.standalone(
                listOf(
                    LiveMakerFixtureFile(
                        name = "scenario/chapter.lsb",
                        payload = childBytes
                    ),
                    LiveMakerFixtureFile(
                        name = "media/unused.bin",
                        payload = ByteArray(8192) { 0x5A.toByte() }
                    )
                )
            )
            val archiveFile = File(root, "Game.vf").apply { writeBytes(archiveBytes) }
            val repository = VfArchiveLiveMakerScriptRepository(archiveFile)
            val loaded = repository.loadPage("scenario/chapter.lsc")

            assertTrue(loaded.isComplete)
            assertEquals(103, loaded.version)
            assertEquals(123L, loaded.commands.first().lineNo)
            assertEquals(15, loaded.commands.last().typeId)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun exposesMissingPageResolutionToHostAndAcceptsProvidedPage() {
        val callerBytes = LiveMakerLsbFixture.build(
            commands = listOf(
                LiveMakerLsbFixture.callPage(
                    page = "external/chapter.lsc",
                    labelIndex = 0,
                    resultVariable = "externalResult",
                    arguments = listOf(
                        LiveMakerLsbFixture.expression(
                            LiveMakerLsbFixture.operation(
                                type = 0,
                                name = "____arg",
                                operands = listOf(LiveMakerLsbFixture.paramString("host-value"))
                            )
                        )
                    )
                )
            )
        )
        val calleeBytes = LiveMakerLsbFixture.build(
            commands = listOf(
                LiveMakerLsbFixture.command(
                    type = 3,
                    body = LiveMakerLsbFixture.emptyString(),
                    lineNo = 44
                )
            )
        )

        val repository = object : LiveMakerScriptRepository {
            override fun loadPage(pageReference: String): io.github.helterfall.ramen.inspection.LiveMakerLsb {
                throw IOException("Page not in configured repositories.")
            }
        }
        val session = LiveMakerGameSession(parse(callerBytes), "main.lsb", repository)

        val resolution = session.runUntilEvent() as LiveMakerSessionEvent.PageResolutionRequired
        assertEquals("external/chapter.lsc", resolution.pageReference)
        assertEquals(44L, resolution.targetLineNo)
        assertTrue(resolution.isCall)
        assertEquals(1, resolution.callArguments.size)

        val entered = session.provideResolvedPage(parse(calleeBytes))
        assertEquals("external/chapter.lsc", entered.pageReference)
        assertEquals(0, entered.commandIndex)
        assertEquals("host-value", session.evaluateExpression(entered.callArguments.single()))
    }

    @Test
    fun rejectsPageTraversalBeforeReadingAnyFile() {
        val root = Files.createTempDirectory("ramen-livemaker-page-path").toFile()
        try {
            val repository = DirectoryLiveMakerScriptRepository(root)
            val failure = runCatching { repository.loadPage("../escape.lsc") }.exceptionOrNull()
            assertTrue(failure is IOException)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun parse(bytes: ByteArray) =
        withSource(bytes) { source -> LiveMakerLsbParser().parse(source) }

    private fun <T> withSource(
        bytes: ByteArray,
        block: (ReadOnlyRandomAccessSource) -> T
    ): T {
        val directory = Files.createTempDirectory("ramen-livemaker-session-parse").toFile()
        try {
            val file = File(directory, "fixture.lsb").apply { writeBytes(bytes) }
            return FileRandomAccessSource(file).use(block)
        } finally {
            directory.deleteRecursively()
        }
    }
}
