package io.github.helterfall.ramen.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameLaunchPolicyTest {
    private fun game(engineId: String, status: String) = GameRecord(
        id = "test",
        title = "Test VN",
        relativeDirectory = "games/test",
        engineId = engineId,
        engineLabel = "test",
        compatibilityStatus = status,
        evidence = "",
        importedAtEpochMillis = 0L
    )

    @Test
    fun nscripterParserAvailableShowsLaunchButton() {
        assertTrue(GameLaunchPolicy.canLaunch(game("nscripter", "PARSER_AVAILABLE")))
    }

    @Test
    fun protectedNscripterDoesNotShowLaunchButton() {
        assertFalse(GameLaunchPolicy.canLaunch(game("nscripter", "DETECTED_UNSUPPORTED")))
    }

    @Test
    fun unknownEngineDoesNotShowLaunchButton() {
        assertFalse(GameLaunchPolicy.canLaunch(game("unknown", "UNKNOWN")))
    }

    @Test
    fun existingHtml5AndSystemNnnLaunchRoutesRemainEnabled() {
        assertTrue(GameLaunchPolicy.canLaunch(game("html5", "SUPPORTED_LIMITED")))
        assertTrue(GameLaunchPolicy.canLaunch(game("system-nnn-spt", "PARSER_AVAILABLE")))
    }
}
