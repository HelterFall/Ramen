package io.github.helterfall.ramen.inspection

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

internal object LiveMakerLsbFixture {
    fun build(
        version: Int = 103,
        commands: List<ByteArray>,
        unknownCommandType: Int? = null
    ): ByteArray {
        val output = ByteArrayOutputStream()
        writeU32(output, version.toLong())
        output.write(0) // flags
        writeU32(output, 64) // command type map entries
        writeU32(output, 1) // packed parameter-map bytes per command type
        repeat(64) { output.write(0) }
        writeU32(output, (commands.size + if (unknownCommandType != null) 1 else 0).toLong())
        commands.forEach(output::write)
        if (unknownCommandType != null) {
            output.write(command(unknownCommandType, ByteArray(0)))
        }
        return output.toByteArray()
    }

    fun command(type: Int, body: ByteArray, lineNo: Int = 0): ByteArray =
        ByteArrayOutputStream().also { output ->
            output.write(type)
            writeU32(output, 0) // indent
            output.write(0) // mute
            output.write(0) // not update
            writeU32(output, lineNo.toLong())
            output.write(body)
        }.toByteArray()

    fun variableNew(name: String, initialValue: Int): ByteArray =
        command(15, ByteArrayOutputStream().also { output ->
            writeString(output, name)
            output.write(1) // ParamType.Int
            output.write(expression(
                operation(
                    type = 1,
                    name = "____arg",
                    operands = listOf(paramInt(initialValue))
                )
            ))
            output.write(0) // global scope
        }.toByteArray())

    fun calcIncrement(name: String, amount: Int): ByteArray =
        command(14, expression(
            operation(
                type = 2,
                name = "____sum",
                operands = listOf(paramVar(name), paramInt(amount))
            ),
            operation(
                type = 1,
                name = name,
                operands = listOf(paramVar("____sum"))
            )
        ))

    fun textInsert(
        rawTpWord: ByteArray,
        stopEventProcessing: Boolean? = null
    ): ByteArray =
        command(20, ByteArrayOutputStream().also { output ->
            writeU32(output, rawTpWord.size.toLong())
            output.write(rawTpWord)
            output.write(expression()) // Target
            output.write(expression()) // Hist
            output.write(expression(
                operation(0, "____arg", listOf(paramFlag(true)))
            )) // Wait
            if (stopEventProcessing != null) {
                output.write(expression(
                    operation(0, "____arg", listOf(paramFlag(stopEventProcessing)))
                )) // StopEvent (LSB version > 106)
            }
        }.toByteArray())

    fun emptyString(): ByteArray = byteArrayOf(0, 0, 0, 0)

    fun callPage(
        page: String,
        labelIndex: Int,
        resultVariable: String,
        condition: Boolean = true
    ): ByteArray = command(5, ByteArrayOutputStream().also { output ->
        writeString(output, page)
        writeU32(output, labelIndex.toLong())
        writeString(output, resultVariable)
        output.write(expression(
            operation(0, "____arg", listOf(paramFlag(condition)))
        ))
        writeU32(output, 0) // zero call arguments
    }.toByteArray())

    fun wait(
        milliseconds: Int,
        shouldWait: Boolean,
        stopEventProcessing: Boolean
    ): ByteArray = command(7, expression(
        operation(0, "____arg", listOf(paramFlag(shouldWait)))
    ) + expression(
        operation(0, "____arg", listOf(paramInt(milliseconds)))
    ) + expression(
        operation(0, "____arg", listOf(paramFlag(stopEventProcessing)))
    ))

    fun exitWhenTrue(): ByteArray = command(
        6,
        expression(operation(0, "____arg", listOf(paramFlag(true))))
    )

    fun expression(vararg operations: ByteArray): ByteArray =
        ByteArrayOutputStream().also { output ->
            writeU32(output, operations.size.toLong())
            operations.forEach(output::write)
        }.toByteArray()

    fun operation(type: Int, name: String, operands: List<ByteArray>): ByteArray =
        ByteArrayOutputStream().also { output ->
            output.write(type)
            writeString(output, name)
            writeU32(output, operands.size.toLong())
            operands.forEach(output::write)
        }.toByteArray()

    fun paramInt(value: Int): ByteArray =
        ByteArrayOutputStream().also { output ->
            output.write(1)
            writeU32(output, value.toLong())
        }.toByteArray()

    fun paramFlag(value: Boolean): ByteArray =
        byteArrayOf(3, if (value) 1 else 0)

    fun paramVar(name: String): ByteArray =
        ByteArrayOutputStream().also { output ->
            output.write(0)
            writeString(output, name)
        }.toByteArray()

    private fun writeString(output: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charset.forName("windows-31j"))
        writeU32(output, bytes.size.toLong())
        output.write(bytes)
    }

    private fun writeU32(output: ByteArrayOutputStream, value: Long) {
        repeat(4) { index ->
            output.write(((value ushr (index * 8)) and 0xFFL).toInt())
        }
    }
}
