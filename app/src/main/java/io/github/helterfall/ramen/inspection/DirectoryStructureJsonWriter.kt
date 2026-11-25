package io.github.helterfall.ramen.inspection

import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Writes a compact UTF-8 JSON manifest. The caller retains ownership of the supplied stream. */
class DirectoryStructureJsonWriter {
    fun write(report: DirectoryStructureReport, output: OutputStream) {
        val writer = BufferedWriter(OutputStreamWriter(output, StandardCharsets.UTF_8))
        write(report, writer)
        writer.flush()
    }

    fun write(report: DirectoryStructureReport, writer: Writer) {
        writer.write("{\n")
        writeNumberProperty(writer, "schemaVersion", SCHEMA_VERSION.toLong())
        writer.write(",\n")
        writeStringProperty(writer, "rootName", report.rootName)
        writer.write(",\n")
        writeNumberProperty(writer, "directoryCount", report.directories.size.toLong())
        writer.write(",\n")
        writeNumberProperty(writer, "fileCount", report.files.size.toLong())
        writer.write(",\n")
        writeBooleanProperty(writer, "truncated", report.truncated)
        writer.write(",\n")
        writePropertyName(writer, "warnings")
        writeStringArray(writer, report.warnings)
        writer.write(",\n")
        writePropertyName(writer, "directories")
        writeStringArray(writer, report.directories)
        writer.write(",\n")
        writePropertyName(writer, "files")
        writer.write("[")
        if (report.files.isNotEmpty()) writer.write("\n")
        report.files.forEachIndexed { index, file ->
            writer.write("    {\n")
            writer.write("      ")
            writePropertyName(writer, "relativePath")
            writeQuoted(writer, file.relativePath)
            writer.write(",\n")
            writer.write("      ")
            writePropertyName(writer, "sizeBytes")
            writer.write(file.sizeBytes?.toString() ?: "null")
            writer.write("\n    }")
            if (index != report.files.lastIndex) writer.write(",")
            writer.write("\n")
        }
        writer.write("  ]\n")
        writer.write("}\n")
    }

    private fun writeStringProperty(writer: Writer, name: String, value: String) {
        writer.write("  ")
        writePropertyName(writer, name)
        writeQuoted(writer, value)
    }

    private fun writeNumberProperty(writer: Writer, name: String, value: Long) {
        writer.write("  ")
        writePropertyName(writer, name)
        writer.write(value.toString())
    }

    private fun writeBooleanProperty(writer: Writer, name: String, value: Boolean) {
        writer.write("  ")
        writePropertyName(writer, name)
        writer.write(value.toString())
    }

    private fun writePropertyName(writer: Writer, name: String) {
        writeQuoted(writer, name)
        writer.write(": ")
    }

    private fun writeStringArray(writer: Writer, values: List<String>) {
        writer.write("[")
        values.forEachIndexed { index, value ->
            if (index > 0) writer.write(",")
            writeQuoted(writer, value)
        }
        writer.write("]")
    }

    private fun writeQuoted(writer: Writer, value: String) {
        writer.write(34)
        value.forEach { character ->
            when (character) {
                '"' -> writer.write("\\\"")
                '\\' -> writer.write("\\\\")
                '\b' -> writer.write("\\b")
                '\u000C' -> writer.write("\\f")
                '\n' -> writer.write("\\n")
                '\r' -> writer.write("\\r")
                '\t' -> writer.write("\\t")
                else -> {
                    if (character.code < JSON_MIN_PRINTABLE_CODE) {
                        writer.write("\\u%04x".format(Locale.ROOT, character.code))
                    } else {
                        writer.write(character.toString())
                    }
                }
            }
        }
        writer.write(34)
    }

    companion object {
        const val SCHEMA_VERSION = 1
        private const val JSON_MIN_PRINTABLE_CODE = 0x20
    }
}
