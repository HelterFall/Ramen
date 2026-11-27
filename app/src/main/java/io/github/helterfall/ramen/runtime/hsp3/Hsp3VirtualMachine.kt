package io.github.helterfall.ramen.runtime.hsp3

import java.io.IOException
import java.util.ArrayDeque
import java.util.Calendar
import java.util.concurrent.ThreadLocalRandom
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

interface Hsp3FileStorage {
    /** Returns a bounded file/resource payload, or null when no matching file exists. */
    @Throws(IOException::class)
    fun read(name: String): ByteArray?

    /** File length without materializing its contents, or null when missing. */
    @Throws(IOException::class)
    fun size(name: String): Long? = read(name)?.size?.toLong()

    /** Writes bytes inside the imported game's private directory. */
    @Throws(IOException::class)
    fun write(name: String, offset: Int, bytes: ByteArray)
}

object Hsp3UnavailableFileStorage : Hsp3FileStorage {
    override fun read(name: String): ByteArray? =
        throw IOException("HSP file I/O is unavailable in this runtime session.")

    override fun write(name: String, offset: Int, bytes: ByteArray) {
        throw IOException("HSP file I/O is unavailable in this runtime session.")
    }
}

interface Hsp3AudioPlayer {
    @Throws(IOException::class)
    fun load(bank: Int, encodedAudio: ByteArray, mode: Int)
    @Throws(IOException::class)
    fun play(bank: Int)
    @Throws(IOException::class)
    fun stop(bank: Int)
    fun close()
}

object Hsp3UnavailableAudioPlayer : Hsp3AudioPlayer {
    override fun load(bank: Int, encodedAudio: ByteArray, mode: Int) {
        throw IOException("HSP audio is unavailable in this runtime host.")
    }
    override fun play(bank: Int) {
        throw IOException("HSP audio is unavailable in this runtime host.")
    }
    override fun stop(bank: Int) {
        throw IOException("HSP audio is unavailable in this runtime host.")
    }
    override fun close() = Unit
}

sealed class Hsp3Instruction {
    data class Screen(val id: Int, val width: Int, val height: Int) : Hsp3Instruction()
    data class Buffer(val id: Int, val width: Int, val height: Int) : Hsp3Instruction()
    data class SelectSurface(val id: Int) : Hsp3Instruction()
    data class Picload(val name: String) : Hsp3Instruction()
    data class Cls(val mode: Int = 0) : Hsp3Instruction()
    data class Title(val value: String) : Hsp3Instruction()
    data class Width(val width: Int, val height: Int) : Hsp3Instruction()
    data class Palette(val arguments: List<Hsp3Expression?>) : Hsp3Instruction()
    data class Grect(
        val centerX: Int, val centerY: Int, val angleRadians: Double,
        val width: Int, val height: Int
    ) : Hsp3Instruction()
    data class FileExists(val fileName: Hsp3Expression) : Hsp3Instruction()
    data class Randomize(val seed: Hsp3Expression?) : Hsp3Instruction()
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
    data class Stick(
        val destinationVariable: Int,
        val nonTriggerMask: Int = 0,
        val destinationIndices: List<Hsp3Expression> = emptyList()
    ) : Hsp3Instruction()
    data class SetInt(val variable: Int, val value: Int) : Hsp3Instruction()
    data class AddInt(val variable: Int, val value: Int) : Hsp3Instruction()
    data class Assign(
        val variable: Int,
        val operator: Int,
        val expression: Hsp3Expression,
        val arrayIndices: List<Hsp3Expression> = emptyList()
    ) : Hsp3Instruction()
    data class Dimension(
        val variable: Int,
        val dimensions: List<Hsp3Expression?>,
        val stringBuffer: Boolean = false
    ) : Hsp3Instruction()
    data class FileTransfer(
        val commandId: Int,
        val fileName: Hsp3Expression,
        val destinationVariable: Int,
        val size: Hsp3Expression? = null,
        val fileOffset: Hsp3Expression? = null
    ) : Hsp3Instruction()
    data class NativeCommand(
        val commandType: Int,
        val commandId: Int,
        val arguments: List<Hsp3Expression?>
    ) : Hsp3Instruction()
    data class GetKey(
        val destinationVariable: Int,
        val keyCode: Hsp3Expression,
        val destinationIndices: List<Hsp3Expression> = emptyList()
    ) : Hsp3Instruction()
    data class Jump(val targetInstruction: Int) : Hsp3Instruction()
    data class JumpIfZero(val variable: Int, val targetInstruction: Int) : Hsp3Instruction()
    data class JumpIfFalse(val condition: Hsp3Expression, val targetInstruction: Int) : Hsp3Instruction()
    data class Gosub(val targetInstruction: Int) : Hsp3Instruction()
    object Return : Hsp3Instruction()
    data class Repeat(
        val count: Int,
        val exitInstruction: Int = -1,
        val initialValue: Int = 0,
        val countExpression: Hsp3Expression? = null,
        val initialValueExpression: Hsp3Expression? = null
    ) : Hsp3Instruction()
    object Loop : Hsp3Instruction()
    data class Break(val targetInstruction: Int) : Hsp3Instruction()
    data class Continue(
        val targetInstruction: Int,
        val countExpression: Hsp3Expression? = null
    ) : Hsp3Instruction()
    object Wait : Hsp3Instruction()
    data class WaitFor(
        val delayMillis: Long,
        val delayExpression: Hsp3Expression? = null,
        val delayMultiplier: Long = 1L
    ) : Hsp3Instruction()
    object End : Hsp3Instruction()
    data class Unsupported(
        val commandType:Int,val commandId:Int,val codeOffset:Int=-1,
        val tokenType:Int=-1,val tokenValue:Long=0L,val detail:String=""
    ) : Hsp3Instruction()
}

/** Input contract uses OpenHSP's stick bit assignments and VK-style getkey IDs. */
fun interface Hsp3InputProvider {
    /** Current held-button mask: arrows 1/2/4/8, space 16, enter 32, esc 128, Z/X 2048/4096. */
    fun stickMask(): Int

    fun stickMask(checkActiveWindow: Boolean): Int = stickMask()

    fun mousePosition(): Pair<Int, Int> = 0 to 0

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
    val yieldDelayMillis: Long = 0L,
    val arrayValues: Map<Pair<Int, List<Int>>, Hsp3RuntimeValue> = emptyMap(),
    val diagnostics: List<String> = emptyList()
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
    private val maxLoopDepth: Int = DEFAULT_MAX_LOOP_DEPTH,
    private val fileStorage: Hsp3FileStorage = Hsp3UnavailableFileStorage,
    private val audio: Hsp3AudioPlayer = Hsp3UnavailableAudioPlayer
) {
    private data class RepeatFrame(
        val bodyStart: Int,
        val exitInstruction: Int,
        val endCount: Long?,
        var current: Int
    )

    private val variables = linkedMapOf<Int, Hsp3RuntimeValue>()
    private val arrayDimensions = linkedMapOf<Int, List<Int>>()
    private val arrayValues = linkedMapOf<Pair<Int, List<Int>>, Hsp3RuntimeValue>()
    private val stringBuffers = linkedMapOf<Int, ByteArray>()
    private val callStack = ArrayDeque<Int>()
    private val repeatStack = ArrayDeque<RepeatFrame>()
    private var programCounter = 0
    private var executedInstructions = 0L
    private var previousStickMask = 0
    private var status = Hsp3VmStatus.READY
    private var errorMessage: String? = null
    private var yieldDelayMillis = 0L
    private var hspStat = 0
    private var statValue = 0
    private var hspError = 0
    private var stringSize = 0
    private var referenceString = ""
    private var referenceDouble = 0.0
    private var randomGenerator = java.util.Random()
    private val vmDiagnostics = mutableListOf<String>()

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
                    is Hsp3Instruction.Cls -> {
                        graphics.cls(instruction.mode)
                        programCounter += 1
                    }
                    is Hsp3Instruction.Title -> {
                        graphics.title(instruction.value)
                        programCounter += 1
                    }
                    is Hsp3Instruction.Width -> {
                        graphics.width(instruction.width, instruction.height)
                        programCounter += 1
                    }
                    is Hsp3Instruction.Palette -> {
                        graphics.palette(instruction.arguments.map { it?.let(::evaluate) })
                        programCounter += 1
                    }
                    is Hsp3Instruction.Grect -> {
                        graphics.grect(
                            instruction.centerX, instruction.centerY, instruction.angleRadians,
                            instruction.width, instruction.height
                        )
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
                        val indices = instruction.destinationIndices.map { evaluate(it).asInt() }
                        readStick(
                            instruction.destinationVariable,
                            indices,
                            instruction.nonTriggerMask,
                            true
                        )
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
                    is Hsp3Instruction.Dimension -> {
                        if (instruction.stringBuffer) {
                            if (instruction.dimensions.size > 1) {
                                throw IOException("Multi-dimensional HSP sdim buffers are not supported.")
                            }
                            val size = instruction.dimensions.firstOrNull()
                                ?.let { evaluate(it).asInt() } ?: DEFAULT_STRING_BUFFER_SIZE
                            if (size !in 1..MAX_STRING_BUFFER_BYTES) {
                                throw IOException("HSP sdim buffer size is outside the configured bound.")
                            }
                            stringBuffers[instruction.variable] = ByteArray(size)
                            arrayDimensions.remove(instruction.variable)
                            arrayValues.keys.removeAll { it.first == instruction.variable }
                            variables[instruction.variable] = Hsp3RuntimeValue.StringValue("")
                        } else {
                            stringBuffers.remove(instruction.variable)
                            val requested = instruction.dimensions
                                .mapNotNull { expression -> expression?.let { evaluate(it).asInt() } }
                                .ifEmpty { listOf(1) }
                            val invalidDimensions = requested.size !in 1..5 ||
                                requested.any { it <= 0 || it > MAX_ARRAY_DIMENSION }
                            val elementCount = if (invalidDimensions) {
                                MAX_ARRAY_ELEMENTS + 1L
                            } else {
                                requested.fold(1L) { total, size ->
                                    if (total > MAX_ARRAY_ELEMENTS / size.toLong()) {
                                        MAX_ARRAY_ELEMENTS + 1L
                                    } else {
                                        total * size.toLong()
                                    }
                                }
                            }
                            if (invalidDimensions || elementCount > MAX_ARRAY_ELEMENTS) {
                                throw IOException("HSP array dimensions exceed the configured bounds.")
                            }
                            arrayDimensions[instruction.variable] = requested
                            arrayValues.keys.removeAll { it.first == instruction.variable }
                            variables.remove(instruction.variable)
                        }
                        programCounter += 1
                    }
                    is Hsp3Instruction.Assign -> {
                        val indices = instruction.arrayIndices.map { evaluate(it).asInt() }
                        val old = readVariable(instruction.variable, indices)
                        val rhs = evaluate(instruction.expression)
                        val value = if (instruction.operator == Hsp3BytecodeDecoder.CALCCODE_EQ) {
                            rhs
                        } else {
                            calculate(instruction.operator, old, rhs)
                        }
                        writeVariable(instruction.variable, indices, value)
                        programCounter += 1
                    }
                    is Hsp3Instruction.FileTransfer -> {
                        executeFileTransfer(instruction)
                        programCounter += 1
                    }
                    is Hsp3Instruction.FileExists -> {
                        val fileName = evaluate(instruction.fileName).asString()
                        val length = fileStorage.size(fileName)
                        stringSize = length?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: -1
                        statValue = stringSize
                        programCounter += 1
                    }
                    is Hsp3Instruction.Randomize -> {
                        val seed = instruction.seed?.let { evaluate(it).asInt().toLong() }
                            ?: (System.currentTimeMillis() / 1_000L)
                        randomGenerator = java.util.Random(seed)
                        programCounter += 1
                    }
                    is Hsp3Instruction.NativeCommand -> {
                        executeNative(instruction)
                        programCounter += 1
                    }
                    is Hsp3Instruction.GetKey -> {
                        val keyCode = evaluate(instruction.keyCode).asInt()
                        val indices = instruction.destinationIndices.map { evaluate(it).asInt() }
                        writeVariable(
                            instruction.destinationVariable,
                            indices,
                            Hsp3RuntimeValue.IntegerValue(if (input.isKeyDown(keyCode)) 1 else 0)
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
                        val repeatCount = instruction.countExpression
                            ?.let { evaluate(it).asInt() } ?: instruction.count
                        val initialValue = instruction.initialValueExpression
                            ?.let { evaluate(it).asInt() } ?: instruction.initialValue
                        val exit = if (instruction.exitInstruction >= 0) {
                            checkedTarget(instruction.exitInstruction, instructions.size)
                        } else {
                            findMatchingLoop(instructions, programCounter + 1) + 1
                        }
                        if (repeatCount == 0) {
                            programCounter = exit
                        } else {
                            if (repeatStack.size >= maxLoopDepth) {
                                throw IOException("HSP repeat stack exceeded its configured depth.")
                            }
                            val endCount = if (repeatCount < 0) null
                                else initialValue.toLong() + repeatCount.toLong()
                            repeatStack.addLast(
                                RepeatFrame(programCounter + 1, exit, endCount, initialValue)
                            )
                            programCounter += 1
                        }
                    }
                    Hsp3Instruction.Loop -> {
                        val frame = repeatStack.lastOrNull()
                        if (frame == null) {
                            vmDiagnostics += "loop at instruction $programCounter has no active repeat; skipped."
                            programCounter += 1
                        } else {
                            programCounter = advanceRepeat(frame, instructions.size)
                        }
                    }
                    is Hsp3Instruction.Break -> {
                        if (repeatStack.isNotEmpty()) repeatStack.removeLast()
                        else vmDiagnostics += "break at instruction $programCounter has no active repeat."
                        programCounter = checkedTarget(instruction.targetInstruction, instructions.size)
                    }
                    is Hsp3Instruction.Continue -> {
                        val frame = repeatStack.lastOrNull()
                        if (frame == null) {
                            vmDiagnostics += "continue at instruction $programCounter has no active repeat."
                            programCounter = checkedTarget(instruction.targetInstruction, instructions.size)
                        } else {
                            val nextCount = instruction.countExpression
                                ?.let { evaluate(it).asInt() } ?: (frame.current + 1)
                            frame.current = nextCount - 1
                            programCounter = checkedTarget(
                                instruction.targetInstruction,
                                instructions.size
                            )
                        }
                    }
                    Hsp3Instruction.Wait -> {
                        programCounter += 1
                        status = Hsp3VmStatus.YIELDED
                        yieldDelayMillis = DEFAULT_WAIT_MS
                        return snapshot()
                    }
                    is Hsp3Instruction.WaitFor -> {
                        val requestedDelay = instruction.delayExpression
                            ?.let { evaluate(it).asInt().toLong() * instruction.delayMultiplier }
                            ?: instruction.delayMillis
                        programCounter += 1
                        status = Hsp3VmStatus.YIELDED
                        yieldDelayMillis = requestedDelay.coerceIn(0L, MAX_WAIT_MS)
                        return snapshot()
                    }
                    Hsp3Instruction.End -> {
                        status = Hsp3VmStatus.ENDED
                        return snapshot()
                    }
                    is Hsp3Instruction.Unsupported -> {
                        val message = instruction.detail.ifBlank {
                            "Unsupported HSP opcode type=${instruction.commandType} id=0x${instruction.commandId.toString(16)} at CS+0x${instruction.codeOffset.toString(16)}."
                        }
                        vmDiagnostics += message
                        throw IOException(message)
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
            yieldDelayMillis = yieldDelayMillis,
            arrayValues = arrayValues.toMap(),
            diagnostics = vmDiagnostics.toList()
        )
    }

    private fun advanceRepeat(frame: RepeatFrame, instructionCount: Int): Int {
        frame.current += 1
        return if (frame.endCount != null && frame.current.toLong() >= frame.endCount) {
            repeatStack.removeLast()
            checkedTarget(frame.exitInstruction, instructionCount)
        } else {
            checkedTarget(frame.bodyStart, instructionCount)
        }
    }

    private fun executeNative(command: Hsp3Instruction.NativeCommand) {
        val args = command.arguments
        val evaluated = arrayOfNulls<Hsp3RuntimeValue>(args.size)
        val wasEvaluated = BooleanArray(args.size)
        fun resolved(index: Int): Hsp3RuntimeValue? {
            if (index !in args.indices) return null
            if (!wasEvaluated[index]) {
                evaluated[index] = args[index]?.let(::evaluate)
                wasEvaluated[index] = true
            }
            return evaluated[index]
        }
        fun arg(index: Int, default: Hsp3RuntimeValue = Hsp3RuntimeValue.IntegerValue(0)) =
            resolved(index) ?: default
        fun intArg(index: Int, default: Int = 0) =
            arg(index, Hsp3RuntimeValue.IntegerValue(default)).asInt()
        fun stringArg(index: Int, default: String = "") =
            arg(index, Hsp3RuntimeValue.StringValue(default)).asString()
        fun doubleArg(index: Int, default: Double = 0.0) =
            arg(index, Hsp3RuntimeValue.DoubleValue(default)).asDouble()
        fun resolvedArguments(): List<Hsp3RuntimeValue?> = args.indices.map(::resolved)

        if (command.commandType == Hsp3BytecodeDecoder.TYPE_EXTCMD) {
            when (command.commandId) {
                Hsp3CommandIds.MMLOAD -> {
                    val fileName = stringArg(0)
                    val bank = intArg(1, 0)
                    val mode = intArg(2, 0)
                    val encodedAudio = fileStorage.read(fileName)
                        ?: throw IOException("HSP mmload audio resource '$fileName' was not found.")
                    audio.load(bank, encodedAudio, mode)
                    hspStat = bank
                    statValue = bank
                }
                Hsp3CommandIds.MMPLAY -> audio.play(intArg(0, 0))
                Hsp3CommandIds.MMSTOP -> audio.stop(intArg(0, -1))
                Hsp3CommandIds.MES -> {
                    val message = stringArg(0)
                    graphics.mes(message, intArg(1))
                    stringSize = message.toByteArray(SHIFT_JIS).size
                }
                Hsp3CommandIds.TITLE -> graphics.title(stringArg(0))
                Hsp3CommandIds.POS -> {
                    val pos = graphics.currentPosition()
                    graphics.pos(intArg(0, pos.first), intArg(1, pos.second))
                }
                Hsp3CommandIds.CLS -> graphics.cls(intArg(0))
                Hsp3CommandIds.FONT -> graphics.font(stringArg(0), intArg(1, 12), intArg(2, 0))
                Hsp3CommandIds.PICLOAD -> graphics.picload(stringArg(0), intArg(1))
                Hsp3CommandIds.COLOR -> graphics.color(intArg(0), intArg(1), intArg(2))
                Hsp3CommandIds.PALCOLOR, Hsp3CommandIds.PALETTE ->
                    graphics.palette(resolvedArguments())
                Hsp3CommandIds.REDRAW -> graphics.redraw(intArg(0, 1) != 0)
                Hsp3CommandIds.WIDTH -> {
                    val size = graphics.currentSurfaceSize()
                    graphics.width(
                        intArg(0, size.first).takeIf { it > 0 } ?: size.first,
                        intArg(1, size.second).takeIf { it > 0 } ?: size.second
                    )
                }
                Hsp3CommandIds.GSEL -> graphics.gsel(intArg(0))
                Hsp3CommandIds.GCOPY -> {
                    val pos = graphics.currentPosition()
                    val size = graphics.copySize()
                    graphics.gcopy(intArg(0), intArg(1), intArg(2),
                        intArg(3, size.first), intArg(4, size.second), pos.first, pos.second)
                }
                Hsp3CommandIds.GZOOM -> {
                    val dst = graphics.currentSurfaceSize()
                    val src = graphics.copySize()
                    graphics.gzoom(
                        intArg(0, dst.first), intArg(1, dst.second), intArg(2),
                        intArg(3), intArg(4), intArg(5, src.first), intArg(6, src.second), intArg(7)
                    )
                }
                Hsp3CommandIds.GMODE -> graphics.gmode(
                    intArg(0), intArg(1, 32), intArg(2, 32), intArg(3, 0)
                )
                Hsp3CommandIds.GETKEY -> {
                    val destination = requiredVariable(args.getOrNull(0), "getkey destination")
                    writeVariable(
                        destination.index,
                        destination.indices.map { evaluate(it).asInt() },
                        Hsp3RuntimeValue.IntegerValue(if (input.isKeyDown(intArg(1, 1))) 1 else 0)
                    )
                }
                Hsp3CommandIds.BUFFER -> graphics.buffer(
                    intArg(0), intArg(1, 640), intArg(2, 480)
                )
                Hsp3CommandIds.SCREEN -> graphics.screen(
                    intArg(0), intArg(1, 640), intArg(2, 480)
                )
                Hsp3CommandIds.BOXF -> if (args.isEmpty()) graphics.boxf()
                    else graphics.boxf(intArg(0), intArg(1), intArg(2), intArg(3))
                Hsp3CommandIds.STICK -> {
                    val destination = requiredVariable(args.getOrNull(0), "stick destination")
                    readStick(
                        destination.index,
                        destination.indices.map { evaluate(it).asInt() },
                        intArg(1),
                        intArg(2, 1) != 0
                    )
                }
                Hsp3CommandIds.GRECT -> {
                    val size = graphics.currentSurfaceSize()
                    graphics.grect(
                        intArg(0), intArg(1), doubleArg(2),
                        intArg(3, size.first), intArg(4, size.second)
                    )
                }
                Hsp3CommandIds.GROTATE -> {
                    val size = graphics.copySize()
                    graphics.grotate(
                        intArg(0), intArg(1), intArg(2), doubleArg(3),
                        intArg(4, size.first), intArg(5, size.second)
                    )
                }
                Hsp3CommandIds.CELLLOAD -> {
                    hspStat = graphics.celload(stringArg(0), intArg(1, -2), intArg(2))
                    statValue = hspStat
                }
                Hsp3CommandIds.CELDIV -> graphics.celdiv(
                    intArg(0, 1), intArg(1), intArg(2), intArg(3), intArg(4)
                )
                Hsp3CommandIds.CELPUT -> graphics.celput(
                    intArg(0, 1), intArg(1), doubleArg(2, 1.0), doubleArg(3, 1.0), doubleArg(4)
                )
                else -> throw IOException("Unsupported HSP graphics command ID ${command.commandId}.")
            }
            return
        }
        throw IOException(
            "Unsupported HSP command token type ${command.commandType}, command ID ${command.commandId}."
        )
    }

    private fun executeFileTransfer(instruction: Hsp3Instruction.FileTransfer) {
        val fileName = evaluate(instruction.fileName).asString()
        if (fileName.isBlank()) throw IOException("HSP bload/bsave filename is empty.")
        val sourceOffset = (instruction.fileOffset?.let { evaluate(it).asInt() } ?: 0)
            .coerceAtLeast(0)
        val capacity = variableBytes(instruction.destinationVariable)
        val requested = instruction.size?.let { evaluate(it).asInt() } ?: -1
        val transferSize = if (requested < 0) capacity.size else minOf(requested, capacity.size)
        if (instruction.commandId == Hsp3BytecodeDecoder.INTCMD_BLOAD) {
            val fileBytes = fileStorage.read(fileName)
                ?: throw IOException("HSP bload file was not found: " + fileName)
            val start = sourceOffset.coerceAtMost(fileBytes.size)
            val count = minOf(transferSize, fileBytes.size - start)
            val loaded = fileBytes.copyOfRange(start, start + count)
            writeVariableBytes(instruction.destinationVariable, loaded, count)
            stringSize = count
        } else if (instruction.commandId == Hsp3BytecodeDecoder.INTCMD_BSAVE) {
            fileStorage.write(fileName, sourceOffset, capacity.copyOfRange(0, transferSize))
        } else {
            throw IOException("Unsupported HSP file command ID " + instruction.commandId)
        }
    }

    private fun variableBytes(index: Int): ByteArray {
        stringBuffers[index]?.let { return it.copyOf() }
        val dimensions = arrayDimensions[index]
        if (dimensions != null) {
            val count = dimensions.fold(1L) { total, size -> total * size.toLong() }
            if (count > MAX_ARRAY_ELEMENTS) throw IOException("HSP array byte buffer exceeds its configured bound.")
            val output = ByteBuffer.allocate(count.toInt() * Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            repeat(count.toInt()) { linearIndex ->
                val indices = linearIndexToIndices(linearIndex, dimensions)
                output.putInt((arrayValues[index to indices] ?: Hsp3RuntimeValue.IntegerValue(0)).asInt())
            }
            return output.array()
        }
        return when (val value = valueOf(index)) {
            is Hsp3RuntimeValue.StringValue -> value.value.toByteArray(SHIFT_JIS) + byteArrayOf(0)
            is Hsp3RuntimeValue.DoubleValue -> ByteBuffer.allocate(java.lang.Double.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN).putDouble(value.value).array()
            is Hsp3RuntimeValue.IntegerValue -> ByteBuffer.allocate(Int.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN).putInt(value.value).array()
            is Hsp3RuntimeValue.LabelValue -> ByteBuffer.allocate(Int.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN).putInt(value.codeOffsetBytes).array()
        }
    }

    private fun writeVariableBytes(index: Int, source: ByteArray, count: Int) {
        val stringBuffer = stringBuffers[index]
        if (stringBuffer != null) {
            stringBuffer.fill(0)
            source.copyInto(stringBuffer, endIndex = minOf(count, stringBuffer.size))
            variables[index] = Hsp3RuntimeValue.StringValue(decodeStringBuffer(stringBuffer))
            return
        }
        val dimensions = arrayDimensions[index]
        if (dimensions != null) {
            val inputBytes = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN)
            val elementCount = dimensions.fold(1L) { total, size -> total * size.toLong() }.toInt()
            repeat(elementCount) { linearIndex ->
                val value = if (inputBytes.remaining() >= Int.SIZE_BYTES) inputBytes.int else 0
                arrayValues[index to linearIndexToIndices(linearIndex, dimensions)] =
                    Hsp3RuntimeValue.IntegerValue(value)
            }
            return
        }
        when (valueOf(index)) {
            is Hsp3RuntimeValue.StringValue -> {
                variables[index] = Hsp3RuntimeValue.StringValue(decodeStringBuffer(source.copyOf(count)))
            }
            is Hsp3RuntimeValue.DoubleValue -> {
                val merged = variableBytes(index)
                source.copyInto(merged, endIndex = minOf(count, merged.size))
                if (merged.size >= java.lang.Double.BYTES) {
                    variables[index] = Hsp3RuntimeValue.DoubleValue(
                        ByteBuffer.wrap(merged).order(ByteOrder.LITTLE_ENDIAN).double
                    )
                }
            }
            else -> {
                val merged = variableBytes(index)
                source.copyInto(merged, endIndex = minOf(count, merged.size))
                if (merged.size >= Int.SIZE_BYTES) {
                    variables[index] = Hsp3RuntimeValue.IntegerValue(
                        ByteBuffer.wrap(merged).order(ByteOrder.LITTLE_ENDIAN).int
                    )
                }
            }
        }
    }

    private fun linearIndexToIndices(linearIndex: Int, dimensions: List<Int>): List<Int> {
        var remaining = linearIndex
        val result = MutableList(dimensions.size) { 0 }
        for (dimension in dimensions.indices.reversed()) {
            result[dimension] = remaining % dimensions[dimension]
            remaining /= dimensions[dimension]
        }
        return result
    }

    private fun decodeStringBuffer(bytes: ByteArray): String {
        val terminator = bytes.indexOf(0)
        return String(bytes, 0, if (terminator >= 0) terminator else bytes.size, SHIFT_JIS)
    }

    private fun readStick(
        destination: Int,
        indices: List<Int>,
        nonTriggerMask: Int,
        activeWindowCheck: Boolean
    ) {
        val current = input.stickMask(activeWindowCheck)
        val triggered = (current xor previousStickMask) and current
        previousStickMask = current
        val result = triggered or (current and nonTriggerMask)
        writeVariable(destination, indices, Hsp3RuntimeValue.IntegerValue(result))
    }

    private fun requiredVariable(expression: Hsp3Expression?, label: String): Hsp3VariableReference =
        expression?.variableReferenceOrNull()
            ?: throw IOException("The HSP $label must be a variable reference.")

    private fun evaluate(expression: Hsp3Expression): Hsp3RuntimeValue =
        expression.evaluate(
            variable = ::valueOf,
            systemVariable = ::systemValue,
            function = ::evaluateFunction,
            arrayVariable = ::arrayValueOf,
            externalSystemVariable = ::externalSystemValue
        )

    /** Executes the common built-ins defined by OpenHSP's TYPE_INTFUNC table. */
    private fun evaluateFunction(
        functionId: Int,
        arguments: List<Hsp3RuntimeValue?>
    ): Hsp3RuntimeValue {
        fun value(index: Int): Hsp3RuntimeValue =
            arguments.getOrNull(index)
                ?: throw IOException("HSP function $functionId is missing argument ${index + 1}.")
        fun int(index: Int): Int = value(index).asInt()
        fun intDefault(index: Int, default: Int): Int =
            arguments.getOrNull(index)?.asInt() ?: default
        fun double(index: Int): Double = value(index).asDouble()
        fun doubleDefault(index: Int, default: Double): Double =
            arguments.getOrNull(index)?.asDouble() ?: default
        fun string(index: Int): String = value(index).asString()

        return when (functionId) {
            0x000 -> Hsp3RuntimeValue.IntegerValue(int(0)) // int()
            0x001 -> {
                val upperBound = int(0)
                if (upperBound == 0) throw IOException("rnd() requires a non-zero upper bound.")
                val bound = kotlin.math.abs(upperBound.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                Hsp3RuntimeValue.IntegerValue(randomGenerator.nextInt(bound))
            }
            0x002 -> Hsp3RuntimeValue.IntegerValue(string(0).toByteArray(SHIFT_JIS).size) // strlen()
            0x00F -> {
                val source = string(0).toByteArray(SHIFT_JIS)
                val start = intDefault(1, 0)
                val target = string(2)
                if (start < 0 || start > source.size) Hsp3RuntimeValue.IntegerValue(-1)
                else {
                    val suffix = String(source, start, source.size - start, SHIFT_JIS)
                    val found = suffix.indexOf(target)
                    val result = if (found < 0) -1
                        else start + suffix.substring(0, found).toByteArray(SHIFT_JIS).size
                    Hsp3RuntimeValue.IntegerValue(result)
                }
            }
            0x008 -> Hsp3RuntimeValue.IntegerValue(readTimePart(int(0))) // gettime()
            0x010 -> Hsp3RuntimeValue.IntegerValue(abs(int(0))) // abs()
            0x011 -> {
                val source = int(0)
                val lower = intDefault(1, Int.MIN_VALUE)
                val upper = intDefault(2, Int.MAX_VALUE)
                Hsp3RuntimeValue.IntegerValue(source.coerceIn(lower, upper))
            }
            0x100 -> Hsp3RuntimeValue.StringValue(string(0)) // str()
            0x101 -> {
                val source = string(0).toByteArray(SHIFT_JIS)
                var start = int(1)
                val length = int(2)
                if (start < 0) start = (source.size - length).coerceAtLeast(0)
                if (start >= source.size || length <= 0) Hsp3RuntimeValue.StringValue("")
                else {
                    val end = (start.toLong() + length.toLong()).coerceAtMost(source.size.toLong()).toInt()
                    Hsp3RuntimeValue.StringValue(String(source, start, end - start, SHIFT_JIS))
                }
            }
            0x180 -> Hsp3RuntimeValue.DoubleValue(sin(doubleDefault(0, 0.0)))
            0x181 -> Hsp3RuntimeValue.DoubleValue(cos(doubleDefault(0, 0.0)))
            0x182 -> Hsp3RuntimeValue.DoubleValue(tan(doubleDefault(0, 0.0)))
            0x183 -> Hsp3RuntimeValue.DoubleValue(
                atan2(double(0), doubleDefault(1, 1.0))
            )
            0x184 -> {
                val operand = doubleDefault(0, 0.0)
                if (operand < 0.0) throw IOException("sqrt() cannot accept a negative value.")
                Hsp3RuntimeValue.DoubleValue(sqrt(operand))
            }
            0x185 -> Hsp3RuntimeValue.DoubleValue(value(0).asDouble())
            0x186 -> Hsp3RuntimeValue.DoubleValue(abs(double(0)))
            0x187 -> Hsp3RuntimeValue.DoubleValue(exp(double(0)))
            0x188 -> {
                val operand = double(0)
                if (operand <= 0.0) throw IOException("logf() requires a positive value.")
                Hsp3RuntimeValue.DoubleValue(ln(operand))
            }
            0x189 -> {
                val source = double(0)
                Hsp3RuntimeValue.DoubleValue(
                    source.coerceIn(doubleDefault(1, Double.NEGATIVE_INFINITY),
                        doubleDefault(2, Double.POSITIVE_INFINITY))
                )
            }
            0x18A -> Hsp3RuntimeValue.DoubleValue(double(0).pow(double(1)))
            else -> throw IOException("Unsupported HSP built-in function ID $functionId.")
        }
    }

    private fun readTimePart(index: Int): Int {
        val now = Calendar.getInstance()
        return when (index) {
            0 -> now.get(Calendar.YEAR)
            1 -> now.get(Calendar.MONTH) + 1
            2 -> now.get(Calendar.DAY_OF_WEEK) - 1
            3 -> now.get(Calendar.DAY_OF_MONTH)
            4 -> now.get(Calendar.HOUR_OF_DAY)
            5 -> now.get(Calendar.MINUTE)
            6 -> now.get(Calendar.SECOND)
            7 -> now.get(Calendar.MILLISECOND)
            else -> throw IOException("gettime() type must be between 0 and 7.")
        }
    }

    private fun valueOf(index: Int): Hsp3RuntimeValue =
        variables[index] ?: Hsp3RuntimeValue.IntegerValue(0)

    private fun arrayValueOf(index: Int, indices: List<Int>): Hsp3RuntimeValue =
        readVariable(index, indices)

    private fun readVariable(index: Int, indices: List<Int>): Hsp3RuntimeValue {
        val dimensions = arrayDimensions[index]
        if (dimensions == null) {
            if (indices.isNotEmpty()) {
                throw IOException("HSP variable $index has not been dimensioned as an array.")
            }
            return valueOf(index)
        }
        // OpenHSP resets an unindexed PVal to APTR 0. Therefore a bare array
        // reference denotes the first element rather than a separate scalar.
        val resolvedIndices = if (indices.isEmpty()) List(dimensions.size) { 0 } else indices
        if (resolvedIndices.size != dimensions.size ||
            resolvedIndices.indices.any { resolvedIndices[it] !in 0 until dimensions[it] }
        ) {
            throw IOException("HSP array index is outside the declared dimensions.")
        }
        return arrayValues[index to resolvedIndices]
            ?: Hsp3RuntimeValue.IntegerValue(0)
    }

    private fun writeVariable(index: Int, indices: List<Int>, value: Hsp3RuntimeValue) {
        val dimensions = arrayDimensions[index]
        if (dimensions == null) {
            if (indices.isNotEmpty()) {
                throw IOException("HSP variable $index has not been dimensioned as an array.")
            }
            variables[index] = value
            return
        }
        val resolvedIndices = if (indices.isEmpty()) List(dimensions.size) { 0 } else indices
        if (resolvedIndices.size != dimensions.size ||
            resolvedIndices.indices.any { resolvedIndices[it] !in 0 until dimensions[it] }
        ) {
            throw IOException("HSP array index is outside the declared dimensions.")
        }
        arrayValues[index to resolvedIndices.toList()] = value
    }

    private fun systemValue(index: Int): Hsp3RuntimeValue = when (index) {
        0 -> Hsp3RuntimeValue.IntegerValue(0) // system
        1 -> Hsp3RuntimeValue.IntegerValue(hspStat) // hspstat
        2 -> Hsp3RuntimeValue.IntegerValue(0x301) // hspver
        3 -> Hsp3RuntimeValue.IntegerValue(statValue) // stat
        4 -> Hsp3RuntimeValue.IntegerValue(repeatStack.lastOrNull()?.current ?: 0) // cnt
        5 -> Hsp3RuntimeValue.IntegerValue(hspError) // err
        6 -> Hsp3RuntimeValue.IntegerValue(stringSize) // strsize
        7 -> Hsp3RuntimeValue.IntegerValue(repeatStack.size) // looplev
        8 -> Hsp3RuntimeValue.IntegerValue(callStack.size) // sublev
        9, 10, 11 -> Hsp3RuntimeValue.IntegerValue(0)
        12 -> Hsp3RuntimeValue.StringValue(referenceString) // refstr
        13 -> Hsp3RuntimeValue.DoubleValue(referenceDouble) // refdval
        else -> throw IOException("Unsupported HSP system variable ID $index.")
    }

    private fun externalSystemValue(index: Int): Hsp3RuntimeValue {
        val p = input.mousePosition()
        return when (index) {
            0 -> Hsp3RuntimeValue.IntegerValue(p.first) // mousex
            1 -> Hsp3RuntimeValue.IntegerValue(p.second) // mousey
            else -> throw IOException("Unsupported HSP external system variable ID $index.")
        }
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
        private const val MAX_WAIT_MS = 60_000L
        private const val MAX_ARRAY_DIMENSION = 65_536
        private const val MAX_ARRAY_ELEMENTS = 1_000_000L
        private const val DEFAULT_STRING_BUFFER_SIZE = 64
        private const val MAX_STRING_BUFFER_BYTES = 16 * 1024 * 1024
        private val SHIFT_JIS: Charset = Charset.forName("Shift_JIS")
    }
}
