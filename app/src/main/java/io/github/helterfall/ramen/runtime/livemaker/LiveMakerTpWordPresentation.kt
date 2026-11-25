package io.github.helterfall.ramen.runtime.livemaker

/**
 * Applies the wait flag from the containing LSB TextIns command to the TpWord body.
 *
 * A valid TextIns can contain only embedded events (for example, an initial menu/logo
 * transition) and therefore have no dialogue glyphs. The LSB Wait flag still represents
 * a real player boundary in that case; dropping it causes the host to auto-run through
 * subsequent pages/events until the presentation budget is exhausted.
 */
internal fun applyLiveMakerTextBlockWait(
    segments: List<LiveMakerTextSegment>,
    shouldWait: Boolean
): List<LiveMakerTextSegment> {
    if (!shouldWait) return segments
    if (segments.isEmpty()) {
        return listOf(LiveMakerTextSegment("", waitForTap = true))
    }

    val result = segments.toMutableList()
    val lastIndex = result.lastIndex
    if (!result[lastIndex].waitForTap) {
        result[lastIndex] = result[lastIndex].copy(waitForTap = true)
    }
    return result
}
