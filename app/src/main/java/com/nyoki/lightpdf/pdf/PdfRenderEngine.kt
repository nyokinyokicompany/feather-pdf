package com.nyoki.lightpdf.pdf

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.LruCache
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

enum class PdfError {
    PASSWORD_PROTECTED,
    PERMISSION_OR_MISSING,
    CORRUPTED,
    EMPTY,
    UNKNOWN
}

data class PdfDocumentInfo(
    val pageCount: Int,
    val aspectRatios: FloatArray
)

data class ZoomTile(
    val pageIndex: Int,
    val zoomedPageWidthPx: Int,
    val zoomedPageHeightPx: Int,
    val region: Rect,
    val bitmap: RefCountedBitmap
)

/**
 * Single-threaded, priority-scheduled PDF rendering engine built on Android's PdfRenderer.
 *
 * All PdfRenderer calls are serialized on one dedicated background thread.
 * Visible pages preempt prefetch tasks, off-screen tasks are pruned immediately,
 * and ARGB_8888 bitmaps are cached in an LruCache and recycled through [BitmapPool].
 */
class PdfRenderEngine(context: Context) {

    interface OpenCallback {
        fun onOpened(info: PdfDocumentInfo)
        fun onError(error: PdfError)
        fun onAspectRatiosUpdated(updatedPages: Map<Int, Float>)
    }

    interface RenderHandle {
        fun cancel()
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val maxCacheBytes: Int
    private val bitmapPool: BitmapPool
    private val cacheLock = Any()
    private val baseCache: LruCache<Int, CachedPage>

    private val queueLock = Object()
    private val pendingTasks = ArrayList<ScheduledTask>(16)
    private val sequenceGen = AtomicLong(0L)

    @Volatile
    private var closed = false

    @Volatile
    private var currentBaseWidthPx = 0

    // Owned exclusively by the render thread
    private var parcelFileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private var tempCacheFile: File? = null
    private var pageCount = 0
    private var pageAspectRatios = FloatArray(0)
    private val reusableMatrix = Matrix()

    private val workerThread = Thread({
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        runWorkerLoop()
    }, "LightPdf-RenderThread").apply {
        isDaemon = true
        start()
    }

    private data class CachedPage(
        val widthPx: Int,
        val bitmap: RefCountedBitmap
    )

    private class ScheduledTask(
        val priority: Int,
        val seq: Long,
        val pageIndex: Int,
        val dedupeKey: String?,
        val canceled: AtomicBoolean = AtomicBoolean(false),
        val action: () -> Unit
    ) : RenderHandle {
        override fun cancel() {
            canceled.set(true)
        }
    }

    init {
        val maxMem = Runtime.getRuntime().maxMemory()
        // Target 1/6 of available heap (between 1/4 and 1/8 per spec), capped conservatively
        maxCacheBytes = (maxMem / 6L).coerceIn(16L * 1024 * 1024, 96L * 1024 * 1024).toInt()
        // Size BitmapPool to hold at least 2-3 full-resolution base pages (up to 1600x2400 ARGB_8888 ≈ 15.4MB)
        val poolBytes = (maxCacheBytes * 3 / 4).coerceIn(32 * 1024 * 1024, 48 * 1024 * 1024)
        bitmapPool = BitmapPool(poolBytes)

        val cacheKb = (maxCacheBytes / 1024).coerceAtLeast(4096)
        baseCache = object : LruCache<Int, CachedPage>(cacheKb) {
            override fun sizeOf(key: Int, value: CachedPage): Int {
                return (value.bitmap.byteCount / 1024).coerceAtLeast(1)
            }

            override fun entryRemoved(
                evicted: Boolean,
                key: Int,
                oldValue: CachedPage,
                newValue: CachedPage?
            ) {
                oldValue.bitmap.release()
            }
        }
    }

    fun open(uri: Uri, initialTargetPage: Int, callback: OpenCallback) {
        enqueueTask(
            priority = PRIORITY_OPEN,
            pageIndex = -1,
            dedupeKey = "open"
        ) {
            openOnWorker(uri, initialTargetPage, callback)
        }
    }

    /**
     * Synchronously acquires a retained base bitmap from cache if width matches.
     * Caller owns the returned reference and MUST call [RefCountedBitmap.release].
     */
    fun getCachedBase(pageIndex: Int, expectedWidthPx: Int): RefCountedBitmap? {
        val safeW = clampBaseWidth(expectedWidthPx)
        synchronized(cacheLock) {
            val cached = baseCache.get(pageIndex) ?: return null
            if (cached.widthPx != safeW) return null
            return if (cached.bitmap.retain()) cached.bitmap else null
        }
    }

    /**
     * Requests a 1x base page bitmap on the dedicated render thread.
     * Callback receives a retained [RefCountedBitmap] on the main thread (caller must release it).
     */
    fun requestBasePage(
        pageIndex: Int,
        targetWidthPx: Int,
        highPriority: Boolean,
        onRendered: (RefCountedBitmap) -> Unit
    ): RenderHandle {
        val safeW = clampBaseWidth(targetWidthPx)
        val priority = if (highPriority) PRIORITY_VISIBLE_BASE else PRIORITY_PREFETCH
        val dedupeKey = "base:$pageIndex:$safeW"

        lateinit var task: ScheduledTask
        task = enqueueTask(
            priority = priority,
            pageIndex = pageIndex,
            dedupeKey = dedupeKey
        ) {
            if (closed || task.canceled.get()) return@enqueueTask

            // Double-check cache on worker thread before rendering
            val cached = getCachedBase(pageIndex, safeW)
            if (cached != null) {
                deliverOnMain(task, cached, onRendered)
                return@enqueueTask
            }

            val rendered = renderBaseOnWorker(pageIndex, safeW) ?: return@enqueueTask

            if (closed) {
                rendered.release()
                return@enqueueTask
            }

            // Store in LruCache (cache takes ownership of initial ref if width still matches)
            val forCaller: RefCountedBitmap?
            synchronized(cacheLock) {
                if (currentBaseWidthPx == 0 || currentBaseWidthPx == safeW) {
                    currentBaseWidthPx = safeW
                    if (rendered.retain()) {
                        forCaller = rendered
                    } else {
                        forCaller = null
                    }
                    baseCache.put(pageIndex, CachedPage(safeW, rendered))
                } else {
                    forCaller = rendered
                }
            }

            if (forCaller != null) {
                deliverOnMain(task, forCaller, onRendered)
            }
        }
        return task
    }

    /**
     * Requests a high-resolution zoomed tile for the visible sub-region of a page.
     * Callback receives a [ZoomTile] whose bitmap the caller owns and MUST release.
     */
    fun requestZoomTile(
        pageIndex: Int,
        zoomedPageWidthPx: Int,
        zoomedPageHeightPx: Int,
        region: Rect,
        onRendered: (ZoomTile) -> Unit
    ): RenderHandle {
        val safeRegion = Rect(
            region.left.coerceIn(0, zoomedPageWidthPx),
            region.top.coerceIn(0, zoomedPageHeightPx),
            region.right.coerceIn(0, zoomedPageWidthPx),
            region.bottom.coerceIn(0, zoomedPageHeightPx)
        )
        if (safeRegion.width() < 8 || safeRegion.height() < 8) {
            return NoOpHandle
        }

        val dedupeKey = "tile:$pageIndex"
        lateinit var task: ScheduledTask
        task = enqueueTask(
            priority = PRIORITY_VISIBLE_TILE,
            pageIndex = pageIndex,
            dedupeKey = dedupeKey
        ) {
            if (closed || task.canceled.get()) return@enqueueTask
            val tile = renderTileOnWorker(
                pageIndex = pageIndex,
                zoomedW = zoomedPageWidthPx,
                zoomedH = zoomedPageHeightPx,
                region = safeRegion
            ) ?: return@enqueueTask

            if (closed || task.canceled.get()) {
                tile.bitmap.release()
                return@enqueueTask
            }

            mainHandler.post {
                if (closed || task.canceled.get()) {
                    tile.bitmap.release()
                } else {
                    onRendered(tile)
                }
            }
        }
        return task
    }

    /**
     * Cancels queued render tasks for pages far outside the visible range.
     */
    fun pruneOffscreenTasks(firstVisible: Int, lastVisible: Int) {
        val keepStart = (firstVisible - 3).coerceAtLeast(0)
        val keepEnd = lastVisible + 3
        synchronized(queueLock) {
            val iter = pendingTasks.iterator()
            while (iter.hasNext()) {
                val t = iter.next()
                if (t.pageIndex >= 0 && (t.pageIndex < keepStart || t.pageIndex > keepEnd)) {
                    if (t.priority == PRIORITY_VISIBLE_BASE ||
                        t.priority == PRIORITY_VISIBLE_TILE ||
                        t.priority == PRIORITY_PREFETCH
                    ) {
                        t.canceled.set(true)
                        iter.remove()
                    }
                }
            }
        }
    }

    /**
     * Clears base cache when viewport width changes (rotation / multi-window resize).
     */
    fun onViewportWidthChanged(newWidthPx: Int) {
        if (newWidthPx <= 0) return
        val clampedW = clampBaseWidth(newWidthPx)
        val baseWidthChanged: Boolean
        synchronized(cacheLock) {
            baseWidthChanged = clampedW != currentBaseWidthPx
            if (baseWidthChanged) {
                currentBaseWidthPx = clampedW
                baseCache.evictAll()
            }
        }
        synchronized(queueLock) {
            val iter = pendingTasks.iterator()
            while (iter.hasNext()) {
                val t = iter.next()
                val shouldCancel = if (baseWidthChanged) {
                    t.priority == PRIORITY_VISIBLE_BASE ||
                        t.priority == PRIORITY_VISIBLE_TILE ||
                        t.priority == PRIORITY_PREFETCH
                } else {
                    t.priority == PRIORITY_VISIBLE_TILE
                }
                if (shouldCancel) {
                    t.canceled.set(true)
                    iter.remove()
                }
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        synchronized(queueLock) {
            for (t in pendingTasks) {
                t.canceled.set(true)
            }
            pendingTasks.clear()
            pendingTasks.add(
                ScheduledTask(
                    priority = PRIORITY_CLOSE,
                    seq = sequenceGen.incrementAndGet(),
                    pageIndex = -1,
                    dedupeKey = null,
                    action = {}
                )
            )
            queueLock.notifyAll()
        }
        synchronized(cacheLock) {
            baseCache.evictAll()
        }
    }

    private fun deliverOnMain(
        task: ScheduledTask,
        bitmap: RefCountedBitmap,
        onRendered: (RefCountedBitmap) -> Unit
    ) {
        mainHandler.post {
            if (closed || task.canceled.get()) {
                bitmap.release()
            } else {
                onRendered(bitmap)
            }
        }
    }

    private fun enqueueTask(
        priority: Int,
        pageIndex: Int,
        dedupeKey: String?,
        action: () -> Unit
    ): ScheduledTask {
        val task = ScheduledTask(
            priority = priority,
            seq = sequenceGen.incrementAndGet(),
            pageIndex = pageIndex,
            dedupeKey = dedupeKey,
            action = action
        )
        synchronized(queueLock) {
            if (closed && priority != PRIORITY_CLOSE) {
                task.canceled.set(true)
                return task
            }
            if (dedupeKey != null) {
                val iter = pendingTasks.iterator()
                while (iter.hasNext()) {
                    val existing = iter.next()
                    if (existing.dedupeKey == dedupeKey) {
                        // Replace older lower-or-equal priority request for the same target
                        if (priority <= existing.priority) {
                            existing.canceled.set(true)
                            iter.remove()
                        } else {
                            // Keep existing higher-priority request
                            task.canceled.set(true)
                            return existing
                        }
                    }
                }
            }
            pendingTasks.add(task)
            queueLock.notifyAll()
        }
        return task
    }

    private fun runWorkerLoop() {
        while (true) {
            val next = synchronized(queueLock) {
                while (pendingTasks.isEmpty()) {
                    try {
                        queueLock.wait()
                    } catch (_: InterruptedException) {
                        if (closed) return@synchronized null
                    }
                }
                // Pick highest priority (lowest number); within same priority pick newest (LIFO)
                // except meta scan which runs FIFO.
                var bestIdx = 0
                var best = pendingTasks[0]
                for (i in 1 until pendingTasks.size) {
                    val c = pendingTasks[i]
                    val isBetter = when {
                        c.priority < best.priority -> true
                        c.priority > best.priority -> false
                        c.priority == PRIORITY_META_SCAN -> c.seq < best.seq
                        else -> c.seq > best.seq
                    }
                    if (isBetter) {
                        best = c
                        bestIdx = i
                    }
                }
                pendingTasks.removeAt(bestIdx)
            } ?: break

            if (next.priority == PRIORITY_CLOSE) {
                break
            }
            if (next.canceled.get() || closed) {
                continue
            }
            try {
                next.action()
            } catch (_: Throwable) {
                // Keep render thread alive for subsequent pages
            }
        }
        releaseNativeResourcesOnWorker(closing = true)
    }

    private fun openOnWorker(uri: Uri, initialTargetPage: Int, callback: OpenCallback) {
        if (closed) return
        releaseNativeResourcesOnWorker(closing = false)

        var pfd: ParcelFileDescriptor? = null
        try {
            pfd = openParcelFileDescriptor(uri)
        } catch (_: SecurityException) {
            postError(callback, PdfError.PERMISSION_OR_MISSING)
            return
        } catch (_: FileNotFoundException) {
            postError(callback, PdfError.PERMISSION_OR_MISSING)
            return
        } catch (_: Exception) {
            postError(callback, PdfError.PERMISSION_OR_MISSING)
            return
        }

        if (pfd == null) {
            postError(callback, PdfError.PERMISSION_OR_MISSING)
            return
        }

        var renderer: PdfRenderer?
        try {
            renderer = PdfRenderer(pfd)
        } catch (_: SecurityException) {
            // Android's PdfRenderer throws SecurityException when the PDF requires a password.
            safeClose(pfd)
            postError(callback, PdfError.PASSWORD_PROTECTED)
            return
        } catch (_: IOException) {
            // Some content providers return non-seekable streams; fallback to temp cache file.
            safeClose(pfd)
            when (val fallback = tryCopyToTempFileAndOpen(uri)) {
                is TempOpenResult.Success -> {
                    pfd = fallback.pfd
                    renderer = fallback.renderer
                    tempCacheFile = fallback.tempFile
                }

                is TempOpenResult.Failure -> {
                    postError(callback, fallback.error)
                    return
                }
            }
        } catch (_: Exception) {
            safeClose(pfd)
            postError(callback, PdfError.CORRUPTED)
            return
        }

        val count = renderer.pageCount
        if (count <= 0) {
            safeClose(renderer)
            safeClose(pfd)
            postError(callback, PdfError.EMPTY)
            return
        }

        parcelFileDescriptor = pfd
        pdfRenderer = renderer
        pageCount = count

        val ratios = FloatArray(count)
        val scanned = BooleanArray(count)

        val defaultRatio = readPageAspectRatio(renderer, 0)
        ratios[0] = defaultRatio
        scanned[0] = true

        if (count <= EAGER_SCAN_THRESHOLD) {
            for (i in 1 until count) {
                if (closed) return
                ratios[i] = readPageAspectRatio(renderer, i)
                scanned[i] = true
            }
        } else {
            // Fast path for huge PDFs: read around initial page & first 20 immediately
            val target = initialTargetPage.coerceIn(0, count - 1)
            val ranges = listOf(
                1..minOf(20, count - 1),
                maxOf(1, target - 8)..minOf(count - 1, target + 16)
            )
            for (r in ranges) {
                for (i in r) {
                    if (!scanned[i]) {
                        ratios[i] = readPageAspectRatio(renderer, i)
                        scanned[i] = true
                    }
                }
            }
            for (i in 0 until count) {
                if (!scanned[i]) {
                    ratios[i] = defaultRatio
                }
            }
        }

        pageAspectRatios = ratios
        val infoCopy = PdfDocumentInfo(count, ratios.copyOf())
        mainHandler.post {
            if (!closed) {
                callback.onOpened(infoCopy)
            }
        }

        // Schedule background aspect-ratio scan for remaining pages of huge PDFs
        if (count > EAGER_SCAN_THRESHOLD) {
            scheduleMetaScanChunk(scanned, startIndex = 1, callback = callback)
        }
    }

    private fun scheduleMetaScanChunk(
        scanned: BooleanArray,
        startIndex: Int,
        callback: OpenCallback
    ) {
        if (closed || startIndex >= scanned.size) return
        enqueueTask(
            priority = PRIORITY_META_SCAN,
            pageIndex = -1,
            dedupeKey = "metascan:$startIndex"
        ) {
            val renderer = pdfRenderer ?: return@enqueueTask
            val updates = HashMap<Int, Float>()
            var processed = 0
            var idx = startIndex
            while (idx < scanned.size && processed < META_SCAN_CHUNK_SIZE) {
                if (closed) return@enqueueTask
                if (!scanned[idx]) {
                    val actual = readPageAspectRatio(renderer, idx)
                    scanned[idx] = true
                    val prev = pageAspectRatios[idx]
                    if (kotlin.math.abs(actual - prev) > 0.01f) {
                        pageAspectRatios[idx] = actual
                        updates[idx] = actual
                    }
                    processed++
                }
                idx++
            }
            if (updates.isNotEmpty() && !closed) {
                mainHandler.post {
                    if (!closed) {
                        callback.onAspectRatiosUpdated(updates)
                    }
                }
            }
            if (idx < scanned.size && !closed) {
                scheduleMetaScanChunk(scanned, idx, callback)
            }
        }
    }

    private fun readPageAspectRatio(renderer: PdfRenderer, pageIndex: Int): Float {
        return try {
            renderer.openPage(pageIndex).use { page ->
                if (page.width > 0 && page.height > 0) {
                    (page.height.toFloat() / page.width.toFloat()).coerceIn(0.25f, 4.5f)
                } else {
                    DEFAULT_A4_RATIO
                }
            }
        } catch (_: Exception) {
            DEFAULT_A4_RATIO
        }
    }

    private fun openParcelFileDescriptor(uri: Uri): ParcelFileDescriptor? {
        return if ("file".equals(uri.scheme, ignoreCase = true)) {
            val path = uri.path ?: return null
            ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        } else {
            appContext.contentResolver.openFileDescriptor(uri, "r")
        }
    }

    private fun tryCopyToTempFileAndOpen(uri: Uri): TempOpenResult {
        val tempFile = File(appContext.cacheDir, "pdf_stream_${System.currentTimeMillis()}.pdf")
        try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output, bufferSize = 32 * 1024)
                }
            } ?: return TempOpenResult.Failure(PdfError.PERMISSION_OR_MISSING)
        } catch (_: SecurityException) {
            tempFile.delete()
            return TempOpenResult.Failure(PdfError.PERMISSION_OR_MISSING)
        } catch (_: FileNotFoundException) {
            tempFile.delete()
            return TempOpenResult.Failure(PdfError.PERMISSION_OR_MISSING)
        } catch (_: Exception) {
            tempFile.delete()
            return TempOpenResult.Failure(PdfError.CORRUPTED)
        }

        if (tempFile.length() == 0L) {
            tempFile.delete()
            return TempOpenResult.Failure(PdfError.CORRUPTED)
        }

        val pfd = try {
            ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (_: Exception) {
            tempFile.delete()
            return TempOpenResult.Failure(PdfError.CORRUPTED)
        }

        val renderer = try {
            PdfRenderer(pfd)
        } catch (_: SecurityException) {
            safeClose(pfd)
            tempFile.delete()
            return TempOpenResult.Failure(PdfError.PASSWORD_PROTECTED)
        } catch (_: Exception) {
            safeClose(pfd)
            tempFile.delete()
            return TempOpenResult.Failure(PdfError.CORRUPTED)
        }
        return TempOpenResult.Success(pfd, renderer, tempFile)
    }

    private fun renderBaseOnWorker(pageIndex: Int, targetWidthPx: Int): RefCountedBitmap? {
        val renderer = pdfRenderer ?: return null
        if (pageIndex < 0 || pageIndex >= pageCount) return null

        return try {
            renderer.openPage(pageIndex).use { page ->
                val ratio = if (page.width > 0) {
                    (page.height.toFloat() / page.width.toFloat()).coerceIn(0.25f, 4.5f)
                } else {
                    DEFAULT_A4_RATIO
                }
                val targetHeightPx = (targetWidthPx * ratio).roundToInt().coerceIn(64, MAX_BASE_HEIGHT_PX)

                renderPageWithOomFallback(page, targetWidthPx, targetHeightPx, matrix = null)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun renderTileOnWorker(
        pageIndex: Int,
        zoomedW: Int,
        zoomedH: Int,
        region: Rect
    ): ZoomTile? {
        val renderer = pdfRenderer ?: return null
        if (pageIndex < 0 || pageIndex >= pageCount) return null

        // Limit tile bitmap dimensions so memory stays bounded even at 5x zoom
        var tileW = region.width()
        var tileH = region.height()
        var downscale = 1f
        val maxDim = MAX_TILE_DIM_PX
        if (tileW > maxDim || tileH > maxDim) {
            downscale = minOf(maxDim.toFloat() / tileW, maxDim.toFloat() / tileH)
            tileW = (tileW * downscale).roundToInt().coerceAtLeast(16)
            tileH = (tileH * downscale).roundToInt().coerceAtLeast(16)
        }

        return try {
            renderer.openPage(pageIndex).use { page ->
                if (page.width <= 0 || page.height <= 0) return null
                val scaleX = (zoomedW.toFloat() / page.width.toFloat()) * downscale
                val scaleY = (zoomedH.toFloat() / page.height.toFloat()) * downscale
                reusableMatrix.reset()
                reusableMatrix.setScale(scaleX, scaleY)
                reusableMatrix.postTranslate(-region.left * downscale, -region.top * downscale)

                val bmp = renderPageWithOomFallback(page, tileW, tileH, reusableMatrix) ?: return null
                ZoomTile(
                    pageIndex = pageIndex,
                    zoomedPageWidthPx = zoomedW,
                    zoomedPageHeightPx = zoomedH,
                    region = Rect(region),
                    bitmap = bmp
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun renderPageWithOomFallback(
        page: PdfRenderer.Page,
        width: Int,
        height: Int,
        matrix: Matrix?
    ): RefCountedBitmap? {
        return try {
            val ref = bitmapPool.acquire(width, height)
            ref.bitmap.eraseColor(Color.WHITE)
            page.render(ref.bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            ref
        } catch (_: OutOfMemoryError) {
            synchronized(cacheLock) {
                baseCache.evictAll()
            }
            bitmapPool.clear()
            if (matrix != null) return null
            val fallbackW = (width * 0.7f).roundToInt().coerceAtLeast(64)
            val fallbackH = (height * 0.7f).roundToInt().coerceAtLeast(64)
            try {
                val ref = bitmapPool.acquire(fallbackW, fallbackH)
                ref.bitmap.eraseColor(Color.WHITE)
                page.render(ref.bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                ref
            } catch (_: OutOfMemoryError) {
                null
            }
        }
    }

    private fun postError(callback: OpenCallback, error: PdfError) {
        mainHandler.post {
            if (!closed) {
                callback.onError(error)
            }
        }
    }

    private fun releaseNativeResourcesOnWorker(closing: Boolean) {
        safeClose(pdfRenderer)
        pdfRenderer = null
        safeClose(parcelFileDescriptor)
        parcelFileDescriptor = null
        tempCacheFile?.let { file ->
            try {
                file.delete()
            } catch (_: Exception) {
            }
        }
        tempCacheFile = null
        if (closing) {
            bitmapPool.close()
        } else {
            bitmapPool.clear()
        }
    }

    private fun safeClose(closeable: AutoCloseable?) {
        try {
            closeable?.close()
        } catch (_: Exception) {
        }
    }

    private sealed class TempOpenResult {
        class Success(
            val pfd: ParcelFileDescriptor,
            val renderer: PdfRenderer,
            val tempFile: File
        ) : TempOpenResult()

        class Failure(val error: PdfError) : TempOpenResult()
    }

    private object NoOpHandle : RenderHandle {
        override fun cancel() = Unit
    }

    companion object {
        private const val PRIORITY_CLOSE = -1
        private const val PRIORITY_OPEN = 0
        private const val PRIORITY_VISIBLE_BASE = 1
        private const val PRIORITY_VISIBLE_TILE = 2
        private const val PRIORITY_PREFETCH = 3
        private const val PRIORITY_META_SCAN = 4

        private const val EAGER_SCAN_THRESHOLD = 120
        private const val META_SCAN_CHUNK_SIZE = 32
        private const val DEFAULT_A4_RATIO = 1.4142f
        const val MIN_BASE_WIDTH_PX = 64
        const val MAX_BASE_WIDTH_PX = 1600
        private const val MAX_BASE_HEIGHT_PX = 3600
        private const val MAX_TILE_DIM_PX = 2048

        fun clampBaseWidth(widthPx: Int): Int = widthPx.coerceIn(MIN_BASE_WIDTH_PX, MAX_BASE_WIDTH_PX)
    }
}
