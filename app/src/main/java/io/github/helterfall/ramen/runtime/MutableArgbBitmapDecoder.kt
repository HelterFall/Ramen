package io.github.helterfall.ramen.runtime

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.IOException
import java.io.InputStream

/**
 * Centralizes the mutable software-bitmap contract used by Canvas render targets.
 *
 * BitmapFactory may ignore a preferred config or return a non-mutable bitmap for
 * some encoded formats. Every decoded bitmap is normalized before it leaves here.
 */
internal object MutableArgbBitmapDecoder {
    fun decodeStream(
        input: InputStream,
        options: BitmapFactory.Options = mutableOptions()
    ): Bitmap? {
        val configured = options.apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeStream(input, null, configured) ?: return null
        return decoded.ensureMutableArgb8888()
    }

    fun decodeByteArray(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size,
        options: BitmapFactory.Options = mutableOptions()
    ): Bitmap? {
        val configured = options.apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, offset, length, configured) ?: return null
        return decoded.ensureMutableArgb8888()
    }

    fun create(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).ensureMutableArgb8888()

    private fun Bitmap.ensureMutableArgb8888(): Bitmap {
        if (isMutable && config == Bitmap.Config.ARGB_8888) return this
        val mutableCopy = try {
            copy(Bitmap.Config.ARGB_8888, true)
        } catch (failure: RuntimeException) {
            throw IOException("Could not convert the decoded image to mutable ARGB_8888.", failure)
        } ?: throw IOException("Could not allocate a mutable ARGB_8888 copy of the decoded image.")

        if (!isRecycled && mutableCopy !== this) recycle()
        return mutableCopy
    }

    private fun mutableOptions(): BitmapFactory.Options =
        BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
}
