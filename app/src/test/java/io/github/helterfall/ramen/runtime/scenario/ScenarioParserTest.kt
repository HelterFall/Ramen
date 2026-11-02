package io.github.helterfall.ramen.runtime.scenario

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ScenarioParserTest {
    @Test
    fun parsesDialoguesChoicesFlagsAndBranches() {
        val project = ScenarioParser.parse(
            """
            title "Test story"
            start start
            scene start
            background "room"
            show "guide" at left
            say "Guide" "Hello, \"traveller\"."
            choice "Continue" -> next
            choice "Finish" -> ending
            scene next
            set has_map = true
            if has_map == true -> ending
            scene ending
            end
            """.trimIndent()
        )

        assertEquals("Test story", project.title)
        assertEquals("start", project.startSceneId)
        assertEquals(3, project.scenes.size)
        val commands = project.scenes.getValue("start").commands
        assertEquals(
            ScenarioCommand.Dialogue("Guide", "Hello, \"traveller\"."),
            commands.filterIsInstance<ScenarioCommand.Dialogue>().single()
        )
        assertEquals(
            2,
            commands.filterIsInstance<ScenarioCommand.Choices>().single().options.size
        )
    }

    @Test
    fun rejectsUnknownCommands() {
        val error = assertThrows(ScenarioParseException::class.java) {
            ScenarioParser.parse(
                """
                start main
                scene main
                execute_native_code
                """.trimIndent()
            )
        }
        assertEquals(3, error.lineNumber)
    }

    @Test
    fun rejectsMissingBranchTargets() {
        assertThrows(ScenarioParseException::class.java) {
            ScenarioParser.parse(
                """
                start main
                scene main
                jump missing
                """.trimIndent()
            )
        }
    }

    @Test
    fun rejectsResourceTraversalKeys() {
        assertThrows(ScenarioParseException::class.java) {
            ScenarioParser.parse(
                """
                start main
                scene main
                background "../outside"
                """.trimIndent()
            )
        }
    }

    @Test
    fun rejectsDuplicateSceneIds() {
        assertThrows(ScenarioParseException::class.java) {
            ScenarioParser.parse(
                """
                start main
                scene main
                end
                scene main
                end
                """.trimIndent()
            )
        }
    }
}
