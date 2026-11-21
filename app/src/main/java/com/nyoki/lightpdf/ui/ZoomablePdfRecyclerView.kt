package com.nyoki.lightpdf.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import android.widget.OverScroller
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * High-performance zoomable vertical PDF RecyclerView.
 *
 * - During active pinch or double-tap animation: applies GPU canvas transform at 60/120fps
 *   with zero layout passes and zero bitmap allocations.
 * - On gesture commit: updates page heights & exact vertical scroll anchor seamlessly,
 *   then triggers high-resolution tile rendering.
 * - While zoomed (> 1x): supports smooth simultaneous horizontal panning/flinging and
 *   native RecyclerView vertical scrolling/flinging.
 */
class ZoomablePdfRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    interface ZoomListener {
        fun onSingleTap()
        fun onZoomChanged(zoom: Float, panX: Float)
        fun onPanChanged(panX: Float)
        fun onViewportSettled()
        fun onViewportWidthChanged(newWidth: Int, oldWidth: Int)
    }

    var zoomListener: ZoomListener? = null

    var committedZoom: Float = MIN_ZOOM
        private set
    var panX: Float = 0f
        private set

    // Transient visual transform active during pinch or double-tap animation
    private var transientScale: Float = 1f
    private var transientTx: Float = 0f
    private var transientTy: Float = 0f
    private var isTransformActive: Boolean = false
    private var wasPinching: Boolean = false
    private var lastFocusX: Float = 0f
    private var lastFocusY: Float = 0f

    // Horizontal pan & fling state when zoomed
    private var activePointerId: Int = MotionEvent.INVALID_POINTER_ID
    private var downTouchX: Float = 0f
    private var lastTouchX: Float = 0f
    private var isHorizontalPanning: Boolean = false
    private var velocityTracker: VelocityTracker? = null
    private val panScroller = OverScroller(context)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity

    private var doubleTapAnimator: ValueAnimator? = null
    private val liquidInterpolator = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)

    private val settleRunnable = Runnable {
        if (!isTransformActive && scrollState == SCROLL_STATE_IDLE && panScroller.isFinished) {
            zoomListener?.onViewportSettled()
        }
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                cancelAnimationsAndFling()
                isTransformActive = true
                wasPinching = true
                transientScale = 1f
                transientTx = 0f
                transientTy = 0f
                lastFocusX = detector.focusX
                lastFocusY = detector.focusY
                removeCallbacks(settleRunnable)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                if (factor.isNaN() || factor <= 0f) return false

                val minRel = MIN_ZOOM / committedZoom
                val maxRel = MAX_ZOOM / committedZoom
                val nextScale = (transientScale * factor).coerceIn(minRel, maxRel)
                val ratio = nextScale / transientScale

                val fx = detector.focusX
                val fy = detector.focusY
                transientTx = fx + (transientTx - lastFocusX) * ratio
                transientTy = fy + (transientTy - lastFocusY) * ratio
                transientScale = nextScale
                lastFocusX = fx
                lastFocusY = fy

                invalidate()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                commitTransform()
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                zoomListener?.onSingleTap()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                animateDoubleTapZoom(e.x, e.y)
                return true
            }
        }
    )

    init {
        scaleDetector.isQuickScaleEnabled = false
        itemAnimator = null

        addOnScrollListener(object : OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == SCROLL_STATE_IDLE) {
                    scheduleSettle()
                } else {
                    removeCallbacks(settleRunnable)
                }
            }
        })
    }

    fun setZoomStateImmediate(zoom: Float, newPanX: Float, viewportWidthPx: Int = width) {
        cancelAnimationsAndFling()
        committedZoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val effectiveWidth = if (viewportWidthPx > 0) viewportWidthPx else width
        val maxPan = (effectiveWidth * (committedZoom - 1f)).coerceAtLeast(0f)
        panX = newPanX.coerceIn(0f, maxPan)
        resetTransientTransform()
        invalidate()
    }

    fun getEffectiveZoom(): Float = (committedZoom * transientScale).coerceIn(MIN_ZOOM, MAX_ZOOM)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && oldw > 0 && w != oldw) {
            val oldMaxPan = (oldw * (committedZoom - 1f)).coerceAtLeast(0f)
            val newMaxPan = maxPanX(committedZoom)
            panX = if (oldMaxPan > 0f && newMaxPan > 0f) {
                ((panX / oldMaxPan).coerceIn(0f, 1f) * newMaxPan).coerceIn(0f, newMaxPan)
            } else {
                0f
            }
            zoomListener?.onViewportWidthChanged(w, oldw)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (isTransformActive) {
            val saveCount = canvas.save()
            canvas.translate(transientTx, transientTy)
            canvas.scale(transientScale, transientScale)
            super.dispatchDraw(canvas)
            canvas.restoreToCount(saveCount)
        } else {
            super.dispatchDraw(canvas)
        }
    }

    override fun computeScroll() {
        super.computeScroll()
        if (panScroller.computeScrollOffset()) {
            val nextPan = panScroller.currX.toFloat().coerceIn(0f, maxPanX(committedZoom))
            if (abs(nextPan - panX) > 0.25f) {
                panX = nextPan
                zoomListener?.onPanChanged(panX)
            }
            if (!panScroller.isFinished) {
                postInvalidateOnAnimation()
            } else {
                scheduleSettle()
            }
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (e.pointerCount >= 2 || scaleDetector.isInProgress || isTransformActive) {
            return true
        }
        return super.onInterceptTouchEvent(e)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            wasPinching = false
            if (!panScroller.isFinished) {
                panScroller.abortAnimation()
            }
        }

        scaleDetector.onTouchEvent(e)
        gestureDetector.onTouchEvent(e)

        if (scaleDetector.isInProgress || e.pointerCount >= 2) {
            if (!wasPinching) {
                wasPinching = true
                // Cancel RecyclerView's ongoing vertical drag cleanly
                val cancelEvent = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                super.onTouchEvent(cancelEvent)
                cancelEvent.recycle()
            }
            isHorizontalPanning = false
            activePointerId = MotionEvent.INVALID_POINTER_ID
            releaseVelocityTracker()
            return true
        }

        // Ignore stray single-finger moves right after lifting from a 2-finger pinch
        if (wasPinching) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                wasPinching = false
            }
            return true
        }

        if (doubleTapAnimator?.isRunning == true) {
            return true
        }

        // Handle simultaneous horizontal panning & flinging when zoomed
        if (committedZoom > 1.01f) {
            handleHorizontalPanEvent(e)
        } else {
            isHorizontalPanning = false
            activePointerId = MotionEvent.INVALID_POINTER_ID
            releaseVelocityTracker()
        }

        val handled = super.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            performClick()
        }
        return handled || committedZoom > 1.01f
    }

    private fun handleHorizontalPanEvent(e: MotionEvent) {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        }
        velocityTracker?.addMovement(e)

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = e.getPointerId(0)
                downTouchX = e.x
                lastTouchX = e.x
                isHorizontalPanning = false
                removeCallbacks(settleRunnable)
            }

            MotionEvent.ACTION_MOVE -> {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) {
                    activePointerId = e.getPointerId(0)
                    downTouchX = e.getX(0)
                    lastTouchX = downTouchX
                    isHorizontalPanning = false
                    return
                }
                val idx = e.findPointerIndex(activePointerId)
                if (idx >= 0) {
                    val x = e.getX(idx)
                    if (!isHorizontalPanning) {
                        val totalDx = x - downTouchX
                        if (abs(totalDx) >= touchSlop) {
                            isHorizontalPanning = true
                            // Consume touch slop smoothly to prevent a sudden jump on pan start
                            lastTouchX = downTouchX + if (totalDx > 0f) touchSlop else -touchSlop
                        } else {
                            return
                        }
                    }
                    val dx = lastTouchX - x
                    lastTouchX = x
                    val nextPan = (panX + dx).coerceIn(0f, maxPanX(committedZoom))
                    if (abs(nextPan - panX) > 0.25f) {
                        panX = nextPan
                        zoomListener?.onPanChanged(panX)
                        removeCallbacks(settleRunnable)
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val upIndex = e.actionIndex
                if (e.getPointerId(upIndex) == activePointerId) {
                    val newIndex = if (upIndex == 0) 1 else 0
                    if (newIndex < e.pointerCount) {
                        activePointerId = e.getPointerId(newIndex)
                        lastTouchX = e.getX(newIndex)
                        downTouchX = lastTouchX
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                velocityTracker?.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
                val vx = velocityTracker?.getXVelocity(activePointerId) ?: 0f
                val wasPanning = isHorizontalPanning
                isHorizontalPanning = false
                releaseVelocityTracker()
                activePointerId = MotionEvent.INVALID_POINTER_ID

                val maxPan = maxPanX(committedZoom).roundToInt()
                if (wasPanning && abs(vx) >= minFlingVelocity && maxPan > 0) {
                    panScroller.fling(
                        panX.roundToInt(),
                        0,
                        (-vx).roundToInt(),
                        0,
                        0,
                        maxPan,
                        0,
                        0
                    )
                    postInvalidateOnAnimation()
                } else {
                    scheduleSettle()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                isHorizontalPanning = false
                releaseVelocityTracker()
                activePointerId = MotionEvent.INVALID_POINTER_ID
                scheduleSettle()
            }
        }
    }

    private fun animateDoubleTapZoom(tapX: Float, tapY: Float) {
        if (width <= 0 || height <= 0) return
        cancelAnimationsAndFling()

        val targetZoom = if (committedZoom > 1.1f) MIN_ZOOM else DOUBLE_TAP_ZOOM
        val targetRelScale = targetZoom / committedZoom

        // Compute target transient translation so the tapped point stays centered/anchored
        val targetTx: Float
        val targetTy: Float
        if (targetZoom <= 1.01f) {
            // Smoothly return to 1x aligned at x=0
            targetTx = panX * targetRelScale
            targetTy = tapY * (1f - targetRelScale)
        } else {
            // Zoom into the tapped spot and gently pull toward screen center
            val rawTx = tapX * (1f - targetRelScale)
            val newMaxPan = width * (targetZoom - 1f)
            val desiredPan = (panX * targetRelScale - rawTx).coerceIn(0f, newMaxPan)
            targetTx = panX * targetRelScale - desiredPan
            targetTy = tapY * (1f - targetRelScale)
        }

        isTransformActive = true
        lastFocusX = tapX
        lastFocusY = tapY
        removeCallbacks(settleRunnable)

        doubleTapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 190L
            interpolator = liquidInterpolator
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                transientScale = 1f + (targetRelScale - 1f) * t
                transientTx = targetTx * t
                transientTy = targetTy * t
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    doubleTapAnimator = null
                    commitTransform()
                }

                override fun onAnimationCancel(animation: Animator) {
                    doubleTapAnimator = null
                }
            })
            start()
        }
    }

    /**
     * Commits transient GPU transform into real RecyclerView item heights, panX, and exact scroll anchor.
     */
    private fun commitTransform() {
        if (!isTransformActive) return

        val s = transientScale
        val tx = transientTx
        val ty = transientTy
        val oldZoom = committedZoom
        val newZoom = (oldZoom * s).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val clampedZoom = if (newZoom < 1.03f) MIN_ZOOM else newZoom

        // Compute horizontal pan (mathematically exact)
        val newPanX = if (clampedZoom <= 1.01f) {
            0f
        } else {
            (panX * s - tx).coerceIn(0f, maxPanX(clampedZoom))
        }

        // Find vertical scroll anchor before changing item heights
        val syRef = if (lastFocusY in 0f..height.toFloat()) lastFocusY else height * 0.5f
        val vyRef = (syRef - ty) / s
        var anchorPos = NO_POSITION
        var anchorFrac = 0f
        var bestDist = Float.MAX_VALUE

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val pos = getChildAdapterPosition(child)
            if (pos == NO_POSITION || child.height <= 0) continue

            val dist = when {
                vyRef < child.top -> child.top - vyRef
                vyRef > child.bottom -> vyRef - child.bottom
                else -> 0f
            }
            if (dist < bestDist) {
                bestDist = dist
                anchorPos = pos
                anchorFrac = ((vyRef - child.top) / child.height.toFloat()).coerceIn(-0.5f, 1.5f)
                if (dist == 0f) break
            }
        }

        // Apply new committed zoom state & reset visual transform
        committedZoom = clampedZoom
        panX = newPanX
        resetTransientTransform()

        val pdfAdapter = adapter as? PdfPagesAdapter
        pdfAdapter?.updateZoomOnAttached(this, committedZoom, panX)

        if (anchorPos != NO_POSITION && pdfAdapter != null) {
            val newPageH = pdfAdapter.computePageHeight(anchorPos)
            val newTop = (syRef - anchorFrac * newPageH).roundToInt()
            val lm = layoutManager as? LinearLayoutManager
            lm?.scrollToPositionWithOffset(anchorPos, newTop - paddingTop)
        }

        invalidate()
        zoomListener?.onZoomChanged(committedZoom, panX)
        post {
            if (!isTransformActive) {
                zoomListener?.onViewportSettled()
            }
        }
    }

    private fun resetTransientTransform() {
        isTransformActive = false
        transientScale = 1f
        transientTx = 0f
        transientTy = 0f
    }

    private fun cancelAnimationsAndFling() {
        doubleTapAnimator?.cancel()
        doubleTapAnimator = null
        isHorizontalPanning = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
        if (!panScroller.isFinished) {
            panScroller.abortAnimation()
        }
        stopScroll()
    }

    private fun maxPanX(zoom: Float): Float = (width * (zoom - 1f)).coerceAtLeast(0f)

    private fun scheduleSettle() {
        removeCallbacks(settleRunnable)
        postDelayed(settleRunnable, 90L)
    }

    private fun releaseVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(settleRunnable)
        cancelAnimationsAndFling()
        releaseVelocityTracker()
    }

    companion object {
        const val MIN_ZOOM = 1.0f
        const val MAX_ZOOM = 5.0f
        private const val DOUBLE_TAP_ZOOM = 2.5f
    }
}
