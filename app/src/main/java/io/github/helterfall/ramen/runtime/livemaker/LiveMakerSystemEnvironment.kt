package io.github.helterfall.ramen.runtime.livemaker

import java.time.Clock
import java.time.LocalDateTime
import java.util.Locale

/**
 * Android-independent host snapshot for LiveNovel's system variables.
 *
 * Device-specific values are supplied by the player. Input values deliberately default to
 * neutral values until the Android host has real pointer/key state to report.
 */
class LiveMakerSystemEnvironment(
    private val clock: Clock = Clock.systemDefaultZone(),
    screenWidthPx: Int = 0,
    screenHeightPx: Int = 0,
    title: String = "",
    isDebug: Boolean = false,
    fileExistsResolver: (String) -> Boolean = { false },
    commandLineArguments: List<String> = emptyList(),
    availableMidiPorts: List<String> = emptyList(),
    fontNames: List<String> = emptyList(),
    fixedFontNames: List<String> = emptyList(),
    senderName: String = ""
) {
    @Volatile
    var screenWidthPx: Int = screenWidthPx.coerceAtLeast(0)

    @Volatile
    var screenHeightPx: Int = screenHeightPx.coerceAtLeast(0)

    @Volatile
    var title: String = title

    @Volatile
    var isDebug: Boolean = isDebug

    @Volatile
    var fileExistsResolver: (String) -> Boolean = fileExistsResolver

    @Volatile
    var additionalFileExistsResolver: (String) -> Boolean = { false }

    @Volatile
    var readTextFileResolver: (String) -> List<String>? = { null }

    @Volatile
    var pcTickCountResolver: () -> Long = { elapsedMilliseconds() }

    private val startedAtNanos = System.nanoTime()
    private val arguments = commandLineArguments.toList()
    private val midiPorts = availableMidiPorts.toList()
    private val fonts = fontNames.joinToString("\n")
    private val fixedFonts = fixedFontNames.joinToString("\n")

    @Volatile
    var senderName: String = senderName

    private data class KeyState(val clicked: Boolean, val pressed: Boolean, val repeated: Boolean)
    private val keyStates = linkedMapOf<Int, KeyState>()
    private var leftClicked = false
    private var rightClicked = false
    private var leftPressed = false
    private var rightPressed = false

    private val writable = linkedMapOf<String, Any?>(
        "@MOUSEX" to 0,
        "@MOUSEY" to 0,
        "@FULLSCR" to false,
        "@SELINDEX" to -1,
        "@SELSTR" to "",
        "@DISPLAYMODEINDEX" to 0,
        "@RESULT" to null,
        "@TEMP" to null,
        "@SOUNDON" to true,
        "@TEXTDELAY" to 0,
        "@MIDIPORT" to "",
        "@ACTIVECOMPO" to "",
        "@CGDITHER" to false,
        "@CURSOR" to ""
    )

    fun now(): LocalDateTime = LocalDateTime.now(clock)

    @Synchronized
    fun updatePointer(
        x: Int,
        y: Int,
        leftClick: Boolean = false,
        rightClick: Boolean = false,
        leftPush: Boolean = false,
        rightPush: Boolean = false
    ) {
        writable["@MOUSEX"] = x.coerceAtLeast(0)
        writable["@MOUSEY"] = y.coerceAtLeast(0)
        leftClicked = leftClick
        rightClicked = rightClick
        leftPressed = leftPush
        rightPressed = rightPush
    }

    @Synchronized
    fun updateKeyState(keyCode: Int, clicked: Boolean, pressed: Boolean, repeated: Boolean) {
        require(keyCode >= 0) { "LiveMaker key code must be non-negative." }
        keyStates[keyCode] = KeyState(clicked, pressed, repeated)
    }

    fun elapsedMilliseconds(): Long =
        ((System.nanoTime() - startedAtNanos).coerceAtLeast(0L) / 1_000_000L)

    fun fileExists(path: String): Boolean =
        runCatching {
            fileExistsResolver(path) || additionalFileExistsResolver(path)
        }.getOrDefault(false)

    fun readTextFile(path: String): List<String>? =
        runCatching { readTextFileResolver(path) }.getOrNull()

    @Synchronized
    fun read(
        name: String,
        historyCount: Int,
        historyMaxCount: Int,
        stackCount: Int
    ): Any? {
        val normalized = normalize(name)
        val indexedInput = INDEXED_INPUT_VARIABLE.matchEntire(normalized)
        if (indexedInput != null) {
            val keyCode = indexedInput.groupValues[2].toIntOrNull() ?: return false
            val state = keyStates[keyCode] ?: return false
            return when (indexedInput.groupValues[1]) {
                "KEYCLICK" -> state.clicked
                "KEYPUSH" -> state.pressed
                else -> state.repeated
            }
        }
        INDEXED_STRING_VARIABLE.matchEntire(normalized)?.let { indexed ->
            val index = indexed.groupValues[2].toIntOrNull() ?: return null
            return when (indexed.groupValues[1]) {
                "PARAMSTR" -> arguments.getOrNull(index) ?: ""
                "MIDIPORTS" -> midiPorts.getOrNull(index) ?: ""
                else -> ""
            }
        }

        val current = now()
        return when (normalized) {
            "@YEAR" -> current.year
            "@MONTH" -> current.monthValue
            "@DAY" -> current.dayOfMonth
            "@WEEK" -> current.dayOfWeek.value % 7 + 1 // LiveMaker: Sunday=1, Saturday=7.
            "@HOUR" -> current.hour
            "@MIN", "@MINUTE" -> current.minute
            "@SEC", "@SECOND" -> current.second
            "@1HOUR" -> 1.0 / 24.0
            "@1MIN" -> 1.0 / 1_440.0
            "@1SEC" -> 1.0 / 86_400.0
            "@MOUSEX", "@MOUSEY", "@FULLSCR", "@SELINDEX", "@SELSTR",
            "@DISPLAYMODEINDEX", "@RESULT", "@TEMP", "@SOUNDON", "@TEXTDELAY",
            "@MIDIPORT", "@ACTIVECOMPO", "@CGDITHER", "@CURSOR" -> writable[normalized]
            "@LCLICK" -> leftClicked
            "@RCLICK" -> rightClicked
            "@LPUSH" -> leftPressed
            "@RPUSH" -> rightPressed
            "@PI" -> Math.PI
            "@TICKCOUNT" -> elapsedMilliseconds()
            // Android's monotonic uptime is process-provided here; exact boot uptime can be
            // injected by a platform host in a future adapter.
            "@PCTICKCOUNT" -> pcTickCountResolver()
            "@TITLE" -> title
            "@SCRWIDTH" -> screenWidthPx
            "@SCRHEIGHT" -> screenHeightPx
            "@HISTORYCOUNT" -> historyCount
            "@HISTORYMAXCOUNT" -> historyMaxCount
            "@FIXEDFONTS" -> fixedFonts
            "@FONTS" -> fonts
            "@SENDER" -> senderName
            "@DISPLAYMODECOUNT" -> 1
            "@ISDEBUG" -> isDebug
            "@PARAMSTR" -> arguments
            "@MIDIPORTS" -> midiPorts
            "@STACKCOUNT" -> stackCount
            else -> null
        }
    }

    @Synchronized
    fun write(name: String, value: Any?): Boolean {
        val normalized = normalize(name)
        if (normalized !in WRITABLE_VARIABLES) return false
        writable[normalized] = when (normalized) {
            "@MOUSEX", "@MOUSEY", "@SELINDEX", "@DISPLAYMODEINDEX", "@TEXTDELAY" ->
                (value as? Number)?.toInt() ?: value?.toString()?.toIntOrNull() ?: 0
            "@FULLSCR", "@SOUNDON", "@CGDITHER" -> toBoolean(value)
            "@SELSTR", "@MIDIPORT", "@ACTIVECOMPO", "@CURSOR" -> value?.toString() ?: ""
            else -> value
        }
        return true
    }

    private fun normalize(name: String): String = name.trim().uppercase(Locale.ROOT)

    private fun toBoolean(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toDouble() != 0.0
        is String -> value.equals("true", ignoreCase = true) || value == "1"
        else -> false
    }

    companion object {
        private val INDEXED_INPUT_VARIABLE =
            Regex("@(KEYCLICK|KEYPUSH|KEYREPEAT)\\[(\\d+)]")

        private val INDEXED_STRING_VARIABLE =
            Regex("@(PARAMSTR|MIDIPORTS)\\[(\\d+)]")

        private val WRITABLE_VARIABLES = setOf(
            "@MOUSEX", "@MOUSEY", "@FULLSCR", "@SELINDEX", "@SELSTR",
            "@DISPLAYMODEINDEX", "@RESULT", "@TEMP", "@SOUNDON", "@TEXTDELAY",
            "@MIDIPORT", "@ACTIVECOMPO", "@CGDITHER", "@CURSOR"
        )
    }
}
