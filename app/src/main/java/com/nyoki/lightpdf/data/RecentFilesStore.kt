package com.nyoki.lightpdf.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import com.nyoki.lightpdf.R
import org.json.JSONArray
import org.json.JSONObject

data class RecentFile(
    val uriString: String,
    val displayName: String,
    val pageCount: Int,
    val lastPage: Int,
    val lastZoom: Float,
    val lastOpenedAt: Long
)

data class ReadingState(
    val page: Int,
    val offsetFraction: Float,
    val zoom: Float,
    val panXFraction: Float = 0f
)

class RecentFilesStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getRecentFiles(): List<RecentFile> {
        val raw = prefs.getString(KEY_RECENT_FILES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = ArrayList<RecentFile>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val uri = obj.optString("uri", "")
                if (uri.isEmpty()) continue
                list.add(
                    RecentFile(
                        uriString = uri,
                        displayName = obj.optString("name", ""),
                        pageCount = obj.optInt("pages", 0),
                        lastPage = obj.optInt("page", 0),
                        lastZoom = obj.optDouble("zoom", 1.0).toFloat().coerceIn(1f, 5f),
                        lastOpenedAt = obj.optLong("ts", 0L)
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun recordOpened(
        uriString: String,
        displayName: String,
        pageCount: Int = 0
    ) {
        val current = getRecentFiles().toMutableList()
        val existing = current.firstOrNull { it.uriString == uriString }
        current.removeAll { it.uriString == uriString }

        val savedState = getReadingState(uriString)
        val mergedPageCount = if (pageCount > 0) pageCount else (existing?.pageCount ?: 0)
        val mergedLastPage = savedState?.page ?: (existing?.lastPage ?: 0)
        val mergedLastZoom = savedState?.zoom ?: (existing?.lastZoom ?: 1f)

        val updated = RecentFile(
            uriString = uriString,
            displayName = displayName.ifBlank { existing?.displayName.orEmpty() },
            pageCount = mergedPageCount,
            lastPage = mergedLastPage,
            lastZoom = mergedLastZoom,
            lastOpenedAt = System.currentTimeMillis()
        )
        current.add(0, updated)
        if (current.size > MAX_RECENT_FILES) {
            current.subList(MAX_RECENT_FILES, current.size).clear()
        }
        saveRecentList(current)
    }

    fun removeRecent(uriString: String) {
        val current = getRecentFiles().toMutableList()
        if (current.removeAll { it.uriString == uriString }) {
            saveRecentList(current)
        }
    }

    fun saveReadingState(
        uriString: String,
        page: Int,
        offsetFraction: Float,
        zoom: Float,
        pageCount: Int,
        displayName: String? = null,
        panXFraction: Float = 0f
    ) {
        val safePage = page.coerceAtLeast(0)
        val safeFraction = offsetFraction.coerceIn(-4f, 1f)
        val safeZoom = zoom.coerceIn(1f, 5f)
        val safePanXFraction = if (safeZoom > 1.01f) panXFraction.coerceIn(0f, 1f) else 0f
        val now = System.currentTimeMillis()

        // Update per-file reading state map
        val statesJson = loadStatesObject()
        val entry = JSONObject().apply {
            put("p", safePage)
            put("f", safeFraction.toDouble())
            put("z", safeZoom.toDouble())
            put("px", safePanXFraction.toDouble())
            put("ts", now)
        }
        statesJson.put(uriString, entry)
        trimStatesObject(statesJson)

        // Also keep recent file metadata in sync if present
        val recent = getRecentFiles().toMutableList()
        val idx = recent.indexOfFirst { it.uriString == uriString }
        val updatedRecentJson = if (idx >= 0) {
            val old = recent[idx]
            recent[idx] = old.copy(
                displayName = displayName?.takeIf { it.isNotBlank() } ?: old.displayName,
                pageCount = if (pageCount > 0) pageCount else old.pageCount,
                lastPage = safePage,
                lastZoom = safeZoom,
                lastOpenedAt = now
            )
            encodeRecentList(recent)
        } else {
            null
        }

        prefs.edit().apply {
            putString(KEY_READING_STATES, statesJson.toString())
            if (updatedRecentJson != null) {
                putString(KEY_RECENT_FILES, updatedRecentJson)
            }
            apply()
        }
    }

    fun getReadingState(uriString: String): ReadingState? {
        val statesJson = loadStatesObject()
        val obj = statesJson.optJSONObject(uriString) ?: return null
        return ReadingState(
            page = obj.optInt("p", 0).coerceAtLeast(0),
            offsetFraction = obj.optDouble("f", 0.0).toFloat().coerceIn(-4f, 1f),
            zoom = obj.optDouble("z", 1.0).toFloat().coerceIn(1f, 5f),
            panXFraction = obj.optDouble("px", 0.0).toFloat().coerceIn(0f, 1f)
        )
    }

    fun isNightMode(): Boolean = prefs.getBoolean(KEY_NIGHT_MODE, false)

    fun setNightMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NIGHT_MODE, enabled).apply()
    }

    private fun saveRecentList(list: List<RecentFile>) {
        prefs.edit().putString(KEY_RECENT_FILES, encodeRecentList(list)).apply()
    }

    private fun encodeRecentList(list: List<RecentFile>): String {
        val arr = JSONArray()
        for (item in list) {
            arr.put(
                JSONObject().apply {
                    put("uri", item.uriString)
                    put("name", item.displayName)
                    put("pages", item.pageCount)
                    put("page", item.lastPage)
                    put("zoom", item.lastZoom.toDouble())
                    put("ts", item.lastOpenedAt)
                }
            )
        }
        return arr.toString()
    }

    private fun loadStatesObject(): JSONObject {
        val raw = prefs.getString(KEY_READING_STATES, null) ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun trimStatesObject(statesJson: JSONObject) {
        if (statesJson.length() <= MAX_SAVED_STATES) return
        val keysWithTs = ArrayList<Pair<String, Long>>(statesJson.length())
        val keys = statesJson.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val ts = statesJson.optJSONObject(k)?.optLong("ts", 0L) ?: 0L
            keysWithTs.add(k to ts)
        }
        keysWithTs.sortBy { it.second }
        val removeCount = keysWithTs.size - MAX_SAVED_STATES
        for (i in 0 until removeCount) {
            statesJson.remove(keysWithTs[i].first)
        }
    }

    companion object {
        private const val PREFS_NAME = "light_pdf_prefs"
        private const val KEY_RECENT_FILES = "recent_files"
        private const val KEY_READING_STATES = "reading_states"
        private const val KEY_NIGHT_MODE = "night_mode"
        private const val MAX_RECENT_FILES = 20
        private const val MAX_SAVED_STATES = 100

        fun resolveDisplayName(context: Context, uri: Uri): String {
            if ("content".equals(uri.scheme, ignoreCase = true)) {
                try {
                    context.contentResolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) {
                                val name = cursor.getString(idx)
                                if (!name.isNullOrBlank()) return name
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Fallback below
                }
            }
            val segment = uri.lastPathSegment
            if (!segment.isNullOrBlank()) {
                val clean = segment.substringAfterLast('/').substringAfterLast(':')
                if (clean.isNotBlank()) return clean
            }
            return context.getString(R.string.untitled_pdf)
        }
    }
}
