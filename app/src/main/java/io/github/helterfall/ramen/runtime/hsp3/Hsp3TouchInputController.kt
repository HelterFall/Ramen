package io.github.helterfall.ramen.runtime.hsp3

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** Maps a pointer to the rectangular cells of a classic 3x3 cross-shaped D-pad. */
internal object Hsp3DpadMapper {
    fun directionMask(x: Float, y: Float, width: Float, height: Float): Int {
        if (width <= 0f || height <= 0f) return 0

        // Cardinal petals are the middle cells of each edge. The corners are
        // explicit diagonal sectors; the center cell is a rectangular dead zone.
        val column = ((x / width) * 3f).toInt().coerceIn(0, 2)
        val row = ((y / height) * 3f).toInt().coerceIn(0, 2)

        return when (row) {
            0 -> when (column) {
                0 -> Hsp3TouchInputController.STICK_UP or Hsp3TouchInputController.STICK_LEFT
                1 -> Hsp3TouchInputController.STICK_UP
                else -> Hsp3TouchInputController.STICK_UP or Hsp3TouchInputController.STICK_RIGHT
            }
            1 -> when (column) {
                0 -> Hsp3TouchInputController.STICK_LEFT
                1 -> 0
                else -> Hsp3TouchInputController.STICK_RIGHT
            }
            else -> when (column) {
                0 -> Hsp3TouchInputController.STICK_DOWN or Hsp3TouchInputController.STICK_LEFT
                1 -> Hsp3TouchInputController.STICK_DOWN
                else -> Hsp3TouchInputController.STICK_DOWN or Hsp3TouchInputController.STICK_RIGHT
            }
        }
    }
}

/** Thread-safe HSP input state shared by the UI pointer handler and VM worker. */
internal class Hsp3TouchInputController : Hsp3InputProvider {
    private val mask = AtomicInteger(0)
    private val mouseX = AtomicInteger(0)
    private val mouseY = AtomicInteger(0)

    override fun stickMask(): Int = mask.get()

    override fun mousePosition(): Pair<Int, Int> = mouseX.get() to mouseY.get()

    fun setMousePosition(x: Int, y: Int) {
        mouseX.set(x.coerceIn(0, Hsp3CanvasRenderer.DEFAULT_WIDTH - 1))
        mouseY.set(y.coerceIn(0, Hsp3CanvasRenderer.DEFAULT_HEIGHT - 1))
    }

    fun setDpadPosition(x: Float, y: Float, width: Float, height: Float) {
        setDpadMask(Hsp3DpadMapper.directionMask(x, y, width, height))
    }

    /** Replaces only the four D-pad bits; action buttons remain independently held. */
    fun setDpadMask(directionMask: Int) {
        val directions = directionMask and DPAD_MASK
        while (true) {
            val previous = mask.get()
            val next = (previous and DPAD_MASK.inv()) or directions
            if (mask.compareAndSet(previous, next)) return
        }
    }

    fun setPressed(bits: Int, pressed: Boolean) {
        while (true) {
            val previous = mask.get()
            val next = if (pressed) previous or bits else previous and bits.inv()
            if (mask.compareAndSet(previous, next)) return
        }
    }

    override fun isKeyDown(keyCode: Int): Boolean {
        val bit = when (keyCode) {
            37 -> STICK_LEFT
            38 -> STICK_UP
            39 -> STICK_RIGHT
            40 -> STICK_DOWN
            13 -> STICK_ENTER
            27 -> STICK_ESCAPE
            32 -> STICK_SPACE
            90 -> STICK_Z
            88 -> STICK_X
            82 -> STICK_R
            else -> 0
        }
        return bit != 0 && (mask.get() and bit) != 0
    }

    companion object {
        const val STICK_LEFT = 1
        const val STICK_UP = 2
        const val STICK_RIGHT = 4
        const val STICK_DOWN = 8
        const val STICK_SPACE = 16
        const val STICK_ENTER = 32
        const val STICK_ESCAPE = 128
        const val STICK_Z = 2048
        const val STICK_X = 4096
        const val STICK_R = 8192
        private const val DPAD_MASK = STICK_LEFT or STICK_UP or STICK_RIGHT or STICK_DOWN
    }
}
