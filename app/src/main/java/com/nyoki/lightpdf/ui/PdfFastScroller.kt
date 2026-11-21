package com.nyoki.lightpdf.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/**
 * Lightweight right-edge draggable fast-scroll thumb with a Liquid Titanium
 * floating page-number bubble for rapid navigation across large PDFs.
 */
class PdfFastScroller @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface Listener {
        fun onScrubToPage(pageIndex: Int)
        fun onScrubStateChanged(isDragging: Boolean)
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private val thumbWidth = 8f * density
    private val thumbExpandedWidth = 14f * density
    private val thumbHeight = 52f * density
    private val thumbMarginEnd = 6f * density
    private val touchStripWidth = 40f * density
    private val bubbleGap = 14f * density
    private val bubbleRadius = 14f * density
    private val bubblePadH = 14f * density
    private val bubbleHeight = 38f * density

    private var topInset = (72f * density).roundToInt()
    private var bottomInset = (36f * density).roundToInt()
    private var rightInset = 0

    private var pageCount = 0
    private var currentPage = 0
    private var progress = 0f // 0f..1f
    private var isDragging = false
    private var visibleAlpha = 0f

    private val thumbRect = RectF()
    private val bubbleRect = RectF()
    private var thumbShaderWidth = -1f

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x26B7C6CF
        style = Paint.Style.FILL
    }

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB7C6CF.toInt()
        style = Paint.Style.FILL
    }

    private val thumbBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCF5FBFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }

    private val bubbleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6211E19.toInt()
        style = Paint.Style.FILL
    }

    private val bubbleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x998A7CFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }

    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF5FBFF.toInt()
        textSize = 14f * density
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    private val hideRunnable = Runnable {
        if (!isDragging) {
            animate().alpha(0f).setDuration(220L).withEndAction {
                visibleAlpha = 0f
            }.start()
        }
    }

    init {
        alpha = 0f
    }

    fun setVerticalInsets(topPx: Int, bottomPx: Int, rightPx: Int = rightInset) {
        topInset = topPx
        bottomInset = bottomPx
        rightInset = rightPx.coerceAtLeast(0)
        thumbShaderWidth = -1f
        invalidate()
    }

    fun setPageCount(count: Int) {
        pageCount = count
        if (count <= 1) {
            alpha = 0f
            visibleAlpha = 0f
        }
        invalidate()
    }

    fun updateFromScroll(page: Int, scrollProgress: Float) {
        if (pageCount <= 1 || isDragging) return
        currentPage = page.coerceIn(0, pageCount - 1)
        progress = scrollProgress.coerceIn(0f, 1f)
        showTemporarily()
        invalidate()
    }

    fun showTemporarily() {
        if (pageCount <= 1) return
        removeCallbacks(hideRunnable)
        if (alpha < 1f) {
            animate().cancel()
            animate().alpha(1f).setDuration(120L).start()
        }
        visibleAlpha = 1f
        if (!isDragging) {
            postDelayed(hideRunnable, HIDE_DELAY_MS)
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (pageCount <= 1) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val trackTop = topInset.toFloat()
                val trackBottom = (height - bottomInset).toFloat()
                if (event.x >= width - touchStripWidth - rightInset && event.y in trackTop..trackBottom) {
                    isDragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    removeCallbacks(hideRunnable)
                    animate().cancel()
                    alpha = 1f
                    visibleAlpha = 1f
                    listener?.onScrubStateChanged(true)
                    updateProgressFromTouch(event.y)
                    return true
                }
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    updateProgressFromTouch(event.y)
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    listener?.onScrubStateChanged(false)
                    postDelayed(hideRunnable, HIDE_DELAY_MS)
                    invalidate()
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        performClick()
                    }
                    return true
                }
            }
        }
        return false
    }

    private fun updateProgressFromTouch(touchY: Float) {
        val usableTop = topInset + thumbHeight * 0.5f
        val usableBottom = (height - bottomInset - thumbHeight * 0.5f).coerceAtLeast(usableTop + 1f)
        val nextProgress = ((touchY - usableTop) / (usableBottom - usableTop)).coerceIn(0f, 1f)
        progress = nextProgress
        val targetPage = (nextProgress * (pageCount - 1)).roundToInt().coerceIn(0, pageCount - 1)
        if (targetPage != currentPage) {
            currentPage = targetPage
            listener?.onScrubToPage(targetPage)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (pageCount <= 1 || width <= 0 || height <= 0) return

        val usableTop = topInset.toFloat()
        val usableBottom = (height - bottomInset).toFloat()
        val trackRange = (usableBottom - usableTop - thumbHeight).coerceAtLeast(1f)

        val currentThumbW = if (isDragging) thumbExpandedWidth else thumbWidth
        val right = width - thumbMarginEnd - rightInset
        val left = right - currentThumbW
        val top = usableTop + progress * trackRange
        val bottom = top + thumbHeight

        // Subtle vertical track line while dragging
        if (isDragging) {
            val trackCenterX = (left + right) * 0.5f
            canvas.drawRoundRect(
                trackCenterX - 1.5f * density,
                usableTop,
                trackCenterX + 1.5f * density,
                usableBottom,
                2f * density,
                2f * density,
                trackPaint
            )
        }

        // Liquid Titanium gradient thumb
        if (thumbShaderWidth != left) {
            thumbShaderWidth = left
            thumbPaint.shader = LinearGradient(
                left, 0f, right, 0f,
                0xFFF5FBFF.toInt(),
                0xFFB7C6CF.toInt(),
                Shader.TileMode.CLAMP
            )
        }

        thumbRect.set(left, top, right, bottom)
        val thumbRadius = currentThumbW * 0.5f
        canvas.drawRoundRect(thumbRect, thumbRadius, thumbRadius, thumbPaint)
        canvas.drawRoundRect(thumbRect, thumbRadius, thumbRadius, thumbBorderPaint)

        // Floating page bubble while dragging
        if (isDragging) {
            val label = "${currentPage + 1} / $pageCount"
            val textW = bubbleTextPaint.measureText(label)
            val bubbleW = textW + bubblePadH * 2f
            val bRight = left - bubbleGap
            val bLeft = bRight - bubbleW
            val bCenterY = (top + bottom) * 0.5f
            val bTop = (bCenterY - bubbleHeight * 0.5f).coerceIn(usableTop, usableBottom - bubbleHeight)
            val bBottom = bTop + bubbleHeight

            bubbleRect.set(bLeft, bTop, bRight, bBottom)
            canvas.drawRoundRect(bubbleRect, bubbleRadius, bubbleRadius, bubbleBgPaint)
            canvas.drawRoundRect(bubbleRect, bubbleRadius, bubbleRadius, bubbleBorderPaint)

            val textY = bubbleRect.centerY() - (bubbleTextPaint.descent() + bubbleTextPaint.ascent()) * 0.5f
            canvas.drawText(label, bubbleRect.centerX(), textY, bubbleTextPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(hideRunnable)
    }

    companion object {
        private const val HIDE_DELAY_MS = 2300L
    }
}
