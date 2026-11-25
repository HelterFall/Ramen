package io.github.helterfall.ramen.runtime.hsp3

import io.github.helterfall.ramen.parser.Hsp3Program
import io.github.helterfall.ramen.parser.Hsp3Token
import java.io.IOException
import java.util.ArrayDeque

/**
 * OpenHSP CS expression reader and command-argument decoder.
 *
 * CS values are 16-bit-word tokens (or 32-bit payload tokens). EXFLG_1 marks
 * command heads; EXFLG_2 marks subsequent argument boundaries; EXFLG_0 is the
 * compiler's single-literal fast-path flag; EXFLG_3 selects a 32-bit value.
 * Expression operators are postfix and use CALCCODE_* values 0..15.
 */
class Hsp3BytecodeDecoder : Hsp3InstructionDecoder {
    private data class PendingInstruction(
        val codeOffset: Int,
        val create: (Map<Int, Int>, Int) -> Hsp3Instruction
    )

    override fun decode(program: Hsp3Program): List<Hsp3Instruction> {
        val tokens = program.tokens().toList()
        if (tokens.isEmpty()) throw IOException("The HSP3 Code Segment is empty.")

        val commandIndexes = tokens.indices.filter { index ->
            val token = tokens[index]
            token.extensionFlags and EXFLG_1 != 0 && token.type in COMMAND_TYPES
        }
        if (commandIndexes.isEmpty()) {
            throw IOException("No HSP3 command heads were found in the Code Segment.")
        }

        val pending = ArrayList<PendingInstruction>(commandIndexes.size)
        commandIndexes.forEachIndexed { commandOrdinal, tokenIndex ->
            val command = tokens[tokenIndex]
            val nextTokenIndex = commandIndexes.getOrNull(commandOrdinal + 1) ?: tokens.size
            val rawArguments = readArgumentGroups(tokens, tokenIndex + 1, nextTokenIndex)
            pending += if (command.type == TYPE_VAR) {
                decodeAssignment(program, command, rawArguments)
            } else {
                decodeCommand(program, command, rawArguments.map { decodeExpression(it, program) })
            }
        }

        val codeToInstruction = pending.mapIndexed { index, item ->
            item.codeOffset to index
        }.toMap()
        val instructionCount = pending.size
        return pending.map { item ->
            item.create(codeToInstruction, instructionCount)
        }
    }

    private fun readArgumentGroups(
        tokens: List<Hsp3Token>,
        startIndex: Int,
        endIndex: Int
    ): List<List<Hsp3Token>> {
        if (startIndex >= endIndex) return emptyList()
        val groups = ArrayList<MutableList<Hsp3Token>>()
        var current = mutableListOf<Hsp3Token>()
        for (index in startIndex until endIndex) {
            val token = tokens[index]
            if (token.extensionFlags and EXFLG_2 != 0 && current.isNotEmpty()) {
                groups += current
                current = mutableListOf()
            }
            current += token.copy(extensionFlags = token.extensionFlags and EXFLG_2.inv())
        }
        if (current.isNotEmpty()) groups += current
        return groups
    }

    private fun decodeExpression(
        tokens: List<Hsp3Token>,
        program: Hsp3Program
    ): Hsp3Expression? {
        if (tokens.isEmpty()) return null
        if (tokens.size == 1 && tokens[0].type == TYPE_MARK && tokens[0].value == OMITTED_ARGUMENT_MARK) {
            return null
        }

        val stack = ArrayDeque<Hsp3ExpressionNode>()
        tokens.forEach { token ->
            when (token.type) {
                TYPE_MARK -> {
                    val operator = token.value.toInt()
                    if (operator == OMITTED_ARGUMENT_MARK) {
                        throw IOException("An omitted HSP parameter was mixed into an expression.")
                    }
                    if (operator !in CALCCODE_ADD..CALCCODE_LR) {
                        throw IOException("Unsupported HSP expression marker $operator.")
                    }
                    if (stack.size < 2) {
                        throw IOException("The HSP postfix expression has too few operands.")
                    }
                    val right = stack.removeLast()
                    val left = stack.removeLast()
                    stack.addLast(Hsp3ExpressionNode.Binary(operator, left, right))
                }
                TYPE_VAR -> {
                    val variable = checkedIndex(token.value, program.header.maxValueCount, "variable")
                    stack.addLast(Hsp3ExpressionNode.Variable(variable))
                }
                TYPE_STRING -> {
                    stack.addLast(Hsp3ExpressionNode.Literal(
                        Hsp3RuntimeValue.StringValue(program.stringAt(token.value))
                    ))
                }
                TYPE_DNUM -> {
                    stack.addLast(Hsp3ExpressionNode.Literal(
                        Hsp3RuntimeValue.DoubleValue(program.doubleAt(token.value))
                    ))
                }
                TYPE_INUM -> {
                    stack.addLast(Hsp3ExpressionNode.Literal(
                        Hsp3RuntimeValue.IntegerValue(token.value.toInt())
                    ))
                }
                TYPE_LABEL -> {
                    val target = program.labelCodeOffset(checkedIndex(
                        token.value,
                        (program.objectTempSegment.sizeBytes / 4L),
                        "label"
                    ))
                    stack.addLast(Hsp3ExpressionNode.Literal(
                        Hsp3RuntimeValue.LabelValue(target.toInt())
                    ))
                }
                TYPE_SYSVAR -> {
                    stack.addLast(Hsp3ExpressionNode.SystemVariable(token.value.toInt()))
                }
                else -> throw IOException(
                    "Unsupported HSP expression token type ${token.type} at CS+${token.codeOffset}."
                )
            }
        }
        if (stack.size != 1) {
            throw IOException("The HSP postfix expression leaves ${stack.size} values on its stack.")
        }
        return Hsp3Expression(stack.removeLast())
    }

    private fun decodeCommand(
        program: Hsp3Program,
        command: Hsp3Token,
        args: List<Hsp3Expression?>
    ): PendingInstruction {
        val pc = command.codeOffset
        val commandId = command.value.toInt()
        return when (command.type) {
            TYPE_EXTCMD -> PendingInstruction(pc) { _, _ ->
                Hsp3Instruction.NativeCommand(TYPE_EXTCMD, commandId, args)
            }
            TYPE_INTCMD -> {
                if (commandId == INTCMD_GETKEY) {
                    val destination = requiredVariable(args.getOrNull(0), "getkey destination")
                    val keyCode = args.getOrNull(1)
                        ?: constantExpression(Hsp3RuntimeValue.IntegerValue(1))
                    PendingInstruction(pc) { _, _ ->
                        Hsp3Instruction.GetKey(destination, keyCode)
                    }
                } else {
                    PendingInstruction(pc) { _, _ ->
                        Hsp3Instruction.Unsupported(TYPE_INTCMD, commandId)
                    }
                }
            }
            TYPE_PROGCMD -> decodeProgramCommand(program, command, args)
            TYPE_CMPCMD -> decodeConditionalCommand(program, command, args)
            else -> PendingInstruction(pc) { _, _ ->
                Hsp3Instruction.Unsupported(command.type, commandId)
            }
        }
    }

    private fun decodeProgramCommand(
        program: Hsp3Program,
        command: Hsp3Token,
        args: List<Hsp3Expression?>
    ): PendingInstruction {
        val id = command.value.toInt()
        val pc = command.codeOffset
        return when (id) {
            PROGCMD_GOTO, PROGCMD_GOSUB -> {
                val target = requiredLabelOffset(program, args.firstOrNull(), "goto/gosub")
                PendingInstruction(pc) { addressMap, count ->
                    val index = resolveTarget(target, addressMap, count, program.codeSegment.sizeBytes.toInt())
                    if (id == PROGCMD_GOTO) Hsp3Instruction.Jump(index)
                    else Hsp3Instruction.Gosub(index)
                }
            }
            PROGCMD_RETURN -> PendingInstruction(pc) { _, _ -> Hsp3Instruction.Return }
            PROGCMD_BREAK -> PendingInstruction(pc) { _, _ -> Hsp3Instruction.Break }
            PROGCMD_REPEAT -> {
                val target = requiredLabelOffset(program, args.firstOrNull(), "repeat")
                val countExpression = args.getOrNull(1)
                    ?: constantExpression(Hsp3RuntimeValue.IntegerValue(-1))
                val initialExpression = args.getOrNull(2)
                    ?: constantExpression(Hsp3RuntimeValue.IntegerValue(0))
                PendingInstruction(pc) { addressMap, count ->
                    Hsp3Instruction.Repeat(
                        count = countExpression.constantIntOrNull() ?: -1,
                        exitInstruction = resolveTarget(target, addressMap, count, program.codeSegment.sizeBytes.toInt()),
                        initialValue = initialExpression.constantIntOrNull() ?: 0
                    )
                }.also {
                    if (countExpression.constantIntOrNull() == null ||
                        initialExpression.constantIntOrNull() == null
                    ) {
                        throw IOException("Dynamic repeat expressions are not yet supported by this VM profile.")
                    }
                }
            }
            PROGCMD_LOOP -> PendingInstruction(pc) { _, _ -> Hsp3Instruction.Loop }
            PROGCMD_CONTINUE -> PendingInstruction(pc) { _, _ -> Hsp3Instruction.Continue }
            PROGCMD_WAIT -> {
                val wait = args.firstOrNull()?.constantIntOrNull() ?: 100
                PendingInstruction(pc) { _, _ ->
                    Hsp3Instruction.WaitFor((wait.coerceAtLeast(0) * 10L).coerceAtMost(MAX_WAIT_MS))
                }
            }
            PROGCMD_AWAIT -> {
                val wait = args.firstOrNull()?.constantIntOrNull() ?: 0
                PendingInstruction(pc) { _, _ ->
                    Hsp3Instruction.WaitFor(wait.coerceAtLeast(0).toLong().coerceAtMost(MAX_WAIT_MS))
                }
            }
            PROGCMD_END, PROGCMD_STOP -> PendingInstruction(pc) { _, _ -> Hsp3Instruction.End }
            else -> PendingInstruction(pc) { _, _ -> Hsp3Instruction.Unsupported(TYPE_PROGCMD, id) }
        }
    }

    private fun decodeConditionalCommand(
        program: Hsp3Program,
        command: Hsp3Token,
        args: List<Hsp3Expression?>
    ): PendingInstruction {
        if (command.encodedSizeBytes != 4) {
            throw IOException("An HSP if/else command must use a 16-bit command token.")
        }
        val rawOffsetLocation = command.codeOffset + command.encodedSizeBytes
        val skipWords = readUInt16(program.codeSegment.bytes, rawOffsetLocation)
        val branchTarget = rawOffsetLocation.toLong() + 2L + skipWords.toLong() * 2L
        if (branchTarget < 0L || branchTarget > program.codeSegment.sizeBytes.toLong()) {
            throw IOException("An HSP if/else branch target lies outside CS.")
        }
        val id = command.value.toInt()
        val pc = command.codeOffset
        if (id == CMP_CMD_IF) {
            val condition = args.firstOrNull()
                ?: throw IOException("The HSP if command has no condition expression.")
            return PendingInstruction(pc) { addressMap, count ->
                Hsp3Instruction.JumpIfFalse(
                    condition,
                    resolveTarget(branchTarget.toInt(), addressMap, count, program.codeSegment.sizeBytes)
                )
            }
        }
        return PendingInstruction(pc) { addressMap, count ->
            Hsp3Instruction.Jump(resolveTarget(branchTarget.toInt(), addressMap, count, program.codeSegment.sizeBytes))
        }
    }

    private fun decodeAssignment(
        program: Hsp3Program,
        command: Hsp3Token,
        argumentGroups: List<List<Hsp3Token>>
    ): PendingInstruction {
        val variable = checkedIndex(command.value, program.header.maxValueCount, "assignment variable")
        val firstGroup = argumentGroups.firstOrNull().orEmpty()
        val operatorToken = firstGroup.firstOrNull()
            ?: throw IOException("An HSP variable assignment is missing its operator token.")
        if (operatorToken.type != TYPE_MARK) {
            throw IOException("An HSP variable assignment is missing its TYPE_MARK operator.")
        }
        val operator = operatorToken.value.toInt()
        if (operator !in CALCCODE_ADD..CALCCODE_LR) {
            throw IOException("Unsupported HSP assignment operator $operator.")
        }
        val expressionTokens = firstGroup.drop(1)
        val expression = if (expressionTokens.isEmpty()) {
            constantExpression(Hsp3RuntimeValue.IntegerValue(1))
        } else {
            decodeExpression(expressionTokens, program)
                ?: constantExpression(Hsp3RuntimeValue.IntegerValue(1))
        }
        if (argumentGroups.size > 1) {
            throw IOException("An HSP assignment contains an unexpected extra argument.")
        }
        return PendingInstruction(command.codeOffset) { _, _ ->
            Hsp3Instruction.Assign(variable, operator, expression)
        }
    }

    private fun requiredVariable(expression: Hsp3Expression?, label: String): Int =
        expression?.singleVariableReference()
            ?: throw IOException("The HSP $label must be a scalar variable.")

    private fun requiredLabelOffset(
        program: Hsp3Program,
        expression: Hsp3Expression?,
        label: String
    ): Int {
        val target = expression?.constantValueOrNull() as? Hsp3RuntimeValue.LabelValue
            ?: throw IOException("The HSP $label command requires an OT label operand.")
        if (target.codeOffsetBytes !in 0 until program.codeSegment.sizeBytes) {
            throw IOException("The HSP $label target is outside CS.")
        }
        return target.codeOffsetBytes
    }

    private fun resolveTarget(
        targetCodeOffset: Int,
        addressMap: Map<Int, Int>,
        instructionCount: Int,
        codeSizeBytes: Int
    ): Int {
        if (targetCodeOffset == codeSizeBytes) return instructionCount
        return addressMap[targetCodeOffset]
            ?: throw IOException("HSP branch target 0x${targetCodeOffset.toString(16)} is not a command boundary.")
    }

    private fun checkedIndex(value: Long, maxExclusive: Long, label: String): Int {
        if (value < 0L || value >= maxExclusive || value > Int.MAX_VALUE.toLong()) {
            throw IOException("The HSP $label index is outside its declared range.")
        }
        return value.toInt()
    }

    private fun readUInt16(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset > bytes.size - 2) {
            throw IOException("A raw HSP 16-bit field extends beyond CS.")
        }
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    companion object {
        const val TYPE_MARK = 0
        const val TYPE_VAR = 1
        const val TYPE_STRING = 2
        const val TYPE_DNUM = 3
        const val TYPE_INUM = 4
        const val TYPE_LABEL = 7
        const val TYPE_INTCMD = 8
        const val TYPE_EXTCMD = 9
        const val TYPE_CMPCMD = 11
        const val TYPE_SYSVAR = 14
        const val TYPE_PROGCMD = 15

        const val EXFLG_0 = 0x1000
        const val EXFLG_1 = 0x2000
        const val EXFLG_2 = 0x4000
        const val EXFLG_3 = 0x8000

        const val CALCCODE_ADD = 0
        const val CALCCODE_SUB = 1
        const val CALCCODE_MUL = 2
        const val CALCCODE_DIV = 3
        const val CALCCODE_MOD = 4
        const val CALCCODE_AND = 5
        const val CALCCODE_OR = 6
        const val CALCCODE_XOR = 7
        const val CALCCODE_EQ = 8
        const val CALCCODE_NE = 9
        const val CALCCODE_GT = 10
        const val CALCCODE_LT = 11
        const val CALCCODE_GTEQ = 12
        const val CALCCODE_LTEQ = 13
        const val CALCCODE_RR = 14
        const val CALCCODE_LR = 15

        const val INTCMD_GETKEY = 0x23
        const val CMP_CMD_IF = 0
        const val PROGCMD_GOTO = 0x00
        const val PROGCMD_GOSUB = 0x01
        const val PROGCMD_RETURN = 0x02
        const val PROGCMD_BREAK = 0x03
        const val PROGCMD_REPEAT = 0x04
        const val PROGCMD_LOOP = 0x05
        const val PROGCMD_CONTINUE = 0x06
        const val PROGCMD_WAIT = 0x07
        const val PROGCMD_AWAIT = 0x08
        const val PROGCMD_END = 0x10
        const val PROGCMD_STOP = 0x11

        private const val TOKEN_TYPE_MASK = 0x0FFF
        private const val OMITTED_ARGUMENT_MARK = 63
        private const val MAX_WAIT_MS = 60_000L
        private val COMMAND_TYPES = setOf(
            TYPE_VAR, TYPE_INTCMD, TYPE_EXTCMD, TYPE_CMPCMD, TYPE_PROGCMD
        )

        private fun constantExpression(value: Hsp3RuntimeValue) =
            Hsp3Expression(Hsp3ExpressionNode.Literal(value))
    }
}

sealed class Hsp3ExpressionNode {
    data class Literal(val value: Hsp3RuntimeValue) : Hsp3ExpressionNode()
    data class Variable(val index: Int) : Hsp3ExpressionNode()
    data class SystemVariable(val index: Int) : Hsp3ExpressionNode()
    data class Binary(
        val operator: Int,
        val left: Hsp3ExpressionNode,
        val right: Hsp3ExpressionNode
    ) : Hsp3ExpressionNode()
}

sealed class Hsp3RuntimeValue {
    data class IntegerValue(val value: Int) : Hsp3RuntimeValue()
    data class DoubleValue(val value: Double) : Hsp3RuntimeValue()
    data class StringValue(val value: String) : Hsp3RuntimeValue()
    data class LabelValue(val codeOffsetBytes: Int) : Hsp3RuntimeValue()

    fun asInt(): Int = when (this) {
        is IntegerValue -> value
        is DoubleValue -> value.toInt()
        is StringValue -> value.toIntOrNull() ?: 0
        is LabelValue -> codeOffsetBytes
    }

    fun asDouble(): Double = when (this) {
        is IntegerValue -> value.toDouble()
        is DoubleValue -> value
        is StringValue -> value.toDoubleOrNull() ?: 0.0
        is LabelValue -> codeOffsetBytes.toDouble()
    }

    fun asString(): String = when (this) {
        is IntegerValue -> value.toString()
        is DoubleValue -> value.toString()
        is StringValue -> value
        is LabelValue -> value.codeOffsetBytes.toString()
    }
}

class Hsp3Expression internal constructor(
    internal val root: Hsp3ExpressionNode
) {
    fun evaluate(
        variable: (Int) -> Hsp3RuntimeValue,
        systemVariable: (Int) -> Hsp3RuntimeValue
    ): Hsp3RuntimeValue = evaluateNode(root, variable, systemVariable)

    fun singleVariableReference(): Int? =
        (root as? Hsp3ExpressionNode.Variable)?.index

    fun constantValueOrNull(): Hsp3RuntimeValue? =
        (root as? Hsp3ExpressionNode.Literal)?.value

    fun constantIntOrNull(): Int? =
        (constantValueOrNull() as? Hsp3RuntimeValue.IntegerValue)?.value

    private fun evaluateNode(
        node: Hsp3ExpressionNode,
        variable: (Int) -> Hsp3RuntimeValue,
        systemVariable: (Int) -> Hsp3RuntimeValue
    ): Hsp3RuntimeValue = when (node) {
        is Hsp3ExpressionNode.Literal -> node.value
        is Hsp3ExpressionNode.Variable -> variable(node.index)
        is Hsp3ExpressionNode.SystemVariable -> systemVariable(node.index)
        is Hsp3ExpressionNode.Binary -> applyBinary(
            node.operator,
            evaluateNode(node.left, variable, systemVariable),
            evaluateNode(node.right, variable, systemVariable)
        )
    }

    private fun applyBinary(
        operator: Int,
        left: Hsp3RuntimeValue,
        right: Hsp3RuntimeValue
    ): Hsp3RuntimeValue {
        if (operator == Hsp3BytecodeDecoder.CALCCODE_ADD &&
            (left is Hsp3RuntimeValue.StringValue || right is Hsp3RuntimeValue.StringValue)
        ) {
            return Hsp3RuntimeValue.StringValue(left.asString() + right.asString())
        }
        val a = left.asDouble()
        val b = right.asDouble()
        val ints = left is Hsp3RuntimeValue.IntegerValue && right is Hsp3RuntimeValue.IntegerValue
        if (ints) {
            val ai = left.asInt()
            val bi = right.asInt()
            return when (operator) {
                Hsp3BytecodeDecoder.CALCCODE_ADD -> Hsp3RuntimeValue.IntegerValue(ai + bi)
                Hsp3BytecodeDecoder.CALCCODE_SUB -> Hsp3RuntimeValue.IntegerValue(ai - bi)
                Hsp3BytecodeDecoder.CALCCODE_MUL -> Hsp3RuntimeValue.IntegerValue(ai * bi)
                Hsp3BytecodeDecoder.CALCCODE_DIV -> {
                    if (bi == 0) throw IOException("Division by zero in an HSP expression.")
                    Hsp3RuntimeValue.IntegerValue(ai / bi)
                }
                Hsp3BytecodeDecoder.CALCCODE_MOD -> {
                    if (bi == 0) throw IOException("Modulo by zero in an HSP expression.")
                    Hsp3RuntimeValue.IntegerValue(ai % bi)
                }
                Hsp3BytecodeDecoder.CALCCODE_AND -> Hsp3RuntimeValue.IntegerValue(ai and bi)
                Hsp3BytecodeDecoder.CALCCODE_OR -> Hsp3RuntimeValue.IntegerValue(ai or bi)
                Hsp3BytecodeDecoder.CALCCODE_XOR -> Hsp3RuntimeValue.IntegerValue(ai xor bi)
                Hsp3BytecodeDecoder.CALCCODE_EQ -> Hsp3RuntimeValue.IntegerValue(if (ai == bi) 1 else 0)
                Hsp3BytecodeDecoder.CALCCODE_NE -> Hsp3RuntimeValue.IntegerValue(if (ai != bi) 1 else 0)
                Hsp3BytecodeDecoder.CALCCODE_GT -> Hsp3RuntimeValue.IntegerValue(if (ai > bi) 1 else 0)
                Hsp3BytecodeDecoder.CALCCODE_LT -> Hsp3RuntimeValue.IntegerValue(if (ai < bi) 1 else 0)
                Hsp3BytecodeDecoder.CALCCODE_GTEQ -> Hsp3RuntimeValue.IntegerValue(if (ai >= bi) 1 else 0)
                Hsp3BytecodeDecoder.CALCCODE_LTEQ -> Hsp3RuntimeValue.IntegerValue(if (ai <= bi) 1 else 0)
                Hsp3BytecodeDecoder.CALCCODE_RR -> Hsp3RuntimeValue.IntegerValue(ai shr bi)
                Hsp3BytecodeDecoder.CALCCODE_LR -> Hsp3RuntimeValue.IntegerValue(ai shl bi)
                else -> throw IOException("Unknown HSP expression operator $operator.")
            }
        }
        return when (operator) {
            Hsp3BytecodeDecoder.CALCCODE_ADD -> Hsp3RuntimeValue.DoubleValue(a + b)
            Hsp3BytecodeDecoder.CALCCODE_SUB -> Hsp3RuntimeValue.DoubleValue(a - b)
            Hsp3BytecodeDecoder.CALCCODE_MUL -> Hsp3RuntimeValue.DoubleValue(a * b)
            Hsp3BytecodeDecoder.CALCCODE_DIV -> {
                if (b == 0.0) throw IOException("Division by zero in an HSP expression.")
                Hsp3RuntimeValue.DoubleValue(a / b)
            }
            Hsp3BytecodeDecoder.CALCCODE_EQ -> Hsp3RuntimeValue.IntegerValue(if (a == b) 1 else 0)
            Hsp3BytecodeDecoder.CALCCODE_NE -> Hsp3RuntimeValue.IntegerValue(if (a != b) 1 else 0)
            Hsp3BytecodeDecoder.CALCCODE_GT -> Hsp3RuntimeValue.IntegerValue(if (a > b) 1 else 0)
            Hsp3BytecodeDecoder.CALCCODE_LT -> Hsp3RuntimeValue.IntegerValue(if (a < b) 1 else 0)
            Hsp3BytecodeDecoder.CALCCODE_GTEQ -> Hsp3RuntimeValue.IntegerValue(if (a >= b) 1 else 0)
            Hsp3BytecodeDecoder.CALCCODE_LTEQ -> Hsp3RuntimeValue.IntegerValue(if (a <= b) 1 else 0)
            else -> throw IOException("This HSP operator requires integer operands.")
        }
    }
}
