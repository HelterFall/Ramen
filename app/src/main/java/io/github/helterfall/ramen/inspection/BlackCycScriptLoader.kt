package io.github.helterfall.ramen.inspection

/**
 * Classification for an opaque Black Cyc script entry.
 *
 * The current repository does not contain a verified Black Cyc SPT header
 * specification or a Black Cyc command parser. Therefore this loader only
 * isolates .spt files when the surrounding game has an exact matching GPK/GTB
 * pair. It does not claim that an extension alone proves a script format.
 */
data class BlackCycScriptInfo(
    val entryName: String,
    val formatId: String = FORMAT_ID,
    val commandParsingSupported: Boolean = false,
    val previewStatus: String = "OPAQUE_SCRIPT"
) {
    companion object {
        const val FORMAT_ID = "blackcyc-spt"
    }
}

/**
 * Opaque loader boundary for Black Cyc SPT entries.
 *
 * Call this only after the game detector has established Black Cyc context
 * from a same-basename .gpk/.gtb pair.
 *
 * The returned metadata is deliberately not passed to a generic bytecode parser.
 */
class BlackCycScriptLoader {

    fun inspect(
        entryName: String,
        hasMatchingGpkGtbPair: Boolean
    ): BlackCycScriptInfo? {
        if (!hasMatchingGpkGtbPair) return null

        val normalizedName = entryName
            .replace('\\', '/')
            .substringAfterLast('/')

        if (!normalizedName.endsWith(".spt", ignoreCase = true)) {
            return null
        }

        return BlackCycScriptInfo(entryName = entryName)
    }

    /**
     * True means the caller must leave this entry opaque and skip any
     * application-specific ScriptCommandParser invocation.
     */
    fun shouldBypassCommandParser(info: BlackCycScriptInfo): Boolean {
        return info.formatId == BlackCycScriptInfo.FORMAT_ID &&
            !info.commandParsingSupported
    }
}
