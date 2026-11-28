package io.github.helterfall.ramen.inspection

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ConcurrentHashMap

enum class DpmxCipherVersion {
    HSP_26_TO_32,
    HSP_33_TO_36
}

data class DpmxCipherKey(
    /** Subtraction seed (seed2x in the HSP reference implementation). */
    val al: Int,
    /** XOR seed (seed1x in the HSP reference implementation). */
    val cl: Int,
    val initialDl: Int = 0,
    val version: DpmxCipherVersion = DpmxCipherVersion.HSP_26_TO_32
) {
    init {
        require(al in 0..0xFF)
        require(cl in 0..0xFF)
        require(initialDl in 0..0xFF)
    }
}

internal fun decryptDpmxDelta(encrypted: Int, key: DpmxCipherKey): Int =
    when (key.version) {
        DpmxCipherVersion.HSP_26_TO_32 ->
            (((encrypted - key.al) and 0xFF) xor key.cl)
        DpmxCipherVersion.HSP_33_TO_36 ->
            (((encrypted xor key.cl) - key.al) and 0xFF)
}

/** Streaming DPMX feedback decryptor; state is carried across input chunks. */
object DpmxDecryptor {
    const val DEFAULT_BUFFER_SIZE = 64 * 1024

    @Throws(IOException::class)
    fun decrypt(
        source: ReadOnlyRandomAccessSource,
        entry: DpmxFileEntry,
        key: DpmxCipherKey,
        output: OutputStream,
        bufferSize: Int = DEFAULT_BUFFER_SIZE
    ) {
        require(bufferSize > 0) { "bufferSize must be positive." }
        val input = DpmxEntryInputStream(source, entry.absoluteOffset, entry.sizeBytes)
        val encrypted = ByteArray(bufferSize)
        val decoded = ByteArray(bufferSize)
        var dl = key.initialDl and 0xFF

        while (true) {
            val count = input.read(encrypted)
            if (count < 0) break
            if (count == 0) continue
            for (index in 0 until count) {
                val enc = encrypted[index].toInt() and 0xFF
                dl = (dl + decryptDpmxDelta(enc, key)) and 0xFF
                decoded[index] = dl.toByte()
            }
            output.write(decoded, 0, count)
        }
    }

    @Throws(IOException::class)
    fun decryptPrefix(
        source: ReadOnlyRandomAccessSource,
        entry: DpmxFileEntry,
        key: DpmxCipherKey,
        length: Int
    ): ByteArray {
        require(length >= 0)
        val count = minOf(length.toLong(), entry.sizeBytes).toInt()
        val prefixEntry = entry.copy(sizeBytes = count.toLong())
        val output = ByteArrayOutputStream(count)
        decrypt(source, prefixEntry, key, output, bufferSize = minOf(DEFAULT_BUFFER_SIZE, maxOf(1, count)))
        return output.toByteArray()
    }

    fun decryptBytes(encrypted: ByteArray, key: DpmxCipherKey): ByteArray {
        val result = ByteArray(encrypted.size)
        var dl = key.initialDl and 0xFF
        for (index in encrypted.indices) {
            val enc = encrypted[index].toInt() and 0xFF
            dl = (dl + decryptDpmxDelta(enc, key)) and 0xFF
            result[index] = dl.toByte()
        }
        return result
    }
}

data class DpmxKeyResolution(
    val key: DpmxCipherKey,
    val method: Method,
    val validation: Validation,
    val deckey: Long? = null,
    val globalSeed1: Int? = null,
    val globalSeed2: Int? = null,
    val detail: String = ""
) {
    enum class Method {
        PE_HSPHED_DECKEY,
        KNOWN_PLAINTEXT_HSP3,
        SIGNATURE_AUTODETECT,
        UNENCRYPTED_ENTRY
    }

    enum class Validation {
        HSP3_BYTECODE_LOADER,
        RESOURCE_SIGNATURE,
        DPM_CHECKSUM,
        KEY_DERIVED_ONLY
    }

    fun diagnosticLabel(): String {
        val params = "AL=0x${key.al.toString(16).padStart(2, '0')} " +
            "CL=0x${key.cl.toString(16).padStart(2, '0')} " +
            "DL=0x${key.initialDl.toString(16).padStart(2, '0')} " +
            "(${key.version})"
        val archiveSeeds = buildList {
            deckey?.let { add("deckey=0x${it.toString(16)}") }
            globalSeed1?.let { add("seed1=0x${it.toString(16).padStart(2, '0')}") }
            globalSeed2?.let { add("seed2=0x${it.toString(16).padStart(2, '0')}") }
        }.joinToString(" ")
        return "${method.name}: $params" +
            if (archiveSeeds.isBlank()) "; validation=${validation.name}" +
                (if (detail.isBlank()) "" else "; $detail")
            else "; $archiveSeeds; validation=${validation.name}" +
                (if (detail.isBlank()) "" else "; $detail")
    }
}

/**
 * Strictly validates the decrypted BMP prefix used for DPMX key auto-detection.
 * BITMAPCOREHEADER stores planes at file offset 22; INFOHEADER variants store
 * it at offset 26. Both layouts are handled explicitly.
 */
internal fun isPlausibleDpmxBmpHeader(bytes: ByteArray, expectedSizeBytes: Long): Boolean {
    if (bytes.size < 26 || expectedSizeBytes < 26L ||
        bytes[0] != 'B'.code.toByte() || bytes[1] != 'M'.code.toByte()
    ) return false

    fun u16(offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    fun u32(offset: Int): Long =
        (bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24)

    if (u32(2) != expectedSizeBytes) return false
    if (bytes[6] != 0.toByte() || bytes[7] != 0.toByte() ||
        bytes[8] != 0.toByte() || bytes[9] != 0.toByte()
    ) return false

    val dibHeaderSize = u32(14)
    if (dibHeaderSize !in SUPPORTED_BMP_DIB_HEADER_SIZES) return false
    val isCoreHeader = dibHeaderSize == 12L
    val planesOffset = if (isCoreHeader) 22 else 26
    val bitsPerPixelOffset = if (isCoreHeader) 24 else 28
    if (bytes.size < bitsPerPixelOffset + 2) return false
    if (u16(planesOffset) != 1) return false
    if (u16(bitsPerPixelOffset) !in SUPPORTED_BMP_BITS_PER_PIXEL) return false

    val pixelOffset = u32(10)
    val headerEnd = 14L + dibHeaderSize
    if (pixelOffset < headerEnd || pixelOffset >= expectedSizeBytes) return false

    val width: Long
    val height: Long
    if (isCoreHeader) {
        width = u16(18).toLong()
        height = u16(20).toLong()
    } else {
        if (bytes.size < 30) return false
        width = u32(18).toInt().toLong()
        val signedHeight = u32(22).toInt()
        if (signedHeight == Int.MIN_VALUE) return false
        height = kotlin.math.abs(signedHeight.toLong())
    }
    return width in 1L..8192L && height in 1L..8192L
}

private val SUPPORTED_BMP_DIB_HEADER_SIZES =
    setOf(12L, 40L, 52L, 56L, 108L, 124L)
private val SUPPORTED_BMP_BITS_PER_PIXEL = setOf(1, 4, 8, 24, 32)

data class DpmxResourceValidationReport(
    val totalEntries: Int,
    val unencryptedEntries: Int,
    val signatureValidatedEntries: Int,
    val keyDerivedEntries: Int,
    val unresolvedEncryptedEntries: Int,
    val archiveChecksumValid: Boolean?
) {
    fun diagnosticLabel(): String =
        "resources: total=$totalEntries, plain=$unencryptedEntries, " +
            "signature-verified=$signatureValidatedEntries, key-derived=$keyDerivedEntries, " +
            "unresolved=$unresolvedEncryptedEntries, DPM-checksum=" +
            when (archiveChecksumValid) {
                true -> "OK"
                false -> "FAILED"
                null -> "not present"
            }
}

/**
 * Resolves embedded HSP DPMX keys from executable metadata first. If the
 * metadata is absent, damaged, or does not yield a valid start.ax, the
 * resolver recovers the cipher parameters from the plaintext HSP3 header.
 *
 * DPM 2.6-3.2 and 3.3-3.6 share the archive/file-key schedule but use
 * different byte decryption order and different file-key constants.
 */
class DpmxKeyResolver {
    private val resolvedByEntry = ConcurrentHashMap<String, DpmxKeyResolution>()
    private val archiveCipherVersions = ConcurrentHashMap<String, DpmxCipherVersion>()

    @Throws(IOException::class)
    fun resolve(
        source: ReadOnlyRandomAccessSource,
        container: DpmxContainer,
        entry: DpmxFileEntry
    ): DpmxKeyResolution? {
        require(entry in container.entries) { "Entry does not belong to this DPMX container." }
        val archiveId = archiveId(container)
        val cacheKey = "$archiveId:${entry.fileKey}:${entry.name.lowercase()}"
        resolvedByEntry[cacheKey]?.let { cached ->
            if (validatesCached(source, entry, cached)) return cached
            resolvedByEntry.remove(cacheKey, cached)
        }

        if (entry.fileKey == 0L) {
            val validation = validateUnencryptedEntry(source, entry) ?: return null
            return DpmxKeyResolution(
                key = DpmxCipherKey(0, 0),
                method = DpmxKeyResolution.Method.UNENCRYPTED_ENTRY,
                validation = validation,
                detail = "fileKey=0; entry is stored without encryption"
            ).also { resolvedByEntry[cacheKey] = it }
        }

        val metadata = container.executableMetadata
        if (metadata != null && metadata.checksumValid && metadata.dpmOffset == container.overlayOffset) {
            val dataSize = source.sizeBytes - metadata.dpmOffset - DPMX_HEADER_SIZE
            if (dataSize >= 0L) {
                val preferredVersion = archiveCipherVersions[archiveId]
                val candidates = if (preferredVersion != null) {
                    listOf(preferredVersion)
                } else {
                    DpmxCipherVersion.values().toList()
                }
                val isAx = isAxEntry(entry)
                for (version in candidates) {
                    val key = deriveEntryKey(metadata.deckey, entry.fileKey, dataSize, version)
                    val validation = validateCandidate(source, entry, key)
                    if (isAx && validation != DpmxKeyResolution.Validation.HSP3_BYTECODE_LOADER) {
                        continue
                    }
                    if (!isAx && validation == null) continue
                    val result = DpmxKeyResolution(
                        key = key,
                        method = DpmxKeyResolution.Method.PE_HSPHED_DECKEY,
                        validation = validation ?: DpmxKeyResolution.Validation.KEY_DERIVED_ONLY,
                        deckey = metadata.deckey,
                        globalSeed1 = deriveGlobalSeeds(metadata.deckey, dataSize).first,
                        globalSeed2 = deriveGlobalSeeds(metadata.deckey, dataSize).second,
                        detail = "HSPHED checksum OK; fileKey=0x${entry.fileKey.toString(16)}; " +
                            "entry=${entry.name}; sumSize=$dataSize"
                    )
                    if (isAx) archiveCipherVersions[archiveId] = version
                    return result.also { resolvedByEntry[cacheKey] = it }
                }

                // A valid archive checksum and an exact HSP metadata record
                // authenticate the global schedule. If this is an unknown
                // resource extension, only reuse a version already proven by start.ax.
                if (!isAx && preferredVersion != null) {
                    val key = deriveEntryKey(metadata.deckey, entry.fileKey, dataSize, preferredVersion)
                    val result = DpmxKeyResolution(
                        key = key,
                        method = DpmxKeyResolution.Method.PE_HSPHED_DECKEY,
                        validation = DpmxKeyResolution.Validation.KEY_DERIVED_ONLY,
                        deckey = metadata.deckey,
                        globalSeed1 = deriveGlobalSeeds(metadata.deckey, dataSize).first,
                        globalSeed2 = deriveGlobalSeeds(metadata.deckey, dataSize).second,
                        detail = "HSPHED checksum OK; cipher version verified by start.ax; " +
                            "fileKey=0x${entry.fileKey.toString(16)}; entry=${entry.name}"
                    )
                    return result.also { resolvedByEntry[cacheKey] = it }
                }
            }
        }

        val isAx = isAxEntry(entry)
        val fallback = if (isAx) {
            recoverStartAxKey(source, entry)
        } else {
            autoDetectResourceKey(source, entry, archiveCipherVersions[archiveId])
        } ?: return null

        if (isAx) archiveCipherVersions[archiveId] = fallback.first.version
        return DpmxKeyResolution(
            key = fallback.first,
            method = if (isAx) DpmxKeyResolution.Method.KNOWN_PLAINTEXT_HSP3
                else DpmxKeyResolution.Method.SIGNATURE_AUTODETECT,
            validation = fallback.second,
            detail = if (isAx) {
                "Recovered from HSP3 magic; full HSPHED/CS/DS/OT/DI accepted by Hsp3BytecodeLoader"
            } else {
                "Recovered from resource signature; fileKey=0x${entry.fileKey.toString(16)}; entry=${entry.name}"
            }
        ).also { resolvedByEntry[cacheKey] = it }
    }

    /**
     * Uses an unpacked HSPHED version to select the matching DPMX cipher family
     * when the encrypted start.ax is not available to identify it directly.
     */
    fun preferHspCipherVersion(container: DpmxContainer, hspVersion: Long) {
        val cipherVersion = if (hspVersion >= 0x00000330L) {
            DpmxCipherVersion.HSP_33_TO_36
        } else {
            DpmxCipherVersion.HSP_26_TO_32
        }
        archiveCipherVersions[archiveId(container)] = cipherVersion
    }

    /**
     * Validates every archive resource key before the game is reported as
     * prepared. Signature checks are exact for common resource formats;
     * unknown extensions are reported as derived-only when the archive
     * metadata and the start.ax-selected cipher version make derivation safe.
     */
    @Throws(IOException::class)
    fun verifyArchiveResources(
        source: ReadOnlyRandomAccessSource,
        container: DpmxContainer
    ): DpmxResourceValidationReport {
        var unencrypted = 0
        var signatureValidated = 0
        var keyDerived = 0
        var unresolved = 0
        val resources = container.entries.filterNot(::isAxEntry)
        for (entry in resources) {
            val resolution = resolve(source, container, entry)
            if (resolution == null) {
                unresolved++
                continue
            }
            if (resolution.method == DpmxKeyResolution.Method.UNENCRYPTED_ENTRY) {
                unencrypted++
                continue
            }
            when (resolution.validation) {
                DpmxKeyResolution.Validation.RESOURCE_SIGNATURE -> {
                    if (resolution.method == DpmxKeyResolution.Method.UNENCRYPTED_ENTRY) {
                        unencrypted++
                    } else {
                        signatureValidated++
                    }
                }
                DpmxKeyResolution.Validation.KEY_DERIVED_ONLY,
                DpmxKeyResolution.Validation.DPM_CHECKSUM -> keyDerived++
                DpmxKeyResolution.Validation.HSP3_BYTECODE_LOADER -> signatureValidated++
            }
        }
        return DpmxResourceValidationReport(
            totalEntries = resources.size,
            unencryptedEntries = unencrypted,
            signatureValidatedEntries = signatureValidated,
            keyDerivedEntries = keyDerived,
            unresolvedEncryptedEntries = unresolved,
            archiveChecksumValid = container.executableMetadata?.checksumValid
        )
    }

    private fun deriveEntryKey(
        deckey: Long,
        fileKey: Long,
        sumSize: Long,
        version: DpmxCipherVersion
    ): DpmxCipherKey {
        val seeds = deriveGlobalSeeds(deckey, sumSize)
        val f1 = fileKey.toInt() and 0xFF
        val f2 = (fileKey.toInt() ushr 8) and 0xFF
        val f3 = (fileKey.toInt() ushr 16) and 0xFF
        val f4 = (fileKey.toInt() ushr 24) and 0xFF
        val constant1 = if (version == DpmxCipherVersion.HSP_33_TO_36) 0x5A else 0x55
        val constant2 = if (version == DpmxCipherVersion.HSP_33_TO_36) 0xA5 else 0xAA
        // dpm_enc1/2 are int expressions in the HSP runtime: do not truncate
        // the addition to one byte before the XOR. The final sum is truncated.
        val fileSeed1 = ((f1 + constant1) xor f3)
        val fileSeed2 = ((f2 + constant2) xor f4)
        return DpmxCipherKey(
            al = (seeds.second + fileSeed2) and 0xFF,
            cl = (seeds.first + fileSeed1) and 0xFF,
            version = version
        )
    }

    private fun deriveGlobalSeeds(deckey: Long, sumSize: Long): Pair<Int, Int> {
        if (deckey == 0xFFFF_FFFFL) return 0xAA to 0x55
        val d1 = deckey.toInt() and 0xFF
        val d2 = (deckey.toInt() ushr 8) and 0xFF
        val d3 = (deckey.toInt() ushr 16) and 0xFF
        val d4 = (deckey.toInt() ushr 24) and 0xFF
        val seed1 = (((d1 * d3 / 3) xor sumSize.toInt()) and 0xFF)
        val seed2 = (((d2 * d4 / 5) xor sumSize.toInt() xor 0xAA) and 0xFF)
        return seed1 to seed2
    }

    private fun validateCandidate(
        source: ReadOnlyRandomAccessSource,
        entry: DpmxFileEntry,
        key: DpmxCipherKey
    ): DpmxKeyResolution.Validation? {
        if (isAxEntry(entry)) {
            val plain = readDecryptedEntry(source, entry, key)
            return if (isValidHspHeader(entry, plain) && loadsWithHsp3BytecodeLoader(plain)) {
                DpmxKeyResolution.Validation.HSP3_BYTECODE_LOADER
            } else null
        }
        val prefixLength = minOf(prefixLengthFor(entry), entry.sizeBytes.toIntSafe())
        if (prefixLength <= 0) return null
        val plain = DpmxDecryptor.decryptPrefix(source, entry, key, prefixLength)
        return if (isPlausibleResource(entry, plain)) {
            DpmxKeyResolution.Validation.RESOURCE_SIGNATURE
        } else null
    }

    private fun validateUnencryptedEntry(
        source: ReadOnlyRandomAccessSource,
        entry: DpmxFileEntry
    ): DpmxKeyResolution.Validation? {
        if (isAxEntry(entry)) {
            val plain = readEntryBytes(source, entry)
            return if (isValidHspHeader(entry, plain) && loadsWithHsp3BytecodeLoader(plain)) {
                DpmxKeyResolution.Validation.HSP3_BYTECODE_LOADER
            } else null
        }
        val prefixLength = minOf(prefixLengthFor(entry), entry.sizeBytes.toIntSafe())
        if (prefixLength <= 0) return null
        val bytes = ByteArray(prefixLength)
        source.readFullyAt(entry.absoluteOffset, bytes)
        return if (isPlausibleResource(entry, bytes)) {
            DpmxKeyResolution.Validation.RESOURCE_SIGNATURE
        } else DpmxKeyResolution.Validation.KEY_DERIVED_ONLY
    }

    private fun validatesCached(
        source: ReadOnlyRandomAccessSource,
        entry: DpmxFileEntry,
        resolution: DpmxKeyResolution
    ): Boolean = when (resolution.method) {
        DpmxKeyResolution.Method.UNENCRYPTED_ENTRY ->
            validateUnencryptedEntry(source, entry) != null
        else ->
            validateCandidate(source, entry, resolution.key) == resolution.validation ||
                resolution.validation == DpmxKeyResolution.Validation.KEY_DERIVED_ONLY
    }

    private fun recoverStartAxKey(
        source: ReadOnlyRandomAccessSource,
        entry: DpmxFileEntry
    ): Pair<DpmxCipherKey, DpmxKeyResolution.Validation>? {
        if (entry.sizeBytes < HSPHED_SIZE_BYTES) return null
        val encrypted = ByteArray(HSPHED_SIZE_BYTES)
        source.readFullyAt(entry.absoluteOffset, encrypted)
        val known = HSP3_MAGIC
        val expectedDeltas = IntArray(3) { index ->
            (known[index + 1] - known[index]) and 0xFF
        }
        val fullLength = entry.sizeBytes.toIntSafe()
        if (fullLength.toLong() != entry.sizeBytes || fullLength > MAX_HSP_VALIDATION_BYTES) return null
        var selectedKey: DpmxCipherKey? = null

        for (version in DpmxCipherVersion.values()) {
            for (al in 0..0xFF) {
                val enc1 = encrypted[1].toInt() and 0xFF
                val cl = when (version) {
                    DpmxCipherVersion.HSP_26_TO_32 ->
                        expectedDeltas[0] xor ((enc1 - al) and 0xFF)
                    DpmxCipherVersion.HSP_33_TO_36 ->
                        enc1 xor ((expectedDeltas[0] + al) and 0xFF)
                }
                var matches = true
                for (index in 1..2) {
                    val enc = encrypted[index + 1].toInt() and 0xFF
                    val delta = when (version) {
                        DpmxCipherVersion.HSP_26_TO_32 ->
                            (((enc - al) and 0xFF) xor cl)
                        DpmxCipherVersion.HSP_33_TO_36 ->
                            (((enc xor cl) - al) and 0xFF)
                    }
                    if (delta != expectedDeltas[index]) {
                        matches = false
                        break
                    }
                }
                if (!matches) continue
                val enc0 = encrypted[0].toInt() and 0xFF
                val firstDelta = when (version) {
                    DpmxCipherVersion.HSP_26_TO_32 ->
                        (((enc0 - al) and 0xFF) xor cl)
                    DpmxCipherVersion.HSP_33_TO_36 ->
                        (((enc0 xor cl) - al) and 0xFF)
                }
                val initialDl = (known[0] - firstDelta) and 0xFF
                val key = canonicalizeSymmetricKey(DpmxCipherKey(al, cl, initialDl, version))
                val headerPlain = DpmxDecryptor.decryptBytes(encrypted, key)
                if (!isValidHspHeader(entry, headerPlain)) continue
                val fullPlain = DpmxDecryptor.decryptPrefix(source, entry, key, fullLength)
                if (!loadsWithHsp3BytecodeLoader(fullPlain)) continue
                if (selectedKey != null && selectedKey != key) return null
                selectedKey = key
            }
        }
        return selectedKey?.let {
            it to DpmxKeyResolution.Validation.HSP3_BYTECODE_LOADER
        }
    }

    private fun autoDetectResourceKey(
        source: ReadOnlyRandomAccessSource,
        entry: DpmxFileEntry,
        preferredVersion: DpmxCipherVersion?
    ): Pair<DpmxCipherKey, DpmxKeyResolution.Validation>? {
        val extension = entry.name.substringAfterLast('.', "").lowercase()
        val expected = expectedFirstByte(entry)
        val sampleLength = minOf(prefixLengthFor(entry), entry.sizeBytes.toIntSafe())
        if (sampleLength < 3) return null
        val encrypted = ByteArray(sampleLength)
        source.readFullyAt(entry.absoluteOffset, encrypted)
        val versions = preferredVersion?.let(::listOf) ?: DpmxCipherVersion.values().toList()
        var selected: DpmxCipherKey? = null
        var selectedPlain: ByteArray? = null
        for (version in versions) {
            if (expected == null && extension in TEXT_RESOURCE_EXTENSIONS) {
                for (al in 0..0xFF) {
                    for (cl in 0..0xFF) {
                        val key = DpmxCipherKey(al, cl, version = version)
                        val plain = DpmxDecryptor.decryptBytes(encrypted, key)
                        if (!isPrintableShiftJis(plain)) continue
                        if (selectedPlain != null && !selectedPlain.contentEquals(plain)) return null
                        if (selected == null || compareKeys(key, selected) < 0) {
                            selected = canonicalizeSymmetricKey(key)
                            selectedPlain = plain
                        }
                    }
                }
            } else if (expected != null) {
                for (al in 0..0xFF) {
                    val first = encrypted[0].toInt() and 0xFF
                    val cl = when (version) {
                        DpmxCipherVersion.HSP_26_TO_32 ->
                            (((first - al) and 0xFF) xor expected)
                        DpmxCipherVersion.HSP_33_TO_36 ->
                            (first xor ((expected + al) and 0xFF))
                    }
                    val key = DpmxCipherKey(al, cl, version = version)
                    val plain = DpmxDecryptor.decryptBytes(encrypted, key)
                    if (!isPlausibleResource(entry, plain)) continue
                    if (selectedPlain != null && !selectedPlain.contentEquals(plain)) return null
                    if (selected == null || compareKeys(key, selected) < 0) {
                        selected = canonicalizeSymmetricKey(key)
                        selectedPlain = plain
                    }
                }
            } else {
                return null
            }
        }
        return selected?.let {
            it to DpmxKeyResolution.Validation.RESOURCE_SIGNATURE
        }
    }

    private fun readDecryptedEntry(
        source: ReadOnlyRandomAccessSource,
        entry: DpmxFileEntry,
        key: DpmxCipherKey
    ): ByteArray {
        val length = entry.sizeBytes.toIntSafe()
        if (length.toLong() != entry.sizeBytes || length > MAX_HSP_VALIDATION_BYTES) {
            throw IOException("The HSP3 start.ax exceeds the resolver validation bound.")
        }
        return DpmxDecryptor.decryptPrefix(source, entry, key, length)
    }

    private fun readEntryBytes(
        source: ReadOnlyRandomAccessSource,
        entry: DpmxFileEntry
    ): ByteArray {
        val length = entry.sizeBytes.toIntSafe()
        if (length.toLong() != entry.sizeBytes || length > MAX_HSP_VALIDATION_BYTES) {
            throw IOException("The DPMX entry exceeds the resolver validation bound.")
        }
        return ByteArray(length).also { source.readFullyAt(entry.absoluteOffset, it) }
    }

    private fun loadsWithHsp3BytecodeLoader(bytes: ByteArray): Boolean = try {
        io.github.helterfall.ramen.parser.Hsp3BytecodeLoader().load(ByteArraySource(bytes))
        true
    } catch (_: Exception) {
        false
    }

    private data class SegmentRange(val offset: Long, val size: Long)

    private fun isValidHspHeader(entry: DpmxFileEntry, bytes: ByteArray): Boolean {
        if (bytes.size < HSPHED_SIZE_BYTES || !bytes.copyOfRange(0, 4).contentEquals(HSP3_MAGIC)) {
            return false
        }
        val version = readUInt32(bytes, 4)
        if (version !in HSP3_VERSION_MIN..HSP3_VERSION_MAX) return false
        val allSize = readUInt32(bytes, 12)
        if (allSize < HSPHED_SIZE_BYTES || allSize > entry.sizeBytes) return false
        if (readUInt32(bytes, 8) > MAX_HSP3_VARIABLES) return false
        val ranges = listOf(
            SegmentRange(readUInt32(bytes, 16), readUInt32(bytes, 20)),
            SegmentRange(readUInt32(bytes, 24), readUInt32(bytes, 28)),
            SegmentRange(readUInt32(bytes, 32), readUInt32(bytes, 36)),
            SegmentRange(readUInt32(bytes, 40), readUInt32(bytes, 44))
        )
        if (ranges.first().size == 0L || ranges.first().size % 2L != 0L) return false
        if (ranges.any { range ->
                range.size == 0L && range.offset > allSize ||
                    range.size > 0L && (range.offset < HSPHED_SIZE_BYTES ||
                        range.offset > allSize || range.size > allSize - range.offset)
            }
        ) return false
        for (left in ranges.indices) {
            for (right in left + 1 until ranges.size) {
                val a = ranges[left]
                val b = ranges[right]
                if (a.size > 0L && b.size > 0L &&
                    a.offset < b.offset + b.size && b.offset < a.offset + a.size
                ) return false
            }
        }
        return true
    }

    private fun isAxEntry(entry: DpmxFileEntry): Boolean =
        entry.name.substringAfterLast('.', "").equals("ax", ignoreCase = true)

    private fun expectedFirstByte(entry: DpmxFileEntry): Int? =
        when (entry.name.substringAfterLast('.', "").lowercase()) {
            "bmp" -> 'B'.code
            "png" -> 0x89
            "jpg", "jpeg" -> 0xFF
            "gif" -> 'G'.code
            "wav" -> 'R'.code
            "ogg" -> 'O'.code
            else -> null
        }

    private fun prefixLengthFor(entry: DpmxFileEntry): Int =
        when (entry.name.substringAfterLast('.', "").lowercase()) {
            "ax" -> HSPHED_SIZE_BYTES
            "bmp" -> BMP_PREFIX_LENGTH
            "png" -> 8
            "jpg", "jpeg" -> 3
            "gif" -> 6
            "wav" -> 12
            "ogg" -> 4
            "txt", "ini", "as", "hsp", "cfg" -> TEXT_PREFIX_LENGTH
            else -> MIN_SAMPLE_LENGTH
        }

    private fun isPlausibleResource(entry: DpmxFileEntry, bytes: ByteArray): Boolean =
        when (entry.name.substringAfterLast('.', "").lowercase()) {
            "bmp" -> isPlausibleDpmxBmpHeader(bytes, entry.sizeBytes)
            "png" -> bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            )
            "jpg", "jpeg" -> bytes.size >= 3 && bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
            "gif" -> bytes.size >= 6 &&
                (String(bytes, 0, 6, Charsets.US_ASCII) == "GIF87a" ||
                    String(bytes, 0, 6, Charsets.US_ASCII) == "GIF89a")
            "wav" -> bytes.size >= 12 &&
                String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE"
            "ogg" -> bytes.size >= 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "OggS"
            else -> false
        }

    private fun isPrintableShiftJis(bytes: ByteArray): Boolean = try {
        val decoded = SHIFT_JIS.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
        decoded.isNotEmpty() && decoded.all { character ->
            character.code == 13 || character.code == 10 || character.code == 9 ||
                !Character.isISOControl(character)
        }
    } catch (_: CharacterCodingException) {
        false
    }

    private fun canonicalizeSymmetricKey(key: DpmxCipherKey): DpmxCipherKey =
        if (key.al >= 0x80) key.copy(al = key.al xor 0x80, cl = key.cl xor 0x80) else key

    private fun compareKeys(left: DpmxCipherKey, right: DpmxCipherKey): Int =
        compareValuesBy(left, right, DpmxCipherKey::al, DpmxCipherKey::cl, DpmxCipherKey::initialDl)

    private fun readUInt32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24)

    private fun Long.toIntSafe(): Int =
        minOf(Int.MAX_VALUE.toLong(), maxOf(0L, this)).toInt()

    private fun archiveId(container: DpmxContainer): String =
        "${container.peOffset}:${container.overlayOffset}:${container.dataAbsoluteOffset}:${container.fileCount}"

    private class ByteArraySource(private val bytes: ByteArray) : ReadOnlyRandomAccessSource {
        override val sizeBytes: Long get() = bytes.size.toLong()
        override fun readAt(position: Long, target: ByteArray, targetOffset: Int, length: Int): Int {
            require(position >= 0L && targetOffset >= 0 && length >= 0 &&
                targetOffset <= target.size - length)
            if (length == 0) return 0
            if (position >= bytes.size.toLong()) return -1
            val count = minOf(length.toLong(), bytes.size.toLong() - position).toInt()
            bytes.copyInto(target, targetOffset, position.toInt(), position.toInt() + count)
            return count
        }
        override fun close() = Unit
    }

    companion object {
        private const val DPMX_HEADER_SIZE = 16L
        private const val HSPHED_SIZE_BYTES = 0x60
        private const val MAX_HSP_VALIDATION_BYTES = 64 * 1024 * 1024
        private const val HSP3_VERSION_MIN = 0x00000301L
        private const val HSP3_VERSION_MAX = 0x00000399L
        private const val MAX_HSP3_VARIABLES = 1_000_000L
        private val HSP3_MAGIC = byteArrayOf(0x48, 0x53, 0x50, 0x33)
        private const val BMP_PREFIX_LENGTH = 30
        private const val TEXT_PREFIX_LENGTH = 32
        private const val MIN_SAMPLE_LENGTH = 6
        private val TEXT_RESOURCE_EXTENSIONS = setOf("txt", "ini", "as", "hsp", "cfg")
        private val SHIFT_JIS: Charset = Charset.forName("Shift_JIS")
    }
}
