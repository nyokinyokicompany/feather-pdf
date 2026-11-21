package com.nyoki.lightpdf.ui

import android.graphics.Rect
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.nyoki.lightpdf.pdf.PdfRenderEngine
import kotlin.math.abs
import kotlin.math.roundToInt

class PdfPagesAdapter(
    private val engine: PdfRenderEngine
) : RecyclerView.Adapter<PdfPagesAdapter.PageViewHolder>() {

    private var aspectRatios: FloatArray = FloatArray(0)
    private var viewportWidthPx: Int = 0
    private var zoomScale: Float = 1f
    private var panX: Float = 0f
    private var nightMode: Boolean = false
    private var tileGeneration: Int = 0
    private var attachedRecyclerView: RecyclerView? = null
    private var attachTileRefreshScheduled: Boolean = false

    private val attachTileRefreshRunnable = Runnable {
        attachTileRefreshScheduled = false
        val rv = attachedRecyclerView ?: return@Runnable
        if (!rv.isAttachedToWindow || zoomScale <= 1.05f) return@Runnable
        val interacting = if (rv is ZoomablePdfRecyclerView) {
            rv.isViewportInteracting()
        } else {
            rv.scrollState != RecyclerView.SCROLL_STATE_IDLE
        }
        if (!interacting) {
            refreshHighResTiles(rv)
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        recyclerView.removeCallbacks(attachTileRefreshRunnable)
        attachTileRefreshScheduled = false
        if (attachedRecyclerView === recyclerView) {
            attachedRecyclerView = null
        }
    }

    private fun scheduleTileRefreshIfIdle() {
        val rv = attachedRecyclerView ?: return
        if (zoomScale <= 1.05f || attachTileRefreshScheduled) return
        val interacting = if (rv is ZoomablePdfRecyclerView) {
            rv.isViewportInteracting()
        } else {
            rv.scrollState != RecyclerView.SCROLL_STATE_IDLE
        }
        if (!interacting) {
            attachTileRefreshScheduled = true
            rv.post(attachTileRefreshRunnable)
        }
    }

    fun setDocument(
        ratios: FloatArray,
        viewportWidth: Int,
        initialZoom: Float,
        initialPanX: Float = 0f,
        nightMode: Boolean
    ) {
        val oldCount = this.aspectRatios.size
        this.aspectRatios = ratios
        this.viewportWidthPx = viewportWidth.coerceAtLeast(1)
        this.zoomScale = initialZoom.coerceIn(1f, 5f)
        val maxPan = (this.viewportWidthPx * (this.zoomScale - 1f)).coerceAtLeast(0f)
        this.panX = initialPanX.coerceIn(0f, maxPan)
        this.nightMode = nightMode
        this.tileGeneration++
        if (oldCount > 0) {
            notifyItemRangeRemoved(0, oldCount)
        }
        if (ratios.isNotEmpty()) {
            notifyItemRangeInserted(0, ratios.size)
        }
    }

    fun updateAspectRatios(updates: Map<Int, Float>, recyclerView: RecyclerView) {
        var anyVisibleChanged = false
        for ((index, ratio) in updates) {
            if (index in aspectRatios.indices) {
                aspectRatios[index] = ratio
                val holder = recyclerView.findViewHolderForAdapterPosition(index) as? PageViewHolder
                if (holder != null) {
                    val newH = computePageHeight(index)
                    if (holder.pageView.layoutParams.height != newH) {
                        holder.pageView.layoutParams.height = newH
                        holder.pageView.requestLayout()
                        anyVisibleChanged = true
                    }
                }
            }
        }
        if (anyVisibleChanged) {
            recyclerView.invalidateItemDecorations()
        }
    }

    fun setViewportWidth(newWidthPx: Int, newPanX: Float, recyclerView: RecyclerView) {
        if (newWidthPx <= 0 || newWidthPx == viewportWidthPx) return
        val desiredBaseW = PdfRenderEngine.clampBaseWidth(newWidthPx)
        viewportWidthPx = newWidthPx
        val maxPan = (viewportWidthPx * (zoomScale - 1f)).coerceAtLeast(0f)
        panX = newPanX.coerceIn(0f, maxPan)
        tileGeneration++
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i)
            val holder = recyclerView.getChildViewHolder(child) as? PageViewHolder ?: continue
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                holder.cancelTile()
                holder.pageView.clearZoomTile()
                holder.boundTileGeneration = tileGeneration
                holder.boundViewportWidthPx = viewportWidthPx
                holder.boundZoomScale = zoomScale
                holder.pageView.layoutParams.height = computePageHeight(pos)
                holder.pageView.setZoomAndPan(zoomScale, panX)
                holder.pageView.requestLayout()
                if (holder.boundBaseWidthPx != desiredBaseW || !holder.pageView.hasUsableBaseBitmap()) {
                    bindBaseBitmap(holder, pos)
                }
            }
        }
    }

    fun computePageHeight(pageIndex: Int): Int {
        val ratio = if (pageIndex in aspectRatios.indices) aspectRatios[pageIndex] else 1.4142f
        return (viewportWidthPx * zoomScale * ratio).roundToInt().coerceAtLeast(64)
    }

    fun updatePanXOnAttached(recyclerView: RecyclerView, newPanX: Float) {
        this.panX = newPanX
        for (i in 0 until recyclerView.childCount) {
            val view = recyclerView.getChildAt(i) as? PdfPageView ?: continue
            view.setPanX(newPanX)
        }
    }

    fun updateZoomOnAttached(recyclerView: RecyclerView, newZoom: Float, newPanX: Float) {
        this.zoomScale = newZoom
        this.panX = newPanX
        tileGeneration++
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i)
            val holder = recyclerView.getChildViewHolder(child) as? PageViewHolder ?: continue
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                holder.cancelTile()
                holder.pageView.clearZoomTile()
                holder.boundZoomScale = newZoom
                holder.boundTileGeneration = tileGeneration
                holder.pageView.layoutParams.height = computePageHeight(pos)
                holder.pageView.setZoomAndPan(newZoom, newPanX)
                holder.pageView.requestLayout()
            }
        }
    }

    fun setNightMode(enabled: Boolean, recyclerView: RecyclerView) {
        if (this.nightMode == enabled) return
        this.nightMode = enabled
        for (i in 0 until recyclerView.childCount) {
            val view = recyclerView.getChildAt(i) as? PdfPageView ?: continue
            view.setNightMode(enabled)
        }
    }

    fun clearAllTiles(recyclerView: RecyclerView) {
        tileGeneration++
        for (i in 0 until recyclerView.childCount) {
            val holder = recyclerView.getChildViewHolder(recyclerView.getChildAt(i)) as? PageViewHolder
                ?: continue
            holder.cancelTile()
            holder.pageView.clearZoomTile()
            holder.boundTileGeneration = tileGeneration
        }
    }

    /**
     * Renders high-resolution tiles only for the currently visible portions of attached pages.
     */
    fun refreshHighResTiles(recyclerView: RecyclerView) {
        if (zoomScale <= 1.05f || viewportWidthPx <= 0) {
            clearAllTiles(recyclerView)
            return
        }

        val rvHeight = recyclerView.height
        if (rvHeight <= 0) return

        val zoomedW = (viewportWidthPx * zoomScale).roundToInt()
        val overscan = (96 * recyclerView.resources.displayMetrics.density).roundToInt()

        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i)
            val holder = recyclerView.getChildViewHolder(child) as? PageViewHolder ?: continue
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION || pos !in aspectRatios.indices) continue

            val pageH = child.height.takeIf { it > 0 } ?: computePageHeight(pos)
            val visTop = (-child.top).coerceAtLeast(0)
            val visBottom = (rvHeight - child.top).coerceAtMost(pageH)
            if (visBottom <= visTop) {
                holder.cancelTile()
                holder.pageView.clearZoomTile()
                continue
            }

            val visLeft = panX.roundToInt().coerceIn(0, zoomedW)
            val visRight = (panX + viewportWidthPx).roundToInt().coerceIn(0, zoomedW)

            // Check if existing tile already covers the visible rect at this zoom
            val existing = holder.pageView.getCurrentZoomTile()
            if (existing != null &&
                existing.bitmap.isUsable() &&
                abs(existing.zoomedPageWidthPx - zoomedW) <= 2 &&
                existing.region.left <= visLeft &&
                existing.region.right >= visRight &&
                existing.region.top <= visTop &&
                existing.region.bottom >= visBottom
            ) {
                continue
            }

            val reqRegion = Rect(
                (visLeft - overscan).coerceAtLeast(0),
                (visTop - overscan).coerceAtLeast(0),
                (visRight + overscan).coerceAtMost(zoomedW),
                (visBottom + overscan).coerceAtMost(pageH)
            )

            if (holder.tileHandle != null &&
                holder.pendingTileZoomedW == zoomedW &&
                holder.pendingTileRegion == reqRegion
            ) {
                continue
            }

            holder.cancelTile()
            holder.pendingTileZoomedW = zoomedW
            holder.pendingTileRegion.set(reqRegion)
            holder.tileHandle = engine.requestZoomTile(
                pageIndex = pos,
                zoomedPageWidthPx = zoomedW,
                zoomedPageHeightPx = pageH,
                region = reqRegion
            ) { tile ->
                holder.tileHandle = null
                holder.pendingTileZoomedW = 0
                holder.pendingTileRegion.setEmpty()
                if (holder.boundPageIndex == pos &&
                    zoomScale > 1.05f &&
                    holder.pageView.isAttachedToWindow
                ) {
                    holder.pageView.setZoomTile(tile)
                } else {
                    tile.bitmap.release()
                }
            }
        }
    }

    fun prefetchAround(firstVisible: Int, lastVisible: Int) {
        if (aspectRatios.isEmpty() || viewportWidthPx <= 0) return
        engine.pruneOffscreenTasks(firstVisible, lastVisible)

        val baseWidth = baseRenderWidth()
        val indices = intArrayOf(
            lastVisible + 1,
            firstVisible - 1,
            lastVisible + 2,
            firstVisible - 2
        )
        for (idx in indices) {
            if (idx in aspectRatios.indices) {
                val cached = engine.getCachedBase(idx, baseWidth)
                if (cached != null) {
                    cached.release()
                } else {
                    engine.requestBasePage(idx, baseWidth, highPriority = false) { bmp ->
                        // Cached inside engine; release our callback ref immediately
                        bmp.release()
                    }
                }
            }
        }
    }

    fun releaseAllAttached(recyclerView: RecyclerView) {
        for (i in 0 until recyclerView.childCount) {
            val holder = recyclerView.getChildViewHolder(recyclerView.getChildAt(i)) as? PageViewHolder
                ?: continue
            holder.cancelAll()
            holder.pageView.clearBitmaps()
        }
    }

    private fun baseRenderWidth(): Int = PdfRenderEngine.clampBaseWidth(viewportWidthPx)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val pageView = PdfPageView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        return PageViewHolder(pageView)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holder.cancelAll()
        holder.boundPageIndex = position
        holder.boundViewportWidthPx = viewportWidthPx
        holder.boundZoomScale = zoomScale
        holder.boundTileGeneration = tileGeneration

        val lp = holder.pageView.layoutParams
        val targetH = computePageHeight(position)
        if (lp.height != targetH) {
            lp.height = targetH
            holder.pageView.layoutParams = lp
        }

        holder.pageView.clearZoomTile()
        holder.pageView.setPageState(
            index = position,
            zoom = zoomScale,
            panX = panX,
            nightMode = nightMode
        )

        bindBaseBitmap(holder, position)
    }

    override fun onViewAttachedToWindow(holder: PageViewHolder) {
        super.onViewAttachedToWindow(holder)
        val pos = holder.bindingAdapterPosition
        if (pos == RecyclerView.NO_POSITION || pos !in aspectRatios.indices) return

        // Re-sync ViewHolders returned from RecyclerView's offscreen cache (mCachedViews)
        // which re-attach without invoking onBindViewHolder().
        holder.boundPageIndex = pos

        val lp = holder.pageView.layoutParams
        val targetH = computePageHeight(pos)
        if (lp.height != targetH) {
            lp.height = targetH
            holder.pageView.layoutParams = lp
        }

        if (holder.boundTileGeneration != tileGeneration || zoomScale <= 1.05f) {
            holder.cancelTile()
            holder.pageView.clearZoomTile()
            holder.boundTileGeneration = tileGeneration
        }

        if (abs(holder.boundZoomScale - zoomScale) > 0.005f ||
            holder.boundViewportWidthPx != viewportWidthPx
        ) {
            holder.boundZoomScale = zoomScale
            holder.boundViewportWidthPx = viewportWidthPx
            holder.pageView.setZoomAndPan(zoomScale, panX)
        } else {
            holder.pageView.setPanX(panX)
        }

        holder.pageView.setNightMode(nightMode)

        val desiredBaseW = baseRenderWidth()
        if (holder.boundBaseWidthPx != desiredBaseW ||
            (!holder.pageView.hasUsableBaseBitmap() && holder.baseHandle == null)
        ) {
            bindBaseBitmap(holder, pos)
        }

        if (zoomScale > 1.05f &&
            holder.pageView.getCurrentZoomTile() == null &&
            holder.tileHandle == null
        ) {
            scheduleTileRefreshIfIdle()
        }
    }

    override fun onViewDetachedFromWindow(holder: PageViewHolder) {
        super.onViewDetachedFromWindow(holder)
        // Release high-res zoom tile immediately when scrolled off-screen (keep 1x baseBitmap)
        holder.cancelTile()
        holder.pageView.clearZoomTile()
    }

    private fun bindBaseBitmap(holder: PageViewHolder, position: Int) {
        holder.cancelBase()
        val renderW = baseRenderWidth()
        holder.boundBaseWidthPx = renderW
        val cached = engine.getCachedBase(position, renderW)
        if (cached != null) {
            holder.pageView.setBaseBitmap(cached)
            return
        }

        holder.pageView.setBaseBitmap(null)
        holder.baseHandle = engine.requestBasePage(
            pageIndex = position,
            targetWidthPx = renderW,
            highPriority = true
        ) { bmp ->
            if (holder.boundPageIndex == position && holder.boundBaseWidthPx == renderW) {
                holder.pageView.setBaseBitmap(bmp)
            } else {
                bmp.release()
            }
        }
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelAll()
        holder.boundPageIndex = RecyclerView.NO_POSITION
        holder.boundBaseWidthPx = 0
        holder.boundViewportWidthPx = 0
        holder.boundZoomScale = 1f
        holder.boundTileGeneration = -1
        holder.pageView.clearBitmaps()
    }

    override fun getItemCount(): Int = aspectRatios.size

    class PageViewHolder(val pageView: PdfPageView) : RecyclerView.ViewHolder(pageView) {
        var boundPageIndex: Int = RecyclerView.NO_POSITION
        var boundBaseWidthPx: Int = 0
        var boundViewportWidthPx: Int = 0
        var boundZoomScale: Float = 1f
        var boundTileGeneration: Int = -1
        var baseHandle: PdfRenderEngine.RenderHandle? = null
        var tileHandle: PdfRenderEngine.RenderHandle? = null
        var pendingTileZoomedW: Int = 0
        val pendingTileRegion = Rect()

        fun cancelBase() {
            baseHandle?.cancel()
            baseHandle = null
        }

        fun cancelTile() {
            tileHandle?.cancel()
            tileHandle = null
            pendingTileZoomedW = 0
            pendingTileRegion.setEmpty()
        }

        fun cancelAll() {
            cancelBase()
            cancelTile()
        }
    }
}
