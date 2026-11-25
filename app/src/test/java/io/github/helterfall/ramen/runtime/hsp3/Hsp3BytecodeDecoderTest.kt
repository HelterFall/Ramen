package io.github.helterfall.ramen.runtime.hsp3

import io.github.helterfall.ramen.parser.Hsp3BytecodeLoader
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Hsp3BytecodeDecoderTest {
    @Test
    fun decodesPostfixExpressionAndOpenHspArgumentBoundaries() {
        withProgram(createColorProgram()) { program ->
            val instructions = Hsp3BytecodeDecoder().decode(program)

            assertEquals(2, instructions.size)
            val color = instructions[0] as Hsp3Instruction.NativeCommand
            assertEquals(Hsp3CommandIds.COLOR, color.commandId)
            assertEquals(3, color.arguments.size)

            val first = color.arguments[0]!!.evaluate(
                variable = { Hsp3RuntimeValue.IntegerValue(0) },
                systemVariable = { Hsp3RuntimeValue.IntegerValue(0) }
            )
            assertEquals(Hsp3RuntimeValue.IntegerValue(3), first)
            assertEquals(
                Hsp3RuntimeValue.IntegerValue(128),
                color.arguments[1]!!.evaluate(
                    variable = { Hsp3RuntimeValue.IntegerValue(0) },
                    systemVariable = { Hsp3RuntimeValue.IntegerValue(0) }
                )
            )
            assertEquals(
                Hsp3RuntimeValue.IntegerValue(64),
                color.arguments[2]!!.evaluate(
                    variable = { Hsp3RuntimeValue.IntegerValue(0) },
                    systemVariable = { Hsp3RuntimeValue.IntegerValue(0) }
                )
            )
            assertEquals(Hsp3Instruction.End, instructions[1])
        }
    }

    @Test
    fun decodedGraphicsCommandExecutesThroughVm() {
        withProgram(createColorProgram()) { program ->
            val graphics = RecordingGraphics()
            val vm = Hsp3VirtualMachine(graphics)
            val snapshot = vm.runSlice(Hsp3BytecodeDecoder().decode(program))

            assertEquals(Hsp3VmStatus.ENDED, snapshot.status)
            assertTrue(graphics.calls.contains("color:3:128:64"))
        }
    }

    @Test
    fun getkeyCommandPreservesDestinationAndKeyExpression() {
        withProgram(createGetKeyProgram()) { program ->
            val instructions = Hsp3BytecodeDecoder().decode(program)
            val getKey = instructions.first() as Hsp3Instruction.GetKey
            assertEquals(7, getKey.destinationVariable)
            assertEquals(
                Hsp3RuntimeValue.IntegerValue(13),
                getKey.keyCode.evaluate(
                    variable = { Hsp3RuntimeValue.IntegerValue(0) },
                    systemVariable = { Hsp3RuntimeValue.IntegerValue(0) }
                )
            )
        }
    }

    @Test
    fun decodesAndExecutesNestedBuiltinStringFunction() {
        withProgram(createMesWithStringFunction()) { program ->
            val graphics = RecordingGraphics()
            val snapshot = Hsp3VirtualMachine(graphics)
                .runSlice(Hsp3BytecodeDecoder().decode(program))

            assertEquals(Hsp3VmStatus.ENDED, snapshot.status)
            assertTrue(graphics.calls.contains("mes:42"))
        }
    }

    @Test
    fun nestedFunctionArgumentsDoNotSplitTheirOuterParameterGroup() {
        withProgram(createMesWithNestedStringFunctions()) { program ->
            val graphics = RecordingGraphics()
            val snapshot = Hsp3VirtualMachine(graphics)
                .runSlice(Hsp3BytecodeDecoder().decode(program))

            assertEquals(Hsp3VmStatus.ENDED, snapshot.status)
            assertTrue(graphics.calls.contains("mes:bcd"))
        }
    }

    @Test
    fun preservesAnOmittedLeadingOptionalParameter() {
        withProgram(createGmodeWithOmittedFirstArgument()) { program ->
            val command = Hsp3BytecodeDecoder().decode(program).first() as Hsp3Instruction.NativeCommand
            assertEquals(Hsp3CommandIds.GMODE, command.commandId)
            assertEquals(2, command.arguments.size)
            assertEquals(null, command.arguments[0])
            assertEquals(
                Hsp3RuntimeValue.IntegerValue(3),
                command.arguments[1]!!.evaluate(
                    variable = { Hsp3RuntimeValue.IntegerValue(0) },
                    systemVariable = { Hsp3RuntimeValue.IntegerValue(0) }
                )
            )
        }
    }

    @Test
    fun assignmentCommandIsConvertedToTypedVmInstruction() {
        withProgram(createAssignmentProgram()) { program ->
            val instructions = Hsp3BytecodeDecoder().decode(program)
            val assignment = instructions.first() as Hsp3Instruction.Assign
            assertEquals(4, assignment.variable)
            assertEquals(Hsp3BytecodeDecoder.CALCCODE_EQ, assignment.operator)

            val vm = Hsp3VirtualMachine(RecordingGraphics())
            val snapshot = vm.runSlice(instructions)
            assertEquals(Hsp3VmStatus.ENDED, snapshot.status)
            assertEquals(9, snapshot.variables[4])
        }
    }

    @Test
    fun conditionalCommandSkipsRawBranchWordAndResolvesOtStylePc() {
        withProgram(createIfProgram()) { program ->
            val instructions = Hsp3BytecodeDecoder().decode(program)
            val branch = instructions.first() as Hsp3Instruction.JumpIfFalse
            assertEquals(2, branch.targetInstruction)
            assertEquals(Hsp3Instruction.End, instructions[1])
            assertEquals(Hsp3Instruction.End, instructions[2])
        }
    }

    @Test
    fun dimensionsAndIndexedAssignmentAreExecutedByVm() {
        withProgram(createArrayAssignmentProgram()) { program ->
            val instructions = Hsp3BytecodeDecoder().decode(program)
            assertTrue(instructions.first() is Hsp3Instruction.Dimension)
            val assignment = instructions[1] as Hsp3Instruction.Assign
            assertEquals(4, assignment.variable)
            assertEquals(1, assignment.arrayIndices.size)

            val snapshot = Hsp3VirtualMachine(RecordingGraphics()).runSlice(instructions)
            assertEquals(Hsp3VmStatus.ENDED, snapshot.status)
            assertEquals(
                Hsp3RuntimeValue.IntegerValue(5),
                snapshot.arrayValues[4 to listOf(1)]
            )
        }
    }

    @Test
    fun arrayElementCanBeReadInsidePostfixExpression() {
        withProgram(createArrayReadProgram()) { program ->
            val instructions = Hsp3BytecodeDecoder().decode(program)
            val snapshot = Hsp3VirtualMachine(RecordingGraphics()).runSlice(instructions)
            assertEquals(Hsp3VmStatus.ENDED, snapshot.status)
            assertEquals(7, snapshot.variables[5])
            assertEquals(
                Hsp3RuntimeValue.IntegerValue(5),
                snapshot.arrayValues[4 to listOf(1)]
            )
        }
    }

    private fun withProgram(bytes: ByteArray, block: (io.github.helterfall.ramen.parser.Hsp3Program) -> Unit) {
        val directory = Files.createTempDirectory("ramen-hsp3-decoder").toFile()
        try {
            val file = File(directory, "start.ax")
            file.writeBytes(bytes)
            block(Hsp3BytecodeLoader().load(file))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun createArrayReadProgram(): ByteArray {
        val code = listOf(
            0x200F, Hsp3BytecodeDecoder.PROGCMD_DIM,
            0x0001, 4,
            0x4004, 3,
            0x2001, 4,
            0x0000, '('.code,
            0x0004, 1,
            0x0000, ')'.code,
            0x0000, Hsp3BytecodeDecoder.CALCCODE_EQ,
            0x0004, 5,
            0x2001, 5,
            0x0000, Hsp3BytecodeDecoder.CALCCODE_EQ,
            0x0001, 4,
            0x0000, '('.code,
            0x0004, 1,
            0x0000, ')'.code,
            0x0004, 2,
            0x0000, Hsp3BytecodeDecoder.CALCCODE_ADD,
            0x200F, Hsp3BytecodeDecoder.PROGCMD_END
        )
        return createAx(code)
    }

    private fun createArrayAssignmentProgram(): ByteArray {
        val code = listOf(
            0x200F, Hsp3BytecodeDecoder.PROGCMD_DIM, // dim
            0x0001, 4,                              // variable #4
            0x4004, 3,                              // length = 3
            0x2001, 4,                              // assignment to variable #4
            0x0000, '('.code,
            0x0004, 1,                              // index 1
            0x0000, ')'.code,
            0x0000, Hsp3BytecodeDecoder.CALCCODE_EQ,
            0x0004, 5,                              // value 5
            0x200F, Hsp3BytecodeDecoder.PROGCMD_END
        )
        return createAx(code)
    }

    private fun createIfProgram(): ByteArray {
        val code = listOf(
            0x200B, Hsp3BytecodeDecoder.CMP_CMD_IF, // TYPE_CMPCMD + EXFLG_1
            4,                                     // raw skip: four CS words after this field
            0x0004, 1,                             // condition expression
            0x200F, 0x10,                          // true branch: end
            0x200F, 0x10                           // false branch target: end
        )
        return createAx(code)
    }

    private fun createMesWithNestedStringFunctions(): ByteArray {
        // A valid nested expression: mes str(int(42)).
        val code = listOf(
            0x2009, Hsp3CommandIds.MES,
            0x000D, 0x0100, // str(
            0x0000, '('.code,
            0x000D, 0x0000, // int(
            0x0000, '('.code,
            0x0004, 42,
            0x0000, ')'.code,
            0x0000, ')'.code,
            0x200F, 0x10
        )
        return createAx(code)
    }

    private fun createMesWithStringFunction(): ByteArray {
        val code = listOf(
            0x2009, Hsp3CommandIds.MES,
            0x000D, 0x0100, // TYPE_INTFUNC str()
            0x0000, '('.code,
            0x0004, 42,
            0x0000, ')'.code,
            0x200F, 0x10
        )
        return createAx(code)
    }

    private fun createGmodeWithOmittedFirstArgument(): ByteArray {
        val code = listOf(
            0x2009, Hsp3CommandIds.GMODE,
            0x4004, 3,     // EXFLG_2: first emitted token belongs to argument 2
            0x200F, 0x10
        )
        return createAx(code)
    }

    private fun createAssignmentProgram(): ByteArray {
        val code = listOf(
            0x2001, 4,     // TYPE_VAR + EXFLG_1: assign variable #4
            0x0000, Hsp3BytecodeDecoder.CALCCODE_EQ,
            0x0004, 9,
            0x200F, 0x10
        )
        return createAx(code)
    }

    private fun createColorProgram(): ByteArray {
        // extcmd color r,g,b; first argument is postfix expression 1 2 +.
        val code = listOf(
            0x2009, 0x18,  // TYPE_EXTCMD + EXFLG_1; COLOR command ID
            0x0004, 1,     // first expression operand
            0x0004, 2,     // second expression operand
            0x0000, 0,     // TYPE_MARK + CALCCODE_ADD
            0x4004, 128,   // next parameter begins (EXFLG_2)
            0x4004, 64,    // next parameter begins (EXFLG_2)
            0x200F, 0x10   // TYPE_PROGCMD + EXFLG_1; END
        )
        return createAx(code)
    }

    private fun createGetKeyProgram(): ByteArray {
        val code = listOf(
            0x2008, 0x23,  // TYPE_INTCMD + EXFLG_1; getkey
            0x0001, 7,     // destination variable
            0x4004, 13,    // key code argument, starts a new parameter
            0x200F, 0x10   // TYPE_PROGCMD + EXFLG_1; END
        )
        return createAx(code)
    }

    private fun createAx(words: List<Int>): ByteArray {
        val codeSize = words.size * 2
        val bytes = ByteArray(0x60 + codeSize)
        "HSP3".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0)
        putUInt32(bytes, 4, 0x301L)
        putUInt32(bytes, 8, 64L)
        putUInt32(bytes, 12, bytes.size.toLong())
        putUInt32(bytes, 16, 0x60L)
        putUInt32(bytes, 20, codeSize.toLong())

        words.forEachIndexed { index, value ->
            putUInt16(bytes, 0x60 + index * 2, value)
        }
        return bytes
    }

    private fun putUInt16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
    }

    private fun putUInt32(target: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 4) target[offset + index] = (value ushr (index * 8)).toByte()
    }

    private class RecordingGraphics : Hsp3Graphics {
        val calls = mutableListOf<String>()

        override fun screen(id: Int, width: Int, height: Int) = Unit
        override fun buffer(id: Int, width: Int, height: Int) = Unit
        override fun gsel(id: Int) = Unit
        override fun picload(name: String) = Unit
        override fun gcopy(
            sourceId: Int, sourceX: Int, sourceY: Int, width: Int, height: Int, destX: Int, destY: Int
        ) = Unit
        override fun boxf(x1: Int, y1: Int, x2: Int, y2: Int) = Unit
        override fun mes(value: String) {
            calls += "mes:$value"
        }
        override fun pos(x: Int, y: Int) = Unit
        override fun color(red: Int, green: Int, blue: Int) {
            calls += "color:$red:$green:$blue"
        }
        override fun font(name: String, size: Int, style: Int) = Unit
        override fun gmode(mode: Int, alpha: Int) = Unit
        override fun redraw(enabled: Boolean) = Unit
        override fun close() = Unit
    }
}
