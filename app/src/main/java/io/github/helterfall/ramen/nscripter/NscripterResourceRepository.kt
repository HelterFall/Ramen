package io.github.helterfall.ramen.nscripter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.Closeable
import java.io.File
import java.util.Locale

class NscripterResourceRepository(
    private val gameDirectory: File,
    private val cacheDirectory: File,
    private val archiveReader: NscripterArchiveReader = NscripterArchiveReader()
) : Closeable {
    private data class IndexedArchive(val file: File, val entries: Map<String, NscripterArchiveEntry>)

    private val archives: List<IndexedArchive> = gameDirectory.listFiles()
        .orEmpty()
        .filter { it.isFile && (it.name.equals("arc.nsa", true) ||
            Regex("^arc[1-9]\\.nsa$", RegexOption.IGNORE_CASE).matches(it.name)) }
        .sortedWith(compareBy<File> { if (it.name.equals("arc.nsa", true)) 0 else 1 }.thenBy { it.name })
        .mapNotNull { file ->
            runCatching {
                val index = archiveReader.readIndex(file)
                IndexedArchive(file, index.entries.associateBy { normalize(it.name) })
            }.getOrNull()
        }

    fun readAsset(name: String): ByteArray? {
        val normalized = normalize(name)
        if (normalized.isBlank() || normalized.startsWith("../") || normalized == "..") return null
        val root = gameDirectory.canonicalFile
        val loose = File(gameDirectory, normalized).canonicalFile
        if (loose.toPath().startsWith(root.toPath()) && loose.isFile) return loose.readBytes()

        for (archive in archives) {
            val entry = archive.entries[normalized] ?: archive.entries.entries
                .firstOrNull { it.key.substringAfterLast('/') == normalized.substringAfterLast('/') }
                ?.value ?: continue
            return archiveReader.readEntry(archive.file, entry)
        }
        return null
    }

    fun loadBitmap(
        imageName: String,
        transparency: String = "a",
        maskName: String? = null,
        colorKey: String? = null
    ): Bitmap? {
        val bytes = readAsset(imageName) ?: return null
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val argb = decoded.copy(Bitmap.Config.ARGB_8888, true) ?: decoded
        if (argb !== decoded) decoded.recycle()
        return when (transparency.lowercase(Locale.ROOT).removePrefix(":")) {
            "l" -> applyEmbeddedHalfMask(argb, true)
            "r" -> applyEmbeddedHalfMask(argb, false)
            "m" -> applyExternalMask(argb, maskName)
            "c" -> applyColorKey(argb, colorKey)
            else -> argb
        }
    }

    fun materializeAudio(name: String): File? {
        val data = readAsset(name) ?: return null
        val safeName = normalize(name).substringAfterLast('/').ifBlank { "track.bin" }
        if (safeName.substringAfterLast('.', "").lowercase(Locale.ROOT) !in AUDIO_EXTENSIONS) return null
        val targetDirectory = File(cacheDirectory, "audio").apply { mkdirs() }
        val target = File(targetDirectory, safeName)
        if (!target.isFile || target.length() != data.size.toLong()) target.writeBytes(data)
        return target
    }

    private fun applyEmbeddedHalfMask(source: Bitmap, pictureOnLeft: Boolean): Bitmap {
        if (source.width < 2) return source
        val width = source.width / 2
        val pictureX = if (pictureOnLeft) 0 else width
        val maskX = if (pictureOnLeft) width else 0
        val output = Bitmap.createBitmap(width, source.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * source.height)
        for (y in 0 until source.height) for (x in 0 until width) {
            val color = source.getPixel(pictureX + x, y)
            val mask = source.getPixel(maskX + x, y)
            val alpha = (Color.red(mask) * 299 + Color.green(mask) * 587 + Color.blue(mask) * 114) / 1000
            pixels[y * width + x] = (color and 0x00FFFFFF) or (alpha shl 24)
        }
        output.setPixels(pixels, 0, width, 0, 0, width, source.height)
        source.recycle()
        return output
    }

    private fun applyExternalMask(source: Bitmap, maskName: String?): Bitmap {
        if (maskName.isNullOrBlank()) return source
        val bytes = readAsset(maskName) ?: return source
        val mask = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return source
        if (mask.width != source.width || mask.height != source.height) {
            mask.recycle()
            return source
        }
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(source.width * source.height)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            val color = source.getPixel(x, y)
            val m = mask.getPixel(x, y)
            val alpha = (Color.red(m) * 299 + Color.green(m) * 587 + Color.blue(m) * 114) / 1000
            pixels[y * source.width + x] = (color and 0x00FFFFFF) or (alpha shl 24)
        }
        output.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        source.recycle()
        mask.recycle()
        return output
    }

    private fun applyColorKey(source: Bitmap, colorKey: String?): Bitmap {
        val transparent = runCatching {
            if (colorKey.isNullOrBlank()) source.getPixel(0, 0) else Color.parseColor(colorKey)
        }.getOrElse { source.getPixel(0, 0) } and 0x00FFFFFF
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        for (index in pixels.indices) if ((pixels[index] and 0x00FFFFFF) == transparent) {
            pixels[index] = pixels[index] and 0x00FFFFFF
        }
        source.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        return source
    }

    private fun normalize(name: String): String =
        name.replace('\\', '/').trim().removePrefix("./").lowercase(Locale.ROOT)

    override fun close() = Unit

    companion object {
        private val AUDIO_EXTENSIONS = setOf("ogg", "wav", "mp3")
    }
}
