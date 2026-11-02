package io.github.helterfall.ramen.importer

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Base64
import io.github.helterfall.ramen.storage.StorageGuardedOutputStream
import io.github.helterfall.ramen.storage.StorageSpaceGuard

/**
 * Moves supported base64 image/audio data URIs out of a standalone HTML document.
 *
 * Large data URIs embedded in CSS or script data can exceed WebView's practical resource
 * limits and make an otherwise valid page render as a blank screen. Extracted files stay
 * inside the imported game's private directory and are served only through WebViewAssetLoader.
 */
class HtmlInlineResourceExtractor(
    private val maxResourceBytes: Long = Long.MAX_VALUE,
    private val maxTotalBytes: Long = Long.MAX_VALUE
) {
    init {
        require(maxResourceBytes > 0L)
        require(maxTotalBytes > 0L)
    }

    fun extract(htmlFile: File, gameDirectory: File): Result {
        if (!htmlFile.isFile || !gameDirectory.isDirectory) {
            throw IOException("HTML-файл или временная папка игры недоступны.")
        }

        val rewrittenFile = File(gameDirectory, ".index.html.rewritten")
        val assetsDirectory = File(gameDirectory, "assets")
        var extractedCount = 0
        var extractedBytes = 0L

        try {
            BufferedReader(
                InputStreamReader(FileInputStream(htmlFile), StandardCharsets.UTF_8)
            ).use { reader ->
                BufferedWriter(
                    OutputStreamWriter(
                        StorageGuardedOutputStream(
                            delegate = FileOutputStream(rewrittenFile),
                            guard = StorageSpaceGuard(gameDirectory)
                        ),
                        StandardCharsets.UTF_8
                    )
                ).use { writer ->
                    var insideString = false
                    var stringQuote = '\u0000'
                    var escaped = false

                    while (true) {
                        val value = reader.read()
                        if (value < 0) break
                        val character = value.toChar()

                        if (!insideString && (character == '"' || character == '\'')) {
                            val nextIndex = extractedCount + 1
                            val extracted = extractDataUriIfSupported(
                                reader = reader,
                                quote = character,
                                assetsDirectory = assetsDirectory,
                                index = nextIndex,
                                alreadyExtractedBytes = extractedBytes
                            )
                            if (extracted != null) {
                                writer.write(character.code)
                                writer.write(extracted.relativePath)
                                writer.write(character.code)
                                extractedCount += 1
                                extractedBytes += extracted.bytes
                                continue
                            }

                            writer.write(character.code)
                            insideString = true
                            stringQuote = character
                            escaped = false
                            continue
                        }

                        writer.write(character.code)
                        if (insideString) {
                            when {
                                escaped -> escaped = false
                                character == '\\' -> escaped = true
                                character == stringQuote -> insideString = false
                            }
                        }
                    }
                }
            }

            if (extractedCount == 0) {
                rewrittenFile.delete()
                return Result(extractedCount = 0, extractedBytes = 0L)
            }

            val originalFile = File(gameDirectory, ".index.html.original")
            if (!htmlFile.renameTo(originalFile)) {
                throw IOException("Не удалось подготовить HTML-файл для обработки.")
            }
            if (!rewrittenFile.renameTo(htmlFile)) {
                originalFile.renameTo(htmlFile)
                throw IOException("Не удалось сохранить HTML-файл со встроенными ресурсами.")
            }
            originalFile.delete()

            return Result(extractedCount, extractedBytes)
        } catch (error: Exception) {
            rewrittenFile.delete()
            if (error is IOException) throw error
            throw IOException("Не удалось извлечь встроенные ресурсы HTML-файла.", error)
        }
    }

    private fun extractDataUriIfSupported(
        reader: BufferedReader,
        quote: Char,
        assetsDirectory: File,
        index: Int,
        alreadyExtractedBytes: Long
    ): ExtractedAsset? {
        reader.mark(MAX_HEADER_LENGTH + 8)
        val header = StringBuilder("data:")

        repeat(5) {
            val value = reader.read()
            if (value < 0 || value.toChar() != "data:"[it]) {
                reader.reset()
                return null
            }
        }

        var headerComplete = false
        while (header.length <= MAX_HEADER_LENGTH) {
            val value = reader.read()
            if (value < 0 || value.toChar() == quote) {
                reader.reset()
                return null
            }
            val character = value.toChar()
            if (character == ',') {
                headerComplete = true
                break
            }
            header.append(character)
        }

        if (!headerComplete) {
            reader.reset()
            return null
        }

        val metadata = header.toString().removePrefix("data:")
        val mimeType = metadata.substringBefore(';').lowercase()
        val isBase64 = metadata.split(';').drop(1).any {
            it.equals("base64", ignoreCase = true)
        }
        val extension = SUPPORTED_MIME_TYPES[mimeType]
        if (!isBase64 || extension == null) {
            reader.reset()
            return null
        }

        if (!assetsDirectory.exists() && !assetsDirectory.mkdirs()) {
            throw IOException("Не удалось создать папку встроенных ресурсов.")
        }

        val fileName = "inline-%04d.%s".format(index, extension)
        val targetFile = File(assetsDirectory, fileName)
        val encodedInput = QuotedBase64InputStream(reader, quote, Long.MAX_VALUE)
        var writtenBytes = 0L

        try {
            val decodedInput = Base64.getMimeDecoder().wrap(encodedInput)
            StorageGuardedOutputStream(
                delegate = FileOutputStream(targetFile),
                guard = StorageSpaceGuard(assetsDirectory)
            ).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val bytesRead = decodedInput.read(buffer)
                    if (bytesRead < 0) break

                    val chunkSize = bytesRead.toLong()
                    if (
                        chunkSize > maxResourceBytes - writtenBytes ||
                        chunkSize > maxTotalBytes - alreadyExtractedBytes - writtenBytes
                    ) {
                        throw IOException(
                            "Встроенные изображения и аудио превышают безопасный лимит извлечения. " +
                                "Для этого проекта используй импорт папки или ZIP."
                        )
                    }

                    output.write(buffer, 0, bytesRead)
                    writtenBytes += chunkSize
                }
            }
            consumeTrailingBase64(encodedInput)

            if (!encodedInput.closingQuoteConsumed) {
                throw IOException("Встроенный ресурс HTML-файла не завершён.")
            }
            if (writtenBytes == 0L) {
                throw IOException("Встроенный ресурс HTML-файла пуст.")
            }
        } catch (error: Exception) {
            targetFile.delete()
            if (error is IOException) throw error
            throw IOException("Не удалось декодировать встроенный ресурс HTML-файла.", error)
        }

        return ExtractedAsset(
            relativePath = "/game/assets/$fileName",
            bytes = writtenBytes
        )
    }

    private fun consumeTrailingBase64(input: InputStream) {
        // Consume trailing whitespace after base64 padding, through the closing quote.
        while (input.read() >= 0) {
            // QuotedBase64InputStream validates every remaining character.
        }
    }

    private data class ExtractedAsset(
        val relativePath: String,
        val bytes: Long
    )

    data class Result(
        val extractedCount: Int,
        val extractedBytes: Long
    )

    private class QuotedBase64InputStream(
        private val reader: BufferedReader,
        private val closingQuote: Char,
        private val maxEncodedBytes: Long
    ) : InputStream() {
        var closingQuoteConsumed: Boolean = false
            private set
        private var encodedBytes = 0L
        private var paddingSeen = false

        override fun read(): Int {
            if (closingQuoteConsumed) return -1

            val value = reader.read()
            if (value < 0) {
                throw IOException("Встроенный ресурс HTML-файла не закрыт кавычкой.")
            }
            val character = value.toChar()

            if (character == closingQuote) {
                closingQuoteConsumed = true
                return -1
            }

            val isWhitespace = character == ' ' ||
                character.code == 9 || character.code == 13 || character.code == 10
            val isBase64 = character in 'A'..'Z' ||
                character in 'a'..'z' ||
                character in '0'..'9' ||
                character == '+' ||
                character == '/' ||
                character == '='

            if (!isWhitespace && !isBase64) {
                throw IOException("Встроенный ресурс содержит некорректные base64-данные.")
            }
            if (paddingSeen && !isWhitespace && character != '=') {
                throw IOException("Некорректное завершение base64-ресурса.")
            }
            if (character == '=') paddingSeen = true

            encodedBytes += 1L
            if (encodedBytes > maxEncodedBytes) {
                throw IOException("Размер встроенного ресурса превышает безопасный лимит.")
            }
            return value
        }
    }

    companion object {
        private const val MAX_HEADER_LENGTH = 256

        private val SUPPORTED_MIME_TYPES = mapOf(
            "image/png" to "png",
            "image/jpeg" to "jpg",
            "image/jpg" to "jpg",
            "image/gif" to "gif",
            "image/webp" to "webp",
            "audio/mpeg" to "mp3",
            "audio/mp3" to "mp3",
            "audio/ogg" to "ogg",
            "audio/wav" to "wav",
            "audio/x-wav" to "wav",
            "audio/aac" to "aac",
            "audio/mp4" to "m4a"
        )
    }
}
