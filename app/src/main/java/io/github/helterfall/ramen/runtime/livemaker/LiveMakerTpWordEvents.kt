package io.github.helterfall.ramen.runtime.livemaker

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

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

/**
 * Decodes LiveMaker's #ddd decimal-byte escape notation used in event resource paths.
 * Bytes are assembled before CP932 decoding so multi-byte Japanese characters survive.
 */
internal fun decodeLiveMakerEventEscapes(value: String): String {
    val bytes = ByteArrayOutputStream()
    var index = 0
    while (index < value.length) {
        if (value[index] == '#' && index + 3 < value.length &&
            value[index + 1].isDigit() &&
            value[index + 2].isDigit() &&
            value[index + 3].isDigit()
        ) {
            val decoded = value.substring(index + 1, index + 4).toIntOrNull()
            if (decoded != null && decoded <= 255) {
                bytes.write(decoded)
                index += 4
                continue
            }
        }
        value.substring(index, index + 1)
            .toByteArray(CP932)
            .forEach(bytes::write)
        index += 1
    }
    return String(bytes.toByteArray(), CP932)
}

private val CP932: Charset = Charset.forName("windows-31j")

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
