package io.github.helterfall.ramen.runtime.systemnnn

import android.graphics.Bitmap
import io.github.helterfall.ramen.inspection.ResourceArchiveParsers
import io.github.helterfall.ramen.inspection.VpkAudioResource
import io.github.helterfall.ramen.runtime.nativeaudio.AndroidGpkImageDecoder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Locale

enum class SystemNnnAssetBankType {
    INDEXED_IMAGE,
    STANDALONE_WGQ_IMAGE,
    INDEXED_AUDIO
}

data class SystemNnnAssetBankInfo(
    /** Stable path-derived ID. Indexes remain local to this bank. */
    val id: String,
    val type: SystemNnnAssetBankType,
    val indexPath: String?,
    val archivePath: String?,
    val resourceCount: Int,
    /** Native DWQ image-group prefix (ta/bg/ev/sc/sm/sys), when recognized. */
    val nativeNamespace: String? = null,
    /** Numeric suffix in dwq/<namespace><slot>.gtb/.gpk, when recognized. */
    val bankSlot: Int? = null
)

data class SystemNnnAssetCatalog(
    val imageBanks: List<SystemNnnAssetBankInfo>,
    val audioBanks: List<SystemNnnAssetBankInfo>,
    /** Loose VAW files that could not be decoded as a supported native audio wrapper. */
    val unindexedVawFiles: List<String>,
    /** VPK files without a matching VTB index; no row order is inferred. */
    val unindexedVpkFiles: List<String>,
    /** MML files seen by the native WGQ/MML loader, but not mapped to a resource token. */
    val unindexedMmlFiles: List<String>,
    /** WGQ files not recognized as either standalone packed-BMP or supported loose audio. */
    val unindexedWgqFiles: List<String>,
    /** Loose .vaw/.wgq resources with a validated native audio wrapper. */
    val looseAudioResources: List<SystemNnnLooseAudioResourceInfo> = emptyList(),
    val warnings: List<String>
) {
    val totalIndexedImageResources: Int
        get() = imageBanks.sumOf(SystemNnnAssetBankInfo::resourceCount)

    val totalIndexedAudioResources: Int
        get() = audioBanks.sumOf(SystemNnnAssetBankInfo::resourceCount)
}

/**
 * Project-level asset facade over every validated image/audio bank found below
 * one imported game directory.
 *
 * GTB and VTB row numbers are bank-local. An unqualified numeric image/voice
 * index is accepted only when exactly one bank exists in that namespace;
 * otherwise callers must select a bank explicitly. Name lookup is allowed only
 * when the name resolves uniquely. This deliberately avoids merging unrelated
 * archive indexes into an invented global resource order.
 *
 * Archive payloads stay on disk. Image banks decode only the requested image;
 * indexed audio banks stage only the requested Ogg row, while loose VAW/WGQ
 * resources are materialized by unique path/name without invented voice indexes.
 */
class SystemNnnAssetManager private constructor(
    private val gameRoot: File,
    private val imageBanks: List<ImageBank>,
    private val audioBanks: List<AudioBank>,
    private val looseAudioRepository: SystemNnnLooseAudioRepository,
    private val musicResourceNames: List<String>,
    private val soundResourceNames: List<String>,
    val catalog: SystemNnnAssetCatalog
) : SystemNnnSceneAssetResolver, SystemNnnAudioResourceRepository {

    private data class ImageBank(
        val info: SystemNnnAssetBankInfo,
        val repository: SystemNnnGpkBitmapRepository
    )

    private data class AudioBank(
        val info: SystemNnnAssetBankInfo,
        val repository: SystemNnnVpkAudioRepository
    )

    private data class PreloadedImageKey(
        val layerIndex: Int,
        val normalizedResourceId: String
    )

    private val preloadedImages = LinkedHashMap<PreloadedImageKey, Bitmap>(16, 0.75f, true)
    private var preloadedImagePixels = 0L
    private var closed = false

    /** Aggregate count for diagnostics only; it is not a global GTB index range. */
    val resourceCount: Int
        get() = imageBanks.fold(0L) { total, bank ->
            total + bank.info.resourceCount.toLong()
        }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /**
     * The legacy player consumes dialogue voice indexes directly. Expose that
     * list only when exactly one VTB bank exists; a multi-bank catalog has no
     * proven cross-bank index mapping.
     */
    override val entries: List<VpkAudioResource>
        get() = if (!closed && audioBanks.size == 1) {
            audioBanks.single().repository.entries
        } else {
            emptyList()
        }

    @Throws(IOException::class)
    override fun loadBitmap(resourceId: String): Bitmap? {
        checkOpen()
        val matchingBanks = imageBanks.filter { it.repository.hasResourceName(resourceId) }
        if (matchingBanks.size > 1) {
            throw IOException(
                "Image resource name '" + resourceId +
                    "' exists in multiple banks; use a bank-qualified lookup."
            )
        }
        if (matchingBanks.size == 1) {
            return matchingBanks.single().repository.loadBitmap(resourceId)
        }

        // Preserve numeric-index/block-offset compatibility for a single bank.
        return if (imageBanks.size == 1) {
            imageBanks.single().repository.loadBitmap(resourceId)
        } else {
            null
        }
    }

    @Throws(IOException::class)
    override fun loadBitmapByIndex(index: Long): Bitmap? {
        checkOpen()
        return when (imageBanks.size) {
            0 -> null
            1 -> imageBanks.single().repository.loadBitmapByIndex(index)
            else -> throw IOException(
                "Image index is ambiguous across " + imageBanks.size +
                    " banks; use loadBitmapByBankIndex(bankId, index)."
            )
        }
    }

    @Throws(IOException::class)
    fun loadBitmapFromBank(bankId: String, resourceId: String): Bitmap? {
        checkOpen()
        val bank = imageBanks.firstOrNull { it.info.id == bankId }
            ?: throw IOException("Unknown image-bank ID: " + bankId)
        return bank.repository.loadBitmap(resourceId)
    }

    @Throws(IOException::class)
    fun loadBitmapByBankIndex(bankId: String, index: Long): Bitmap? {
        checkOpen()
        val bank = imageBanks.firstOrNull { it.info.id == bankId }
            ?: throw IOException("Unknown image-bank ID: " + bankId)
        return bank.repository.loadBitmapByIndex(index)
    }

    /**
     * Resolves one native DWQ namespace/slot explicitly. The caller must not
     * infer a namespace from an SPT operand until that operand's semantics are proven.
     */
    @Throws(IOException::class)
    override fun loadBitmapFromNativeBank(
        namespace: String,
        slot: Int,
        resourceId: String
    ): Bitmap? {
        checkOpen()
        if (namespace.isBlank() || slot < 0) {
            throw IOException("A native image namespace and non-negative slot are required.")
        }
        val matches = imageBanks.filter {
            it.info.nativeNamespace.equals(namespace.trim(), ignoreCase = true) &&
                it.info.bankSlot == slot
        }
        return when (matches.size) {
            0 -> null
            1 -> matches.single().repository.loadBitmap(resourceId)
            else -> throw IOException(
                "Native image bank " + namespace + slot +
                    " is ambiguous; use its path-derived bank ID."
            )
        }
    }

    /** Resolves a zero-based resource row in an explicitly selected native bank. */
    @Throws(IOException::class)
    override fun loadBitmapByNativeBankIndex(namespace: String, slot: Int, index: Long): Bitmap? {
        checkOpen()
        if (namespace.isBlank() || slot < 0) {
            throw IOException("A native image namespace and non-negative slot are required.")
        }
        val matches = imageBanks.filter {
            it.info.nativeNamespace.equals(namespace.trim(), ignoreCase = true) &&
                it.info.bankSlot == slot
        }
        return when (matches.size) {
            0 -> null
            1 -> matches.single().repository.loadBitmapByIndex(index)
            else -> throw IOException(
                "Native image bank " + namespace + slot +
                    " is ambiguous; use loadBitmapByBankIndex(bankId, index)."
            )
        }
    }

    @Throws(IOException::class)
    fun loadBitmapAtBlockOffset(bankId: String, blockOffset: Long): Bitmap? {
        checkOpen()
        val bank = imageBanks.firstOrNull { it.info.id == bankId }
            ?: throw IOException("Unknown image-bank ID: " + bankId)
        return bank.repository.loadBitmapAtBlockOffset(blockOffset)
    }

    @Synchronized
    override fun preloadBitmap(layerIndex: Int, resourceId: String): Boolean {
        checkOpen()
        if (layerIndex !in 0 until MAX_PRELOAD_LAYERS) {
            throw IOException("PRELOADDWQ layer is outside the supported layer range.")
        }
        val key = PreloadedImageKey(
            layerIndex,
            resourceId.trim().replace('\\', '/').lowercase(Locale.ROOT)
        )
        if (preloadedImages[key] != null) return true

        // Native PRELOADDWQ loads into the picture slot belonging to this layer.
        // Replacing that slot releases the older not-yet-displayed image.
        val replacedKeys = preloadedImages.keys.filter { it.layerIndex == layerIndex }
        replacedKeys.forEach { oldKey ->
            preloadedImages.remove(oldKey)?.let { oldBitmap ->
                preloadedImagePixels -= oldBitmap.width.toLong() * oldBitmap.height.toLong()
                releaseBitmap(oldBitmap)
            }
        }

        val bitmap = loadBitmap(resourceId) ?: return false
        val pixels = bitmap.width.toLong() * bitmap.height.toLong()
        if (pixels <= 0L || pixels > MAX_PRELOADED_IMAGE_PIXELS) {
            releaseBitmap(bitmap)
            return false
        }
        while (preloadedImagePixels + pixels > MAX_PRELOADED_IMAGE_PIXELS) {
            val oldest = preloadedImages.entries.firstOrNull() ?: break
            preloadedImages.remove(oldest.key)
            preloadedImagePixels -= oldest.value.width.toLong() * oldest.value.height.toLong()
            releaseBitmap(oldest.value)
        }
        preloadedImages[key] = bitmap
        preloadedImagePixels += pixels
        return true
    }

    @Synchronized
    override fun takePreloadedBitmap(layerIndex: Int, resourceId: String): Bitmap? {
        checkOpen()
        val key = PreloadedImageKey(
            layerIndex,
            resourceId.trim().replace('\\', '/').lowercase(Locale.ROOT)
        )
        val bitmap = preloadedImages.remove(key) ?: return null
        preloadedImagePixels -= bitmap.width.toLong() * bitmap.height.toLong()
        return bitmap
    }

    override fun releaseBitmap(bitmap: Bitmap) {
        // The ViewModel retains the manager as the bitmap owner and invokes
        // release only after the corresponding Compose Image leaves composition.
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    override fun findResource(resourceId: String): VpkAudioResource? {
        checkOpen()
        val matchingBanks = audioBanks.filter { bank ->
            bank.repository.entries.any { audioResourceNameMatches(it.name, resourceId) }
        }
        if (matchingBanks.size > 1) return null
        if (matchingBanks.size == 1) {
            return matchingBanks.single().repository.findResource(resourceId)
        }
        return if (audioBanks.size == 1) {
            audioBanks.single().repository.findResource(resourceId)
        } else {
            null
        }
    }

    @Throws(IOException::class)
    @Synchronized
    override fun materialize(resourceId: String): File {
        checkOpen()
        val matchingBanks = audioBanks.filter { bank ->
            bank.repository.entries.any { audioResourceNameMatches(it.name, resourceId) }
        }
        if (matchingBanks.size > 1) {
            throw IOException(
                "Audio resource name '" + resourceId +
                    "' exists in multiple banks; use a bank-qualified lookup."
            )
        }
        if (matchingBanks.size == 1) {
            return matchingBanks.single().repository.materialize(resourceId)
        }
        if (audioBanks.size == 1 && audioBanks.single().repository.findResource(resourceId) != null) {
            return audioBanks.single().repository.materialize(resourceId)
        }
        return looseAudioRepository.materialize(resourceId)
    }

    @Throws(IOException::class)
    @Synchronized
    override fun materializeSound(resourceId: String): File {
        checkOpen()
        val token = normalizeNativeResourceToken(resourceId, "SE")
            .removeSuffixIgnoreCase(".vaw")
            .removePrefixIgnoreCase("vaw/")
            .removePrefixIgnoreCase("se/")
        if (token.isBlank()) throw IOException("SE list contains an empty resource name.")
        return looseAudioRepository.materialize("vaw/se/" + token + ".vaw")
    }

    @Throws(IOException::class)
    @Synchronized
    override fun materializeMusic(resourceId: String, repeatCount: Int): SystemNnnMusicPlaylist {
        checkOpen()
        if (repeatCount < 0) throw IOException("The native BGM repeat count is invalid.")
        val token = normalizeNativeResourceToken(resourceId, "BGM")
            .removeSuffixIgnoreCase(".mml")
            .removeSuffixIgnoreCase(".wgq")
            .removePrefixIgnoreCase("wgq/")
        if (token.isBlank()) throw IOException("BGM list contains an empty resource name.")

        val mmlPath = "wgq/" + token + ".mml"
        val mmlFile = File(gameRoot, mmlPath)
        val canonicalMml = mmlFile.canonicalFile
        if (!canonicalMml.toPath().startsWith(gameRoot.toPath()) ||
            Files.isSymbolicLink(mmlFile.toPath()) ||
            !canonicalMml.isFile
        ) {
            val single = looseAudioRepository.materialize("wgq/" + token + ".wgq")
            return SystemNnnMusicPlaylist(listOf(single), repeatCount)
        }
        if (canonicalMml.length() > MAX_MML_BYTES) {
            throw IOException("BGM MML playlist exceeds the supported size: " + mmlPath)
        }

        val sequence = SystemNnnMmlPlaylistParser.parse(
            SystemNnnLegacyText.decode(canonicalMml.readBytes())
        )
        val staged = ArrayList<File>(sequence.segmentNames.size)
        try {
            sequence.segmentNames.forEach { name ->
                val segment = normalizeNativeResourceToken(name, "WGQ segment")
                staged += looseAudioRepository.materialize("wgq/" + segment + ".wgq")
            }
            return SystemNnnMusicPlaylist(
                files = staged,
                repeatCount = repeatCount,
                infiniteLoopStartIndex = sequence.infiniteLoopStartIndex
            )
        } catch (error: Exception) {
            staged.forEach { runCatching { it.delete() } }
            if (error is IOException) {
                throw IOException(
                    "BGM '" + resourceId + "' references a WGQ segment that could not be prepared: " +
                        (error.message ?: "resource not found."),
                    error
                )
            }
            throw IOException("Could not prepare native BGM playlist '" + resourceId + "'.", error)
        }
    }

    override fun musicResourceName(oneBasedNumber: Int): String? {
        checkOpen()
        if (oneBasedNumber <= 0) return null
        val slot = (oneBasedNumber.toLong() - 1L) * 2L
        if (slot > Int.MAX_VALUE.toLong()) return null
        return musicResourceNames.getOrNull(slot.toInt())?.takeIf { it.isNotBlank() }
    }

    override fun soundResourceName(zeroBasedNumber: Int): String? {
        checkOpen()
        if (zeroBasedNumber < 0) return null
        val slot = zeroBasedNumber.toLong() * 2L
        if (slot > Int.MAX_VALUE.toLong()) return null
        return soundResourceNames.getOrNull(slot.toInt())?.takeIf { it.isNotBlank() }
    }

    @Throws(IOException::class)
    @Synchronized
    override fun materializeByIndex(index: Long): File {
        checkOpen()
        return when (audioBanks.size) {
            0 -> throw IOException("No indexed VAW/VTB audio bank is available.")
            1 -> audioBanks.single().repository.materializeByIndex(index)
            else -> throw IOException(
                "Voice index is ambiguous across " + audioBanks.size +
                    " banks; use materializeByBankIndex(bankId, index)."
            )
        }
    }

    /** Materializes a validated loose VAW/WGQ resource by unique name or relative path. */
    @Throws(IOException::class)
    @Synchronized
    fun materializeLooseAudio(resourceId: String): File {
        checkOpen()
        return looseAudioRepository.materialize(resourceId)
    }

    @Throws(IOException::class)
    @Synchronized
    fun materializeFromBank(bankId: String, resourceId: String): File {
        checkOpen()
        val bank = audioBanks.firstOrNull { it.info.id == bankId }
            ?: throw IOException("Unknown audio-bank ID: " + bankId)
        return bank.repository.materialize(resourceId)
    }

    @Throws(IOException::class)
    @Synchronized
    fun materializeByBankIndex(bankId: String, index: Long): File {
        checkOpen()
        val bank = audioBanks.firstOrNull { it.info.id == bankId }
            ?: throw IOException("Unknown audio-bank ID: " + bankId)
        return bank.repository.materializeByIndex(index)
    }

    private fun checkOpen() {
        if (closed) throw IOException("System-NNN asset manager is closed.")
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        preloadedImages.values.forEach { bitmap -> runCatching { releaseBitmap(bitmap) } }
        preloadedImages.clear()
        preloadedImagePixels = 0L
        imageBanks.forEach { runCatching { it.repository.close() } }
        audioBanks.forEach { runCatching { it.repository.close() } }
        runCatching { looseAudioRepository.close() }
    }

    companion object {
        private const val MAX_GAME_SCAN_DEPTH = 12
        private const val MAX_DISCOVERED_FILES = 10_000
        private const val MAX_NAME_LIST_BYTES = 4L * 1024L * 1024L
        private const val MAX_NAME_LIST_ENTRIES = 100_000
        private const val MAX_MML_BYTES = 4L * 1024L * 1024L
        private const val MAX_PRELOAD_LAYERS = 32
        private const val MAX_PRELOADED_IMAGE_PIXELS = 8_000_000L
        private const val ALTERNATE_PROJECT_DIRECTORY = "!eng files"

        private fun normalizeNativeResourceToken(value: String, kind: String): String {
            val token = value.trim().replace('\\', '/')
            if (token.isBlank() || token.startsWith('/') || token.length > 255 || ':' in token ||
                token.split('/').any { it.isBlank() || it == "." || it == ".." } ||
                token.any { it.isISOControl() }
            ) {
                throw IOException(kind + " resource name is invalid.")
            }
            return token
        }

        private fun String.removeSuffixIgnoreCase(suffix: String): String =
            if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this

        private fun String.removePrefixIgnoreCase(prefix: String): String =
            if (startsWith(prefix, ignoreCase = true)) drop(prefix.length) else this

        private val INDEXED_IMAGE_ARCHIVE_EXTENSIONS = setOf("gpk", "wgq")
        private val INDEXED_AUDIO_ARCHIVE_EXTENSIONS = setOf("vaw", "vpk")

        @Throws(IOException::class)
        fun forGameDirectory(
            gameDirectory: File,
            cacheDirectory: File,
            imageDecoder: AndroidGpkImageDecoder = AndroidGpkImageDecoder()
        ): SystemNnnAssetManager {
            if (!gameDirectory.isDirectory || Files.isSymbolicLink(gameDirectory.toPath())) {
                throw IOException("The imported game directory is unavailable or is a symbolic link.")
            }
            val root = gameDirectory.canonicalFile
            val warnings = ArrayList<String>()
            val musicResourceNames = readGameNameList(root, "bgmlist.xtx", warnings)
            val soundResourceNames = readGameNameList(root, "selist.xtx", warnings)
            val imageBanks = ArrayList<ImageBank>()
            val audioBanks = ArrayList<AudioBank>()
            val pairedImageArchives = HashSet<String>()
            val pairedAudioArchives = HashSet<String>()

            val gtbFiles = discoverFiles(root, "gtb", warnings)
            gtbFiles.forEach { indexFile ->
                val archiveCandidates = matchingSiblings(indexFile, INDEXED_IMAGE_ARCHIVE_EXTENSIONS)
                if (archiveCandidates.isEmpty()) {
                    warnings += relativePath(root, indexFile) +
                        ": GTB has no sibling GPK/WGQ archive with the same base name."
                    return@forEach
                }

                var openedRepository: SystemNnnGpkBitmapRepository? = null
                var selectedArchive: File? = null
                archiveCandidates.forEach candidateLoop@ { archiveFile ->
                    if (openedRepository != null) return@candidateLoop
                    try {
                        val repository = SystemNnnGpkBitmapRepository(indexFile, archiveFile, imageDecoder)
                        if (repository.entries.none { it.isValid }) {
                            repository.close()
                            warnings += relativePath(root, archiveFile) +
                                ": indexed image archive contains no valid image resources."
                        } else {
                            openedRepository = repository
                            selectedArchive = archiveFile
                        }
                    } catch (error: Exception) {
                        warnings += relativePath(root, archiveFile) +
                            ": image archive candidate could not be opened: " +
                            (error.message ?: error.javaClass.simpleName)
                    }
                }

                val repository = openedRepository
                val archiveFile = selectedArchive
                if (repository != null && archiveFile != null) {
                    pairedImageArchives += archiveFile.canonicalPath
                    val info = SystemNnnAssetBankInfo(
                        id = relativePath(root, indexFile),
                        type = SystemNnnAssetBankType.INDEXED_IMAGE,
                        indexPath = relativePath(root, indexFile),
                        archivePath = relativePath(root, archiveFile),
                        resourceCount = repository.resourceCount,
                        nativeNamespace = nativeImageIdentity(indexFile.nameWithoutExtension).first,
                        bankSlot = nativeImageIdentity(indexFile.nameWithoutExtension).second
                    )
                    imageBanks += ImageBank(info, repository)
                } else {
                    warnings += relativePath(root, indexFile) + ": no valid image archive candidate was found."
                }
            }

            val wgqFiles = discoverFiles(root, "wgq", warnings)
            val unpairedWgq = wgqFiles.filter { it.canonicalPath !in pairedImageArchives }
            val standaloneWgqImages = unpairedWgq.filter(
                ResourceArchiveParsers::isBlackCycWgqImageCandidate
            )
            standaloneWgqImages
                .groupBy { it.parentFile?.canonicalPath ?: root.canonicalPath }
                .toSortedMap()
                .forEach { (parentPath, files) ->
                    val orderedFiles = files.sortedBy { relativePath(root, it) }
                    try {
                        val repository = SystemNnnGpkBitmapRepository(orderedFiles, imageDecoder)
                        val parent = File(parentPath)
                        val bankId = "wgq-standalone/" +
                            if (parent == root) "." else relativePath(root, parent)
                        val info = SystemNnnAssetBankInfo(
                            id = bankId,
                            type = SystemNnnAssetBankType.STANDALONE_WGQ_IMAGE,
                            indexPath = null,
                            archivePath = relativePath(root, parent),
                            resourceCount = repository.resourceCount
                        )
                        imageBanks += ImageBank(info, repository)
                    } catch (error: Exception) {
                        warnings += relativePath(root, File(parentPath)) +
                            ": standalone WGQ image bank could not be opened: " +
                            (error.message ?: error.javaClass.simpleName)
                    }
                }

            val vtbFiles = discoverFiles(root, "vtb", warnings)
            vtbFiles.forEach { indexFile ->
                val archiveCandidates = matchingSiblings(indexFile, INDEXED_AUDIO_ARCHIVE_EXTENSIONS)
                if (archiveCandidates.isEmpty()) {
                    warnings += relativePath(root, indexFile) +
                        ": VTB has no sibling VAW/VPK archive with the same base name."
                    return@forEach
                }

                var openedRepository: SystemNnnVpkAudioRepository? = null
                var selectedArchive: File? = null
                archiveCandidates.forEach candidateLoop@ { archiveFile ->
                    if (openedRepository != null) return@candidateLoop
                    try {
                        val repository = SystemNnnVpkAudioRepository(
                            indexFile = indexFile,
                            archiveFile = archiveFile,
                            cacheDirectory = cacheDirectory
                        )
                        if (repository.entries.isEmpty()) {
                            repository.close()
                            warnings += relativePath(root, archiveFile) + ": indexed audio bank is empty."
                        } else {
                            openedRepository = repository
                            selectedArchive = archiveFile
                        }
                    } catch (error: Exception) {
                        warnings += relativePath(root, archiveFile) +
                            ": audio archive candidate could not be opened: " +
                            (error.message ?: error.javaClass.simpleName)
                    }
                }

                val repository = openedRepository
                val archiveFile = selectedArchive
                if (repository != null && archiveFile != null) {
                    pairedAudioArchives += archiveFile.canonicalPath
                    val info = SystemNnnAssetBankInfo(
                        id = relativePath(root, indexFile),
                        type = SystemNnnAssetBankType.INDEXED_AUDIO,
                        indexPath = relativePath(root, indexFile),
                        archivePath = relativePath(root, archiveFile),
                        resourceCount = repository.entries.size
                    )
                    audioBanks += AudioBank(info, repository)
                } else {
                    warnings += relativePath(root, indexFile) + ": no valid audio archive candidate was found."
                }
            }

            val vawFiles = discoverFiles(root, "vaw", warnings)
            val looseVawFiles = vawFiles.filter { it.canonicalPath !in pairedAudioArchives }
            val vpkFiles = discoverFiles(root, "vpk", warnings)
            val unindexedVpk = vpkFiles
                .filter { it.canonicalPath !in pairedAudioArchives }
                .map { relativePath(root, it) }
            // The target executable has a separate WGQ/MML loader path. Keep MML
            // filenames visible, but do not pair them by directory order or guess
            // that a differently named MML file describes a given WGQ resource.
            val unindexedMmlFiles = discoverFiles(root, "mml", warnings)
                .map { relativePath(root, it) }
            val looseWgqFiles = unpairedWgq.filter { it !in standaloneWgqImages }
            val looseAudioRepository = SystemNnnLooseAudioRepository(
                gameDirectory = root,
                candidateFiles = looseVawFiles + looseWgqFiles,
                cacheDirectory = cacheDirectory
            )
            warnings += looseAudioRepository.warnings
            val recognizedLooseAudio = looseAudioRepository.resources.map { it.id }.toSet()
            val unindexedVaw = looseVawFiles
                .map { relativePath(root, it) }
                .filterNot { it in recognizedLooseAudio }
            val unindexedWgqFiles = looseWgqFiles
                .map { relativePath(root, it) }
                .filterNot { it in recognizedLooseAudio }

            val catalog = SystemNnnAssetCatalog(
                imageBanks = imageBanks.map(ImageBank::info),
                audioBanks = audioBanks.map(AudioBank::info),
                unindexedVawFiles = unindexedVaw,
                unindexedVpkFiles = unindexedVpk,
                unindexedMmlFiles = unindexedMmlFiles,
                unindexedWgqFiles = unindexedWgqFiles,
                looseAudioResources = looseAudioRepository.resources,
                warnings = warnings.toList()
            )
            return SystemNnnAssetManager(
                gameRoot = root,
                imageBanks = imageBanks.toList(),
                audioBanks = audioBanks.toList(),
                looseAudioRepository = looseAudioRepository,
                musicResourceNames = musicResourceNames,
                soundResourceNames = soundResourceNames,
                catalog = catalog
            )
        }

        private fun readGameNameList(
            root: File,
            fileName: String,
            warnings: MutableList<String>
        ): List<String> {
            val scriptDirectory = root.listFiles()?.firstOrNull {
                it.isDirectory && it.name.equals("spt", ignoreCase = true)
            } ?: return emptyList()
            val source = scriptDirectory.listFiles()?.firstOrNull {
                it.isFile && it.name.equals(fileName, ignoreCase = true)
            } ?: return emptyList()
            if (source.length() > MAX_NAME_LIST_BYTES) {
                warnings += relativePath(root, source) + ": name list exceeds the supported size and was ignored."
                return emptyList()
            }
            return runCatching {
                val text = SystemNnnLegacyText.decode(source.readBytes())
                Regex("[^,\\s]+").findAll(text)
                    .map { it.value.trim() }
                    .filter { it.isNotEmpty() && it.none { character -> character.isISOControl() } }
                    .take(MAX_NAME_LIST_ENTRIES)
                    .toList()
            }.onFailure { error ->
                warnings += relativePath(root, source) + ": could not read name list: " +
                    (error.message ?: error.javaClass.simpleName)
            }.getOrDefault(emptyList())
        }

        private fun discoverFiles(
            root: File,
            extension: String,
            warnings: MutableList<String>
        ): List<File> {
            val discovered = root.walkTopDown()
                .maxDepth(MAX_GAME_SCAN_DEPTH)
                .onEnter { directory ->
                    !Files.isSymbolicLink(directory.toPath()) &&
                        (
                            directory == root ||
                                directory.name.equals(ALTERNATE_PROJECT_DIRECTORY, ignoreCase = true) ||
                                !hasDirectSystemNnnProject(directory)
                            )
                }
                .filter { file ->
                    file.isFile &&
                        !Files.isSymbolicLink(file.toPath()) &&
                        file.extension.equals(extension, ignoreCase = true)
                }
                .take(MAX_DISCOVERED_FILES + 1)
                .toList()
                .sortedBy { relativePath(root, it) }

            if (discovered.size > MAX_DISCOVERED_FILES) {
                warnings += "Asset discovery for .$extension was truncated at $MAX_DISCOVERED_FILES files."
            }
            return discovered.take(MAX_DISCOVERED_FILES)
        }

        /**
         * A selected game folder can itself contain another complete installation
         * (for example a bundled demo). Its archives must not be merged into the
         * active project's bank catalog: SPT indexes are local to their install.
         */
        private fun hasDirectSystemNnnProject(directory: File): Boolean {
            val sptDirectory = directory.listFiles()?.firstOrNull { candidate ->
                candidate.isDirectory &&
                    !Files.isSymbolicLink(candidate.toPath()) &&
                    candidate.name.equals("spt", ignoreCase = true)
            } ?: return false
            return sptDirectory.listFiles()?.any { candidate ->
                candidate.isFile &&
                    !Files.isSymbolicLink(candidate.toPath()) &&
                    candidate.name.equals("project.xtx", ignoreCase = true)
            } == true
        }

        private fun matchingSiblings(indexFile: File, extensions: Set<String>): List<File> =
            indexFile.parentFile
                ?.listFiles()
                ?.asSequence()
                ?.filter { candidate ->
                    candidate.isFile &&
                        !Files.isSymbolicLink(candidate.toPath()) &&
                        candidate.extension.lowercase() in extensions &&
                        candidate.nameWithoutExtension.equals(
                            indexFile.nameWithoutExtension,
                            ignoreCase = true
                        )
                }
                ?.sortedWith(
                    compareBy<File> { candidate ->
                        when (candidate.extension.lowercase()) {
                            "vaw", "gpk" -> 0
                            else -> 1
                        }
                    }.thenBy { it.name.lowercase() }
                )
                ?.toList()
                .orEmpty()

        private val NATIVE_IMAGE_NAMESPACES = setOf("ta", "bg", "ev", "sc", "sm", "sys")

        private fun nativeImageIdentity(baseName: String): Pair<String?, Int?> {
            val normalized = baseName.lowercase()
            val slot = normalized.lastOrNull()?.digitToIntOrNull() ?: return null to null
            val namespace = normalized.dropLast(1)
            return if (namespace in NATIVE_IMAGE_NAMESPACES) {
                namespace to slot
            } else {
                null to null
            }
        }

        private fun relativePath(root: File, file: File): String =
            file.relativeTo(root).invariantSeparatorsPath
    }
}
