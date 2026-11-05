package io.github.helterfall.ramen.runtime.systemnnn

import io.github.helterfall.ramen.parser.SystemNnnCommandRecord
import io.github.helterfall.ramen.parser.SystemNnnCommandStream
import io.github.helterfall.ramen.parser.SystemNnnCommandStreamReader
import io.github.helterfall.ramen.parser.SystemNnnRecordKind
import io.github.helterfall.ramen.parser.SystemNnnScript
import java.io.IOException

/**
 * First executable subset of the System-NNN VM.
 *
 * The record envelope and message-reference layout are verified against the
 * bundled SPT modules and the public reference runtime. PRINT, LPRINT and
 * APPEND resolve their first payload DWORD through the module's primary text
 * pool. SETFILM is additionally validated against the descriptor-3 film table;
 * local SUB/RETURN uses a bounded in-module stack. ENDFILM advances to the next
 * record so a following RETURN can restore the local caller. Inter-module
 * SCRIPT/SCRIPTJUMP/SUBSCRIPT and unverified resource semantics remain disabled.
 */
class SystemNnnCommandDecoderImpl(
    private val gtbResourceCount: Int,
    private val streamReader: SystemNnnCommandStreamReader = SystemNnnCommandStreamReader()
) : SystemNnnCommandDecoder {

    private var cachedScript: SystemNnnScript? = null
    private var cachedStream: SystemNnnCommandStream? = null
    private var cachedFilmTableEntries: List<io.github.helterfall.ramen.parser.SystemNnnFilmTableEntry>? = null
    private val variables = HashMap<Int, Int>()
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

        return when (record.recordKind) {
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

            SystemNnnRecordKind.COMMAND,
            SystemNnnRecordKind.FUNCTION,
            null -> throw unsupportedRecord(record)
        }
    }

    private fun decodeSystemCommand(
        script: SystemNnnScript,
        record: SystemNnnCommandRecord
    ): SystemNnnDecodedCommand {
        if (record.codeFamily != SYSTEM_COMMAND_FAMILY) {
            throw unsupportedRecord(record)
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

            CODE_SELECT -> decodeSelect(script, streamFor(script), record)

            else -> throw unsupportedRecord(record)
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

        // Reference runtime: old format stores count + option refs only.
        // Newer records append timing/serial fields and put message-prefix count
        // in the final DWORD.
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
     * Mirrors the reference runtime's CASE scan: each CASE contains a 1-based
     * option number. A following opening-brace record stores the block span in
     * DWORDs; skipping it lands on the closing brace and then the next CASE.
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
            cursor = skipBraceBlock(script, stream, first!!)
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


    private fun decodeSystemFunction(
        script: SystemNnnScript,
        stream: SystemNnnCommandStream,
        record: SystemNnnCommandRecord
    ): SystemNnnDecodedCommand {
        if (record.codeFamily != SYSTEM_FUNCTION_FAMILY) {
            throw unsupportedRecord(record)
        }

        return when (record.codeId) {
            CODE_SYSTEMFUNCTION_SETFILM -> decodeSetFilm(script, stream, record)
            else -> SystemNnnDecodedCommand.Skip(record.endOffsetExclusive)
        }
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
            throw unsupportedRecord(record)
        }
        if (record.codeFamily != CONTROL_FAMILY) {
            throw unsupportedRecord(record)
        }

        return when (record.codeId) {
            CODE_CONTROL_NOP,
            CODE_CONTROL_ENDKOMA,
            CODE_CONTROL_ENDIF -> SystemNnnDecodedCommand.Skip(record.endOffsetExclusive)

            CODE_CONTROL_CASE -> SystemNnnDecodedCommand.Skip(
                skipFollowingBranch(script, stream, record)
            )

            CODE_CONTROL_SUB -> decodeLocalSubroutine(script, stream, record)

            CODE_CONTROL_RETURN -> returnFromLocalSubroutine(stream, record)

            CODE_CONTROL_ENDFILM -> SystemNnnDecodedCommand.Skip(record.endOffsetExclusive)

            CODE_CONTROL_END -> SystemNnnDecodedCommand.End

            else -> throw unsupportedRecord(record)
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

                else -> throw IOException(
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
                OP_DIVIDE -> ExpressionValue(left.value / right.value.coerceAtLeast(1).let {
                    if (right.value < 0) -it else it
                })
                OP_MODULO -> ExpressionValue(left.value % right.value.coerceAtLeast(1).let {
                    if (right.value < 0) -it else it
                })
                OP_BIT_AND -> ExpressionValue(left.value and right.value)
                OP_BIT_XOR -> ExpressionValue(left.value xor right.value)
                OP_BIT_OR -> ExpressionValue(left.value or right.value)
                else -> throw IOException("Unsupported System-NNN CALCU operator 0x" + operator.toString(16) + ".")
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
                        OP_DIVIDE -> left.value / right.value.coerceAtLeast(1).let {
                            if (right.value < 0) -it else it
                        }
                        OP_MODULO -> left.value % right.value.coerceAtLeast(1).let {
                            if (right.value < 0) -it else it
                        }
                        OP_BIT_AND -> left.value and right.value
                        OP_BIT_XOR -> left.value xor right.value
                        OP_BIT_OR -> left.value or right.value
                        else -> throw IOException("Unsupported System-NNN compound operator.")
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
                    else -> throw IOException("Unsupported System-NNN logical operator.")
                }
                ExpressionValue(if (value) 1 else 0)
            }

            SCRIPT_CALCU_COMPARE_FAMILY -> {
                val value = when (operator) {
                    OP_LESS -> left.value < right.value
                    OP_GREATER -> left.value > right.value
                    else -> throw IOException("Unsupported System-NNN comparison operator.")
                }
                ExpressionValue(if (value) 1 else 0)
            }

            SCRIPT_CALCU_COMPARE_EQUAL_FAMILY -> {
                val value = when (operator) {
                    OP_LESS -> left.value <= right.value
                    OP_GREATER -> left.value >= right.value
                    OP_EQUAL -> left.value == right.value
                    OP_NOT -> left.value != right.value
                    else -> throw IOException("Unsupported System-NNN inclusive comparison operator.")
                }
                ExpressionValue(if (value) 1 else 0)
            }

            else -> throw IOException("Unsupported System-NNN CALCU operator family.")
        }
        stack += result
    }

    internal fun variableValue(index: Int): Int = variables[index] ?: 0

    private fun streamFor(script: SystemNnnScript): SystemNnnCommandStream {
        if (cachedScript !== script || cachedStream == null) {
            cachedStream = streamReader.read(script)
            cachedScript = script
            cachedFilmTableEntries = null
            variables.clear()
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

    private fun unsupportedRecord(record: SystemNnnCommandRecord): IOException =
        IOException(
            "Unsupported System-NNN record at 0x" +
                record.offset.toString(16) +
                " (identifier=0x" + record.identifier.toString(16) +
                ", code=0x" + record.code.toString(16) + ")."
        )

    private companion object {
        const val DWORD_BYTES = 4L
        const val RECORD_HEADER_WORDS = 3
        const val SYSTEM_COMMAND_FAMILY = 0x2222
        const val SYSTEM_FUNCTION_FAMILY = 0x3333
        const val CODE_SYSTEMFUNCTION_SETFILM = 0x001A
        const val SETFILM_PAYLOAD_WORD_COUNT = 2
        const val SETFILM_ARGUMENT_COUNT_PAYLOAD = 0
        const val SETFILM_FILM_NUMBER_PAYLOAD = 1
        const val SETFILM_ARGUMENT_COUNT = 1
        const val CONTROL_FAMILY = 0x5556
        const val EXPRESSION_FAMILY = 0xA000

        const val CODE_PRINT = 0x0001
        const val CODE_LPRINT = 0x0002
        const val CODE_APPEND = 0x0003

        const val CODE_CONTROL_NOP = 0x0001
        const val CODE_CONTROL_END = 0x0005
        const val CODE_CONTROL_ENDFILM = 0x000C
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
        const val MAX_BRACE_SPAN_WORDS = 1_000_000L
        const val CODE_SELECT = 0x0006

        const val MESSAGE_INDEX_PAYLOAD_WORD = 0
        const val MESSAGE_REFERENCE_WORD_COUNT = 2
    }
}
