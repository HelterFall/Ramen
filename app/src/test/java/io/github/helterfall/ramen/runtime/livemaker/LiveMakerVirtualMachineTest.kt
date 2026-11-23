package io.github.helterfall.ramen.runtime.livemaker

import io.github.helterfall.ramen.inspection.LiveMakerLsbFixture
import io.github.helterfall.ramen.inspection.LiveMakerLsbParser
import io.github.helterfall.ramen.inspection.ReadOnlyRandomAccessSource
import io.github.helterfall.ramen.inspection.FileRandomAccessSource
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveMakerVirtualMachineTest {
    @Test
    fun executesVariableArithmeticAndSurfacesTextToTheHost() {
        val scriptBytes = LiveMakerLsbFixture.build(
            commands = listOf(
                LiveMakerLsbFixture.variableNew("score", 42),
                LiveMakerLsbFixture.calcIncrement("score", 8),
                LiveMakerLsbFixture.textInsert("TpWord".toByteArray()),
                LiveMakerLsbFixture.exitWhenTrue()
            )
        )

        withSource(scriptBytes) { source ->
            val script = LiveMakerLsbParser().parse(source)
            val vm = LiveMakerVirtualMachine(script)

            val event = vm.runUntilEvent()
            assertTrue(event is LiveMakerVmEvent.TextBlock)
            assertEquals(50L, vm.variablesSnapshot()["score"])

            vm.resume()
            assertTrue(vm.runUntilEvent() is LiveMakerVmEvent.Completed)
        }
    }

    @Test
    fun loadsCalledScriptAndReturnsToCaller() {
        val callerBytes = LiveMakerLsbFixture.build(
            commands = listOf(
                LiveMakerLsbFixture.callPage("chapter_b.lsb", 0, "returnValue"),
                LiveMakerLsbFixture.exitWhenTrue()
            )
        )
        val calleeBytes = LiveMakerLsbFixture.build(
            commands = listOf(LiveMakerLsbFixture.variableNew("localValue", 7))
        )

        withSource(callerBytes) { callerSource ->
            withSource(calleeBytes) { calleeSource ->
                val caller = LiveMakerLsbParser().parse(callerSource)
                val callee = LiveMakerLsbParser().parse(calleeSource)
                val vm = LiveMakerVirtualMachine(caller)

                val transfer = vm.runUntilEvent() as LiveMakerVmEvent.ScriptTransfer
                assertTrue(transfer.isCall)
                assertEquals("chapter_b.lsb", transfer.page)
                assertEquals("returnValue", transfer.returnVariable)

                vm.loadTransferredScript(
                    newScript = callee,
                    isCall = transfer.isCall,
                    returnVariable = transfer.returnVariable
                )
                assertTrue(vm.returnFromCall(123))
                assertEquals(123, vm.variablesSnapshot()["returnValue"])
                assertTrue(vm.runUntilEvent() is LiveMakerVmEvent.Completed)
            }
        }
    }

    @Test
    fun waitStillWaitsWhenStopEventIsFalse() {
        val bytes = LiveMakerLsbFixture.build(
            version = 107,
            commands = listOf(
                LiveMakerLsbFixture.wait(
                    milliseconds = 250,
                    shouldWait = true,
                    stopEventProcessing = false
                )
            )
        )
        withSource(bytes) { source ->
            val script = LiveMakerLsbParser().parse(source)
            val vm = LiveMakerVirtualMachine(script)
            val event = vm.runUntilEvent() as LiveMakerVmEvent.Delay

            assertEquals(250L, event.milliseconds)
            assertEquals(false, event.stopEventProcessing)
        }
    }

    @Test
    fun textInsDoesNotWaitWhenStopEventIsFalseInVersion107() {
        val bytes = LiveMakerLsbFixture.build(
            version = 107,
            commands = listOf(LiveMakerLsbFixture.textInsert("x".toByteArray(), stopEventProcessing = false))
        )
        withSource(bytes) { source ->
            val vm = LiveMakerVirtualMachine(LiveMakerLsbParser().parse(source))
            val event = vm.runUntilEvent() as LiveMakerVmEvent.TextBlock

            assertEquals(false, event.shouldWait)
            assertEquals(false, event.stopEventProcessing)
        }
    }

    @Test
    fun selectsElseifBranchAndSkipsTheElseBodyAfterward() {
        val bytes = LiveMakerLsbFixture.build(
            commands = listOf(
                LiveMakerLsbFixture.ifBranch(false),
                LiveMakerLsbFixture.variableNew("wrong", 1).withIndent(1),
                LiveMakerLsbFixture.elseifBranch(true),
                LiveMakerLsbFixture.variableNew("selected", 2).withIndent(1),
                LiveMakerLsbFixture.elseBranch(),
                LiveMakerLsbFixture.variableNew("alsoWrong", 3).withIndent(1)
            )
        )
        withSource(bytes) { source ->
            val vm = LiveMakerVirtualMachine(LiveMakerLsbParser().parse(source))
            assertTrue(vm.runUntilEvent() is LiveMakerVmEvent.Completed)
            assertEquals(2, vm.variablesSnapshot()["selected"])
            assertTrue(!vm.variablesSnapshot().containsKey("wrong"))
            assertTrue(!vm.variablesSnapshot().containsKey("alsoWrong"))
        }
    }

    @Test
    fun executesWhileInitConditionAndLoopIncrement() {
        val bytes = LiveMakerLsbFixture.build(
            commands = listOf(
                LiveMakerLsbFixture.whileInit(
                    LiveMakerLsbFixture.assignInt("counter", 0)
                ),
                LiveMakerLsbFixture.whileCondition(
                    LiveMakerLsbFixture.lessThanVariable("counter", 3),
                    endIndex = 2
                ),
                LiveMakerLsbFixture.command(27, LiveMakerLsbFixture.emptyString(), indent = 1),
                LiveMakerLsbFixture.whileLoop(
                    LiveMakerLsbFixture.incrementVariable("counter"),
                    startIndex = 0
                )
            )
        )
        withSource(bytes) { source ->
            val vm = LiveMakerVirtualMachine(LiveMakerLsbParser().parse(source))
            assertTrue(vm.runUntilEvent() is LiveMakerVmEvent.Completed)
            assertEquals(3L, vm.variablesSnapshot()["counter"])
        }
    }

    @Test
    fun evaluatesPureStringMathAndArrayExpressionFunctions() {
        val bytes = LiveMakerLsbFixture.build(
            commands = listOf(
                LiveMakerLsbFixture.calc(
                    LiveMakerLsbFixture.functionOperation(
                        functionId = 17, // Trim
                        name = "____trimmed",
                        operands = listOf(LiveMakerLsbFixture.paramString("  LiveMaker  "))
                    ),
                    LiveMakerLsbFixture.operation(
                        type = 1,
                        name = "trimmed",
                        operands = listOf(LiveMakerLsbFixture.paramVar("____trimmed"))
                    ),
                    LiveMakerLsbFixture.operation(
                        type = 2,
                        name = "____joined",
                        operands = listOf(
                            LiveMakerLsbFixture.paramString("Live"),
                            LiveMakerLsbFixture.paramString("Maker")
                        )
                    ),
                    LiveMakerLsbFixture.operation(
                        type = 1,
                        name = "joined",
                        operands = listOf(LiveMakerLsbFixture.paramVar("____joined"))
                    ),
                    LiveMakerLsbFixture.functionOperation(
                        functionId = 39, // Sin
                        name = "____sine",
                        operands = listOf(LiveMakerLsbFixture.paramInt(0))
                    ),
                    LiveMakerLsbFixture.operation(
                        type = 1,
                        name = "sine",
                        operands = listOf(LiveMakerLsbFixture.paramVar("____sine"))
                    ),
                    LiveMakerLsbFixture.operation(
                        type = 10, // DimTo
                        name = "____second",
                        operands = listOf(
                            LiveMakerLsbFixture.paramVar("values"),
                            LiveMakerLsbFixture.paramInt(1)
                        )
                    ),
                    LiveMakerLsbFixture.operation(
                        type = 1,
                        name = "second",
                        operands = listOf(LiveMakerLsbFixture.paramVar("____second"))
                    )
                )
            )
        )

        withSource(bytes) { source ->
            val vm = LiveMakerVirtualMachine(LiveMakerLsbParser().parse(source))
            vm.setVariable("values", listOf("zero", "one", "two"))

            assertTrue(vm.runUntilEvent() is LiveMakerVmEvent.Completed)
            assertEquals("LiveMaker", vm.variablesSnapshot()["trimmed"])
            assertEquals("LiveMaker", vm.variablesSnapshot()["joined"])
            assertEquals("one", vm.variablesSnapshot()["second"])
            assertEquals(0.0, vm.variablesSnapshot()["sine"])
        }
    }

    @Test
    fun pausesOnUnsupportedExpressionUntilHostChoosesHowToResume() {
        val bytes = LiveMakerLsbFixture.build(
            commands = listOf(
                LiveMakerLsbFixture.calc(
                    LiveMakerLsbFixture.functionOperation(
                        functionId = 2, // GetProp requires a host adapter.
                        name = "____hostResult",
                        operands = emptyList()
                    )
                )
            )
        )
        withSource(bytes) { source ->
            val vm = LiveMakerVirtualMachine(LiveMakerLsbParser().parse(source))

            val unsupported = vm.runUntilEvent() as LiveMakerVmEvent.UnsupportedExpression
            assertEquals(0, unsupported.commandIndex)
            assertTrue(vm.runUntilEvent() is LiveMakerVmEvent.AwaitingHost)

            // The host explicitly skips this operation; the VM must not retry it implicitly.
            vm.resume(nextCommandIndex = 1)
            assertTrue(vm.runUntilEvent() is LiveMakerVmEvent.Completed)
        }
    }

    private fun ByteArray.withIndent(indent: Long): ByteArray {
        val copy = copyOf()
        val offset = 1
        repeat(4) { i -> copy[offset + i] = ((indent ushr (8 * i)) and 0xFFL).toByte() }
        return copy
    }

    private fun withSource(bytes: ByteArray, block: (ReadOnlyRandomAccessSource) -> Unit) {
        val directory = Files.createTempDirectory("ramen-livemaker-vm-test").toFile()
        try {
            File(directory, "script.lsb").apply { writeBytes(bytes) }
                .let(::FileRandomAccessSource)
                .use(block)
        } finally {
            directory.deleteRecursively()
        }
    }
}
