package io.github.helterfall.ramen.nscripter

import java.io.IOException
import java.io.Serializable
import java.util.Calendar
import java.util.Locale

data class NscripterSpriteState(
    val number: Int, val image: String, val x: Int, val y: Int,
    val opacity: Int = 255, val visible: Boolean = true,
    val transparency: String = "a", val maskFile: String? = null, val colorKey: String? = null
) : Serializable

data class NscripterHotspot(
    val buttonNumber: Int, val x: Int, val y: Int, val width: Int, val height: Int,
    val spriteNumber: Int? = null,
    val label: String = ""
) : Serializable

data class NscripterChoice(val label: String, val destination: String) : Serializable
data class NscripterMenuItem(val label: String, val action: String) : Serializable

data class NscripterTextWindow(
    val x: Int = 0, val y: Int = 320, val width: Int = 640, val height: Int = 145,
    val textX: Int = 18, val textY: Int = 14, val columns: Int = 23,
    val lines: Int = 16, val fontSizeX: Int = 26, val fontSizeY: Int = 26,
    val color: String = "#999999", val backgroundImage: String? = null
) : Serializable

data class NscripterSceneState(
    val background: String? = null, val backgroundColor: String? = "#000000",
    val backgroundEffect: Int = 1, val sprites: List<NscripterSpriteState> = emptyList(),
    val buttons: List<NscripterHotspot> = emptyList(),
    val choices: List<NscripterChoice> = emptyList(),
    val menu: List<NscripterMenuItem> = emptyList(), val dialogue: String = "",
    val speaker: String? = null, val caption: String = "Ramen",
    val textWindow: NscripterTextWindow = NscripterTextWindow(),
    val textWindowVisible: Boolean = true, val windowBack: Boolean = false,
    val windowEffect: Int = 10, val windowEffectMillis: Int = 0
) : Serializable

data class NscripterSavedGame(
    val programCounter: Int, val integerVariables: HashMap<String, Int>,
    val stringVariables: HashMap<String, String>, val scene: NscripterSceneState,
    val savedAtEpochMillis: Long
) : Serializable

interface NscripterSaveStore {
    fun exists(slot: Int): Boolean
    fun save(slot: Int, savedGame: NscripterSavedGame)
    fun load(slot: Int): NscripterSavedGame?
}

sealed class NscripterVmYield {
    data class Frame(val scene: NscripterSceneState) : NscripterVmYield()
    data class Dialogue(val scene: NscripterSceneState) : NscripterVmYield()
    data class TimedWait(val milliseconds: Int, val scene: NscripterSceneState) : NscripterVmYield()
    data class ButtonWait(val scene: NscripterSceneState, val textButton: Boolean) : NscripterVmYield()
    data class ChoiceWait(val scene: NscripterSceneState) : NscripterVmYield()
    data class Finished(val scene: NscripterSceneState) : NscripterVmYield()
}

data class NscripterAudioAction(
    val channel: Int, val fileName: String?, val loop: Boolean, val stop: Boolean
)

class NscripterVirtualMachine(
    private val program: NscripterScriptProgram,
    private val saveStore: NscripterSaveStore
) {
    private data class LoopFrame(
        val variable: String, val endValue: Int, val step: Int, val bodyPc: Int
    )
    private enum class WaitState { NONE, TAP, TIMER, BUTTON, TEXT_BUTTON, CHOICE }

    private var pc = program.defineInstruction ?: program.startInstruction
    private var waitState = WaitState.NONE
    private var buttonResultVariable: String? = null
    private val loops = ArrayList<LoopFrame>()
    private val callStack = java.util.ArrayDeque<Int>()
    private val integerVariables = HashMap<String, Int>()
    private val stringVariables = HashMap<String, String>()
    private val numericAliases = HashMap<String, Int>()
    private val integerLimits = HashMap<String, IntRange>()
    private val audioActions = ArrayList<NscripterAudioAction>()
    private var saveEnabled = true
    private var saveCapacity = 9
    var archiveEnabled: Boolean = true
        private set
    private var scene = NscripterSceneState(
        textWindow = NscripterTextWindow(
            width = program.screenWidth, y = program.screenHeight * 2 / 3
        )
    )

    fun resume(): NscripterVmYield {
        when (waitState) {
            WaitState.TAP -> return NscripterVmYield.Dialogue(scene)
            WaitState.TIMER -> return NscripterVmYield.TimedWait(0, scene)
            WaitState.BUTTON, WaitState.TEXT_BUTTON ->
                return NscripterVmYield.ButtonWait(scene, waitState == WaitState.TEXT_BUTTON)
            WaitState.CHOICE -> return NscripterVmYield.ChoiceWait(scene)
            WaitState.NONE -> Unit
        }

        var executed = 0
        while (pc in program.instructions.indices && executed++ < MAX_COMMANDS_PER_SLICE) {
            val instruction = program.instructions[pc++]
            val args = splitNscripterArguments(instruction.arguments)
            when (instruction.command) {
                "text" -> {
                    scene = scene.copy(
                        dialogue = interpolate(instruction.arguments),
                        speaker = instruction.speaker ?: scene.speaker,
                        choices = emptyList()
                    )
                    waitState = WaitState.TAP
                    return NscripterVmYield.Dialogue(scene)
                }
                "bg" -> {
                    val image = args.firstOrNull()?.let(::resolveString)
                    val effect = args.getOrNull(1)?.let(::value) ?: 1
                    scene = if (image.isNullOrBlank()) {
                        scene.copy(background = null, backgroundColor = "#000000", backgroundEffect = effect)
                    } else if (image.equals("black", true) || image.equals("white", true) || image.startsWith("#")) {
                        scene.copy(background = null, backgroundColor = image, backgroundEffect = effect)
                    } else {
                        scene.copy(background = image, backgroundColor = null, backgroundEffect = effect)
                    }
                }
                "lsp", "lsph" -> {
                    requireArgs(instruction, args, 4)
                    val number = value(args[0])
                    if (number !in 0..999) throw IOException("lsp sprite number must be in 0..999.")
                    val sprite = NscripterSpriteState(
                        number, resolveString(args[1]), value(args[2]), value(args[3]),
                        args.getOrNull(5)?.let(::value)?.coerceIn(0, 255) ?: 255,
                        instruction.command == "lsp",
                        args.getOrNull(4)?.let(::resolveString)?.lowercase() ?: "a",
                        args.getOrNull(6)?.let(::resolveString),
                        args.getOrNull(7)?.let(::resolveString)
                    )
                    scene = scene.copy(sprites = scene.sprites.filterNot { it.number == number } + sprite)
                }
                "csp" -> {
                    val number = value(args.firstOrNull() ?: "0")
                    scene = scene.copy(sprites = if (number < 0) emptyList()
                        else scene.sprites.filterNot { it.number == number })
                }
                "print" -> return NscripterVmYield.Frame(scene)
                "windowback" -> scene = scene.copy(windowBack = true)
                "windoweffect" -> scene = scene.copy(
                    windowEffect = args.getOrNull(0)?.let(::value) ?: 10,
                    windowEffectMillis = args.getOrNull(1)?.let(::value)?.coerceIn(0, 10000) ?: 0
                )
                "setwindow" -> {
                    requireArgs(instruction, args, 14)
                    val old = scene.textWindow
                    val left = args.getOrNull(12)?.let(::value) ?: 0
                    val top = args.getOrNull(13)?.let(::value) ?: 0
                    val right = args.getOrNull(14)?.let(::value) ?: (program.screenWidth - 1)
                    val bottom = args.getOrNull(15)?.let(::value) ?: (program.screenHeight - 1)
                    scene = scene.copy(
                        textWindow = NscripterTextWindow(
                            x = left, y = top,
                            width = (right - left + 1).coerceAtLeast(1),
                            height = (bottom - top + 1).coerceAtLeast(1),
                            textX = args.getOrNull(0)?.let(::value) ?: old.textX,
                            textY = args.getOrNull(1)?.let(::value) ?: old.textY,
                            columns = args.getOrNull(2)?.let(::value) ?: old.columns,
                            lines = args.getOrNull(3)?.let(::value) ?: old.lines,
                            fontSizeX = args.getOrNull(4)?.let(::value) ?: old.fontSizeX,
                            fontSizeY = args.getOrNull(5)?.let(::value) ?: old.fontSizeY,
                            color = args.getOrNull(11)?.let(::resolveString) ?: old.color
                        ),
                        textWindowVisible = true
                    )
                }
                "btn" -> {
                    requireArgs(instruction, args, 2)
                    val id = value(args[0])
                    val labelOrX = args[1]
                    val isLegacyGeometry = labelOrX.trim().toIntOrNull() != null
                    val button = if (isLegacyGeometry) {
                        NscripterHotspot(
                            id, value(args[1]), value(args.getOrNull(2) ?: "0"),
                            value(args.getOrNull(3) ?: "140").coerceAtLeast(1),
                            value(args.getOrNull(4) ?: "42").coerceAtLeast(1)
                        )
                    } else {
                        NscripterHotspot(
                            id, value(args.getOrNull(2) ?: "0"), value(args.getOrNull(3) ?: "0"),
                            value(args.getOrNull(4) ?: "180").coerceAtLeast(1),
                            value(args.getOrNull(5) ?: "44").coerceAtLeast(1),
                            label = resolveString(labelOrX)
                        )
                    }
                    scene = scene.copy(buttons = scene.buttons.filterNot { it.buttonNumber == id } + button)
                }
                "spbtn" -> {
                    requireArgs(instruction, args, 2)
                    val spriteNo = value(args[0])
                    val id = value(args[1])
                    val sprite = scene.sprites.firstOrNull { it.number == spriteNo }
                        ?: throw IOException("spbtn references missing sprite " + spriteNo)
                    scene = scene.copy(buttons = scene.buttons.filterNot { it.buttonNumber == id } +
                        NscripterHotspot(id, sprite.x, sprite.y, -1, -1, spriteNo))
                }
                "exbtn" -> {
                    requireArgs(instruction, args, 3)
                    val spriteNo = value(args[0])
                    val id = value(args[1])
                    val sprite = scene.sprites.firstOrNull { it.number == spriteNo }
                        ?: throw IOException("exbtn references missing sprite " + spriteNo)
                    scene = scene.copy(buttons = scene.buttons.filterNot { it.buttonNumber == id } +
                        NscripterHotspot(id, sprite.x, sprite.y, -1, -1, spriteNo))
                    applySpriteControl(resolveString(args[2]))
                }
                "exbtn_d" -> applySpriteControl(resolveString(args.firstOrNull() ?: ""))
                "btnwait" -> {
                    buttonResultVariable = args.firstOrNull()
                    waitState = WaitState.BUTTON
                    return NscripterVmYield.ButtonWait(scene, false)
                }
                "textbtnwait" -> {
                    buttonResultVariable = args.firstOrNull()
                    waitState = WaitState.TEXT_BUTTON
                    return NscripterVmYield.ButtonWait(scene, true)
                }
                "csel" -> {
                    val pairs = args.chunked(2)
                    val choices = pairs.mapNotNull { pair ->
                        if (pair.size != 2) null else NscripterChoice(
                            resolveString(pair[0]), pair[1].trim().removePrefix("*").lowercase()
                        )
                    }
                    if (choices.isEmpty()) throw IOException("csel requires choice/label pairs.")
                    scene = scene.copy(choices = choices, dialogue = "")
                    waitState = WaitState.CHOICE
                    return NscripterVmYield.ChoiceWait(scene)
                }
                "rmenu" -> {
                    scene = scene.copy(menu = args.chunked(2).mapNotNull { pair ->
                        if (pair.size != 2) null
                        else NscripterMenuItem(resolveString(pair[0]), pair[1].trim().lowercase())
                    })
                }
                "dwave", "dwaveloop" -> {
                    val channel = args.getOrNull(0)?.let(::value) ?: 0
                    val file = args.getOrNull(1)?.let(::resolveString)
                    if (!file.isNullOrBlank()) audioActions += NscripterAudioAction(
                        channel, file, instruction.command == "dwaveloop", false
                    )
                }
                "dwavestop" -> audioActions += NscripterAudioAction(
                    args.firstOrNull()?.let(::value) ?: 0, null, false, true
                )
                "stop" -> audioActions += NscripterAudioAction(-1, null, false, true)
                "numalias" -> {
                    requireArgs(instruction, args, 2)
                    numericAliases[args[0].trim().lowercase(Locale.ROOT)] = value(args[1])
                }
                "intlimit" -> {
                    requireArgs(instruction, args, 3)
                    val key = normalizeVariable(args[0])
                    val min = value(args[1])
                    val max = value(args[2])
                    if (min > max) throw IOException("intlimit minimum exceeds maximum.")
                    integerLimits[key] = min..max
                    setInteger(key, integerVariables[key] ?: 0)
                }
                "add" -> {
                    requireArgs(instruction, args, 2)
                    val raw = args[0].trim()
                    val key = normalizeVariable(raw)
                    if (raw.startsWith("\$")) stringVariables[key] =
                        (stringVariables[key] ?: "") + resolveString(args[1])
                    else setInteger(key, (integerVariables[key] ?: 0) + value(args[1]))
                }
                "if" -> executeIf(instruction)
                "for" -> beginFor(instruction)
                "next" -> finishFor()
                "goto" -> jump(args.firstOrNull())
                "gosub" -> { callStack.addLast(pc); jump(args.firstOrNull()) }
                "return" -> {
                    if (callStack.isEmpty()) throw IOException("return without gosub.")
                    pc = callStack.removeLast()
                }
                "wait" -> {
                    val millis = value(args.firstOrNull() ?: "0").coerceIn(0, 60000)
                    waitState = WaitState.TIMER
                    return NscripterVmYield.TimedWait(millis, scene)
                }
                "caption" -> scene = scene.copy(caption = resolveString(args.firstOrNull() ?: "Ramen"))
                "game" -> pc = program.startInstruction
                "end" -> return NscripterVmYield.Finished(scene)
                "nsa" -> archiveEnabled = true
                "savefileexist" -> {
                    requireArgs(instruction, args, 2)
                    setInteger(normalizeVariable(args[0]), if (saveStore.exists(value(args[1]))) 1 else 0)
                }
                "savenumber" -> saveCapacity = value(args.firstOrNull() ?: "9").coerceIn(1, 20)
                "saveon" -> saveEnabled = true
                "savetime" -> {
                    requireArgs(instruction, args, 5)
                    val stored = saveStore.load(value(args[0]))
                    val calendar = Calendar.getInstance()
                    if (stored != null) calendar.timeInMillis = stored.savedAtEpochMillis
                    val values = if (stored == null) listOf(0, 0, 0, 0) else listOf(
                        calendar.get(Calendar.MONTH) + 1,
                        calendar.get(Calendar.DAY_OF_MONTH),
                        calendar.get(Calendar.HOUR_OF_DAY),
                        calendar.get(Calendar.MINUTE)
                    )
                    args.drop(1).take(4).zip(values).forEach { pair ->
                        setInteger(normalizeVariable(pair.first), pair.second)
                    }
                }
                else -> {
                    // Static parser recognized a valid NScripter command whose
                    // runtime behavior is not implemented yet. Skip it safely;
                    // it must never be displayed as a dialogue line.
                }
            }
        }

        if (executed >= MAX_COMMANDS_PER_SLICE) {
            throw IOException("NScripter instruction budget exceeded (possible infinite loop).")
        }
        return NscripterVmYield.Finished(scene)
    }

    fun acceptTap() {
        if (waitState == WaitState.TAP || waitState == WaitState.TIMER) {
            waitState = WaitState.NONE
            scene = scene.copy(dialogue = "")
        }
    }

    fun completeTimedWait() {
        if (waitState == WaitState.TIMER) waitState = WaitState.NONE
    }

    fun acceptButton(number: Int) {
        if (waitState != WaitState.BUTTON && waitState != WaitState.TEXT_BUTTON) return
        buttonResultVariable?.let { setInteger(normalizeVariable(it), number) }
        buttonResultVariable = null
        waitState = WaitState.NONE
        scene = scene.copy(buttons = emptyList())
    }

    fun acceptChoice(index: Int) {
        if (waitState != WaitState.CHOICE) return
        val selected = scene.choices.getOrNull(index) ?: return
        waitState = WaitState.NONE
        scene = scene.copy(choices = emptyList(), dialogue = "")
        jump(selected.destination)
    }

    fun performMenuAction(action: String, slot: Int = 1): Boolean {
        val safeSlot = slot.coerceIn(0, saveCapacity - 1)
        when (action.lowercase()) {
            "save" -> {
                if (!saveEnabled) return false
                saveStore.save(safeSlot, createSave())
            }
            "load" -> {
                val saved = saveStore.load(safeSlot) ?: return false
                restoreSave(saved)
            }
            "lookback", "skip", "auto" -> return true
            else -> return false
        }
        waitState = WaitState.NONE
        return true
    }

    fun takeAudioActions(): List<NscripterAudioAction> {
        val copy = audioActions.toList()
        audioActions.clear()
        return copy
    }

    fun snapshot(): NscripterSceneState = scene
    fun saveSlots(): Int = saveCapacity

    private fun executeIf(instruction: NscripterInstruction) {
        val match = Regex("^(.*?)(?:\\s+)(goto|gosub|return|end|add)\\s*(.*)$", RegexOption.IGNORE_CASE)
            .matchEntire(instruction.arguments)
        val condition = match?.groupValues?.get(1) ?: instruction.arguments
        if (!evaluateCondition(condition) || match == null) return
        val op = match.groupValues[2].lowercase()
        val rest = match.groupValues[3]
        when (op) {
            "goto" -> jump(rest)
            "gosub" -> { callStack.addLast(pc); jump(rest) }
            "return" -> if (callStack.isNotEmpty()) pc = callStack.removeLast()
            "end" -> pc = program.instructions.size
            "add" -> {
                val pair = splitNscripterArguments(rest)
                if (pair.size >= 2) {
                    val key = normalizeVariable(pair[0])
                    setInteger(key, (integerVariables[key] ?: 0) + value(pair[1]))
                }
            }
        }
    }

    private fun beginFor(instruction: NscripterInstruction) {
        val match = Regex("^\\s*(%[A-Za-z0-9_]+)\\s*=\\s*(.+?)\\s+to\\s+(.+?)(?:\\s+step\\s+(.+))?\\s*$", RegexOption.IGNORE_CASE)
            .matchEntire(instruction.arguments) ?: throw IOException("Invalid for syntax at line " + instruction.sourceLine)
        val key = normalizeVariable(match.groupValues[1])
        val first = value(match.groupValues[2])
        val last = value(match.groupValues[3])
        val step = match.groupValues[4].takeIf { it.isNotBlank() }?.let(::value) ?: 1
        if (step == 0) throw IOException("for step cannot be zero.")
        setInteger(key, first)
        val enters = if (step > 0) first <= last else first >= last
        if (enters) loops += LoopFrame(key, last, step, pc)
        else pc = findMatchingNext(pc) + 1
    }

    private fun finishFor() {
        val frame = loops.lastOrNull() ?: return
        val next = (integerVariables[frame.variable] ?: 0) + frame.step
        setInteger(frame.variable, next)
        val repeats = if (frame.step > 0) next <= frame.endValue else next >= frame.endValue
        if (repeats) pc = frame.bodyPc else loops.removeAt(loops.lastIndex)
    }

    private fun findMatchingNext(from: Int): Int {
        var nested = 0
        for (index in from until program.instructions.size) {
            when (program.instructions[index].command) {
                "for" -> nested++
                "next" -> if (nested == 0) return index else nested--
            }
        }
        throw IOException("for has no matching next.")
    }

    private fun evaluateCondition(expression: String): Boolean {
        val match = Regex("^\\s*(.*?)\\s*(==|!=|>=|<=|=|>|<)\\s*(.*?)\\s*$")
            .matchEntire(expression) ?: throw IOException("Unsupported if condition: " + expression)
        val left = value(match.groupValues[1])
        val right = value(match.groupValues[3])
        return when (match.groupValues[2]) {
            "=", "==" -> left == right
            "!=" -> left != right
            ">" -> left > right
            "<" -> left < right
            ">=" -> left >= right
            "<=" -> left <= right
            else -> false
        }
    }

    private fun jump(raw: String?) {
        val label = raw?.trim()?.removePrefix("*")?.lowercase()
            ?: throw IOException("Jump requires a destination label.")
        pc = program.labels[label] ?: throw IOException("Unknown NScripter label '*" + label + "'.")
    }

    private fun value(raw: String): Int {
        val token = raw.trim()
        if (token.startsWith("%")) return integerVariables[normalizeVariable(token)] ?: 0
        numericAliases[token.removePrefix("%").lowercase(Locale.ROOT)]?.let { return it }
        return token.toIntOrNull() ?: token.removePrefix("0x").toIntOrNull(16)
            ?: throw IOException("Unsupported numeric expression: " + raw)
    }

    private fun resolveString(raw: String): String = interpolate(nscripterUnquote(raw))

    private fun interpolate(raw: String): String =
        Regex("([%\\$])([A-Za-z0-9_]+)").replace(nscripterUnquote(raw)) { match ->
            val key = match.groupValues[2].lowercase(Locale.ROOT)
            if (match.groupValues[1] == "%") {
                (integerVariables[key] ?: numericAliases[key] ?: 0).toString()
            } else stringVariables[key] ?: ""
        }

    private fun normalizeVariable(raw: String): String =
        raw.trim().removePrefix("%").removePrefix("\$").lowercase(Locale.ROOT)

    private fun setInteger(key: String, value: Int) {
        integerVariables[key] = integerLimits[key]?.let { value.coerceIn(it.first, it.last) } ?: value
    }

    private fun applySpriteControl(control: String) {
        val sprites = scene.sprites.associateBy { it.number }.toMutableMap()
        Regex("([PC])(\\d+)(?:,(\\d+))?", RegexOption.IGNORE_CASE).findAll(control).forEach { match ->
            val op = match.groupValues[1].uppercase()
            val number = match.groupValues[2].toIntOrNull() ?: return@forEach
            if (op == "C") sprites.remove(number)
            else sprites[number]?.let { sprites[number] = it.copy(visible = true) }
        }
        scene = scene.copy(sprites = sprites.values.sortedBy { it.number })
    }

    private fun createSave(): NscripterSavedGame = NscripterSavedGame(
        pc, HashMap(integerVariables), HashMap(stringVariables), scene, System.currentTimeMillis()
    )

    private fun restoreSave(saved: NscripterSavedGame) {
        pc = saved.programCounter.coerceIn(0, program.instructions.size)
        integerVariables.clear()
        integerVariables.putAll(saved.integerVariables)
        stringVariables.clear()
        stringVariables.putAll(saved.stringVariables)
        scene = saved.scene
        loops.clear()
        callStack.clear()
    }

    private fun requireArgs(instruction: NscripterInstruction, args: List<String>, count: Int) {
        if (args.size < count) throw IOException(
            instruction.command + " requires " + count + " arguments at line " + instruction.sourceLine
        )
    }

    companion object {
        private const val MAX_COMMANDS_PER_SLICE = 100_000
    }
}
