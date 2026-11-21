package com.nyoki.lightpdf

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nyoki.lightpdf.data.RecentFilesStore
import com.nyoki.lightpdf.pdf.PdfDocumentInfo
import com.nyoki.lightpdf.pdf.PdfError
import com.nyoki.lightpdf.pdf.PdfRenderEngine
import com.nyoki.lightpdf.ui.PdfFastScroller
import com.nyoki.lightpdf.ui.PdfPagesAdapter
import com.nyoki.lightpdf.ui.ZoomablePdfRecyclerView
import java.util.Locale
import kotlin.math.roundToInt

class ViewerActivity : Activity() {

    private lateinit var store: RecentFilesStore
    private lateinit var engine: PdfRenderEngine
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var pagesAdapter: PdfPagesAdapter

    private lateinit var pdfRecyclerView: ZoomablePdfRecyclerView
    private lateinit var fastScroller: PdfFastScroller
    private lateinit var topToolbar: View
    private lateinit var tvDocumentTitle: TextView
    private lateinit var btnNightMode: ImageButton
    private lateinit var btnPageJump: ImageButton
    private lateinit var tvPageIndicator: TextView
    private lateinit var loadingContainer: View
    private lateinit var errorContainer: View
    private lateinit var tvErrorMessage: TextView
    private lateinit var btnErrorRemoveRecent: Button

    private val mainHandler = Handler(Looper.getMainLooper())

    private var documentUri: Uri? = null
    private var displayName: String = ""
    private var launchedFromRecent: Boolean = false
    private var pageCount: Int = 0
    private var currentPage: Int = 0
    private var isNightMode: Boolean = false
    private var isToolbarVisible: Boolean = true
    private var isDocumentReady: Boolean = false
    private var isScrubbing: Boolean = false

    private var pendingRestorePage: Int = 0
    private var pendingRestoreFraction: Float = 0f
    private var pendingRestoreZoom: Float = 1f
    private var pendingRestorePanXFraction: Float = 0f

    // Continuously maintained scroll anchor updated while layout coordinates are valid
    private var lastAnchorPage: Int = 0
    private var lastAnchorOffsetFraction: Float = 0f
    private var lastAnchorCenterFraction: Float = 0f
    private var lastAnchorAtDocumentTop: Boolean = true
    private var hasScrollAnchor: Boolean = false

    private var pageJumpDialog: Dialog? = null
    private var pageGapPx: Int = 0

    private val showLoadingRunnable = Runnable {
        if (!isDocumentReady && errorContainer.visibility != View.VISIBLE) {
            loadingContainer.visibility = View.VISIBLE
        }
    }

    private val hidePageIndicatorRunnable = Runnable {
        if (!isScrubbing) {
            tvPageIndicator.animate()
                .alpha(0f)
                .setDuration(220L)
                .withEndAction {
                    if (tvPageIndicator.alpha == 0f) {
                        tvPageIndicator.visibility = View.GONE
                    }
                }
                .start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_viewer)

        store = RecentFilesStore(this)
        engine = PdfRenderEngine(this)
        pageGapPx = resources.getDimensionPixelSize(R.dimen.pdf_page_gap)

        bindViews()
        setupInsets()
        setupRecyclerView()
        setupControls()

        val uri = extractUriFromIntent(intent)
        if (uri == null) {
            showError(getString(R.string.error_no_uri), allowRemoveRecent = false)
            return
        }
        documentUri = uri
        launchedFromRecent = intent.getBooleanExtra(EXTRA_FROM_RECENT, false)

        // Try taking persistable read permission when opened via content URI
        if ("content".equals(uri.scheme, ignoreCase = true)) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Non-persistable URI from external app; session grant is sufficient
            }
        }

        val extraName = intent.getStringExtra(EXTRA_DISPLAY_NAME)
        displayName = if (!extraName.isNullOrBlank()) {
            extraName
        } else {
            RecentFilesStore.resolveDisplayName(this, uri)
        }
        tvDocumentTitle.text = displayName

        isNightMode = savedInstanceState?.getBoolean(STATE_NIGHT_MODE) ?: store.isNightMode()
        updateNightModeIcon()

        if (savedInstanceState != null) {
            pendingRestorePage = savedInstanceState.getInt(STATE_PAGE, 0)
            pendingRestoreFraction = savedInstanceState.getFloat(STATE_FRACTION, 0f)
            pendingRestoreZoom = savedInstanceState.getFloat(STATE_ZOOM, 1f)
            pendingRestorePanXFraction = savedInstanceState.getFloat(STATE_PAN_X_FRACTION, 0f)
        } else {
            val saved = store.getReadingState(uri.toString())
            if (saved != null) {
                pendingRestorePage = saved.page
                pendingRestoreFraction = saved.offsetFraction
                pendingRestoreZoom = saved.zoom
                pendingRestorePanXFraction = saved.panXFraction
            }
        }

        openDocument(uri)
    }

    private fun bindViews() {
        pdfRecyclerView = findViewById(R.id.pdfRecyclerView)
        fastScroller = findViewById(R.id.pdfFastScroller)
        topToolbar = findViewById(R.id.topToolbar)
        tvDocumentTitle = findViewById(R.id.tvDocumentTitle)
        btnNightMode = findViewById(R.id.btnNightMode)
        btnPageJump = findViewById(R.id.btnPageJump)
        tvPageIndicator = findViewById(R.id.tvPageIndicator)
        loadingContainer = findViewById(R.id.loadingContainer)
        errorContainer = findViewById(R.id.errorContainer)
        tvErrorMessage = findViewById(R.id.tvErrorMessage)
        btnErrorRemoveRecent = findViewById(R.id.btnErrorRemoveRecent)
    }

    private fun setupInsets() {
        val root = findViewById<View>(android.R.id.content)
        val indicatorBaseMargin = (20 * resources.displayMetrics.density).roundToInt()

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )

            topToolbar.setPadding(bars.left, bars.top, bars.right, 0)

            val toolbarHeight = (56 * resources.displayMetrics.density).roundToInt()
            fastScroller.setVerticalInsets(
                topPx = bars.top + toolbarHeight + (12 * resources.displayMetrics.density).roundToInt(),
                bottomPx = bars.bottom + (24 * resources.displayMetrics.density).roundToInt(),
                rightPx = bars.right
            )

            val lp = tvPageIndicator.layoutParams as android.widget.FrameLayout.LayoutParams
            lp.bottomMargin = indicatorBaseMargin + bars.bottom
            tvPageIndicator.layoutParams = lp

            insets
        }
    }

    private fun setupRecyclerView() {
        layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        pagesAdapter = PdfPagesAdapter(engine)

        pdfRecyclerView.layoutManager = layoutManager
        pdfRecyclerView.adapter = pagesAdapter
        pdfRecyclerView.setItemViewCacheSize(2)
        pdfRecyclerView.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                outRect: Rect,
                view: View,
                parent: RecyclerView,
                state: RecyclerView.State
            ) {
                val pos = parent.getChildAdapterPosition(view)
                if (pos != RecyclerView.NO_POSITION && pos < (parent.adapter?.itemCount ?: 0) - 1) {
                    outRect.bottom = pageGapPx
                }
            }
        })

        pdfRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (!isDocumentReady) return
                updateCurrentPageAndPrefetch(showIndicator = dy != 0)
            }
        })

        pdfRecyclerView.zoomListener = object : ZoomablePdfRecyclerView.ZoomListener {
            override fun onSingleTap() {
                if (isDocumentReady) {
                    toggleToolbarVisibility()
                }
            }

            override fun onZoomChanged(zoom: Float, panX: Float) {
                if (zoom <= 1.05f) {
                    pagesAdapter.clearAllTiles(pdfRecyclerView)
                }
                showPageIndicator(withZoom = zoom > 1.05f)
            }

            override fun onPanChanged(panX: Float) {
                pagesAdapter.updatePanXOnAttached(pdfRecyclerView, panX)
            }

            override fun onViewportSettled() {
                if (isDocumentReady) {
                    updateLatestScrollAnchor()
                    pagesAdapter.refreshHighResTiles(pdfRecyclerView)
                }
            }

            override fun onViewportWidthChanged(
                newWidth: Int,
                oldWidth: Int,
                newHeight: Int,
                oldHeight: Int
            ) {
                if (!isDocumentReady) return
                if (!hasScrollAnchor) {
                    updateLatestScrollAnchor(referenceHeight = oldHeight)
                }
                val anchorPage = if (hasScrollAnchor) lastAnchorPage else currentPage
                val anchorCenterFrac = lastAnchorCenterFraction
                val anchorAtTop = lastAnchorAtDocumentTop

                engine.onViewportWidthChanged(newWidth)
                pagesAdapter.setViewportWidth(newWidth, pdfRecyclerView.panX, pdfRecyclerView)
                restoreScrollAnchorForViewportChange(
                    page = anchorPage,
                    centerFraction = anchorCenterFrac,
                    atDocumentTop = anchorAtTop,
                    viewportHeight = newHeight
                )
                pdfRecyclerView.post {
                    if (isDocumentReady) {
                        updateCurrentPageAndPrefetch(showIndicator = false)
                        pagesAdapter.refreshHighResTiles(pdfRecyclerView)
                    }
                }
            }
        }
    }

    private fun setupControls() {
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            finish()
        }
        btnNightMode.setOnClickListener {
            toggleNightMode()
        }
        btnPageJump.setOnClickListener {
            if (isDocumentReady && pageCount > 0) {
                showPageJumpDialog()
            }
        }
        tvPageIndicator.setOnClickListener {
            if (isDocumentReady && pageCount > 0) {
                showPageJumpDialog()
            }
        }
        findViewById<Button>(R.id.btnErrorClose).setOnClickListener {
            finish()
        }
        btnErrorRemoveRecent.setOnClickListener {
            documentUri?.let { store.removeRecent(it.toString()) }
            Toast.makeText(this, R.string.toast_removed_recent, Toast.LENGTH_SHORT).show()
            finish()
        }

        fastScroller.listener = object : PdfFastScroller.Listener {
            override fun onScrubToPage(pageIndex: Int) {
                if (!isDocumentReady) return
                currentPage = pageIndex
                setDirectScrollAnchor(pageIndex, offsetFraction = 0f)
                layoutManager.scrollToPositionWithOffset(pageIndex, 0)
                updatePageIndicatorText(withZoom = false)
            }

            override fun onScrubStateChanged(isDragging: Boolean) {
                isScrubbing = isDragging
                if (isDragging) {
                    mainHandler.removeCallbacks(hidePageIndicatorRunnable)
                } else if (isDocumentReady) {
                    pdfRecyclerView.post {
                        if (isDocumentReady && !isScrubbing) {
                            updateCurrentPageAndPrefetch(showIndicator = true)
                            pagesAdapter.refreshHighResTiles(pdfRecyclerView)
                        }
                    }
                }
            }
        }
    }

    private fun openDocument(uri: Uri) {
        // Delay loading indicator slightly so fast local PDFs open with zero spinner flash
        mainHandler.postDelayed(showLoadingRunnable, 120L)

        engine.open(uri, pendingRestorePage, object : PdfRenderEngine.OpenCallback {
            override fun onOpened(info: PdfDocumentInfo) {
                mainHandler.removeCallbacks(showLoadingRunnable)
                loadingContainer.visibility = View.GONE
                onPdfOpened(uri, info)
            }

            override fun onError(error: PdfError) {
                mainHandler.removeCallbacks(showLoadingRunnable)
                loadingContainer.visibility = View.GONE
                val msg = when (error) {
                    PdfError.PASSWORD_PROTECTED -> getString(R.string.error_password_protected)
                    PdfError.PERMISSION_OR_MISSING -> getString(R.string.error_permission_or_missing)
                    PdfError.CORRUPTED -> getString(R.string.error_corrupted)
                    PdfError.EMPTY -> getString(R.string.error_empty_pdf)
                    PdfError.UNKNOWN -> getString(R.string.error_unknown)
                }
                showError(msg, allowRemoveRecent = launchedFromRecent)
            }

            override fun onAspectRatiosUpdated(updatedPages: Map<Int, Float>) {
                if (isDocumentReady) {
                    pagesAdapter.updateAspectRatios(updatedPages, pdfRecyclerView)
                }
            }
        })
    }

    private fun onPdfOpened(uri: Uri, info: PdfDocumentInfo) {
        pageCount = info.pageCount
        isDocumentReady = true
        fastScroller.setPageCount(pageCount)

        store.recordOpened(uri.toString(), displayName, pageCount)

        val initWithWidth = { widthPx: Int ->
            val targetPage = pendingRestorePage.coerceIn(0, pageCount - 1)
            val targetZoom = pendingRestoreZoom.coerceIn(
                ZoomablePdfRecyclerView.MIN_ZOOM,
                ZoomablePdfRecyclerView.MAX_ZOOM
            )
            val maxPan = (widthPx * (targetZoom - 1f)).coerceAtLeast(0f)
            val targetPanX = if (targetZoom > 1.01f) {
                pendingRestorePanXFraction.coerceIn(0f, 1f) * maxPan
            } else {
                0f
            }
            currentPage = targetPage

            pdfRecyclerView.setZoomStateImmediate(targetZoom, targetPanX, widthPx)
            pagesAdapter.setDocument(
                ratios = info.aspectRatios,
                viewportWidth = widthPx,
                initialZoom = targetZoom,
                initialPanX = targetPanX,
                nightMode = isNightMode
            )

            restoreScrollAnchor(targetPage, pendingRestoreFraction)
            setDirectScrollAnchor(targetPage, pendingRestoreFraction)
            showPageIndicator(withZoom = targetZoom > 1.05f)

            pdfRecyclerView.post {
                if (isDocumentReady) {
                    updateCurrentPageAndPrefetch(showIndicator = false)
                    updatePageIndicatorText(withZoom = targetZoom > 1.05f)
                    if (targetZoom > 1.05f) {
                        pagesAdapter.refreshHighResTiles(pdfRecyclerView)
                    }
                }
            }
        }

        if (pdfRecyclerView.width > 0) {
            initWithWidth(pdfRecyclerView.width)
        } else {
            pdfRecyclerView.post {
                val w = pdfRecyclerView.width.takeIf { it > 0 }
                    ?: resources.displayMetrics.widthPixels
                initWithWidth(w)
            }
        }
    }

    private fun updateLatestScrollAnchor(referenceHeight: Int = pdfRecyclerView.height): Int {
        val first = layoutManager.findFirstVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return RecyclerView.NO_POSITION

        val effectiveHeight = referenceHeight.takeIf { it > 0 } ?: pdfRecyclerView.height
        val centerY = effectiveHeight / 2
        var bestPage = first
        var bestView: View? = null
        var bestDist = Int.MAX_VALUE
        for (i in 0 until pdfRecyclerView.childCount) {
            val child = pdfRecyclerView.getChildAt(i)
            val pos = pdfRecyclerView.getChildAdapterPosition(child)
            if (pos == RecyclerView.NO_POSITION) continue
            val dist = when {
                centerY < child.top -> child.top - centerY
                centerY > child.bottom -> centerY - child.bottom
                else -> 0
            }
            if (dist < bestDist) {
                bestDist = dist
                bestPage = pos
                bestView = child
                if (dist == 0) break
            }
        }

        val view = bestView ?: layoutManager.findViewByPosition(bestPage)
        if (view != null && view.height > 0) {
            val h = view.height.toFloat()
            lastAnchorPage = bestPage
            lastAnchorOffsetFraction = ((-view.top).toFloat() / h).coerceIn(-4f, 1f)
            lastAnchorCenterFraction = ((centerY - view.top).toFloat() / h).coerceIn(0f, 1f)
            lastAnchorAtDocumentTop = bestPage == 0 && view.top >= 0
            hasScrollAnchor = true
        }
        return bestPage
    }

    private fun setDirectScrollAnchor(
        page: Int,
        offsetFraction: Float,
        viewportHeight: Int = pdfRecyclerView.height
    ) {
        if (pageCount <= 0) return
        val safePage = page.coerceIn(0, pageCount - 1)
        val clampedFraction = offsetFraction.coerceIn(-4f, 1f)
        val pageH = pagesAdapter.computePageHeight(safePage)
        val effectiveHeight = viewportHeight.takeIf { it > 0 } ?: pdfRecyclerView.height
        var offsetPx = -(pageH * clampedFraction).roundToInt()
        if (safePage == 0) {
            offsetPx = offsetPx.coerceAtMost(0)
        } else if (effectiveHeight > 0) {
            offsetPx = offsetPx.coerceAtMost(effectiveHeight / 2)
        }
        lastAnchorPage = safePage
        lastAnchorOffsetFraction = clampedFraction
        lastAnchorAtDocumentTop = safePage == 0 && offsetPx >= 0
        lastAnchorCenterFraction = if (pageH > 0 && effectiveHeight > 0) {
            ((effectiveHeight / 2f - offsetPx) / pageH.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        hasScrollAnchor = true
    }

    private fun updateCurrentPageAndPrefetch(showIndicator: Boolean) {
        val first = layoutManager.findFirstVisibleItemPosition()
        val last = layoutManager.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return

        val centerPage = updateLatestScrollAnchor()
        if (centerPage != RecyclerView.NO_POSITION) {
            currentPage = centerPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        }

        if (showIndicator) {
            val range = (pdfRecyclerView.computeVerticalScrollRange() - pdfRecyclerView.computeVerticalScrollExtent())
                .coerceAtLeast(1)
            val progress = pdfRecyclerView.computeVerticalScrollOffset().toFloat() / range.toFloat()
            fastScroller.updateFromScroll(currentPage, progress)
            showPageIndicator(withZoom = false)
        }

        pagesAdapter.prefetchAround(first, last.coerceAtLeast(first))
    }

    private fun showPageIndicator(withZoom: Boolean) {
        if (pageCount <= 0) return
        updatePageIndicatorText(withZoom)
        mainHandler.removeCallbacks(hidePageIndicatorRunnable)
        tvPageIndicator.animate().cancel()
        if (tvPageIndicator.visibility != View.VISIBLE || tvPageIndicator.alpha < 1f) {
            tvPageIndicator.visibility = View.VISIBLE
            tvPageIndicator.animate().alpha(1f).setDuration(120L).start()
        }
        mainHandler.postDelayed(hidePageIndicatorRunnable, INDICATOR_HIDE_DELAY_MS)
    }

    private fun updatePageIndicatorText(withZoom: Boolean) {
        val baseText = getString(R.string.page_indicator_format, currentPage + 1, pageCount)
        tvPageIndicator.text = if (withZoom && pdfRecyclerView.committedZoom > 1.05f) {
            val pct = (pdfRecyclerView.committedZoom * 100f).roundToInt()
            "$baseText  •  ${getString(R.string.zoom_indicator_format, pct)}"
        } else {
            baseText
        }
    }

    private fun toggleToolbarVisibility() {
        isToolbarVisible = !isToolbarVisible
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (isToolbarVisible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
            topToolbar.animate().cancel()
            topToolbar.visibility = View.VISIBLE
            topToolbar.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(160L)
                .start()
            showPageIndicator(withZoom = pdfRecyclerView.committedZoom > 1.05f)
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            topToolbar.animate().cancel()
            topToolbar.animate()
                .translationY(-topToolbar.height.toFloat().coerceAtLeast(120f))
                .alpha(0f)
                .setDuration(160L)
                .withEndAction {
                    if (!isToolbarVisible) {
                        topToolbar.visibility = View.GONE
                    }
                }
                .start()
        }
    }

    private fun toggleNightMode() {
        isNightMode = !isNightMode
        store.setNightMode(isNightMode)
        updateNightModeIcon()
        pagesAdapter.setNightMode(isNightMode, pdfRecyclerView)
        Toast.makeText(
            this,
            if (isNightMode) R.string.night_mode_on else R.string.night_mode_off,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateNightModeIcon() {
        btnNightMode.setImageResource(
            if (isNightMode) R.drawable.ic_day_mode else R.drawable.ic_night_mode
        )
    }

    private fun showPageJumpDialog() {
        pageJumpDialog?.dismiss()
        val dialog = Dialog(this, R.style.Theme_LightPdf_Dialog)
        pageJumpDialog = dialog
        dialog.setOnDismissListener {
            if (pageJumpDialog === dialog) {
                pageJumpDialog = null
            }
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val rootParent = findViewById<ViewGroup>(android.R.id.content)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_page_jump, rootParent, false)
        dialog.setContentView(view)

        val etPage = view.findViewById<EditText>(R.id.etPageNumber)
        val tvError = view.findViewById<TextView>(R.id.tvJumpError)
        etPage.hint = getString(R.string.dialog_jump_hint, pageCount)
        etPage.setText(String.format(Locale.US, "%d", currentPage + 1))
        etPage.selectAll()

        val performJump = {
            val num = etPage.text.toString().trim().toIntOrNull()
            if (num == null || num < 1 || num > pageCount) {
                tvError.text = getString(R.string.dialog_jump_invalid, pageCount)
                tvError.visibility = View.VISIBLE
            } else {
                val targetIndex = num - 1
                currentPage = targetIndex
                setDirectScrollAnchor(targetIndex, offsetFraction = 0f)
                layoutManager.scrollToPositionWithOffset(targetIndex, 0)
                showPageIndicator(withZoom = false)
                pdfRecyclerView.post {
                    if (isDocumentReady) {
                        updateCurrentPageAndPrefetch(showIndicator = true)
                        pagesAdapter.refreshHighResTiles(pdfRecyclerView)
                    }
                }
                dialog.dismiss()
            }
        }

        etPage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                performJump()
                true
            } else {
                false
            }
        }
        view.findViewById<Button>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<Button>(R.id.btnConfirm).setOnClickListener { performJump() }

        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        etPage.requestFocus()
    }

    private fun showError(message: String, allowRemoveRecent: Boolean) {
        tvErrorMessage.text = message
        btnErrorRemoveRecent.visibility = if (allowRemoveRecent) View.VISIBLE else View.GONE
        errorContainer.visibility = View.VISIBLE
        btnPageJump.visibility = View.GONE
    }

    private fun captureScrollAnchor(): Pair<Int, Float> {
        if (!pdfRecyclerView.isLayoutRequested &&
            pdfRecyclerView.childCount > 0 &&
            pdfRecyclerView.height > 0
        ) {
            updateLatestScrollAnchor()
        }
        return if (hasScrollAnchor) {
            lastAnchorPage to lastAnchorOffsetFraction
        } else {
            currentPage to 0f
        }
    }

    private fun restoreScrollAnchor(page: Int, offsetFraction: Float) {
        val safePage = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        val pageH = pagesAdapter.computePageHeight(safePage)
        var offsetPx = -(pageH * offsetFraction.coerceIn(-4f, 1f)).roundToInt()
        if (safePage == 0) {
            offsetPx = offsetPx.coerceAtMost(0)
        } else if (pdfRecyclerView.height > 0) {
            offsetPx = offsetPx.coerceAtMost(pdfRecyclerView.height / 2)
        }
        layoutManager.scrollToPositionWithOffset(safePage, offsetPx)
    }

    private fun restoreScrollAnchorForViewportChange(
        page: Int,
        centerFraction: Float,
        atDocumentTop: Boolean,
        viewportHeight: Int
    ) {
        val safePage = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        val offsetPx = if (atDocumentTop && safePage == 0) {
            0
        } else {
            val pageH = pagesAdapter.computePageHeight(safePage)
            val centerY = (viewportHeight.takeIf { it > 0 } ?: pdfRecyclerView.height) / 2
            var computed = (centerY - pageH * centerFraction.coerceIn(0f, 1f)).roundToInt()
            if (safePage == 0) {
                computed = computed.coerceAtMost(0)
            } else if (centerY > 0) {
                computed = computed.coerceAtMost(centerY)
            }
            computed
        }
        layoutManager.scrollToPositionWithOffset(safePage, offsetPx)
    }

    private fun capturePanXFraction(): Float {
        val zoom = pdfRecyclerView.committedZoom
        val w = pdfRecyclerView.width
        if (zoom <= 1.01f || w <= 0) return 0f
        val maxPan = w * (zoom - 1f)
        return if (maxPan > 0f) (pdfRecyclerView.panX / maxPan).coerceIn(0f, 1f) else 0f
    }

    private fun persistReadingState() {
        val uri = documentUri ?: return
        if (!isDocumentReady || pageCount <= 0) return
        val (page, fraction) = captureScrollAnchor()
        store.saveReadingState(
            uriString = uri.toString(),
            page = page,
            offsetFraction = fraction,
            zoom = pdfRecyclerView.committedZoom,
            pageCount = pageCount,
            displayName = displayName,
            panXFraction = capturePanXFraction()
        )
    }

    override fun onPause() {
        super.onPause()
        persistReadingState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val (page, fraction) = if (isDocumentReady) {
            captureScrollAnchor()
        } else {
            pendingRestorePage to pendingRestoreFraction
        }
        val zoom = if (isDocumentReady) pdfRecyclerView.committedZoom else pendingRestoreZoom
        val panXFraction = if (isDocumentReady) capturePanXFraction() else pendingRestorePanXFraction
        outState.putInt(STATE_PAGE, page)
        outState.putFloat(STATE_FRACTION, fraction)
        outState.putFloat(STATE_ZOOM, zoom)
        outState.putFloat(STATE_PAN_X_FRACTION, panXFraction)
        outState.putBoolean(STATE_NIGHT_MODE, isNightMode)
    }

    override fun onDestroy() {
        pageJumpDialog?.dismiss()
        pageJumpDialog = null
        mainHandler.removeCallbacksAndMessages(null)
        pagesAdapter.releaseAllAttached(pdfRecyclerView)
        pdfRecyclerView.adapter = null
        pdfRecyclerView.recycledViewPool.clear()
        engine.close()
        super.onDestroy()
    }

    private fun extractUriFromIntent(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                } ?: intent.data
            }

            else -> intent.data
        }
    }

    companion object {
        const val EXTRA_DISPLAY_NAME = "extra_display_name"
        const val EXTRA_FROM_RECENT = "extra_from_recent"

        private const val STATE_PAGE = "state_page"
        private const val STATE_FRACTION = "state_fraction"
        private const val STATE_ZOOM = "state_zoom"
        private const val STATE_PAN_X_FRACTION = "state_pan_x_fraction"
        private const val STATE_NIGHT_MODE = "state_night_mode"
        private const val INDICATOR_HIDE_DELAY_MS = 2200L
    }
}
