package io.github.helterfall.ramen.parser

import java.io.IOException

data class Hsp3LibraryInfo(val index: Int, val flag: Int, val nameIndex: Int, val name: String, val classId: String)
data class Hsp3ExternalFunctionInfo(
    val index: Int, val libraryIndex: Int, val subId: Int, val parameterIndex: Int,
    val parameterCount: Int, val nameIndex: Int, val name: String, val libraryName: String,
    val stackBytes: Int, val flags: Int
)
data class Hsp3HpiPluginInfo(val index: Int, val flag: Int, val option: Int, val libraryName: String, val functionName: String)

/** Read-only metadata inventory. Native libraries are never loaded or executed. */
data class Hsp3ExternalLibraryManifest(
    val libraries: List<Hsp3LibraryInfo>,
    val functions: List<Hsp3ExternalFunctionInfo>,
    val plugins: List<Hsp3HpiPluginInfo>,
    val moduleParameterCount: Int,
    val functionInfo2SizeBytes: Int,
    val variablePluginTypeCount: Int,
    val diagnostics: List<String>
) {
    fun functionAt(index: Int): Hsp3ExternalFunctionInfo? = functions.getOrNull(index)

    fun diagnosticLabel(maxLibraries: Int = 12, maxFunctionsPerLibrary: Int = 20, maxPlugins: Int = 12): String {
        if (libraries.isEmpty() && functions.isEmpty() && plugins.isEmpty() &&
            moduleParameterCount == 0 && functionInfo2SizeBytes == 0 && variablePluginTypeCount == 0
        ) return "External imports: none."

        val libraryDetails = libraries.take(maxLibraries).map { library ->
            val names = functions.filter { it.libraryIndex == library.index }.map { it.name }.distinct()
            val shown = names.take(maxFunctionsPerLibrary)
            val overflow = if (names.size > shown.size) ", +${names.size - shown.size}" else ""
            library.name + if (names.isEmpty()) "" else " [${shown.joinToString(", ")}$overflow]"
        }.toMutableList()
        if (libraries.size > maxLibraries) libraryDetails += "+${libraries.size - maxLibraries} more DLLs"
        val dllFunctions = functions.filter { it.libraryIndex >= 0 }
        val moduleFunctions = functions.size - dllFunctions.size
        val hpiDetails = plugins.take(maxPlugins).map {
            "${it.libraryName}!${it.functionName} (flag=${it.flag})"
        }.toMutableList()
        if (plugins.size > maxPlugins) hpiDetails += "+${plugins.size - maxPlugins} more HPI entries"

        return buildString {
            append("External imports (metadata only; native code is not loaded): ")
            if (libraryDetails.isNotEmpty()) {
                append("LIBDAT/FINFO: ").append(libraryDetails.joinToString("; ")).append(". ")
            } else if (dllFunctions.isNotEmpty()) {
                append("FINFO imports: ")
                append(dllFunctions.take(maxLibraries * maxFunctionsPerLibrary)
                    .joinToString("; ") { "${it.libraryName}!${it.name}" })
                append(". ")
            }
            if (hpiDetails.isNotEmpty()) append("HPI: ").append(hpiDetails.joinToString("; ")).append(". ")
            append("DLL functions=${dllFunctions.size}, module functions=$moduleFunctions, ")
            append("MINFO parameters=$moduleParameterCount, FINFO2 bytes=$functionInfo2SizeBytes, ")
            append("HPI variable types=$variablePluginTypeCount. Unmapped calls use a zero-return shim.")
            if (diagnostics.isNotEmpty()) append(" Metadata notes: ").append(diagnostics.take(4).joinToString(" | "))
        }
    }

    companion object {
        val EMPTY = Hsp3ExternalLibraryManifest(emptyList(), emptyList(), emptyList(), 0, 0, 0, emptyList())
        private const val LIBDAT_RECORD_BYTES = 16
        private const val STRUCTDAT_RECORD_BYTES = 28
        private const val STRUCTPRM_RECORD_BYTES = 8
        private const val HPIDAT_RECORD_BYTES = 16
        private const val MAX_METADATA_STRING_BYTES = 4096

        internal fun parse(
            dataSegment: ByteArray, libraryInfo: ByteArray, moduleInfo: ByteArray,
            functionInfo: ByteArray, functionInfo2: ByteArray, hpiData: ByteArray,
            variablePluginTypeCount: Int
        ): Hsp3ExternalLibraryManifest {
            requireTableSize(libraryInfo, LIBDAT_RECORD_BYTES, "LIBDAT")
            requireTableSize(functionInfo, STRUCTDAT_RECORD_BYTES, "FINFO")
            requireTableSize(moduleInfo, STRUCTPRM_RECORD_BYTES, "MINFO")
            requireTableSize(hpiData, HPIDAT_RECORD_BYTES, "HPIDAT")
            val notes = mutableListOf<String>()

            val libraries = libraryInfo.indices.step(LIBDAT_RECORD_BYTES).mapIndexed { index, offset ->
                val nameIndex = readInt32(libraryInfo, offset + 4)
                val classIdIndex = readInt32(libraryInfo, offset + 12)
                Hsp3LibraryInfo(
                    index, readInt32(libraryInfo, offset), nameIndex,
                    readDataString(dataSegment, nameIndex, "LIBDAT[$index].name", notes),
                    if (classIdIndex > 0 && classIdIndex < dataSegment.size)
                        readDataString(dataSegment, classIdIndex, "LIBDAT[$index].classId", notes) else ""
                )
            }
            val functions = functionInfo.indices.step(STRUCTDAT_RECORD_BYTES).mapIndexed { index, offset ->
                val libraryIndex = readInt16(functionInfo, offset)
                val nameIndex = readInt32(functionInfo, offset + 12)
                Hsp3ExternalFunctionInfo(
                    index, libraryIndex, readInt16(functionInfo, offset + 2),
                    readInt32(functionInfo, offset + 4), readInt32(functionInfo, offset + 8),
                    nameIndex, readDataString(dataSegment, nameIndex, "FINFO[$index].name", notes),
                    libraries.getOrNull(libraryIndex)?.name ?: if (libraryIndex < 0) "<HSP module>" else "<LIBDAT #$libraryIndex>",
                    readInt32(functionInfo, offset + 16), readInt32(functionInfo, offset + 24)
                )
            }
            val plugins = hpiData.indices.step(HPIDAT_RECORD_BYTES).mapIndexed { index, offset ->
                Hsp3HpiPluginInfo(
                    index, readInt16(hpiData, offset), readInt16(hpiData, offset + 2),
                    readDataString(dataSegment, readInt32(hpiData, offset + 4), "HPIDAT[$index].library", notes),
                    readDataString(dataSegment, readInt32(hpiData, offset + 8), "HPIDAT[$index].function", notes)
                )
            }
            if (functionInfo2.isNotEmpty()) notes += "FINFO2 is retained as opaque metadata (${functionInfo2.size} bytes)."
            return Hsp3ExternalLibraryManifest(
                libraries, functions, plugins, moduleInfo.size / STRUCTPRM_RECORD_BYTES,
                functionInfo2.size, variablePluginTypeCount, notes
            )
        }

        private fun requireTableSize(bytes: ByteArray, recordSize: Int, label: String) {
            if (bytes.size % recordSize != 0) throw IOException(
                "The HSP3 $label table is truncated: ${bytes.size} bytes is not a multiple of $recordSize."
            )
        }

        private fun readDataString(data: ByteArray, offset: Int, label: String, notes: MutableList<String>): String {
            if (offset < 0) return "<dynamic>"
            if (offset >= data.size) {
                notes += "$label has an out-of-range DS offset $offset."
                return "<invalid DS offset $offset>"
            }
            val limit = minOf(data.size, offset + MAX_METADATA_STRING_BYTES)
            var end = offset
            while (end < limit && data[end] != 0.toByte()) end++
            if (end == limit && end < data.size) notes += "$label exceeds the metadata string bound."
            return Hsp3StringEncoding.decode(data, offset, end - offset).trim().ifEmpty { "<empty>" }
        }

        private fun readInt16(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)).toShort().toInt()

        private fun readInt32(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16) or (bytes[offset + 3].toInt() shl 24)
    }
}
