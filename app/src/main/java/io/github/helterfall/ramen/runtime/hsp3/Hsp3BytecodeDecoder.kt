package io.github.helterfall.ramen.runtime.hsp3

import io.github.helterfall.ramen.parser.Hsp3Program
import io.github.helterfall.ramen.parser.Hsp3Token
import java.io.IOException
import java.util.ArrayDeque

data class Hsp3DecodeDiagnostic(
    val codeOffset:Int,val tokenType:Int?,val tokenValue:Long?,val extensionFlags:Int,val message:String
) {
    fun asLogMessage():String =
        "cs_offset=0x${codeOffset.toString(16)} token_type=${tokenType ?: "?"}" +
        " token_value=${tokenValue?.let { "0x${it.toString(16)}" } ?: "?"}" +
        " exflag=0x${extensionFlags.toString(16)}: $message"
}
private class Hsp3DecodeException(val token:Hsp3Token?,message:String,cause:Throwable?=null):IOException(message,cause)

/**
 * OpenHSP CS expression reader and command-argument decoder.
 *
 * CS values are 16-bit-word tokens (or 32-bit payload tokens). EXFLG_1 marks
 * command heads; EXFLG_2 marks subsequent argument boundaries; EXFLG_0 is the
 * compiler's single-literal fast-path flag; EXFLG_3 selects a 32-bit value.
 * Expression operators are postfix and use CALCCODE_* values 0..15.
 */
class Hsp3BytecodeDecoder : Hsp3InstructionDecoder {
    private val diagnosticBuffer=ArrayList<Hsp3DecodeDiagnostic>()
    val diagnostics:List<Hsp3DecodeDiagnostic> get()=diagnosticBuffer.toList()

    private data class PendingInstruction(
        val codeOffset: Int,
        val create: (Map<Int, Int>, Int) -> Hsp3Instruction
    )

    override fun decode(program:Hsp3Program):List<Hsp3Instruction> {
        diagnosticBuffer.clear()
        val tokens=try { program.tokens().toList() } catch(e:Exception) {
            val msg="CS token scan failed: ${e.message ?: e.javaClass.simpleName}"
            diagnosticBuffer+=Hsp3DecodeDiagnostic(0,null,null,0,msg)
            return listOf(Hsp3Instruction.Unsupported(TYPE_PROGCMD,0,0,-1,0L,msg))
        }
        if(tokens.isEmpty()) {
            val msg="The HSP3 Code Segment is empty."
            diagnosticBuffer+=Hsp3DecodeDiagnostic(0,null,null,0,msg)
            return listOf(Hsp3Instruction.Unsupported(TYPE_PROGCMD,0,-1,-1,0L,msg))
        }
        val heads=tokens.indices.filter { i ->
            val t=tokens[i]
            t.extensionFlags and EXFLG_1!=0 && t.type in COMMAND_TYPES
        }
        if(heads.isEmpty()) {
            val t=tokens.first()
            val msg="No HSP3 command heads were found in the Code Segment."
            diagnosticBuffer+=Hsp3DecodeDiagnostic(t.codeOffset,t.type,t.value,t.extensionFlags,msg)
            return listOf(Hsp3Instruction.Unsupported(t.type,t.value.toInt(),t.codeOffset,t.type,t.value,msg))
        }
        val pending=ArrayList<PendingInstruction>(heads.size)
        heads.forEachIndexed { ordinal,index ->
            val command=tokens[index]
            val end=heads.getOrNull(ordinal+1) ?: tokens.size
            try {
                val groups=readArgumentGroups(tokens,index+1,end)
                val decoded=if(command.type==TYPE_VAR) decodeAssignment(program,command,groups)
                    else decodeCommand(program,command,groups.map { decodeExpression(it,program) })
                pending+=decoded
                if(isUnsupportedCommand(command)) diagnosticBuffer+=Hsp3DecodeDiagnostic(
                    command.codeOffset,command.type,command.value,command.extensionFlags,
                    "Command is unsupported by this Android VM profile and will be skipped."
                )
            } catch(e:Exception) {
                val t=(e as? Hsp3DecodeException)?.token ?: command
                val msg=e.message ?: e.javaClass.simpleName
                diagnosticBuffer+=Hsp3DecodeDiagnostic(t.codeOffset,t.type,t.value,t.extensionFlags,msg)
                pending+=PendingInstruction(command.codeOffset) { _,_ ->
                    Hsp3Instruction.Unsupported(command.type,command.value.toInt(),t.codeOffset,t.type,t.value,msg)
                }
            }
        }
        val map=pending.mapIndexed { i,item -> item.codeOffset to i }.toMap()
        return pending.map { it.create(map,pending.size) }
    }

    private fun isUnsupportedCommand(t:Hsp3Token):Boolean = when(t.type) {
        TYPE_EXTCMD -> t.value.toInt() !in SUPPORTED_EXT_COMMANDS
        TYPE_INTCMD -> t.value.toInt() !in setOf(INTCMD_EXIST,INTCMD_BLOAD,INTCMD_BSAVE,INTCMD_GETKEY)
        TYPE_PROGCMD -> t.value.toInt() !in setOf(
            PROGCMD_GOTO,PROGCMD_GOSUB,PROGCMD_RETURN,PROGCMD_BREAK,PROGCMD_REPEAT,
            PROGCMD_LOOP,PROGCMD_CONTINUE,PROGCMD_DIM,PROGCMD_SDIM,PROGCMD_WAIT,
            PROGCMD_AWAIT,PROGCMD_END,PROGCMD_STOP
        )
        else -> t.type!=TYPE_VAR && t.type!=TYPE_CMPCMD
    }

    private fun readArgumentGroups(tokens:List<Hsp3Token>,startIndex:Int,endIndex:Int):List<List<Hsp3Token>> {
        if(startIndex>=endIndex) return emptyList()
        val groups=ArrayList<List<Hsp3Token>>()
        var current=ArrayList<Hsp3Token>()
        var depth=0
        var previousFast=false
        fun finish() { if(current.isNotEmpty()) groups+=current.toList(); current=ArrayList() }
        for(i in startIndex until endIndex) {
            val t=tokens[i]
            val open=t.type==TYPE_MARK && t.value=='('.code.toLong()
            val close=t.type==TYPE_MARK && t.value==')'.code.toLong()
            if(t.extensionFlags and EXFLG_2!=0 && depth==0) {
                if(current.isNotEmpty()) finish()
                else if(groups.isEmpty() || !previousFast) groups.add(emptyList())
            }
            if(t.extensionFlags and EXFLG_0!=0 && depth==0) {
                finish()
                groups+=listOf(t.copy(extensionFlags=t.extensionFlags and (EXFLG_0 or EXFLG_2).inv()))
                previousFast=true
                continue
            }
            // Keep nested EXFLG_2 separators until parenthesized arguments are parsed.
            current+=t.copy(extensionFlags=t.extensionFlags and EXFLG_0.inv())
            previousFast=false
            if(open) depth++
            if(close) {
                if(depth==0) throw Hsp3DecodeException(t,"Unbalanced closing parenthesis in HSP parameters.")
                depth--
            }
        }
        if(depth!=0) throw Hsp3DecodeException(tokens.getOrNull(endIndex-1),"Unbalanced HSP parentheses.")
        finish()
        return groups
    }

    private fun decodeExpression(tokens:List<Hsp3Token>,program:Hsp3Program):Hsp3Expression? {
        if(tokens.isEmpty()) return null
        if(tokens.size==1 && tokens[0].type==TYPE_MARK &&
            tokens[0].value==OMITTED_ARGUMENT_MARK.toLong()) return null
        val stack=ArrayDeque<Hsp3ExpressionNode>()
        var i=0
        while(i<tokens.size) {
            val t=tokens[i]
            try {
                when(t.type) {
                    TYPE_MARK -> {
                        val op=t.value.toInt()
                        if(op==OMITTED_ARGUMENT_MARK) throw IOException("Omitted marker inside expression.")
                        if(op !in CALCCODE_ADD..CALCCODE_LR) throw IOException("Unsupported expression marker $op.")
                        if(stack.size<2) throw IOException("The HSP postfix expression has too few operands.")
                        val right=stack.removeLast(); val left=stack.removeLast()
                        stack.addLast(Hsp3ExpressionNode.Binary(op,left,right))
                    }
                    TYPE_VAR -> {
                        val v=checkedIndex(t.value,program.header.maxValueCount,"variable")
                        val next=tokens.getOrNull(i+1)
                        if(next?.type==TYPE_MARK && next.value=='('.code.toLong()) {
                            val (args,nextIndex)=readParenthesizedArguments(tokens,i+1,program)
                            val indices=args.map { it ?: throw IOException("Omitted HSP array index.") }
                            stack.addLast(Hsp3ExpressionNode.Variable(v,indices)); i=nextIndex-1
                        } else stack.addLast(Hsp3ExpressionNode.Variable(v))
                    }
                    TYPE_STRING -> stack.addLast(Hsp3ExpressionNode.Literal(Hsp3RuntimeValue.StringValue(program.stringAt(t.value))))
                    TYPE_DNUM -> stack.addLast(Hsp3ExpressionNode.Literal(Hsp3RuntimeValue.DoubleValue(program.doubleAt(t.value))))
                    TYPE_INUM -> stack.addLast(Hsp3ExpressionNode.Literal(Hsp3RuntimeValue.IntegerValue(t.value.toInt())))
                    TYPE_LABEL -> {
                        val target=program.labelCodeOffset(checkedIndex(t.value,program.objectTempSegment.sizeBytes/4L,"label"))
                        stack.addLast(Hsp3ExpressionNode.Literal(Hsp3RuntimeValue.LabelValue(target.toInt())))
                    }
                    TYPE_SYSVAR,TYPE_EXTSYSVAR -> {
                        val next=tokens.getOrNull(i+1)
                        if(next?.type==TYPE_MARK && next.value=='('.code.toLong()) {
                            i=skipParenthesized(tokens,i+1)-1
                            diagnosticBuffer+=Hsp3DecodeDiagnostic(
                                t.codeOffset,t.type,t.value,t.extensionFlags,
                                "Indexed system-variable callback retained as one opaque value."
                            )
                            stack.addLast(Hsp3ExpressionNode.Opaque(t.type,t.value.toInt()))
                        } else stack.addLast(
                            Hsp3ExpressionNode.SystemVariable(
                                t.value.toInt(), external = t.type == TYPE_EXTSYSVAR
                            )
                        )
                    }
                    TYPE_XLABEL -> {
                        val next=tokens.getOrNull(i+1)
                        if(next?.type==TYPE_MARK && next.value=='('.code.toLong()) {
                            i=skipParenthesized(tokens,i+1)-1
                        }
                        diagnosticBuffer+=Hsp3DecodeDiagnostic(
                            t.codeOffset,t.type,t.value,t.extensionFlags,
                            "TYPE_XLABEL requires external label metadata; retained as one opaque value."
                        )
                        stack.addLast(Hsp3ExpressionNode.Opaque(t.type,t.value.toInt()))
                    }
                    TYPE_INTFUNC -> {
                        val (args,nextIndex)=readParenthesizedArguments(tokens,i+1,program)
                        stack.addLast(Hsp3ExpressionNode.Function(t.value.toInt(),args)); i=nextIndex-1
                    }
                    TYPE_STRUCT,TYPE_INTCMD,TYPE_EXTCMD,TYPE_CMPCMD,TYPE_MODCMD,
                    TYPE_PROGCMD,TYPE_DLLFUNC,TYPE_DLLCTRL,TYPE_USERDEF -> {
                        if(t.type!=TYPE_STRUCT) {
                            val next=tokens.getOrNull(i+1)
                            if(next?.type==TYPE_MARK && next.value=='('.code.toLong()) {
                                // OpenHSP dispatches these through HSP3TYPEINFO.reffunc.
                                // A callback may consume its own argument list; discard
                                // the balanced region as one opaque return value here.
                                i=skipParenthesized(tokens,i+1)-1
                            }
                        }
                        diagnosticBuffer+=Hsp3DecodeDiagnostic(
                            t.codeOffset,t.type,t.value,t.extensionFlags,
                            if(t.type==TYPE_STRUCT) "TYPE_STRUCT needs module metadata; retained as one opaque value."
                            else "Token type ${t.type} has no runtime resolver; retained as one opaque value."
                        )
                        stack.addLast(Hsp3ExpressionNode.Opaque(t.type,t.value.toInt()))
                    }
                    else -> throw IOException("Unsupported HSP expression token type ${t.type}.")
                }
            } catch(e:Hsp3DecodeException) { throw e }
            catch(e:Exception) { throw Hsp3DecodeException(t,e.message ?: e.javaClass.simpleName,e) }
            i++
        }
        if(stack.size!=1) {
            val t=tokens.lastOrNull()
            throw Hsp3DecodeException(t,"The HSP postfix expression leaves ${stack.size} values on its stack.")
        }
        return Hsp3Expression(stack.removeLast())
    }

    /**
     * TYPE_INTFUNC is a prefix token followed by '(' argument expressions ')'.
     * Its argument list uses the same EXFLG_2/default convention as command
     * parameters, and nested calls are skipped by matching parenthesis markers.
     */
    private fun skipParenthesized(tokens:List<Hsp3Token>,openIndex:Int):Int {
        val open=tokens.getOrNull(openIndex)
        if(open==null || open.type!=TYPE_MARK || open.value!='('.code.toLong()) {
            throw Hsp3DecodeException(open,"Expected an opening parenthesis after an HSP callback token.")
        }
        var depth=0
        for(index in openIndex+1 until tokens.size) {
            val token=tokens[index]
            if(token.type==TYPE_MARK && token.value=='('.code.toLong()) depth++
            else if(token.type==TYPE_MARK && token.value==')'.code.toLong()) {
                if(depth==0) return index+1
                depth--
            }
        }
        throw Hsp3DecodeException(open,"HSP callback argument list has no matching closing parenthesis.")
    }

    private fun readParenthesizedArguments(
        tokens: List<Hsp3Token>,
        openIndex: Int,
        program: Hsp3Program
    ): Pair<List<Hsp3Expression?>, Int> {
        val open = tokens.getOrNull(openIndex)
            ?: throw IOException("An HSP built-in function is missing its opening parenthesis.")
        if (open.type != TYPE_MARK || open.value != '('.code.toLong()) {
            throw IOException("An HSP built-in function is not followed by '('.")
        }

        var nesting = 0
        var cursor = openIndex + 1
        var closeIndex = -1
        while (cursor < tokens.size) {
            val token = tokens[cursor]
            if (token.type == TYPE_MARK && token.value == '('.code.toLong()) {
                nesting += 1
            } else if (token.type == TYPE_MARK && token.value == ')'.code.toLong()) {
                if (nesting == 0) {
                    closeIndex = cursor
                    break
                }
                nesting -= 1
            }
            cursor += 1
        }
        if (closeIndex < 0) {
            throw IOException("An HSP built-in function has no closing parenthesis.")
        }

        val groups = readArgumentGroups(tokens, openIndex + 1, closeIndex)
        return groups.map { group -> decodeExpression(group, program) } to (closeIndex + 1)
    }

    private fun decodeCommand(
        program: Hsp3Program,
        command: Hsp3Token,
        args: List<Hsp3Expression?>
    ): PendingInstruction {
        val pc = command.codeOffset
        val commandId = command.value.toInt()
        return when (command.type) {
            TYPE_EXTCMD -> if (commandId in SUPPORTED_EXT_COMMANDS) {
                PendingInstruction(pc) { _, _ ->
                    Hsp3Instruction.NativeCommand(TYPE_EXTCMD, commandId, args)
                }
            } else {
                PendingInstruction(pc) { _, _ ->
                    Hsp3Instruction.Unsupported(TYPE_EXTCMD, commandId)
                }
            }
            TYPE_INTCMD -> {
                when (commandId) {
                    INTCMD_GETKEY -> {
                        val destination = requiredVariable(args.getOrNull(0), "getkey destination")
                        val keyCode = args.getOrNull(1)
                            ?: constantExpression(Hsp3RuntimeValue.IntegerValue(1))
                        PendingInstruction(pc) { _, _ ->
                            Hsp3Instruction.GetKey(
                                destinationVariable = destination.index,
                                keyCode = keyCode,
                                destinationIndices = destination.indices
                            )
                        }
                    }
                    INTCMD_EXIST -> {
                        val fileName = args.firstOrNull()
                            ?: throw IOException("HSP exist requires a filename expression.")
                        if (args.getOrNull(1) == null) {
                            PendingInstruction(pc) { _, _ -> Hsp3Instruction.FileExists(fileName) }
                        } else {
                            val destination = requiredVariable(args.getOrNull(1), "bload buffer")
                            PendingInstruction(pc) { _, _ ->
                                Hsp3Instruction.FileTransfer(
                                    commandId = INTCMD_BLOAD, fileName = fileName,
                                    destinationVariable = destination.index,
                                    size = args.getOrNull(2), fileOffset = args.getOrNull(3)
                                )
                            }
                        }
                    }
                    INTCMD_BLOAD, INTCMD_BSAVE -> {
                        val fileName = args.firstOrNull()
                            ?: throw IOException("HSP bload/bsave requires a filename expression.")
                        if (commandId == INTCMD_BSAVE && args.getOrNull(1) == null) {
                            PendingInstruction(pc) { _, _ -> Hsp3Instruction.FileExists(fileName) }
                        } else {
                            val destination = requiredVariable(args.getOrNull(1), "bload/bsave buffer")
                            if (destination.indices.isNotEmpty()) {
                                throw IOException("HSP bload/bsave requires a plain variable buffer.")
                            }
                            PendingInstruction(pc) { _, _ ->
                                Hsp3Instruction.FileTransfer(
                                    commandId = commandId, fileName = fileName,
                                    destinationVariable = destination.index,
                                    size = args.getOrNull(2), fileOffset = args.getOrNull(3)
                                )
                            }
                        }
                    }
                    else -> PendingInstruction(pc) { _, _ ->
                        Hsp3Instruction.Unsupported(TYPE_INTCMD, commandId)
                    }
                }
            }
            TYPE_PROGCMD -> decodeProgramCommand(program, command, args)
            TYPE_CMPCMD -> decodeConditionalCommand(program, command, args)
            else -> PendingInstruction(pc) { _, _ ->
                Hsp3Instruction.Unsupported(command.type, commandId)
            }
        }
    }

    private fun decodeProgramCommand(
        program: Hsp3Program,
        command: Hsp3Token,
        args: List<Hsp3Expression?>
    ): PendingInstruction {
        val id = command.value.toInt()
        val pc = command.codeOffset
        return when (id) {
            PROGCMD_GOTO, PROGCMD_GOSUB -> {
                val target = requiredLabelOffset(program, args.firstOrNull(), "goto/gosub")
                PendingInstruction(pc) { addressMap, count ->
                    val index = resolveTarget(target, addressMap, count, program.codeSegment.sizeBytes.toInt())
                    if (id == PROGCMD_GOTO) Hsp3Instruction.Jump(index)
                    else Hsp3Instruction.Gosub(index)
                }
            }
            PROGCMD_RETURN -> PendingInstruction(pc) { _, _ -> Hsp3Instruction.Return }
            PROGCMD_BREAK -> {
                val target = requiredLabelOffset(program, args.firstOrNull(), "break")
                PendingInstruction(pc) { addressMap, count ->
                    Hsp3Instruction.Break(
                        resolveTarget(target, addressMap, count, program.codeSegment.sizeBytes)
                    )
                }
            }
            PROGCMD_REPEAT -> {
                val target = requiredLabelOffset(program, args.firstOrNull(), "repeat")
                val countExpression = args.getOrNull(1)
                    ?: constantExpression(Hsp3RuntimeValue.IntegerValue(-1))
                val initialExpression = args.getOrNull(2)
                    ?: constantExpression(Hsp3RuntimeValue.IntegerValue(0))
                val constantCount = countExpression.constantIntOrNull()
                val constantInitial = initialExpression.constantIntOrNull()
                PendingInstruction(pc) { addressMap, count ->
                    Hsp3Instruction.Repeat(
                        count = constantCount ?: -1,
                        exitInstruction = resolveTarget(target, addressMap, count, program.codeSegment.sizeBytes.toInt()),
                        initialValue = constantInitial ?: 0,
                        countExpression = countExpression.takeIf { constantCount == null },
                        initialValueExpression = initialExpression.takeIf { constantInitial == null }
                    )
                }
            }
            PROGCMD_LOOP -> PendingInstruction(pc) { _, _ -> Hsp3Instruction.Loop }
            PROGCMD_CONTINUE -> {
                val target = requiredLabelOffset(program, args.firstOrNull(), "continue")
                val countExpression = args.getOrNull(1)
                PendingInstruction(pc) { addressMap, count ->
                    Hsp3Instruction.Continue(
                        targetInstruction = resolveTarget(
                            target, addressMap, count, program.codeSegment.sizeBytes
                        ),
                        countExpression = countExpression
                    )
                }
            }
            PROGCMD_DIM, PROGCMD_SDIM -> {
                val destination = requiredVariable(args.firstOrNull(), "dim destination")
                if (destination.indices.isNotEmpty()) {
                    throw IOException("HSP dim/sdim requires a plain variable, not an array element.")
                }
                val dimensions = args.drop(1).take(4)
                PendingInstruction(pc) { _, _ ->
                    Hsp3Instruction.Dimension(destination.index, dimensions, stringBuffer = id == PROGCMD_SDIM)
                }
            }
            PROGCMD_WAIT -> {
                val delayExpression = args.firstOrNull()
                    ?: constantExpression(Hsp3RuntimeValue.IntegerValue(100))
                val constantDelay = delayExpression.constantIntOrNull()
                PendingInstruction(pc) { _, _ ->
                    Hsp3Instruction.WaitFor(
                        delayMillis = ((constantDelay ?: 0).coerceAtLeast(0) * 10L).coerceAtMost(MAX_WAIT_MS),
                        delayExpression = delayExpression.takeIf { constantDelay == null },
                        delayMultiplier = 10L
                    )
                }
            }
            PROGCMD_AWAIT -> {
                val delayExpression = args.firstOrNull()
                    ?: constantExpression(Hsp3RuntimeValue.IntegerValue(0))
                val constantDelay = delayExpression.constantIntOrNull()
                PendingInstruction(pc) { _, _ ->
                    Hsp3Instruction.WaitFor(
                        delayMillis = (constantDelay ?: 0).coerceAtLeast(0).toLong().coerceAtMost(MAX_WAIT_MS),
                        delayExpression = delayExpression.takeIf { constantDelay == null },
                        delayMultiplier = 1L
                    )
                }
            }
            PROGCMD_RANDOMIZE -> PendingInstruction(pc) { _, _ ->
                Hsp3Instruction.Randomize(args.firstOrNull())
            }
            PROGCMD_END -> PendingInstruction(pc) { _, _ -> Hsp3Instruction.End }
            PROGCMD_STOP -> PendingInstruction(pc) { _, _ ->
                val seed = args.firstOrNull()
                if (seed == null) Hsp3Instruction.End else Hsp3Instruction.Randomize(seed)
            }
            else -> PendingInstruction(pc) { _, _ -> Hsp3Instruction.Unsupported(TYPE_PROGCMD, id) }
        }
    }

    private fun decodeConditionalCommand(
        program: Hsp3Program,
        command: Hsp3Token,
        args: List<Hsp3Expression?>
    ): PendingInstruction {
        if (command.encodedSizeBytes != 4) {
            throw IOException("An HSP if/else command must use a 16-bit command token.")
        }
        val rawOffsetLocation = command.codeOffset + command.encodedSizeBytes
        val skipWords = readUInt16(program.codeSegment.bytes, rawOffsetLocation)
        val branchTarget = rawOffsetLocation.toLong() + 2L + skipWords.toLong() * 2L
        if (branchTarget < 0L || branchTarget > program.codeSegment.sizeBytes.toLong()) {
            throw IOException("An HSP if/else branch target lies outside CS.")
        }
        val id = command.value.toInt()
        val pc = command.codeOffset
        val targetInstruction = { addressMap: Map<Int, Int>, count: Int ->
            resolveTarget(branchTarget.toInt(), addressMap, count, program.codeSegment.sizeBytes)
        }
        return when (id) {
            CMP_CMD_IF -> {
                val condition = args.firstOrNull()
                    ?: throw IOException("The HSP if command has no condition expression.")
                PendingInstruction(pc) { addressMap, count ->
                    Hsp3Instruction.JumpIfFalse(condition, targetInstruction(addressMap, count))
                }
            }
            CMP_CMD_ELSE -> PendingInstruction(pc) { addressMap, count ->
                Hsp3Instruction.Jump(targetInstruction(addressMap, count))
            }
            else -> PendingInstruction(pc) { _, _ ->
                Hsp3Instruction.Unsupported(TYPE_CMPCMD, id)
            }
        }
    }

    private fun decodeAssignment(
        program: Hsp3Program,
        command: Hsp3Token,
        argumentGroups: List<List<Hsp3Token>>
    ): PendingInstruction {
        val variable = checkedIndex(command.value, program.header.maxValueCount, "assignment variable")
        // arr(i,j) and the RHS can be split into separate EXFLG_2 groups.
        // Reconstruct the assignment token stream instead of rejecting group 2.
        val assignmentTokens = argumentGroups.flatten()
        var cursor = 0
        val arrayIndices = if (
            assignmentTokens.getOrNull(cursor)?.let {
                it.type == TYPE_MARK && it.value == '('.code.toLong()
            } == true
        ) {
            val (indices, nextCursor) = readParenthesizedArguments(assignmentTokens, cursor, program)
            cursor = nextCursor
            indices.map { expression ->
                expression ?: throw IOException("An HSP array assignment index cannot be omitted.")
            }
        } else {
            emptyList()
        }
        val operatorToken = assignmentTokens.getOrNull(cursor)
            ?: throw IOException("An HSP variable assignment is missing its operator token.")
        if (operatorToken.type != TYPE_MARK) {
            throw IOException("An HSP variable assignment is missing its TYPE_MARK operator.")
        }
        val operator = operatorToken.value.toInt()
        if (operator !in CALCCODE_ADD..CALCCODE_LR) {
            throw IOException("Unsupported HSP assignment operator $operator.")
        }
        val expressionTokens = assignmentTokens.drop(cursor + 1)
        val expression = if (expressionTokens.isEmpty()) {
            constantExpression(Hsp3RuntimeValue.IntegerValue(1))
        } else {
            decodeExpression(expressionTokens, program)
                ?: constantExpression(Hsp3RuntimeValue.IntegerValue(1))
        }
        return PendingInstruction(command.codeOffset) { _, _ ->
            Hsp3Instruction.Assign(variable, operator, expression, arrayIndices)
        }
    }

    private fun requiredVariable(expression: Hsp3Expression?, label: String): Hsp3VariableReference =
        expression?.variableReferenceOrNull()
            ?: throw IOException("The HSP $label must be a variable reference.")

    private fun requiredLabelOffset(
        program: Hsp3Program,
        expression: Hsp3Expression?,
        label: String
    ): Int {
        val target = expression?.constantValueOrNull() as? Hsp3RuntimeValue.LabelValue
            ?: throw IOException("The HSP $label command requires an OT label operand.")
        if (target.codeOffsetBytes !in 0..program.codeSegment.sizeBytes) {
            throw IOException("The HSP $label target is outside CS.")
        }
        return target.codeOffsetBytes
    }

    private fun resolveTarget(
        targetCodeOffset: Int,
        addressMap: Map<Int, Int>,
        instructionCount: Int,
        codeSizeBytes: Int
    ): Int {
        if (targetCodeOffset == codeSizeBytes) return instructionCount
        return addressMap[targetCodeOffset]
            ?: throw IOException("HSP branch target 0x${targetCodeOffset.toString(16)} is not a command boundary.")
    }

    private fun checkedIndex(value: Long, maxExclusive: Long, label: String): Int {
        if (value < 0L || value >= maxExclusive || value > Int.MAX_VALUE.toLong()) {
            throw IOException("The HSP $label index is outside its declared range.")
        }
        return value.toInt()
    }

    private fun readUInt16(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset > bytes.size - 2) {
            throw IOException("A raw HSP 16-bit field extends beyond CS.")
        }
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    companion object {
        const val TYPE_MARK = 0
        const val TYPE_VAR = 1
        const val TYPE_STRING = 2
        const val TYPE_DNUM = 3
        const val TYPE_INUM = 4
        const val TYPE_STRUCT = 5
        const val TYPE_XLABEL = 6
        const val TYPE_LABEL = 7
        const val TYPE_INTCMD = 8
        const val TYPE_EXTCMD = 9
        const val TYPE_EXTSYSVAR = 10
        const val TYPE_CMPCMD = 11
        const val TYPE_MODCMD = 12
        const val TYPE_SYSVAR = 14
        const val TYPE_PROGCMD = 15
        const val TYPE_DLLFUNC = 16
        const val TYPE_DLLCTRL = 17
        const val TYPE_USERDEF = 18
        const val TYPE_INTFUNC = 13

        const val EXFLG_0 = 0x1000
        const val EXFLG_1 = 0x2000
        const val EXFLG_2 = 0x4000
        const val EXFLG_3 = 0x8000

        const val CALCCODE_ADD = 0
        const val CALCCODE_SUB = 1
        const val CALCCODE_MUL = 2
        const val CALCCODE_DIV = 3
        const val CALCCODE_MOD = 4
        const val CALCCODE_AND = 5
        const val CALCCODE_OR = 6
        const val CALCCODE_XOR = 7
        const val CALCCODE_EQ = 8
        const val CALCCODE_NE = 9
        const val CALCCODE_GT = 10
        const val CALCCODE_LT = 11
        const val CALCCODE_GTEQ = 12
        const val CALCCODE_LTEQ = 13
        const val CALCCODE_RR = 14
        const val CALCCODE_LR = 15

        const val INTCMD_EXIST = 0x11
        const val INTCMD_BLOAD = 0x16
        const val INTCMD_BSAVE = 0x17
        const val INTCMD_GETKEY = 0x23
        const val CMP_CMD_IF = 0
        const val CMP_CMD_ELSE = 1
        const val PROGCMD_GOTO = 0x00
        const val PROGCMD_GOSUB = 0x01
        const val PROGCMD_RETURN = 0x02
        const val PROGCMD_BREAK = 0x03
        const val PROGCMD_REPEAT = 0x04
        const val PROGCMD_LOOP = 0x05
        const val PROGCMD_CONTINUE = 0x06
        const val PROGCMD_DIM = 0x09
        const val PROGCMD_SDIM = 0x0A
        const val PROGCMD_WAIT = 0x07
        const val PROGCMD_AWAIT = 0x08
        const val PROGCMD_END = 0x10
        const val PROGCMD_STOP = 0x11
        const val PROGCMD_RANDOMIZE = 0x11

        private const val TOKEN_TYPE_MASK = 0x0FFF
        private const val OMITTED_ARGUMENT_MARK = 63
        private const val MAX_WAIT_MS = 60_000L
        private val COMMAND_TYPES = setOf(
            TYPE_VAR,TYPE_INTCMD,TYPE_EXTCMD,TYPE_CMPCMD,TYPE_PROGCMD
        )
        private val SUPPORTED_EXT_COMMANDS = setOf(
            Hsp3CommandIds.MES,
            Hsp3CommandIds.TITLE,
            Hsp3CommandIds.POS,
            Hsp3CommandIds.CLS,
            Hsp3CommandIds.FONT,
            Hsp3CommandIds.PICLOAD,
            Hsp3CommandIds.COLOR,
            Hsp3CommandIds.PALCOLOR,
            Hsp3CommandIds.PALETTE,
            Hsp3CommandIds.REDRAW,
            Hsp3CommandIds.WIDTH,
            Hsp3CommandIds.GSEL,
            Hsp3CommandIds.GCOPY,
            Hsp3CommandIds.GZOOM,
            Hsp3CommandIds.GROTATE,
            Hsp3CommandIds.CELLLOAD,
            Hsp3CommandIds.CELDIV,
            Hsp3CommandIds.CELPUT,
            Hsp3CommandIds.GMODE,
            Hsp3CommandIds.BUFFER,
            Hsp3CommandIds.SCREEN,
            Hsp3CommandIds.BOXF,
            Hsp3CommandIds.STICK,
            Hsp3CommandIds.GRECT
        )

        private fun constantExpression(value: Hsp3RuntimeValue) =
            Hsp3Expression(Hsp3ExpressionNode.Literal(value))
    }
}

data class Hsp3VariableReference(
    val index: Int,
    val indices: List<Hsp3Expression>
)

sealed class Hsp3ExpressionNode {
    data class Literal(val value: Hsp3RuntimeValue) : Hsp3ExpressionNode()
    data class Variable(
        val index: Int,
        val indices: List<Hsp3Expression> = emptyList()
    ) : Hsp3ExpressionNode()
    data class SystemVariable(val index: Int, val external: Boolean = false) : Hsp3ExpressionNode()
    data class Opaque(val tokenType:Int,val tokenValue:Int) : Hsp3ExpressionNode()
    data class Function(val id: Int, val arguments: List<Hsp3Expression?>) : Hsp3ExpressionNode()
    data class Binary(
        val operator: Int,
        val left: Hsp3ExpressionNode,
        val right: Hsp3ExpressionNode
    ) : Hsp3ExpressionNode()
}

sealed class Hsp3RuntimeValue {
    data class IntegerValue(val value: Int) : Hsp3RuntimeValue()
    data class DoubleValue(val value: Double) : Hsp3RuntimeValue()
    data class StringValue(val value: String) : Hsp3RuntimeValue()
    data class LabelValue(val codeOffsetBytes: Int) : Hsp3RuntimeValue()

    fun asInt(): Int = when (this) {
        is IntegerValue -> value
        is DoubleValue -> value.toInt()
        is StringValue -> value.toIntOrNull() ?: 0
        is LabelValue -> codeOffsetBytes
    }

    fun asDouble(): Double = when (this) {
        is IntegerValue -> value.toDouble()
        is DoubleValue -> value
        is StringValue -> value.toDoubleOrNull() ?: 0.0
        is LabelValue -> codeOffsetBytes.toDouble()
    }

    fun asString(): String = when (this) {
        is IntegerValue -> value.toString()
        is DoubleValue -> value.toString()
        is StringValue -> value
        is LabelValue -> codeOffsetBytes.toString()
    }
}

class Hsp3Expression internal constructor(
    internal val root: Hsp3ExpressionNode
) {
    fun evaluate(
        variable: (Int) -> Hsp3RuntimeValue,
        systemVariable: (Int) -> Hsp3RuntimeValue,
        function: (Int, List<Hsp3RuntimeValue?>) -> Hsp3RuntimeValue = { id, _ ->
            throw IOException("Unsupported HSP built-in function ID $id.")
        },
        arrayVariable: (Int, List<Int>) -> Hsp3RuntimeValue = { index, _ ->
            throw IOException("HSP array variable $index was evaluated without an array runtime.")
        },
        externalSystemVariable: (Int) -> Hsp3RuntimeValue = systemVariable
    ): Hsp3RuntimeValue = evaluateNode(
        root, variable, systemVariable, function, arrayVariable, externalSystemVariable
    )

    fun singleVariableReference(): Int? =
        (root as? Hsp3ExpressionNode.Variable)?.takeIf { it.indices.isEmpty() }?.index

    fun variableReferenceOrNull(): Hsp3VariableReference? =
        (root as? Hsp3ExpressionNode.Variable)?.let {
            Hsp3VariableReference(it.index, it.indices)
        }

    fun constantValueOrNull(): Hsp3RuntimeValue? =
        (root as? Hsp3ExpressionNode.Literal)?.value

    fun constantIntOrNull(): Int? =
        (constantValueOrNull() as? Hsp3RuntimeValue.IntegerValue)?.value

    private fun evaluateNode(
        node: Hsp3ExpressionNode,
        variable: (Int) -> Hsp3RuntimeValue,
        systemVariable: (Int) -> Hsp3RuntimeValue,
        function: (Int, List<Hsp3RuntimeValue?>) -> Hsp3RuntimeValue,
        arrayVariable: (Int, List<Int>) -> Hsp3RuntimeValue,
        externalSystemVariable: (Int) -> Hsp3RuntimeValue
    ): Hsp3RuntimeValue = when (node) {
        is Hsp3ExpressionNode.Literal -> node.value
        is Hsp3ExpressionNode.Variable -> {
            if (node.indices.isEmpty()) {
                variable(node.index)
            } else {
                arrayVariable(
                    node.index,
                    node.indices.map { indexExpression ->
                        indexExpression.evaluate(
                            variable, systemVariable, function, arrayVariable, externalSystemVariable
                        ).asInt()
                    }
                )
            }
        }
        is Hsp3ExpressionNode.SystemVariable -> if (node.external) {
            externalSystemVariable(node.index)
        } else systemVariable(node.index)
        is Hsp3ExpressionNode.Opaque -> Hsp3RuntimeValue.IntegerValue(0)
        is Hsp3ExpressionNode.Function -> function(
            node.id,
            node.arguments.map { argument ->
                argument?.evaluate(
                    variable, systemVariable, function, arrayVariable, externalSystemVariable
                )
            }
        )
        is Hsp3ExpressionNode.Binary -> applyBinary(
            node.operator,
            evaluateNode(node.left, variable, systemVariable, function, arrayVariable, externalSystemVariable),
            evaluateNode(node.right, variable, systemVariable, function, arrayVariable, externalSystemVariable)
        )
    }

    private fun applyBinary(
        operator: Int,
        left: Hsp3RuntimeValue,
        right: Hsp3RuntimeValue
    ): Hsp3RuntimeValue {
        if (operator == Hsp3BytecodeDecoder.CALCCODE_ADD &&
            (left is Hsp3RuntimeValue.StringValue || right is Hsp3RuntimeValue.StringValue)
        ) {
            return Hsp3RuntimeValue.StringValue(left.asString() + right.asString())
        }
        val a = left.asDouble()
        val b = right.asDouble()
        val ints = left is Hsp3RuntimeValue.IntegerValue && right is Hsp3RuntimeValue.IntegerValue
        if (ints) {
            val ai = left.asInt()
            val bi = right.asInt()
            return when (operator) {
                Hsp3BytecodeDecoder.CALCCODE_ADD -> Hsp3RuntimeValue.IntegerValue(ai + bi)
                Hsp3BytecodeDecoder.CALCCODE_SUB -> Hsp3RuntimeValue.IntegerValue(ai - bi)
                Hsp3BytecodeDecoder.CALCCODE_MUL -> Hsp3RuntimeValue.IntegerValue(ai * bi)
                Hsp3BytecodeDecoder.CALCCODE_DIV -> {
                    if (bi == 0) throw IOException("Division by zero in an HSP expression.")
                    Hsp3RuntimeValue.IntegerValue(ai / bi)
                }
                Hsp3BytecodeDecoder.CALCCODE_MOD -> {
                    if (bi == 0) throw IOException("Modulo by zero in an HSP expression.")
                    Hsp3RuntimeValue.IntegerValue(ai % bi)
                }
                Hsp3BytecodeDecoder.CALCCODE_AND -> Hsp3RuntimeValue.IntegerValue(ai and bi)
                Hsp3BytecodeDecoder.CALCCODE_OR -> Hsp3RuntimeValue.IntegerValue(ai or bi)
                Hsp3BytecodeDecoder.CALCCODE_XOR -> Hsp3RuntimeValue.IntegerValue(ai xor bi)
                Hsp3BytecodeDecoder.CALCCODE_EQ -> Hsp3RuntimeValue.IntegerValue(if (ai == bi) 1 else 0)
                Hsp3BytecodeDecoder.CALCCODE_NE -> Hsp3RuntimeValue.IntegerValue(if (ai != bi) 1 else 0)
                Hsp3BytecodeDecoder.CALCCODE_GT -> Hsp3RuntimeValue.IntegerValue(if (ai > bi) 1 else 0)
                Hsp3BytecodeDecoder.CALCCODE_LT -> Hsp3RuntimeValue.IntegerValue(if (ai < bi) 1 else 0)
                Hsp3BytecodeDecoder.CALCCODE_GTEQ -> Hsp3RuntimeValue.IntegerValue(if (ai >= bi) 1 else 0)
                Hsp3BytecodeDecoder.CALCCODE_LTEQ -> Hsp3RuntimeValue.IntegerValue(if (ai <= bi) 1 else 0)
                Hsp3BytecodeDecoder.CALCCODE_RR -> Hsp3RuntimeValue.IntegerValue(ai shr bi)
                Hsp3BytecodeDecoder.CALCCODE_LR -> Hsp3RuntimeValue.IntegerValue(ai shl bi)
                else -> throw IOException("Unknown HSP expression operator $operator.")
            }
        }
        return when (operator) {
            Hsp3BytecodeDecoder.CALCCODE_ADD -> Hsp3RuntimeValue.DoubleValue(a + b)
            Hsp3BytecodeDecoder.CALCCODE_SUB -> Hsp3RuntimeValue.DoubleValue(a - b)
            Hsp3BytecodeDecoder.CALCCODE_MUL -> Hsp3RuntimeValue.DoubleValue(a * b)
            Hsp3BytecodeDecoder.CALCCODE_DIV -> {
                if (b == 0.0) throw IOException("Division by zero in an HSP expression.")
                Hsp3RuntimeValue.DoubleValue(a / b)
            }
            Hsp3BytecodeDecoder.CALCCODE_EQ -> Hsp3RuntimeValue.IntegerValue(if (a == b) 1 else 0)
            Hsp3BytecodeDecoder.CALCCODE_NE -> Hsp3RuntimeValue.IntegerValue(if (a != b) 1 else 0)
            Hsp3BytecodeDecoder.CALCCODE_GT -> Hsp3RuntimeValue.IntegerValue(if (a > b) 1 else 0)
            Hsp3BytecodeDecoder.CALCCODE_LT -> Hsp3RuntimeValue.IntegerValue(if (a < b) 1 else 0)
            Hsp3BytecodeDecoder.CALCCODE_GTEQ -> Hsp3RuntimeValue.IntegerValue(if (a >= b) 1 else 0)
            Hsp3BytecodeDecoder.CALCCODE_LTEQ -> Hsp3RuntimeValue.IntegerValue(if (a <= b) 1 else 0)
            else -> throw IOException("This HSP operator requires integer operands.")
        }
    }
}
