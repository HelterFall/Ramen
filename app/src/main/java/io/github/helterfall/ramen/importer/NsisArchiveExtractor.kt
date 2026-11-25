package io.github.helterfall.ramen.importer

import io.github.helterfall.ramen.storage.StorageGuardedOutputStream
import io.github.helterfall.ramen.storage.StorageSpaceGuard
import org.tukaani.xz.LZMAInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Pure Kotlin/Java parser for common NSIS 2.x/3.x installers. */
class NsisArchiveExtractor(
    private val maxEntries: Int = 10_000,
    private val maxPathDepth: Int = 64
) {
    init { require(maxEntries in 1..50_000); require(maxPathDepth in 1..128) }

    fun extract(installer: File, destination: File): ExtractionSummary {
        if (!installer.isFile) throw IOException("The NSIS installer is not a regular file.")
        if (destination.exists() && !destination.deleteRecursively()) {
            throw IOException("Could not prepare the NSIS extraction directory.")
        }
        if (!destination.mkdirs() && !destination.isDirectory) {
            throw IOException("Could not create the NSIS extraction directory.")
        }
        val root = destination.canonicalFile
        val rootPath = root.toPath()
        val seen = HashSet<String>()
        var total = 0L
        var count = 0
        try {
            RandomAccessFile(installer, "r").use { file ->
                val fhOffset = findFirstHeader(file)
                val fh = readAt(file, fhOffset, 28)
                val flags = u32(fh, 0)
                val magic = u32(fh, 4)
                if (magic != 0xDEADBEEFL && magic != 0xDEADBEEDL) {
                    throw IOException("Invalid NSIS FirstHeader signature.")
                }
                if (!fh.copyOfRange(8, 20).contentEquals(NSIS_MARKER)) {
                    throw IOException("Invalid NullsoftInst marker.")
                }
                val headerSize = u32(fh, 20)
                val declaredLength = u32(fh, 24)
                if (headerSize !in 68L..(32L * 1024 * 1024) || declaredLength < 28L) {
                    throw IOException("Invalid NSIS header lengths.")
                }
                val dataStart = fhOffset + 28L
                val available = (file.length() - dataStart).coerceAtLeast(0L)
                val following = minOf(available, declaredLength - 28L)
                if (following < 4L || following > MAX_ARCHIVE) {
                    throw IOException("NSIS payload exceeds the supported range.")
                }
                val layout = readHeader(file, dataStart, following, headerSize.toInt(), flags)
                val entriesOffset = u32(layout.header, 20).toInt()
                val entriesCount = i32(layout.header, 24)
                val stringsOffset = u32(layout.header, 28).toInt()
                val stringsLength = i32(layout.header, 32)
                if (entriesCount !in 0..maxEntries ||
                    entriesOffset < 68 || entriesOffset.toLong() + entriesCount.toLong() * 28L > layout.header.size
                ) throw IOException("NSIS Entries block is invalid.")
                if (stringsLength <= 0 || stringsOffset < 68 ||
                    stringsOffset.toLong() + stringsLength.toLong() > layout.header.size
                ) throw IOException("NSIS Strings block is invalid.")
                val unicode = layout.header[stringsOffset] == 0.toByte() &&
                    layout.header[stringsOffset + 1] == 0.toByte()
                val table = StringTable(stringsOffset, stringsLength, unicode)
                var outDir = ""
                repeat(entriesCount) { index ->
                    val p = entriesOffset + index * 28
                    val opcode = i32(layout.header, p)
                    val a = i32(layout.header, p + 4)
                    val b = i32(layout.header, p + 8)
                    val c = i32(layout.header, p + 12)
                    when (opcode) {
                        11 -> if (b != 0) outDir = clean(readNsisString(layout.header, table, a))
                        20 -> {
                            val raw = readNsisString(layout.header, table, b)
                            val rooted = raw.firstOrNull() == '$' || raw.firstOrNull() == 92.toChar() ||
                                DRIVE.containsMatchIn(raw)
                            val name = clean(raw)
                            val combined = if (rooted || outDir.isBlank()) name else "$outDir/$name"
                            val path = normalizeNsisEntryName(combined, maxPathDepth)
                            if (!seen.add(path.lowercase(Locale.ROOT))) {
                                throw IOException("Duplicate NSIS path: $path")
                            }
                            if (c < 0) throw IOException("Negative NSIS file-data offset.")
                            val bytes = filePayload(file, layout, c.toLong())
                            total = Math.addExact(total, bytes.size.toLong())
                            if (total > MAX_TOTAL) throw IOException("NSIS extraction exceeds total size limit.")
                            val target = File(root, path).canonicalFile
                            if (target == root || !target.toPath().startsWith(rootPath)) {
                                throw IOException("NSIS path escapes its extraction directory.")
                            }
                            val parent = target.parentFile ?: throw IOException("NSIS output has no parent.")
                            if (!parent.mkdirs() && !parent.isDirectory) throw IOException("Cannot create NSIS output folder.")
                            StorageGuardedOutputStream(
                                FileOutputStream(target, false), StorageSpaceGuard(root)
                            ).use { it.write(bytes) }
                            count++
                        }
                    }
                }
            }
            if (count == 0) throw IOException("NSIS contains no extractable files.")
            return ExtractionSummary(fileCount = count, totalBytes = total)
        } catch (e: Exception) {
            destination.deleteRecursively()
            if (e is IOException) throw e
            throw IOException("NSIS extraction failed: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    private data class StringTable(val offset: Int, val length: Int, val unicode: Boolean)
    private data class Layout(val header: ByteArray, val dataOffset: Long, val solid: ByteArray?)

    private fun readHeader(file: RandomAccessFile, start: Long, available: Long, expected: Int, flags: Long): Layout {
        val prefix = u32(readAt(file, start, 4), 0)
        val compressed = prefix and 0x80000000L != 0L
        val size = (prefix and 0x7FFFFFFFL).toInt()
        val consumed = 4L + size
        if (!compressed && size == expected && consumed <= available) {
            return Layout(readAt(file, start + 4, size), start + consumed, null)
        }
        if (compressed && size in 1..MAX_BLOCK && consumed <= available) {
            val block = readAt(file, start + 4, size)
            val decoded = runCatching { decodeAuto(block, expected, MAX_HEADER) }.getOrNull()
            if (decoded != null && decoded.size == expected) return Layout(decoded, start + consumed, null)
        }
        // Solid stream: [header-size][header][file records...] after decompression.
        val crcSize = if (flags and 0x04L == 0L) 4L else 0L
        val solidSize = (available - crcSize).coerceAtLeast(0L)
        if (solidSize !in 1..MAX_ARCHIVE) throw IOException("Invalid NSIS solid stream size.")
        val decoded = decodeAuto(readAt(file, start, solidSize.toInt()), -1, MAX_SOLID)
        if (decoded.size < expected + 4 || u32(decoded, 0) != expected.toLong()) {
            throw IOException("NSIS solid stream does not contain its declared header.")
        }
        return Layout(decoded.copyOfRange(4, expected + 4), 0L, decoded.copyOfRange(expected + 4, decoded.size))
    }

    private fun readNsisString(header: ByteArray, table: StringTable, index: Int): String {
        if (index < 0) throw IOException("NSIS language-dependent strings are unsupported.")
        val width = if (table.unicode) 2 else 1
        val relative = index.toLong() * width
        if (relative >= table.length) throw IOException("NSIS string offset is outside the Strings block.")
        val begin = table.offset + relative.toInt()
        val end = table.offset + table.length
        var cursor = begin
        if (table.unicode) {
            while (cursor + 1 < end &&
                !(header[cursor] == 0.toByte() && header[cursor + 1] == 0.toByte())
            ) cursor += 2
            if (cursor + 1 >= end) throw IOException("Unterminated NSIS Unicode string.")
            return String(header, begin, cursor - begin, Charsets.UTF_16LE)
        }
        while (cursor < end && header[cursor] != 0.toByte()) cursor++
        if (cursor >= end) throw IOException("Unterminated NSIS ANSI string.")
        return String(header, begin, cursor - begin, WINDOWS_1252)
    }

    private fun clean(raw: String): String {
        var value = raw.replace(92.toChar(), '/').trim().replace(DRIVE, "")
        ROOT_VAR.find(value)?.let { value = value.substring(it.range.last + 1) }
        return value.trimStart('/')
    }

    private fun filePayload(file: RandomAccessFile, layout: Layout, offset: Long): ByteArray {
        val p = if (layout.solid != null) offset else layout.dataOffset + offset
        val prefix = if (layout.solid != null) readArray(layout.solid, p, 4) else readAt(file, p, 4)
        val value = u32(prefix, 0)
        val compressed = value and 0x80000000L != 0L
        val size = (value and 0x7FFFFFFFL).toInt()
        if (size > MAX_BLOCK) throw IOException("NSIS file record is too large.")
        val data = if (layout.solid != null) readArray(layout.solid, p + 4, size)
        else readAt(file, p + 4, size)
        return if (compressed) decodeAuto(data, -1, MAX_FILE) else data
    }

    private fun decodeAuto(input: ByteArray, expected: Int, limit: Int): ByteArray {
        if (input.isEmpty()) throw IOException("Empty NSIS compressed block.")
        if (input[0] == 0x31.toByte() ||
            (input.size >= 3 && input[0] == 'B'.code.toByte() &&
                input[1] == 'Z'.code.toByte() && input[2] == 'h'.code.toByte())
        ) throw IOException("NSIS BZip2 compression is unsupported by the pure-Java extractor.")
        val lzmaFirst = (input[0].toInt() and 255) == 0x5D
        var failure: Exception? = null
        for (lzma in if (lzmaFirst) listOf(true, false) else listOf(false, true)) {
            try {
                if (lzma) return inflateLzma(input, expected, limit)
                val decoded = inflateNsisDeflate(input, limit)
                if (expected >= 0 && decoded.size != expected) throw IOException("Deflate size mismatch.")
                return decoded
            } catch (e: Exception) { failure = e }
        }
        throw IOException("NSIS LZMA/Deflate failed: ${failure?.message}", failure)
    }

    private fun inflateLzma(input: ByteArray, expected: Int, limit: Int): ByteArray {
        if (input.size < 5) throw IOException("Truncated NSIS LZMA properties.")
        val props = input[0].toInt() and 255
        if (props > 224) throw IOException("Invalid NSIS LZMA properties.")
        val dict = u32(input, 1)
        if (dict > MAX_DICT) throw IOException("NSIS LZMA dictionary exceeds the memory limit.")
        val declared = if (expected >= 0) expected.toLong() else -1L
        val output = ByteArrayOutputStream(minOf(limit, input.size * 3).coerceAtLeast(32))
        LZMAInputStream(ByteArrayInputStream(input, 5, input.size - 5), declared, props.toByte(), dict.toInt()).use { stream ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                if (output.size().toLong() + n > limit.toLong()) throw IOException("NSIS LZMA output limit exceeded.")
                output.write(buffer, 0, n)
            }
        }
        return output.toByteArray()
    }

    private fun findFirstHeader(file: RandomAccessFile): Long {
        val signatures = listOf(FH_SIG + NSIS_MARKER, FH_SIG_ALT + NSIS_MARKER)
        val overlap = signatures.maxOf { it.size } - 1
        var pos = 0L
        var carry = ByteArray(0)
        while (pos < file.length()) {
            val n = minOf(64L * 1024, file.length() - pos).toInt()
            val merged = carry + readAt(file, pos, n)
            for (sig in signatures) {
                val index = indexOf(merged, sig)
                if (index >= 0) {
                    val candidate = pos - carry.size + index - 4L
                    if (candidate >= 0 && candidate + 28 <= file.length()) return candidate
                }
            }
            carry = if (merged.size > overlap) merged.copyOfRange(merged.size - overlap, merged.size) else merged
            pos += n
        }
        throw IOException("NullsoftInst FirstHeader was not found.")
    }

    private fun readAt(file: RandomAccessFile, offset: Long, size: Int): ByteArray {
        if (offset < 0 || size < 0 || offset > file.length() - size.toLong()) {
            throw IOException("NSIS data range is outside the installer.")
        }
        return ByteArray(size).also { file.seek(offset); file.readFully(it) }
    }
    private fun readArray(bytes: ByteArray, offset: Long, size: Int): ByteArray {
        if (offset < 0 || size < 0 || offset > bytes.size.toLong() - size) {
            throw IOException("NSIS data range is outside the decompressed stream.")
        }
        val p = offset.toInt()
        return bytes.copyOfRange(p, p + size)
    }
    private fun u32(b: ByteArray, p: Int): Long {
        if (p < 0 || p > b.size - 4) throw IOException("Truncated NSIS dword.")
        return (b[p].toLong() and 255L) or ((b[p+1].toLong() and 255L) shl 8) or
            ((b[p+2].toLong() and 255L) shl 16) or ((b[p+3].toLong() and 255L) shl 24)
    }
    private fun i32(b: ByteArray, p: Int): Int = u32(b, p).toInt()
    private fun indexOf(b: ByteArray, pattern: ByteArray): Int {
        if (pattern.isEmpty() || pattern.size > b.size) return -1
        outer@ for (i in 0..b.size-pattern.size) {
            for (j in pattern.indices) if (b[i+j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }
    companion object {
        private const val MAX_HEADER=32*1024*1024
        private const val MAX_BLOCK=256*1024*1024
        private const val MAX_ARCHIVE=512L*1024*1024
        private const val MAX_SOLID=512*1024*1024
        private const val MAX_FILE=256*1024*1024
        private const val MAX_TOTAL=1024L*1024*1024
        private const val MAX_DICT=64L*1024*1024
        private val WINDOWS_1252: Charset = Charset.forName("windows-1252")
        private val DRIVE=Regex("^[A-Za-z]:")
        private val ROOT_VAR=Regex("^\\$[A-Za-z_][A-Za-z0-9_]*")
        private val FH_SIG=byteArrayOf(0xEF.toByte(),0xBE.toByte(),0xAD.toByte(),0xDE.toByte())
        private val FH_SIG_ALT=byteArrayOf(0xED.toByte(),0xBE.toByte(),0xAD.toByte(),0xDE.toByte())
        private val NSIS_MARKER="NullsoftInst".toByteArray(Charsets.US_ASCII)
    }
}

internal fun normalizeNsisEntryName(rawName: String, maxPathDepth: Int = 64): String {
    if (rawName.isBlank() || rawName.length > 4096) throw IOException("Invalid NSIS path.")
    if (rawName.firstOrNull() == '/' || rawName.firstOrNull() == 92.toChar() ||
        rawName.any { it.code == 0 || it.code < 0x20 || it.code == 0x7F }
    ) throw IOException("Absolute paths and control characters are not allowed.")
    val normalized=rawName.replace(92.toChar(),'/')
    if (Regex("^[A-Za-z]:").containsMatchIn(normalized)) throw IOException("Drive paths are not allowed.")
    val parts=normalized.split('/')
    if (parts.any { it==".." }) throw IOException("Path traversal is not allowed.")
    val safe=parts.filter { it.isNotEmpty() && it!="." }
    if (safe.isEmpty() || safe.size>maxPathDepth) throw IOException("Invalid/deep NSIS path.")
    return safe.joinToString("/")
}

internal fun inflateNsisDeflate(input: ByteArray, limit: Int): ByteArray {
    require(limit>=0)
    val inflater=Inflater(true)
    val output=ByteArrayOutputStream(minOf(32*1024,limit))
    try {
        inflater.setInput(input)
        val buffer=ByteArray(32*1024)
        while (!inflater.finished()) {
            val n=try { inflater.inflate(buffer) }
            catch(e:DataFormatException) { throw IOException("Invalid NSIS Deflate stream.",e) }
            if(n>0) {
                if(output.size().toLong()+n>limit.toLong()) throw IOException("NSIS Deflate output limit exceeded.")
                output.write(buffer,0,n)
            } else if(inflater.needsDictionary()) throw IOException("NSIS Deflate dictionary required.")
            else if(inflater.needsInput()) break
            else throw IOException("NSIS Deflate decoder made no progress.")
        }
        if(!inflater.finished()) throw IOException("NSIS Deflate stream ended prematurely.")
        return output.toByteArray()
    } finally { inflater.end() }
}
