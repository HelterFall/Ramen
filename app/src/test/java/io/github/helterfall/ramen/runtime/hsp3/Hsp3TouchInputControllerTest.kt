package io.github.helterfall.ramen.runtime.hsp3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Hsp3TouchInputControllerTest {
    @Test
    fun slidingFromDownIntoBottomLeftSectorKeepsDownAndAddsLeft() {
        val input = Hsp3TouchInputController()
        input.setDpadPosition(50f, 90f, 100f, 100f)
        assertEquals(Hsp3TouchInputController.STICK_DOWN, input.stickMask())

        input.setDpadPosition(18f, 82f, 100f, 100f)
        val expected = Hsp3TouchInputController.STICK_DOWN or
            Hsp3TouchInputController.STICK_LEFT
        assertEquals(expected, input.stickMask())
        assertTrue(input.isKeyDown(40))
        assertTrue(input.isKeyDown(37))

        input.setDpadMask(0)
        assertEquals(0, input.stickMask())
        assertFalse(input.isKeyDown(40))
        assertFalse(input.isKeyDown(37))
    }

    @Test
    fun slidingFromDownIntoBottomRightSectorKeepsDownAndAddsRight() {
        val input = Hsp3TouchInputController()
        input.setDpadPosition(50f, 90f, 100f, 100f)
        assertEquals(Hsp3TouchInputController.STICK_DOWN, input.stickMask())

        input.setDpadPosition(82f, 82f, 100f, 100f)
        val expected = Hsp3TouchInputController.STICK_DOWN or
            Hsp3TouchInputController.STICK_RIGHT
        assertEquals(expected, input.stickMask())
        assertTrue(input.isKeyDown(40))
        assertTrue(input.isKeyDown(39))

        input.setDpadMask(0)
        assertEquals(0, input.stickMask())
    }

    @Test
    fun mapperSupportsAllEightDirectionsAndHasADeadZone() {
        val input = Hsp3TouchInputController()
        val center = 50f
        val size = 100f

        val samples = listOf(
            Triple(90f, 50f, Hsp3TouchInputController.STICK_RIGHT),
            Triple(90f, 90f, Hsp3TouchInputController.STICK_RIGHT or Hsp3TouchInputController.STICK_DOWN),
            Triple(50f, 90f, Hsp3TouchInputController.STICK_DOWN),
            Triple(10f, 90f, Hsp3TouchInputController.STICK_LEFT or Hsp3TouchInputController.STICK_DOWN),
            Triple(10f, 50f, Hsp3TouchInputController.STICK_LEFT),
            Triple(10f, 10f, Hsp3TouchInputController.STICK_LEFT or Hsp3TouchInputController.STICK_UP),
            Triple(50f, 10f, Hsp3TouchInputController.STICK_UP),
            Triple(90f, 10f, Hsp3TouchInputController.STICK_RIGHT or Hsp3TouchInputController.STICK_UP)
        )
        samples.forEach { (x, y, expected) ->
            input.setDpadPosition(x, y, size, size)
            assertEquals(expected, input.stickMask())
        }

        input.setDpadPosition(center + 4f, center - 3f, size, size)
        assertEquals(0, input.stickMask())
    }

    @Test
    fun dpadPointerUpdatesDoNotClearIndependentlyHeldActionButtons() {
        val input = Hsp3TouchInputController()
        input.setPressed(Hsp3TouchInputController.STICK_Z, true)
        input.setDpadPosition(20f, 80f, 100f, 100f)

        assertTrue(input.isKeyDown(90))
        assertTrue(input.isKeyDown(40))
        assertTrue(input.isKeyDown(37))

        input.setDpadMask(0)
        assertTrue(input.isKeyDown(90))
        assertEquals(Hsp3TouchInputController.STICK_Z, input.stickMask())

        input.setPressed(Hsp3TouchInputController.STICK_Z, false)
        assertEquals(0, input.stickMask())
    }
}
