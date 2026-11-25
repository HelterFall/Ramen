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
    fun dispatchesRequiredNativeDrawingCommandsToGraphicsBackend() {
        val graphics = RecordingGraphics()
        fun int(value: Int) = Hsp3Expression(
            Hsp3ExpressionNode.Literal(Hsp3RuntimeValue.IntegerValue(value))
        )
        fun decimal(value: Double) = Hsp3Expression(
            Hsp3ExpressionNode.Literal(Hsp3RuntimeValue.DoubleValue(value))
        )
        fun string(value: String) = Hsp3Expression(
            Hsp3ExpressionNode.Literal(Hsp3RuntimeValue.StringValue(value))
        )
        fun command(id: Int, vararg values: Hsp3Expression?) =
            Hsp3Instruction.NativeCommand(9, id, values.toList())
        val snapshot = Hsp3VirtualMachine(graphics).runSlice(
            listOf(
                command(Hsp3CommandIds.PICLOAD, string("bg.png"), int(0)),
                command(Hsp3CommandIds.CELLLOAD, string("sprites.png")),
                command(Hsp3CommandIds.CELDIV, int(1), int(32), int(32), int(16), int(16)),
                command(Hsp3CommandIds.GCOPY, int(1), int(0), int(0), int(32), int(48)),
                command(Hsp3CommandIds.GZOOM, int(64), int(96), int(1), int(0), int(0), int(32), int(48), int(1)),
                command(Hsp3CommandIds.GROTATE, int(1), int(0), int(0), decimal(0.5), int(32), int(48)),
                command(Hsp3CommandIds.CELPUT, int(1), int(2), decimal(1.0), decimal(1.0), decimal(0.0)),
                command(Hsp3CommandIds.GMODE, int(3), int(16), int(16), int(128)),
                command(Hsp3CommandIds.BOXF, int(1), int(2), int(3), int(4)),
                command(Hsp3CommandIds.MES, string("hello"), int(1)),
                command(Hsp3CommandIds.REDRAW, int(1)),
                Hsp3Instruction.End
            )
        )

        assertEquals(Hsp3VmStatus.ENDED, snapshot.status)
        assertTrue(graphics.calls.contains("picload:bg.png:0"))
        assertTrue(graphics.calls.contains("celload:sprites.png:-2:0"))
        assertTrue(graphics.calls.contains("celdiv:1:32x32:16,16"))
        assertTrue(graphics.calls.any { it.startsWith("gcopy:1:") })
        assertTrue(graphics.calls.contains("gzoom:64x96:1:32x48"))
        assertTrue(graphics.calls.contains("grotate:1:0.5:32x48"))
        assertTrue(graphics.calls.contains("celput:1:2:1.0:1.0:0.0"))
        assertTrue(graphics.calls.contains("gmode:3:16x16:128"))
        assertTrue(graphics.calls.contains("boxf:1,2,3,4"))
        assertTrue(graphics.calls.contains("mes:hello:1"))
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
                    Hsp3BytecodeDecoder.INTCMD_BLOAD, string("source.bin"), 8,
                    size = integer(4), fileOffset = integer(1)
                ),
                Hsp3Instruction.FileTransfer(
                    Hsp3BytecodeDecoder.INTCMD_BSAVE, string("save.bin"), 8,
                    size = integer(4), fileOffset = integer(2)
                ),
                Hsp3Instruction.End
            )
        )

        assertEquals(Hsp3VmStatus.ENDED, snapshot.status)
        assertEquals(Hsp3RuntimeValue.StringValue("BC"), snapshot.values[8])
        assertEquals(
            listOf(0, 0, 66, 67, 0, 88, 0, 0),
            storage.files.getValue("save.bin").map { it.toInt() and 0xFF }
        )
    }

    @Test
    fun skipsUnsupportedHspCommandWithoutFailingTheModule() {
        val vm = Hsp3VirtualMachine(RecordingGraphics())
        val snapshot = vm.runSlice(
            listOf(
                Hsp3Instruction.Unsupported(9, 0x77, codeOffset = 0x24, tokenType = 9),
                Hsp3Instruction.End
            )
        )
        assertEquals(Hsp3VmStatus.ENDED, snapshot.status)
        assertEquals(2, snapshot.executedInstructions)
        assertEquals(null, snapshot.errorMessage)
    }

    @Test
    fun repeatEvaluatesCountAndInitialValueExpressionsAtRuntime() {
        val vm = Hsp3VirtualMachine(RecordingGraphics())
        val variable = Hsp3Expression(Hsp3ExpressionNode.Variable(0))
        val initial = Hsp3Expression(Hsp3ExpressionNode.Variable(1))
        val snapshot = vm.runSlice(
            listOf(
                Hsp3Instruction.SetInt(0, 3),
                Hsp3Instruction.SetInt(1, 7),
                Hsp3Instruction.Repeat(
                    count = -1,
                    exitInstruction = 5,
                    countExpression = variable,
                    initialValueExpression = initial
                ),
                Hsp3Instruction.Assign(
                    variable = 2,
                    operator = Hsp3BytecodeDecoder.CALCCODE_ADD,
                    expression = Hsp3Expression(Hsp3ExpressionNode.SystemVariable(4))
                ),
                Hsp3Instruction.Loop,
                Hsp3Instruction.End
            )
        )

        assertEquals(Hsp3VmStatus.ENDED, snapshot.status)
        assertEquals(24, snapshot.variables[2])
    }

    @Test
    fun waitAndAwaitEvaluateDynamicDelaysAndApplyDifferentUnits() {
        val delay = Hsp3Expression(Hsp3ExpressionNode.Variable(0))
        val vm = Hsp3VirtualMachine(RecordingGraphics())
        val wait = vm.runSlice(
            listOf(
                Hsp3Instruction.SetInt(0, 25),
                Hsp3Instruction.WaitFor(0L, delayExpression = delay, delayMultiplier = 10L)
            )
        )
        assertEquals(Hsp3VmStatus.YIELDED, wait.status)
        assertEquals(250L, wait.yieldDelayMillis)

        val await = Hsp3VirtualMachine(RecordingGraphics()).runSlice(
            listOf(
                Hsp3Instruction.SetInt(0, 25),
                Hsp3Instruction.WaitFor(0L, delayExpression = delay, delayMultiplier = 1L)
            )
        )
        assertEquals(Hsp3VmStatus.YIELDED, await.status)
        assertEquals(25L, await.yieldDelayMillis)
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

        override fun picload(name: String, mode: Int) {
            calls += "picload:$name:$mode"
        }

        override fun celload(name: String, id: Int, mode: Int): Int {
            calls += "celload:$name:$id:$mode"
            return if (id >= 0) id else 7
        }

        override fun celdiv(id: Int, cellWidth: Int, cellHeight: Int, baseX: Int, baseY: Int) {
            calls += "celdiv:$id:${cellWidth}x$cellHeight:$baseX,$baseY"
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

        override fun gzoom(
            destWidth: Int, destHeight: Int, sourceId: Int, sourceX: Int, sourceY: Int,
            sourceWidth: Int, sourceHeight: Int, quality: Int
        ) {
            calls += "gzoom:${destWidth}x$destHeight:$sourceId:${sourceWidth}x$sourceHeight"
        }

        override fun grotate(
            sourceId: Int, sourceX: Int, sourceY: Int, angleRadians: Double,
            destWidth: Int, destHeight: Int
        ) {
            calls += "grotate:$sourceId:$angleRadians:${destWidth}x$destHeight"
        }

        override fun celput(id: Int, cellIndex: Int, zoomX: Double, zoomY: Double, angleRadians: Double) {
            calls += "celput:$id:$cellIndex:$zoomX:$zoomY:$angleRadians"
        }

        override fun gmode(mode: Int, copyWidth: Int, copyHeight: Int, alpha: Int) {
            calls += "gmode:$mode:${copyWidth}x$copyHeight:$alpha"
        }

        override fun boxf(x1: Int, y1: Int, x2: Int, y2: Int) {
            calls += "boxf:$x1,$y1,$x2,$y2"
        }

        override fun mes(value: String) {
            calls += "mes:$value"
        }

        override fun mes(value: String, flags: Int) {
            calls += "mes:$value:$flags"
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
