package io.github.helterfall.ramen.inspection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlackCycScriptLoaderTest {

    private val loader = BlackCycScriptLoader()

    @Test
    fun sptIsOpaqueInsideBlackCycArchiveContext() {
        val info = loader.inspect(
            entryName = "scripts/scenario.spt",
            hasMatchingGpkGtbPair = true
        ) ?: error("Expected an opaque Black Cyc SPT classification.")

        assertFalse(info.commandParsingSupported)
        assertTrue(loader.shouldBypassCommandParser(info))
    }

    @Test
    fun sptAloneDoesNotIdentifyBlackCyc() {
        assertNull(
            loader.inspect(
                entryName = "scenario.spt",
                hasMatchingGpkGtbPair = false
            )
        )
    }

    @Test
    fun unrelatedExtensionIsNotClassifiedAsSpt() {
        assertNull(
            loader.inspect(
                entryName = "scenario.bin",
                hasMatchingGpkGtbPair = true
            )
        )
    }
}
