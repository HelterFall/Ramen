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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveMakerGameSessionTest {
    @Test
    fun invokesExternalMesNewCallbackWithParamStrAndSenderContext() {
        val callbackBytes = LiveMakerLsbFixture.build(
            commands = listOf(
                LiveMakerLsbFixture.command(
                    type = 14,
                    body = LiveMakerLsbFixture.expression(
                        LiveMakerLsbFixture.operation(
                            type = 10,
                            name = "____event",
                            operands = listOf(
                                LiveMakerLsbFixture.paramVar("@ParamStr"),
                                LiveMakerLsbFixture.paramInt(0)
                            )
                        ),
                        LiveMakerLsbFixture.operation(
                            type = 1,
                            name = "receivedEvent",
                            operands = listOf(LiveMakerLsbFixture.paramVar("____event"))
                        )
                    )
                ),
                LiveMakerLsbFixture.command(
                    type = 14,
                    body = LiveMakerLsbFixture.expression(
                        LiveMakerLsbFixture.operation(
                            type = 1,
                            name = "receivedSender",
                            operands = listOf(LiveMakerLsbFixture.paramVar("@Sender"))
                        )
                    )
                ),
                LiveMakerLsbFixture.exitWhenTrue()
            )
        )
        val mainBytes = LiveMakerLsbFixture.build(
            commands = listOf(LiveMakerLsbFixture.exitWhenTrue())
        )
        val repository = object : LiveMakerScriptRepository {
            override fun loadPage(pageReference: String): io.github.helterfall.ramen.inspection.LiveMakerLsb {
                assertEquals("callbacks/event.lsc", pageReference)
                return parse(callbackBytes)
            }
        }
        val objects = LiveMakerObjectStore().apply {
            create(
                name = "message-box",
                componentTypeId = LiveMakerSceneComponent.COMPONENT_MES_NEW,
                initialProperties = mapOf(
                    LiveMakerObjectStore.PR_ONNOTIFY to "callbacks/event.lsc"
                )
            )
        }
        val session = LiveMakerGameSession(
            initialScript = parse(mainBytes),
            initialPageReference = "main.lsb",
            pageRepository = repository,
            objectStore = objects
        )

        assertTrue(dispatchLiveMakerMessageEnd(session, "message-box"))
        val finished = session.runUntilEvent() as LiveMakerSessionEvent.EngineEvent

        assertTrue(finished.event is LiveMakerVmEvent.Completed)
        assertEquals("MESEND", session.variablesSnapshot()["receivedEvent"])
        assertEquals("message-box", session.variablesSnapshot()["receivedSender"])
    }

    @Test
    fun messageEndFallsBackToCompletionFlagWhenNotifyCallbackIsMissing() {
        val mainBytes = LiveMakerLsbFixture.build(
            commands = listOf(LiveMakerLsbFixture.exitWhenTrue())
        )
        val repository = object : LiveMakerScriptRepository {
            override fun loadPage(pageReference: String): io.github.helterfall.ramen.inspection.LiveMakerLsb {
                throw LiveMakerPageNotFoundException(pageReference)
            }
        }
        val session = LiveMakerGameSession(
            initialScript = parse(mainBytes),
            initialPageReference = "main.lsb",
            pageRepository = repository
        )

        assertFalse(dispatchLiveMakerMessageEnd(session, "message-box"))
        assertEquals(1, session.variablesSnapshot()["__メッセージ終了"])
    }

    @Test
    fun resolvesCallPageByCommandIndexRatherThanDebugLineNo() {
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
                        labelIndex = 1,
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
                        lineNo = 12
                    ),
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
            assertEquals(1L, entered.targetCommandIndex)
            assertEquals(1, entered.commandIndex)
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
    fun resolvesCompiledPageFromEmbeddedExecutableArchive() {
        val root = Files.createTempDirectory("ramen-livemaker-exe-pages").toFile()
        try {
            val childBytes = LiveMakerLsbFixture.build(
                commands = listOf(LiveMakerLsbFixture.variableNew("exeArchiveLoaded", 17))
            )
            val executableBytes = LiveMakerArchiveFixture.executable(
                listOf(
                    LiveMakerFixtureFile(
                        name = "scenario/embedded.lsb",
                        payload = childBytes
                    )
                ),
                archiveOffset = 48
            )
            val executableFile = File(root, "Game.exe").apply { writeBytes(executableBytes) }
            val repository = VfArchiveLiveMakerScriptRepository(executableFile)
            val loaded = repository.loadPage("scenario/embedded.lsc")

            assertTrue(loaded.isComplete)
            assertEquals(103, loaded.version)
            assertEquals(15, loaded.commands.single().typeId)
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
                    labelIndex = 1,
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
                ),
                LiveMakerLsbFixture.command(
                    type = 3,
                    body = LiveMakerLsbFixture.emptyString(),
                    lineNo = 89
                )
            )
        )

        val repository = object : LiveMakerScriptRepository {
            override fun loadPage(pageReference: String): io.github.helterfall.ramen.inspection.LiveMakerLsb {
                throw LiveMakerPageNotFoundException(pageReference)
            }
        }
        val session = LiveMakerGameSession(parse(callerBytes), "main.lsb", repository)

        val resolution = session.runUntilEvent() as LiveMakerSessionEvent.PageResolutionRequired
        assertEquals("external/chapter.lsc", resolution.pageReference)
        assertEquals(1L, resolution.targetCommandIndex)
        assertTrue(resolution.isCall)
        assertEquals(1, resolution.callArguments.size)

        val entered = session.provideResolvedPage(parse(calleeBytes))
        assertEquals("external/chapter.lsc", entered.pageReference)
        assertEquals(1L, entered.targetCommandIndex)
        assertEquals(1, entered.commandIndex)
        assertEquals("host-value", session.evaluateExpression(entered.callArguments.single()))
    }

    @Test
    fun propagatesPageReadFailuresInsteadOfTreatingThemAsMissingPages() {
        val callerBytes = LiveMakerLsbFixture.build(
            commands = listOf(
                LiveMakerLsbFixture.callPage(
                    page = "broken/chapter.lsc",
                    labelIndex = 0,
                    resultVariable = "result",
                    arguments = emptyList()
                )
            )
        )
        var fallbackCalled = false
        val brokenRepository = object : LiveMakerScriptRepository {
            override fun loadPage(pageReference: String): io.github.helterfall.ramen.inspection.LiveMakerLsb {
                throw IOException("Corrupt LSB page.")
            }
        }
        val fallbackRepository = object : LiveMakerScriptRepository {
            override fun loadPage(pageReference: String): io.github.helterfall.ramen.inspection.LiveMakerLsb {
                fallbackCalled = true
                throw LiveMakerPageNotFoundException(pageReference)
            }
        }
        val session = LiveMakerGameSession(
            initialScript = parse(callerBytes),
            initialPageReference = "main.lsb",
            pageRepository = CompositeLiveMakerScriptRepository(
                listOf(brokenRepository, fallbackRepository)
            )
        )

        val failure = runCatching { session.runUntilEvent() }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals("Corrupt LSB page.", failure?.message)
        assertTrue(!fallbackCalled)
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
