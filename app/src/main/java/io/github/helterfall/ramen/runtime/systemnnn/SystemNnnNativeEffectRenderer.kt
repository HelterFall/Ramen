package io.github.helterfall.ramen.runtime.systemnnn

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pixel-domain implementations for native System-NNN effects that operate on the
 * common screen buffer. Inputs and outputs are ARGB_8888 arrays in top-down order.
 *
 * This renderer deliberately works on immutable inputs: it never mutates a source
 * frame or a saved GET/OVERRAP snapshot owned by the player.
 */
internal object SystemNnnNativeEffectRenderer {
    data class RectI(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = (right - left).coerceAtLeast(0)
        val height: Int get() = (bottom - top).coerceAtLeast(0)
    }

    data class ParticleStamp(
        val x: Float,
        val y: Float,
        val sourceRect: RectI,
        val alpha: Float,
        val scale: Float = 1f
    )

    data class LayerOffset(val x: Int, val y: Int, val scale: Float = 1f, val alpha: Float = 1f)

    fun renderScreenBufferEffect(
        effectId: Int,
        frame: Int,
        parameters: List<Int>,
        source: IntArray,
        width: Int,
        height: Int,
        savedGetBuffer: IntArray? = null,
        transitionSource: IntArray? = null
    ): IntArray? {
        if (!validFrame(source, width, height)) return null
        val duration = (parameters.firstOrNull() ?: 20).coerceIn(1, 9_999)
        val currentFrame = frame.coerceIn(0, duration)
        return when (effectId) {
            2 -> {
                val buffer = savedGetBuffer
                if (buffer != null && buffer.size == source.size) buffer.copyOf()
                else {
                    val red = (parameters.getOrNull(1) ?: 0).coerceIn(0, 255)
                    val green = (parameters.getOrNull(2) ?: red).coerceIn(0, 255)
                    val blue = (parameters.getOrNull(3) ?: green).coerceIn(0, 255)
                    IntArray(source.size) { argb(255, red, green, blue) }
                }
            }
            20 -> light(source, currentFrame, duration, parameters)
            22 -> kagerou(source, width, height, currentFrame, parameters)
            29 -> sunaNoise(source, width, height, currentFrame, duration, parameters)
            31 -> negative(source, currentFrame, duration, parameters)
            43 -> mosaic(source, width, height, currentFrame, parameters)
            50 -> gradation(source, width, height, currentFrame, duration, parameters)
            69 -> hamon(
                source, width, height, currentFrame, duration, parameters, transitionSource
            )
            71 -> hotaru(source, width, height, currentFrame, parameters)
            74 -> bura(source, width, height, currentFrame, duration, parameters)
            100 -> haikeiYureScreenBuffer(source, width, height, currentFrame, parameters)
            73 -> source.copyOf() // GET is captured by the player at the draw boundary.
            else -> null
        }
    }

    /**
     * Native HAIKEIYURE (100), screen mode. MoveScreen() mutates the shared
     * framebuffer in place; pixels in the newly exposed strips are left intact.
     */
    fun haikeiYureScreenBuffer(
        source: IntArray,
        width: Int,
        height: Int,
        frame: Int,
        parameters: List<Int>
    ): IntArray {
        if (!validFrame(source, width, height)) return source.copyOf()
        val offset = SystemNnnVisualEffectMath.haikeiYureOffset(frame, parameters)
        val dx = offset.x.roundToInt()
        val dy = offset.y.roundToInt()
        if ((dx == 0 && dy == 0) || dx <= -width || dx >= width ||
            dy <= -height || dy >= height
        ) return source.copyOf()

        val result = source.copyOf()
        val destinationLeft = max(0, dx)
        val destinationTop = max(0, dy)
        val destinationRight = min(width, width + dx)
        val destinationBottom = min(height, height + dy)
        val copyWidth = (destinationRight - destinationLeft).coerceAtLeast(0)
        if (copyWidth == 0) return result

        for (destinationY in destinationTop until destinationBottom) {
            val sourceY = destinationY - dy
            val sourceX = destinationLeft - dx
            val sourceStart = sourceY * width + sourceX
            val destinationStart = destinationY * width + destinationLeft
            source.copyInto(result, destinationStart, sourceStart, sourceStart + copyWidth)
        }
        return result
    }

    /** Native OverrapBlt: channel-wise weighted sum using 8-bit fixed-point weights. */
    fun blendOverrap(
        start: IntArray,
        end: IntArray,
        startPercent: Int,
        endPercent: Int
    ): IntArray? {
        if (start.size != end.size) return null
        val firstWeight = (startPercent.coerceIn(0, 100) * 256) / 100
        val secondWeight = (endPercent.coerceIn(0, 100) * 256) / 100
        return IntArray(start.size) { index ->
            val a = start[index]
            val b = end[index]
            val alpha = blendChannel(a ushr 24, b ushr 24, firstWeight, secondWeight)
            val red = blendChannel(a ushr 16, b ushr 16, firstWeight, secondWeight)
            val green = blendChannel(a ushr 8, b ushr 8, firstWeight, secondWeight)
            val blue = blendChannel(a, b, firstWeight, secondWeight)
            argb(alpha, red, green, blue)
        }
    }

    /** Native simple-wipe Add: saturating add-in during the first half,
     * then reverse the source roles while fading the original source out. */
    fun addOverrap(start: IntArray, end: IntArray, frame: Int, length: Int): IntArray? {
        if (start.size != end.size) return null
        val total = length.coerceAtLeast(1)
        val step = frame.coerceIn(0, total)
        val half = (total / 2).coerceAtLeast(1)
        val reverse = step >= half
        val denominator = if (reverse) (total - half).coerceAtLeast(1) else half
        val progress = if (reverse) total - step else step
        val weight = ((progress * 256L) / denominator).toInt().coerceIn(0, 256)
        return IntArray(start.size) { index ->
            val first = if (reverse) end[index] else start[index]
            val second = if (reverse) start[index] else end[index]
            fun channel(shift: Int): Int = (
                ((first ushr shift) and 0xFF) +
                    (((second ushr shift) and 0xFF) * weight / 256)
                ).coerceIn(0, 255)
            argb(255, channel(16), channel(8), channel(0))
        }
    }
    fun overrapWeights(frame: Int, length: Int): Pair<Int, Int> {
        val total = length.coerceAtLeast(1)
        val step = frame.coerceIn(0, total)
        val end = (step * 100) / total
        return (100 - end) to end
    }

    /**
     * Applies one of the native CAllSimpleWipe selectors to the two full-screen
     * snapshots. Type 0 delegates to the native CPicture::Overrap fixed-point
     * blend. The remaining selectors use their native wipe family geometry:
     * curtains/slides, radial and clock, directional move/door, patterned
     * masks, blinds, mosaic/random strips, and zoom/center families.
     */
    fun renderOverrapFrame(
        start: IntArray,
        end: IntArray,
        transitionType: Int,
        frame: Int,
        length: Int,
        width: Int,
        height: Int
    ): IntArray? {
        if (start.size != end.size || width <= 0 || height <= 0 ||
            width.toLong() * height.toLong() != start.size.toLong()
        ) return null
        val total = length.coerceAtLeast(1)
        val step = frame.coerceIn(0, total)
        if (step <= 0) return start.copyOf()
        if (step >= total) return end.copyOf()
        val percent = step.toFloat() / total.toFloat()
        if (transitionType == 0 || transitionType !in 0..95) {
            val (startPercent, endPercent) = overrapWeights(step, total)
            return blendOverrap(start, end, startPercent, endPercent)
        }
        if (transitionType == 40) {
            return addOverrap(start, end, step, total)
        }
        val type = transitionType
        val maxDimension = max(width, height).coerceAtLeast(1).toFloat()
        val result = IntArray(start.size)
        for (y in 0 until height) {
            val ny = if (height <= 1) 0f else y.toFloat() / (height - 1)
            for (x in 0 until width) {
                val nx = if (width <= 1) 0f else x.toFloat() / (width - 1)
                val dx = nx - 0.5f
                val dy = ny - 0.5f
                val angle = ((kotlin.math.atan2(dy.toDouble(), dx.toDouble()) + PI) / (2.0 * PI)).toFloat()
                val radius = hypot(dx.toDouble(), dy.toDouble()).toFloat() / 0.7072f
                val reveal = when (type) {
                    1 -> (abs(dx) * 2f) // curtain opens from centre
                    2 -> (abs(dy) * 2f) // curtain2 opens vertically
                    3 -> ((nx * 16f).toInt() % 2) * 0.38f + nx * 0.62f
                    4 -> nx
                    5 -> 1f - nx
                    6, 7 -> if (type == 6) radius else 1f - radius
                    8 -> (radius + 0.12f * sin(angle * 8f * PI.toFloat())) .coerceIn(0f, 1f)
                    9 -> angle
                    in 10..17 -> directionalMoveScore(type - 10, nx, ny)
                    in 18..21 -> if ((type - 18) % 2 == 0) abs(dx) * 2f else abs(dy) * 2f
                    22 -> (abs(dx) + abs(dy)).coerceIn(0f, 1f)
                    23 -> 1f - ny
                    in 24..37 -> {
                        val angleWave = 0.5f + 0.5f * cos(angle * (8 + (type % 4) * 4) * 2f * PI.toFloat())
                        (radius * (0.65f + 0.35f * angleWave)).coerceIn(0f, 1f)
                    }
                    38 -> {
                        val tileX = x / 16
                        val tileY = y / 16
                        randomMix(tileY * 0x45D9F3B xor tileX * 0x119DE1F3).toFloat() /
                            Int.MAX_VALUE.toFloat()
                    }
                    39 -> {
                        val stripe = x / 8
                        (randomMix(stripe * 0x45D9F3B).toFloat() / Int.MAX_VALUE.toFloat() * 0.7f) +
                            (frame.toFloat() / total.toFloat() * 0.3f)
                    }
                    41 -> (nx + ny) * 0.5f
                    in 42..47 -> blindsScore(type - 42, nx, ny)
                    in 48..59 -> if (type < 54) nx else ny
                    60 -> min(nx, ny) * 2f
                    in 61..64 -> {
                        val strips = 4 + type - 61
                        ((x / (width / strips.coerceAtLeast(1)).coerceAtLeast(1)).toFloat() /
                            strips.toFloat()) * 0.7f + ny * 0.3f
                    }
                    in 65..68 -> ((nx + ny * (1 + type - 65) / 4f) / 2f).coerceIn(0f, 1f)
                    in 69..77 -> ((nx * (2 + type % 3) + ny) / (3 + type % 3)).coerceIn(0f, 1f)
                    in 78..79 -> (radius * (0.8f + 0.2f * cos(angle * 12f * PI.toFloat()))).coerceIn(0f, 1f)
                    in 80..83 -> radius
                    in 84..87 -> max(abs(dx), abs(dy)) * 2f
                    in 88..95 -> directionalMoveScore((type - 88) % 8, nx, ny)
                    else -> nx
                }.coerceIn(0f, 1f)
                val index = y * width + x
                result[index] = if (reveal <= percent) end[index] else start[index]
            }
        }
        return result
    }

    private fun directionalMoveScore(direction: Int, x: Float, y: Float): Float =
        when (direction and 7) {
            0 -> x
            1 -> 1f - x
            2 -> y
            3 -> 1f - y
            4 -> ((x + y) * 0.5f)
            5 -> ((1f - x + y) * 0.5f)
            6 -> ((x + 1f - y) * 0.5f)
            else -> ((2f - x - y) * 0.5f)
        }

    private fun blindsScore(pattern: Int, x: Float, y: Float): Float {
        val across = if (pattern % 2 == 0) x else y
        val stripeCount = 4 + pattern / 2
        val stripe = (across * stripeCount).toInt()
        val local = across * stripeCount - stripe
        return (stripe.toFloat() / stripeCount + local * 0.65f / stripeCount).coerceIn(0f, 1f)
    }

    /** LIGHT (20): add an interpolated RGB tint to every pixel in the common buffer. */
    fun light(source: IntArray, frame: Int, duration: Int, parameters: List<Int>): IntArray {
        val red = SystemNnnVisualEffectMath.nativeSpeedValue(
            frame, duration, 0, parameters.getOrNull(1) ?: 0,
            parameters.getOrNull(5) ?: 0, parameters.getOrNull(4) ?: 0
        ).coerceIn(-255, 255)
        val green = SystemNnnVisualEffectMath.nativeSpeedValue(
            frame, duration, 0, parameters.getOrNull(2) ?: red,
            parameters.getOrNull(5) ?: 0, parameters.getOrNull(4) ?: 0
        ).coerceIn(-255, 255)
        val blue = SystemNnnVisualEffectMath.nativeSpeedValue(
            frame, duration, 0, parameters.getOrNull(3) ?: green,
            parameters.getOrNull(5) ?: 0, parameters.getOrNull(4) ?: 0
        ).coerceIn(-255, 255)
        return IntArray(source.size) { index ->
            val pixel = source[index]
            argb(
                pixel ushr 24,
                ((pixel ushr 16 and 0xFF) + red).coerceIn(0, 255),
                ((pixel ushr 8 and 0xFF) + green).coerceIn(0, 255),
                ((pixel and 0xFF) + blue).coerceIn(0, 255)
            )
        }
    }

    /** NEGA (31): native negative interpolation around the channel midpoint 128. */
    fun negative(source: IntArray, frame: Int, duration: Int, parameters: List<Int>): IntArray {
        // Native effectNega.cpp converts percentages to the signed contrast
        // coefficient first, then feeds those endpoints to CCalcuSpeed.
        val startFactor = (((parameters.getOrNull(4) ?: 0) * -254 * 2) / 100) + 254
        val endFactor = (((parameters.getOrNull(3) ?: 100) * -254 * 2) / 100) + 254
        val negativeFactor = SystemNnnVisualEffectMath.nativeSpeedValue(
            frame, duration, startFactor, endFactor,
            parameters.getOrNull(2) ?: 0, parameters.getOrNull(1) ?: 0
        ).coerceIn(-254, 254)
        return IntArray(source.size) { index ->
            val pixel = source[index]
            argb(
                pixel ushr 24,
                negativeChannel(pixel ushr 16 and 0xFF, negativeFactor),
                negativeChannel(pixel ushr 8 and 0xFF, negativeFactor),
                negativeChannel(pixel and 0xFF, negativeFactor)
            )
        }
    }

    /** GRADATION (50): two native endpoint colours with a scanline progression. */
    fun gradation(
        source: IntArray,
        width: Int,
        height: Int,
        frame: Int,
        duration: Int,
        parameters: List<Int>
    ): IntArray {
        if (!validFrame(source, width, height)) return source.copyOf()
        val speedType = parameters.getOrNull(7) ?: 0
        val subFrame = parameters.getOrNull(8) ?: 0

        // effectGradation.cpp calculates two independently animated RGB
        // endpoints: p9..11 -> p1..3 and p12..14 -> p4..6.
        fun endpoint(startIndex: Int, endIndex: Int): SystemNnnVisualColor =
            SystemNnnVisualColor(
                SystemNnnVisualEffectMath.nativeSpeedValue(
                    frame, duration.coerceAtLeast(1),
                    parameters.getOrNull(startIndex) ?: 0,
                    parameters.getOrNull(endIndex) ?: 0,
                    subFrame, speedType
                ),
                SystemNnnVisualEffectMath.nativeSpeedValue(
                    frame, duration.coerceAtLeast(1),
                    parameters.getOrNull(startIndex + 1) ?: 0,
                    parameters.getOrNull(endIndex + 1) ?: 0,
                    subFrame, speedType
                ),
                SystemNnnVisualEffectMath.nativeSpeedValue(
                    frame, duration.coerceAtLeast(1),
                    parameters.getOrNull(startIndex + 2) ?: 0,
                    parameters.getOrNull(endIndex + 2) ?: 0,
                    subFrame, speedType
                )
            )

        val left = endpoint(9, 1)
        val right = endpoint(12, 4)
        val startY = (parameters.getOrNull(15) ?: 0).coerceIn(0, height)
        val spanY = (parameters.getOrNull(16) ?: height).coerceIn(0, height - startY)
        val result = source.copyOf()
        if (spanY <= 0) return result

        // Native Print() performs one integer RGB interpolation for each row,
        // using i/printSizeY (not i/(printSizeY-1)), then adds that color to
        // every pixel in the row. There is no horizontal interpolation.
        for (rowIndex in 0 until spanY) {
            val red = left.red + ((right.red - left.red) * rowIndex) / spanY
            val green = left.green + ((right.green - left.green) * rowIndex) / spanY
            val blue = left.blue + ((right.blue - left.blue) * rowIndex) / spanY
            val row = (startY + rowIndex) * width
            for (x in 0 until width) {
                val index = row + x
                val pixel = source[index]
                result[index] = argb(
                    pixel ushr 24,
                    ((pixel ushr 16 and 0xFF) + red).coerceIn(0, 255),
                    ((pixel ushr 8 and 0xFF) + green).coerceIn(0, 255),
                    ((pixel and 0xFF) + blue).coerceIn(0, 255)
                )
            }
        }
        return result
    }

    /** KAGEROU (22): scanline refraction driven by three native cosine waves. */
    fun kagerou(
        source: IntArray,
        width: Int,
        height: Int,
        frame: Int,
        parameters: List<Int>
    ): IntArray {
        if (!validFrame(source, width, height)) return source.copyOf()
        val startY = (parameters.getOrNull(1) ?: 0).coerceIn(0, height)
        val spanY = (parameters.getOrNull(2) ?: height).coerceIn(0, height - startY)
        val startWait = (parameters.getOrNull(3) ?: 0).coerceAtLeast(0)
        val totalMulti = if (startWait > 0 && frame < startWait) {
            frame.toDouble() / startWait.toDouble()
        } else {
            1.0
        }
        val heightScale = (parameters.getOrNull(4) ?: 100) / 100.0
        val widthScale = (parameters.getOrNull(5) ?: 100) / 100.0
        val speedScale = (parameters.getOrNull(6) ?: 100) / 100.0
        val h1 = (parameters.getOrNull(7) ?: 0) * heightScale
        val h2 = (parameters.getOrNull(10) ?: 0) * heightScale
        val h3 = (parameters.getOrNull(13) ?: 0) * heightScale
        val w1 = (parameters.getOrNull(8) ?: 1) * widthScale
        val w2 = (parameters.getOrNull(11) ?: 1) * widthScale
        val w3 = (parameters.getOrNull(14) ?: 1) * widthScale
        val speed1 = (parameters.getOrNull(9) ?: 0) * speedScale
        val speed2 = (parameters.getOrNull(12) ?: 0) * speedScale
        val speed3 = (parameters.getOrNull(15) ?: 0) * speedScale
        val twoPiOverHeight = 2.0 * PI / height.coerceAtLeast(1)
        val result = source.copyOf()
        val endY = min(height, startY + spanY)
        for (y in startY until endY) {
            val yValue = y.toDouble()
            val theta1 = (yValue * w1 + speed1 * frame) * twoPiOverHeight
            val theta2 = (yValue * w2 - speed2 * frame) * twoPiOverHeight
            val theta3 = (yValue * w3 - speed3 * frame) * twoPiOverHeight
            val dx = (totalMulti * (
                cos(theta1) * h1 + cos(theta2) * h2 + cos(theta3) * h3
            ) + 0.5).toInt()
            val row = y * width
            for (x in 0 until width) {
                val sourceX = Math.floorMod(x - dx, width)
                result[row + x] = source[row + sourceX]
            }
        }
        return result
    }

    /** MOZAIC (43): native block mosaic, sampling one source pixel per tile. */
    fun mosaic(
        source: IntArray,
        width: Int,
        height: Int,
        frame: Int,
        parameters: List<Int>
    ): IntArray {
        if (!validFrame(source, width, height)) return source.copyOf()
        val blockWidth = (parameters.getOrNull(1) ?: 16).coerceIn(1, width)
        val blockHeight = (parameters.getOrNull(2) ?: 16).coerceIn(1, height)
        val left = (parameters.getOrNull(3) ?: 0).coerceIn(0, width)
        val top = (parameters.getOrNull(4) ?: 0).coerceIn(0, height)
        val right = (parameters.getOrNull(5) ?: width).coerceIn(left, width)
        val bottom = (parameters.getOrNull(6) ?: height).coerceIn(top, height)
        val result = source.copyOf()
        // Native PrintMozaicMain only processes complete cells; the source
        // sample for each cell is a pseudo-random offset inside that same cell.
        val cellsX = (right - left) / blockWidth
        val cellsY = (bottom - top) / blockHeight
        for (cellY in 0 until cellsY) {
            for (cellX in 0 until cellsX) {
                val x = left + cellX * blockWidth
                val y = top + cellY * blockHeight
                val seed = randomMix(frame * 0x45D9F3B xor cellY * 0x119DE1F3 xor cellX * 0x3449F5)
                val sampleX = x + floorMod(seed, blockWidth)
                val sampleY = y + floorMod(randomMix(seed xor 0x1B873593), blockHeight)
                val sample = source[sampleY * width + sampleX]
                for (yy in y until y + blockHeight) {
                    val row = yy * width
                    for (xx in x until x + blockWidth) result[row + xx] = sample
                }
            }
        }
        return result
    }

    /** HAMON (69): radial ripple of the captured screen, optionally seeded by a second snapshot. */
    fun hamon(
        source: IntArray,
        width: Int,
        height: Int,
        frame: Int,
        duration: Int,
        parameters: List<Int>,
        transitionSource: IntArray? = null
    ): IntArray {
        if (!validFrame(source, width, height)) return source.copyOf()
        val centerX = (parameters.getOrNull(2) ?: width / 2).coerceIn(0, width - 1)
        val centerY = (parameters.getOrNull(3) ?: height / 2).coerceIn(0, height - 1)
        val power = (parameters.getOrNull(4) ?: 0).coerceAtLeast(0).toDouble()
        val waveLength = (parameters.getOrNull(5) ?: 1).coerceAtLeast(1).toDouble()
        val speed = (parameters.getOrNull(6) ?: 1).toDouble()
        val phaseSpeed = (parameters.getOrNull(7) ?: 0).toDouble()
        val decayPercent = (parameters.getOrNull(8) ?: 100).coerceIn(0, 100)
        val aspect = (parameters.getOrNull(9) ?: 100).coerceAtLeast(1) / 100.0
        val startY = (parameters.getOrNull(10) ?: 0).coerceIn(0, height)
        val progress = (frame.toDouble() / duration.coerceAtLeast(1)).coerceIn(0.0, 1.0)
        val result = IntArray(source.size)
        val second = transitionSource?.takeIf { it.size == source.size }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                if (y < startY) {
                    result[index] = source[index]
                    continue
                }
                val dx = x - centerX
                val dy = (y - centerY) * aspect
                val radius = hypot(dx.toDouble(), dy)
                val envelope = if (decayPercent == 0) 1.0
                    else (1.0 - radius / (max(width, height) * decayPercent / 100.0).coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
                val phase = (radius - frame * speed + frame * phaseSpeed) * (2.0 * PI / waveLength)
                val displacement = (cos(phase) * power * envelope * (1.0 - progress * 0.35)).roundToInt()
                val srcX = (x - displacement).coerceIn(0, width - 1)
                val srcY = (y - (sin(phase) * displacement * 0.15).roundToInt()).coerceIn(0, height - 1)
                val warped = source[srcY * width + srcX]
                result[index] = if (second == null) warped else {
                    blendPixel(transitionSource[index], warped, progress)
                }
            }
        }
        return result
    }

    /** HOTARU (71): additive firefly glow particles painted into the common buffer. */
    /** HOTARU (71): deterministic replay of native 256-unit particles and 8x8 glow. */
    fun hotaru(
        source: IntArray,
        width: Int,
        height: Int,
        frame: Int,
        parameters: List<Int>
    ): IntArray {
        if (!validFrame(source, width, height)) return source.copyOf()
        val requestedCount = (parameters.getOrNull(1) ?: 100).coerceAtLeast(0)
        val workStart = (parameters.getOrNull(13) ?: 0).coerceIn(0, 600)
        val count = min(requestedCount, 600 - workStart)
        if (count <= 0) return source.copyOf()

        val originX = parameters.getOrNull(2) ?: width
        val originY = parameters.getOrNull(3) ?: (height - 180)
        val spanX = (parameters.getOrNull(4) ?: 100).coerceAtLeast(1)
        val spanY = (parameters.getOrNull(5) ?: 100).coerceAtLeast(1)
        val baseSpeedX = parameters.getOrNull(6) ?: -2
        val baseSpeedY = parameters.getOrNull(7) ?: -3
        val red = parameters.getOrNull(8) ?: 255
        val green = parameters.getOrNull(9) ?: 255
        val blue = parameters.getOrNull(10) ?: 255
        val loop = (parameters.getOrNull(11) ?: 0) != 0
        val shakePeriod = (parameters.getOrNull(15) ?: 15).coerceAtLeast(1)
        val shakeAngle = parameters.getOrNull(16) ?: -1
        val shakeWidth = (parameters.getOrNull(17) ?: 20).coerceAtLeast(0)
        val duration = (parameters.firstOrNull() ?: 20).coerceAtLeast(1)
        val fadePercent = (
            100 + ((parameters.getOrNull(14) ?: 100) - 100) * frame.coerceIn(0, duration) / duration
        ).coerceIn(0, 100)
        val clipLeft = 4
        val clipTop = 4
        val clipRight = width - 4
        val clipBottom = height - 4
        if (clipRight <= clipLeft || clipBottom <= clipTop) return source.copyOf()

        val waveCos = if (shakeAngle == -1) 0.2 else 0.2 * cos(shakeAngle * 2.0 * PI / 360.0)
        val waveSin = if (shakeAngle == -1) 0.0 else 0.2 * sin(shakeAngle * 2.0 * PI / 360.0)
        val result = source.copyOf()

        repeat(count) { particle ->
            val particleId = workStart + particle
            val seed = randomMix(particleId * 0x45D9F3B xor 0x71C0A2)
            var x = 0
            var y = 0
            var speedX = 0
            var speedY = 0
            var thetaMax = 1
            var theta = 0

            fun initialize(initSeed: Int) {
                val xNoise = randomMix(initSeed xor 0x1B873593)
                val yNoise = randomMix(initSeed xor 0x3449F5)
                x = (originX + floorMod(xNoise, spanX)) * 256
                y = (originY + floorMod(yNoise, spanY)) * 256

                speedX = baseSpeedX * 256
                speedY = baseSpeedY * 256
                val divX = max(1, kotlin.math.abs(speedX / 2))
                val divY = max(1, kotlin.math.abs(speedY / 2))
                speedX += floorMod(randomMix(initSeed xor 0x7F4A), divX) - divX / 2
                speedY += floorMod(randomMix(initSeed xor 0x1947), divY) - divY / 2

                thetaMax = shakePeriod + floorMod(randomMix(initSeed xor 0xB5297A4D.toInt()), shakePeriod * 4)
                theta = floorMod(randomMix(initSeed xor 0x2C1B3C6D), thetaMax)
            }

            initialize(seed)
            var alive = true
            for (tick in 0 until frame.coerceIn(0, 10_000)) {
                x += speedX
                y += speedY
                theta += 1
                if (theta >= thetaMax) theta -= thetaMax
                val wave = cos(theta.toDouble() * 2.0 * PI / thetaMax.toDouble()) * shakeWidth * 256.0
                val deltaX: Int
                val deltaY: Int
                if (shakeAngle == -1) {
                    if (kotlin.math.abs(speedX) > kotlin.math.abs(speedY)) {
                        deltaX = 0
                        deltaY = (wave * 0.2 + 0.5).toInt()
                    } else {
                        deltaX = (wave * 0.2 + 0.5).toInt()
                        deltaY = 0
                    }
                } else {
                    deltaX = (wave * waveCos + 0.5).toInt()
                    deltaY = (wave * waveSin + 0.5).toInt()
                }
                x += deltaX
                y += deltaY

                val px = x / 256
                val py = y / 256
                val outside = (if (speedX < 0) px < clipLeft else px > clipRight) ||
                    (if (speedY < 0) py < clipTop else py > clipBottom)
                if (outside) {
                    if (loop) initialize(randomMix(seed xor (tick * 0x119DE1F3) xor 0x4A39B70D))
                    else {
                        alive = false
                        break
                    }
                }
            }
            if (!alive) return@repeat

            val centerX = x / 256
            val centerY = y / 256
            if (centerX !in clipLeft..clipRight || centerY !in clipTop..clipBottom) return@repeat

            for (ky in 0 until 8) for (kx in 0 until 8) {
                val dx = ((kx - 4) * 2) + 1
                val dy = ((ky - 4) * 2) + 1
                val falloff = (dx * dx + dy * dy) * 2
                val px = centerX + kx - 4
                val py = centerY + ky - 4
                if (px !in 0 until width || py !in 0 until height) continue

                fun delta(channel: Int): Int {
                    val intensity = ((kotlin.math.abs(channel) - falloff).coerceAtLeast(0) * fadePercent) / 100
                    return if (channel >= 0) intensity else -intensity
                }
                val index = py * width + px
                val old = result[index]
                result[index] = argb(
                    old ushr 24,
                    ((old ushr 16 and 0xFF) + delta(red)).coerceIn(0, 255),
                    ((old ushr 8 and 0xFF) + delta(green)).coerceIn(0, 255),
                    ((old and 0xFF) + delta(blue)).coerceIn(0, 255)
                )
            }
        }
        return result
    }

    /** BURA (74): native zoom-from/zoom-to around an interpolated screen centre. */
    fun bura(
        source: IntArray,
        width: Int,
        height: Int,
        frame: Int,
        duration: Int,
        parameters: List<Int>
    ): IntArray {
        if (!validFrame(source, width, height)) return source.copyOf()
        val t = frame.toDouble().coerceIn(0.0, duration.coerceAtLeast(1).toDouble()) /
            duration.coerceAtLeast(1).toDouble()
        val endX = (parameters.getOrNull(1) ?: width / 2).toDouble()
        val endY = (parameters.getOrNull(2) ?: height / 2).toDouble()
        val startMagnification = ((parameters.getOrNull(4) ?: 100) * 100).coerceAtLeast(1)
        val endMagnification = ((parameters.getOrNull(3) ?: 100) * 100).coerceAtLeast(1)
        val magnificationHundredths = SystemNnnVisualEffectMath.nativeSpeedValue(
            frame, duration.coerceAtLeast(1), startMagnification, endMagnification,
            parameters.getOrNull(8) ?: 0, parameters.getOrNull(7) ?: 0
        ).coerceAtLeast(10_000)
        val startAlpha = (parameters.getOrNull(6) ?: 100).coerceIn(0, 100)
        val endAlpha = (parameters.getOrNull(5) ?: 100).coerceIn(0, 100)
        val opacity = SystemNnnVisualEffectMath.nativeSpeedValue(
            frame, duration.coerceAtLeast(1), startAlpha, endAlpha,
            parameters.getOrNull(10) ?: 0, parameters.getOrNull(9) ?: 0
        ).coerceIn(0, 100) / 100.0
        val startX = (parameters.getOrNull(11) ?: endX.toInt()).toDouble()
        val startY = (parameters.getOrNull(12) ?: endY.toInt()).toDouble()
        val centerX = startX + (endX - startX) * t
        val centerY = startY + (endY - startY) * t
        val magnification = magnificationHundredths / 10_000.0
        val result = IntArray(source.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val sx = (centerX + (x - centerX) / magnification).roundToInt().coerceIn(0, width - 1)
                val sy = (centerY + (y - centerY) / magnification).roundToInt().coerceIn(0, height - 1)
                val mapped = source[sy * width + sx]
                result[y * width + x] = blendPixel(source[y * width + x], mapped, opacity)
            }
        }
        return result
    }

    /** SUNA (29): native 128x64 noise cells blended into a bounded viewport rectangle. */
    fun sunaNoise(
        source: IntArray,
        width: Int,
        height: Int,
        frame: Int,
        duration: Int,
        parameters: List<Int>
    ): IntArray {
        if (!validFrame(source, width, height)) return source.copyOf()
        var left = (parameters.getOrNull(6) ?: 0).coerceIn(0, width)
        var top = (parameters.getOrNull(7) ?: 0).coerceIn(0, height)
        val right = (left + (parameters.getOrNull(8) ?: width).coerceAtLeast(0)).coerceAtMost(width)
        val bottom = (top + (parameters.getOrNull(9) ?: height).coerceAtLeast(0)).coerceAtMost(height)
        if (right <= left || bottom <= top) return source.copyOf()

        val transparent = (parameters.getOrNull(1) ?: 0) != 0
        val startPercent = (parameters.getOrNull(3) ?: 100).coerceIn(0, 100)
        val endPercent = (parameters.getOrNull(2) ?: 100).coerceIn(0, 100)
        val blendPercent = if (transparent) {
            SystemNnnVisualEffectMath.nativeSpeedValue(
                frame, duration.coerceAtLeast(1), startPercent, endPercent,
                parameters.getOrNull(5) ?: 0, parameters.getOrNull(4) ?: 0
            ).coerceIn(0, 100)
        } else 100
        val weight = blendPercent * 256 / 100
        val result = source.copyOf()
        // The native mask is an 8x8 texture expanded to 2x2 pixel blocks and
        // tiled in 128x128 screen patches. Use a fixed seed per tile/frame so
        // animation is stable for the entire presentation frame.
        val tileColumns = (right - left + 127) / 128
        for (y in top until bottom) {
            val localY = y - top
            val tileY = localY / 128
            val inTileY = localY % 128
            for (x in left until right) {
                val localX = x - left
                val tileX = localX / 128
                val inTileX = localX % 128
                val tile = tileY * tileColumns + tileX
                // Native Suna stretches a random 64x64 patch from its 128x128
                // texture to each 128x128 tile (2x source-pixel expansion).
                val sourceSeed = randomMix(tile * 0x45D9F3B xor frame * 0x3449F5)
                val sourceStartX = sourceSeed and 63
                val sourceStartY = randomMix(sourceSeed xor 0x119DE1F3) and 63
                val sourceX = sourceStartX + inTileX / 2
                val sourceY = sourceStartY + inTileY / 2
                val noiseIndex = sourceY * 128 + sourceX
                val noise = randomMix(noiseIndex * 0x119DE1F3)
                val gray = noise and 0xFF
                val index = y * width + x
                val old = source[index]
                result[index] = argb(
                    old ushr 24,
                    blendChannel(old ushr 16, gray, 256 - weight, weight),
                    blendChannel(old ushr 8, gray, 256 - weight, weight),
                    blendChannel(old, gray, 256 - weight, weight)
                )
            }
        }
        return result
    }

    /**
     * SUNA (29) has no source bitmap: native Print paints its random 64x64
     * noise texture into 128x128 tiles of the shared framebuffer at this
     * layer's position. This deterministic overlay preserves that composition
     * point and native rectangle/strength parameters without depending on a
     * game-provided bitmap.
     */
    fun sunaNoiseOverlay(
        width: Int,
        height: Int,
        frame: Int,
        parameters: List<Int>
    ): IntArray {
        if (width <= 0 || height <= 0) return IntArray(0)

        val left = ((parameters.getOrNull(6) ?: 0) and -8).coerceIn(0, width)
        val top = ((parameters.getOrNull(7) ?: 0) and -2).coerceIn(0, height)
        val requestedWidth = ((parameters.getOrNull(8) ?: width) and -8).coerceAtLeast(0)
        val requestedHeight = ((parameters.getOrNull(9) ?: height) and -2).coerceAtLeast(0)
        val right = min(width, left + requestedWidth)
        val bottom = min(height, top + requestedHeight)
        if (right <= left || bottom <= top) return IntArray(width * height)

        val configuredDuration = parameters.getOrNull(0) ?: 0
        val countMax = configuredDuration.coerceAtLeast(1)
        // CEffectSuna::Calcu clamps count to para[0]; a zero duration therefore
        // keeps the effect at its start percentage while the noise texture changes.
        val count = if (configuredDuration <= 0) 0 else frame.coerceIn(0, countMax)
        val transparentMode = (parameters.getOrNull(1) ?: 0) != 0
        val strength = if (transparentMode) {
            SystemNnnVisualEffectMath.nativeSpeedValue(
                count = count,
                countMax = countMax,
                startData = (parameters.getOrNull(3) ?: 100).coerceIn(0, 100),
                endData = (parameters.getOrNull(2) ?: 100).coerceIn(0, 100),
                delayCount = parameters.getOrNull(5) ?: 0,
                speedType = parameters.getOrNull(4) ?: 0
            ).coerceIn(0, 100)
        } else {
            100
        }
        val alpha = strength * 255 / 100
        if (alpha == 0) return IntArray(width * height)

        val output = IntArray(width * height)
        val tileColumns = (right - left + 127) / 128
        for (y in top until bottom) {
            val localY = y - top
            val tileY = localY / 128
            val inTileY = localY % 128
            for (x in left until right) {
                val localX = x - left
                val tileX = localX / 128
                val inTileX = localX % 128
                val tile = tileY * tileColumns + tileX
                val tileSeed = randomMix(tile * 0x45D9F3B xor frame * 0x3449F5)
                val sourceStartX = tileSeed and 63
                val sourceStartY = randomMix(tileSeed xor 0x119DE1F3) and 63
                val sourceX = sourceStartX + inTileX / 2
                val sourceY = sourceStartY + inTileY / 2
                val textureIndex = sourceY * 128 + sourceX
                val gray = randomMix(textureIndex * 0x119DE1F3 xor tileSeed) and 0xFF
                output[y * width + x] = argb(alpha, gray, gray, gray)
            }
        }
        return output
    }

    /** SUNA source-mask helper retained for offline tile-dissolve previews. */
    fun sunaDissolve(
        source: IntArray,
        width: Int,
        height: Int,
        frame: Int,
        parameters: List<Int>
    ): IntArray {
        if (!validFrame(source, width, height)) return source.copyOf()
        val duration = (parameters.firstOrNull() ?: 90).coerceAtLeast(1)
        val startAlpha = (parameters.getOrNull(6) ?: 0).coerceIn(0, 100)
        val endAlpha = (parameters.getOrNull(2) ?: 100).coerceIn(0, 100)
        val ratio = SystemNnnVisualEffectMath.nativeSpeedValue(
            frame.coerceIn(0, duration), duration, startAlpha, endAlpha,
            parameters.getOrNull(5) ?: 0, parameters.getOrNull(4) ?: 0
        ).coerceIn(0, 100) / 100f
        val tileSize = 8
        val result = IntArray(source.size)
        val tilesX = (width + tileSize - 1) / tileSize
        val tilesY = (height + tileSize - 1) / tileSize
        for (ty in 0 until tilesY) for (tx in 0 until tilesX) {
            val tile = ty * tilesX + tx
            val threshold = (randomMix(tile xor 0x2C1B3C6D) ushr 8) / 16_777_216f
            if (threshold > ratio) continue
            for (y in ty * tileSize until min(height, (ty + 1) * tileSize)) {
                val row = y * width
                for (x in tx * tileSize until min(width, (tx + 1) * tileSize)) {
                    val pixel = source[row + x]
                    result[row + x] = (pixel and 0x00FFFFFF) or
                        (((pixel ushr 24) * ratio).roundToInt().coerceIn(0, 255) shl 24)
                }
            }
        }
        return result
    }

    /** KE (27): deterministic native sprite-sheet particle positions for the layer painter. */
    fun keParticles(
        bitmapWidth: Int,
        bitmapHeight: Int,
        frame: Int,
        parameters: List<Int>,
        screenWidth: Int = bitmapWidth,
        screenHeight: Int = bitmapHeight
    ): List<ParticleStamp> {
        if (bitmapWidth <= 0 || bitmapHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) return emptyList()
        val cellWidth = (parameters.getOrNull(1) ?: 32).coerceIn(1, bitmapWidth)
        val cellHeight = (parameters.getOrNull(2) ?: 32).coerceIn(1, bitmapHeight)
        val particleCount = (parameters.getOrNull(3) ?: 1).coerceIn(1, 16)
        val attachMask = parameters.getOrNull(4) ?: 0
        val spriteColumns = (parameters.getOrNull(5) ?: 1).coerceIn(1, (bitmapWidth / cellWidth).coerceAtLeast(1))
        val animationCells = (parameters.getOrNull(6) ?: 1).coerceIn(1, 64)
        val animationSpeed = (parameters.getOrNull(7) ?: 1).coerceAtLeast(1)
        val visibleBase = (parameters.getOrNull(8) ?: 30).coerceAtLeast(1)
        val hiddenBase = (parameters.getOrNull(9) ?: 0).coerceAtLeast(0)
        val jitter = (parameters.getOrNull(10) ?: 0).coerceIn(0, 64)
        val edgeChangeProbability = (parameters.getOrNull(11) ?: 0).coerceIn(0, 10_000)
        val rows = (bitmapHeight / cellHeight).coerceAtLeast(1)
        val totalFrames = frame.coerceIn(0, 10_000)

        return List(particleCount) { index ->
            val seed = randomMix(index * 0x45D9F3B xor 0x27C0FFEE)
            val showSpan = (visibleBase / 2 + floorMod(randomMix(seed xor 0x1947), visibleBase)).coerceAtLeast(1)
            val hideSpan = if (hiddenBase == 0) 1 else
                (hiddenBase / 2 + floorMod(randomMix(seed xor 0x7F4A), hiddenBase)).coerceAtLeast(1)
            val startVisible = floorMod(randomMix(seed xor 0xB5297A4D.toInt()), visibleBase + hiddenBase) < visibleBase
            val cycleLength = showSpan + hideSpan
            val phase = floorMod(totalFrames + if (startVisible) 0 else showSpan, cycleLength)
            val visible = phase < showSpan

            var x = floorMod(seed, screenWidth)
            var y = floorMod(randomMix(seed xor 0x3449F5), screenHeight)
            var direction = chooseKeEdge(attachMask, seed)
            for (tick in 0 until totalFrames) {
                if (jitter > 0) {
                    val movementSeed = randomMix(seed xor (tick * 0x119DE1F3))
                    x = (x + floorMod(movementSeed, jitter * 2 + 1) - jitter).coerceIn(0, screenWidth - 1)
                    y = (y + floorMod(randomMix(movementSeed xor 0x1B873593), jitter * 2 + 1) - jitter)
                        .coerceIn(0, screenHeight - 1)
                }
                if (direction != 0 && edgeChangeProbability > 0) {
                    val changeSeed = randomMix(seed xor (tick * 0x45D9F3B))
                    if (floorMod(changeSeed, 10_000) < edgeChangeProbability) {
                        direction = chooseKeEdge(attachMask, changeSeed)
                    }
                }
                when (direction) {
                    1 -> y = cellHeight / 2
                    2 -> x = screenWidth - cellWidth / 2
                    3 -> y = screenHeight - cellHeight / 2
                    4 -> x = cellWidth / 2
                }
            }

            val cel = floorMod(totalFrames / animationSpeed, animationCells)
            val sourceColumn = index % spriteColumns
            val sourceRow = cel % rows
            ParticleStamp(
                x.toFloat(), y.toFloat(),
                RectI(
                    sourceColumn * cellWidth, sourceRow * cellHeight,
                    min(bitmapWidth, (sourceColumn + 1) * cellWidth),
                    min(bitmapHeight, (sourceRow + 1) * cellHeight)
                ),
                if (visible) 1f else 0f
            )
        }.filter { it.alpha > 0f && it.sourceRect.width > 0 && it.sourceRect.height > 0 }
    }

    private fun chooseKeEdge(mask: Int, seed: Int): Int {
        if (mask == 0) return 0
        val allowed = (0 until 4).mapNotNull { index ->
            val bit = 1 shl index
            if ((mask and bit) != 0) index + 1 else null
        }
        return allowed.getOrNull(floorMod(seed, allowed.size.coerceAtLeast(1))) ?: 0
    }

    /** RAIN2 (56): repeated layer placements with native per-frame translation. */
    fun rain2Offsets(
        frame: Int,
        parameters: List<Int>,
        screenWidth: Int,
        screenHeight: Int,
        bitmapWidth: Int = screenWidth,
        bitmapHeight: Int = screenHeight
    ): List<LayerOffset> {
        val count = (parameters.getOrNull(3) ?: 2).coerceIn(1, 4)
        val baseSpeedX = (parameters.getOrNull(1) ?: 5) * 100
        val baseSpeedY = (parameters.getOrNull(2) ?: 30) * 100
        val baseVariationX = (parameters.getOrNull(4) ?: 3) * 100
        val baseVariationY = (parameters.getOrNull(5) ?: 0) * 100
        val nextPercent = (parameters.getOrNull(6) ?: 50).coerceIn(0, 100)
        val randomInterval = (parameters.getOrNull(7) ?: 100).coerceAtLeast(1)
        val minX = min(bitmapWidth, screenWidth).coerceAtLeast(1)
        val minY = min(bitmapHeight, screenHeight).coerceAtLeast(1)
        val ticks = frame.coerceIn(0, 10_000)

        val speedX = MutableList(count) { 0 }
        val speedY = MutableList(count) { 0 }
        val randomX = MutableList(count) { 0 }
        val randomY = MutableList(count) { 0 }
        speedX[0] = baseSpeedX
        speedY[0] = baseSpeedY
        randomX[0] = baseVariationX
        randomY[0] = baseVariationY
        for (index in 1 until count) {
            speedX[index] = speedX[index - 1] * nextPercent / 100
            speedY[index] = speedY[index - 1] * nextPercent / 100
            randomX[index] = randomX[index - 1] * nextPercent / 100
            randomY[index] = randomY[index - 1] * nextPercent / 100
        }

        val x = MutableList(count) { index ->
            floorMod(randomMix(index * 0x119DE1F3), bitmapWidth.coerceAtLeast(1)) * 100
        }
        val y = MutableList(count) { index ->
            floorMod(randomMix(index * 0x3449F5), bitmapHeight.coerceAtLeast(1)) * 100
        }

        for (tick in 0 until ticks) {
            // Native Calcu updates every X velocity before every Y velocity,
            // then advances and wraps all placements.
            for (index in 0 until count) {
                val seed = randomMix(index * 0x45D9F3B xor (tick * 0x119DE1F3))
                val variation = randomX[index]
                if (variation > 0 && floorMod(seed, randomInterval) == 0) {
                    var delta = floorMod(randomMix(seed xor 0x1B873593), variation)
                    if ((randomMix(seed xor 0x3449F5) % 100) < 50) delta = -delta
                    speedX[index] = (speedX[index] + delta).coerceIn(
                        baseSpeedX - baseVariationX, baseSpeedX + baseVariationX
                    )
                }
            }
            for (index in 0 until count) {
                val seed = randomMix(index * 0x7F4A xor (tick * 0x3449F5))
                val variation = randomY[index]
                if (variation > 0 && floorMod(seed, randomInterval) == 0) {
                    var delta = floorMod(randomMix(seed xor 0xB5297A4D.toInt()), variation)
                    if ((randomMix(seed xor 0x1947) % 100) < 50) delta = -delta
                    speedY[index] = (speedY[index] + delta).coerceIn(
                        baseSpeedY - baseVariationY, baseSpeedY + baseVariationY
                    ).coerceAtLeast(100)
                }
            }
            for (index in 0 until count) {
                var nextX = x[index] + speedX[index]
                var nextY = y[index] + speedY[index]
                if (nextX <= -minX * 100) nextX += minX * 1_000
                if (nextX >= minX * 100) nextX %= minX * 100
                if (nextY < -minY * 100) nextY += minY * 1_000
                if (nextY >= minY * 100) nextY %= minY * 100
                x[index] = nextX
                y[index] = nextY
            }
        }
        return List(count) { index -> LayerOffset(x[index] / 100, y[index] / 100) }
    }

    /** KOMAANIME (63): source rectangle for the current sprite-sheet cel. */
    fun komaAnimeSourceRect(
        bitmapWidth: Int,
        bitmapHeight: Int,
        frame: Int,
        parameters: List<Int>
    ): RectI {
        val cellWidth = (parameters.getOrNull(1) ?: bitmapWidth).coerceIn(1, bitmapWidth.coerceAtLeast(1))
        val cellHeight = (parameters.getOrNull(2) ?: bitmapHeight).coerceIn(1, bitmapHeight.coerceAtLeast(1))
        val columns = (bitmapWidth / cellWidth).coerceAtLeast(1)
        val rows = (bitmapHeight / cellHeight).coerceAtLeast(1)
        val celCount = (columns * rows).coerceAtLeast(1)
        val frameDuration = (parameters.getOrNull(3) ?: 1).coerceAtLeast(1)
        var cel = frame / frameDuration
        val loopCount = (parameters.getOrNull(9) ?: 0)
        if (loopCount > 0) cel %= loopCount
        cel = floorMod(cel, celCount)
        val x = (cel % columns) * cellWidth
        val y = (cel / columns) * cellHeight
        return RectI(x, y, min(bitmapWidth, x + cellWidth), min(bitmapHeight, y + cellHeight))
    }

    /** RAIN4 (102): native radial copies, ordered from the closest/highest-opacity ring. */
    fun rain4Copies(
        bitmapWidth: Int,
        bitmapHeight: Int,
        frame: Int,
        parameters: List<Int>,
        screenWidth: Int,
        screenHeight: Int
    ): List<LayerOffset> {
        if (bitmapWidth <= 0 || bitmapHeight <= 0) return emptyList()
        val count = (parameters.getOrNull(1) ?: 1).coerceIn(1, 4)
        val high = (parameters.getOrNull(2) ?: 500).coerceAtLeast(10)
        val low = (parameters.getOrNull(3) ?: 20).coerceIn(1, high - 1)
        val speed = parameters.getOrNull(4) ?: -1
        val centerX = (parameters.getOrNull(5) ?: screenWidth / 2).coerceIn(0, screenWidth - 1)
        val centerY = (parameters.getOrNull(6) ?: screenHeight / 2).coerceIn(0, screenHeight - 1)
        val startAlpha = (parameters.getOrNull(7) ?: 0).coerceIn(0, 100)
        val fadeDistance = (parameters.getOrNull(8) ?: 60).coerceAtLeast(1)
        val range = high - low
        val distanceBase = if (speed < 0) high - floorMod(abs(speed) * frame, range) else low + floorMod(speed * frame, range)
        return List(count) { index ->
            val dist = (distanceBase + range * index / count).coerceIn(low, high)
            val scale = 100f / dist
            val edge = min(dist - low, high - dist)
            val alpha = if (edge >= fadeDistance) 1f
                else (startAlpha + edge * (100 - startAlpha) / fadeDistance).coerceIn(1, 100) / 100f
            LayerOffset(centerX, centerY, scale, alpha)
        }
    }

    private fun negativeChannel(channel: Int, factor: Int): Int =
        (128 + ((channel - 128) * factor) / 256).coerceIn(0, 255)

    private fun blendChannel(start: Int, end: Int, firstWeight: Int, secondWeight: Int): Int =
        (((start and 0xFF) * firstWeight + (end and 0xFF) * secondWeight) ushr 8).coerceIn(0, 255)

    private fun blendPixel(start: Int, end: Int, amount: Double): Int {
        val t = amount.coerceIn(0.0, 1.0)
        fun channel(shift: Int) = (
            ((start ushr shift) and 0xFF) * (1.0 - t) +
                ((end ushr shift) and 0xFF) * t
            ).roundToInt().coerceIn(0, 255)
        return argb(channel(24), channel(16), channel(8), channel(0))
    }

    private fun interpolateColor(start: SystemNnnVisualColor, end: SystemNnnVisualColor, percent: Int) =
        mixColor(start, end, percent.coerceIn(0, 100) / 100f)

    private fun mixColor(start: SystemNnnVisualColor, end: SystemNnnVisualColor, t: Float) =
        SystemNnnVisualColor(
            (start.red + (end.red - start.red) * t).roundToInt(),
            (start.green + (end.green - start.green) * t).roundToInt(),
            (start.blue + (end.blue - start.blue) * t).roundToInt()
        )

    private fun rgb(parameters: List<Int>, index: Int, default: Int) = SystemNnnVisualColor(
        (parameters.getOrNull(index) ?: default).coerceIn(0, 255),
        (parameters.getOrNull(index + 1) ?: default).coerceIn(0, 255),
        (parameters.getOrNull(index + 2) ?: default).coerceIn(0, 255)
    )

    private fun validFrame(source: IntArray, width: Int, height: Int): Boolean =
        width > 0 && height > 0 && width.toLong() * height == source.size.toLong()

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        ((alpha.coerceIn(0, 255) and 0xFF) shl 24) or
            ((red.coerceIn(0, 255) and 0xFF) shl 16) or
            ((green.coerceIn(0, 255) and 0xFF) shl 8) or
            (blue.coerceIn(0, 255) and 0xFF)

    private fun floorMod(value: Int, divisor: Int): Int {
        val safeDivisor = divisor.coerceAtLeast(1)
        val result = value % safeDivisor
        return if (result < 0) result + safeDivisor else result
    }

    private fun randomMix(value: Int): Int {
        var mixed = value
        mixed = (mixed xor (mixed ushr 16)) * 0x7FEB352D
        mixed = (mixed xor (mixed ushr 15)) * 0x846CA68B.toInt()
        mixed = mixed xor (mixed ushr 16)
        return mixed and Int.MAX_VALUE
    }
}
