package io.github.helterfall.ramen.runtime.livemaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveMakerTpWordPresentationTest {
    @Test
    fun emptyEventOnlyTextInsStillWaitsForPlayerInput() {
        val result = applyLiveMakerTextBlockWait(
            segments = emptyList(),
            shouldWait = true
        )

        assertEquals(1, result.size)
        assertEquals("", result.single().text)
        assertTrue(result.single().waitForTap)
        assertFalse(result.single().clearBefore)
    }

    @Test
    fun waitFlagIsAttachedAfterTheLastEmbeddedEvent() {
        val event = LiveMakerTpWordEvent(
            name = "CREATECG",
            arguments = listOf("000", "title.gal"),
            isSystem = true
        )
        val result = applyLiveMakerTextBlockWait(
            segments = listOf(
                LiveMakerTextSegment("", eventsBefore = listOf(event))
            ),
            shouldWait = true
        )

        assertEquals(1, result.size)
        assertEquals(listOf(event), result.single().eventsBefore)
        assertTrue(result.single().waitForTap)
    }

    @Test
    fun nonWaitingEventOnlyTextInsCanStillAutoAdvance() {
        assertTrue(
            applyLiveMakerTextBlockWait(emptyList(), shouldWait = false).isEmpty()
        )
        val segments = listOf(
            LiveMakerTextSegment("", eventsBefore = listOf(
                LiveMakerTpWordEvent("MESEND", emptyList())
            ))
        )
        assertEquals(
            segments,
            applyLiveMakerTextBlockWait(segments, shouldWait = false)
        )
    }

    @Test
    fun containingTextInsWaitIsAppliedAfterTheLastDialogueSegment() {
        val result = applyLiveMakerTextBlockWait(
            segments = listOf(
                LiveMakerTextSegment("first", waitForTap = true),
                LiveMakerTextSegment("second")
            ),
            shouldWait = true
        )

        assertEquals(2, result.size)
        assertTrue(result[0].waitForTap)
        assertEquals("second", result[1].text)
        assertTrue(result[1].waitForTap)
    }
}
