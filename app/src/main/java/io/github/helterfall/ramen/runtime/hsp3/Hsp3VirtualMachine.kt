package io.github.helterfall.ramen.runtime.hsp3

import java.io.IOException
import java.util.ArrayDeque

/** Typed execution instruction emitted by a future HSP3 command/parameter decoder. */
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
    data class Jump(val targetInstruction: Int) : Hsp3Instruction()
    data class JumpIfZero(val variable: Int, val targetInstruction: Int) : Hsp3Instruction()
    data class Gosub(val targetInstruction: Int) : Hsp3Instruction()
    object Return : Hsp3Instruction()
    data class Repeat(val count: Int) : Hsp3Instruction()
    object Loop : Hsp3Instruction()
    object Break : Hsp3Instruction()
    object Continue : Hsp3Instruction()
    object Wait : Hsp3Instruction()
    object End : Hsp3Instruction()
    data class Unsupported(val commandType: Int, val commandId: Int) : Hsp3Instruction()
}

fun interface Hsp3InputProvider {
    /** Current held-button bit mask using HSP stick bit assignments. */
    fun stickMask(): Int
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
    val errorMessage: String? = null
)

/**
 * Bounded VM for the first HSP3 intermediate instruction set.
 *
 * The VM implements a controllable instruction loop, variables, branches,
 * subroutines, repeat/loop, wait/yield and the renderer/input bridge. It does
 * not yet translate raw TYPE_EXTCMD token streams into this IR; command IDs
 * and argument decoding are a separate, explicitly replaceable layer.
 */
class Hsp3VirtualMachine(
    private val graphics: Hsp3Graphics,
    private val input: Hsp3InputProvider = Hsp3InputProvider { 0 },
    private val maxCallDepth: Int = DEFAULT_MAX_CALL_DEPTH,
    private val maxLoopDepth: Int = DEFAULT_MAX_LOOP_DEPTH
) {
    private data class RepeatFrame(
        val bodyStart: Int,
        var remaining: Int
    )

    private val variables = linkedMapOf<Int, Int>()
    private val callStack = ArrayDeque<Int>()
    private val repeatStack = ArrayDeque<RepeatFrame>()
    private var programCounter = 0
    private var executedInstructions = 0L
    private var previousStickMask = 0
    private var status = Hsp3VmStatus.READY
    private var errorMessage: String? = null

    init {
        require(maxCallDepth > 0)
        require(maxLoopDepth > 0)
    }

    /**
     * Executes at most [instructionBudget] IR instructions and returns to the
     * Android host on WAIT, end-of-program, error, or budget exhaustion.
     */
    fun runSlice(
        instructions: List<Hsp3Instruction>,
        instructionBudget: Int = DEFAULT_INSTRUCTION_BUDGET
    ): Hsp3VmSnapshot {
        require(instructionBudget > 0)
        if (status == Hsp3VmStatus.ENDED || status == Hsp3VmStatus.FAILED) {
            return snapshot()
        }
        status = Hsp3VmStatus.READY
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
                            instruction.sourceId,
                            instruction.sourceX,
                            instruction.sourceY,
                            instruction.width,
                            instruction.height,
                            instruction.destX,
                            instruction.destY
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
                        val current = input.stickMask()
                        val triggered = (current xor previousStickMask) and current
                        previousStickMask = current
                        val result = triggered or (current and instruction.nonTriggerMask)
                        variables[instruction.destinationVariable] = result
                        programCounter += 1
                    }
                    is Hsp3Instruction.SetInt -> {
                        variables[instruction.variable] = instruction.value
                        programCounter += 1
                    }
                    is Hsp3Instruction.AddInt -> {
                        variables[instruction.variable] =
                            (variables[instruction.variable] ?: 0) + instruction.value
                        programCounter += 1
                    }
                    is Hsp3Instruction.Jump -> {
                        programCounter = checkedTarget(instruction.targetInstruction, instructions.size)
                    }
                    is Hsp3Instruction.JumpIfZero -> {
                        programCounter = if ((variables[instruction.variable] ?: 0) == 0) {
                            checkedTarget(instruction.targetInstruction, instructions.size)
                        } else {
                            programCounter + 1
                        }
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
                        if (instruction.count <= 0) {
                            programCounter = findMatchingLoop(instructions, programCounter + 1) + 1
                        } else {
                            if (repeatStack.size >= maxLoopDepth) {
                                throw IOException("HSP repeat stack exceeded its configured depth.")
                            }
                            repeatStack.addLast(
                                RepeatFrame(
                                    bodyStart = programCounter + 1,
                                    remaining = instruction.count
                                )
                            )
                            programCounter += 1
                        }
                    }
                    Hsp3Instruction.Loop -> {
                        val frame = repeatStack.lastOrNull()
                            ?: throw IOException("HSP loop has no matching repeat.")
                        frame.remaining -= 1
                        if (frame.remaining > 0) {
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
                        return snapshot()
                    }
                    Hsp3Instruction.End -> {
                        status = Hsp3VmStatus.ENDED
                        return snapshot()
                    }
                    is Hsp3Instruction.Unsupported -> {
                        throw IOException(
                            "Unsupported HSP command token type " + instruction.commandType +
                                ", command ID " + instruction.commandId + "."
                        )
                    }
                }
            }
            if (remainingBudget == 0 && programCounter in instructions.indices) {
                status = Hsp3VmStatus.YIELDED
            }
            return snapshot()
        } catch (error: Exception) {
            status = Hsp3VmStatus.FAILED
            errorMessage = error.message ?: "HSP virtual machine execution failed."
            return snapshot()
        }
    }

    fun snapshot(): Hsp3VmSnapshot = Hsp3VmSnapshot(
        programCounter = programCounter,
        executedInstructions = executedInstructions,
        status = status,
        variables = variables.toMap(),
        errorMessage = errorMessage
    )

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
    }
}
