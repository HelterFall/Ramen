package io.github.helterfall.ramen.runtime.livemaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveMakerTpWordEventsTest {
    @Test
    fun createCgArgumentsUseLiveNovelPositionalOrder() {
        val args = LiveMakerTpWordArguments(
            listOf("000", "graphics/bg.gal", "WAIT", "#1", "C", "B", "0", "1", "500")
        )

        assertEquals("000", args.get(0, "NAME"))
        assertEquals("graphics/bg.gal", args.get(1, "SOURCE"))
        assertEquals("WAIT", args.get(2, "MODE"))
        assertEquals("#1", args.get(3, "PARENT"))
        assertEquals("C", args.get(4, "X"))
        assertEquals("B", args.get(5, "Y"))
        assertEquals("0", args.get(6, "Z"))
        assertEquals("1", args.get(7, "WIPE"))
        assertEquals("500", args.get(8, "DURATION"))
    }

    @Test
    fun changeCgUsesShorterPositionalSignature() {
        val args = LiveMakerTpWordArguments(
            listOf("hero", "graphics/hero.gal", "NORMAL", "1", "1000")
        )
        assertEquals("hero", args.get(0, "NAME"))
        assertEquals("graphics/hero.gal", args.get(1, "SOURCE"))
        assertEquals("NORMAL", args.get(2, "MODE"))
        assertEquals("1", args.get(3, "WIPE"))
        assertEquals("1000", args.get(4, "DURATION"))
    }

    @Test
    fun namedArgumentsRemainSupported() {
        val args = LiveMakerTpWordArguments(
            listOf("SOURCE=graphics/bg.gal", "NAME=background", "X=14")
        )
        assertEquals("background", args.get(0, "NAME"))
        assertEquals("graphics/bg.gal", args.get(1, "SOURCE"))
        assertEquals("14", args.get(4, "X"))
    }

    @Test
    fun centerAndEdgePositionsUseImageDimensions() {
        assertEquals(
            300f,
            parseLiveMakerStageCoordinate("C", stageExtent = 800f, objectExtent = 200f, horizontal = true),
            0.001f
        )
        assertEquals(
            500f,
            parseLiveMakerStageCoordinate("R", stageExtent = 800f, objectExtent = 300f, horizontal = true),
            0.001f
        )
        assertEquals(
            400f,
            parseLiveMakerStageCoordinate("B", stageExtent = 600f, objectExtent = 200f, horizontal = false),
            0.001f
        )
        assertEquals(
            32f,
            parseLiveMakerStageCoordinate("32", stageExtent = 800f, objectExtent = 200f, horizontal = true),
            0.001f
        )
    }

    @Test
    fun recognizesConventionalAndJapaneseBackgroundLayers() {
        assertTrue(isLiveMakerBackgroundLayer("000", "graphics/bg.gal"))
        assertTrue(isLiveMakerBackgroundLayer("BG", "graphics/bg.gal"))
        assertTrue(isLiveMakerBackgroundLayer("背景", "graphics/room.gal"))
        assertFalse(isLiveMakerBackgroundLayer("hero", "graphics/character.gal"))
    }
}
