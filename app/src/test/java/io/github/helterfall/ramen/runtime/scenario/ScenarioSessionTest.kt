package io.github.helterfall.ramen.runtime.scenario

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioSessionTest {
    @Test
    fun followsChoiceBranchAndRestoresSavedProgress() {
        val project = ScenarioParser.parse(ScenarioDemoContent.SOURCE)
        val session = ScenarioSession.start(project)

        assertEquals("arrival", session.frame().sceneId)
        assertEquals("coast", session.frame().backgroundResourceKey)
        assertEquals("Проводник", session.frame().dialogue?.speaker)

        assertEquals(2, session.continueStory().choices.size)
        val mapFrame = session.choose(0)
        assertEquals("map", mapFrame.sceneId)
        assertEquals("map_table", mapFrame.backgroundResourceKey)

        val returnFrame = session.continueStory()
        assertEquals("return_to_path", returnFrame.sceneId)
        assertEquals("Проводник", returnFrame.dialogue?.speaker)

        val encoded = ScenarioSaveCodec.encode(session.saveState())
        val decoded = ScenarioSaveCodec.decode(encoded)
        val restored = ScenarioSession.restore(project, decoded)
        assertEquals(returnFrame, restored.frame())

        assertEquals(2, restored.continueStory().choices.size)
        val endingBranch = restored.choose(0)
        assertEquals("tower_with_map", endingBranch.sceneId)
        assertTrue(endingBranch.dialogue?.text?.contains("Карта помогла") == true)
    }

    @Test
    fun missingFlagTakesFalseBranch() {
        val project = ScenarioParser.parse(ScenarioDemoContent.SOURCE)
        val session = ScenarioSession.start(project)
        session.continueStory()
        val frame = session.choose(1)

        assertEquals("tower", frame.sceneId)
        assertEquals("cliff", frame.backgroundResourceKey)
        assertFalse(frame.ended)
    }

    @Test
    fun saveCodecRejectsTrailingBytes() {
        val project = ScenarioParser.parse(ScenarioDemoContent.SOURCE)
        val bytes = ScenarioSaveCodec.encode(ScenarioSession.start(project).saveState())
        val corrupt = bytes + byteArrayOf(1, 2, 3)
        val error = runCatching { ScenarioSaveCodec.decode(corrupt) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun automaticJumpCyclesAreBounded() {
        val project = ScenarioParser.parse(
            """
            start alpha
            scene alpha
            jump beta
            scene beta
            jump alpha
            """.trimIndent()
        )
        val error = runCatching { ScenarioSession.start(project) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
    }
}
