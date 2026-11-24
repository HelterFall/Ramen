package io.github.helterfall.ramen.runtime.livemaker

import io.github.helterfall.ramen.inspection.LiveMakerCommand
import io.github.helterfall.ramen.inspection.LiveMakerExpression
import io.github.helterfall.ramen.inspection.LiveMakerLsb
import io.github.helterfall.ramen.inspection.LiveMakerOperation
import io.github.helterfall.ramen.inspection.LiveMakerParameter
import io.github.helterfall.ramen.inspection.LiveMakerParameterValue
import java.nio.charset.Charset
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

sealed interface LiveMakerVmEvent {
    data class TextBlock(
        val tpWordBytes: ByteArray,
        val targetExpression: LiveMakerExpression,
        val historyExpression: LiveMakerExpression,
        val shouldWait: Boolean,
        val stopEventProcessing: Boolean,
        val commandIndex: Int
    ) : LiveMakerVmEvent

    data class Delay(
        val milliseconds: Long,
        val stopEventProcessing: Boolean,
        val commandIndex: Int
    ) : LiveMakerVmEvent

    data class ScriptTransfer(
        val page: String,
        val labelIndex: Long,
        val isCall: Boolean,
        val returnVariable: String?,
        val arguments: List<LiveMakerExpression>,
        val commandIndex: Int
    ) : LiveMakerVmEvent

    data class HostCommandRequired(
        val command: LiveMakerCommand
    ) : LiveMakerVmEvent

    data class UnsupportedExpression(
        val commandIndex: Int,
        val reason: String
    ) : LiveMakerVmEvent

    data object Running : LiveMakerVmEvent
    data object AwaitingHost : LiveMakerVmEvent
    data object Completed : LiveMakerVmEvent
}

/**
 * First execution core for the parsed LSB subset.
 *
 * This is deliberately a host-driven VM: graphics, audio, save/load, user input, cross-page
 * calls, and other platform features are surfaced as events rather than silently skipped.
 */
class LiveMakerVirtualMachine(
    private var script: LiveMakerLsb,
    private val maxInstructionsPerStep: Int = 10_000,
    initialScriptIdentifier: String? = null,
    val objectStore: LiveMakerObjectStore = LiveMakerObjectStore(),
    private val systemEnvironment: LiveMakerSystemEnvironment = LiveMakerSystemEnvironment(),
    private val textHistoryHost: LiveMakerTextHistoryHostAdapter = LiveMakerTextHistoryHostAdapter()
) {
    private data class CallFrame(
        val script: LiveMakerLsb,
        val returnCommandIndex: Int,
        val resultVariable: String?,
        val scriptIdentifier: String?
    )

    private val variables = linkedMapOf<String, Any?>()
    /** VarNew's serialized ParamType is used by LiveMaker to select Plus vs ComboStr semantics. */
    private val variableTypes = linkedMapOf<String, Int>()
    private val callStack = ArrayDeque<CallFrame>()
    private var programCounter = 0
    private var scriptIdentifier: String? = initialScriptIdentifier
    private var completed = false
    private var waitingForHost = false

    init {
        require(script.isComplete) {
            "Cannot execute a partially parsed LiveMaker script."
        }
        require(maxInstructionsPerStep > 0)
    }

    fun variablesSnapshot(): Map<String, Any?> = variables.toMap()

    fun currentCommandIndex(): Int = programCounter

    /** Logical page/script identifier, restored automatically when returning from a Call. */
    fun currentScriptIdentifier(): String? = scriptIdentifier

    /**
     * Runs until a host event, the end of the script, or the bounded instruction budget.
     * Call resume() after the host has handled an event that blocks execution.
     */
    fun runUntilEvent(): LiveMakerVmEvent {
        if (completed) return LiveMakerVmEvent.Completed
        if (waitingForHost) {
            return LiveMakerVmEvent.AwaitingHost
        }

        var executed = 0
        while (executed < maxInstructionsPerStep) {
            if (programCounter >= script.commands.size) {
                if (callStack.isNotEmpty()) {
                    returnFromCall()
                    continue
                }
                completed = true
                return LiveMakerVmEvent.Completed
            }

            val command = script.commands[programCounter]
            executed += 1

            try {
                when (command.typeId) {
                    0 -> {
                        if (truthy(evaluateRequired(command, 0))) {
                            programCounter += 1
                        } else {
                            enterNextConditionalBranch(command.indent, programCounter + 1)
                        }
                    }
                    1, 2 -> {
                        // Reaching an Elseif/Else by falling through means an earlier
                        // branch was already taken. Skip the remaining alternatives.
                        programCounter = skipConditionalRemainder(programCounter, command.indent)
                    }
                    3, 27 -> programCounter += 1
                    45 -> {
                        completed = true
                        return LiveMakerVmEvent.Completed
                    }
                    14 -> {
                        evaluateRequired(command, 0)
                        programCounter += 1
                    }
                    31 -> {
                        val condition = truthy(evaluateRequired(command, 0))
                        val endIndex = command.integers.firstOrNull()
                            ?: return unsupported(command, "While is missing its End index.")
                        if (condition) {
                            programCounter += 1
                        } else {
                            programCounter = checkedCommandTarget(endIndex + 2L, command)
                        }
                    }
                    32 -> {
                        // WhileInit runs once before the corresponding While command.
                        evaluateRequired(command, 0)
                        programCounter += 1
                    }
                    33 -> {
                        evaluateRequired(command, 0)
                        val startIndex = command.integers.firstOrNull()
                            ?: return unsupported(command, "WhileLoop is missing its Start index.")
                        programCounter = checkedCommandTarget(startIndex + 1L, command)
                    }
                    34 -> {
                        val shouldBreak = truthy(evaluateRequired(command, 0))
                        val endIndex = command.integers.firstOrNull()
                            ?: return unsupported(command, "Break is missing its End index.")
                        programCounter = if (shouldBreak) {
                            checkedCommandTarget(endIndex + 2L, command)
                        } else {
                            programCounter + 1
                        }
                    }
                    35 -> {
                        val shouldContinue = truthy(evaluateRequired(command, 0))
                        val startIndex = command.integers.firstOrNull()
                            ?: return unsupported(command, "Continue is missing its Start index.")
                        programCounter = if (shouldContinue) {
                            checkedCommandTarget(startIndex + 1L, command)
                        } else {
                            programCounter + 1
                        }
                    }
                    15 -> {
                        val variableName = command.strings.firstOrNull()
                            ?: return unsupported(command, "VarNew is missing its variable name.")
                        val variableType = command.byteValues.firstOrNull()
                        val initial = evaluateRequired(command, 0, variableType)
                        variables[variableName] = initial
                        variableType?.let { variableTypes[variableName] = it }
                        programCounter += 1
                    }
                    16 -> {
                        val variableName = command.strings.firstOrNull()
                            ?: return unsupported(command, "VarDel is missing its variable name.")
                        variables.remove(variableName)
                        variableTypes.remove(variableName)
                        programCounter += 1
                    }
                    20 -> {
                        val rawText = command.binaryBlobs.firstOrNull()
                            ?: return unsupported(command, "TextIns is missing its TpWord block.")
                        val targetExpression = command.expressions.getOrNull(0)
                            ?: return unsupported(command, "TextIns is missing its target expression.")
                        val historyExpression = command.expressions.getOrNull(1)
                            ?: return unsupported(command, "TextIns is missing its history expression.")
                        val requestedWait = command.expressions.getOrNull(2)
                            ?.let { truthy(evaluate(it)) } ?: true
                        val stopEventProcessing = script.version > 106 &&
                            (command.expressions.getOrNull(3)
                                ?.let { truthy(evaluate(it)) } ?: false)
                        val shouldWait = requestedWait &&
                            (script.version <= 106 || stopEventProcessing)
                        programCounter += 1
                        waitingForHost = shouldWait
                        return LiveMakerVmEvent.TextBlock(
                            tpWordBytes = rawText.copyOf(),
                            targetExpression = targetExpression,
                            historyExpression = historyExpression,
                            shouldWait = shouldWait,
                            stopEventProcessing = stopEventProcessing,
                            commandIndex = command.index
                        )
                    }
                    4 -> {
                        val page = command.strings.firstOrNull()
                            ?: return unsupported(command, "Jump is missing its target page.")
                        val label = command.integers.firstOrNull()
                            ?: return unsupported(command, "Jump is missing its target label.")
                        val condition = evaluateRequired(command, 0)
                        programCounter += 1
                        if (truthy(condition)) {
                            waitingForHost = true
                            return LiveMakerVmEvent.ScriptTransfer(
                                page = page,
                                labelIndex = label,
                                isCall = false,
                                returnVariable = null,
                                arguments = emptyList(),
                                commandIndex = command.index
                            )
                        }
                    }
                    5 -> {
                        val page = command.strings.firstOrNull()
                            ?: return unsupported(command, "Call is missing its target page.")
                        val label = command.integers.firstOrNull()
                            ?: return unsupported(command, "Call is missing its target label.")
                        val resultName = command.strings.getOrNull(1)?.takeIf { it.isNotEmpty() }
                        val condition = evaluateRequired(command, 0)
                        programCounter += 1
                        if (truthy(condition)) {
                            waitingForHost = true
                            return LiveMakerVmEvent.ScriptTransfer(
                                page = page,
                                labelIndex = label,
                                isCall = true,
                                returnVariable = resultName,
                                arguments = command.expressions.drop(1),
                                commandIndex = command.index
                            )
                        }
                    }
                    6 -> {
                        val shouldExit = truthy(evaluateRequired(command, 0))
                        programCounter += 1
                        if (shouldExit) {
                            if (callStack.isNotEmpty()) {
                                returnFromCall()
                            } else {
                                completed = true
                                return LiveMakerVmEvent.Completed
                            }
                        }
                    }
                    7 -> {
                        val shouldWait = truthy(evaluateRequired(command, 0))
                        val duration = if (shouldWait) {
                            number(evaluateRequired(command, 1))?.toLong()
                                ?: return unsupported(command, "Wait duration is not numeric.")
                        } else {
                            0L
                        }
                        val stopEventProcessing = script.version > 106 &&
                            (command.expressions.getOrNull(2)
                                ?.let { truthy(evaluate(it)) } ?: false)
                        programCounter += 1
                        if (shouldWait && duration > 0L) {
                            waitingForHost = true
                            return LiveMakerVmEvent.Delay(
                                duration,
                                stopEventProcessing,
                                command.index
                            )
                        }
                    }
                    else -> {
                        // The host must apply the command before the VM advances.
                        programCounter += 1
                        waitingForHost = true
                        return LiveMakerVmEvent.HostCommandRequired(command)
                    }
                }
            } catch (error: VmEvaluationException) {
                waitingForHost = true
                return LiveMakerVmEvent.UnsupportedExpression(
                    command.index,
                    error.message ?: "Unsupported expression."
                )
            }
        }

        return LiveMakerVmEvent.Running
    }

    /**
     * Resume after a blocking event has been handled.
     * For UnsupportedExpression, retry the current command or pass a command index to skip it.
     */
    fun resume(nextCommandIndex: Int? = null) {
        if (nextCommandIndex != null) {
            require(nextCommandIndex in 0..script.commands.size) {
                "Resume command index is outside the current script."
            }
            programCounter = nextCommandIndex
        }
        waitingForHost = false
    }

    /**
     * Switches to a page resolved by the host after ScriptTransfer.
     * For a Call, the current script and return position are pushed onto the call stack.
     */
    fun loadTransferredScript(
        newScript: LiveMakerLsb,
        entryCommandIndex: Int = 0,
        isCall: Boolean,
        returnVariable: String? = null,
        scriptIdentifier: String? = null
    ) {
        require(waitingForHost) { "The VM is not waiting for a script transfer." }
        require(newScript.isComplete) { "Cannot execute a partially parsed LiveMaker script." }
        require(entryCommandIndex in 0..newScript.commands.size) {
            "Script entry command index is outside the command list."
        }
        if (isCall) {
            callStack.addLast(
                CallFrame(
                    script = script,
                    returnCommandIndex = programCounter,
                    resultVariable = returnVariable,
                    scriptIdentifier = this.scriptIdentifier
                )
            )
        }
        script = newScript
        this.scriptIdentifier = scriptIdentifier
        programCounter = entryCommandIndex
        completed = false
        waitingForHost = false
    }

    /** Returns from a called page and optionally writes its result into the caller variable. */
    fun returnFromCall(returnValue: Any? = null): Boolean {
        if (callStack.isEmpty()) return false
        val frame = callStack.removeLast()
        script = frame.script
        scriptIdentifier = frame.scriptIdentifier
        programCounter = frame.returnCommandIndex
        frame.resultVariable?.let { variables[it] = returnValue }
        completed = false
        waitingForHost = false
        return true
    }

    /** Accept a host-supplied game or LiveNovel system variable value. */
    fun setVariable(name: String, value: Any?) {
        assignVariable(name, value)
    }

    /** Update device values that are normally provided by the Android player host. */
    fun updateScreenSize(widthPx: Int, heightPx: Int) {
        systemEnvironment.screenWidthPx = widthPx.coerceAtLeast(0)
        systemEnvironment.screenHeightPx = heightPx.coerceAtLeast(0)
    }

    fun updatePointerState(
        x: Int,
        y: Int,
        leftClick: Boolean = false,
        rightClick: Boolean = false,
        leftPush: Boolean = false,
        rightPush: Boolean = false
    ) = systemEnvironment.updatePointer(x, y, leftClick, rightClick, leftPush, rightPush)

    fun updateKeyState(
        keyCode: Int,
        clicked: Boolean,
        pressed: Boolean,
        repeated: Boolean = false
    ) = systemEnvironment.updateKeyState(keyCode, clicked, pressed, repeated)

    fun setFileExistsResolver(resolver: (String) -> Boolean) {
        systemEnvironment.fileExistsResolver = resolver
    }

    /** Evaluates an expression in the current caller state for a host-side Call adapter. */
    fun evaluateExpression(expression: LiveMakerExpression): Any? = evaluate(expression)


    /**
     * Find the next Elseif/Else at the same indentation level.
     * Nested conditional markers have a greater indent and are ignored.
     */
    private fun nextConditionalBranch(indent: Long, fromIndex: Int): Int? {
        var index = fromIndex
        while (index < script.commands.size) {
            val candidate = script.commands[index]
            if (candidate.indent < indent) return null
            if (candidate.indent == indent) {
                return index.takeIf { candidate.typeId == 1 || candidate.typeId == 2 }
            }
            index += 1
        }
        return null
    }

    private fun enterNextConditionalBranch(indent: Long, fromIndex: Int) {
        var searchFrom = fromIndex
        while (true) {
            val branchIndex = nextConditionalBranch(indent, searchFrom)
            if (branchIndex == null) {
                programCounter = firstCommandAtOrOutsideIndent(indent, searchFrom)
                return
            }

            val branch = script.commands[branchIndex]
            when (branch.typeId) {
                1 -> {
                    val condition = branch.expressions.firstOrNull()
                        ?: throw VmEvaluationException("Elseif command is missing its condition.")
                    if (truthy(evaluate(condition))) {
                        programCounter = branchIndex + 1
                        return
                    }
                    searchFrom = branchIndex + 1
                }
                2 -> {
                    programCounter = branchIndex + 1
                    return
                }
                else -> error("Unexpected conditional branch type.")
            }
        }
    }

    private fun skipConditionalRemainder(branchIndex: Int, indent: Long): Int =
        firstCommandAtOrOutsideIndent(indent, branchIndex + 1)

    private fun firstCommandAtOrOutsideIndent(indent: Long, fromIndex: Int): Int {
        var index = fromIndex
        while (index < script.commands.size && script.commands[index].indent > indent) {
            index += 1
        }
        return index
    }

    private fun checkedCommandTarget(target: Long, command: LiveMakerCommand): Int {
        if (target < 0L || target > script.commands.size.toLong()) {
            throw VmEvaluationException(
                "Command " + command.index + " contains out-of-range control-flow target " + target + "."
            )
        }
        return target.toInt()
    }

    private fun evaluateRequired(
        command: LiveMakerCommand,
        index: Int,
        resultTypeHint: Int? = null
    ): Any? {
        val expression = command.expressions.getOrNull(index)
            ?: throw VmEvaluationException("Command is missing expression " + index + ".")
        return evaluate(expression, resultTypeHint)
    }

    private fun evaluate(
        expression: LiveMakerExpression,
        resultTypeHint: Int? = null
    ): Any? {
        val local = HashMap<String, Any?>()

        fun resolve(parameter: LiveMakerParameter): Any? =
            when (val value = parameter.value) {
                is LiveMakerParameterValue.Variable ->
                    if (local.containsKey(value.name)) local[value.name]
                    else if (value.name.startsWith("@")) readSystemVariable(value.name)
                    else variables[value.name]
                is LiveMakerParameterValue.Integer -> value.value
                is LiveMakerParameterValue.Flag -> value.value != 0
                is LiveMakerParameterValue.StringValue -> value.value
                is LiveMakerParameterValue.Float80 ->
                    throw VmEvaluationException("80-bit floating-point operands are parsed but not evaluated yet.")
            }

        for ((operationIndex, operation) in expression.operations.withIndex()) {
            val operands = operation.operands.map(::resolve)
            // Intermediate "____" results inherit the declared type of the next real
            // destination variable, matching pylivemaker's LiveParser Plus behavior.
            val destinationType = variableTypes[operation.name]
                ?: expression.operations.drop(operationIndex + 1)
                    .firstOrNull { it.typeId == OP_TO && variableTypes.containsKey(it.name) }
                    ?.let { variableTypes[it.name] }
                ?: resultTypeHint
            val result = evaluateOperation(operation, operands, destinationType)
            local[operation.name] = result
            if (operation.typeId == OP_TO && operation.name.isNotEmpty() &&
                !operation.name.startsWith("____")
            ) {
                assignVariable(operation.name, result)
            }
        }
        return expression.operations.lastOrNull()?.let { local[it.name] }
    }

    private fun evaluateOperation(
        operation: LiveMakerOperation,
        values: List<Any?>,
        resultTypeHint: Int? = null
    ): Any? {
        fun pair(): Pair<Any?, Any?> {
            if (values.size != 2) {
                throw VmEvaluationException("Operator " + operation.typeId + " expects two operands.")
            }
            return values[0] to values[1]
        }

        return when (operation.typeId) {
            0 -> values.lastOrNull()
            1 -> values.lastOrNull()
            2 -> {
                val (a, b) = pair()
                val numericDestination =
                    resultTypeHint != null && resultTypeHint in NUMERIC_VARIABLE_TYPES
                val stringDestination = resultTypeHint == VARIABLE_TYPE_STRING
                if (numericDestination ||
                    (!stringDestination && values.all(::isNumericCoercible))
                ) {
                    numericBinary(a, b) { x, y -> x + y }
                } else {
                    (a?.toString() ?: "") + (b?.toString() ?: "")
                }
            }
            3 -> numericPair(pair()) { a, b -> a - b }
            4 -> numericPair(pair()) { a, b -> a * b }
            5 -> numericPair(pair()) { a, b ->
                if (b == 0.0) throw VmEvaluationException("Division by zero.")
                a / b
            }
            6 -> {
                val (a, b) = pair()
                val left = number(a) ?: throw VmEvaluationException("Modulo operand is not numeric.")
                val right = number(b) ?: throw VmEvaluationException("Modulo operand is not numeric.")
                if (right == 0.0) throw VmEvaluationException("Modulo by zero.")
                left % right
            }
            7 -> booleanOrBitwise(pair(), true)
            8 -> booleanOrBitwise(pair(), false)
            9 -> {
                val (a, b) = pair()
                (number(a) ?: throw VmEvaluationException("XOR operand is not numeric.")).toLong() xor
                    (number(b) ?: throw VmEvaluationException("XOR operand is not numeric.")).toLong()
            }
            10 -> evaluateArrayIndex(values)
            11 -> evaluatePureFunction(
                operation.functionId ?: throw VmEvaluationException("Function operator has no function id."),
                values,
                operation.operands
            )
            12 -> {
                val (a, b) = pair()
                compareNumbers(a, b) == 0
            }
            13 -> compare(pair()) { a, b -> compareNumbers(a, b) > 0 }
            14 -> compare(pair()) { a, b -> compareNumbers(a, b) < 0 }
            15 -> compare(pair()) { a, b -> compareNumbers(a, b) >= 0 }
            16 -> compare(pair()) { a, b -> compareNumbers(a, b) <= 0 }
            17, 18 -> {
                val (a, b) = pair()
                val left = number(a)?.toLong() ?: throw VmEvaluationException("Shift operand is not numeric.")
                val right = number(b)?.toInt() ?: throw VmEvaluationException("Shift count is not numeric.")
                if (operation.typeId == 17) left shl right else left shr right
            }
            19 -> values.joinToString("") { it?.toString() ?: "" }
            20 -> {
                val (a, b) = pair()
                compareNumbers(a, b) != 0
            }
            else -> throw VmEvaluationException("Expression operator " + operation.typeId + " is not supported.")
        }
    }


    private fun evaluateArrayIndex(values: List<Any?>): Any? {
        if (values.size < 2) {
            throw VmEvaluationException("DimTo expects a base value and at least one index.")
        }
        return values.drop(1).fold(values.first()) { current, indexValue ->
            val index = number(indexValue)?.toInt()
                ?: throw VmEvaluationException("DimTo index is not numeric.")
            when (current) {
                is List<*> -> current.getOrNull(index)
                    ?: throw VmEvaluationException("DimTo list index is out of range.")
                is Array<*> -> current.getOrNull(index)
                    ?: throw VmEvaluationException("DimTo array index is out of range.")
                is String -> current.getOrNull(index)?.toString()
                    ?: throw VmEvaluationException("DimTo string index is out of range.")
                else -> throw VmEvaluationException("DimTo base value is not indexable.")
            }
        }
    }

    /** Built-ins implemented against VM state or safe, explicitly bounded host adapters. */
    private fun evaluatePureFunction(
        functionId: Int,
        args: List<Any?>,
        sourceOperands: List<LiveMakerParameter>
    ): Any? {
        fun arg(index: Int): Any? {
            if (index !in args.indices) {
                throw VmEvaluationException("Function $functionId is missing argument $index.")
            }
            return args[index]
        }

        fun stringArg(index: Int): String =
            arg(index)?.toString() ?: ""

        fun numberArg(index: Int): Double =
            number(arg(index)) ?: stringArg(index).toDoubleOrNull()
                ?: throw VmEvaluationException("Function $functionId expects numeric argument $index.")

        fun requireCount(minimum: Int, maximum: Int = minimum) {
            if (args.size !in minimum..maximum) {
                throw VmEvaluationException(
                    "Function $functionId expects $minimum..$maximum arguments; got ${args.size}."
                )
            }
        }

        fun assignVariableArgument(index: Int, value: Any?) {
            val variable = sourceOperands.getOrNull(index)?.value as? LiveMakerParameterValue.Variable
                ?: throw VmEvaluationException(
                    "Function $functionId expects argument $index to be a variable reference."
                )
            assignVariable(variable.name, value)
        }

        fun dateTimeArgument(index: Int): LocalDateTime =
            dateTimeFromLiveMakerValue(numberArg(index))

        return when (functionId) {
            2 -> {
                requireCount(2)
                objectStore.getProperty(stringArg(0), numberArg(1).toInt())
            }
            3 -> {
                requireCount(3)
                objectStore.setProperty(stringArg(0), numberArg(1).toInt(), arg(2))
                null
            }
            0 -> {
                requireCount(1, 2)
                val value = numberArg(0).toLong().toString()
                val width = if (args.size == 2) numberArg(1).toInt().coerceIn(0, 64) else 0
                if (value.startsWith("-")) {
                    "-" + value.substring(1).padStart((width - 1).coerceAtLeast(0), '0')
                } else {
                    value.padStart(width, '0')
                }
            }
            1 -> {
                requireCount(1, 2)
                val value = numberArg(0).toLong()
                val hex = value.toString(16).uppercase()
                val width = if (args.size == 2) numberArg(1).toInt().coerceIn(0, 64) else 0
                hex.padStart(width, '0')
            }
            4 -> {
                requireCount(1, 2)
                val values = when (val value = arg(0)) {
                    is List<*> -> value
                    is Array<*> -> value.asList()
                    else -> throw VmEvaluationException("GetArraySize expects an array.")
                }
                if (values.any { it is Collection<*> || it is Array<*> }) {
                    throw VmEvaluationException("GetArraySize for multi-dimensional arrays is not implemented.")
                }
                if (args.size == 2 && numberArg(1).toInt() != 0) {
                    throw VmEvaluationException("GetArraySize currently supports only dimension 0.")
                }
                values.size
            }
            5 -> {
                requireCount(1)
                val value = arg(0) as? String
                    ?: throw VmEvaluationException("Length expects a string.")
                value.toByteArray(CP932).size
            }
            13 -> {
                requireCount(2)
                stringArg(0).compareTo(stringArg(1)).coerceIn(-1, 1)
            }
            14 -> {
                requireCount(2)
                stringArg(0).compareTo(stringArg(1), ignoreCase = true).coerceIn(-1, 1)
            }
            15 -> {
                requireCount(2)
                val index = stringArg(1).indexOf(stringArg(0))
                if (index < 0) 0 else index + 1
            }
            17 -> {
                requireCount(1)
                stringArg(0).trim()
            }
            20 -> {
                requireCount(1)
                when (val value = arg(0)) {
                    is Boolean -> !value
                    is Number -> value.toLong().inv()
                    else -> throw VmEvaluationException("Not expects a Boolean or integer.")
                }
            }
            37 -> {
                requireCount(1)
                numberArg(0).toLong()
            }
            38 -> {
                requireCount(1)
                numberArg(0)
            }
            39 -> { requireCount(1); sin(numberArg(0)) }
            40 -> { requireCount(1); cos(numberArg(0)) }
            41 -> { requireCount(1); tan(numberArg(0)) }
            42 -> { requireCount(1); asin(numberArg(0)) }
            43 -> { requireCount(1); acos(numberArg(0)) }
            44 -> { requireCount(1); atan(numberArg(0)) }
            45 -> { requireCount(2); atan2(numberArg(0), numberArg(1)) }
            46 -> { requireCount(2); hypot(numberArg(0), numberArg(1)) }
            48 -> {
                requireCount(1)
                val value = numberArg(0)
                if (value % 1.0 == 0.0) abs(value.toLong()) else abs(value)
            }
            49 -> { requireCount(1); abs(numberArg(0)) }
            50 -> {
                requireCount(1)
                variables.containsKey(stringArg(0))
            }
            73 -> { requireCount(1); stringArg(0).lowercase() }
            74 -> { requireCount(1); stringArg(0).uppercase() }
            82 -> {
                requireCount(2)
                val character = stringArg(0)
                if (character.length != 1) {
                    throw VmEvaluationException("StringOfChar expects a single character as its first argument.")
                }
                val count = numberArg(1).toInt().coerceIn(0, 65_536)
                character.repeat(count)
            }
            83 -> {
                requireCount(3, 5)
                val source = stringArg(0)
                val search = stringArg(1)
                val replacement = stringArg(2)
                val replaceAll = args.getOrNull(3)?.let(::truthy) ?: false
                val ignoreCase = args.getOrNull(4)?.let(::truthy) ?: false
                if (search.isEmpty()) {
                    source
                } else if (replaceAll) {
                    source.replace(search, replacement, ignoreCase)
                } else {
                    val index = source.indexOf(search, ignoreCase = ignoreCase)
                    if (index < 0) source else source.replaceRange(index, index + search.length, replacement)
                }
            }
            126 -> { requireCount(2); min(numberArg(0), numberArg(1)) }
            127 -> { requireCount(2); max(numberArg(0), numberArg(1)) }
            128 -> { requireCount(2); min(numberArg(0), numberArg(1)) }
            129 -> { requireCount(2); max(numberArg(0), numberArg(1)) }
            138 -> {
                requireCount(2)
                stringArg(0).toLongOrNull() ?: numberArg(1).toLong()
            }
            139 -> {
                requireCount(2)
                stringArg(0).toDoubleOrNull() ?: numberArg(1)
            }
            152 -> {
                requireCount(1)
                numberArg(0).toInt().toChar().toString()
            }
            153 -> {
                requireCount(1)
                stringArg(0).firstOrNull()?.code
                    ?: throw VmEvaluationException("Ord received an empty string.")
            }
            // LiveNovel date/time helpers use Delphi TDateTime serial values.
            51 -> {
                requireCount(3)
                val date = LocalDate.of(
                    numberArg(0).toInt(),
                    numberArg(1).toInt(),
                    numberArg(2).toInt()
                )
                ChronoUnit.DAYS.between(LIVEMAKER_DATE_EPOCH, date).toDouble()
            }
            52 -> {
                requireCount(3)
                val time = LocalTime.of(
                    numberArg(0).toInt(),
                    numberArg(1).toInt(),
                    numberArg(2).toInt()
                )
                time.toNanoOfDay().toDouble() / NANOS_PER_DAY.toDouble()
            }
            53 -> {
                requireCount(4)
                val date = dateTimeArgument(0).toLocalDate()
                assignVariableArgument(1, date.year)
                assignVariableArgument(2, date.monthValue)
                assignVariableArgument(3, date.dayOfMonth)
                null
            }
            54 -> {
                requireCount(4)
                val time = dateTimeArgument(0).toLocalTime()
                assignVariableArgument(1, time.hour)
                assignVariableArgument(2, time.minute)
                assignVariableArgument(3, time.second)
                null
            }
            55 -> { requireCount(1); dateTimeArgument(0).year }
            56 -> { requireCount(1); dateTimeArgument(0).monthValue }
            57 -> { requireCount(1); dateTimeArgument(0).dayOfMonth }
            58 -> { requireCount(1); dateTimeArgument(0).hour }
            59 -> { requireCount(1); dateTimeArgument(0).minute }
            60 -> { requireCount(1); dateTimeArgument(0).second }
            61 -> {
                requireCount(1)
                dateTimeArgument(0).dayOfWeek.value % 7 + 1
            }
            62 -> {
                requireCount(1)
                dateTimeArgument(0).dayOfWeek.getDisplayName(
                    java.time.format.TextStyle.FULL,
                    java.util.Locale.ENGLISH
                )
            }
            63 -> {
                requireCount(1)
                listOf("月", "火", "水", "木", "金", "土", "日")[
                    dateTimeArgument(0).dayOfWeek.value - 1
                ]
            }
            // Coordinate transforms are identity operations in the single-window player.
            33, 34, 35, 36 -> { requireCount(2); numberArg(1) }
            27 -> { requireCount(1); "" }
            47 -> { requireCount(2); -1 }
            65 -> {
                requireCount(5)
                if (numberArg(0).toInt() != 0) {
                    false
                } else {
                    assignVariableArgument(1, systemEnvironment.screenWidthPx)
                    assignVariableArgument(2, systemEnvironment.screenHeightPx)
                    assignVariableArgument(3, 32)
                    assignVariableArgument(4, 60)
                    true
                }
            }
            19 -> { requireCount(1); objectStore.contains(stringArg(0)) }
            69 -> {
                requireCount(1)
                val name = stringArg(0)
                objectStore.contains(name) &&
                    objectStore.getProperty(name, LiveMakerObjectStore.PR_PARENT).toString().isBlank()
            }
            71 -> { requireCount(1); systemEnvironment.fileExists(stringArg(0)) }
            154 -> {
                requireCount(1)
                val name = stringArg(0)
                objectStore.contains(name) &&
                    objectStore.getProperty(name, LiveMakerObjectStore.PR_PARENT).toString().isNotBlank()
            }
            // Cross-platform path helpers operate on LiveMaker's slash-separated paths.
            75 -> {
                requireCount(1)
                val path = stringArg(0)
                val slash = max(path.lastIndexOf('/'), path.lastIndexOf('\\'))
                if (slash < 0) "" else path.substring(0, slash + 1)
            }
            76 -> {
                requireCount(1)
                val path = stringArg(0)
                path.substring(max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1)
            }
            77 -> {
                requireCount(1)
                val path = stringArg(0)
                val nameStart = max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1
                val dot = path.lastIndexOf('.')
                if (dot < nameStart) "" else path.substring(dot)
            }
            78 -> {
                requireCount(2)
                val bytes = stringArg(0).toByteArray(CP932)
                val index = numberArg(1).toInt() - 1
                index in bytes.indices && bytes[index].toInt() == '\\'.code
            }
            79 -> {
                requireCount(1)
                val path = stringArg(0)
                if (path.endsWith('\\')) path else path + '\\'
            }
            80 -> {
                requireCount(2)
                val path = stringArg(0)
                val extension = stringArg(1)
                val slash = max(path.lastIndexOf('/'), path.lastIndexOf('\\'))
                val dot = path.lastIndexOf('.')
                val stem = if (dot > slash) path.substring(0, dot) else path
                if (extension.isEmpty()) stem
                else stem + if (extension.startsWith('.')) extension else ".$extension"
            }
            81 -> {
                requireCount(3)
                val delimiters = stringArg(0).toByteArray(CP932)
                val bytes = stringArg(1).toByteArray(CP932)
                val index = numberArg(2).toInt() - 1
                index in bytes.indices && delimiters.contains(bytes[index])
            }
            84 -> { requireCount(1); assignVariable("@Temp", arg(0)); null }
            85 -> { requireCount(1); toFullWidth(stringArg(0)) }
            86 -> { requireCount(1); java.text.Normalizer.normalize(stringArg(0), java.text.Normalizer.Form.NFKC) }
            // UI actions that are not available in this player use safe cancel/no-op values.
            72 -> {
                requireCount(2)
                val content = systemEnvironment.readTextFile(stringArg(1))
                if (content == null) {
                    assignVariableArgument(0, emptyList<String>())
                    false
                } else {
                    assignVariableArgument(0, content)
                    true
                }
            }
            135 -> { requireCount(1); false }
            137 -> { requireCount(0, 1); false }
            140 -> { requireCount(2); false }
            141 -> { requireCount(3); null }
            142 -> { requireCount(0); textHistoryHost.snapshot().entries.size }
            143 -> { requireCount(0); textHistoryHost.maxEntries }
            144 -> {
                requireCount(1)
                val requested = numberArg(0).toInt().coerceIn(0, MAX_HISTORY_ENTRIES)
                textHistoryHost.setMaxEntries(requested)
                null
            }
            145 -> { requireCount(2); -1 }
            146 -> { requireCount(2); false }
            147 -> { requireCount(3); null }
            148, 149, 150 -> {
                // Android file/directory pickers are intentionally treated as cancelled.
                requireCount(1, 2)
                assignVariableArgument(0, "")
                false
            }
            151 -> { requireCount(1, 3); false }
            155, 156, 157 -> null
            158 -> { requireCount(2); false }
            159 -> { requireCount(0); 0 }
            160 -> {
                requireCount(3)
                assignVariableArgument(1, "")
                assignVariableArgument(2, false)
                false
            }
            166 -> { requireCount(1); false }
            167 -> { requireCount(2); false }
            168 -> { requireCount(1); false }
            24 -> { requireCount(2); false }
            25 -> { requireCount(3); null }
            130 -> {
                requireCount(1)
                val name = (sourceOperands.getOrNull(0)?.value as? LiveMakerParameterValue.Variable)?.name
                    ?: stringArg(0)
                variableTypes[name] ?: 0
            }
            131 -> { requireCount(2); false }
            132 -> { requireCount(3); null }
            133 -> { requireCount(1, 2); null }
            134 -> { requireCount(1); emptyList<String>() }
            136 -> { requireCount(1); false }
            161 -> {
                requireCount(2, 3)
                val componentType = numberArg(0).toInt()
                val name = stringArg(1)
                val parent = args.getOrNull(2)?.toString().orEmpty()
                if (name.isBlank() || objectStore.contains(name)) {
                    false
                } else {
                    objectStore.create(
                        name,
                        componentType,
                        mapOf(LiveMakerObjectStore.PR_PARENT to parent)
                    )
                    true
                }
            }
            162 -> {
                requireCount(1)
                objectStore.remove(stringArg(0))
            }
            163 -> { requireCount(2); "" }
            169 -> {
                requireCount(3)
                objectStore.getProperty(stringArg(1), numberArg(2).toInt())
            }
            170 -> {
                requireCount(4)
                objectStore.setProperty(stringArg(1), numberArg(2).toInt(), arg(3))
                null
            }
            else -> throw VmEvaluationException(
                "LiveMaker function $functionId requires a host adapter or is not implemented."
            )
        }
    }

    private fun toFullWidth(value: String): String {
        val result = StringBuilder()
        value.codePoints().forEach { codePoint ->
            when {
                codePoint == 0x20 -> result.append('　')
                codePoint in 0x21..0x7E -> result.appendCodePoint(codePoint + 0xFEE0)
                codePoint in 0xFF61..0xFF9F -> result.append(
                    java.text.Normalizer.normalize(
                        String(Character.toChars(codePoint)),
                        java.text.Normalizer.Form.NFKC
                    )
                )
                else -> result.appendCodePoint(codePoint)
            }
        }
        return java.text.Normalizer.normalize(result.toString(), java.text.Normalizer.Form.NFC)
    }

    private fun dateTimeFromLiveMakerValue(value: Double): LocalDateTime {
        if (!value.isFinite() || kotlin.math.abs(value) > 3_000_000.0) {
            throw VmEvaluationException("Invalid LiveMaker date/time value.")
        }
        val millis = (value * MILLIS_PER_DAY).toLong()
        val wholeDays = Math.floorDiv(millis, MILLIS_PER_DAY_LONG)
        val dayRemainderMillis = Math.floorMod(millis, MILLIS_PER_DAY_LONG)
        return LIVEMAKER_DATE_EPOCH.atStartOfDay()
            .plusDays(wholeDays)
            .plusNanos(dayRemainderMillis * NANOS_PER_MILLISECOND)
    }

    private fun readSystemVariable(name: String): Any? {
        if (name.equals("@HistoryCount", ignoreCase = true)) {
            return textHistoryHost.snapshot().entries.size
        }
        if (name.equals("@HistoryMaxCount", ignoreCase = true)) {
            return textHistoryHost.maxEntries
        }
        return systemEnvironment.read(
            name = name,
            historyCount = textHistoryHost.snapshot().entries.size,
            historyMaxCount = textHistoryHost.maxEntries,
            stackCount = callStack.size
        )
    }

    private fun systemVariableValue(name: String): Any? = readSystemVariable(name)

    private fun assignVariable(name: String, value: Any?) {
        if (!name.startsWith("@")) {
            variables[name] = value
            return
        }
        if (name.equals("@HistoryMaxCount", ignoreCase = true)) {
            val maxEntries = (number(value) ?: 0.0).toInt().coerceIn(0, MAX_HISTORY_ENTRIES)
            textHistoryHost.setMaxEntries(maxEntries)
            return
        }
        if (!systemEnvironment.write(name, value)) {
            throw VmEvaluationException("LiveNovel system variable $name is read-only or unsupported.")
        }
    }

    private fun numericBinary(a: Any?, b: Any?, block: (Double, Double) -> Double): Any {
        val left = number(a) ?: throw VmEvaluationException("Numeric operand expected.")
        val right = number(b) ?: throw VmEvaluationException("Numeric operand expected.")
        val result = block(left, right)
        return if (result.isFinite() && result % 1.0 == 0.0 &&
            result >= Long.MIN_VALUE.toDouble() && result <= Long.MAX_VALUE.toDouble()
        ) result.toLong() else result
    }

    private fun numericPair(pair: Pair<Any?, Any?>, block: (Double, Double) -> Double): Any =
        numericBinary(pair.first, pair.second, block)

    private fun booleanOrBitwise(pair: Pair<Any?, Any?>, isOr: Boolean): Any {
        val a = pair.first
        val b = pair.second
        if (a is Boolean && b is Boolean) return if (isOr) a || b else a && b
        val left = number(a)?.toLong() ?: throw VmEvaluationException("Logical operand is unsupported.")
        val right = number(b)?.toLong() ?: throw VmEvaluationException("Logical operand is unsupported.")
        return if (isOr) left or right else left and right
    }

    private fun compare(
        pair: Pair<Any?, Any?>,
        comparison: (Any?, Any?) -> Boolean
    ): Boolean = comparison(pair.first, pair.second)

    private fun compareNumbers(a: Any?, b: Any?): Int {
        val left = number(a) ?: throw VmEvaluationException("Comparison operand is not numeric.")
        val right = number(b) ?: throw VmEvaluationException("Comparison operand is not numeric.")
        return left.compareTo(right)
    }

    private fun truthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is Number -> value.toDouble() != 0.0
        is String -> {
            val normalized = value.trim()
            normalized.toDoubleOrNull()?.let { it != 0.0 } ?: normalized.isNotEmpty()
        }
        else -> false
    }

    /**
     * Delphi/LiveMaker Variant values are often returned as strings. In numeric
     * expression contexts an empty, missing, or non-numeric string is equivalent
     * to zero; numeric strings are parsed rather than rejected.
     */
    private fun number(value: Any?): Double? = when (value) {
        null -> 0.0
        is Number -> value.toDouble()
        is Boolean -> if (value) 1.0 else 0.0
        is String -> value.trim().toDoubleOrNull() ?: 0.0
        else -> null
    }

    private fun isNumericCoercible(value: Any?): Boolean = when (value) {
        null, is Number, is Boolean -> true
        is String -> {
            val normalized = value.trim()
            normalized.isEmpty() || normalized.toDoubleOrNull() != null
        }
        else -> false
    }

    private fun unsupported(command: LiveMakerCommand, reason: String): LiveMakerVmEvent.UnsupportedExpression {
        waitingForHost = true
        return LiveMakerVmEvent.UnsupportedExpression(command.index, reason)
    }

    private class VmEvaluationException(message: String) : RuntimeException(message)

    companion object {
        private const val OP_TO = 1
        private const val MAX_HISTORY_ENTRIES = 100_000
        private const val MILLIS_PER_DAY = 86_400_000.0
        private const val MILLIS_PER_DAY_LONG = 86_400_000L
        private const val NANOS_PER_DAY = 86_400_000_000_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private val LIVEMAKER_DATE_EPOCH = LocalDate.of(1899, 12, 30)
        private const val VARIABLE_TYPE_INT = 1
        private const val VARIABLE_TYPE_FLOAT = 2
        private const val VARIABLE_TYPE_FLAG = 3
        private const val VARIABLE_TYPE_STRING = 4
        private val NUMERIC_VARIABLE_TYPES = setOf(
            VARIABLE_TYPE_INT,
            VARIABLE_TYPE_FLOAT,
            VARIABLE_TYPE_FLAG
        )
        private val CP932: Charset = Charset.forName("windows-31j")
    }
}
