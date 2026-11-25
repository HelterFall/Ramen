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
) : Closeable {
    fun open(name: String): InputStream? {
        val entry = container.findEntry(name) ?: return null
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

    override fun close() = Unit
}

/**
 * Ramen-side HSP3 adapter boundary for PE32+DPMX executables.
 *
 * prepare() identifies and decrypts start.ax in a bounded in-memory script
 * buffer, then hands that bytecode to Hsp3BytecodeLoader. A platform-neutral
 * instruction decoder remains injectable because the HSP expression and
 * command-parameter grammar is not represented by the token envelope alone.
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
            val container = containerParser.parse(source)
            val entry = container.findEntry(START_SCRIPT_NAME)
                ?: throw IOException("The DPMX container does not contain start.ax.")
            if (entry.sizeBytes > maxScriptSizeBytes) {
                throw IOException("start.ax exceeds the configured decoded-script size limit.")
            }
            val resolution = keyResolver.resolve(source, container, entry)
                ?: throw IOException("A verified DPMX decryption key for start.ax was not found.")

            val output = ByteArrayOutputStream(entry.sizeBytes.toInt())
            DpmxDecryptor.decrypt(source, entry, resolution.key, output)
            val plainBytes = output.toByteArray()
            val program = bytecodeLoader.load(ByteArrayRandomAccessSource(plainBytes))

            if (program.header.libraryInfoSizeBytes != 0L ||
                program.header.functionInfoSizeBytes != 0L ||
                program.header.moduleInfoSizeBytes != 0L ||
                program.header.functionInfo2SizeBytes != 0L ||
                program.header.hasExternalPluginData
            ) {
                throw IOException("This HSP3 runtime profile does not load external DLL/HPI plugin segments.")
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
        instructionDecoder: Hsp3InstructionDecoder,
        input: Hsp3InputProvider = Hsp3InputProvider { 0 }
    ): Hsp3RuntimeSession {
        val prepared = prepare(executable)
        val assets = DpmxAssetRepository(prepared.executable, prepared.container, keyResolver)
        try {
            val decodedInstructions = instructionDecoder.decode(prepared.program)
            val renderer = Hsp3CanvasRenderer(openAsset = assets::open)
            val vm = Hsp3VirtualMachine(renderer, input)
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
    const val GROTATE = 0x36
    const val CELPUT = 0x3E
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
