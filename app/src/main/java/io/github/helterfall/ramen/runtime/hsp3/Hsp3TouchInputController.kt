package io.github.helterfall.ramen.runtime.hsp3

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** Maps a single pointer within the complete D-pad area to one of eight directions. */
internal object Hsp3DpadMapper {
    private const val CENTER_DEAD_ZONE = 0.22f
    // Diagonal sectors span 45 degrees and set both directional bits.
    private const val DIAGONAL_RATIO = 0.41421357f

    fun directionMask(x: Float, y: Float, width: Float, height: Float): Int {
        if (width <= 0f || height <= 0f) return 0

        // Normalize the axes independently so a non-square touch zone still has
        // a circular dead zone and equal angular sectors.
        val dx = ((x / width) * 2f - 1f).coerceIn(-1f, 1f)
        val dy = ((y / height) * 2f - 1f).coerceIn(-1f, 1f)
        if (dx * dx + dy * dy <= CENTER_DEAD_ZONE * CENTER_DEAD_ZONE) return 0

        val horizontal = abs(dx)
        val vertical = abs(dy)
        val horizontalBit = if (dx < 0f) {
            Hsp3TouchInputController.STICK_LEFT
        } else {
            Hsp3TouchInputController.STICK_RIGHT
        }
        val verticalBit = if (dy < 0f) {
            Hsp3TouchInputController.STICK_UP
        } else {
            Hsp3TouchInputController.STICK_DOWN
        }

        return when {
            vertical < horizontal * DIAGONAL_RATIO -> horizontalBit
            horizontal < vertical * DIAGONAL_RATIO -> verticalBit
            else -> horizontalBit or verticalBit
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
        private const val DPAD_MASK = STICK_LEFT or STICK_UP or STICK_RIGHT or STICK_DOWN
    }
}
