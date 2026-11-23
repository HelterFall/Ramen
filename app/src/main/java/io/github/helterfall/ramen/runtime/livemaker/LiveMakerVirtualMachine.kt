package io.github.helterfall.ramen.runtime.livemaker

import io.github.helterfall.ramen.inspection.LiveMakerCommand
import io.github.helterfall.ramen.inspection.LiveMakerExpression
import io.github.helterfall.ramen.inspection.LiveMakerLsb
import io.github.helterfall.ramen.inspection.LiveMakerOperation
import io.github.helterfall.ramen.inspection.LiveMakerParameter
import io.github.helterfall.ramen.inspection.LiveMakerParameterValue

sealed interface LiveMakerVmEvent {
    data class TextBlock(
        val tpWordBytes: ByteArray,
        val shouldWait: Boolean,
        val commandIndex: Int
    ) : LiveMakerVmEvent

    data class Delay(val milliseconds: Long, val commandIndex: Int) : LiveMakerVmEvent

    data class ScriptTransfer(
        val page: String,
        val labelIndex: Long,
        val isCall: Boolean,
        val returnVariable: String?,
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
    private val maxInstructionsPerStep: Int = 10_000
) {
    private data class CallFrame(
        val script: LiveMakerLsb,
        val returnCommandIndex: Int,
        val resultVariable: String?
    )

    private val variables = linkedMapOf<String, Any?>()
    private val callStack = ArrayDeque<CallFrame>()
    private var programCounter = 0
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

    /**
     * Runs until a host event, the end of the script, or the bounded instruction budget.
     * Call resume() after the host has handled an event that blocks execution.
     */
    fun runUntilEvent(): LiveMakerVmEvent {
        if (completed) return LiveMakerVmEvent.Completed
        if (waitingForHost) {
            return LiveMakerVmEvent.UnsupportedExpression(
                programCounter,
                "The VM is waiting for the host to call resume()."
            )
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
                    3, 27 -> programCounter += 1
                    45 -> {
                        completed = true
                        return LiveMakerVmEvent.Completed
                    }
                    14 -> {
                        evaluateRequired(command, 0)
                        programCounter += 1
                    }
                    15 -> {
                        val variableName = command.strings.firstOrNull()
                            ?: return unsupported(command, "VarNew is missing its variable name.")
                        val initial = evaluateRequired(command, 0)
                        variables[variableName] = initial
                        programCounter += 1
                    }
                    16 -> {
                        val variableName = command.strings.firstOrNull()
                            ?: return unsupported(command, "VarDel is missing its variable name.")
                        variables.remove(variableName)
                        programCounter += 1
                    }
                    20 -> {
                        val rawText = command.binaryBlobs.firstOrNull()
                            ?: return unsupported(command, "TextIns is missing its TpWord block.")
                        val shouldWait = command.expressions.getOrNull(2)
                            ?.let { truthy(evaluate(it)) } ?: true
                        programCounter += 1
                        waitingForHost = shouldWait
                        return LiveMakerVmEvent.TextBlock(
                            tpWordBytes = rawText.copyOf(),
                            shouldWait = shouldWait,
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
                                page, label, false, null, command.index
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
                                page, label, true, resultName, command.index
                            )
                        }
                    }
                    6 -> {
                        val shouldExit = truthy(evaluateRequired(command, 0))
                        programCounter += 1
                        if (shouldExit) {
                            completed = true
                            return LiveMakerVmEvent.Completed
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
                        programCounter += 1
                        if (shouldWait && duration > 0L) {
                            waitingForHost = true
                            return LiveMakerVmEvent.Delay(duration, command.index)
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
                return LiveMakerVmEvent.UnsupportedExpression(command.index, error.message ?: "Unsupported expression.")
            }
        }

        return LiveMakerVmEvent.Running
    }

    /** Resume after a TextBlock, Delay, or host-required command has been handled. */
    fun resume() {
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
        returnVariable: String? = null
    ) {
        require(waitingForHost) { "The VM is not waiting for a script transfer." }
        require(newScript.isComplete) { "Cannot execute a partially parsed LiveMaker script." }
        require(entryCommandIndex in 0..newScript.commands.size) {
            "Script entry command index is outside the command list."
        }
        if (isCall) {
            callStack.addLast(CallFrame(script, programCounter, returnVariable))
        }
        script = newScript
        programCounter = entryCommandIndex
        completed = false
        waitingForHost = false
    }

    /** Returns from a called page and optionally writes its result into the caller variable. */
    fun returnFromCall(returnValue: Any? = null): Boolean {
        if (callStack.isEmpty()) return false
        val frame = callStack.removeLast()
        script = frame.script
        programCounter = frame.returnCommandIndex
        frame.resultVariable?.let { variables[it] = returnValue }
        completed = false
        waitingForHost = false
        return true
    }

    /** Accept a host-supplied game variable value. */
    fun setVariable(name: String, value: Any?) {
        variables[name] = value
    }

    private fun evaluateRequired(command: LiveMakerCommand, index: Int): Any? {
        val expression = command.expressions.getOrNull(index)
            ?: throw VmEvaluationException("Command is missing expression " + index + ".")
        return evaluate(expression)
    }

    private fun evaluate(expression: LiveMakerExpression): Any? {
        val local = HashMap<String, Any?>()

        fun resolve(parameter: LiveMakerParameter): Any? =
            when (val value = parameter.value) {
                is LiveMakerParameterValue.Variable ->
                    if (local.containsKey(value.name)) local[value.name] else variables[value.name]
                is LiveMakerParameterValue.Integer -> value.value
                is LiveMakerParameterValue.Flag -> value.value != 0
                is LiveMakerParameterValue.StringValue -> value.value
                is LiveMakerParameterValue.Float80 ->
                    throw VmEvaluationException("80-bit floating-point operands are parsed but not evaluated yet.")
            }

        for (operation in expression.operations) {
            val operands = operation.operands.map(::resolve)
            val result = evaluateOperation(operation, operands)
            local[operation.name] = result
            if (operation.typeId == OP_TO && operation.name.isNotEmpty() &&
                !operation.name.startsWith("____")
            ) {
                variables[operation.name] = result
            }
        }
        return expression.operations.lastOrNull()?.let { local[it.name] }
    }

    private fun evaluateOperation(operation: LiveMakerOperation, values: List<Any?>): Any? {
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
                if (a is String || b is String) {
                    throw VmEvaluationException("Plus string coercion is not implemented.")
                }
                numericBinary(a, b) { x, y -> x + y }
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
            12 -> compare(pair()) { a, b -> a == b }
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
            20 -> compare(pair()) { a, b -> a != b }
            else -> throw VmEvaluationException("Expression operator " + operation.typeId + " is not supported.")
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
        is String -> value.isNotEmpty()
        else -> false
    }

    private fun number(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        is Boolean -> if (value) 1.0 else 0.0
        else -> null
    }

    private fun unsupported(command: LiveMakerCommand, reason: String) =
        LiveMakerVmEvent.UnsupportedExpression(command.index, reason)

    private class VmEvaluationException(message: String) : RuntimeException(message)

    companion object {
        private const val OP_TO = 1
    }
}
