package io.github.helterfall.ramen.runtime.systemnnn

import android.graphics.Bitmap
import io.github.helterfall.ramen.inspection.ResourceArchiveParsers
import io.github.helterfall.ramen.inspection.VpkAudioResource
import io.github.helterfall.ramen.runtime.nativeaudio.AndroidGpkImageDecoder
import java.io.File
import java.io.IOException
import java.nio.file.Files

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
    /** Loose VAW files have no paired VTB index and are not assigned voice indexes. */
    val unindexedVawFiles: List<String>,
    /** VPK files without a matching VTB index; no row order is inferred. */
    val unindexedVpkFiles: List<String>,
    /** MML files seen by the native WGQ/MML loader, but not mapped to a resource token. */
    val unindexedMmlFiles: List<String>,
    /** WGQ files not paired with GTB and not recognized as standalone packed-BMP images. */
    val unindexedWgqFiles: List<String>,
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
 * audio banks stage only the requested Ogg stream into the app cache.
 */
class SystemNnnAssetManager private constructor(
    private val imageBanks: List<ImageBank>,
    private val audioBanks: List<AudioBank>,
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

    @Throws(IOException::class)
    fun loadBitmapAtBlockOffset(bankId: String, blockOffset: Long): Bitmap? {
        checkOpen()
        val bank = imageBanks.firstOrNull { it.info.id == bankId }
            ?: throw IOException("Unknown image-bank ID: " + bankId)
        return bank.repository.loadBitmapAtBlockOffset(blockOffset)
    }

    override fun releaseBitmap(bitmap: Bitmap) {
        // The ViewModel retains the manager as the bitmap owner and invokes
        // release only after the corresponding Compose Image leaves composition.
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    override fun findResource(resourceId: String): VpkAudioResource? {
        checkOpen()
        val matchingBanks = audioBanks.filter { bank ->
            bank.repository.entries.any { it.name.equals(resourceId, ignoreCase = true) }
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
            bank.repository.entries.any { it.name.equals(resourceId, ignoreCase = true) }
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
        if (audioBanks.size == 1) {
            return audioBanks.single().repository.materialize(resourceId)
        }
        throw IOException("Audio resource was not found or its bank is ambiguous: " + resourceId)
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
        imageBanks.forEach { runCatching { it.repository.close() } }
        audioBanks.forEach { runCatching { it.repository.close() } }
    }

    companion object {
        private const val MAX_GAME_SCAN_DEPTH = 12
        private const val MAX_DISCOVERED_FILES = 10_000
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
                        if (repository.resourceCount <= 0) {
                            repository.close()
                            warnings += relativePath(root, archiveFile) + ": indexed image bank is empty."
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
            val unindexedVaw = vawFiles
                .filter { it.canonicalPath !in pairedAudioArchives }
                .map { relativePath(root, it) }
            val vpkFiles = discoverFiles(root, "vpk", warnings)
            val unindexedVpk = vpkFiles
                .filter { it.canonicalPath !in pairedAudioArchives }
                .map { relativePath(root, it) }
            // The target executable has a separate WGQ/MML loader path. Keep MML
            // filenames visible, but do not pair them by directory order or guess
            // that a differently named MML file describes a given WGQ resource.
            val unindexedMmlFiles = discoverFiles(root, "mml", warnings)
                .map { relativePath(root, it) }
            val unindexedWgqFiles = unpairedWgq
                .filter { it !in standaloneWgqImages }
                .map { relativePath(root, it) }

            val catalog = SystemNnnAssetCatalog(
                imageBanks = imageBanks.map(ImageBank::info),
                audioBanks = audioBanks.map(AudioBank::info),
                unindexedVawFiles = unindexedVaw,
                unindexedVpkFiles = unindexedVpk,
                unindexedMmlFiles = unindexedMmlFiles,
                unindexedWgqFiles = unindexedWgqFiles,
                warnings = warnings.toList()
            )
            return SystemNnnAssetManager(
                imageBanks = imageBanks.toList(),
                audioBanks = audioBanks.toList(),
                catalog = catalog
            )
        }

        private fun discoverFiles(
            root: File,
            extension: String,
            warnings: MutableList<String>
        ): List<File> {
            val discovered = root.walkTopDown()
                .maxDepth(MAX_GAME_SCAN_DEPTH)
                .onEnter { directory -> !Files.isSymbolicLink(directory.toPath()) }
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
