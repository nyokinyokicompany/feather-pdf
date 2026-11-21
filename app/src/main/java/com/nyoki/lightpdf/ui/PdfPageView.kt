package com.nyoki.lightpdf.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.nyoki.lightpdf.pdf.RefCountedBitmap
import com.nyoki.lightpdf.pdf.ZoomTile
import kotlin.math.abs

/**
 * Lightweight View rendering a single PDF page with:
 * - Immediate 1x base bitmap drawing (stretched smoothly while zooming/panning)
 * - Overlaid high-resolution [ZoomTile] for razor-sharp zoomed text
 * - Zero-cost Night Mode via ColorMatrixColorFilter at draw time
 */
class PdfPageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var baseBitmap: RefCountedBitmap? = null
    private var zoomTile: ZoomTile? = null

    private var pageIndex: Int = 0
    private var zoomScale: Float = 1f
    private var panX: Float = 0f
    private var nightMode: Boolean = false

    private val pageBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    private val placeholderTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 14f * resources.displayMetrics.density
    }

    private val pageRect = RectF()
    private val tileRect = RectF()

    fun setPageState(
        index: Int,
        zoom: Float,
        panX: Float,
        nightMode: Boolean
    ) {
        this.pageIndex = index
        this.zoomScale = zoom
        this.panX = panX
        applyNightMode(nightMode)
        invalidate()
    }

    fun setPanX(newPanX: Float) {
        if (abs(this.panX - newPanX) > 0.25f || (newPanX == 0f && this.panX != 0f)) {
            this.panX = newPanX
            invalidate()
        }
    }

    fun setZoomAndPan(newZoom: Float, newPanX: Float) {
        val zoomChanged = abs(this.zoomScale - newZoom) > 0.005f
        this.zoomScale = newZoom
        this.panX = newPanX
        if (zoomChanged) {
            clearZoomTile()
        }
        invalidate()
    }

    fun setNightMode(enabled: Boolean) {
        if (this.nightMode != enabled) {
            applyNightMode(enabled)
            invalidate()
        }
    }

    fun hasUsableBaseBitmap(): Boolean = baseBitmap?.isUsable() == true

    fun setBaseBitmap(ref: RefCountedBitmap?) {
        if (baseBitmap === ref) {
            ref?.release()
            return
        }
        baseBitmap?.release()
        baseBitmap = ref
        invalidate()
    }

    fun setZoomTile(tile: ZoomTile?) {
        if (zoomTile === tile) {
            tile?.bitmap?.release()
            return
        }
        zoomTile?.bitmap?.release()
        zoomTile = tile
        invalidate()
    }

    fun clearZoomTile() {
        if (zoomTile != null) {
            zoomTile?.bitmap?.release()
            zoomTile = null
            invalidate()
        }
    }

    fun getCurrentZoomTile(): ZoomTile? = zoomTile

    fun clearBitmaps() {
        baseBitmap?.release()
        baseBitmap = null
        zoomTile?.bitmap?.release()
        zoomTile = null
    }

    private fun applyNightMode(enabled: Boolean) {
        this.nightMode = enabled
        if (enabled) {
            pageBgPaint.color = NIGHT_PAGE_BG
            bitmapPaint.colorFilter = NIGHT_COLOR_FILTER
            placeholderTextPaint.color = NIGHT_PLACEHOLDER_TEXT
        } else {
            pageBgPaint.color = Color.WHITE
            bitmapPaint.colorFilter = null
            placeholderTextPaint.color = LIGHT_PLACEHOLDER_TEXT
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val zoomedW = w * zoomScale
        val left = -panX
        val right = left + zoomedW
        pageRect.set(left, 0f, right, h)

        // Draw page paper background
        canvas.drawRect(pageRect, pageBgPaint)

        // Draw 1x base bitmap (always immediate fallback while panning/zooming)
        val base = baseBitmap
        if (base != null && base.isUsable()) {
            canvas.drawBitmap(base.bitmap, null, pageRect, bitmapPaint)
        } else {
            val cx = (left + right) * 0.5f
            val cy = h * 0.5f - (placeholderTextPaint.descent() + placeholderTextPaint.ascent()) * 0.5f
            canvas.drawText((pageIndex + 1).toString(), cx, cy, placeholderTextPaint)
        }

        // Overlay crisp high-res zoom tile if available for the current zoom size
        val tile = zoomTile
        if (tile != null && tile.bitmap.isUsable() && zoomScale > 1.05f) {
            if (abs(tile.zoomedPageWidthPx - zoomedW) <= 3f) {
                tileRect.set(
                    tile.region.left - panX,
                    tile.region.top.toFloat(),
                    tile.region.right - panX,
                    tile.region.bottom.toFloat()
                )
                canvas.drawBitmap(tile.bitmap.bitmap, null, tileRect, bitmapPaint)
            }
        }
    }

    companion object {
        private const val NIGHT_PAGE_BG = 0xFF14181F.toInt()
        private const val NIGHT_PLACEHOLDER_TEXT = 0x66F5FBFF
        private const val LIGHT_PLACEHOLDER_TEXT = 0x44070B12

        // Soft OLED-friendly inversion: maps white (#FFFFFF) -> #14181F and black (#000000) -> #ECF2F7
        private val NIGHT_COLOR_FILTER = ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    -0.90f, 0f, 0f, 0f, 240f,
                    0f, -0.90f, 0f, 0f, 244f,
                    0f, 0f, -0.89f, 0f, 248f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    }
}
