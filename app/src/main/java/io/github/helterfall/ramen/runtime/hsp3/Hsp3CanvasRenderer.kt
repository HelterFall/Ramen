package io.github.helterfall.ramen.runtime.hsp3

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import kotlin.math.min

interface Hsp3Graphics : Closeable {
    fun screen(id: Int, width: Int, height: Int)
    fun buffer(id: Int, width: Int, height: Int)
    fun gsel(id: Int)
    fun picload(name: String)
    fun gcopy(sourceId: Int, sourceX: Int, sourceY: Int, width: Int, height: Int, destX: Int, destY: Int)
    fun boxf(x1: Int, y1: Int, x2: Int, y2: Int)
    fun mes(value: String)
    fun pos(x: Int, y: Int)
    fun color(red: Int, green: Int, blue: Int)
    fun font(name: String, size: Int, style: Int)
    fun gmode(mode: Int, alpha: Int = 255)
    fun redraw(enabled: Boolean)
}

/** Android Canvas renderer for the first HSP3 2D drawing subset. */
class Hsp3CanvasRenderer(
    private val openAsset: (String) -> InputStream?,
    private val logicalWidth: Int = DEFAULT_WIDTH,
    private val logicalHeight: Int = DEFAULT_HEIGHT
) : Hsp3Graphics {
    private data class Surface(val bitmap: Bitmap, val canvas: Canvas)

    private val surfaces = linkedMapOf<Int, Surface>()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        color = Color.WHITE
    }
    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = DEFAULT_FONT_SIZE.toFloat()
    }
    private var activeId = 0
    private var cursorX = 0
    private var cursorY = 0
    private var currentColor = Color.WHITE
    private var blendMode = 0
    private var blendAlpha = 255
    private var shouldPresent = true
    private var closed = false

    init {
        validateDimensions(logicalWidth, logicalHeight)
        createSurface(0, logicalWidth, logicalHeight)
    }

    override fun screen(id: Int, width: Int, height: Int) {
        checkOpen()
        require(id == 0) { "HSP screen must use window ID 0." }
        createSurface(id, width, height)
        activeId = id
    }

    override fun buffer(id: Int, width: Int, height: Int) {
        checkOpen()
        require(id > 0) { "HSP buffer IDs must be greater than zero." }
        createSurface(id, width, height)
    }

    override fun gsel(id: Int) {
        checkOpen()
        if (!surfaces.containsKey(id)) throw IOException("HSP window/buffer $id has not been created.")
        activeId = id
    }

    override fun picload(name: String) {
        checkOpen()
        if (name.isBlank() || name.contains('/') || name.contains('\\') || name == "." || name == "..") {
            throw IOException("Invalid HSP asset name.")
        }
        val stream = openAsset(name) ?: throw IOException("HSP asset '$name' was not found.")
        val bitmap = stream.use { BitmapFactory.decodeStream(it) }
            ?: throw IOException("HSP asset '$name' could not be decoded as a bitmap.")
        val old = surfaces.put(activeId, Surface(bitmap, Canvas(bitmap)))
        old?.bitmap?.recycle()
        cursorX = 0
        cursorY = 0
    }

    override fun gcopy(
        sourceId: Int,
        sourceX: Int,
        sourceY: Int,
        width: Int,
        height: Int,
        destX: Int,
        destY: Int
    ) {
        checkOpen()
        require(width >= 0 && height >= 0) { "gcopy dimensions must not be negative." }
        val source = surfaces[sourceId]
            ?: throw IOException("HSP source buffer $sourceId has not been created.")
        val destination = activeSurface()
        if (width == 0 || height == 0) return

        val sourceRect = Rect(sourceX, sourceY, sourceX + width, sourceY + height)
        val bitmap = if (blendMode == COLOR_KEY_BLACK) makeBlackTransparent(source.bitmap) else source.bitmap
        bitmapPaint.color = Color.WHITE
        bitmapPaint.alpha = if (blendMode == ALPHA_BLEND || blendMode == ALPHA_BLEND_ALT) blendAlpha else 255
        destination.canvas.drawBitmap(
            bitmap,
            sourceRect,
            Rect(destX, destY, destX + width, destY + height),
            bitmapPaint
        )
        if (bitmap !== source.bitmap) bitmap.recycle()
        bitmapPaint.alpha = 255
    }

    override fun boxf(x1: Int, y1: Int, x2: Int, y2: Int) {
        checkOpen()
        shapePaint.color = currentColor
        activeSurface().canvas.drawRect(
            RectF(
                minOf(x1, x2).toFloat(),
                minOf(y1, y2).toFloat(),
                maxOf(x1, x2).toFloat() + 1f,
                maxOf(y1, y2).toFloat() + 1f
            ),
            shapePaint
        )
    }

    override fun mes(value: String) {
        checkOpen()
        textPaint.color = currentColor
        activeSurface().canvas.drawText(value, cursorX.toFloat(), cursorY + textPaint.textSize, textPaint)
        cursorX += textPaint.measureText(value).toInt()
    }

    override fun pos(x: Int, y: Int) {
        cursorX = x
        cursorY = y
    }

    override fun color(red: Int, green: Int, blue: Int) {
        currentColor = Color.rgb(red.coerceIn(0, 255), green.coerceIn(0, 255), blue.coerceIn(0, 255))
    }

    override fun font(name: String, size: Int, style: Int) {
        require(size > 0) { "HSP font size must be positive." }
        val typefaceStyle = when (style and FONT_STYLE_MASK) {
            FONT_STYLE_BOLD -> Typeface.BOLD
            FONT_STYLE_ITALIC -> Typeface.ITALIC
            FONT_STYLE_BOLD_ITALIC -> Typeface.BOLD_ITALIC
            else -> Typeface.NORMAL
        }
        textPaint.typeface = Typeface.create(name.takeIf { it.isNotBlank() }, typefaceStyle)
        textPaint.textSize = size.toFloat()
    }

    override fun gmode(mode: Int, alpha: Int) {
        require(mode in SUPPORTED_GMODE) {
            "HSP gmode $mode is not supported by the Canvas renderer."
        }
        blendMode = mode
        blendAlpha = alpha.coerceIn(0, 255)
    }

    override fun redraw(enabled: Boolean) {
        shouldPresent = enabled
    }

    /** Draws the selected HSP target with aspect-preserving letterboxing. */
    fun draw(target: Canvas) {
        checkOpen()
        if (!shouldPresent) return
        val bitmap = activeSurface().bitmap
        val width = target.width.toFloat()
        val height = target.height.toFloat()
        if (width <= 0f || height <= 0f) return

        val scale = min(width / bitmap.width.toFloat(), height / bitmap.height.toFloat())
        val drawWidth = bitmap.width * scale
        val drawHeight = bitmap.height * scale
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) / 2f
        val saveCount = target.save()
        try {
            target.drawColor(Color.BLACK)
            bitmapPaint.color = Color.WHITE
            bitmapPaint.alpha = 255
            target.drawBitmap(
                bitmap,
                null,
                RectF(left, top, left + drawWidth, top + drawHeight),
                bitmapPaint
            )
        } finally {
            target.restoreToCount(saveCount)
        }
    }

    private fun createSurface(id: Int, width: Int, height: Int) {
        validateDimensions(width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val old = surfaces.put(id, Surface(bitmap, Canvas(bitmap)))
        old?.bitmap?.recycle()
    }

    private fun activeSurface(): Surface =
        surfaces[activeId] ?: throw IOException("The selected HSP surface is unavailable.")

    private fun makeBlackTransparent(source: Bitmap): Bitmap {
        val mutable = source.copy(Bitmap.Config.ARGB_8888, true)
            ?: throw IOException("Could not create a transparent copy of an HSP source bitmap.")
        val pixels = IntArray(mutable.width * mutable.height)
        mutable.getPixels(pixels, 0, mutable.width, 0, 0, mutable.width, mutable.height)
        for (index in pixels.indices) {
            if ((pixels[index] and 0x00FFFFFF) == 0) pixels[index] = 0
        }
        mutable.setPixels(pixels, 0, mutable.width, 0, 0, mutable.width, mutable.height)
        return mutable
    }

    private fun validateDimensions(width: Int, height: Int) {
        require(width in 1..MAX_SURFACE_DIMENSION && height in 1..MAX_SURFACE_DIMENSION)
        require(width.toLong() * height.toLong() <= MAX_SURFACE_PIXELS) {
            "HSP surface exceeds the configured pixel limit."
        }
    }

    private fun checkOpen() {
        check(!closed) { "The HSP Canvas renderer is closed." }
    }

    override fun close() {
        if (closed) return
        closed = true
        surfaces.values.forEach { surface ->
            if (!surface.bitmap.isRecycled) surface.bitmap.recycle()
        }
        surfaces.clear()
    }

    companion object {
        const val DEFAULT_WIDTH = 800
        const val DEFAULT_HEIGHT = 600
        private const val DEFAULT_FONT_SIZE = 16
        private const val MAX_SURFACE_DIMENSION = 8192
        private const val MAX_SURFACE_PIXELS = 16L * 1024L * 1024L
        private const val FONT_STYLE_MASK = 0x03
        private const val FONT_STYLE_BOLD = 0x01
        private const val FONT_STYLE_ITALIC = 0x02
        private const val FONT_STYLE_BOLD_ITALIC = FONT_STYLE_BOLD or FONT_STYLE_ITALIC
        private const val COLOR_KEY_BLACK = 2
        private const val ALPHA_BLEND = 3
        private const val ALPHA_BLEND_ALT = 5
        private val SUPPORTED_GMODE = setOf(0, COLOR_KEY_BLACK, ALPHA_BLEND, ALPHA_BLEND_ALT)
    }
}
