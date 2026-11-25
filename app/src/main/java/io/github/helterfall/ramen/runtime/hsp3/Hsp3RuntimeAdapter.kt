package io.github.helterfall.ramen.runtime.hsp3

import io.github.helterfall.ramen.inspection.DpmxCipherKey
import io.github.helterfall.ramen.inspection.DpmxContainer
import io.github.helterfall.ramen.inspection.DpmxContainerParser
import io.github.helterfall.ramen.inspection.DpmxDecryptingInputStream
import io.github.helterfall.ramen.inspection.DpmxDecryptor
import io.github.helterfall.ramen.inspection.DpmxFileEntry
import io.github.helterfall.ramen.inspection.DpmxKeyResolution
import io.github.helterfall.ramen.inspection.DpmxKeyResolver
import io.github.helterfall.ramen.inspection.FileRandomAccessSource
import io.github.helterfall.ramen.inspection.ReadOnlyRandomAccessSource
import io.github.helterfall.ramen.parser.Hsp3BytecodeLoader
import io.github.helterfall.ramen.parser.Hsp3Program
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.FileInputStream
import java.io.RandomAccessFile

fun interface Hsp3InstructionDecoder {
    /**
     * Converts validated CS tokens and their DS/OT references into the
     * renderer-neutral HSP instruction IR. Implementations must reject
     * unsupported token/argument forms instead of guessing operand widths.
     */
    fun decode(program: Hsp3Program): List<Hsp3Instruction>
}

data class Hsp3PreparedGame(
    val executable: File,
    val container: DpmxContainer,
    val scriptEntry: DpmxFileEntry,
    val scriptKey: DpmxKeyResolution,
    val program: Hsp3Program
)

/** Opens one DPMX resource at a time and decrypts it directly into the consumer. */
class DpmxAssetRepository(
    private val executable: File,
    private val container: DpmxContainer,
    private val keyResolver: DpmxKeyResolver
) : Closeable, Hsp3FileStorage {
    private val gameRoot: File = executable.canonicalFile.parentFile
        ?: throw IOException("The HSP game directory is unavailable.")

    fun open(name: String): InputStream? {
        val normalized = normalizeRelativeName(name)
        val local = resolvePrivateFile(normalized)
        if (local.isFile) return FileInputStream(local)
        val entry = container.entries.firstOrNull {
            normalizeArchiveName(it.name).equals(normalized, ignoreCase = true)
        } ?: return null
        val source = FileRandomAccessSource(executable)
        return try {
            val resolution = keyResolver.resolve(source, container, entry)
            if (resolution == null) {
                source.close()
                null
            } else {
                DpmxDecryptingInputStream(source, entry, resolution.key)
            }
        } catch (error: Exception) {
            source.close()
            throw error
        }
    }

    override fun read(name: String): ByteArray? {
        val stream = open(name) ?: return null
        stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_FILE_BYTES) {
                    throw IOException("HSP file read exceeds the configured size limit.")
                }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    override fun write(name: String, offset: Int, bytes: ByteArray) {
        if (offset < 0 || bytes.size > MAX_FILE_BYTES ||
            offset.toLong() + bytes.size.toLong() > MAX_FILE_BYTES
        ) {
            throw IOException("HSP bsave request exceeds the configured file bounds.")
        }
        val normalized = normalizeRelativeName(name)
        val target = resolvePrivateFile(normalized)
        val parent = target.parentFile ?: throw IOException("HSP save path has no parent directory.")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Could not create the HSP save directory.")
        }
        val checkedTarget = target.canonicalFile
        if (!checkedTarget.toPath().startsWith(gameRoot.toPath())) {
            throw IOException("HSP save path escapes the imported game directory.")
        }
        if (offset > 0 && !checkedTarget.isFile) {
            throw IOException("HSP bsave with a non-zero file offset requires an existing file.")
        }
        RandomAccessFile(checkedTarget, "rw").use { output ->
            if (offset == 0) output.setLength(0L)
            output.seek(offset.toLong())
            output.write(bytes)
            if (output.length() > MAX_FILE_BYTES) {
                throw IOException("HSP save file exceeds the configured size limit.")
            }
        }
    }

    private fun resolvePrivateFile(normalized: String): File {
        val target = File(gameRoot, normalized.replace('/', File.separatorChar)).canonicalFile
        if (target == gameRoot || !target.toPath().startsWith(gameRoot.toPath())) {
            throw IOException("HSP file path escapes the imported game directory.")
        }
        return target
    }

    private fun normalizeRelativeName(name: String): String {
        val value = name.replace(92.toChar(), '/').trim()
        if (value.isBlank() || value.startsWith("/") || value.contains(':') ||
            value.indexOf(0.toChar()) >= 0
        ) {
            throw IOException("Invalid HSP relative file name.")
        }
        val parts = value.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." || it.indexOf(0.toChar()) >= 0 }) {
            throw IOException("Invalid HSP relative file name.")
        }
        return parts.joinToString("/")
    }

    private fun normalizeArchiveName(name: String): String =
        name.replace(92.toChar(), '/').trim().trimStart('/').split('/')
            .filter { it.isNotEmpty() && it != "." }.joinToString("/")

    override fun close() = Unit

    companion object {
        private const val MAX_FILE_BYTES = 32 * 1024 * 1024
    }
}

/**
 * Ramen-side HSP3 adapter boundary for PE32+DPMX executables.
 *
 * prepare() identifies and decrypts start.ax in a bounded in-memory script
 * buffer, then hands that bytecode to Hsp3BytecodeLoader. createSession()
 * defaults to the OpenHSP CS decoder; an alternate decoder can still be
 * injected for validation or future compatibility profiles.
 */
class Hsp3RuntimeAdapter(
    private val containerParser: DpmxContainerParser = DpmxContainerParser(),
    private val keyResolver: DpmxKeyResolver = DpmxKeyResolver(),
    private val bytecodeLoader: Hsp3BytecodeLoader = Hsp3BytecodeLoader(),
    private val maxScriptSizeBytes: Long = DEFAULT_MAX_SCRIPT_SIZE_BYTES
) {
    init {
        require(maxScriptSizeBytes >= Hsp3BytecodeLoader.HSPHED_SIZE_BYTES)
        require(maxScriptSizeBytes <= Int.MAX_VALUE.toLong())
    }

    @Throws(IOException::class)
    fun prepare(executable: File): Hsp3PreparedGame {
        if (!executable.isFile) throw IOException("The HSP3 executable is not a regular file.")
        FileRandomAccessSource(executable).use { source ->
            val container = try {
                containerParser.parse(source)
            } catch (error: Exception) {
                throw IOException("PE32/DPMX parsing failed: ${error.message ?: error.javaClass.simpleName}", error)
            }
            val entry = try {
                container.findEntry(START_SCRIPT_NAME)
                    ?: throw IOException("The DPMX container does not contain start.ax.")
            } catch (error: Exception) {
                throw IOException("DPMX start.ax lookup failed: ${error.message ?: error.javaClass.simpleName}", error)
            }
            if (entry.sizeBytes > maxScriptSizeBytes) {
                throw IOException("DPMX start.ax size validation failed: the decoded script exceeds the configured limit.")
            }
            val resolution = try {
                keyResolver.resolve(source, container, entry)
                    ?: throw IOException("A verified DPMX decryption key was not found.")
            } catch (error: Exception) {
                throw IOException("DPMX start.ax key resolution failed: ${error.message ?: error.javaClass.simpleName}", error)
            }

            val program = try {
                val output = ByteArrayOutputStream(entry.sizeBytes.toInt())
                DpmxDecryptor.decrypt(source, entry, resolution.key, output)
                bytecodeLoader.load(ByteArrayRandomAccessSource(output.toByteArray()))
            } catch (error: Exception) {
                throw IOException("HSPHED/CS/DS/OT/DI loading failed: ${error.message ?: error.javaClass.simpleName}", error)
            }

            if (program.header.libraryInfoSizeBytes != 0L ||
                program.header.functionInfoSizeBytes != 0L ||
                program.header.moduleInfoSizeBytes != 0L ||
                program.header.functionInfo2SizeBytes != 0L ||
                program.header.hasExternalPluginData
            ) {
                throw IOException("HSP3 external-library validation failed: this runtime profile does not load DLL/HPI plugin segments.")
            }

            return Hsp3PreparedGame(
                executable = executable.canonicalFile,
                container = container,
                scriptEntry = entry,
                scriptKey = resolution,
                program = program
            )
        }
    }

    @Throws(IOException::class)
    fun createSession(
        executable: File,
        instructionDecoder: Hsp3InstructionDecoder = Hsp3BytecodeDecoder(),
        input: Hsp3InputProvider = Hsp3InputProvider { 0 }
    ): Hsp3RuntimeSession {
        val prepared = prepare(executable)
        val assets = DpmxAssetRepository(prepared.executable, prepared.container, keyResolver)
        try {
            val decodedInstructions = instructionDecoder.decode(prepared.program)
            val renderer = Hsp3CanvasRenderer(openAsset = assets::open)
            val vm = Hsp3VirtualMachine(renderer, input, fileStorage = assets)
            return Hsp3RuntimeSession(prepared, decodedInstructions, assets, renderer, vm)
        } catch (error: Exception) {
            assets.close()
            throw error
        }
    }

    companion object {
        const val ENGINE_ID = "hsp3-dpmx"
        const val ENGINE_LABEL = "Hot Soup Processor 3.1 (DPMX)"
        const val START_SCRIPT_NAME = "start.ax"
        private const val DEFAULT_MAX_SCRIPT_SIZE_BYTES = 16L * 1024L * 1024L
    }
}

/** Owns the renderer and VM for one Android presentation session. */
class Hsp3RuntimeSession internal constructor(
    val preparedGame: Hsp3PreparedGame,
    val instructions: List<Hsp3Instruction>,
    private val assets: DpmxAssetRepository,
    val renderer: Hsp3CanvasRenderer,
    val virtualMachine: Hsp3VirtualMachine
) : Closeable {
    fun runSlice(instructionBudget: Int = 10_000): Hsp3VmSnapshot =
        virtualMachine.runSlice(instructions, instructionBudget)

    override fun close() {
        renderer.close()
        assets.close()
    }
}

/**
 * HSP3 token type and graphics command IDs observed in OpenHSP's dispatcher.
 * Values are identifiers only; parameter decoding is deliberately separate.
 */
object Hsp3CommandIds {
    const val TYPE_EXTCMD = 9
    const val TYPE_PROGCMD = 15

    const val MES = 0x0F
    const val POS = 0x11
    const val FONT = 0x14
    const val PICLOAD = 0x17
    const val COLOR = 0x18
    const val REDRAW = 0x1B
    const val GSEL = 0x1D
    const val GCOPY = 0x1E
    const val GZOOM = 0x1F
    const val GMODE = 0x20
    const val BUFFER = 0x29
    const val SCREEN = 0x2A
    const val BOXF = 0x31
    const val STICK = 0x34
    const val CELLLOAD = 0x3C
    const val CELDIV = 0x3D
    const val CELPUT = 0x3E
    const val GROTATE = 0x36
}

private class ByteArrayRandomAccessSource(
    private val bytes: ByteArray
) : ReadOnlyRandomAccessSource {
    private var closed = false

    override val sizeBytes: Long
        get() = bytes.size.toLong()

    override fun readAt(
        position: Long,
        target: ByteArray,
        targetOffset: Int,
        length: Int
    ): Int {
        check(!closed) { "The in-memory HSP source is closed." }
        require(position >= 0L)
        require(targetOffset >= 0 && targetOffset <= target.size)
        require(length >= 0 && length <= target.size - targetOffset)
        if (length == 0) return 0
        if (position >= bytes.size.toLong()) return -1
        val count = minOf(length.toLong(), bytes.size.toLong() - position).toInt()
        bytes.copyInto(
            destination = target,
            destinationOffset = targetOffset,
            startIndex = position.toInt(),
            endIndex = position.toInt() + count
        )
        return count
    }

    override fun close() {
        closed = true
    }
}
