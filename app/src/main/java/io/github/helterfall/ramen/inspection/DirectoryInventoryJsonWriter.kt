package io.github.helterfall.ramen.inspection

import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Writes a deterministic UTF-8 JSON representation of a directory inventory.
 *
 * The schema is versioned independently from the app. The writer streams entries to the supplied
 * output rather than building one large JSON string. It flushes but never closes the caller-owned
 * OutputStream or Writer.
 */
class DirectoryInventoryJsonWriter {
    fun write(report: DirectoryInventoryReport, output: OutputStream) {
        val writer = BufferedWriter(OutputStreamWriter(output, StandardCharsets.UTF_8))
        write(report, writer)
        writer.flush()
    }

    fun write(report: DirectoryInventoryReport, writer: Writer) {
        writer.write("{\n")
        writePropertyName(writer, "schemaVersion")
        writer.write("$SCHEMA_VERSION,\n")
        writeStringProperty(writer, "rootName", report.rootName)
        writer.write(",\n")
        writeNumberProperty(writer, "discoveredEntries", report.discoveredEntries.toLong())
        writer.write(",\n")
        writeNumberProperty(writer, "totalBytesObserved", report.totalBytesObserved)
        writer.write(",\n")
        writeNumberProperty(writer, "totalBytesHashed", report.totalBytesHashed)
        writer.write(",\n")
        writeBooleanProperty(writer, "truncated", report.truncated)
        writer.write(",\n")
        writeStringArrayProperty(writer, "warnings", report.warnings)
        writer.write(",\n")
        writePropertyName(writer, "entries")
        writer.write("[")
        if (report.entries.isNotEmpty()) writer.write("\n")
        report.entries.forEachIndexed { index, entry ->
            writer.write("    ")
            writeEntry(writer, entry, indent = "    ")
            if (index != report.entries.lastIndex) writer.write(",")
            writer.write("\n")
        }
        writer.write("  ]\n")
        writer.write("}\n")
    }

    private fun writeEntry(
        writer: Writer,
        entry: DirectoryInventoryEntry,
        indent: String
    ) {
        writer.write("{\n")
        writer.write("$indent  ")
        writeStringProperty(writer, "relativePath", entry.relativePath)
        writer.write(",\n")
        writer.write("$indent  ")
        writeNumberProperty(writer, "sizeBytes", entry.sizeBytes)
        writer.write(",\n")
        writer.write("$indent  ")
        writeNullableStringProperty(writer, "sha256", entry.sha256Hex)
        writer.write(",\n")
        writer.write("$indent  ")
        writePropertyName(writer, "artifact")
        writeArtifact(writer, entry.artifactReport, indent = "$indent  ")
        writer.write(",\n")
        writer.write("$indent  ")
        writeStringArrayProperty(writer, "warnings", entry.warnings)
        writer.write("\n$indent}")
    }

    private fun writeArtifact(
        writer: Writer,
        artifact: BinaryArtifactReport?,
        indent: String
    ) {
        if (artifact == null) {
            writer.write("null")
            return
        }

        writer.write("{\n")
        writer.write("$indent  ")
        writeStringProperty(writer, "fileName", artifact.fileName)
        writer.write(",\n")
        writer.write("$indent  ")
        writeNumberProperty(writer, "fileSizeBytes", artifact.fileSizeBytes)
        writer.write(",\n")
        writer.write("$indent  ")
        writeNullableStringProperty(writer, "extensionHint", artifact.extensionHint)
        writer.write(",\n")
        writer.write("$indent  ")
        writeStringProperty(writer, "kind", artifact.kind.name)
        writer.write(",\n")
        writer.write("$indent  ")
        writeNullableStringProperty(writer, "signature", artifact.signature)
        writer.write(",\n")
        writer.write("$indent  ")
        writeStringProperty(writer, "evidence", artifact.evidence)
        writer.write(",\n")
        writer.write("$indent  ")
        writePropertyName(writer, "peMetadata")
        writePeMetadata(writer, artifact.peMetadata, "$indent  ")
        writer.write(",\n")
        writer.write("$indent  ")
        writeStringArrayProperty(writer, "warnings", artifact.warnings)
        writer.write("\n$indent}")
    }

    private fun writePeMetadata(
        writer: Writer,
        metadata: PeImageMetadata?,
        indent: String
    ) {
        if (metadata == null) {
            writer.write("null")
            return
        }

        writer.write("{\n")
        writer.write("$indent  ")
        writeNumberProperty(writer, "machineCode", metadata.machineCode.toLong())
        writer.write(",\n")
        writer.write("$indent  ")
        writeStringProperty(writer, "machineLabel", metadata.machineLabel)
        writer.write(",\n")
        writer.write("$indent  ")
        writeNullableNumberProperty(writer, "optionalHeaderMagic", metadata.optionalHeaderMagic?.toLong())
        writer.write(",\n")
        writer.write("$indent  ")
        writeNullableStringProperty(writer, "optionalHeaderLabel", metadata.optionalHeaderLabel)
        writer.write(",\n")
        writer.write("$indent  ")
        writeNumberProperty(writer, "sectionCount", metadata.sectionCount.toLong())
        writer.write(",\n")
        writer.write("$indent  ")
        writeBooleanProperty(writer, "isDll", metadata.isDll)
        writer.write(",\n")
        writer.write("$indent  ")
        writeStringArrayProperty(writer, "importedLibraries", metadata.importedLibraries)
        writer.write(",\n")
        writer.write("$indent  ")
        writeNullableNumberProperty(writer, "importDirectoryRva", metadata.importDirectoryRva)
        writer.write(",\n")
        writer.write("$indent  ")
        writeNullableNumberProperty(writer, "importDirectorySize", metadata.importDirectorySize)
        writer.write("\n$indent}")
    }

    private fun writeStringProperty(writer: Writer, name: String, value: String) {
        writePropertyName(writer, name)
        writeQuoted(writer, value)
    }

    private fun writeNullableStringProperty(writer: Writer, name: String, value: String?) {
        writePropertyName(writer, name)
        if (value == null) writer.write("null") else writeQuoted(writer, value)
    }

    private fun writeNumberProperty(writer: Writer, name: String, value: Long) {
        writePropertyName(writer, name)
        writer.write(value.toString())
    }

    private fun writeNullableNumberProperty(writer: Writer, name: String, value: Long?) {
        writePropertyName(writer, name)
        writer.write(value?.toString() ?: "null")
    }

    private fun writeBooleanProperty(writer: Writer, name: String, value: Boolean) {
        writePropertyName(writer, name)
        writer.write(value.toString())
    }

    private fun writeStringArrayProperty(writer: Writer, name: String, values: List<String>) {
        writePropertyName(writer, name)
        writer.write("[")
        values.forEachIndexed { index, value ->
            if (index > 0) writer.write(",")
            writeQuoted(writer, value)
        }
        writer.write("]")
    }

    private fun writePropertyName(writer: Writer, name: String) {
        writeQuoted(writer, name)
        writer.write(": ")
    }

    private fun writeQuoted(writer: Writer, value: String) {
        writer.write(""")
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
                        writer.write(
                            "\\u%04x".format(Locale.ROOT, character.code)
                        )
                    } else {
                        writer.write(character.toString())
                    }
                }
            }
        }
        writer.write(""")
    }

    companion object {
        const val SCHEMA_VERSION = 1
        private const val JSON_MIN_PRINTABLE_CODE = 0x20
    }
}
