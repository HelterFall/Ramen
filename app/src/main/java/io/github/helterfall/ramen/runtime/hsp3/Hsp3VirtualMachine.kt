package io.github.helterfall.ramen.runtime.hsp3

import java.io.IOException
import java.util.ArrayDeque

sealed class Hsp3Instruction {
    data class Screen(val id: Int, val width: Int, val height: Int) : Hsp3Instruction()
    data class Buffer(val id: Int, val width: Int, val height: Int) : Hsp3Instruction()
    data class SelectSurface(val id: Int) : Hsp3Instruction()
    data class Picload(val name: String) : Hsp3Instruction()
    data class Gcopy(
        val sourceId: Int,
        val sourceX: Int,
        val sourceY: Int,
        val width: Int,
        val height: Int,
        val destX: Int,
        val destY: Int
    ) : Hsp3Instruction()
    data class Boxf(val x1: Int, val y1: Int, val x2: Int, val y2: Int) : Hsp3Instruction()
    data class Mes(val value: String) : Hsp3Instruction()
    data class Pos(val x: Int, val y: Int) : Hsp3Instruction()
    data class Color(val red: Int, val green: Int, val blue: Int) : Hsp3Instruction()
    data class Font(val name: String, val size: Int, val style: Int) : Hsp3Instruction()
    data class Gmode(val mode: Int, val alpha: Int = 255) : Hsp3Instruction()
    data class Redraw(val enabled: Boolean) : Hsp3Instruction()
    data class Stick(val destinationVariable: Int, val nonTriggerMask: Int = 0) : Hsp3Instruction()
    data class SetInt(val variable: Int, val value: Int) : Hsp3Instruction()
    data class AddInt(val variable: Int, val value: Int) : Hsp3Instruction()
    data class Assign(
        val variable: Int,
        val operator: Int,
        val expression: Hsp3Expression
    ) : Hsp3Instruction()
    data class NativeCommand(
        val commandType: Int,
        val commandId: Int,
        val arguments: List<Hsp3Expression?>
    ) : Hsp3Instruction()
    data class GetKey(val destinationVariable: Int, val keyCode: Hsp3Expression) : Hsp3Instruction()
    data class Jump(val targetInstruction: Int) : Hsp3Instruction()
    data class JumpIfZero(val variable: Int, val targetInstruction: Int) : Hsp3Instruction()
    data class JumpIfFalse(val condition: Hsp3Expression, val targetInstruction: Int) : Hsp3Instruction()
    data class Gosub(val targetInstruction: Int) : Hsp3Instruction()
    object Return : Hsp3Instruction()
    data class Repeat(
        val count: Int,
        val exitInstruction: Int = -1,
        val initialValue: Int = 0
    ) : Hsp3Instruction()
    object Loop : Hsp3Instruction()
    object Break : Hsp3Instruction()
    object Continue : Hsp3Instruction()
    object Wait : Hsp3Instruction()
    data class WaitFor(val delayMillis: Long) : Hsp3Instruction()
    object End : Hsp3Instruction()
    data class Unsupported(val commandType: Int, val commandId: Int) : Hsp3Instruction()
}

/** Input contract uses OpenHSP's stick bit assignments and VK-style getkey IDs. */
fun interface Hsp3InputProvider {
    /** Current held-button mask: arrows 1/2/4/8, space 16, enter 32, esc 128, Z/X 2048/4096. */
    fun stickMask(): Int

    fun stickMask(checkActiveWindow: Boolean): Int = stickMask()

    fun isKeyDown(keyCode: Int): Boolean {
        val bit = when (keyCode) {
            37 -> 1
            38 -> 2
            39 -> 4
            40 -> 8
            32 -> 16
            13 -> 32
            17 -> 64
            27 -> 128
            9 -> 1024
            90 -> 2048
            88 -> 4096
            67 -> 8192
            65 -> 16384
            87 -> 32768
            68 -> 65536
            83 -> 131072
            else -> 0
        }
        return bit != 0 && stickMask() and bit != 0
    }
}

enum class Hsp3VmStatus {
    READY,
    YIELDED,
    ENDED,
    FAILED
}

data class Hsp3VmSnapshot(
    val programCounter: Int,
    val executedInstructions: Long,
    val status: Hsp3VmStatus,
    val variables: Map<Int, Int>,
    val errorMessage: String? = null,
    val values: Map<Int, Hsp3RuntimeValue> = emptyMap(),
    val yieldDelayMillis: Long = 0L
)

/**
 * Bounded HSP VM for decoded OpenHSP CS expressions and the Android 2D/input
 * command profile. Variable values retain HSP int/double/string types. Branches
 * and loop/call stacks are bounded, and every runSlice has an instruction cap.
 */
class Hsp3VirtualMachine(
    private val graphics: Hsp3Graphics,
    private val input: Hsp3InputProvider = Hsp3InputProvider { 0 },
    private val maxCallDepth: Int = DEFAULT_MAX_CALL_DEPTH,
    private val maxLoopDepth: Int = DEFAULT_MAX_LOOP_DEPTH
) {
    private data class RepeatFrame(
        val bodyStart: Int,
        var remaining: Int,
        var current: Int
    )

    private val variables = linkedMapOf<Int, Hsp3RuntimeValue>()
    private val callStack = ArrayDeque<Int>()
    private val repeatStack = ArrayDeque<RepeatFrame>()
    private var programCounter = 0
    private var executedInstructions = 0L
    private var previousStickMask = 0
    private var status = Hsp3VmStatus.READY
    private var errorMessage: String? = null
    private var yieldDelayMillis = 0L
    private var hspStat = 0
    private var hspError = 0
    private var stringSize = 0
    private var referenceString = ""

    init {
        require(maxCallDepth > 0)
        require(maxLoopDepth > 0)
    }

    fun runSlice(
        instructions: List<Hsp3Instruction>,
        instructionBudget: Int = DEFAULT_INSTRUCTION_BUDGET
    ): Hsp3VmSnapshot {
        require(instructionBudget > 0)
        if (status == Hsp3VmStatus.ENDED || status == Hsp3VmStatus.FAILED) return snapshot()
        status = Hsp3VmStatus.READY
        yieldDelayMillis = 0L
        var remainingBudget = instructionBudget

        try {
            while (remainingBudget > 0) {
                if (programCounter !in instructions.indices) {
                    status = Hsp3VmStatus.ENDED
                    break
                }
                val instruction = instructions[programCounter]
                remainingBudget -= 1
                executedInstructions += 1

                when (instruction) {
                    is Hsp3Instruction.Screen -> {
                        graphics.screen(instruction.id, instruction.width, instruction.height)
                        programCounter += 1
                    }
                    is Hsp3Instruction.Buffer -> {
                        graphics.buffer(instruction.id, instruction.width, instruction.height)
                        programCounter += 1
                    }
                    is Hsp3Instruction.SelectSurface -> {
                        graphics.gsel(instruction.id)
                        programCounter += 1
                    }
                    is Hsp3Instruction.Picload -> {
                        graphics.picload(instruction.name)
                        programCounter += 1
                    }
                    is Hsp3Instruction.Gcopy -> {
                        graphics.gcopy(
                            instruction.sourceId, instruction.sourceX, instruction.sourceY,
                            instruction.width, instruction.height, instruction.destX, instruction.destY
                        )
                        programCounter += 1
                    }
                    is Hsp3Instruction.Boxf -> {
                        graphics.boxf(instruction.x1, instruction.y1, instruction.x2, instruction.y2)
                        programCounter += 1
                    }
                    is Hsp3Instruction.Mes -> {
                        graphics.mes(instruction.value)
                        programCounter += 1
                    }
                    is Hsp3Instruction.Pos -> {
                        graphics.pos(instruction.x, instruction.y)
                        programCounter += 1
                    }
                    is Hsp3Instruction.Color -> {
                        graphics.color(instruction.red, instruction.green, instruction.blue)
                        programCounter += 1
                    }
                    is Hsp3Instruction.Font -> {
                        graphics.font(instruction.name, instruction.size, instruction.style)
                        programCounter += 1
                    }
                    is Hsp3Instruction.Gmode -> {
                        graphics.gmode(instruction.mode, instruction.alpha)
                        programCounter += 1
                    }
                    is Hsp3Instruction.Redraw -> {
                        graphics.redraw(instruction.enabled)
                        programCounter += 1
                    }
                    is Hsp3Instruction.Stick -> {
                        readStick(instruction.destinationVariable, instruction.nonTriggerMask, true)
                        programCounter += 1
                    }
                    is Hsp3Instruction.SetInt -> {
                        variables[instruction.variable] = Hsp3RuntimeValue.IntegerValue(instruction.value)
                        programCounter += 1
                    }
                    is Hsp3Instruction.AddInt -> {
                        val old = valueOf(instruction.variable)
                        val rhs = Hsp3RuntimeValue.IntegerValue(instruction.value)
                        variables[instruction.variable] = calculate(
                            Hsp3BytecodeDecoder.CALCCODE_ADD, old, rhs
                        )
                        programCounter += 1
                    }
                    is Hsp3Instruction.Assign -> {
                        val rhs = evaluate(instruction.expression)
                        val value = if (instruction.operator == Hsp3BytecodeDecoder.CALCCODE_EQ) {
                            rhs
                        } else {
                            calculate(instruction.operator, valueOf(instruction.variable), rhs)
                        }
                        variables[instruction.variable] = value
                        programCounter += 1
                    }
                    is Hsp3Instruction.NativeCommand -> {
                        executeNative(instruction)
                        programCounter += 1
                    }
                    is Hsp3Instruction.GetKey -> {
                        val keyCode = evaluate(instruction.keyCode).asInt()
                        variables[instruction.destinationVariable] = Hsp3RuntimeValue.IntegerValue(
                            if (input.isKeyDown(keyCode)) 1 else 0
                        )
                        programCounter += 1
                    }
                    is Hsp3Instruction.Jump -> {
                        programCounter = checkedTarget(instruction.targetInstruction, instructions.size)
                    }
                    is Hsp3Instruction.JumpIfZero -> {
                        programCounter = if ((valueOf(instruction.variable).asInt()) == 0) {
                            checkedTarget(instruction.targetInstruction, instructions.size)
                        } else programCounter + 1
                    }
                    is Hsp3Instruction.JumpIfFalse -> {
                        val value = evaluate(instruction.condition)
                        programCounter = if (value.asInt() == 0) {
                            checkedTarget(instruction.targetInstruction, instructions.size)
                        } else programCounter + 1
                    }
                    is Hsp3Instruction.Gosub -> {
                        if (callStack.size >= maxCallDepth) {
                            throw IOException("HSP gosub stack exceeded its configured depth.")
                        }
                        callStack.addLast(programCounter + 1)
                        programCounter = checkedTarget(instruction.targetInstruction, instructions.size)
                    }
                    Hsp3Instruction.Return -> {
                        if (callStack.isEmpty()) throw IOException("HSP return has no matching gosub.")
                        programCounter = callStack.removeLast()
                    }
                    is Hsp3Instruction.Repeat -> {
                        if (instruction.count == 0) {
                            programCounter = if (instruction.exitInstruction >= 0) {
                                checkedTarget(instruction.exitInstruction, instructions.size)
                            } else {
                                findMatchingLoop(instructions, programCounter + 1) + 1
                            }
                        } else {
                            if (repeatStack.size >= maxLoopDepth) {
                                throw IOException("HSP repeat stack exceeded its configured depth.")
                            }
                            repeatStack.addLast(
                                RepeatFrame(
                                    bodyStart = programCounter + 1,
                                    remaining = instruction.count,
                                    current = instruction.initialValue
                                )
                            )
                            programCounter += 1
                        }
                    }
                    Hsp3Instruction.Loop -> {
                        val frame = repeatStack.lastOrNull()
                            ?: throw IOException("HSP loop has no matching repeat.")
                        frame.current += 1
                        if (frame.remaining < 0 || --frame.remaining > 0) {
                            programCounter = frame.bodyStart
                        } else {
                            repeatStack.removeLast()
                            programCounter += 1
                        }
                    }
                    Hsp3Instruction.Break -> {
                        if (repeatStack.isEmpty()) throw IOException("HSP break has no active repeat.")
                        repeatStack.removeLast()
                        programCounter = findMatchingLoop(instructions, programCounter + 1) + 1
                    }
                    Hsp3Instruction.Continue -> {
                        if (repeatStack.isEmpty()) throw IOException("HSP continue has no active repeat.")
                        programCounter = findMatchingLoop(instructions, programCounter + 1)
                    }
                    Hsp3Instruction.Wait -> {
                        programCounter += 1
                        status = Hsp3VmStatus.YIELDED
                        yieldDelayMillis = DEFAULT_WAIT_MS
                        return snapshot()
                    }
                    is Hsp3Instruction.WaitFor -> {
                        programCounter += 1
                        status = Hsp3VmStatus.YIELDED
                        yieldDelayMillis = instruction.delayMillis
                        return snapshot()
                    }
                    Hsp3Instruction.End -> {
                        status = Hsp3VmStatus.ENDED
                        return snapshot()
                    }
                    is Hsp3Instruction.Unsupported -> {
                        throw IOException(
                            "Unsupported HSP command token type ${instruction.commandType}, " +
                                "command ID ${instruction.commandId}."
                        )
                    }
                }
            }
            if (remainingBudget == 0 && programCounter in instructions.indices) {
                status = Hsp3VmStatus.YIELDED
                yieldDelayMillis = BUDGET_YIELD_MS
            }
            return snapshot()
        } catch (error: Exception) {
            status = Hsp3VmStatus.FAILED
            hspError = 1
            errorMessage = error.message ?: "HSP virtual machine execution failed."
            return snapshot()
        }
    }

    fun snapshot(): Hsp3VmSnapshot {
        val numericValues = variables.mapValues { (_, value) -> value.asInt() }
        return Hsp3VmSnapshot(
            programCounter = programCounter,
            executedInstructions = executedInstructions,
            status = status,
            variables = numericValues,
            errorMessage = errorMessage,
            values = variables.toMap(),
            yieldDelayMillis = yieldDelayMillis
        )
    }

    private fun executeNative(command: Hsp3Instruction.NativeCommand) {
        val args = command.arguments
        fun arg(index: Int, default: Hsp3RuntimeValue = Hsp3RuntimeValue.IntegerValue(0)) =
            args.getOrNull(index)?.let(::evaluate) ?: default
        fun intArg(index: Int, default: Int = 0) =
            arg(index, Hsp3RuntimeValue.IntegerValue(default)).asInt()
        fun stringArg(index: Int, default: String = "") =
            arg(index, Hsp3RuntimeValue.StringValue(default)).asString()

        if (command.commandType == Hsp3BytecodeDecoder.TYPE_EXTCMD) {
            when (command.commandId) {
                Hsp3CommandIds.MES -> {
                    val message = stringArg(0)
                    graphics.mes(message)
                    stringSize = message.length
                }
                Hsp3CommandIds.POS -> {
                    val current = graphics.currentPosition()
                    graphics.pos(intArg(0, current.first), intArg(1, current.second))
                }
                Hsp3CommandIds.FONT -> {
                    graphics.font(stringArg(0), intArg(1, 12), intArg(2, 0))
                }
                Hsp3CommandIds.PICLOAD -> graphics.picload(stringArg(0))
                Hsp3CommandIds.COLOR -> graphics.color(intArg(0), intArg(1), intArg(2))
                Hsp3CommandIds.REDRAW -> graphics.redraw(intArg(0, 1) != 0)
                Hsp3CommandIds.GSEL -> graphics.gsel(intArg(0))
                Hsp3CommandIds.GCOPY -> {
                    val position = graphics.currentPosition()
                    graphics.gcopy(
                        sourceId = intArg(0),
                        sourceX = intArg(1),
                        sourceY = intArg(2),
                        width = intArg(3, Hsp3CanvasRenderer.DEFAULT_WIDTH),
                        height = intArg(4, Hsp3CanvasRenderer.DEFAULT_HEIGHT),
                        destX = position.first,
                        destY = position.second
                    )
                }
                Hsp3CommandIds.GMODE -> graphics.gmode(intArg(0), intArg(1, 255))
                Hsp3CommandIds.BUFFER -> graphics.buffer(
                    intArg(0), intArg(1, Hsp3CanvasRenderer.DEFAULT_WIDTH),
                    intArg(2, Hsp3CanvasRenderer.DEFAULT_HEIGHT)
                )
                Hsp3CommandIds.SCREEN -> graphics.screen(
                    intArg(0), intArg(1, Hsp3CanvasRenderer.DEFAULT_WIDTH),
                    intArg(2, Hsp3CanvasRenderer.DEFAULT_HEIGHT)
                )
                Hsp3CommandIds.BOXF -> graphics.boxf(intArg(0), intArg(1), intArg(2), intArg(3))
                Hsp3CommandIds.STICK -> {
                    val destination = requiredVariable(args.getOrNull(0), "stick destination")
                    readStick(destination, intArg(1), intArg(2, 1) != 0)
                }
                else -> throw IOException(
                    "Unsupported HSP graphics command ID ${command.commandId}."
                )
            }
            return
        }
        throw IOException(
            "Unsupported HSP command token type ${command.commandType}, command ID ${command.commandId}."
        )
    }

    private fun readStick(destination: Int, nonTriggerMask: Int, activeWindowCheck: Boolean) {
        val current = input.stickMask(activeWindowCheck)
        val triggered = (current xor previousStickMask) and current
        previousStickMask = current
        val result = triggered or (current and nonTriggerMask)
        variables[destination] = Hsp3RuntimeValue.IntegerValue(result)
    }

    private fun requiredVariable(expression: Hsp3Expression?, label: String): Int =
        expression?.singleVariableReference()
            ?: throw IOException("The HSP $label must be a scalar variable.")

    private fun evaluate(expression: Hsp3Expression): Hsp3RuntimeValue =
        expression.evaluate(::valueOf, ::systemValue)

    private fun valueOf(index: Int): Hsp3RuntimeValue =
        variables[index] ?: Hsp3RuntimeValue.IntegerValue(0)

    private fun systemValue(index: Int): Hsp3RuntimeValue = when (index) {
        0 -> Hsp3RuntimeValue.IntegerValue(0)
        1 -> Hsp3RuntimeValue.IntegerValue(hspStat)
        2 -> Hsp3RuntimeValue.IntegerValue(0x301)
        3 -> Hsp3RuntimeValue.IntegerValue(hspStat)
        4 -> Hsp3RuntimeValue.IntegerValue(repeatStack.lastOrNull()?.current ?: 0)
        5 -> Hsp3RuntimeValue.IntegerValue(hspError)
        6 -> Hsp3RuntimeValue.IntegerValue(stringSize)
        7 -> Hsp3RuntimeValue.IntegerValue(repeatStack.size)
        8 -> Hsp3RuntimeValue.IntegerValue(callStack.size)
        9, 10, 11 -> Hsp3RuntimeValue.IntegerValue(0)
        12 -> Hsp3RuntimeValue.StringValue(referenceString)
        13 -> Hsp3RuntimeValue.DoubleValue(0.0)
        else -> throw IOException("Unsupported HSP system variable ID $index.")
    }

    private fun calculate(
        operator: Int,
        left: Hsp3RuntimeValue,
        right: Hsp3RuntimeValue
    ): Hsp3RuntimeValue {
        val expr = Hsp3Expression(
            Hsp3ExpressionNode.Binary(
                operator,
                Hsp3ExpressionNode.Literal(left),
                Hsp3ExpressionNode.Literal(right)
            )
        )
        return expr.evaluate({ Hsp3RuntimeValue.IntegerValue(0) }, { Hsp3RuntimeValue.IntegerValue(0) })
    }

    private fun checkedTarget(target: Int, instructionCount: Int): Int {
        if (target !in 0..instructionCount) {
            throw IOException("HSP branch target is outside the decoded instruction stream.")
        }
        return target
    }

    private fun findMatchingLoop(instructions: List<Hsp3Instruction>, start: Int): Int {
        var depth = 0
        for (index in start until instructions.size) {
            when (instructions[index]) {
                is Hsp3Instruction.Repeat -> depth += 1
                Hsp3Instruction.Loop -> {
                    if (depth == 0) return index
                    depth -= 1
                }
                else -> Unit
            }
        }
        throw IOException("HSP repeat block has no matching loop instruction.")
    }

    companion object {
        private const val DEFAULT_MAX_CALL_DEPTH = 256
        private const val DEFAULT_MAX_LOOP_DEPTH = 32
        private const val DEFAULT_INSTRUCTION_BUDGET = 10_000
        private const val DEFAULT_WAIT_MS = 100L
        private const val BUDGET_YIELD_MS = 16L
    }
}
