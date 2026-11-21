package com.nyoki.lightpdf.pdf

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reference-counted wrapper around a mutable ARGB_8888 Bitmap.
 * Prevents recycling or returning a Bitmap to [BitmapPool] while UI Views are still drawing it.
 */
class RefCountedBitmap(
    val bitmap: Bitmap,
    private val pool: BitmapPool
) {
    private val refCount = AtomicInteger(1)

    val byteCount: Int
        get() = bitmap.allocationByteCount

    fun retain(): Boolean {
        while (true) {
            val current = refCount.get()
            if (current <= 0) return false
            if (refCount.compareAndSet(current, current + 1)) return true
        }
    }

    fun release() {
        val remaining = refCount.decrementAndGet()
        if (remaining == 0) {
            pool.put(bitmap)
        }
    }

    fun isUsable(): Boolean = refCount.get() > 0 && !bitmap.isRecycled
}

/**
 * Thread-safe mutable ARGB_8888 Bitmap pool using API 26+ Bitmap.reconfigure()
 * to eliminate allocation churn and GC pauses during rapid PDF scrolling and zooming.
 */
class BitmapPool(private val maxBytes: Int) {

    private val lock = Any()
    private val pool = ArrayList<Bitmap>(8)
    private var currentBytes = 0
    private var closed = false

    fun acquire(width: Int, height: Int): RefCountedBitmap {
        val safeW = width.coerceAtLeast(1)
        val safeH = height.coerceAtLeast(1)
        val neededBytes = safeW.toLong() * safeH.toLong() * 4L

        val reused = synchronized(lock) {
            if (closed) return@synchronized null
            var bestIndex = -1
            var bestAlloc = Long.MAX_VALUE

            for (i in pool.indices) {
                val candidate = pool[i]
                if (candidate.isRecycled || !candidate.isMutable) continue
                if (candidate.width == safeW && candidate.height == safeH) {
                    bestIndex = i
                    break
                }
                val alloc = candidate.allocationByteCount.toLong()
                if (alloc >= neededBytes && alloc <= neededBytes * 2L && alloc < bestAlloc) {
                    bestAlloc = alloc
                    bestIndex = i
                }
            }

            if (bestIndex >= 0) {
                val bmp = pool.removeAt(bestIndex)
                currentBytes -= bmp.allocationByteCount
                bmp
            } else {
                null
            }
        }

        val bitmap = if (reused != null) {
            if (reused.width != safeW || reused.height != safeH) {
                reused.reconfigure(safeW, safeH, Bitmap.Config.ARGB_8888)
            }
            reused
        } else {
            Bitmap.createBitmap(safeW, safeH, Bitmap.Config.ARGB_8888)
        }

        return RefCountedBitmap(bitmap, this)
    }

    fun put(bitmap: Bitmap) {
        if (bitmap.isRecycled || !bitmap.isMutable || bitmap.config != Bitmap.Config.ARGB_8888) {
            return
        }
        val size = bitmap.allocationByteCount
        if (size <= 0 || size > maxBytes) {
            bitmap.recycle()
            return
        }

        synchronized(lock) {
            if (closed) {
                bitmap.recycle()
                return
            }
            while (currentBytes + size > maxBytes && pool.isNotEmpty()) {
                val evicted = pool.removeAt(0)
                currentBytes -= evicted.allocationByteCount
                if (!evicted.isRecycled) {
                    evicted.recycle()
                }
            }
            if (currentBytes + size <= maxBytes) {
                pool.add(bitmap)
                currentBytes += size
            } else {
                bitmap.recycle()
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            for (bmp in pool) {
                if (!bmp.isRecycled) {
                    bmp.recycle()
                }
            }
            pool.clear()
            currentBytes = 0
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            for (bmp in pool) {
                if (!bmp.isRecycled) {
                    bmp.recycle()
                }
            }
            pool.clear()
            currentBytes = 0
        }
    }
}
