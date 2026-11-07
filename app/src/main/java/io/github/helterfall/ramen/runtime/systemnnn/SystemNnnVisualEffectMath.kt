package io.github.helterfall.ramen.runtime.systemnnn

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

internal data class SystemNnnVisualOffset(
    val x: Float,
    val y: Float
)

internal data class SystemNnnVisualBlend(
    val primaryAlpha: Float,
    val secondaryAlpha: Float
)

internal data class SystemNnnVisualColor(
    val red: Int,
    val green: Int,
    val blue: Int
)

internal data class SystemNnnScreenFade(
    val color: SystemNnnVisualColor,
    val alpha: Float
)

internal data class SystemNnnFlashState(
    val color: SystemNnnVisualColor,
    val drawsFill: Boolean
)

internal data class SystemNnnCloudPixels(
    val width: Int,
    val height: Int,
    val pixels: IntArray
)

internal data class SystemNnnKeParticle(
    val x: Int,
    val y: Int,
    val size: Int,
    val sourceX: Int,
    val sourceY: Int
)

internal data class SystemNnnVisualLayerConfiguration(
    val sourceRect: SystemNnnNativeRect? = null,
    val destinationRect: SystemNnnNativeRect? = null,
    val effectId: Int? = null,
    val effectParameters: List<Int> = emptyList(),
    val effectCommandOffset: Long? = null
)

/**
 * Keeps effect-layer instructions even when a layer's bitmap has not been
 * loaded yet or a dialogue boundary splits the commands that configure it.
 */
internal class SystemNnnVisualLayerConfigurationStore {
    private val configurations = LinkedHashMap<Int, SystemNnnVisualLayerConfiguration>()

    fun snapshot(): MutableMap<Int, SystemNnnVisualLayerConfiguration> =
        LinkedHashMap(configurations)

    fun replaceWith(values: Map<Int, SystemNnnVisualLayerConfiguration>) {
        configurations.clear()
        configurations.putAll(values)
    }

    fun clear() {
        configurations.clear()
    }
}

/**
 * Pure calculations mirrored from the pinned System-NNN family compositor.
 * Time is expressed in native effect frames, not wall-clock milliseconds.
 */
internal object SystemNnnVisualEffectMath {
    private val shakePattern = intArrayOf(-16, -12, 16, 12)

    fun nativeScreenColor(parameters: List<Int>): SystemNnnVisualColor {
        val red = (parameters.getOrNull(1) ?: 0).coerceIn(0, 255)
        val green = (parameters.getOrNull(2) ?: red).coerceIn(0, 255)
        val blue = (parameters.getOrNull(3) ?: green).coerceIn(0, 255)
        return SystemNnnVisualColor(red, green, blue)
    }

    fun screenFade(effectId: Int, frame: Int, parameters: List<Int>): SystemNnnScreenFade? {
        if (effectId != 10 && effectId != 11) return null
        val duration = (parameters.firstOrNull() ?: 20).coerceIn(1, 9_999)
        val progress = nativeSpeedValue(
            count = frame,
            countMax = duration,
            startData = 0,
            endData = 100,
            delayCount = parameters.getOrNull(5) ?: 0,
            speedType = parameters.getOrNull(4) ?: 0
        ).coerceIn(0, 100) / 100f
        val alpha = if (effectId == 10) 1f - progress else progress
        return SystemNnnScreenFade(nativeScreenColor(parameters), alpha)
    }


    /**
     * Native FLASH (17). Before CountIsMax it flashes the whole screen; at
     * countMax the native command changes to FILL and the final color remains.
     */
    fun flashState(frame: Int, parameters: List<Int>): SystemNnnFlashState {
        val duration = (parameters.getOrNull(0) ?: 20).coerceAtLeast(0)
        val onTime = parameters.getOrNull(1) ?: 1
        val offTime = parameters.getOrNull(2) ?: onTime
        val period = (onTime.toLong() + offTime.toLong()).coerceAtLeast(1L)
        val red = parameters.getOrNull(3) ?: 255
        val green = parameters.getOrNull(4) ?: red
        val blue = parameters.getOrNull(5) ?: green
        val finalFill = frame >= duration
        val activePulse = !finalFill && Math.floorMod(frame.toLong(), period) < onTime.toLong()
        return SystemNnnFlashState(
            color = SystemNnnVisualColor(
                red.coerceIn(0, 255),
                green.coerceIn(0, 255),
                blue.coerceIn(0, 255)
            ),
            drawsFill = finalFill || activePulse
        )
    }

    /** Native SUNA (29) opacity; RGB values are not used by the grayscale noise path. */
    fun sunaOpacity(frame: Int, parameters: List<Int>): Float {
        if ((parameters.getOrNull(1) ?: 0) == 0) return 1f
        val duration = (parameters.getOrNull(0) ?: 0).coerceAtLeast(1)
        val end = parameters.getOrNull(2) ?: 100
        val start = parameters.getOrNull(3) ?: 0
        return nativeSpeedValue(
            frame.coerceIn(0, duration), duration, start, end,
            parameters.getOrNull(5) ?: 0,
            parameters.getOrNull(4) ?: 0
        ).coerceIn(0, 100) / 100f
    }

    /**
     * Generates the native SUNA grayscale noise plane at half resolution.
     * The native routine updates one of two interleaved noise buffers per print;
     * this keeps the same 2x2 pixel grain while using a stable, bounded seed.
     */
    fun sunaNoisePixels(width: Int, height: Int, frame: Int, seed: Int): IntArray {
        require(width in 1..2_000 && height in 1..2_000)
        val pixels = IntArray(width * height)
        val phase = frame ushr 1
        for (index in pixels.indices) {
            val random = mix(seed xor (phase * 0x45d9f3b) xor (index * 0x119de1f3))
            val gray = ((random ushr 8) and 0x7f) * 2
            pixels[index] = -0x1000000 or (gray shl 16) or (gray shl 8) or gray
        }
        return pixels
    }

    /**
     * Deterministic replay of the native KE atlas-particle envelope. Native uses
     * the process C RNG for spawn, lifetime and jitter; the target archive does
     * not expose that RNG state, so geometry is stable for a given effect/film.
     */
    fun keParticles(
        frame: Int,
        parameters: List<Int>,
        screenWidth: Int,
        screenHeight: Int,
        atlasWidth: Int,
        atlasHeight: Int,
        seed: Int
    ): List<SystemNnnKeParticle> {
        if (screenWidth <= 0 || screenHeight <= 0 || atlasWidth <= 0 || atlasHeight <= 0) {
            return emptyList()
        }
        val sizeX = (parameters.getOrNull(1) ?: 32).coerceIn(1, atlasWidth)
        val sizeY = (parameters.getOrNull(2) ?: 32).coerceIn(1, atlasHeight)
        val count = (parameters.getOrNull(3) ?: 1).coerceIn(1, 16)
        val stickMask = parameters.getOrNull(4) ?: 0
        val atlasColumns = (parameters.getOrNull(5) ?: 1).coerceAtLeast(1)
        val animationFrames = (parameters.getOrNull(6) ?: 1).coerceAtLeast(1)
        val animationSpeed = (parameters.getOrNull(7) ?: 1).coerceAtLeast(1)
        val visibleDuration = (parameters.getOrNull(8) ?: 30).coerceAtLeast(0)
        val hiddenDuration = (parameters.getOrNull(9) ?: 0).coerceAtLeast(0)
        val jitter = (parameters.getOrNull(10) ?: 0).coerceAtLeast(0)
        val directionChangeChance = (parameters.getOrNull(11) ?: 0).coerceIn(0, 10_000)
        val cycleLength = (visibleDuration.toLong() + hiddenDuration.toLong())
            .coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val availableDirections = buildList {
            if ((stickMask and 1) != 0) add(1)
            if ((stickMask and 2) != 0) add(2)
            if ((stickMask and 4) != 0) add(3)
            if ((stickMask and 8) != 0) add(4)
        }

        return buildList {
            repeat(count) { index ->
                val particleSeed = mix(seed xor (index * 0x45d9f3b))
                val phase = Math.floorMod(frame.toLong() + particleSeed.toLong(), cycleLength.toLong()).toInt()
                if (visibleDuration == 0 || phase >= visibleDuration) return@repeat

                val initialX = Math.floorMod(particleSeed, screenWidth)
                val initialY = Math.floorMod(mix(particleSeed xor 0x119de1f3), screenHeight)
                var centerX = initialX
                var centerY = initialY
                if (availableDirections.isNotEmpty()) {
                    val directionIndex = Math.floorMod(particleSeed ushr 4, availableDirections.size)
                    when (availableDirections[directionIndex]) {
                        1 -> centerY = sizeY / 2
                        2 -> centerX = screenWidth - sizeX / 2
                        3 -> centerY = screenHeight - sizeY / 2
                        4 -> centerX = sizeX / 2
                    }
                    if (directionChangeChance > 0) {
                        val directionSeed = mix(particleSeed xor (frame * 0x27d4eb2d)) % 10_000
                        if (directionSeed < directionChangeChance) {
                            when (availableDirections[Math.floorMod(directionSeed, availableDirections.size)]) {
                                1 -> centerY = sizeY / 2
                                2 -> centerX = screenWidth - sizeX / 2
                                3 -> centerY = screenHeight - sizeY / 2
                                4 -> centerX = sizeX / 2
                            }
                        }
                    }
                }
                if (jitter > 0) {
                    val jitterSeed = mix(seed xor (frame * 0x3449f5) xor (index * 0x7feb352d))
                    val range = jitter * 2 + 1
                    centerX += Math.floorMod(jitterSeed, range) - jitter
                    centerY += Math.floorMod(mix(jitterSeed xor 0x2468ace), range) - jitter
                }
                centerX = centerX.coerceIn(0, screenWidth - 1)
                centerY = centerY.coerceIn(0, screenHeight - 1)

                val animationSeed = mix(particleSeed xor (frame / animationSpeed))
                val animationFrame = if (animationFrames > 1) {
                    Math.floorMod(frame / animationSpeed + animationSeed % animationFrames, animationFrames)
                } else 0
                val sourceX = (index % atlasColumns) * sizeX
                val sourceY = animationFrame * sizeY
                if (sourceX + sizeX <= atlasWidth && sourceY + sizeY <= atlasHeight) {
                    add(
                        SystemNnnKeParticle(
                            x = centerX - sizeX / 2,
                            y = centerY - sizeY / 2,
                            size = sizeX,
                            sourceX = sourceX,
                            sourceY = sourceY
                        )
                    )
                }
            }
        }
    }

    /**
     * Native HAIKEIYURE (100) displacement. Mode 0 moves the source layer;
     * mode 1 applies the same displacement to the current screen buffer.
     */
    fun haikeiYureOffset(frame: Int, parameters: List<Int>): SystemNnnVisualOffset {
        val duration = (parameters.getOrNull(0) ?: 20).coerceAtLeast(0)
        if (duration == 0 || frame < 0 || frame >= duration) {
            return SystemNnnVisualOffset(0f, 0f)
        }

        val movementPercent = if ((parameters.getOrNull(11) ?: 0) != 0) {
            nativeSpeedValue(
                frame,
                duration,
                0,
                100,
                parameters.getOrNull(13) ?: 0,
                parameters.getOrNull(12) ?: 0
            )
        } else {
            100
        }
        val horizontal = ((parameters.getOrNull(1) ?: 20).toLong() *
            movementPercent.toLong() / 100L).toInt()
        val vertical = ((parameters.getOrNull(2) ?: 0).toLong() *
            movementPercent.toLong() / 100L).toInt()
        val phase = parameters.getOrNull(6) ?: 0
        val speedX = parameters.getOrNull(3) ?: 25
        val speedY = parameters.getOrNull(4) ?: 25

        fun nativeWave(speed: Int): Int {
            // Preserve C++ signed remainder order rather than floorMod.
            var value = (phase.toLong() + speed.toLong() * frame.toLong() + 75L) % 100L
            value = (value - 25L + 100L) % 100L
            if (value > 50L) value = 100L - value
            return value.toInt()
        }

        val waveX = nativeWave(speedX)
        val waveY = nativeWave(speedY)
        val dx = -horizontal.toLong() + (horizontal.toLong() * 2L * waveX.toLong()) / 50L
        val dy = -vertical.toLong() + (vertical.toLong() * 2L * waveY.toLong()) / 50L
        return SystemNnnVisualOffset(dx.toFloat(), dy.toFloat())
    }

    /**
     * Native software CLOUD (82) path observed in the supplied SPT diagnostics:
     * para[0] == 0, no DWQ mask (para[8] == 0), row-wise perspective sampling.
     *
     * The output is screen-width by para[1] rows. Source X uses the native
     * 16.16 accumulator and source Y is sampled once per output row.
     */
    fun cloudWarpPixels(
        frame: Long,
        parameters: List<Int>,
        sourcePixels: IntArray,
        sourceWidth: Int,
        sourceHeight: Int,
        screenWidth: Int,
        screenHeight: Int
    ): SystemNnnCloudPixels? {
        if (sourceWidth <= 0 || sourceHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) return null
        if (sourcePixels.size.toLong() < sourceWidth.toLong() * sourceHeight.toLong()) return null
        if ((parameters.getOrNull(0) ?: 9_999) != 0) return null
        if ((parameters.getOrNull(8) ?: 0) != 0) return null

        val rows = (parameters.getOrNull(1) ?: 200).coerceIn(1, screenHeight)
        val pixelCount = screenWidth.toLong() * rows.toLong()
        if (pixelCount <= 0L || pixelCount > 24_000_000L) return null

        val height = (parameters.getOrNull(4) ?: 200).toDouble()
        val nearDistance = (parameters.getOrNull(5) ?: 100).coerceAtLeast(1).toDouble()
        val farDistance = (parameters.getOrNull(6) ?: 500).let {
            if (it.toDouble() <= nearDistance) (nearDistance + 1.0) else it.toDouble()
        }
        val scalePercent = (parameters.getOrNull(7) ?: 100).coerceAtLeast(1)
        if (height <= 0.0 || farDistance <= nearDistance) return null

        val h2 = height * (farDistance - nearDistance) / farDistance
        val startDotPara = 100.0 / scalePercent.toDouble()
        val movementX = if (frame <= 0L) 0L else {
            Math.floorMod(
                (parameters.getOrNull(2) ?: 0).toLong() * frame,
                sourceWidth.toLong() * 100L
            )
        }
        val movementY = if (frame <= 0L) 0L else {
            Math.floorMod(
                (parameters.getOrNull(3) ?: 50).toLong() * frame,
                sourceHeight.toLong() * 100L
            )
        }
        val startDotY = movementY * 0.01
        val addSubEnabled = (parameters.getOrNull(9) ?: 0) != 0
        val colorStartY = (parameters.getOrNull(13) ?: 0).coerceIn(0, rows - 1)

        fun gradient(value: Int, row: Int): Int {
            if (!addSubEnabled || row < colorStartY) return 0
            val denominator = (rows - colorStartY).coerceAtLeast(1)
            val current = (row - colorStartY).coerceAtMost(denominator)
            val half = denominator / 2
            return ((value.toLong() * current.toLong() + half.toLong()) /
                denominator.toLong()).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        }

        val redOperand = parameters.getOrNull(10) ?: 0
        val greenOperand = parameters.getOrNull(11) ?: redOperand
        val blueOperand = parameters.getOrNull(12) ?: greenOperand
        val addRedValue = redOperand.coerceAtLeast(0)
        val addGreenValue = greenOperand.coerceAtLeast(0)
        val addBlueValue = blueOperand.coerceAtLeast(0)
        val subRedValue = (-redOperand.toLong()).coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val subGreenValue = (-greenOperand.toLong()).coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val subBlueValue = (-blueOperand.toLong()).coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        val output = IntArray(pixelCount.toInt())
        val screenHalf = screenWidth / 2
        val sourceFixedWidth = sourceWidth.toLong() shl 16
        val startXHighBase = screenHalf.toLong() + movementX / 100L
        val startXLowBase = ((movementX % 100L) * 65_536L) / 100L

        for (row in 0 until rows) {
            val hk = (row + 1).toDouble() * h2 / rows.toDouble()
            val denominator = height - hk
            if (denominator <= 0.0) return null
            val nearZ = height * nearDistance / denominator
            val dotPara = startDotPara * nearZ / nearDistance
            val deltaDotY = (nearZ - nearDistance) * startDotPara
            val sourceYValue = startDotY + deltaDotY
            if (!sourceYValue.isFinite()) return null
            val sourceY = sourceYValue.toInt() % sourceHeight
            if (sourceY < 0) return null

            val dotParaInteger = dotPara.toInt()
            var dotParaHigh = dotParaInteger % sourceWidth
            if (dotParaHigh < 0) dotParaHigh += sourceWidth
            val fraction = dotPara - dotParaInteger.toDouble()
            var dotParaLow = (fraction * 65_536.0 + 0.5).toInt()
            if (dotParaLow >= 65_536) dotParaLow = 65_535
            if (dotParaLow < 0) dotParaLow = 0

            var startXHigh = startXHighBase
            var startXLow = startXLowBase
            startXHigh += (startXLow ushr 16) and 0xffffL
            startXHigh %= sourceWidth.toLong()
            startXLow = startXLow and 0xffffL

            var deltaXHigh = dotParaHigh.toLong() * screenHalf.toLong()
            var deltaXLow = dotParaLow.toLong() * screenHalf.toLong()
            deltaXHigh += (deltaXLow ushr 16) and 0xffffL
            deltaXHigh %= sourceWidth.toLong()
            deltaXLow = deltaXLow and 0xffffL

            deltaXHigh = (sourceWidth.toLong() - deltaXHigh) % sourceWidth.toLong()
            if (deltaXLow > 0L) {
                deltaXHigh = (deltaXHigh - 1L + sourceWidth.toLong()) % sourceWidth.toLong()
                deltaXLow = 65_536L - deltaXLow
            }

            startXHigh += deltaXHigh
            startXLow += deltaXLow
            startXHigh += (startXLow ushr 16) and 0xffffL
            startXHigh %= sourceWidth.toLong()
            startXLow = startXLow and 0xffffL

            var sourceFixed = (startXHigh shl 16) or startXLow
            val sourceStep = (dotParaHigh.toLong() shl 16) or dotParaLow.toLong()
            val addRed = gradient(addRedValue, row)
            val addGreen = gradient(addGreenValue, row)
            val addBlue = gradient(addBlueValue, row)
            val subRed = gradient(subRedValue, row)
            val subGreen = gradient(subGreenValue, row)
            val subBlue = gradient(subBlueValue, row)
            val outputOffset = row * screenWidth
            val sourceOffset = sourceY * sourceWidth

            for (x in 0 until screenWidth) {
                val sourceX = (sourceFixed ushr 16).toInt()
                if (sourceX !in 0 until sourceWidth) return null
                val pixel = sourcePixels[sourceOffset + sourceX]
                if (addSubEnabled && row >= colorStartY) {
                    val red = ((((pixel ushr 16) and 0xff) + addRed).coerceAtMost(255) - subRed)
                        .coerceAtLeast(0)
                    val green = ((((pixel ushr 8) and 0xff) + addGreen).coerceAtMost(255) - subGreen)
                        .coerceAtLeast(0)
                    val blue = (((pixel and 0xff) + addBlue).coerceAtMost(255) - subBlue)
                        .coerceAtLeast(0)
                    output[outputOffset + x] =
                        -0x1000000 or (red shl 16) or (green shl 8) or blue
                } else {
                    output[outputOffset + x] = -0x1000000 or (pixel and 0x00ffffff)
                }

                sourceFixed += sourceStep
                if ((sourceFixed ushr 16) >= sourceWidth.toLong()) {
                    sourceFixed -= sourceFixedWidth
                }
            }
        }

        return SystemNnnCloudPixels(screenWidth, rows, output)
    }

    /**
     * Returns true only for native effects with an Android rendering path.
     * Several paths are parameter-gated because the native object has modes
     * with materially different screen/global behavior.
     */
    fun hasNativeLayerEffectPath(effectId: Int, parameters: List<Int>): Boolean =
        when (effectId) {
            0, 8, 9, 10, 11, 17, 18, 27, 29, 39, 40, 52, 54, 55, 62, 64, 65, 66, 99, 119, 132 -> true
            82 -> (parameters.getOrNull(0) ?: 9_999) == 0 &&
                (parameters.getOrNull(8) ?: 0) == 0
            100 -> (parameters.getOrNull(5) ?: 0) == 0
            else -> false
        }

    fun scrollUsesLoopFrame(parameters: List<Int>): Boolean =
        (parameters.getOrNull(3) ?: 0) != 0

    /**
     * Native CAllEffect::SetEffectScript treats MAE as a continuation marker.
     * IDs 4..7 have no registered native effect object and are rejected without
     * replacing the layer's previous effect.
     */
    fun shouldApplyNativeLayerEffectCommand(effectId: Int): Boolean =
        effectId !in setOf(-1, 1, 4, 5, 6, 7)

    /**
     * Whether the Compose renderer should draw the source bitmap as a fallback.
     *
     * The native CheckNeedPicPrint flag cannot be reused as a Compose draw
     * decision: FALSE means the native effect has its own Print implementation.
     * TURN, STRETCHBLT, COLORADDSUB, the observed static CLOUD software mode,
     * and layer-local HAIKEIYURE own their source drawing. BUFFER is deliberately
     * omitted because its native Print method is a no-op and CheckAllPrint is FALSE.
     * SCROLL has its own repeated translated bitmap painter. Unsupported modes may
     * retain the original bitmap only as an explicit incomplete fallback.
     */
    fun shouldDrawPlainLayerBitmap(effectId: Int?, parameters: List<Int>): Boolean =
        when (effectId) {
            27, 39, 54, 64, 65, 66, 99 -> false
            82 -> (parameters.getOrNull(0) ?: 9_999) != 0 ||
                (parameters.getOrNull(8) ?: 0) != 0
            100 -> (parameters.getOrNull(5) ?: 0) != 0
            else -> true
        }

    fun nativeSpeedValue(
        count: Int,
        countMax: Int,
        startData: Int,
        endData: Int,
        delayCount: Int = 0,
        speedType: Int = 0,
        randomSeed: Int = count
    ): Int {
        var start = startData
        var end = endData
        var encodedType = speedType

        if ((encodedType / 100) % 2 != 0) {
            val swap = start
            start = end
            end = swap
        }

        val randomFlag = encodedType / 200
        encodedType %= 100
        val acceleration = encodedType / 15
        val curve = encodedType % 15

        val maxFrame = countMax.coerceAtLeast(1)
        val frame = count.coerceIn(0, maxFrame)
        val delay = delayCount.coerceAtLeast(0)
        val waveDiv = delay.coerceAtLeast(1)
        val waveDiv2 = waveDiv * 2
        val waveCount = frame % waveDiv
        val waveCount2 = frame % waveDiv2
        val c = frame.toDouble()
        val max = maxFrame.toDouble()
        var ratio = c / max

        when (curve) {
            1 -> ratio = if (frame < delay) {
                0.0
            } else {
                (frame - delay).toDouble() / (maxFrame - delay).coerceAtLeast(1).toDouble()
            }
            2 -> ratio = if (frame < delay) {
                c / delay.coerceAtLeast(1).toDouble()
            } else {
                1.0
            }
            3 -> ratio = if (frame < delay) {
                c / delay.coerceAtLeast(1).toDouble()
            } else {
                (max - c) / (maxFrame - delay).coerceAtLeast(1).toDouble()
            }
            4 -> ratio = when {
                frame < delay -> c / delay.coerceAtLeast(1).toDouble()
                frame >= maxFrame - delay -> (max - c) / delay.coerceAtLeast(1).toDouble()
                else -> 1.0
            }
            5 -> {
                ratio = if (waveCount2 < waveDiv) waveCount2.toDouble()
                else (waveDiv2 - waveCount2).toDouble()
                ratio /= waveDiv.toDouble()
            }
            6 -> {
                ratio = if (waveCount2 < waveDiv) (waveDiv - waveCount2).toDouble()
                else (waveCount2 - waveDiv).toDouble()
                ratio /= waveDiv.toDouble()
            }
            7 -> ratio = waveCount.toDouble() / waveDiv.toDouble()
            8 -> ratio = (waveDiv - waveCount).toDouble() / waveDiv.toDouble()
            9 -> {
                if (frame <= waveDiv2 * 2) {
                    ratio = if (waveCount2 < waveDiv) waveCount2.toDouble()
                    else (waveDiv2 - waveCount2).toDouble()
                    ratio /= waveDiv.toDouble()
                } else {
                    val lastWave = maxFrame - waveDiv2 * 2
                    val lastDiv = (lastWave / 2).coerceAtLeast(1)
                    val last = frame - waveDiv2 * 2
                    ratio = if (last <= lastDiv) last.toDouble()
                    else (lastWave - last).toDouble()
                    ratio /= (lastWave / 2).coerceAtLeast(1).toDouble()
                }
            }
            10 -> {
                if (frame <= waveDiv * 2) {
                    ratio = waveCount.toDouble() / waveDiv.toDouble()
                } else {
                    val lastWave = maxFrame - waveDiv * 2
                    ratio = (frame - waveDiv * 2).toDouble() /
                        lastWave.coerceAtLeast(1).toDouble()
                }
            }
            // 0, 11 and 12 use the ordinary linear interpolation.
            else -> Unit
        }

        ratio = ratio.coerceIn(0.0, 1.0)
        ratio = when (acceleration) {
            2 -> ratio * ratio
            3 -> ratio.pow(1.0) // Native custom parameter defaults to 100%.
            4 -> sqrt(ratio)
            5 -> (1.0 - cos(ratio * Math.PI)) * 0.5
            else -> ratio
        }

        if (randomFlag != 0) {
            val mixed = mix(randomSeed)
            ratio *= (mixed % 10_000).toDouble() / 10_000.0
        }

        val value = start.toDouble() + (end.toLong() - start.toLong()).toDouble() * ratio
        return (value + 0.5).toInt()
    }

    fun changeBlend(
        frame: Int,
        parameters: List<Int>
    ): SystemNnnVisualBlend {
        val duration = (parameters.getOrNull(0) ?: 20).coerceIn(1, 9_999)
        val endTransparency = parameters.getOrNull(2) ?: 100
        val startTransparency = parameters.getOrNull(3) ?: endTransparency
        val transitionType = parameters.getOrNull(6) ?: 0
        val transitionSubFrame = parameters.getOrNull(7) ?: 0
        val changeType = parameters.getOrNull(4) ?: 0
        val changeSubFrame = parameters.getOrNull(5) ?: 0

        val transparency = nativeSpeedValue(
            frame, duration, startTransparency, endTransparency,
            transitionSubFrame, transitionType
        )
        val change = nativeSpeedValue(
            frame, duration, 100, 0, changeSubFrame, changeType
        )
        val primary = (change.toLong() * transparency / 100L).coerceIn(0L, 100L)
        val secondary = ((100 - change).toLong() * transparency / 100L).coerceIn(0L, 100L)
        return SystemNnnVisualBlend(primary / 100f, secondary / 100f)
    }

    fun bezierOffset(frame: Int, parameters: List<Int>): SystemNnnVisualOffset {
        // Native defaults from CEffectBezierMove::SetParam.
        val duration = (parameters.getOrNull(0) ?: 1).coerceAtLeast(1)
        val speed = nativeSpeedValue(
            frame, duration, 0, 10_000,
            parameters.getOrNull(10) ?: 0,
            parameters.getOrNull(9) ?: 0
        )
        val t = speed / 10_000f
        val inverse = 1f - t
        val x1 = (parameters.getOrNull(1) ?: 0).toFloat()
        val y1 = (parameters.getOrNull(2) ?: 0).toFloat()
        val x2 = (parameters.getOrNull(3) ?: 200).toFloat()
        val y2 = (parameters.getOrNull(4) ?: -100).toFloat()
        val x3 = (parameters.getOrNull(5) ?: 300).toFloat()
        val y3 = (parameters.getOrNull(6) ?: -200).toFloat()
        val x4 = (parameters.getOrNull(7) ?: 400).toFloat()
        val y4 = (parameters.getOrNull(8) ?: 100).toFloat()
        return SystemNnnVisualOffset(
            inverse * inverse * inverse * x1 +
                3f * t * inverse * inverse * x2 +
                3f * t * t * inverse * x3 +
                t * t * t * x4,
            inverse * inverse * inverse * y1 +
                3f * t * inverse * inverse * y2 +
                3f * t * t * inverse * y3 +
                t * t * t * y4
        )
    }

    fun bSpline2Offset(frame: Int, parameters: List<Int>): SystemNnnVisualOffset {
        // Native defaults from CEffectBSpline2::SetParam.
        val duration = (parameters.getOrNull(0) ?: 1).coerceAtLeast(1)
        val speed = nativeSpeedValue(
            frame, duration, 0, 10_000,
            parameters.getOrNull(8) ?: 0,
            parameters.getOrNull(7) ?: 0
        )
        val t = speed / 10_000f
        val inverse = 1f - t
        val x1 = parameters.getOrNull(1) ?: 0
        val y1 = parameters.getOrNull(2) ?: 0
        val x2 = parameters.getOrNull(3) ?: 200
        val y2 = parameters.getOrNull(4) ?: -200
        val x3 = parameters.getOrNull(5) ?: 400
        val y3 = parameters.getOrNull(6) ?: 100
        return SystemNnnVisualOffset(
            inverse * inverse * x1 +
                2f * inverse * t * x2 +
                t * t * x3,
            inverse * inverse * y1 +
                2f * inverse * t * y2 +
                t * t * y3
        )
    }

    fun shakeOffset(
        frame: Int,
        frameCount: Int,
        vertical: Boolean
    ): SystemNnnVisualOffset {
        val duration = frameCount.coerceAtLeast(1)
        if (frame < 0 || frame >= duration) return SystemNnnVisualOffset(0f, 0f)
        val index = Math.floorMod(duration - frame, shakePattern.size)
        val displacement = shakePattern[index].toFloat()
        return if (vertical) {
            SystemNnnVisualOffset(0f, displacement)
        } else {
            SystemNnnVisualOffset(displacement, 0f)
        }
    }


    fun moveRect(
        frame: Int,
        durationFrames: Int,
        from: SystemNnnNativeRect,
        to: SystemNnnNativeRect
    ): SystemNnnNativeRect {
        val duration = durationFrames.coerceAtLeast(1)
        val count = frame.coerceIn(0, duration)
        if (count >= duration) return to

        fun interpolate(start: Int, end: Int): Int {
            val delta = (end.toLong() - start.toLong()) * count.toLong()
            val half = duration / 2L
            val roundedDelta = if (delta >= 0L) {
                (delta + half) / duration
            } else {
                -((-delta + half) / duration)
            }
            return (start.toLong() + roundedDelta)
                .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                .toInt()
        }

        // Native CalcuOnly moves only the origin until the final frame.
        return SystemNnnNativeRect(
            x = interpolate(from.x, to.x),
            y = interpolate(from.y, to.y),
            width = from.width,
            height = from.height
        )
    }

    fun scrollOffset(
        frame: Int,
        parameters: List<Int>,
        screenWidth: Int,
        screenHeight: Int
    ): SystemNnnVisualOffset {
        if (screenWidth <= 0 || screenHeight <= 0) return SystemNnnVisualOffset(0f, 0f)
        val x = (parameters.getOrNull(1) ?: 32).toLong() * frame.toLong()
        val y = (parameters.getOrNull(2) ?: 0).toLong() * frame.toLong()
        return SystemNnnVisualOffset(
            Math.floorMod(x, screenWidth.toLong()).toFloat(),
            Math.floorMod(y, screenHeight.toLong()).toFloat()
        )
    }

    /** Deterministic replay of the native randomized character-jitter envelope. */
    fun charaBureOffset(
        frame: Long,
        parameters: List<Int>,
        seed: Int
    ): SystemNnnVisualOffset {
        val amplitudeX = (parameters.getOrNull(1) ?: 4).coerceAtLeast(0)
        val amplitudeY = (parameters.getOrNull(2) ?: 4).coerceAtLeast(0)
        if (amplitudeX == 0 && amplitudeY == 0) return SystemNnnVisualOffset(0f, 0f)

        val baseDuration = (parameters.getOrNull(3) ?: 8).coerceAtLeast(1)
        val sizeRandom = (parameters.getOrNull(4) ?: 50).coerceAtLeast(0)
        val timeRandom = (parameters.getOrNull(5) ?: 50).coerceAtLeast(0)
        val speedType = parameters.getOrNull(6) ?: 0
        val subFrame = parameters.getOrNull(7) ?: 0
        var segmentStart = 0L
        var previousX = 0
        var previousY = 0

        for (segment in 0..4_096) {
            val randomTime = mix(seed xor (segment * 0x45d9f3b))
            val timeRange = (baseDuration.toLong() * timeRandom).coerceAtLeast(1L)
            val segmentDuration = (
                baseDuration.toLong() * 100L - randomTime.toLong() % timeRange
            ).div(100L).coerceIn(1L, 9_999L).toInt()
            val randomX = mix(seed xor (segment * 0x119de1f3) xor 0x13579bdf)
            val randomY = mix(seed xor (segment * 0x3449f5) xor 0x2468ace)

            fun target(amplitude: Int, random: Int): Int {
                if (amplitude == 0) return 0
                val range = (amplitude.toLong() * sizeRandom).coerceAtLeast(1L)
                val value = (amplitude.toLong() * 100L - random.toLong() % range) / 100L
                val signed = value.toInt()
                return if (random % 100 < 50) -signed else signed
            }

            val targetX = target(amplitudeX, randomX)
            val targetY = target(amplitudeY, randomY)
            val segmentEnd = segmentStart + segmentDuration
            if (frame <= segmentEnd) {
                val localFrame = (frame - segmentStart).coerceIn(0L, segmentDuration.toLong()).toInt()
                return SystemNnnVisualOffset(
                    nativeSpeedValue(localFrame, segmentDuration, previousX, targetX, subFrame, speedType).toFloat(),
                    nativeSpeedValue(localFrame, segmentDuration, previousY, targetY, subFrame, speedType).toFloat()
                )
            }
            previousX = targetX
            previousY = targetY
            segmentStart = segmentEnd
        }

        return SystemNnnVisualOffset(previousX.toFloat(), previousY.toFloat())
    }


    /** Native STRETCHBLT (65): 100 in the parameter array means a 1x scale. */
    fun stretchBltScale(
        frame: Int,
        parameters: List<Int>,
        horizontal: Boolean
    ): Float {
        val duration = (parameters.getOrNull(0) ?: 20).coerceAtLeast(1)
        val endIndex = if (horizontal) 3 else 4
        val startIndex = if (horizontal) 5 else 6
        val end = parameters.getOrNull(endIndex) ?: 100
        val start = parameters.getOrNull(startIndex) ?: end
        val scaleHundredths = nativeSpeedValue(
            frame,
            duration,
            start * 100,
            end * 100,
            parameters.getOrNull(8) ?: 0,
            parameters.getOrNull(7) ?: 0
        )
        return (scaleHundredths / 10_000f).coerceAtLeast(0.001f)
    }

    /** STRETCHBLT transparency percentage. Native modes 0/1 do not use this value. */
    fun stretchBltAlpha(frame: Int, parameters: List<Int>): Float {
        if ((parameters.getOrNull(9) ?: 0) != 2) return 1f
        val duration = (parameters.getOrNull(0) ?: 20).coerceAtLeast(1)
        val start = parameters.getOrNull(11) ?: 100
        val end = parameters.getOrNull(10) ?: start
        return nativeSpeedValue(
            frame, duration, start, end,
            parameters.getOrNull(13) ?: 0,
            parameters.getOrNull(12) ?: 0
        ).coerceIn(0, 100) / 100f
    }

    /** Native STRETCHBLT center is source center plus para[1]/para[2]. */
    fun stretchBltPivotOffset(parameters: List<Int>): SystemNnnVisualOffset =
        SystemNnnVisualOffset(
            (parameters.getOrNull(1) ?: 0).toFloat(),
            (parameters.getOrNull(2) ?: 0).toFloat()
        )

    /** Native STRETCHBLT color add/subtract; disabled means an unmodified source. */
    fun stretchBltColorOffset(frame: Int, parameters: List<Int>): SystemNnnVisualColor {
        if ((parameters.getOrNull(14) ?: 0) == 0) return SystemNnnVisualColor(0, 0, 0)
        val duration = (parameters.getOrNull(0) ?: 20).coerceAtLeast(1)
        fun channel(startIndex: Int, endIndex: Int): Int {
            val end = parameters.getOrNull(endIndex) ?: 0
            val start = parameters.getOrNull(startIndex) ?: end
            return nativeSpeedValue(
                frame, duration, start, end,
                parameters.getOrNull(22) ?: 0,
                parameters.getOrNull(21) ?: 0
            )
        }
        return SystemNnnVisualColor(channel(18, 15), channel(19, 16), channel(20, 17))
    }

    /** Native EFFECT_TURN (64): angle is stored in tenths of a degree. */

    /** Native COLORADDSUB (99): end/start RGB and speed operands follow SetParam. */
    fun colorAddSubOffset(frame: Int, parameters: List<Int>): SystemNnnVisualColor {
        val duration = (parameters.getOrNull(0) ?: 20).coerceAtLeast(1)
        fun channel(startIndex: Int, endIndex: Int, defaultEnd: Int = 0): Int {
            val end = parameters.getOrNull(endIndex) ?: defaultEnd
            val start = parameters.getOrNull(startIndex) ?: end
            return nativeSpeedValue(
                frame, duration, start, end,
                parameters.getOrNull(9) ?: 0,
                parameters.getOrNull(8) ?: 0
            )
        }
        val endR = parameters.getOrNull(1) ?: 0
        val endG = parameters.getOrNull(2) ?: endR
        val endB = parameters.getOrNull(3) ?: endG
        return SystemNnnVisualColor(
            channel(4, 1, endR),
            channel(5, 2, endG),
            channel(6, 3, endB)
        )
    }

    fun turnRotationDegrees(frame: Int, parameters: List<Int>): Float {
        val duration = (parameters.getOrNull(0) ?: 20).coerceAtLeast(1)
        val start = parameters.getOrNull(2) ?: (parameters.getOrNull(1) ?: 0)
        val end = parameters.getOrNull(1) ?: 0
        val angleTenths = nativeSpeedValue(
            frame, duration, start * 10, end * 10,
            parameters.getOrNull(11) ?: 0, parameters.getOrNull(10) ?: 0
        )
        return Math.floorMod(angleTenths, 3600) / 10f
    }

    /** Native TURN scale: para[6] is start, para[5] is end; 100 means 1:1. */
    fun turnScale(frame: Int, parameters: List<Int>): Float {
        val duration = (parameters.getOrNull(0) ?: 20).coerceAtLeast(1)
        val end = parameters.getOrNull(5) ?: 100
        val start = parameters.getOrNull(6) ?: end
        val nativeTenths = nativeSpeedValue(
            frame, duration, start * 10, end * 10,
            parameters.getOrNull(13) ?: 0, parameters.getOrNull(12) ?: 0
        )
        return (nativeTenths / 10).coerceAtLeast(1) / 100f
    }

    /** Native TURN only applies this opacity when para[7] == 2. */
    fun turnAlpha(frame: Int, parameters: List<Int>): Float {
        if ((parameters.getOrNull(7) ?: 0) != 2) return 1f
        val duration = (parameters.getOrNull(0) ?: 20).coerceAtLeast(1)
        return nativeSpeedValue(
            frame, duration,
            parameters.getOrNull(9) ?: 100,
            parameters.getOrNull(8) ?: 100,
            parameters.getOrNull(15) ?: 0,
            parameters.getOrNull(14) ?: 0
        ).coerceIn(0, 100) / 100f
    }

    /** Native TURN pivot is source center plus para[3]/para[4]. */
    fun turnPivotOffset(parameters: List<Int>): SystemNnnVisualOffset =
        SystemNnnVisualOffset(
            (parameters.getOrNull(3) ?: 0).toFloat(),
            (parameters.getOrNull(4) ?: 0).toFloat()
        )

    /** Native TURN's optional quadratic/cubic spline translation (para[25] = 2/3). */
    fun turnSplineOffset(frame: Int, parameters: List<Int>): SystemNnnVisualOffset {
        val splineType = parameters.getOrNull(25) ?: 0
        if (splineType != 2 && splineType != 3) return SystemNnnVisualOffset(0f, 0f)
        val duration = (parameters.getOrNull(0) ?: 20).coerceAtLeast(1)
        val speed = nativeSpeedValue(
            frame, duration, 0, 10_000,
            parameters.getOrNull(35) ?: 0, parameters.getOrNull(34) ?: 0
        )
        val t = speed.coerceIn(0, 10_000) / 10_000f
        val inverse = 1f - t
        val x1 = (parameters.getOrNull(26) ?: 0).toFloat()
        val y1 = (parameters.getOrNull(27) ?: 0).toFloat()
        val x2 = (parameters.getOrNull(28) ?: 200).toFloat()
        val y2 = (parameters.getOrNull(29) ?: -100).toFloat()
        val x3 = (parameters.getOrNull(30) ?: 300).toFloat()
        val y3 = (parameters.getOrNull(31) ?: -200).toFloat()
        if (splineType == 2) {
            return SystemNnnVisualOffset(
                inverse * inverse * x1 + 2f * inverse * t * x2 + t * t * x3,
                inverse * inverse * y1 + 2f * inverse * t * y2 + t * t * y3
            )
        }
        val x4 = (parameters.getOrNull(32) ?: 400).toFloat()
        val y4 = (parameters.getOrNull(33) ?: 100).toFloat()
        val t2 = t * t
        val t3 = t2 * t
        val inverse2 = inverse * inverse
        val inverse3 = inverse2 * inverse
        return SystemNnnVisualOffset(
            inverse3 * x1 + 3f * t * inverse2 * x2 + 3f * t2 * inverse * x3 + t3 * x4,
            inverse3 * y1 + 3f * t * inverse2 * y2 + 3f * t2 * inverse * y3 + t3 * y4
        )
    }


    /**
     * Native TURN maps the source pivot (bitmap center + para[3..4]) to
     * screen center + destination offset + optional spline offset. The caller
     * aligns the node to screen center, so this returns only the additional layout offset.
     */
    fun turnCenteredOffset(
        frame: Int,
        parameters: List<Int>,
        destinationX: Int,
        destinationY: Int
    ): SystemNnnVisualOffset {
        val pivot = turnPivotOffset(parameters)
        val spline = turnSplineOffset(frame, parameters)
        // The caller aligns the layer box to screen center. Its offset is therefore
        // only the native destination displacement minus the source pivot delta.
        // The bitmap half-size is already accounted for by Alignment.Center.
        return SystemNnnVisualOffset(
            destinationX + spline.x - pivot.x,
            destinationY + spline.y - pivot.y
        )
    }

    /** Native CTRANS colour offset; null means the normal mask/translucency path. */
    fun ctransColorOffset(frame: Int, parameters: List<Int>): SystemNnnVisualColor? {
        if ((parameters.getOrNull(14) ?: 0) == 0) return null
        val duration = (parameters.getOrNull(0) ?: 20).coerceAtLeast(1)
        fun channel(startIndex: Int, endIndex: Int): Int {
            val end = parameters.getOrNull(endIndex) ?: 0
            val start = parameters.getOrNull(startIndex) ?: end
            return nativeSpeedValue(
                frame, duration, start, end,
                parameters.getOrNull(22) ?: 0, parameters.getOrNull(21) ?: 0
            )
        }
        return SystemNnnVisualColor(channel(18, 15), channel(19, 16), channel(20, 17))
    }

    /** Native CTRANS clip rectangle interpolates start para[10..13] to end para[6..9]. */
    fun ctransClipRect(
        frame: Int,
        parameters: List<Int>,
        screenWidth: Int,
        screenHeight: Int
    ): SystemNnnNativeRect? {
        if ((parameters.getOrNull(5) ?: 0) == 0) return null
        val duration = (parameters.getOrNull(0) ?: 20).coerceAtLeast(1)
        fun value(startIndex: Int, endIndex: Int, defaultEnd: Int): Int {
            val end = parameters.getOrNull(endIndex) ?: defaultEnd
            val start = parameters.getOrNull(startIndex) ?: end
            return nativeSpeedValue(frame, duration, start, end)
        }
        val clipEndWidth = parameters.getOrNull(8) ?: screenWidth
        val clipEndHeight = parameters.getOrNull(9) ?: screenHeight
        return SystemNnnNativeRect(
            x = value(10, 6, 0),
            y = value(11, 7, 0),
            width = value(12, 8, clipEndWidth),
            height = value(13, 9, clipEndHeight)
        )
    }

    private fun mix(value: Int): Int {
        var mixed = value
        mixed = (mixed xor (mixed ushr 16)) * 0x7feb352d
        mixed = (mixed xor (mixed ushr 15)) * 0x846ca68b.toInt()
        mixed = mixed xor (mixed ushr 16)
        return mixed and Int.MAX_VALUE
    }
}
