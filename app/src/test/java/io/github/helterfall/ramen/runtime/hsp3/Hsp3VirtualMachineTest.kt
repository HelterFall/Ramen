package io.github.helterfall.ramen.runtime.hsp3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable

class Hsp3VirtualMachineTest {
    @Test
    fun runsScreenBufferBlitTextAndRedrawCommands() {
        val graphics = RecordingGraphics()
        val vm = Hsp3VirtualMachine(graphics)
        val instructions = listOf(
            Hsp3Instruction.Screen(0, 800, 600),
            Hsp3Instruction.Buffer(23, 800, 600),
            Hsp3Instruction.SelectSurface(23),
            Hsp3Instruction.Color(255, 255, 255),
            Hsp3Instruction.Pos(8, 12),
            Hsp3Instruction.Boxf(0, 0, 20, 20),
            Hsp3Instruction.Mes("HSP3"),
            Hsp3Instruction.Gcopy(0, 0, 0, 16, 16, 24, 24),
            Hsp3Instruction.Redraw(true),
            Hsp3Instruction.Wait
        )

        val snapshot = vm.runSlice(instructions)

        assertEquals(Hsp3VmStatus.YIELDED, snapshot.status)
        assertEquals(10, snapshot.programCounter)
        assertTrue(graphics.calls.contains("screen:0:800x600"))
        assertTrue(graphics.calls.contains("buffer:23:800x600"))
        assertTrue(graphics.calls.contains("boxf:0,0,20,20"))
        assertTrue(graphics.calls.contains("mes:HSP3"))
        assertTrue(graphics.calls.contains("gcopy:0:0,0,16,16:24,24"))
        assertTrue(graphics.calls.contains("redraw:true"))
    }

    @Test
    fun repeatLoopAndSubroutineUpdateVariables() {
        val vm = Hsp3VirtualMachine(RecordingGraphics())
        val instructions = listOf(
            Hsp3Instruction.Repeat(2),
            Hsp3Instruction.AddInt(0, 1),
            Hsp3Instruction.Loop,
            Hsp3Instruction.Gosub(6),
            Hsp3Instruction.Jump(8),
            Hsp3Instruction.End,
            Hsp3Instruction.AddInt(1, 7),
            Hsp3Instruction.Return,
            Hsp3Instruction.End
        )

        val snapshot = vm.runSlice(instructions)

        assertEquals(Hsp3VmStatus.ENDED, snapshot.status)
        assertEquals(2, snapshot.variables[0])
        assertEquals(7, snapshot.variables[1])
    }

    @Test
    fun stickUsesRisingEdgeAndNonTriggerMask() {
        var heldMask = 0b0011
        val vm = Hsp3VirtualMachine(
            RecordingGraphics(),
            Hsp3InputProvider { heldMask }
        )
        val instructions = listOf(
            Hsp3Instruction.Stick(5, nonTriggerMask = 0b0100),
            Hsp3Instruction.Wait,
            Hsp3Instruction.Jump(0)
        )

        val first = vm.runSlice(instructions)
        assertEquals(0b0011, first.variables[5])

        heldMask = 0b0011
        val second = vm.runSlice(instructions)
        assertEquals(0b0000, second.variables[5])
    }

    @Test
    fun getkeyMapsTouchGamepadAliasesToOpenHspKeyCodes() {
        val mask = (1 shl 5) or (1 shl 11) or (1 shl 7) // Enter, Z and Escape
        val vm = Hsp3VirtualMachine(
            RecordingGraphics(),
            Hsp3InputProvider { mask }
        )
        fun key(code: Int) = Hsp3Expression(
            Hsp3ExpressionNode.Literal(Hsp3RuntimeValue.IntegerValue(code))
        )
        val snapshot = vm.runSlice(
            listOf(
                Hsp3Instruction.GetKey(0, key(13)),
                Hsp3Instruction.GetKey(1, key(90)),
                Hsp3Instruction.GetKey(2, key(27)),
                Hsp3Instruction.End
            )
        )
        assertEquals(Hsp3VmStatus.ENDED, snapshot.status)
        assertEquals(1, snapshot.variables[0])
        assertEquals(1, snapshot.variables[1])
        assertEquals(1, snapshot.variables[2])
    }

    @Test
    fun bloadAndBsaveTransferBytesThroughSdimBuffer() {
        val storage = MemoryFileStorage(
            mutableMapOf(
                "source.bin" to byteArrayOf(65, 66, 67, 0, 88),
                "save.bin" to ByteArray(8)
            )
        )
        fun integer(value: Int) = Hsp3Expression(
            Hsp3ExpressionNode.Literal(Hsp3RuntimeValue.IntegerValue(value))
        )
        fun string(value: String) = Hsp3Expression(
            Hsp3ExpressionNode.Literal(Hsp3RuntimeValue.StringValue(value))
        )
        val snapshot = Hsp3VirtualMachine(
            RecordingGraphics(),
            fileStorage = storage
        ).runSlice(
            listOf(
                Hsp3Instruction.Dimension(8, listOf(integer(12)), stringBuffer = true),
                Hsp3Instruction.FileTransfer(
                    Hsp3BytecodeDecoder.INTCMD_BLOAD, string("source.bin"), 8
                ),
                Hsp3Instruction.FileTransfer(
                    Hsp3BytecodeDecoder.INTCMD_BSAVE, string("save.bin"), 8,
                    size = integer(4), fileOffset = integer(2)
                ),
                Hsp3Instruction.End
            )
        )

        assertEquals(Hsp3VmStatus.ENDED, snapshot.status)
        assertEquals(Hsp3RuntimeValue.StringValue("ABC"), snapshot.values[8])
        assertEquals(
            listOf(0, 0, 65, 66, 67, 0, 0, 0),
            storage.files.getValue("save.bin").map { it.toInt() and 0xFF }
        )
    }

    @Test
    fun rejectsUnsupportedHspCommandWithoutCrashingHost() {
        val vm = Hsp3VirtualMachine(RecordingGraphics())
        val snapshot = vm.runSlice(listOf(Hsp3Instruction.Unsupported(9, 0x77)))
        assertEquals(Hsp3VmStatus.FAILED, snapshot.status)
        assertTrue(snapshot.errorMessage!!.contains("Unsupported HSP command"))
    }

    private class MemoryFileStorage(
        val files: MutableMap<String, ByteArray>
    ) : Hsp3FileStorage {
        override fun read(name: String): ByteArray? = files[name]?.copyOf()

        override fun write(name: String, offset: Int, bytes: ByteArray) {
            val existing = files[name] ?: throw java.io.IOException("Missing output file.")
            val result = existing.copyOf(maxOf(existing.size, offset + bytes.size))
            bytes.copyInto(result, destinationOffset = offset)
            files[name] = result
        }
    }

    private class RecordingGraphics : Hsp3Graphics {
        val calls = mutableListOf<String>()

        override fun screen(id: Int, width: Int, height: Int) {
            calls += "screen:$id:${width}x$height"
        }

        override fun buffer(id: Int, width: Int, height: Int) {
            calls += "buffer:$id:${width}x$height"
        }

        override fun gsel(id: Int) {
            calls += "gsel:$id"
        }

        override fun picload(name: String) {
            calls += "picload:$name"
        }

        override fun gcopy(
            sourceId: Int,
            sourceX: Int,
            sourceY: Int,
            width: Int,
            height: Int,
            destX: Int,
            destY: Int
        ) {
            calls += "gcopy:$sourceId:$sourceX,$sourceY,$width,$height:$destX,$destY"
        }

        override fun boxf(x1: Int, y1: Int, x2: Int, y2: Int) {
            calls += "boxf:$x1,$y1,$x2,$y2"
        }

        override fun mes(value: String) {
            calls += "mes:$value"
        }

        override fun pos(x: Int, y: Int) {
            calls += "pos:$x,$y"
        }

        override fun color(red: Int, green: Int, blue: Int) {
            calls += "color:$red,$green,$blue"
        }

        override fun font(name: String, size: Int, style: Int) {
            calls += "font:$name,$size,$style"
        }

        override fun gmode(mode: Int, alpha: Int) {
            calls += "gmode:$mode,$alpha"
        }

        override fun redraw(enabled: Boolean) {
            calls += "redraw:$enabled"
        }

        override fun close() = Unit
    }
}
