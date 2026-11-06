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

/**
 * Pure calculations mirrored from the pinned System-NNN family compositor.
 * Time is expressed in native effect frames, not wall-clock milliseconds.
 */
internal object SystemNnnVisualEffectMath {
    private val shakePattern = intArrayOf(-16, -12, 16, 12)

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
        if (parameters.size < 9) return SystemNnnVisualOffset(0f, 0f)
        val duration = (parameters.getOrNull(0) ?: 1).coerceAtLeast(1)
        val speed = nativeSpeedValue(
            frame, duration, 0, 10_000,
            parameters.getOrNull(10) ?: 0,
            parameters.getOrNull(9) ?: 0
        )
        val t = speed / 10_000f
        val inverse = 1f - t
        val x1 = parameters[1].toFloat()
        val y1 = parameters[2].toFloat()
        val x2 = parameters[3].toFloat()
        val y2 = parameters[4].toFloat()
        val x3 = parameters[5].toFloat()
        val y3 = parameters[6].toFloat()
        val x4 = parameters[7].toFloat()
        val y4 = parameters[8].toFloat()
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
        if (parameters.size < 7) return SystemNnnVisualOffset(0f, 0f)
        val duration = (parameters.getOrNull(0) ?: 1).coerceAtLeast(1)
        val speed = nativeSpeedValue(
            frame, duration, 0, 10_000,
            parameters.getOrNull(8) ?: 0,
            parameters.getOrNull(7) ?: 0
        )
        val t = speed / 10_000f
        val inverse = 1f - t
        return SystemNnnVisualOffset(
            inverse * inverse * parameters[1] +
                2f * inverse * t * parameters[3] +
                t * t * parameters[5],
            inverse * inverse * parameters[2] +
                2f * inverse * t * parameters[4] +
                t * t * parameters[6]
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

    private fun mix(value: Int): Int {
        var mixed = value
        mixed = (mixed xor (mixed ushr 16)) * 0x7feb352d
        mixed = (mixed xor (mixed ushr 15)) * 0x846ca68b.toInt()
        mixed = mixed xor (mixed ushr 16)
        return mixed and Int.MAX_VALUE
    }
}
