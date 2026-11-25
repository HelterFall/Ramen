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
            73 -> source.copyOf() // GET is captured by the player at the draw boundary.
            else -> null
        }
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

    fun overrapWeights(frame: Int, length: Int): Pair<Int, Int> {
        val total = length.coerceAtLeast(1)
        val step = frame.coerceIn(0, total)
        val end = (step * 100) / total
        return (100 - end) to end
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
        val startPercent = parameters.getOrNull(4) ?: 0
        val endPercent = parameters.getOrNull(3) ?: 100
        val amount = SystemNnnVisualEffectMath.nativeSpeedValue(
            frame, duration, startPercent, endPercent,
            parameters.getOrNull(2) ?: 0, parameters.getOrNull(1) ?: 0
        ).coerceIn(0, 100)
        val negativeFactor = (254 - (amount * 508 / 100)).coerceIn(-254, 254)
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
        val endLeft = rgb(parameters, 1, 255)
        val endRight = rgb(parameters, 4, endLeft.red)
        val startLeft = rgb(parameters, 9, 0)
        val startRight = rgb(parameters, 12, startLeft.red)
        val speedType = parameters.getOrNull(7) ?: 0
        val subFrame = parameters.getOrNull(8) ?: 0
        val left = interpolateColor(
            startLeft, endLeft,
            SystemNnnVisualEffectMath.nativeSpeedValue(frame, duration, 0, 100, subFrame, speedType)
        )
        val right = interpolateColor(
            startRight, endRight,
            SystemNnnVisualEffectMath.nativeSpeedValue(frame, duration, 0, 100, subFrame, speedType)
        )
        val startY = (parameters.getOrNull(14) ?: 0).coerceIn(0, height)
        val spanY = (parameters.getOrNull(15) ?: height).coerceAtLeast(1)
        val result = source.copyOf()
        for (y in startY until min(height, startY + spanY)) {
            val vertical = if (spanY <= 1) 0f else (y - startY).toFloat() / (spanY - 1)
            val rowLeft = mixColor(startLeft, left, vertical)
            val rowRight = mixColor(startRight, right, vertical)
            val rowBase = y * width
            for (x in 0 until width) {
                val horizontal = if (width <= 1) 0f else x.toFloat() / (width - 1)
                val tint = mixColor(rowLeft, rowRight, horizontal)
                val index = rowBase + x
                val pixel = source[index]
                result[index] = argb(
                    pixel ushr 24,
                    ((pixel ushr 16 and 0xFF) + tint.red).coerceIn(0, 255),
                    ((pixel ushr 8 and 0xFF) + tint.green).coerceIn(0, 255),
                    ((pixel and 0xFF) + tint.blue).coerceIn(0, 255)
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
        val spanY = (parameters.getOrNull(2) ?: height).coerceAtLeast(0)
        val wait = (parameters.getOrNull(3) ?: 0).coerceAtLeast(0)
        val scale = (parameters.getOrNull(4) ?: 100).coerceIn(0, 400) / 100.0
        val waveHeightScale = (parameters.getOrNull(5) ?: 100).coerceIn(0, 400) / 100.0
        val speedScale = (parameters.getOrNull(6) ?: 100).coerceIn(0, 400) / 100.0
        val progress = if (wait <= 0) 1.0 else (frame.toDouble() / wait).coerceIn(0.0, 1.0)
        val heights = intArrayOf(
            parameters.getOrNull(7) ?: 0,
            parameters.getOrNull(10) ?: 0,
            parameters.getOrNull(13) ?: 0
        )
        val widths = intArrayOf(
            parameters.getOrNull(8) ?: 1,
            parameters.getOrNull(11) ?: 1,
            parameters.getOrNull(14) ?: 1
        )
        val speeds = intArrayOf(
            parameters.getOrNull(9) ?: 0,
            parameters.getOrNull(12) ?: 0,
            parameters.getOrNull(15) ?: 0
        )
        val result = source.copyOf()
        val endY = min(height, startY + spanY)
        for (y in startY until endY) {
            val row = y * width
            var displacement = 0.0
            for (wave in 0..2) {
                val phase = (y.toDouble() * widths[wave] -
                    frame.toDouble() * speeds[wave] * speedScale) * (2.0 * PI / height.coerceAtLeast(1))
                displacement += cos(phase) * heights[wave]
            }
            displacement *= scale * waveHeightScale * progress
            val dx = displacement.roundToInt()
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
        var y = top
        while (y < bottom) {
            val cellBottom = min(bottom, y + blockHeight)
            val sampleY = min(height - 1, y + ((cellBottom - y - 1) / 2))
            var x = left
            while (x < right) {
                val cellRight = min(right, x + blockWidth)
                val sampleX = min(width - 1, x + ((cellRight - x - 1) / 2))
                val sample = source[sampleY * width + sampleX]
                for (yy in y until cellBottom) {
                    val row = yy * width
                    for (xx in x until cellRight) result[row + xx] = sample
                }
                x = cellRight
            }
            y = cellBottom
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
    fun hotaru(
        source: IntArray,
        width: Int,
        height: Int,
        frame: Int,
        parameters: List<Int>
    ): IntArray {
        if (!validFrame(source, width, height)) return source.copyOf()
        val count = (parameters.getOrNull(1) ?: 0).coerceIn(0, 96)
        val originX = parameters.getOrNull(2) ?: width
        val originY = parameters.getOrNull(3) ?: (height - 180)
        val spanX = (parameters.getOrNull(4) ?: 100).coerceAtLeast(1)
        val spanY = (parameters.getOrNull(5) ?: 100).coerceAtLeast(1)
        val velocityX = parameters.getOrNull(6) ?: -2
        val velocityY = parameters.getOrNull(7) ?: -3
        val red = (parameters.getOrNull(8) ?: 255).coerceIn(0, 255)
        val green = (parameters.getOrNull(9) ?: 255).coerceIn(0, 255)
        val blue = (parameters.getOrNull(10) ?: 255).coerceIn(0, 255)
        val alpha = (parameters.getOrNull(14) ?: 100).coerceIn(0, 100) / 100.0
        val radiusBase = (parameters.getOrNull(17) ?: 20).coerceIn(1, 80)
        val result = source.copyOf()
        repeat(count) { particle ->
            val seed = randomMix(particle * 0x45D9F3B xor 0x71C0A2)
            val startX = originX + floorMod(seed, spanX)
            val startY = originY + floorMod(randomMix(seed xor 0x1B873593), spanY)
            val x = floorMod(startX + frame * velocityX, width)
            val y = floorMod(startY + frame * velocityY, height)
            val radius = max(2, radiusBase / 4 + (seed ushr 4) % max(2, radiusBase / 3))
            val redAdd = (red * alpha).roundToInt()
            val greenAdd = (green * alpha).roundToInt()
            val blueAdd = (blue * alpha).roundToInt()
            val y0 = max(0, y - radius)
            val y1 = min(height - 1, y + radius)
            val x0 = max(0, x - radius)
            val x1 = min(width - 1, x + radius)
            for (py in y0..y1) {
                for (px in x0..x1) {
                    val distance = hypot((px - x).toDouble(), (py - y).toDouble())
                    if (distance > radius) continue
                    val falloff = (1.0 - distance / radius).let { it * it }
                    val index = py * width + px
                    val old = result[index]
                    result[index] = argb(
                        old ushr 24,
                        ((old ushr 16 and 0xFF) + (redAdd * falloff).roundToInt()).coerceIn(0, 255),
                        ((old ushr 8 and 0xFF) + (greenAdd * falloff).roundToInt()).coerceIn(0, 255),
                        ((old and 0xFF) + (blueAdd * falloff).roundToInt()).coerceIn(0, 255)
                    )
                }
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
        val endMagnification = (parameters.getOrNull(3) ?: 100).coerceAtLeast(1) / 100.0
        val startMagnification = (parameters.getOrNull(4) ?: 100).coerceAtLeast(1) / 100.0
        val endAlpha = (parameters.getOrNull(5) ?: 100).coerceIn(0, 100) / 100.0
        val startAlpha = (parameters.getOrNull(6) ?: 100).coerceIn(0, 100) / 100.0
        val startX = (parameters.getOrNull(11) ?: endX.toInt()).toDouble()
        val startY = (parameters.getOrNull(12) ?: endY.toInt()).toDouble()
        val centerX = startX + (endX - startX) * t
        val centerY = startY + (endY - startY) * t
        val magnification = startMagnification + (endMagnification - startMagnification) * t
        val opacity = startAlpha + (endAlpha - startAlpha) * t
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
            val sourceY = inTileY / 16
            for (x in left until right) {
                val localX = x - left
                val tileX = localX / 128
                val inTileX = localX % 128
                val sourceX = inTileX / 16
                val tile = tileY * tileColumns + tileX
                val noise = randomMix(tile * 0x45D9F3B xor sourceY * 0x119DE1F3 xor sourceX * 0x3449F5 xor frame)
                val lane = (sourceX + sourceY * 8) and 3
                val value = ((noise ushr (lane * 8)) and 0xFF)
                val gray = (value + (noise ushr 24 and 0x7F)) / 2
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
        val spriteColumns = (parameters.getOrNull(5) ?: 1).coerceIn(1, bitmapWidth / cellWidth.coerceAtLeast(1))
        val animationCells = (parameters.getOrNull(6) ?: 1).coerceIn(1, 64)
        val animationSpeed = (parameters.getOrNull(7) ?: 1).coerceAtLeast(1)
        val visibleFrames = (parameters.getOrNull(8) ?: 30).coerceAtLeast(1)
        val hiddenFrames = (parameters.getOrNull(9) ?: 0).coerceAtLeast(0)
        val jitter = (parameters.getOrNull(10) ?: 0).coerceIn(0, 64)
        val interval = (visibleFrames + hiddenFrames).coerceAtLeast(1)
        val rows = (bitmapHeight / cellHeight).coerceAtLeast(1)
        return List(particleCount) { index ->
            val seed = randomMix(index * 0x45D9F3B xor 0x27C0FFEE)
            val phase = floorMod(frame + (seed and 0x7FFF), interval)
            val visible = phase < visibleFrames
            val baseX = floorMod(seed, screenWidth)
            val baseY = floorMod(randomMix(seed xor 0xB5297A4D.toInt()), screenHeight)
            val noise = randomMix(seed xor (frame * 0x119DE1F3))
            val jitterX = if (jitter == 0) 0 else floorMod(noise, jitter * 2 + 1) - jitter
            val jitterY = if (jitter == 0) 0 else floorMod(randomMix(noise), jitter * 2 + 1) - jitter
            val direction = when {
                attachMask == 0 -> 0
                else -> floorMod(index, 4) + 1
            }
            val x = when (direction) {
                2 -> screenWidth - cellWidth / 2
                4 -> cellWidth / 2
                else -> floorMod(baseX + jitterX, screenWidth)
            }
            val y = when (direction) {
                1 -> cellHeight / 2
                3 -> screenHeight - cellHeight / 2
                else -> floorMod(baseY + jitterY, screenHeight)
            }
            val cel = floorMod(frame / animationSpeed + index, animationCells)
            val cellX = index % spriteColumns
            val cellY = cel % rows
            ParticleStamp(
                x.toFloat(), y.toFloat(),
                RectI(
                    cellX * cellWidth, cellY * cellHeight,
                    min(bitmapWidth, (cellX + 1) * cellWidth),
                    min(bitmapHeight, (cellY + 1) * cellHeight)
                ),
                if (visible) 1f else 0f,
                scale = 1f
            )
        }.filter { it.alpha > 0f && it.sourceRect.width > 0 && it.sourceRect.height > 0 }
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
        val count = (parameters.getOrNull(3) ?: 1).coerceIn(1, 4)
        val speedX = (parameters.getOrNull(1) ?: 0) * 0.01f
        val speedY = (parameters.getOrNull(2) ?: 0) * 0.01f
        val variationX = (parameters.getOrNull(4) ?: 0).coerceAtLeast(0)
        val variationY = (parameters.getOrNull(5) ?: 0).coerceAtLeast(0)
        return List(count) { index ->
            val seed = randomMix(index * 0x119DE1F3)
            val jitterSpanX = (variationX * 2 + 1).coerceAtLeast(1)
            val jitterSpanY = (variationY * 2 + 1).coerceAtLeast(1)
            val jitterX = floorMod(randomMix(seed xor (frame * 0x45D9F3B)), jitterSpanX) - variationX
            val jitterY = floorMod(randomMix(seed xor (frame * 0x119DE1F3)), jitterSpanY) - variationY
            val initialX = floorMod(seed, bitmapWidth.coerceAtLeast(1))
            val initialY = floorMod(randomMix(seed xor 0x3449F5), bitmapHeight.coerceAtLeast(1))
            val xPeriod = min(bitmapWidth, screenWidth).coerceAtLeast(1)
            val yPeriod = min(bitmapHeight, screenHeight).coerceAtLeast(1)
            LayerOffset(
                floorMod((initialX + frame * speedX).roundToInt() + jitterX, xPeriod),
                floorMod((initialY + frame * speedY).roundToInt() + jitterY, yPeriod),
                1f,
                1f
            )
        }
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
