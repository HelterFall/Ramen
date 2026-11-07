package io.github.helterfall.ramen.runtime.systemnnn

import io.github.helterfall.ramen.parser.SystemNnnCommandRecord
import io.github.helterfall.ramen.parser.SystemNnnCommandStream
import io.github.helterfall.ramen.parser.SystemNnnCommandStreamReader
import io.github.helterfall.ramen.parser.SystemNnnRecordKind
import io.github.helterfall.ramen.parser.SystemNnnScript
import java.io.File
import java.io.IOException

/**
 * First executable subset of the System-NNN VM.
 *
 * The record envelope and message-reference layout are verified against the
 * bundled SPT modules and the public reference runtime. PRINT, LPRINT and
 * APPEND resolve their first payload DWORD through the module's primary text
 * pool. SETFILM is additionally validated against the descriptor-3 film table;
 * local SUB/RETURN uses a bounded in-module stack. IF/ELSIF/ELSE evaluates
 * the target RPN conditions and bounded brace spans; the false path skips a
 * branch body and resumes at the following ELSIF/ELSE marker. CALCU divide and
 * modulo by zero follow the target executable's divisor-to-one fallback.
 * SCRIPT/SUBSCRIPT push a project-module frame, SCRIPTJUMP replaces the active
 * frame, NEXT returns to the saved caller continuation, and END restarts sys.
 */
class SystemNnnCommandDecoderImpl(
    private val gtbResourceCount: Int,
    private val streamReader: SystemNnnCommandStreamReader = SystemNnnCommandStreamReader(),
    private val variables: MutableMap<Int, Int> = HashMap(),
    private val debugLogFile: File? = null,
    private val moduleName: String = "unknown"
) : SystemNnnCommandDecoder {

    private var cachedScript: SystemNnnScript? = null
    private var cachedStream: SystemNnnCommandStream? = null
    private var cachedFilmTableEntries: List<io.github.helterfall.ramen.parser.SystemNnnFilmTableEntry>? = null
    private val localSubroutineStack = ArrayList<LocalSubroutineFrame>()

    init {
        require(gtbResourceCount >= 0) { "gtbResourceCount must not be negative." }
    }

    @Synchronized
    override fun decodeNext(
        script: SystemNnnScript,
        commandOffset: Long
    ): SystemNnnDecodedCommand {
        val stream = streamFor(script)
        if (commandOffset == stream.recordEndOffsetExclusive) {
            if (localSubroutineStack.isNotEmpty()) {
                throw IOException("The SPT command stream ended before a local SUB returned.")
            }
            return SystemNnnDecodedCommand.End
        }

        val recordIndex = stream.records.binarySearchBy(commandOffset) { it.offset }
        if (recordIndex < 0) {
            throw IOException("The SPT command cursor is not on a record boundary.")
        }
        val record = stream.records[recordIndex]

        return try {
            when (record.recordKind) {
                SystemNnnRecordKind.DATA ->
                    SystemNnnDecodedCommand.Skip(record.endOffsetExclusive)

                SystemNnnRecordKind.SYSTEM_FUNCTION ->
                    decodeSystemFunction(script, stream, record)

                SystemNnnRecordKind.SYSTEM_COMMAND -> decodeSystemCommand(script, record)

                SystemNnnRecordKind.CONTROL -> decodeControl(script, stream, record)

                SystemNnnRecordKind.CALCULATION -> {
                    evaluateCalculation(script, record)
                    SystemNnnDecodedCommand.Skip(record.endOffsetExclusive)
                }

                SystemNnnRecordKind.FUNCTION -> decodeUserFunction(script, record)

                SystemNnnRecordKind.COMMAND,
                null -> skipUnsupported(record, "unknown record identifier", script)
            }
        } catch (failure: UnsupportedSystemNnnRecordException) {
            skipUnsupported(record, failure.message ?: "unsupported command payload", script)
        }
    }

    private fun decodeSystemCommand(
        script: SystemNnnScript,
        record: SystemNnnCommandRecord
    ): SystemNnnDecodedCommand {
        if (record.codeFamily != SYSTEM_COMMAND_FAMILY) {
            return skipUnsupported(record, "unknown or unsupported command code", script)
        }

        return when (record.codeId) {
            CODE_PRINT,
            CODE_LPRINT,
            CODE_APPEND -> {
                if (record.payloadWordCount < MESSAGE_REFERENCE_WORD_COUNT) {
                    throw IOException("A System-NNN print command has a truncated message reference.")
                }

                val messageIndex = readPayloadWord(script, record, MESSAGE_INDEX_PAYLOAD_WORD).toLong()
                val textEntry = script.textPool.getOrNull(messageIndex.toInt())
                    ?.takeIf { messageIndex <= Int.MAX_VALUE.toLong() }
                    ?: throw IOException(
                        "A System-NNN print command references missing primary text entry " +
                            messageIndex + "."
                    )

                SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = record.endOffsetExclusive,
                    textEntryOffset = textEntry.offset
                )
            }

            // The pinned native ExecScript reads the first payload DWORD as a
            // draw-frame count. Zero continues immediately; nonzero returns a
            // DRAW event after advancing PC by the declared record length.
            CODE_DRAW -> {
                // Payload word 0 is the parameter count; the first DRAW
                // operand is the following DWORD (native m_data[m_pc + 4]).
                if (record.payloadWordCount < 2) {
                    throw IOException("A System-NNN DRAW command has no frame-count operand.")
                }
                val frameCount = readPayloadWord(script, record, 1).toInt()
                if (frameCount == 0) {
                    SystemNnnDecodedCommand.Skip(record.endOffsetExclusive)
                } else {
                    SystemNnnDecodedCommand.Draw(record.endOffsetExclusive, frameCount)
                }
            }

            CODE_SELECT -> decodeSelect(script, streamFor(script), record)
            CODE_OVERRAP -> skipKnownUnimplemented(
                record, script, "OVERLAP transition capture/composition is not implemented in the Android player."
            )
            else -> return skipUnsupported(record, "unknown or unsupported command code", script)
        }
    }

    private fun decodeSelect(
        script: SystemNnnScript,
        stream: SystemNnnCommandStream,
        record: SystemNnnCommandRecord
    ): SystemNnnDecodedCommand.Choice {
        if (record.payloadWordCount < 1) {
            throw IOException("A System-NNN select command has no option count.")
        }

        val optionCountValue = readPayloadWord(script, record, 0).toLong()
        if (optionCountValue !in 1L..MAX_SELECT_OPTIONS.toLong()) {
            throw IOException("A System-NNN select command has an invalid option count.")
        }
        val optionCount = optionCountValue.toInt()

        // Native gss.exe treats length == optionCount + 5 as the legacy
        // zero-prefix form. Extended records store prefix count in the final
        // DWORD and keep three opaque metadata DWORDs before it. The native
        // SELECT handler forwards two metadata words to the UI callback but
        // does not read the earlier word (the suspected selectSerial field).
        // No nonzero metadata values occur in the cleaned primary SPT set.
        val prefixCount = if (record.lengthWords.toLong() == optionCountValue + 5L) {
            0
        } else {
            if (record.payloadWordCount < 2) {
                throw IOException("A System-NNN select command has a truncated header.")
            }
            val value = readPayloadWord(script, record, record.payloadWordCount - 1).toLong()
            if (value !in 0L..MAX_SELECT_OPTIONS.toLong()) {
                throw IOException("A System-NNN select command has an invalid prefix count.")
            }
            value.toInt()
        }

        val totalTextCount = optionCount + prefixCount
        if (1 + totalTextCount > record.payloadWordCount) {
            throw IOException("A System-NNN select command has truncated option text references.")
        }

        // The prefix entries are non-select message rows. The reference runtime
        // drops their leading marker character before displaying them.
        val prefixLines = ArrayList<String>(prefixCount)
        repeat(prefixCount) { index ->
            val textIndex = readPayloadWord(script, record, 1 + index).toLong()
            val entry = script.secondaryTextPool.getOrNull(textIndex.toInt())
                ?.takeIf { textIndex <= Int.MAX_VALUE.toLong() }
                ?: throw IOException("A System-NNN select prefix references a missing secondary text entry.")
            prefixLines += entry.text.drop(1)
        }

        val options = ArrayList<SystemNnnChoiceOption>(optionCount)
        repeat(optionCount) { index ->
            val caseNumber = index + 1
            val textIndex = readPayloadWord(script, record, 1 + prefixCount + index).toLong()
            val entry = script.secondaryTextPool.getOrNull(textIndex.toInt())
                ?.takeIf { textIndex <= Int.MAX_VALUE.toLong() }
                ?: throw IOException("A System-NNN select option references a missing secondary text entry.")
            val target = findCaseTarget(script, stream, record.endOffsetExclusive, caseNumber)
            options += SystemNnnChoiceOption(
                id = "case-$caseNumber",
                label = entry.text,
                targetCommandOffset = target
            )
        }

        return SystemNnnDecodedCommand.Choice(
            promptTextEntryOffset = null,
            options = options,
            promptText = prefixLines.joinToString("\n")
        )
    }

    /**
     * Mirrors target gss.exe GetCase: CASE carries the 1-based option number,
     * and a matching branch begins immediately after that CASE record. Unmatched
     * branch blocks are skipped by the opening brace's DWORD span, including the
     * closing-brace record when the span lands on it.
     */
    private fun findCaseTarget(
        script: SystemNnnScript,
        stream: SystemNnnCommandStream,
        afterSelectOffset: Long,
        requestedCase: Int
    ): Long {
        var cursor = afterSelectOffset
        val first = recordAt(stream, cursor)
        if (isOpenBrace(first)) {
            // Target gss.exe GetCase skips an initial wrapper brace record only.
            // CASE-body braces below are skipped by their declared DWORD span.
            cursor = first!!.endOffsetExclusive
        }

        repeat(MAX_CASE_SCAN_RECORDS) {
            val caseRecord = recordAt(stream, cursor)
                ?: throw IOException("A System-NNN select command has no CASE record for option $requestedCase.")
            if (caseRecord.recordKind != SystemNnnRecordKind.CONTROL ||
                caseRecord.codeFamily != CONTROL_FAMILY ||
                caseRecord.codeId != CODE_CONTROL_CASE
            ) {
                throw IOException("A System-NNN select branch does not begin with a CASE record.")
            }
            if (caseRecord.payloadWordCount < 1) {
                throw IOException("A System-NNN CASE record has no case number.")
            }
            val caseNumber = (readPayloadWord(script, caseRecord, 0).toInt() and 0xFFFF)
            if (caseNumber == requestedCase) return caseRecord.endOffsetExclusive

            val body = recordAt(stream, caseRecord.endOffsetExclusive)
                ?: throw IOException("A System-NNN CASE block is truncated.")
            cursor = if (isOpenBrace(body)) {
                skipBraceBlock(script, stream, body!!)
            } else {
                body.endOffsetExclusive
            }
        }

        throw IOException("The System-NNN select branch table exceeded the scan limit.")
    }

    private fun skipBraceBlock(
        script: SystemNnnScript,
        stream: SystemNnnCommandStream,
        openingBrace: SystemNnnCommandRecord
    ): Long {
        if (openingBrace.payloadWordCount < 1) {
            throw IOException("A System-NNN opening brace has no block length.")
        }
        val spanWords = readPayloadWord(script, openingBrace, 0).toLong()
        if (spanWords <= 0L || spanWords > MAX_BRACE_SPAN_WORDS) {
            throw IOException("A System-NNN opening brace has an invalid block length.")
        }
        val closingBraceOffset = openingBrace.offset + spanWords * DWORD_BYTES
        val closingBrace = recordAt(stream, closingBraceOffset)
            ?: throw IOException("A System-NNN brace block does not end on a record boundary.")
        return if (isCloseBrace(closingBrace)) {
            closingBrace.endOffsetExclusive
        } else {
            closingBraceOffset
        }
    }

    private fun recordAt(stream: SystemNnnCommandStream, offset: Long): SystemNnnCommandRecord? {
        val index = stream.records.binarySearchBy(offset) { it.offset }
        return stream.records.getOrNull(index)
    }

    private fun isOpenBrace(record: SystemNnnCommandRecord?): Boolean =
        record?.recordKind == SystemNnnRecordKind.CONTROL &&
            record.codeFamily == EXPRESSION_FAMILY &&
            record.codeId == CODE_OPEN_BRACE

    private fun isCloseBrace(record: SystemNnnCommandRecord?): Boolean =
        record?.recordKind == SystemNnnRecordKind.CONTROL &&
            record.codeFamily == EXPRESSION_FAMILY &&
            record.codeId == CODE_CLOSE_BRACE


    /**
     * The target gss.exe transfer handlers read exactly one DWORD at record+0x0C:
     * a Pool 2 module-name index. They then advance the active PC by this record's
     * own length. The following DWORD is the next record's length, not a target
     * return selector. The ViewModel preserves this exact caller continuation.
     */
    private fun decodeModuleTransfer(
        script: SystemNnnScript,
        record: SystemNnnCommandRecord,
        kind: SystemNnnModuleTransferKind
    ): SystemNnnDecodedCommand.ModuleTransfer {
        if (record.payloadWordCount != MODULE_TRANSFER_PAYLOAD_WORD_COUNT) {
            throw IOException("A System-NNN $kind record must contain exactly one Pool 2 operand.")
        }
        val poolIndexValue = readPayloadWord(script, record, MODULE_TRANSFER_POOL_INDEX_PAYLOAD).toLong()
        if (poolIndexValue > Int.MAX_VALUE.toLong()) {
            throw IOException("A System-NNN $kind target index is outside the Pool 2 range.")
        }
        val target = script.secondaryTextPool.getOrNull(poolIndexValue.toInt())
            ?: throw IOException("A System-NNN $kind target references a missing Pool 2 entry.")
        if (target.hasDecodingErrors || !MODULE_NAME_PATTERN.matches(target.text)) {
            throw IOException("A System-NNN $kind target is not a valid module name.")
        }
        return SystemNnnDecodedCommand.ModuleTransfer(
            nextCommandOffset = record.endOffsetExclusive,
            targetModuleName = target.text,
            kind = kind
        )
    }

    /**
     * The cleaned target archive contains 22 IDENTIFY_FUNCTION records, all
     * code 0x0002 with zero arguments. In its function.xtx, function slot 2 is
     * "stopf5"; the reference callback only clears skip-mode flags. Ramen has
     * no skip-mode state, so this exact record is a no-op. Other user functions
     * remain unsupported rather than being guessed.
     */
    private fun decodeUserFunction(
        script: SystemNnnScript,
        record: SystemNnnCommandRecord
    ): SystemNnnDecodedCommand.Skip {
        if (record.codeFamily != USER_FUNCTION_FAMILY ||
            record.codeId != USER_FUNCTION_STOP_F5 ||
            record.payloadWordCount != USER_FUNCTION_ARGUMENT_COUNT_WORDS ||
            readPayloadWord(script, record, USER_FUNCTION_ARGUMENT_COUNT_PAYLOAD) != 0u
        ) {
            return skipUnsupported(record, "unknown or unsupported command code", script)
        }
        return SystemNnnDecodedCommand.Skip(record.endOffsetExclusive)
    }

    private fun decodeSystemFunction(
        script: SystemNnnScript,
        stream: SystemNnnCommandStream,
        record: SystemNnnCommandRecord
    ): SystemNnnDecodedCommand {
        if (record.codeFamily != SYSTEM_FUNCTION_FAMILY) {
            return skipUnsupported(record, "unknown or unsupported command code")
        }

        return when (record.codeId) {
            CODE_SYSTEMFUNCTION_MUSIC -> decodeMusic(script, record)
            CODE_SYSTEMFUNCTION_VOICE -> decodeVoice(script, record)
            CODE_SYSTEMFUNCTION_SOUND -> decodeSound(script, record)
            CODE_SYSTEMFUNCTION_SETEFFECT -> decodeSetEffect(script, record)
            CODE_SYSTEMFUNCTION_SETEFFECTRECT -> decodeSetEffectRect(script, record)
            CODE_SYSTEMFUNCTION_SETFILM -> decodeSetFilm(script, stream, record)
            CODE_SYSTEMFUNCTION_LOADDWQ -> decodeLoadDwq(script, record)
            CODE_SYSTEMFUNCTION_CLEAREFFECTLAYER -> decodeClearEffectLayer(script, record)
            CODE_SYSTEMFUNCTION_CLEARALLEFFECT ->
                SystemNnnDecodedCommand.ClearAllImageLayers(record.endOffsetExclusive)
            // PRELOADDWQ affects only the native image cache; display is
            // deferred until a later LOADDWQ record selects a layer.
            CODE_SYSTEMFUNCTION_PRELOADDWQ ->
                SystemNnnDecodedCommand.Skip(record.endOffsetExclusive)
            CODE_SYSTEMFUNCTION_PREPAREOVERRAP -> skipKnownUnimplemented(
                record, script, "PREPAREOVERRAP captures the current screen before a native transition."
            )
            CODE_SYSTEMFUNCTION_STARTMESSAGE -> skipKnownUnimplemented(
                record, script, "STARTMESSAGE changes native message-id/read/skip/window state."
            )
            CODE_SYSTEMFUNCTION_STARTKOMA -> skipKnownUnimplemented(
                record, script, "STARTKOMA changes native dialogue type and message-window mode."
            )
            CODE_SYSTEMFUNCTION_NOMESSAGEWINDOW -> skipKnownUnimplemented(
                record, script, "NOMESSAGEWINDOW suppresses the native message window."
            )
            CODE_SYSTEMFUNCTION_SETDEMOFLAG -> skipKnownUnimplemented(
                record, script, "SETDEMOFLAG changes native demo/playback gating."
            )
            CODE_SYSTEMFUNCTION_AUTOMESSAGE -> skipKnownUnimplemented(
                record, script, "AUTOMESSAGE changes native automatic-advance timing."
            )
            CODE_SYSTEMFUNCTION_CANNOTCLICK -> skipKnownUnimplemented(
                record, script, "CANNOTCLICK changes whether the native player accepts taps."
            )
            CODE_SYSTEMFUNCTION_CANNOTSKIP -> skipKnownUnimplemented(
                record, script, "CANNOTSKIP changes native skip gating."
            )
            CODE_SYSTEMFUNCTION_OPTIONOFF -> skipKnownUnimplemented(
                record, script, "OPTIONOFF changes native option-menu availability."
            )
            CODE_SYSTEMFUNCTION_SETCG -> skipKnownIgnored(
                record, script, "SETCG updates native CG-collection metadata; it does not load or draw an image."
            )
            CODE_SYSTEMFUNCTION_SETSCENE -> skipKnownIgnored(
                record, script, "SETSCENE is disabled in the pinned native callback (the SetScene call is commented out)."
            )
            CODE_SYSTEMFUNCTION_STARTFILM -> skipKnownIgnored(
                record, script, "STARTFILM has no execution branch in the pinned native ExecScript."
            )
            CODE_SYSTEMFUNCTION_FILMNAME,
            CODE_SYSTEMFUNCTION_STORYNAME,
            CODE_SYSTEMFUNCTION_FILMTYPETIME -> skipKnownIgnored(
                record, script, "Film/story display metadata is not consumed by the Android player."
            )
            else -> skipUnsupported(record, "unknown system function code", script)
        }
    }

    /**
     * MUSIC/VOICE/SOUND carry an argument-count DWORD followed by their native
     * parameters. These operand positions follow the pinned System-NNN family
     * reference; resource banks are resolved separately by the asset manager.
     */
    private fun readDeclaredArgumentCount(
        script: SystemNnnScript,
        record: SystemNnnCommandRecord,
        minimum: Int,
        maximum: Int,
        label: String
    ): Int {
        if (record.payloadWordCount < 2) {
            throw IOException("A System-NNN $label record has no argument payload.")
        }
        val count = readPayloadWord(script, record, 0).toLong()
        if (count !in minimum.toLong()..maximum.toLong() ||
            count + 1L > record.payloadWordCount.toLong()
        ) {
            throw IOException("A System-NNN $label record has an invalid argument count.")
        }
        return count.toInt()
    }

    private fun decodeMusic(
        script: SystemNnnScript,
        record: SystemNnnCommandRecord
    ): SystemNnnDecodedCommand.Music {
        val argumentCount = readDeclaredArgumentCount(script, record, 1, MAX_AUDIO_ARGUMENTS, "MUSIC")
        val number = readPayloadWord(script, record, 1).toInt()
        if (number == 0 || number < -2) {
            throw IOException("A System-NNN MUSIC command has an invalid track number.")
        }
        return SystemNnnDecodedCommand.Music(
            nextCommandOffset = record.endOffsetExclusive,
            musicNumber = number,
            loopCount = if (argumentCount >= 2) readPayloadWord(script, record, 2).toInt() else 0
        )
    }

    private fun decodeVoice(
        script: SystemNnnScript,
        record: SystemNnnCommandRecord
    ): SystemNnnDecodedCommand.Voice {
        readDeclaredArgumentCount(script, record, 2, MAX_AUDIO_ARGUMENTS, "VOICE")
        val resourceIndex = readPayloadWord(script, record, 1).toInt()
        val channel = readPayloadWord(script, record, 2).toLong()
        if (channel !in 0L..MAX_AUDIO_CHANNEL.toLong()) {
            throw IOException("A System-NNN VOICE channel is outside the supported range.")
        }
        val resourceId = if (resourceIndex == -1) {
            null
        } else {
            if (resourceIndex < 0) throw IOException("A System-NNN VOICE resource index is negative.")
            script.secondaryTextPool.getOrNull(resourceIndex)?.let { entry ->
                if (entry.hasDecodingErrors || entry.text.isBlank() ||
                    entry.text.any { character -> character.isISOControl() }
                ) null else entry.text
            } ?: throw IOException("A System-NNN VOICE command references a missing Pool 2 resource.")
        }
        return SystemNnnDecodedCommand.Voice(
            nextCommandOffset = record.endOffsetExclusive,
            resourceId = resourceId,
            channel = channel.toInt()
        )
    }

    private fun decodeSound(
        script: SystemNnnScript,
        record: SystemNnnCommandRecord
    ): SystemNnnDecodedCommand.Sound {
        readDeclaredArgumentCount(script, record, 2, MAX_AUDIO_ARGUMENTS, "SOUND")
        val soundNumber = readPayloadWord(script, record, 1).toInt()
        val channel = readPayloadWord(script, record, 2).toLong()
        if (soundNumber < -1 || channel !in 0L..MAX_AUDIO_CHANNEL.toLong()) {
            throw IOException("A System-NNN SOUND command has an invalid sound number or channel.")
        }
        return SystemNnnDecodedCommand.Sound(
            nextCommandOffset = record.endOffsetExclusive,
            soundNumber = soundNumber,
            channel = channel.toInt()
        )
    }

    private fun decodeSetEffect(
        script: SystemNnnScript,
        record: SystemNnnCommandRecord
    ): SystemNnnDecodedCommand {
        val argumentCount = readDeclaredArgumentCount(
            script, record, 2, MAX_EFFECT_ARGUMENTS, "SETEFFECT"
        )
        val layer = readPayloadWord(script, record, 1).toLong()
        val effect = readPayloadWord(script, record, 2).toInt()
        if (layer !in 0L until MAX_IMAGE_LAYERS.toLong()) {
            throw IOException("A System-NNN SETEFFECT layer is outside the supported range.")
        }
        if (effect == -1) {
            return SystemNnnDecodedCommand.ClearImageLayer(record.endOffsetExclusive, layer.toInt())
        }
        if (effect !in 0..MAX_SYSTEM_EFFECT_ID) {
            throw IOException("A System-NNN SETEFFECT id is outside the known family range.")
        }
        val parameters = List(argumentCount - 2) { index ->
            readPayloadWord(script, record, index + 3).toInt()
        }
        return SystemNnnDecodedCommand.SetImageLayerEffect(
            nextCommandOffset = record.endOffsetExclusive,
            layerIndex = layer.toInt(),
            effectId = effect,
            parameters = parameters
        )
    }

    private fun decodeSetEffectRect(
        script: SystemNnnScript,
        record: SystemNnnCommandRecord
    ): SystemNnnDecodedCommand {
        // Family builds use para1 as a variable operand count. The native callback
        // reads the first nine operands (layer + two RECTs) and ignores extras.
        // Keep this command fail-open: malformed/unusual rectangles must not stop
        // the script player from reaching the next visible dialogue.
        return try {
            val argumentCount = readDeclaredArgumentCount(
                script, record, 9, MAX_EFFECT_ARGUMENTS, "SETEFFECTRECT"
            )
            if (argumentCount < 9) {
                return skipUnsupported(record, "SETEFFECTRECT has fewer than nine required operands")
            }
            val layer = readPayloadWord(script, record, 1).toLong()
            if (layer !in 0L until MAX_IMAGE_LAYERS.toLong()) {
                return skipUnsupported(record, "SETEFFECTRECT layer is outside the supported range")
            }

            fun readRect(start: Int): SystemNnnNativeRect? {
                // The native engine stores Win32 RECT edges (left, top, right, bottom).
                val left = readPayloadWord(script, record, start).toInt()
                val top = readPayloadWord(script, record, start + 1).toInt()
                val right = readPayloadWord(script, record, start + 2).toInt()
                val bottom = readPayloadWord(script, record, start + 3).toInt()
                // The family renderer stores left/top as the origin and right/bottom
                // as width/height. CAllEffect::CalcuOnly interpolates only left/top;
                // PrintLayers passes right/bottom directly as the picture dimensions.
                val width = right.toLong()
                val height = bottom.toLong()
                if (left !in -MAX_EFFECT_RECT_COORD..MAX_EFFECT_RECT_COORD ||
                    top !in -MAX_EFFECT_RECT_COORD..MAX_EFFECT_RECT_COORD ||
                    width !in 1L..MAX_EFFECT_RECT_DIMENSION.toLong() ||
                    height !in 1L..MAX_EFFECT_RECT_DIMENSION.toLong()
                ) {
                    return null
                }
                return SystemNnnNativeRect(left, top, width.toInt(), height.toInt())
            }

            val fromRect = readRect(2)
                ?: return skipUnsupported(record, "SETEFFECTRECT source RECT is not renderable")
            val toRect = readRect(6)
                ?: return skipUnsupported(record, "SETEFFECTRECT destination RECT is not renderable")

            SystemNnnDecodedCommand.SetImageLayerRect(
                nextCommandOffset = record.endOffsetExclusive,
                layerIndex = layer.toInt(),
                fromRect = fromRect,
                toRect = toRect
            )
        } catch (failure: IOException) {
            skipUnsupported(
                record,
                "SETEFFECTRECT could not be applied safely: " +
                    (failure.message ?: failure.javaClass.simpleName)
            )
        }
    }

    /**
     * Family-reference execscript.cpp forwards LOADDWQ as (layer, Pool2 string).
     * Its callback loads that named DWQ into the selected effect layer. The
     * reference is cross-title context; all reads stay bounded by this record
     * and the target script's already-validated Pool 2 catalog.
     */
    private fun decodeLoadDwq(
        script: SystemNnnScript,
        record: SystemNnnCommandRecord
    ): SystemNnnDecodedCommand {
        if (record.payloadWordCount < LOADDWQ_PAYLOAD_WORD_COUNT) {
            return skipUnsupported(record, "LOADDWQ is missing its layer or Pool 2 resource index")
        }
        val layerValue = readPayloadWord(script, record, LOADDWQ_LAYER_PAYLOAD).toLong()
        if (layerValue !in 0L until MAX_IMAGE_LAYERS.toLong()) {
            return skipUnsupported(record, "LOADDWQ layer is outside the supported layer range")
        }
        val resourceIndex = readPayloadWord(script, record, LOADDWQ_RESOURCE_INDEX_PAYLOAD).toLong()
        if (resourceIndex > Int.MAX_VALUE.toLong()) {
            return skipUnsupported(record, "LOADDWQ Pool 2 index is outside the supported range")
        }
        val resource = script.secondaryTextPool.getOrNull(resourceIndex.toInt())
            ?.takeIf { entry ->
                !entry.hasDecodingErrors &&
                    entry.text.isNotBlank() &&
                    entry.text.none { character -> character.isISOControl() }
            }
            ?: return skipUnsupported(record, "LOADDWQ references a missing or invalid Pool 2 resource")

        return SystemNnnDecodedCommand.LoadImageLayer(
            nextCommandOffset = record.endOffsetExclusive,
            layerIndex = layerValue.toInt(),
            resourceId = resource.text
        )
    }

    private fun decodeClearEffectLayer(
        script: SystemNnnScript,
        record: SystemNnnCommandRecord
    ): SystemNnnDecodedCommand {
        if (record.payloadWordCount < CLEAR_EFFECT_LAYER_PAYLOAD_WORD_COUNT) {
            return skipUnsupported(record, "CLEAREFFECTLAYER is missing its layer operand")
        }
        val layerValue = readPayloadWord(script, record, CLEAR_EFFECT_LAYER_LAYER_PAYLOAD).toLong()
        if (layerValue !in 0L until MAX_IMAGE_LAYERS.toLong()) {
            return skipUnsupported(record, "CLEAREFFECTLAYER layer is outside the supported layer range")
        }
        return SystemNnnDecodedCommand.ClearImageLayer(
            nextCommandOffset = record.endOffsetExclusive,
            layerIndex = layerValue.toInt()
        )
    }

    /**
     * SETFILM stores the one-based film number associated with the descriptor-3
     * interval that contains this record. This is a metadata update, not a PC
     * jump: ENDFILM is the sequential boundary handled by the outer script loop.
     */
    private fun decodeSetFilm(
        script: SystemNnnScript,
        stream: SystemNnnCommandStream,
        record: SystemNnnCommandRecord
    ): SystemNnnDecodedCommand.SetFilm {
        if (record.payloadWordCount != SETFILM_PAYLOAD_WORD_COUNT) {
            throw IOException("A System-NNN SETFILM record must contain an argument count and one film number.")
        }
        val argumentCount = readPayloadWord(script, record, SETFILM_ARGUMENT_COUNT_PAYLOAD)
        if (argumentCount != SETFILM_ARGUMENT_COUNT.toUInt()) {
            throw IOException("A System-NNN SETFILM record must declare exactly one argument.")
        }

        val filmNumberValue = readPayloadWord(script, record, SETFILM_FILM_NUMBER_PAYLOAD).toLong()
        if (filmNumberValue !in 1L..Int.MAX_VALUE.toLong()) {
            throw IOException("A System-NNN SETFILM number is outside the supported range.")
        }

        val owner = filmTableEntriesFor(script, stream)
            .asSequence()
            .filter { it.targetCommandOffset <= record.offset }
            .maxByOrNull { it.targetCommandOffset }
            ?: throw IOException("A System-NNN SETFILM record is outside a descriptor-3 film interval.")

        val expectedFilmNumber = owner.filmNumber?.toLong()?.plus(1L)
            ?: throw IOException("A System-NNN SETFILM record belongs to a reserved descriptor-3 entry.")
        if (filmNumberValue != expectedFilmNumber) {
            throw IOException(
                "SETFILM number $filmNumberValue does not match descriptor-3 film number $expectedFilmNumber."
            )
        }

        return SystemNnnDecodedCommand.SetFilm(
            nextCommandOffset = record.endOffsetExclusive,
            filmNumber = filmNumberValue.toInt()
        )
    }

    private fun decodeControl(
        script: SystemNnnScript,
        stream: SystemNnnCommandStream,
        record: SystemNnnCommandRecord
    ): SystemNnnDecodedCommand {
        if (record.codeFamily == EXPRESSION_FAMILY) {
            if (record.codeId == CODE_OPEN_BRACE || record.codeId == CODE_CLOSE_BRACE) {
                return SystemNnnDecodedCommand.Skip(record.endOffsetExclusive)
            }
            return skipUnsupported(record, "unknown or unsupported command code", script)
        }
        if (record.codeFamily != CONTROL_FAMILY) {
            return skipUnsupported(record, "unknown or unsupported command code", script)
        }

        return when (record.codeId) {
            CODE_CONTROL_NOP,
            CODE_CONTROL_ENDKOMA,
            CODE_CONTROL_ENDIF -> SystemNnnDecodedCommand.Skip(record.endOffsetExclusive)

            CODE_CONTROL_IF -> decodeIfBranch(script, stream, record)

            CODE_CONTROL_ELSIF,
            CODE_CONTROL_ELSE,
            CODE_CONTROL_CASE -> SystemNnnDecodedCommand.Skip(
                skipFollowingBranch(script, stream, record)
            )

            CODE_CONTROL_SUB -> decodeLocalSubroutine(script, stream, record)

            CODE_CONTROL_RETURN -> returnFromLocalSubroutine(stream, record)

            CODE_CONTROL_SCRIPT -> decodeModuleTransfer(
                script, record, SystemNnnModuleTransferKind.SCRIPT
            )

            CODE_CONTROL_SUBSCRIPT -> decodeModuleTransfer(
                script, record, SystemNnnModuleTransferKind.SUBSCRIPT
            )

            CODE_CONTROL_SCRIPTJUMP -> decodeModuleTransfer(
                script, record, SystemNnnModuleTransferKind.SCRIPTJUMP
            )

            CODE_CONTROL_NEXT -> {
                // NEXT is the module-return boundary. Some real scripts arrive
                // here with stale local SUB frames and opaque trailing DWORDs.
                // The record envelope already validated its full length, so
                // discard the stale stack and ignore payload without decoding it.
                localSubroutineStack.clear()
                SystemNnnDecodedCommand.ModuleReturn
            }

            CODE_CONTROL_ENDFILM -> SystemNnnDecodedCommand.EndFilm(record.endOffsetExclusive)

            CODE_CONTROL_END -> SystemNnnDecodedCommand.ControllerReset

            CODE_CONTROL_EXIT -> SystemNnnDecodedCommand.End

            else -> return skipUnsupported(record, "unknown or unsupported command code", script)
        }
    }


    /**
     * Executes the verified local SUB shape:
     * payload[0] selects the descriptor-3 jump-vector entry;
     * payload[1] is the module-local SUB identifier.
     *
     * The family reference pushes the bytecode continuation before jumping
     * through descriptor 3. The target archive confirms the same two operands
     * and valid 0x3333/0x50 or 0x3333/0x51 function targets for every SUB.
     */
    private fun decodeLocalSubroutine(
        script: SystemNnnScript,
        stream: SystemNnnCommandStream,
        record: SystemNnnCommandRecord
    ): SystemNnnDecodedCommand.Jump {
        if (record.payloadWordCount != LOCAL_SUB_PAYLOAD_WORD_COUNT) {
            throw IOException("A System-NNN SUB record must contain exactly two DWORD operands.")
        }
        if (localSubroutineStack.size >= MAX_LOCAL_SUBROUTINE_DEPTH) {
            throw IOException("The System-NNN local SUB stack exceeded its depth limit.")
        }

        val tableIndexValue = readPayloadWord(script, record, LOCAL_SUB_TABLE_INDEX_PAYLOAD).toLong()
        val subroutineIdValue = readPayloadWord(script, record, LOCAL_SUB_ID_PAYLOAD).toLong()
        if (tableIndexValue > Int.MAX_VALUE.toLong() ||
            subroutineIdValue > MAX_LOCAL_SUBROUTINE_ID.toLong()
        ) {
            throw IOException("A System-NNN SUB operand is outside the supported range.")
        }

        val entries = filmTableEntriesFor(script, stream)
        if (tableIndexValue >= entries.size.toLong()) {
            throw IOException("A System-NNN SUB references a missing descriptor-3 entry.")
        }

        val target = entries[tableIndexValue.toInt()].targetCommandOffset
        val targetIndex = stream.records.binarySearchBy(target) { it.offset }
        if (targetIndex < 0) {
            throw IOException("A System-NNN SUB target is not a validated command boundary.")
        }

        val continuation = record.endOffsetExclusive
        if (
            continuation != stream.recordEndOffsetExclusive &&
            stream.records.binarySearchBy(continuation) { it.offset } < 0
        ) {
            throw IOException("A System-NNN SUB return continuation is not a record boundary.")
        }

        localSubroutineStack += LocalSubroutineFrame(
            returnCommandOffset = continuation,
            subroutineId = subroutineIdValue.toInt()
        )
        return SystemNnnDecodedCommand.Jump(target)
    }

    /** Returns to the exact command after the matching SUB; never guesses a caller. */
    private fun returnFromLocalSubroutine(
        stream: SystemNnnCommandStream,
        record: SystemNnnCommandRecord
    ): SystemNnnDecodedCommand.Jump {
        if (record.payloadWordCount != 0) {
            throw IOException("A System-NNN RETURN record must not contain payload DWORDs.")
        }
        val frame = localSubroutineStack.lastOrNull()
            ?: throw IOException("A System-NNN RETURN record has no matching local SUB call.")
        localSubroutineStack.removeAt(localSubroutineStack.lastIndex)

        if (
            frame.returnCommandOffset != stream.recordEndOffsetExclusive &&
            stream.records.binarySearchBy(frame.returnCommandOffset) { it.offset } < 0
        ) {
            throw IOException("A System-NNN RETURN continuation is not a record boundary.")
        }
        return SystemNnnDecodedCommand.Jump(frame.returnCommandOffset)
    }

    private fun filmTableEntriesFor(
        script: SystemNnnScript,
        stream: SystemNnnCommandStream
    ): List<io.github.helterfall.ramen.parser.SystemNnnFilmTableEntry> {
        cachedFilmTableEntries?.let { return it }
        return streamReader.filmTableEntries(script, stream).also {
            cachedFilmTableEntries = it
        }
    }

    private data class LocalSubroutineFrame(
        val returnCommandOffset: Long,
        val subroutineId: Int
    )

    /**
     * Executes the target IF/ELSIF chain. Each condition is a bounded RPN
     * expression in the CONTROL record payload; a true branch falls through
     * into its brace body, while a false branch skips the exact brace span and
     * checks the immediately following ELSIF/ELSE marker.
     */
    private fun decodeIfBranch(
        script: SystemNnnScript,
        stream: SystemNnnCommandStream,
        firstCondition: SystemNnnCommandRecord
    ): SystemNnnDecodedCommand.Skip {
        var condition = firstCondition
        repeat(MAX_CONDITIONAL_BRANCHES) {
            if (evaluateCalculation(script, condition) > 0) {
                return SystemNnnDecodedCommand.Skip(condition.endOffsetExclusive)
            }

            val body = recordAt(stream, condition.endOffsetExclusive)
                ?: return SystemNnnDecodedCommand.Skip(stream.recordEndOffsetExclusive)
            val afterBody = if (isOpenBrace(body)) {
                skipBraceBlock(script, stream, body)
            } else {
                body.endOffsetExclusive
            }

            val nextBranch = recordAt(stream, afterBody)
                ?: return SystemNnnDecodedCommand.Skip(afterBody)
            if (
                nextBranch.recordKind != SystemNnnRecordKind.CONTROL ||
                nextBranch.codeFamily != CONTROL_FAMILY
            ) {
                return SystemNnnDecodedCommand.Skip(afterBody)
            }

            when (nextBranch.codeId) {
                CODE_CONTROL_ELSIF -> condition = nextBranch
                CODE_CONTROL_ELSE -> return SystemNnnDecodedCommand.Skip(nextBranch.endOffsetExclusive)
                else -> return SystemNnnDecodedCommand.Skip(afterBody)
            }
        }
        throw IOException("A System-NNN IF chain exceeded the conditional branch limit.")
    }

    private fun skipFollowingBranch(
        script: SystemNnnScript,
        stream: SystemNnnCommandStream,
        branchRecord: SystemNnnCommandRecord
    ): Long {
        val body = recordAt(stream, branchRecord.endOffsetExclusive)
            ?: return stream.recordEndOffsetExclusive
        return if (isOpenBrace(body)) {
            skipBraceBlock(script, stream, body)
        } else {
            body.endOffsetExclusive
        }
    }

    private data class ExpressionValue(
        val value: Int,
        val variableIndex: Int? = null
    )

    /**
     * Evaluates the bounded reverse-Polish expression carried by a CALCU
     * record. Only integer/variable tokens and operators observed in the
     * supplied module set are accepted.
     */
    private fun evaluateCalculation(
        script: SystemNnnScript,
        record: SystemNnnCommandRecord
    ): Int {
        if (record.payloadWordCount <= 0) {
            throw IOException("A System-NNN CALCU record has no expression payload.")
        }

        val first = readPayloadWord(script, record, 0)
        val tokens = if (first == SCRIPT_CALCU_TOKEN) {
            if (record.payloadWordCount < 2) {
                throw IOException("A System-NNN CALCU expression has no token count.")
            }
            val tokenCount = readPayloadWord(script, record, 1).toLong()
            if (tokenCount !in 1L..MAX_EXPRESSION_TOKENS.toLong() ||
                tokenCount + 2L > record.payloadWordCount.toLong()
            ) {
                throw IOException("A System-NNN CALCU expression has an invalid token count.")
            }
            List(tokenCount.toInt()) { index -> readPayloadWord(script, record, index + 2) }
        } else {
            if (record.payloadWordCount != 1) {
                throw IOException("A simple System-NNN CALCU expression has trailing words.")
            }
            listOf(first)
        }

        val stack = ArrayList<ExpressionValue>(tokens.size)
        tokens.forEach { token ->
            when (val family = (token shr 16).toInt() and 0xFFFF) {
                SCRIPT_VAR_FAMILY -> {
                    val rawIndex = (token and 0xFFFFu).toInt()
                    if ((rawIndex and IMMEDIATE_VARIABLE_FLAG) != 0) {
                        stack += ExpressionValue(rawIndex and IMMEDIATE_VARIABLE_MASK)
                    } else {
                        stack += ExpressionValue(variables[rawIndex] ?: 0, rawIndex)
                    }
                }

                SCRIPT_NUMBER_FAMILY -> {
                    val raw = (token and 0xFFFFu).toInt()
                    val value = if ((raw and 0x8000) != 0) raw or -0x10000 else raw
                    stack += ExpressionValue(value)
                }

                SCRIPT_CALCU_ENZAN_FAMILY,
                SCRIPT_CALCU_COMPOUND_FAMILY,
                SCRIPT_CALCU_LOGICAL_FAMILY,
                SCRIPT_CALCU_COMPARE_FAMILY,
                SCRIPT_CALCU_COMPARE_EQUAL_FAMILY -> {
                    applyExpressionOperator(
                        family = family,
                        operator = (token and 0xFFFFu).toInt(),
                        stack = stack
                    )
                }

                else -> throw UnsupportedSystemNnnRecordException(
                    "Unsupported System-NNN CALCU token 0x" + token.toString(16) + "."
                )
            }
            if (stack.size > MAX_EXPRESSION_STACK) {
                throw IOException("A System-NNN CALCU expression exceeded the stack limit.")
            }
        }

        if (stack.size != 1) {
            throw IOException("A System-NNN CALCU expression left an invalid result stack.")
        }
        return stack.single().value
    }

    private fun applyExpressionOperator(
        family: Int,
        operator: Int,
        stack: MutableList<ExpressionValue>
    ) {
        if (family == SCRIPT_CALCU_ENZAN_FAMILY && operator == OP_NOT) {
            val operand = stack.removeLastOrNull()
                ?: throw IOException("A System-NNN CALCU NOT operator has no operand.")
            val result = if (operand.value == 0) 1 else 0
            operand.variableIndex?.let { variables[it] = result }
            stack += ExpressionValue(result, operand.variableIndex)
            return
        }

        if (stack.size < 2) {
            throw IOException("A System-NNN CALCU binary operator has too few operands.")
        }
        val right = stack.removeAt(stack.lastIndex)
        val left = stack.removeAt(stack.lastIndex)

        val result = when (family) {
            SCRIPT_CALCU_ENZAN_FAMILY -> when (operator) {
                OP_ASSIGN -> {
                    val destination = left.variableIndex
                        ?: throw IOException("A System-NNN CALCU assignment target is not a variable.")
                    variables[destination] = right.value
                    ExpressionValue(right.value, destination)
                }
                OP_ADD -> ExpressionValue(left.value + right.value)
                OP_SUBTRACT -> ExpressionValue(left.value - right.value)
                OP_MULTIPLY -> ExpressionValue(left.value * right.value)
                OP_DIVIDE -> {
                    // Native gss.exe replaces a zero divisor with 1 before idiv.
                    val divisor = if (right.value == 0) 1 else right.value
                    ExpressionValue(left.value / divisor)
                }
                OP_MODULO -> {
                    // The same zero-divisor fallback is used by the native modulo path.
                    val divisor = if (right.value == 0) 1 else right.value
                    ExpressionValue(left.value % divisor)
                }
                OP_BIT_AND -> ExpressionValue(left.value and right.value)
                OP_BIT_XOR -> ExpressionValue(left.value xor right.value)
                OP_BIT_OR -> ExpressionValue(left.value or right.value)
                else -> throw UnsupportedSystemNnnRecordException("Unsupported System-NNN CALCU operator 0x" + operator.toString(16) + ".")
            }

            SCRIPT_CALCU_COMPOUND_FAMILY -> {
                if (operator == OP_EQUAL) {
                    ExpressionValue(if (left.value == right.value) 1 else 0)
                } else {
                    val destination = left.variableIndex
                        ?: throw IOException("A System-NNN compound assignment target is not a variable.")
                    val updated = when (operator) {
                        OP_ADD -> left.value + right.value
                        OP_SUBTRACT -> left.value - right.value
                        OP_MULTIPLY -> left.value * right.value
                        OP_DIVIDE -> {
                            val divisor = if (right.value == 0) 1 else right.value
                            left.value / divisor
                        }
                        OP_MODULO -> {
                            val divisor = if (right.value == 0) 1 else right.value
                            left.value % divisor
                        }
                        OP_BIT_AND -> left.value and right.value
                        OP_BIT_XOR -> left.value xor right.value
                        OP_BIT_OR -> left.value or right.value
                        else -> throw UnsupportedSystemNnnRecordException("Unsupported System-NNN compound operator.")
                    }
                    variables[destination] = updated
                    ExpressionValue(updated, destination)
                }
            }

            SCRIPT_CALCU_LOGICAL_FAMILY -> {
                val value = when (operator) {
                    OP_EQUAL -> left.value == right.value
                    OP_BIT_AND -> left.value != 0 && right.value != 0
                    OP_BIT_OR -> left.value != 0 || right.value != 0
                    OP_BIT_XOR -> (left.value != 0) xor (right.value != 0)
                    else -> throw UnsupportedSystemNnnRecordException("Unsupported System-NNN logical operator.")
                }
                ExpressionValue(if (value) 1 else 0)
            }

            SCRIPT_CALCU_COMPARE_FAMILY -> {
                val value = when (operator) {
                    OP_LESS -> left.value < right.value
                    OP_GREATER -> left.value > right.value
                    else -> throw UnsupportedSystemNnnRecordException("Unsupported System-NNN comparison operator.")
                }
                ExpressionValue(if (value) 1 else 0)
            }

            SCRIPT_CALCU_COMPARE_EQUAL_FAMILY -> {
                val value = when (operator) {
                    OP_LESS -> left.value <= right.value
                    OP_GREATER -> left.value >= right.value
                    OP_EQUAL -> left.value == right.value
                    OP_NOT -> left.value != right.value
                    else -> throw UnsupportedSystemNnnRecordException("Unsupported System-NNN inclusive comparison operator.")
                }
                ExpressionValue(if (value) 1 else 0)
            }

            else -> throw UnsupportedSystemNnnRecordException("Unsupported System-NNN CALCU operator family.")
        }
        stack += result
    }

    internal fun variableValue(index: Int): Int = variables[index] ?: 0

    private fun streamFor(script: SystemNnnScript): SystemNnnCommandStream {
        if (cachedScript !== script || cachedStream == null) {
            cachedStream = streamReader.read(script)
            cachedScript = script
            cachedFilmTableEntries = null
            localSubroutineStack.clear()
        }
        return checkNotNull(cachedStream)
    }

    private fun readPayloadWord(
        script: SystemNnnScript,
        record: SystemNnnCommandRecord,
        payloadIndex: Int
    ): UInt {
        if (payloadIndex !in 0 until record.payloadWordCount) {
            throw IOException("The SPT command payload index is outside the record.")
        }
        val absoluteOffset = record.offset + (RECORD_HEADER_WORDS + payloadIndex) * DWORD_BYTES
        val bytes = try {
            script.readCommandBytes(absoluteOffset, DWORD_BYTES.toInt())
        } catch (failure: IllegalArgumentException) {
            throw IOException("Could not read the bounded SPT command payload.", failure)
        }
        return (bytes[0].toInt() and 0xFF).toUInt() or
            ((bytes[1].toInt() and 0xFF).toUInt() shl 8) or
            ((bytes[2].toInt() and 0xFF).toUInt() shl 16) or
            ((bytes[3].toInt() and 0xFF).toUInt() shl 24)
    }

    private fun skipUnsupported(
        record: SystemNnnCommandRecord,
        reason: String,
        script: SystemNnnScript? = null
    ): SystemNnnDecodedCommand.Skip {
        recordDiagnostic("decoder_unsupported", record, reason, script)
        appendDebugLog(record, reason, script)
        return SystemNnnDecodedCommand.Skip(record.endOffsetExclusive)
    }

    private fun skipKnownUnimplemented(
        record: SystemNnnCommandRecord,
        script: SystemNnnScript,
        reason: String
    ): SystemNnnDecodedCommand.Skip {
        recordDiagnostic("decoder_known_unimplemented", record, reason, script)
        return SystemNnnDecodedCommand.Skip(record.endOffsetExclusive)
    }

    private fun skipKnownIgnored(
        record: SystemNnnCommandRecord,
        script: SystemNnnScript,
        reason: String
    ): SystemNnnDecodedCommand.Skip {
        recordDiagnostic("decoder_known_ignored", record, reason, script)
        return SystemNnnDecodedCommand.Skip(record.endOffsetExclusive)
    }

    /** Captures a bounded raw operand sample without risking a second decode failure. */
    private fun recordDiagnostic(
        event: String,
        record: SystemNnnCommandRecord,
        reason: String,
        script: SystemNnnScript?
    ) {
        val payloadSample = if (script == null) {
            "unavailable"
        } else {
            (0 until minOf(record.payloadWordCount, MAX_DIAGNOSTIC_PAYLOAD_WORDS))
                .joinToString(",") { index ->
                    runCatching { readPayloadWord(script, record, index).toString(16).padStart(8, '0') }
                        .getOrElse { "????????" }
                }
                .let { "[$it]" }
        }
        val details = "module=$moduleName offset=0x${record.offset.toString(16)} " +
            "lengthWords=${record.lengthWords} payloadWordCount=${record.payloadWordCount} " +
            "identifier=0x${record.identifier.toString(16)} code=0x${record.code.toString(16)} " +
            "kind=${record.recordKind?.name ?: "UNKNOWN"} payloadWords=$payloadSample " +
            "payloadTruncated=${record.payloadWordCount > MAX_DIAGNOSTIC_PAYLOAD_WORDS} " +
            "reason=${reason.replace('\n', ' ').replace('\r', ' ')}"
        SystemNnnDiagnosticLog.active?.record(event, details)
    }

    /**
     * Best-effort raw decoder log. It contains only records actually skipped
     * by the VM; successfully decoded SETEFFECT records are logged as effect_set.
     */
    private fun appendDebugLog(
        record: SystemNnnCommandRecord,
        reason: String,
        script: SystemNnnScript?
    ) {
        val destination = debugLogFile ?: return
        val safeReason = reason.replace('\n', ' ').replace('\r', ' ')
        val payloadSample = if (script == null) {
            "unavailable"
        } else {
            (0 until minOf(record.payloadWordCount, MAX_DIAGNOSTIC_PAYLOAD_WORDS))
                .joinToString(",") { index ->
                    runCatching { readPayloadWord(script, record, index).toString(16).padStart(8, '0') }
                        .getOrElse { "????????" }
                }
        }
        val line = "unsupported_record timeMs=${System.currentTimeMillis()} module=$moduleName " +
            "address=0x${record.offset.toString(16)} lengthWords=${record.lengthWords} " +
            "payloadWordCount=${record.payloadWordCount} identifier=0x${record.identifier.toString(16)} " +
            "code=0x${record.code.toString(16)} kind=${record.recordKind?.name ?: "UNKNOWN"} " +
            "payloadWords=[$payloadSample] payloadTruncated=${record.payloadWordCount > MAX_DIAGNOSTIC_PAYLOAD_WORDS} " +
            "reason=$safeReason\n"

        runCatching {
            synchronized(DEBUG_LOG_LOCK) {
                destination.parentFile?.let { parent ->
                    if (!parent.exists()) parent.mkdirs()
                }
                if (destination.exists() && destination.length() + line.length >= MAX_DEBUG_LOG_BYTES) {
                    destination.writeText("ramen_debug.log rotated after reaching the size limit.\n", Charsets.UTF_8)
                }
                destination.appendText(line, Charsets.UTF_8)
            }
        }
    }

    private class UnsupportedSystemNnnRecordException(message: String) : IOException(message)

    private companion object {
        const val DWORD_BYTES = 4L
        const val RECORD_HEADER_WORDS = 3
        const val SYSTEM_COMMAND_FAMILY = 0x2222
        const val CODE_OVERRAP = 0x0005
        const val USER_FUNCTION_FAMILY = 0x0000
        const val USER_FUNCTION_STOP_F5 = 0x0002
        const val USER_FUNCTION_ARGUMENT_COUNT_WORDS = 1
        const val USER_FUNCTION_ARGUMENT_COUNT_PAYLOAD = 0
        const val SYSTEM_FUNCTION_FAMILY = 0x3333
        const val CODE_SYSTEMFUNCTION_MUSIC = 0x0007
        const val CODE_SYSTEMFUNCTION_VOICE = 0x0008
        const val CODE_SYSTEMFUNCTION_SOUND = 0x0009
        const val CODE_SYSTEMFUNCTION_SETEFFECT = 0x0013
        const val CODE_SYSTEMFUNCTION_SETEFFECTRECT = 0x0016
        const val CODE_SYSTEMFUNCTION_SETFILM = 0x001A
        const val CODE_SYSTEMFUNCTION_CLEARALLEFFECT = 0x0012
        const val CODE_SYSTEMFUNCTION_CLEAREFFECTLAYER = 0x0014
        const val CODE_SYSTEMFUNCTION_LOADDWQ = 0x0015
        const val CODE_SYSTEMFUNCTION_PRELOADDWQ = 0x001F
        const val CODE_SYSTEMFUNCTION_PREPAREOVERRAP = 0x0011
        const val CODE_SYSTEMFUNCTION_NOMESSAGEWINDOW = 0x0017
        const val CODE_SYSTEMFUNCTION_SETCG = 0x0018
        const val CODE_SYSTEMFUNCTION_SETSCENE = 0x0019
        const val CODE_SYSTEMFUNCTION_STARTMESSAGE = 0x001B
        const val CODE_SYSTEMFUNCTION_STARTKOMA = 0x001C
        const val CODE_SYSTEMFUNCTION_STARTFILM = 0x001D
        const val CODE_SYSTEMFUNCTION_SETDEMOFLAG = 0x001E
        const val CODE_SYSTEMFUNCTION_FILMNAME = 0x0050
        const val CODE_SYSTEMFUNCTION_STORYNAME = 0x0051
        const val CODE_SYSTEMFUNCTION_FILMTYPETIME = 0x0052
        const val CODE_SYSTEMFUNCTION_AUTOMESSAGE = 0x0057
        const val CODE_SYSTEMFUNCTION_CANNOTCLICK = 0x0058
        const val CODE_SYSTEMFUNCTION_CANNOTSKIP = 0x0059
        const val CODE_SYSTEMFUNCTION_OPTIONOFF = 0x005A
        const val LOADDWQ_PAYLOAD_WORD_COUNT = 2
        const val LOADDWQ_LAYER_PAYLOAD = 0
        const val LOADDWQ_RESOURCE_INDEX_PAYLOAD = 1
        const val CLEAR_EFFECT_LAYER_PAYLOAD_WORD_COUNT = 1
        const val CLEAR_EFFECT_LAYER_LAYER_PAYLOAD = 0
        const val MAX_IMAGE_LAYERS = 32
        const val MAX_AUDIO_ARGUMENTS = 16
        const val MAX_AUDIO_CHANNEL = 15
        const val MAX_EFFECT_ARGUMENTS = 66
        const val MAX_SYSTEM_EFFECT_ID = 140
        const val MAX_EFFECT_RECT_COORD = 16_384
        const val MAX_EFFECT_RECT_DIMENSION = 16_384
        const val SETFILM_PAYLOAD_WORD_COUNT = 2
        const val SETFILM_ARGUMENT_COUNT_PAYLOAD = 0
        const val SETFILM_FILM_NUMBER_PAYLOAD = 1
        const val SETFILM_ARGUMENT_COUNT = 1
        const val CONTROL_FAMILY = 0x5556
        const val EXPRESSION_FAMILY = 0xA000

        const val CODE_PRINT = 0x0001
        const val CODE_LPRINT = 0x0002
        const val CODE_APPEND = 0x0003
        const val CODE_DRAW = 0x0004

        const val CODE_CONTROL_NOP = 0x0001
        const val CODE_CONTROL_IF = 0x0002
        const val CODE_CONTROL_ELSIF = 0x0003
        const val CODE_CONTROL_ELSE = 0x0004
        const val CODE_CONTROL_END = 0x0005
        const val CODE_CONTROL_NEXT = 0x0006
        const val CODE_CONTROL_ENDFILM = 0x000C
        const val CODE_CONTROL_EXIT = 0x000B
        const val CODE_CONTROL_SCRIPT = 0x000A
        const val CODE_CONTROL_SCRIPTJUMP = 0x0015
        const val CODE_CONTROL_SUBSCRIPT = 0x0016
        const val MODULE_TRANSFER_PAYLOAD_WORD_COUNT = 1
        const val MODULE_TRANSFER_POOL_INDEX_PAYLOAD = 0
        val MODULE_NAME_PATTERN = Regex("[A-Za-z0-9_]{1,64}")
        const val CODE_CONTROL_ENDKOMA = 0x000E
        const val CODE_CONTROL_ENDIF = 0x0013
        const val CODE_CONTROL_CASE = 0x0008
        const val CODE_CONTROL_SUB = 0x0009
        const val CODE_CONTROL_RETURN = 0x0007
        const val LOCAL_SUB_PAYLOAD_WORD_COUNT = 2
        const val LOCAL_SUB_TABLE_INDEX_PAYLOAD = 0
        const val LOCAL_SUB_ID_PAYLOAD = 1
        const val MAX_LOCAL_SUBROUTINE_DEPTH = 249
        const val MAX_LOCAL_SUBROUTINE_ID = 10_000
        const val SCRIPT_VAR_FAMILY = 0x8000
        const val SCRIPT_NUMBER_FAMILY = 0x9000
        const val SCRIPT_CALCU_ENZAN_FAMILY = 0xA000
        const val SCRIPT_CALCU_COMPOUND_FAMILY = 0xA200
        const val SCRIPT_CALCU_LOGICAL_FAMILY = 0xA300
        const val SCRIPT_CALCU_COMPARE_FAMILY = 0xA400
        const val SCRIPT_CALCU_COMPARE_EQUAL_FAMILY = 0xA500
        const val SCRIPT_CALCU_TOKEN = 0xA0000000u
        const val IMMEDIATE_VARIABLE_FLAG = 0x8000
        const val IMMEDIATE_VARIABLE_MASK = 0x7FFF
        const val MAX_EXPRESSION_TOKENS = 256
        const val MAX_EXPRESSION_STACK = 256
        const val OP_ASSIGN = '='.code
        const val OP_EQUAL = '='.code
        const val OP_NOT = '!'.code
        const val OP_ADD = '+'.code
        const val OP_SUBTRACT = '-'.code
        const val OP_MULTIPLY = '*'.code
        const val OP_DIVIDE = '/'.code
        const val OP_MODULO = '%'.code
        const val OP_BIT_AND = '&'.code
        const val OP_BIT_XOR = '^'.code
        const val OP_BIT_OR = '|'.code
        const val OP_LESS = '<'.code
        const val OP_GREATER = '>'.code
        const val CODE_OPEN_BRACE = 0x007B
        const val CODE_CLOSE_BRACE = 0x007D
        const val MAX_SELECT_OPTIONS = 64
        const val MAX_CASE_SCAN_RECORDS = 4096
        const val MAX_CONDITIONAL_BRANCHES = 64
        const val MAX_BRACE_SPAN_WORDS = 1_000_000L
        const val CODE_SELECT = 0x0006

        const val MESSAGE_INDEX_PAYLOAD_WORD = 0
        const val MESSAGE_REFERENCE_WORD_COUNT = 2
        const val MAX_DEBUG_LOG_BYTES = 1L * 1024L * 1024L
        const val MAX_DIAGNOSTIC_PAYLOAD_WORDS = 24
        val DEBUG_LOG_LOCK = Any()
    }
}
