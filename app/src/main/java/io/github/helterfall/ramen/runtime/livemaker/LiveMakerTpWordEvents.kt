package io.github.helterfall.ramen.runtime.livemaker

/**
 * LiveNovel system-event arguments are usually positional, not NAME=value pairs.
 * The helper also accepts named fields found in diagnostic/repacked scripts.
 */
internal class LiveMakerTpWordArguments(arguments: List<String>) {
    private val raw = arguments
    private val positional = arguments.filterNot { it.contains('=') }

    fun get(position: Int, vararg names: String): String? {
        val named = raw.firstOrNull { item ->
            item.contains('=') && names.any { name ->
                item.substringBefore('=').trim().equals(name, ignoreCase = true)
            }
        }?.substringAfter('=', missingDelimiterValue = "")
        return clean(named) ?: clean(positional.getOrNull(position))
    }

    fun raw(position: Int): String? = clean(positional.getOrNull(position))

    fun csv(position: Int): List<String> =
        raw(position).orEmpty()
            .split(',')
            .mapNotNull(::clean)
            .distinct()
}

internal fun parseLiveMakerStageCoordinate(
    token: String?,
    stageExtent: Float,
    objectExtent: Float,
    horizontal: Boolean
): Float {
    val value = token?.trim().orEmpty()
    return when (value.uppercase()) {
        "L" -> if (horizontal) 0f else value.toFloatOrNull() ?: 0f
        "T" -> if (!horizontal) 0f else value.toFloatOrNull() ?: 0f
        "C" -> ((stageExtent - objectExtent) / 2f).coerceAtLeast(0f)
        "R" -> if (horizontal) (stageExtent - objectExtent).coerceAtLeast(0f)
            else value.toFloatOrNull() ?: 0f
        "B" -> if (!horizontal) (stageExtent - objectExtent).coerceAtLeast(0f)
            else value.toFloatOrNull() ?: 0f
        else -> value.toFloatOrNull() ?: 0f
    }
}

internal fun isLiveMakerBackgroundLayer(objectName: String, source: String): Boolean {
    val name = objectName.trim()
    val path = source.replace('\\', '/')
    return name == "000" ||
        name.equals("bg", ignoreCase = true) ||
        name.contains("background", ignoreCase = true) ||
        name.contains("back", ignoreCase = true) ||
        name.contains("背景") ||
        path.contains("/background/", ignoreCase = true) ||
        path.contains("background", ignoreCase = true) ||
        path.substringAfterLast('/').startsWith("bg", ignoreCase = true) ||
        path.contains("背景")
}

private fun clean(value: String?): String? =
    value?.trim()?.trim('"', '\'')?.takeIf { it.isNotEmpty() }
