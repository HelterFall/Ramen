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
    fun evaluatesGetPropAndSetPropAgainstMessageBoxPropertyDefaults() {
        val messageBox = "メッセージボックス"
        val bytes = LiveMakerLsbFixture.build(
            commands = listOf(
                LiveMakerLsbFixture.calc(
                    LiveMakerLsbFixture.functionOperation(
                        functionId = 3,
                        name = "____setLeft",
                        operands = listOf(
                            LiveMakerLsbFixture.paramString(messageBox),
                            LiveMakerLsbFixture.paramInt(LiveMakerObjectStore.PR_LEFT),
                            LiveMakerLsbFixture.paramInt(640)
                        )
                    ),
                    LiveMakerLsbFixture.functionOperation(
                        functionId = 2,
                        name = "____left",
                        operands = listOf(
                            LiveMakerLsbFixture.paramString(messageBox),
                            LiveMakerLsbFixture.paramInt(LiveMakerObjectStore.PR_LEFT)
                        )
                    ),
                    LiveMakerLsbFixture.operation(
                        type = 1,
                        name = "left",
                        operands = listOf(LiveMakerLsbFixture.paramVar("____left"))
                    ),
                    LiveMakerLsbFixture.functionOperation(
                        functionId = 2,
                        name = "____rowCount",
                        operands = listOf(
                            LiveMakerLsbFixture.paramString(messageBox),
                            LiveMakerLsbFixture.paramInt(LiveMakerObjectStore.PR_ROWCOUNT)
                        )
                    ),
                    LiveMakerLsbFixture.operation(
                        type = 1,
                        name = "rowCount",
                        operands = listOf(LiveMakerLsbFixture.paramVar("____rowCount"))
                    ),
                    LiveMakerLsbFixture.functionOperation(
                        functionId = 2,
                        name = "____ignoreMouse",
                        operands = listOf(
                            LiveMakerLsbFixture.paramString(messageBox),
                            LiveMakerLsbFixture.paramInt(LiveMakerObjectStore.PR_IGNOREMOUSE)
                        )
                    ),
                    LiveMakerLsbFixture.operation(
                        type = 1,
                        name = "ignoreMouse",
                        operands = listOf(LiveMakerLsbFixture.paramVar("____ignoreMouse"))
                    ),
                    LiveMakerLsbFixture.functionOperation(
                        functionId = 2,
                        name = "____reverse",
                        operands = listOf(
                            LiveMakerLsbFixture.paramString(messageBox),
                            LiveMakerLsbFixture.paramInt(LiveMakerObjectStore.PR_REVERSE)
                        )
                    ),
                    LiveMakerLsbFixture.operation(
                        type = 1,
                        name = "reverse",
                        operands = listOf(LiveMakerLsbFixture.paramVar("____reverse"))
                    ),
                    LiveMakerLsbFixture.functionOperation(
                        functionId = 2,
                        name = "____visible",
                        operands = listOf(
                            LiveMakerLsbFixture.paramString(messageBox),
                            LiveMakerLsbFixture.paramInt(LiveMakerObjectStore.PR_VISIBLE)
                        )
                    ),
                    LiveMakerLsbFixture.operation(
                        type = 1,
                        name = "visible",
                        operands = listOf(LiveMakerLsbFixture.paramVar("____visible"))
                    )
                )
            )
        )

        withSource(bytes) { source ->
            val store = LiveMakerObjectStore()
            store.create(messageBox, componentTypeId = 10)
            val vm = LiveMakerVirtualMachine(
                LiveMakerLsbParser().parse(source),
                objectStore = store
            )

            assertTrue(vm.runUntilEvent() is LiveMakerVmEvent.Completed)
            assertEquals(640, vm.variablesSnapshot()["left"])
            assertEquals(0, vm.variablesSnapshot()["rowCount"])
            assertEquals(false, vm.variablesSnapshot()["ignoreMouse"])
            assertEquals(false, vm.variablesSnapshot()["reverse"])
            assertEquals(true, vm.variablesSnapshot()["visible"])
            assertEquals(640, store.getProperty(messageBox, LiveMakerObjectStore.PR_LEFT))
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
    fun matchesLiveNovelBuiltInStringAndArrayFunctionSignatures() {
        val bytes = LiveMakerLsbFixture.build(
            commands = listOf(
                LiveMakerLsbFixture.calc(
                    LiveMakerLsbFixture.functionOperation(
                        functionId = 82,
                        name = "____repeated",
                        operands = listOf(
                            LiveMakerLsbFixture.paramString("あ"),
                            LiveMakerLsbFixture.paramInt(5)
                        )
                    ),
                    LiveMakerLsbFixture.operation(
                        type = 1,
                        name = "repeated",
                        operands = listOf(LiveMakerLsbFixture.paramVar("____repeated"))
                    ),
                    LiveMakerLsbFixture.functionOperation(
                        functionId = 83,
                        name = "____firstReplacement",
                        operands = listOf(
                            LiveMakerLsbFixture.paramString("ABCabc"),
                            LiveMakerLsbFixture.paramString("A"),
                            LiveMakerLsbFixture.paramString("X")
                        )
                    ),
                    LiveMakerLsbFixture.operation(
                        type = 1,
                        name = "firstReplacement",
                        operands = listOf(LiveMakerLsbFixture.paramVar("____firstReplacement"))
                    ),
                    LiveMakerLsbFixture.functionOperation(
                        functionId = 83,
                        name = "____allReplacement",
                        operands = listOf(
                            LiveMakerLsbFixture.paramString("ABCabc"),
                            LiveMakerLsbFixture.paramString("A"),
                            LiveMakerLsbFixture.paramString("X"),
                            LiveMakerLsbFixture.paramFlag(true),
                            LiveMakerLsbFixture.paramFlag(true)
                        )
                    ),
                    LiveMakerLsbFixture.operation(
                        type = 1,
                        name = "allReplacement",
                        operands = listOf(LiveMakerLsbFixture.paramVar("____allReplacement"))
                    ),
                    LiveMakerLsbFixture.functionOperation(
                        functionId = 5,
                        name = "____byteLength",
                        operands = listOf(LiveMakerLsbFixture.paramString("あ"))
                    ),
                    LiveMakerLsbFixture.operation(
                        type = 1,
                        name = "byteLength",
                        operands = listOf(LiveMakerLsbFixture.paramVar("____byteLength"))
                    ),
                    LiveMakerLsbFixture.functionOperation(
                        functionId = 0,
                        name = "____paddedNumber",
                        operands = listOf(
                            LiveMakerLsbFixture.paramInt(87),
                            LiveMakerLsbFixture.paramInt(3)
                        )
                    ),
                    LiveMakerLsbFixture.operation(
                        type = 1,
                        name = "paddedNumber",
                        operands = listOf(LiveMakerLsbFixture.paramVar("____paddedNumber"))
                    ),
                    LiveMakerLsbFixture.functionOperation(
                        functionId = 4,
                        name = "____arraySize",
                        operands = listOf(LiveMakerLsbFixture.paramVar("values"))
                    ),
                    LiveMakerLsbFixture.operation(
                        type = 1,
                        name = "arraySize",
                        operands = listOf(LiveMakerLsbFixture.paramVar("____arraySize"))
                    )
                )
            )
        )

        withSource(bytes) { source ->
            val vm = LiveMakerVirtualMachine(LiveMakerLsbParser().parse(source))
            vm.setVariable("values", listOf("zero", "one", "two"))

            assertTrue(vm.runUntilEvent() is LiveMakerVmEvent.Completed)
            assertEquals("あああああ", vm.variablesSnapshot()["repeated"])
            assertEquals("XBCabc", vm.variablesSnapshot()["firstReplacement"])
            assertEquals("XBCXbc", vm.variablesSnapshot()["allReplacement"])
            assertEquals(2, vm.variablesSnapshot()["byteLength"])
            assertEquals("087", vm.variablesSnapshot()["paddedNumber"])
            assertEquals(3, vm.variablesSnapshot()["arraySize"])
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


    @Test
    fun formatHistRegistersFormatAndClrHistClearsEntriesWithoutStoppingTheScenario() {
        val formatName = LiveMakerLsbFixture.expression(
            LiveMakerLsbFixture.operation(
                type = 0,
                name = "____format",
                operands = listOf(LiveMakerLsbFixture.paramString("novel-history"))
            )
        )
        val target = LiveMakerLsbFixture.expression(
            LiveMakerLsbFixture.operation(
                type = 0,
                name = "____target",
                operands = listOf(LiveMakerLsbFixture.paramString("message-window"))
            )
        )
        val bytes = LiveMakerLsbFixture.build(
            version = 111,
            commands = listOf(
                LiveMakerLsbFixture.command(58, formatName + target),
                LiveMakerLsbFixture.command(22, ByteArray(0)),
                LiveMakerLsbFixture.exitWhenTrue()
            )
        )

        withSource(bytes) { source ->
            val vm = LiveMakerVirtualMachine(LiveMakerLsbParser().parse(source))
            val historyHost = LiveMakerTextHistoryHostAdapter()

            val formatEvent = vm.runUntilEvent() as LiveMakerVmEvent.HostCommandRequired
            assertEquals(58, formatEvent.command.typeId)
            assertTrue(historyHost.handle(formatEvent.command, vm::evaluateExpression))
            assertEquals("novel-history", historyHost.snapshot().activeFormat?.name)
            assertEquals("message-window", historyHost.snapshot().activeFormat?.target)

            historyHost.addText("This entry is cleared", includeInHistory = true)
            historyHost.addText("This entry is not recorded", includeInHistory = false)
            assertEquals(1, historyHost.snapshot().entries.size)

            vm.resume()
            val clearEvent = vm.runUntilEvent() as LiveMakerVmEvent.HostCommandRequired
            assertEquals(22, clearEvent.command.typeId)
            assertTrue(historyHost.handle(clearEvent.command, vm::evaluateExpression))
            assertTrue(historyHost.snapshot().entries.isEmpty())
            // ClrHist clears entries but leaves the registered FormatHist definition active.
            assertEquals("novel-history", historyHost.snapshot().activeFormat?.name)

            historyHost.addText("The next line", includeInHistory = true)
            vm.resume()
            assertTrue(vm.runUntilEvent() is LiveMakerVmEvent.Completed)
            assertEquals(listOf("The next line"), historyHost.snapshot().entries.map { it.text })
        }
    }


    @Test
    fun coercesEmptyNullAndNumericStringOperandsInCommandNinety() {
        val outputs = listOf(
            "sum" to 1,
            "difference" to 1,
            "product" to 1,
            "quotient" to 1,
            "remainder" to 1,
            "equalEmptyAndNull" to 1,
            "greaterNumericStrings" to 1,
            "lessNullAndOne" to 1,
            "notEqualFallbackZero" to 1,
            "caption" to 4
        )
        val setup = outputs.map { (name, type) ->
            LiveMakerLsbFixture.variableNew(name, 0, type)
        }
        val fillers = List(90 - setup.size) {
            LiveMakerLsbFixture.command(27, LiveMakerLsbFixture.emptyString())
        }
        val commandNinety = LiveMakerLsbFixture.calc(
            // GetProp on a missing object's string property returns an empty string.
            LiveMakerLsbFixture.functionOperation(
                functionId = 2,
                name = "____emptyProperty",
                operands = listOf(
                    LiveMakerLsbFixture.paramString("missing-object"),
                    LiveMakerLsbFixture.paramInt(LiveMakerObjectStore.PR_SOURCE)
                )
            ),
            LiveMakerLsbFixture.operation(
                2, "____sum",
                listOf(
                    LiveMakerLsbFixture.paramVar("____emptyProperty"),
                    LiveMakerLsbFixture.paramString("1")
                )
            ),
            LiveMakerLsbFixture.operation(1, "sum", listOf(LiveMakerLsbFixture.paramVar("____sum"))),

            LiveMakerLsbFixture.operation(
                3, "____difference",
                listOf(
                    LiveMakerLsbFixture.paramVar("numericString"),
                    LiveMakerLsbFixture.paramVar("notInitialized")
                )
            ),
            LiveMakerLsbFixture.operation(
                1, "difference",
                listOf(LiveMakerLsbFixture.paramVar("____difference"))
            ),

            LiveMakerLsbFixture.operation(
                4, "____product",
                listOf(
                    LiveMakerLsbFixture.paramString("2"),
                    LiveMakerLsbFixture.paramString("3")
                )
            ),
            LiveMakerLsbFixture.operation(
                1, "product",
                listOf(LiveMakerLsbFixture.paramVar("____product"))
            ),

            LiveMakerLsbFixture.operation(
                5, "____quotient",
                listOf(
                    LiveMakerLsbFixture.paramString("6"),
                    LiveMakerLsbFixture.paramString("2")
                )
            ),
            LiveMakerLsbFixture.operation(
                1, "quotient",
                listOf(LiveMakerLsbFixture.paramVar("____quotient"))
            ),

            LiveMakerLsbFixture.operation(
                6, "____remainder",
                listOf(
                    LiveMakerLsbFixture.paramString("5"),
                    LiveMakerLsbFixture.paramString("2")
                )
            ),
            LiveMakerLsbFixture.operation(
                1, "remainder",
                listOf(LiveMakerLsbFixture.paramVar("____remainder"))
            ),

            LiveMakerLsbFixture.operation(
                12, "____equal",
                listOf(
                    LiveMakerLsbFixture.paramString(""),
                    LiveMakerLsbFixture.paramVar("notInitialized")
                )
            ),
            LiveMakerLsbFixture.operation(
                1, "equalEmptyAndNull",
                listOf(LiveMakerLsbFixture.paramVar("____equal"))
            ),

            LiveMakerLsbFixture.operation(
                13, "____greater",
                listOf(
                    LiveMakerLsbFixture.paramVar("numericString"),
                    LiveMakerLsbFixture.paramString("2")
                )
            ),
            LiveMakerLsbFixture.operation(
                1, "greaterNumericStrings",
                listOf(LiveMakerLsbFixture.paramVar("____greater"))
            ),

            LiveMakerLsbFixture.operation(
                14, "____less",
                listOf(
                    LiveMakerLsbFixture.paramVar("notInitialized"),
                    LiveMakerLsbFixture.paramString("1")
                )
            ),
            LiveMakerLsbFixture.operation(
                1, "lessNullAndOne",
                listOf(LiveMakerLsbFixture.paramVar("____less"))
            ),

            LiveMakerLsbFixture.operation(
                20, "____notEqual",
                listOf(
                    LiveMakerLsbFixture.paramString("not-a-number"),
                    LiveMakerLsbFixture.paramVar("notInitialized")
                )
            ),
            LiveMakerLsbFixture.operation(
                1, "notEqualFallbackZero",
                listOf(LiveMakerLsbFixture.paramVar("____notEqual"))
            ),

            // Plus remains string concatenation when its destination is ParamType.Str.
            LiveMakerLsbFixture.operation(
                2, "____caption",
                listOf(
                    LiveMakerLsbFixture.paramString("Chapter "),
                    LiveMakerLsbFixture.paramString("1")
                )
            ),
            LiveMakerLsbFixture.operation(
                1, "caption",
                listOf(LiveMakerLsbFixture.paramVar("____caption"))
            )
        )

        val scriptBytes = LiveMakerLsbFixture.build(
            commands = setup + fillers + commandNinety
        )
        withSource(scriptBytes) { source ->
            val script = LiveMakerLsbParser().parse(source)
            assertTrue(script.isComplete)
            assertEquals(91, script.commands.size)
            assertEquals(90, script.commands.last().index)
            assertEquals(14, script.commands.last().typeId)
            assertEquals(1, script.commands.last().expressions.size)

            val vm = LiveMakerVirtualMachine(script)
            vm.setVariable("numericString", "2.5")

            val event = vm.runUntilEvent()
            assertTrue("Command 90 should run without a Numeric operand expected error: $event",
                event is LiveMakerVmEvent.Completed
            )
            val values = vm.variablesSnapshot()
            assertEquals(1L, values["sum"])
            assertEquals(2.5, (values["difference"] as Number).toDouble(), 0.000001)
            assertEquals(6L, values["product"])
            assertEquals(3L, values["quotient"])
            assertEquals(1L, values["remainder"])
            assertEquals(true, values["equalEmptyAndNull"])
            assertEquals(true, values["greaterNumericStrings"])
            assertEquals(true, values["lessNullAndOne"])
            assertEquals(false, values["notEqualFallbackZero"])
            assertEquals("Chapter 1", values["caption"])
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
