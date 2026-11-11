package io.github.helterfall.ramen.runtime.systemnnn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemNnnAudioPlaybackGateTest {

    @Test
    fun pauseDuringPreparationKeepsThePlayerPausedUntilResume() {
        val gate = SystemNnnAudioPlaybackGate()
        gate.beginPreparation()

        gate.requestPause()
        assertFalse(gate.markPrepared())
        assertFalse(gate.shouldStartPreparedPlayer())

        gate.requestResume()
        assertTrue(gate.shouldStartPreparedPlayer())
    }

    @Test
    fun resumeBeforePreparationCompletesAllowsAutomaticStart() {
        val gate = SystemNnnAudioPlaybackGate()
        gate.beginPreparation()

        gate.requestPause()
        gate.requestResume()

        assertTrue(gate.markPrepared())
        assertTrue(gate.shouldStartPreparedPlayer())
    }

    @Test
    fun pauseAndResumeAfterPreparationOnlyChangesTheStartPermission() {
        val gate = SystemNnnAudioPlaybackGate()
        gate.beginPreparation()
        assertTrue(gate.markPrepared())
        assertTrue(gate.shouldStartPreparedPlayer())

        gate.requestPause()
        assertFalse(gate.shouldStartPreparedPlayer())

        gate.requestResume()
        assertTrue(gate.shouldStartPreparedPlayer())
    }

    @Test
    fun nextPlaylistSegmentCanBePreparedWithoutLosingALifecyclePause() {
        val gate = SystemNnnAudioPlaybackGate()
        gate.beginPreparation()
        gate.requestPause()
        assertFalse(gate.markPrepared())

        gate.beginPreparation()
        assertFalse(gate.markPrepared())
        assertFalse(gate.shouldStartPreparedPlayer())

        gate.requestResume()
        assertTrue(gate.shouldStartPreparedPlayer())
    }

    @Test
    fun stopResetsPreparationAndPauseState() {
        val gate = SystemNnnAudioPlaybackGate()
        gate.beginPreparation()
        gate.requestPause()
        gate.markPrepared()

        gate.reset()

        assertFalse(gate.isPrepared())
        assertFalse(gate.shouldStartPreparedPlayer())
        gate.beginPreparation()
        assertTrue(gate.markPrepared())
    }
}
