package io.github.helterfall.ramen.runtime.hsp3

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import io.github.helterfall.ramen.runtime.MutableArgbBitmapDecoder
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import kotlin.math.min

interface Hsp3Graphics : Closeable {
    fun screen(id: Int, width: Int, height: Int)
    fun buffer(id: Int, width: Int, height: Int)
    fun gsel(id: Int)
    fun cls(mode: Int = 0) = Unit
    fun title(value: String) = Unit
    fun width(width: Int, height: Int) = screen(0, width, height)
    fun palette(arguments: List<Hsp3RuntimeValue?>) = Unit
    fun grect(centerX: Int, centerY: Int, angleRadians: Double, width: Int, height: Int) = Unit
    fun picload(name: String)
    fun picload(name: String, mode: Int) = picload(name)
    fun gcopy(sourceId: Int, sourceX: Int, sourceY: Int, width: Int, height: Int, destX: Int, destY: Int)
    fun gzoom(destWidth: Int, destHeight: Int, sourceId: Int, sourceX: Int, sourceY: Int,
              sourceWidth: Int, sourceHeight: Int, quality: Int) {
        val position = currentPosition()
        gcopy(sourceId, sourceX, sourceY, sourceWidth, sourceHeight, position.first, position.second)
    }
    fun grotate(sourceId: Int, sourceX: Int, sourceY: Int, angleRadians: Double,
                destWidth: Int, destHeight: Int) {
        val size = surfaceSize(sourceId)
        val position = currentPosition()
        gcopy(sourceId, sourceX, sourceY, size.first, size.second,
            position.first - destWidth / 2, position.second - destHeight / 2)
    }
    fun celload(name: String, id: Int): Int { picload(name); return id }
    fun celload(name: String, id: Int, mode: Int): Int = celload(name, id)
    fun celdiv(id: Int, cellWidth: Int, cellHeight: Int, baseX: Int = 0, baseY: Int = 0) = Unit
    fun celput(id: Int, cellIndex: Int, zoomX: Double, zoomY: Double, angleRadians: Double) {
        val size = surfaceSize(id)
        val position = currentPosition()
        gcopy(id, 0, 0, size.first, size.second, position.first, position.second)
    }
    fun boxf(x1: Int, y1: Int, x2: Int, y2: Int)
    fun boxf() = boxf(0, 0, Hsp3CanvasRenderer.DEFAULT_WIDTH, Hsp3CanvasRenderer.DEFAULT_HEIGHT)
    fun mes(value: String)
    fun mes(value: String, flags: Int) = mes(value)
    fun pos(x: Int, y: Int)
    fun color(red: Int, green: Int, blue: Int)
    fun font(name: String, size: Int, style: Int)
    fun gmode(mode: Int, alpha: Int = 0)
    fun gmode(mode: Int, copyWidth: Int, copyHeight: Int, alpha: Int) = gmode(mode, alpha)
    fun redraw(enabled: Boolean)
    fun currentPosition(): Pair<Int, Int> = 0 to 0
    fun surfaceSize(id: Int): Pair<Int, Int> =
        Hsp3CanvasRenderer.DEFAULT_WIDTH to Hsp3CanvasRenderer.DEFAULT_HEIGHT
    fun currentSurfaceSize(): Pair<Int, Int> = surfaceSize(0)
    fun copySize(): Pair<Int, Int> = 32 to 32
}

/** Android Canvas renderer for the first HSP3 2D drawing subset. */
class Hsp3CanvasRenderer(
    private val openAsset: (String) -> InputStream?,
    private val logicalWidth: Int = DEFAULT_WIDTH,
    private val logicalHeight: Int = DEFAULT_HEIGHT
) : Hsp3Graphics {
    private data class Surface(
        val bitmap: Bitmap,
        val canvas: Canvas,
        var shouldPresent: Boolean = true,
        var suspendedFrame: Bitmap? = null,
        var cursorX: Int = 0,
        var cursorY: Int = 0,
        var currentColor: Int = Color.WHITE,
        var blendMode: Int = 0,
        var blendAlpha: Int = 0,
        var gmodeCopyWidth: Int = 32,
        var gmodeCopyHeight: Int = 32,
        val textPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = DEFAULT_FONT_SIZE.toFloat()
        }
    ) {
        init {
            check(bitmap.isMutable) { "HSP Canvas surfaces must use mutable bitmaps." }
            check(bitmap.config == Bitmap.Config.ARGB_8888) {
                "HSP Canvas surfaces must use ARGB_8888 bitmaps."
            }
        }
    }
    private data class CellGrid(val width: Int, val height: Int, val baseX: Int, val baseY: Int)

    private val surfaces = linkedMapOf<Int, Surface>()
    private val cellGrids = linkedMapOf<Int, CellGrid>()
    private val preloadedCellIds = linkedMapOf<String, Int>()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        color = Color.WHITE
    }
    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private var activeId = 0
    private var currentTitle = ""
    private var closed = false

    // These fields belong to the active OpenHSP Bmscr. Switching gsel changes
    // the whole graphics context, not just the backing bitmap.
    private val textPaint: Paint
        get() = activeSurface().textPaint
    private var cursorX: Int
        get() = activeSurface().cursorX
        set(value) { activeSurface().cursorX = value }
    private var cursorY: Int
        get() = activeSurface().cursorY
        set(value) { activeSurface().cursorY = value }
    private var currentColor: Int
        get() = activeSurface().currentColor
        set(value) { activeSurface().currentColor = value }
    private var blendMode: Int
        get() = activeSurface().blendMode
        set(value) { activeSurface().blendMode = value }
    private var blendAlpha: Int
        get() = activeSurface().blendAlpha
        set(value) { activeSurface().blendAlpha = value }
    private var gmodeCopyWidth: Int
        get() = activeSurface().gmodeCopyWidth
        set(value) { activeSurface().gmodeCopyWidth = value }
    private var gmodeCopyHeight: Int
        get() = activeSurface().gmodeCopyHeight
        set(value) { activeSurface().gmodeCopyHeight = value }

    init {
        validateDimensions(logicalWidth, logicalHeight)
        createSurface(0, logicalWidth, logicalHeight)
    }

    @Synchronized
    override fun screen(id: Int, width: Int, height: Int) {
        checkOpen()
        require(id == 0) { "HSP screen must use window ID 0." }
        createSurface(id, width, height)
        activeId = id
    }

    @Synchronized
    override fun buffer(id: Int, width: Int, height: Int) {
        checkOpen()
        require(id > 0) { "HSP buffer IDs must be greater than zero." }
        createSurface(id, width, height)
        // OpenHSP's TYPE_EXTCMD buffer case makes the new Bmscr current.
        activeId = id
    }

    @Synchronized
    override fun cls(mode: Int) {
        checkOpen()
        activeSurface().canvas.drawColor(Color.BLACK, PorterDuff.Mode.SRC)
        cursorX = 0
        cursorY = 0
    }

    @Synchronized
    override fun title(value: String) {
        checkOpen()
        currentTitle = value
    }

    @Synchronized
    override fun width(width: Int, height: Int) {
        checkOpen()
        screen(0, width, height)
    }

    @Synchronized
    override fun palette(arguments: List<Hsp3RuntimeValue?>) {
        // ARGB_8888 targets are true-colour. Accept legacy palette commands safely.
        checkOpen()
    }

    @Synchronized
    override fun grect(
        centerX: Int,
        centerY: Int,
        angleRadians: Double,
        width: Int,
        height: Int
    ) {
        checkOpen()
        if (width <= 0 || height <= 0) return
        val canvas = activeSurface().canvas
        shapePaint.color = currentColor
        val saved = canvas.save()
        try {
            if (angleRadians != 0.0) {
                canvas.rotate(
                    Math.toDegrees(angleRadians).toFloat(),
                    centerX.toFloat(),
                    centerY.toFloat()
                )
            }
            canvas.drawRect(
                RectF(
                    centerX - width / 2f,
                    centerY - height / 2f,
                    centerX + width / 2f,
                    centerY + height / 2f
                ),
                shapePaint
            )
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    @Synchronized
    override fun gsel(id: Int) {
        checkOpen()
        if (!surfaces.containsKey(id)) throw IOException("HSP window/buffer $id has not been created.")
        activeId = id
    }

    override fun picload(name: String) = picload(name, 0)

    override fun picload(name: String, mode: Int) {
        validateAssetName(name, "picload")
        val targetId = synchronized(this) {
            checkOpen()
            activeId
        }
        val stream = openAsset(name) ?: throw IOException("HSP asset '" + name + "' was not found.")
        val bitmap = stream.use { MutableArgbBitmapDecoder.decodeByteArray(readBoundedAsset(it)) }
            ?: throw IOException("HSP asset '" + name + "' could not be decoded as a bitmap.")

        var resized: Bitmap? = null
        try {
            if (mode == 2) {
                resized = MutableArgbBitmapDecoder.create(bitmap.width, bitmap.height)
                Canvas(resized!!).apply {
                    drawColor(Color.BLACK)
                    drawBitmap(bitmap, 0f, 0f, null)
                }
            } else if (mode !in 0..1) {
                throw IOException("Unsupported HSP picload mode " + mode + ".")
            }

            synchronized(this) {
                checkOpen()
                if (activeId != targetId) {
                    throw IOException("The selected HSP surface changed while picload was reading its asset.")
                }
                when (mode) {
                    0 -> {
                        val old = surfaces[targetId]
                        val replacement = old?.copy(bitmap = bitmap, canvas = Canvas(bitmap))
                            ?: Surface(bitmap, Canvas(bitmap))
                        surfaces[targetId] = replacement
                        old?.bitmap?.recycle()
                        cellGrids.remove(targetId)
                        replacement.cursorX = 0
                        replacement.cursorY = 0
                    }
                    1 -> {
                        val destination = activeSurface()
                        destination.canvas.drawBitmap(bitmap, cursorX.toFloat(), cursorY.toFloat(), null)
                    }
                    2 -> {
                        val replacement = resized!!
                        val old = surfaces[targetId]
                        val replacementSurface = old?.copy(
                            bitmap = replacement, canvas = Canvas(replacement)
                        ) ?: Surface(replacement, Canvas(replacement))
                        surfaces[targetId] = replacementSurface
                        old?.bitmap?.recycle()
                        cellGrids.remove(targetId)
                        replacementSurface.cursorX = 0
                        replacementSurface.cursorY = 0
                    }
                }
            }
        } catch (error: Exception) {
            if (!bitmap.isRecycled) bitmap.recycle()
            resized?.let { if (!it.isRecycled) it.recycle() }
            throw error
        } finally {
            if (mode != 0 && !bitmap.isRecycled) bitmap.recycle()
            if (mode != 2) resized?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    override fun celload(name: String, id: Int): Int = celload(name, id, 0)

    override fun celload(name: String, id: Int, mode: Int): Int {
        validateAssetName(name, "celload")
        if (mode != 0) throw IOException("HSP Canvas renderer only supports full-color celload mode 0.")
        val cacheKey = name.lowercase(Locale.ROOT)
        synchronized(this) {
            checkOpen()
            if (id == -2) preloadedCellIds[cacheKey]?.let { cached ->
                if (surfaces.containsKey(cached)) return cached
            }
        }

        val stream = openAsset(name) ?: throw IOException("HSP celload asset was not found: " + name)
        val bitmap = stream.use { MutableArgbBitmapDecoder.decodeByteArray(readBoundedAsset(it)) }
            ?: throw IOException("HSP celload asset could not be decoded: " + name)
        try {
            return synchronized(this) {
                checkOpen()
                if (id == -2) preloadedCellIds[cacheKey]?.let { cached ->
                    if (surfaces.containsKey(cached)) {
                        bitmap.recycle()
                        return@synchronized cached
                    }
                }
                val targetId = if (id >= 0) id else generateSequence(1) { it + 1 }
                    .first { !surfaces.containsKey(it) }
                val old = surfaces.put(targetId, Surface(bitmap, Canvas(bitmap)))
                old?.bitmap?.recycle()
                old?.suspendedFrame?.let { if (!it.isRecycled) it.recycle() }
                cellGrids.remove(targetId)
                preloadedCellIds.entries.removeAll { it.value == targetId }
                if (id == -2) preloadedCellIds[cacheKey] = targetId
                targetId
            }
        } catch (error: Exception) {
            if (!bitmap.isRecycled) bitmap.recycle()
            throw error
        }
    }

    @Synchronized
    override fun celdiv(id: Int, cellWidth: Int, cellHeight: Int, baseX: Int, baseY: Int) {
        checkOpen()
        if (!surfaces.containsKey(id)) throw IOException("HSP celdiv source surface " + id + " does not exist.")
        require(cellWidth > 0 && cellHeight > 0) { "HSP celdiv cell dimensions must be positive." }
        if (baseX !in 0..cellWidth || baseY !in 0..cellHeight) {
            throw IOException("HSP celdiv base point must be inside the cell.")
        }
        cellGrids[id] = CellGrid(cellWidth, cellHeight, baseX, baseY)
    }

    @Synchronized
    override fun celput(
        id: Int,
        cellIndex: Int,
        zoomX: Double,
        zoomY: Double,
        angleRadians: Double
    ) {
        checkOpen()
        if (cellIndex < 0) throw IOException("HSP celput cell index cannot be negative.")
        val source = surfaces[id] ?: throw IOException("HSP celput source surface " + id + " does not exist.")
        val grid = cellGrids[id] ?: CellGrid(source.bitmap.width, source.bitmap.height, 0, 0)
        val columns = (source.bitmap.width + grid.width - 1) / grid.width
        val rows = (source.bitmap.height + grid.height - 1) / grid.height
        if (cellIndex >= columns * rows) throw IOException("HSP celput cell index is outside the divided image.")
        val sourceX = (cellIndex % columns) * grid.width
        val sourceY = (cellIndex / columns) * grid.height
        val sourceWidth = minOf(grid.width, source.bitmap.width - sourceX)
        val sourceHeight = minOf(grid.height, source.bitmap.height - sourceY)
        val destWidth = sourceWidth * zoomX
        val destHeight = sourceHeight * zoomY
        if (destWidth <= 0.0 || destHeight <= 0.0) return
        val destinationLeft = cursorX - (grid.baseX * zoomX).toFloat()
        val destinationTop = cursorY - (grid.baseY * zoomY).toFloat()
        val destination = RectF(
            destinationLeft, destinationTop,
            destinationLeft + destWidth.toFloat(), destinationTop + destHeight.toFloat()
        )
        drawRegion(
            source,
            Rect(sourceX, sourceY, sourceX + sourceWidth, sourceY + sourceHeight),
            destination,
            filter = true,
            rotationDegrees = Math.toDegrees(angleRadians).toFloat(),
            rotationPivotX = cursorX.toFloat(),
            rotationPivotY = cursorY.toFloat()
        )
        cursorX += destWidth.toInt()
    }

    @Synchronized
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
        drawRegion(
            source,
            sourceRect,
            RectF(destX.toFloat(), destY.toFloat(), (destX + width).toFloat(), (destY + height).toFloat())
        )
    }

    @Synchronized
    override fun gzoom(
        destWidth: Int,
        destHeight: Int,
        sourceId: Int,
        sourceX: Int,
        sourceY: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        quality: Int
    ) {
        checkOpen()
        require(destWidth >= 0 && destHeight >= 0 && sourceWidth >= 0 && sourceHeight >= 0)
        val source = surfaces[sourceId] ?: throw IOException("HSP gzoom source surface " + sourceId + " does not exist.")
        if (destWidth == 0 || destHeight == 0 || sourceWidth == 0 || sourceHeight == 0) return
        val position = currentPosition()
        drawRegion(
            source,
            Rect(sourceX, sourceY, sourceX + sourceWidth, sourceY + sourceHeight),
            RectF(position.first.toFloat(), position.second.toFloat(),
                (position.first + destWidth).toFloat(), (position.second + destHeight).toFloat()),
            filter = quality != 0,
            applyGmode = false
        )
    }

    @Synchronized
    override fun grotate(
        sourceId: Int,
        sourceX: Int,
        sourceY: Int,
        angleRadians: Double,
        destWidth: Int,
        destHeight: Int
    ) {
        checkOpen()
        require(destWidth >= 0 && destHeight >= 0)
        val source = surfaces[sourceId] ?: throw IOException("HSP grotate source surface " + sourceId + " does not exist.")
        val sourceWidth = gmodeCopyWidth
        val sourceHeight = gmodeCopyHeight
        if (destWidth == 0 || destHeight == 0 || sourceWidth == 0 || sourceHeight == 0) return
        val position = currentPosition()
        val left = position.first - destWidth / 2f
        val top = position.second - destHeight / 2f
        drawRegion(
            source,
            Rect(sourceX, sourceY, sourceX + sourceWidth, sourceY + sourceHeight),
            RectF(left, top, left + destWidth, top + destHeight),
            rotationDegrees = Math.toDegrees(angleRadians).toFloat()
        )
    }

    @Synchronized
    override fun boxf(x1: Int, y1: Int, x2: Int, y2: Int) {
        checkOpen()
        val bitmap = activeSurface().bitmap
        val left = minOf(x1, x2).coerceAtLeast(0)
        val top = minOf(y1, y2).coerceAtLeast(0)
        val right = (maxOf(x1, x2).toLong() + 1L)
            .coerceAtMost(bitmap.width.toLong()).toInt()
        val bottom = (maxOf(y1, y2).toLong() + 1L)
            .coerceAtMost(bitmap.height.toLong()).toInt()
        if (right <= left || bottom <= top) return

        // HSP boxf uses inclusive integer coordinates. Write exact pixels rather
        // than a filtered Canvas primitive so one-pixel edges are never softened.
        val scanline = IntArray(right - left) { currentColor }
        for (row in top until bottom) {
            bitmap.setPixels(scanline, 0, scanline.size, left, row, scanline.size, 1)
        }
    }

    @Synchronized
    override fun mes(value: String) = mes(value, 0)

    @Synchronized
    override fun mes(value: String, flags: Int) {
        checkOpen()
        val canvas = activeSurface().canvas
        // OpenHSP treats CRLF, LF and CR as line delimiters in its string scanner.
        val lines = value.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val startX = cursorX
        val startY = cursorY
        val metrics = textPaint.fontMetrics
        val lineHeight = maxOf(
            textPaint.fontSpacing,
            metrics.descent - metrics.ascent
        ).coerceAtLeast(textPaint.textSize).toInt().coerceAtLeast(1).toFloat()
        val foreground = currentColor
        val baseStyle = textPaint.style
        val baseStroke = textPaint.strokeWidth
        var widestLine = 0f

        lines.forEach { line ->
            // Canvas drawText takes a baseline; HSP PrintLine's cy is the top edge.
            val baseline = cursorY - metrics.ascent
            if (flags and MES_SHADOW != 0) {
                textPaint.style = Paint.Style.FILL
                textPaint.color = Color.BLACK
                canvas.drawText(line, cursorX + 1f, baseline + 1f, textPaint)
            }
            if (flags and MES_OUTLINE != 0) {
                textPaint.style = Paint.Style.STROKE
                textPaint.strokeWidth = 1.5f
                textPaint.color = Color.BLACK
                canvas.drawText(line, cursorX.toFloat(), baseline, textPaint)
            }
            textPaint.style = Paint.Style.FILL
            textPaint.color = foreground
            canvas.drawText(line, cursorX.toFloat(), baseline, textPaint)

            widestLine = maxOf(widestLine, textPaint.measureText(line))
            cursorX = startX
            cursorY += lineHeight
        }

        // Bmscr::Print() always advances by the rendered line height. HSPMES_NOCR
        // then restores the original Y and leaves X after the widest printed line.
        if (flags and MES_NO_LINE_BREAK != 0) {
            cursorX = startX + widestLine.toInt()
            cursorY = startY
        } else {
            cursorX = startX
        }
        textPaint.style = baseStyle
        textPaint.strokeWidth = baseStroke
        textPaint.color = foreground
    }

    @Synchronized
    override fun pos(x: Int, y: Int) {
        cursorX = x
        cursorY = y
    }

    @Synchronized
    override fun currentPosition(): Pair<Int, Int> = cursorX to cursorY

    @Synchronized
    override fun surfaceSize(id: Int): Pair<Int, Int> {
        val bitmap = surfaces[id]?.bitmap
            ?: throw IOException("HSP surface " + id + " does not exist.")
        return bitmap.width to bitmap.height
    }

    /** Independent mutable snapshot used by renderer-level bitmap tests. */
    @Synchronized
    internal fun copySurfaceBitmap(id: Int): Bitmap {
        val bitmap = surfaces[id]?.bitmap
            ?: throw IOException("HSP surface " + id + " does not exist.")
        return bitmap.copy(Bitmap.Config.ARGB_8888, true)
            ?: throw IOException("Could not copy HSP surface " + id + ".")
    }

    @Synchronized
    override fun boxf() {
        val bitmap = activeSurface().bitmap
        boxf(0, 0, bitmap.width - 1, bitmap.height - 1)
    }

    @Synchronized
    override fun color(red: Int, green: Int, blue: Int) {
        currentColor = Color.rgb(red.coerceIn(0, 255), green.coerceIn(0, 255), blue.coerceIn(0, 255))
    }

    @Synchronized
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

    @Synchronized
    override fun gmode(mode: Int, alpha: Int) = gmode(mode, 32, 32, alpha)

    @Synchronized
    override fun gmode(mode: Int, copyWidth: Int, copyHeight: Int, alpha: Int) {
        require(mode in SUPPORTED_GMODE) {
            "HSP gmode " + mode + " is not supported by the Canvas renderer."
        }
        require(copyWidth >= 0 && copyHeight >= 0) { "HSP gmode copy dimensions cannot be negative." }
        blendMode = mode
        blendAlpha = alpha.coerceIn(0, 256)
        gmodeCopyWidth = copyWidth
        gmodeCopyHeight = copyHeight
    }

    @Synchronized
    override fun copySize(): Pair<Int, Int> = gmodeCopyWidth to gmodeCopyHeight

    @Synchronized
    override fun currentSurfaceSize(): Pair<Int, Int> {
        val bitmap = activeSurface().bitmap
        return bitmap.width to bitmap.height
    }

    @Synchronized
    override fun redraw(enabled: Boolean) {
        // OpenHSP's Bmscr::Send() stops updating the window while fl_udraw == 0.
        // Keep the last presented screen image so Compose invalidations cannot
        // replace a deliberately frozen HSP frame with a cleared Canvas.
        val surface = activeSurface()
        if (!enabled && activeId == 0 && surface.shouldPresent) {
            surface.suspendedFrame?.let { if (!it.isRecycled) it.recycle() }
            surface.suspendedFrame = surface.bitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: throw IOException("Could not snapshot the last presented HSP frame.")
        } else if (enabled) {
            surface.suspendedFrame?.let { if (!it.isRecycled) it.recycle() }
            surface.suspendedFrame = null
        }
        surface.shouldPresent = enabled
    }

    /** Presents the visible HSP window (screen ID 0), never an offscreen buffer. */
    @Synchronized
    fun draw(target: Canvas) {
        if (closed) return
        val screen = surfaces[0] ?: return
        val bitmap = if (screen.shouldPresent) screen.bitmap
            else screen.suspendedFrame ?: screen.bitmap
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

    private fun drawRegion(
        source: Surface,
        sourceRect: Rect,
        destinationRect: RectF,
        filter: Boolean = true,
        rotationDegrees: Float = 0f,
        rotationPivotX: Float? = null,
        rotationPivotY: Float? = null,
        applyGmode: Boolean = true
    ) {
        if (sourceRect.width() <= 0 || sourceRect.height() <= 0 ||
            destinationRect.width() <= 0f || destinationRect.height() <= 0f
        ) return
        val effectiveMode = if (applyGmode) blendMode else 0
        if (effectiveMode == ADDITIVE_BLEND || effectiveMode == SUBTRACTIVE_BLEND) {
            drawArithmeticBlend(
                source, sourceRect, destinationRect, filter,
                rotationDegrees, rotationPivotX, rotationPivotY, effectiveMode
            )
            return
        }
        val bitmap = when (effectiveMode) {
            COLOR_KEY_BLACK -> makeColorTransparent(source.bitmap, Color.BLACK)
            COLOR_KEY_CURRENT -> makeColorTransparent(source.bitmap, currentColor)
            else -> source.bitmap
        }
        bitmapPaint.color = Color.WHITE
        bitmapPaint.isFilterBitmap = filter
        bitmapPaint.alpha = when (effectiveMode) {
            ALPHA_BLEND, COLOR_KEY_ALPHA -> ((blendAlpha * 255) / 256).coerceIn(0, 255)
            else -> 255
        }
        bitmapPaint.xfermode = null
        val canvas = activeSurface().canvas
        val saveCount = canvas.save()
        try {
            if (rotationDegrees != 0f) {
                canvas.rotate(
                    rotationDegrees,
                    rotationPivotX ?: destinationRect.centerX(),
                    rotationPivotY ?: destinationRect.centerY()
                )
            }
            canvas.drawBitmap(bitmap, sourceRect, destinationRect, bitmapPaint)
        } finally {
            canvas.restoreToCount(saveCount)
            bitmapPaint.alpha = 255
            bitmapPaint.xfermode = null
            bitmapPaint.isFilterBitmap = true
            if (bitmap !== source.bitmap) bitmap.recycle()
        }
    }

    /** HSP modes 5/6 are channel arithmetic, not Porter-Duff blend approximations. */
    private fun drawArithmeticBlend(
        source: Surface,
        sourceRect: Rect,
        destinationRect: RectF,
        filter: Boolean,
        rotationDegrees: Float,
        rotationPivotX: Float?,
        rotationPivotY: Float?,
        mode: Int
    ) {
        val destination = activeSurface().bitmap
        val sourceBitmap = source.bitmap
        val pivotX = rotationPivotX ?: destinationRect.centerX()
        val pivotY = rotationPivotY ?: destinationRect.centerY()
        val radians = Math.toRadians(rotationDegrees.toDouble())
        val cosine = kotlin.math.cos(radians)
        val sine = kotlin.math.sin(radians)
        val corners = listOf(
            destinationRect.left.toDouble() to destinationRect.top.toDouble(),
            destinationRect.right.toDouble() to destinationRect.top.toDouble(),
            destinationRect.right.toDouble() to destinationRect.bottom.toDouble(),
            destinationRect.left.toDouble() to destinationRect.bottom.toDouble()
        )
        val rotated: List<Pair<Double, Double>> = if (rotationDegrees == 0f) {
            corners
        } else {
            corners.map { (x, y) ->
                val dx = x - pivotX.toDouble()
                val dy = y - pivotY.toDouble()
                (pivotX + dx * cosine - dy * sine) to
                    (pivotY + dx * sine + dy * cosine)
            }
        }
        val left = kotlin.math.floor(rotated.minOf { it.first }).toInt().coerceAtLeast(0)
        val top = kotlin.math.floor(rotated.minOf { it.second }).toInt().coerceAtLeast(0)
        val right = kotlin.math.ceil(rotated.maxOf { it.first }).toInt().coerceAtMost(destination.width)
        val bottom = kotlin.math.ceil(rotated.maxOf { it.second }).toInt().coerceAtMost(destination.height)
        if (right <= left || bottom <= top) return

        val regionWidth = right - left
        val regionHeight = bottom - top
        val destinationPixels = IntArray(regionWidth * regionHeight)
        destination.getPixels(
            destinationPixels, 0, regionWidth, left, top, regionWidth, regionHeight
        )

        val sourceWidth = sourceRect.width().toDouble()
        val sourceHeight = sourceRect.height().toDouble()
        val targetWidth = destinationRect.width().toDouble()
        val targetHeight = destinationRect.height().toDouble()
        val rate = blendAlpha.coerceIn(0, 256)

        for (row in 0 until regionHeight) {
            for (column in 0 until regionWidth) {
                val destinationX = left + column + 0.5
                val destinationY = top + row + 0.5

                // Undo the destination rotation to locate the corresponding point
                // inside the unrotated destination rectangle.
                val dx = destinationX - pivotX.toDouble()
                val dy = destinationY - pivotY.toDouble()
                val unrotatedX = if (rotationDegrees == 0f) destinationX
                    else pivotX + dx * cosine + dy * sine
                val unrotatedY = if (rotationDegrees == 0f) destinationY
                    else pivotY - dx * sine + dy * cosine
                val u = (unrotatedX - destinationRect.left) / targetWidth
                val v = (unrotatedY - destinationRect.top) / targetHeight
                if (u < 0.0 || u >= 1.0 || v < 0.0 || v >= 1.0) continue

                val sourceX = sourceRect.left + u * sourceWidth
                val sourceY = sourceRect.top + v * sourceHeight
                if (sourceX < 0.0 || sourceY < 0.0 ||
                    sourceX >= sourceBitmap.width || sourceY >= sourceBitmap.height
                ) continue

                val sourcePixel = sampleBlendSourcePixel(
                    sourceBitmap, sourceX, sourceY, filter
                )
                val sourceAlpha = (sourcePixel ushr 24) and 0xFF
                val effectiveRate = (rate * sourceAlpha + 127) / 255
                if (effectiveRate == 0) continue

                val pixelIndex = row * regionWidth + column
                val destinationPixel = destinationPixels[pixelIndex]
                fun channel(shift: Int): Int {
                    val destinationValue = (destinationPixel ushr shift) and 0xFF
                    val sourceValue = (sourcePixel ushr shift) and 0xFF
                    // OpenHSP: (source channel * gfrate) >> 8.
                    val contribution = (sourceValue * effectiveRate) ushr 8
                    return if (mode == ADDITIVE_BLEND) {
                        (destinationValue + contribution).coerceAtMost(255)
                    } else {
                        (destinationValue - contribution).coerceAtLeast(0)
                    }
                }
                val alpha = destinationPixel and -0x1000000
                destinationPixels[pixelIndex] = alpha or
                    (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
            }
        }
        destination.setPixels(
            destinationPixels, 0, regionWidth, left, top, regionWidth, regionHeight
        )
    }

    /**
     * Samples one source pixel at bitmap coordinates. The same-size path is
     * exact; scaled copies use bilinear interpolation to avoid nearest-neighbour
     * jaggies while rotation and blending are evaluated in destination space.
     */
    private fun sampleBlendSourcePixel(
        bitmap: Bitmap,
        sourceX: Double,
        sourceY: Double,
        filter: Boolean
    ): Int {
        if (!filter) {
            return bitmap.getPixel(sourceX.toInt(), sourceY.toInt())
        }

        val sampleX = sourceX - 0.5
        val sampleY = sourceY - 0.5
        val floorX = kotlin.math.floor(sampleX)
        val floorY = kotlin.math.floor(sampleY)
        val x0 = floorX.toInt().coerceIn(0, bitmap.width - 1)
        val y0 = floorY.toInt().coerceIn(0, bitmap.height - 1)
        val x1 = (floorX.toInt() + 1).coerceIn(0, bitmap.width - 1)
        val y1 = (floorY.toInt() + 1).coerceIn(0, bitmap.height - 1)
        val fractionX = (sampleX - floorX).coerceIn(0.0, 1.0)
        val fractionY = (sampleY - floorY).coerceIn(0.0, 1.0)
        val topLeft = bitmap.getPixel(x0, y0)
        val topRight = bitmap.getPixel(x1, y0)
        val bottomLeft = bitmap.getPixel(x0, y1)
        val bottomRight = bitmap.getPixel(x1, y1)

        fun channel(shift: Int): Int {
            val top = ((topLeft ushr shift) and 0xFF) * (1.0 - fractionX) +
                ((topRight ushr shift) and 0xFF) * fractionX
            val bottom = ((bottomLeft ushr shift) and 0xFF) * (1.0 - fractionX) +
                ((bottomRight ushr shift) and 0xFF) * fractionX
            return (top * (1.0 - fractionY) + bottom * fractionY + 0.5)
                .toInt().coerceIn(0, 255)
        }
        return (channel(24) shl 24) or
            (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun validateAssetName(name: String, command: String) {
        if (name.isBlank() || name.startsWith("/") || name.contains(':') || name.indexOf(0.toChar()) >= 0) {
            throw IOException("Invalid HSP " + command + " asset name.")
        }
    }

    private fun readBoundedAsset(input: InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count.toLong()
            if (total > MAX_ASSET_BYTES) {
                throw IOException("HSP bitmap exceeds the configured asset-size limit.")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun createSurface(id: Int, width: Int, height: Int) {
        validateDimensions(width, height)
        val bitmap = MutableArgbBitmapDecoder.create(width, height)
        val old = surfaces.put(id, Surface(bitmap, Canvas(bitmap)))
        old?.bitmap?.recycle()
        old?.suspendedFrame?.let { if (!it.isRecycled) it.recycle() }
    }

    private fun activeSurface(): Surface =
        surfaces[activeId] ?: throw IOException("The selected HSP surface is unavailable.")

    private fun makeColorTransparent(source: Bitmap, keyColor: Int): Bitmap {
        val mutable = source.copy(Bitmap.Config.ARGB_8888, true)
            ?: throw IOException("Could not create a transparent copy of an HSP source bitmap.")
        val pixels = IntArray(mutable.width * mutable.height)
        val key = keyColor and 0x00FFFFFF
        mutable.getPixels(pixels, 0, mutable.width, 0, 0, mutable.width, mutable.height)
        for (index in pixels.indices) {
            if ((pixels[index] and 0x00FFFFFF) == key) pixels[index] = 0
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

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        surfaces.values.forEach { surface ->
            if (!surface.bitmap.isRecycled) surface.bitmap.recycle()
            surface.suspendedFrame?.let { if (!it.isRecycled) it.recycle() }
        }
        surfaces.clear()
    }

    companion object {
        const val DEFAULT_WIDTH = 800
        const val DEFAULT_HEIGHT = 600
        private const val DEFAULT_FONT_SIZE = 16
        private const val MAX_SURFACE_DIMENSION = 8192
        private const val MAX_SURFACE_PIXELS = 16L * 1024L * 1024L
        private const val MAX_ASSET_BYTES = 32L * 1024L * 1024L
        private const val FONT_STYLE_MASK = 0x03
        private const val FONT_STYLE_BOLD = 0x01
        private const val FONT_STYLE_ITALIC = 0x02
        private const val FONT_STYLE_BOLD_ITALIC = FONT_STYLE_BOLD or FONT_STYLE_ITALIC
        private const val MES_NO_LINE_BREAK = 1
        private const val MES_SHADOW = 2
        private const val MES_OUTLINE = 4
        private const val COLOR_KEY_BLACK = 2
        private const val ALPHA_BLEND = 3
        private const val ADDITIVE_BLEND = 5
        private const val COLOR_KEY_ALPHA = 4
        private const val COLOR_KEY_CURRENT = 4
        private const val SUBTRACTIVE_BLEND = 6
        private val SUPPORTED_GMODE = 0..6
    }
}
