package io.github.helterfall.ramen.nscripter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class NscripterVirtualMachineTest {
    private class MemorySaveStore : NscripterSaveStore {
        private val slots = HashMap<Int, NscripterSavedGame>()
        override fun exists(slot: Int): Boolean = slots.containsKey(slot)
        override fun save(slot: Int, savedGame: NscripterSavedGame) { slots[slot] = savedGame }
        override fun load(slot: Int): NscripterSavedGame? = slots[slot]
    }

    @Test
    fun parsesMode800DefineAndStartLabelsAndCyrillicDialogue() {
        val program = NscripterScriptProgram.parse(
            """
            ;mode800
            *define
            caption "Narcissu"
            game
            *start
            #Сэцуми
            Привет, мир!
            end
            """.trimIndent()
        )
        assertEquals(800, program.screenWidth)
        assertEquals(600, program.screenHeight)
        assertTrue(program.defineInstruction != null)
        assertTrue(program.startInstruction > program.defineInstruction!!)
        val vm = NscripterVirtualMachine(program, MemorySaveStore())
        val first = vm.resume()
        assertTrue(first is NscripterVmYield.Dialogue)
        assertEquals("Привет, мир!", (first as NscripterVmYield.Dialogue).scene.dialogue)
        assertEquals("Сэцуми", first.scene.speaker)
    }

    @Test
    fun skipsDefineHeaderPluginsVersionAndEffectsBeforeFirstScene() {
        val source = """
            *define
            caption "NarcissuRus"
            spi "jpgplgin.dll|jpg"
            versionstr "NarcissuRus 1.0"
            effect 2,6,500
            effect 3,10,600
            effect 4,13,3000
            effect 5,10,800
            effect 6,6,500
            header_custom 1
            game
            *start
            bg "scene.jpg",1
            Первая настоящая сцена.
            end
        """.trimIndent()

        val program = NscripterScriptProgram.parse(source)
        assertTrue(program.instructions.none { it.command == "text" && it.sourceLine in 3..10 })
        val vm = NscripterVirtualMachine(program, MemorySaveStore())

        val first = vm.resume()
        assertTrue(first is NscripterVmYield.Dialogue)
        assertEquals("Первая настоящая сцена.", (first as NscripterVmYield.Dialogue).scene.dialogue)
        assertEquals("NarcissuRus", first.scene.caption)
        assertEquals("scene.jpg", first.scene.background)
    }

    @Test
    fun executesAliasesLimitsForIfAndGosubReturnUntilTheDialogue() {
        val program = NscripterScriptProgram.parse(
            """
            *define
            numalias increment, 3
            game
            *start
            intlimit %score, 0, 10
            add %score, increment
            gosub *routine
            if %score >= 4 goto *passed
            end
            *routine
            for %i = 1 to 1
            add %score, 1
            next
            return
            *passed
            caption "Сценарий"
            done
            end
            """.trimIndent()
        )
        val vm = NscripterVirtualMachine(program, MemorySaveStore())
        val result = vm.resume()
        assertTrue(result is NscripterVmYield.Dialogue)
        assertEquals("done", (result as NscripterVmYield.Dialogue).scene.dialogue)
        assertEquals("Сценарий", result.scene.caption)
        vm.acceptTap()
        assertTrue(vm.resume() is NscripterVmYield.Finished)
    }

    @Test
    fun exposesButtonChoiceAndAudioYieldActions() {
        val program = NscripterScriptProgram.parse(
            """
            *start
            btn 1, "Продолжить", 20, 30, 160, 44
            dwave 0, "voice.ogg"
            dwaveloop 1, "ambience.mp3"
            dwavestop 1
            stop
            btnwait %button
            csel "Да", *yes, "Нет", *no
            *yes
            selected
            end
            *no
            cancelled
            end
            """.trimIndent()
        )
        val vm = NscripterVirtualMachine(program, MemorySaveStore())
        val buttons = vm.resume()
        assertTrue(buttons is NscripterVmYield.ButtonWait)
        assertEquals("Продолжить", vm.snapshot().buttons.single().label)
        val audio = vm.takeAudioActions()
        assertEquals(4, audio.size)
        assertEquals(0, audio[0].channel)
        assertEquals("voice.ogg", audio[0].fileName)
        assertTrue(audio[1].loop)
        assertTrue(audio[2].stop)
        assertTrue(audio[3].stop)
        vm.acceptButton(1)
        val choices = vm.resume()
        assertTrue(choices is NscripterVmYield.ChoiceWait)
        assertEquals(2, vm.snapshot().choices.size)
        vm.acceptChoice(0)
        val selected = vm.resume()
        assertTrue(selected is NscripterVmYield.Dialogue)
        assertEquals("selected", (selected as NscripterVmYield.Dialogue).scene.dialogue)
    }

    @Test
    fun savesAndRestoresSerializedGameState() {
        val directory = Files.createTempDirectory("nscripter-saves").toFile()
        val store = FileNscripterSaveStore(directory)
        val state = NscripterSavedGame(
            programCounter = 3,
            integerVariables = hashMapOf("score" to 7),
            stringVariables = hashMapOf("name" to "test"),
            scene = NscripterSceneState(dialogue = "hello"),
            savedAtEpochMillis = 123456L
        )
        store.save(2, state)
        assertTrue(store.exists(2))
        assertEquals(state, store.load(2))
        assertFalse(store.exists(3))
        directory.deleteRecursively()
    }


    @Test
    fun evaluatesLogicalAndOrComparisonsInIfConditions() {
        val program = NscripterScriptProgram.parse(
            """
            *start
            mov %a, 2
            mov %b, 0
            if %a == 2 && %b == 0 mov %result, 1
            if %result == 1
            logical-condition-ok
            end
            """.trimIndent()
        )
        val vm = NscripterVirtualMachine(program, MemorySaveStore())
        val result = vm.resume()
        assertTrue(result is NscripterVmYield.Dialogue)
        assertEquals("logical-condition-ok", (result as NscripterVmYield.Dialogue).scene.dialogue)
    }

    @Test
    fun executesColonSeparatedMovAndGotoActionsInsideIf() {
        val program = NscripterScriptProgram.parse(
            """
            *start
            if 3 mov %102,1:mov %sys_mode,7:goto *loaded
            should-not-run
            end
            *loaded
            chain-ok
            end
            """.trimIndent()
        )
        val vm = NscripterVirtualMachine(program, MemorySaveStore())
        val result = vm.resume()
        assertTrue(result is NscripterVmYield.Dialogue)
        assertEquals("chain-ok", (result as NscripterVmYield.Dialogue).scene.dialogue)
    }

    @Test
    fun parsesBtndefErasetextwindowJumpbAndTildeAsCommandsNotDialogue() {
        val program = NscripterScriptProgram.parse(
            """
            *define
            btndef "tui2\title_on.bmp"
            clickstr "click"
            defaultspeed 20
            defmp3vol 100
            defsevol 100
            defvoicevol 100
            globalon
            kidokuskip
            killmenu
            menusetwindow 0,0,640,480
            mode_ext
            mode_wave_demo
            selectcolor 1,2
            textgosub *text
            usewheel
            cdfadeout 100
            game
            *start
            erasetextwindow 1
            ~
            jumpb
            ready
            end
            """.trimIndent()
        )
        assertTrue(program.instructions.none {
            it.command == "text" && (it.sourceLine in 2..18 || it.sourceLine in 20..22)
        })
        assertTrue(program.instructions.any { it.command == "btndef" })
        assertTrue(program.instructions.any { it.command == "erasetextwindow" })
        assertTrue(program.instructions.any { it.command == "jumpb" })
        assertTrue(program.instructions.any { it.command == "tilde" })
        val vm = NscripterVirtualMachine(
            NscripterScriptProgram.parse(
                """
                *define
                btndef "tui2\title_on.bmp"
                game
                *start
                erasetextwindow 1
                ready
                end
                """.trimIndent()
            ),
            MemorySaveStore()
        )
        val result = vm.resume()
        assertTrue(result is NscripterVmYield.Dialogue)
        assertEquals("tui2\title_on.bmp", vm.snapshot().buttonDefinition)
        assertTrue(vm.snapshot().eraseTextWindowDuringEffect)
    }

    @Test
    fun parserRecognizesEveryRequestedRuntimeCommand() {
        val source = """
            bg "bg.jpg", 1
            lsp 1, "sprite.png", 0, 0, :c
            lsph 2, "hidden.png", 2, 2, :a
            csp 1
            print
            windowback
            windoweffect 10, 200
            setwindow 16, 16, 20, 4, 26, 26, 0, 0, 0, 0, 0, "#ffffff", 0, 320, 639, 479
            btn 1, "OK", 10, 10, 100, 40
            spbtn 2, 3
            exbtn 2, 3, "P2"
            exbtn_d "C2"
            btnwait %0
            textbtnwait %1
            csel "Yes", *yes, "No", *no
            rmenu "Save", save, "Load", load
            dwave 0, "a.wav"
            dwaveloop 1, "b.mp3"
            dwavestop 1
            stop
            numalias zero, 0
            intlimit %0, 0, 5
            add %0, 1
            if %0 > 0 goto *yes
            for %1 = 1 to 2
            next
            goto *yes
            gosub *yes
            return
            wait 10
            caption "title"
            game
            end
            nsa
            savefileexist %2, 0
            savenumber 8
            saveon
            savetime 0, %3, %4, %5, %6
            *yes
            end
            *no
            end
        """.trimIndent()
        val program = NscripterScriptProgram.parse(source)
        val recognized = program.instructions.map { it.command }.toSet()
        assertTrue(NscripterScriptProgram.supportedCommands.all { it in recognized })
        assertTrue(program.instructions.none { it.command == "text" })
    }
}
