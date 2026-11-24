package io.github.helterfall.ramen.runtime.livemaker

import io.github.helterfall.ramen.inspection.LiveMakerCommand
import io.github.helterfall.ramen.inspection.LiveMakerExpression
import java.util.ArrayDeque

data class LiveMakerTextHistoryFormat(
    val name: String,
    val target: String?
)

data class LiveMakerTextHistoryEntry(
    val text: String,
    val formatName: String?
)

data class LiveMakerTextHistorySnapshot(
    val entries: List<LiveMakerTextHistoryEntry>,
    val activeFormat: LiveMakerTextHistoryFormat?
)

/**
 * Host-side implementation of LiveMaker's text history.
 *
 * FormatHist (58) registers the named display format, ClrHist (22) clears
 * accumulated entries, and TextIns.Hist is the AddHist operation: it appends
 * the decoded text block when that flag evaluates to TRUE.
 */
class LiveMakerTextHistoryHostAdapter(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES
) {
    private val entries = ArrayDeque<LiveMakerTextHistoryEntry>()
    private val formats = linkedMapOf<String, LiveMakerTextHistoryFormat>()

    @Volatile
    private var currentFormat: LiveMakerTextHistoryFormat? = null

    init {
        require(maxEntries > 0) { "maxEntries must be positive." }
    }

    /** Applies the serialized history commands described by pylivemaker. */
    fun handle(
        command: LiveMakerCommand,
        evaluate: (LiveMakerExpression) -> Any?
    ): Boolean = when (command.typeId) {
        COMMAND_CLR_HIST -> {
            clear()
            true
        }
        COMMAND_FORMAT_HIST -> {
            val name = command.expressions.getOrNull(0)
                ?.let(evaluate)
                ?.toString()
                .orEmpty()
            val target = command.expressions.getOrNull(1)
                ?.let(evaluate)
                ?.toString()
                ?.takeIf { it.isNotBlank() }
            registerFormat(name, target)
            true
        }
        else -> false
    }

    /** Adds the decoded TextIns block when its Hist expression is TRUE. */
    @Synchronized
    fun addText(text: String, includeInHistory: Boolean): Boolean {
        if (!includeInHistory || text.isEmpty()) return false

        entries.addLast(
            LiveMakerTextHistoryEntry(
                text = text,
                formatName = currentFormat?.name
            )
        )
        while (entries.size > maxEntries) entries.removeFirst()
        return true
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun snapshot(): LiveMakerTextHistorySnapshot =
        LiveMakerTextHistorySnapshot(entries.toList(), currentFormat)

    @Synchronized
    private fun registerFormat(name: String, target: String?) {
        if (name.isBlank()) {
            currentFormat = null
            return
        }
        val format = LiveMakerTextHistoryFormat(name, target)
        formats[name] = format
        currentFormat = format
    }

    companion object {
        private const val COMMAND_CLR_HIST = 22
        private const val COMMAND_FORMAT_HIST = 58
        private const val DEFAULT_MAX_ENTRIES = 2_048
    }
}
