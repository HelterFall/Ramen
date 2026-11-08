package io.github.helterfall.ramen.runtime.renpy

import org.junit.Assert.assertFalse
import org.junit.Test

class RenPyPlaybackGateTest {
    @Test
    fun embeddedPlaybackRemainsDisabledUntilDeviceSmokeTestPasses() {
        assertFalse(RenPyRuntimeAvailabilityProbe.EMBEDDED_LAUNCH_ENABLED)
    }
}
