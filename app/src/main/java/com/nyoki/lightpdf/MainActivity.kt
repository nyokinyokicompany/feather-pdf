package com.nyoki.lightpdf

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nyoki.lightpdf.data.RecentFile
import com.nyoki.lightpdf.data.RecentFilesStore
import com.nyoki.lightpdf.ui.RecentFilesAdapter

class MainActivity : Activity() {

    private lateinit var store: RecentFilesStore
    private lateinit var adapter: RecentFilesAdapter
    private lateinit var rvRecentFiles: RecyclerView
    private lateinit var emptyStateCard: View
    private lateinit var tvRecentHint: View
    private var confirmDialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setContentView(R.layout.activity_main)

        store = RecentFilesStore(this)
        rvRecentFiles = findViewById(R.id.rvRecentFiles)
        emptyStateCard = findViewById(R.id.emptyStateCard)
        tvRecentHint = findViewById(R.id.tvRecentHint)

        val rootHome = findViewById<View>(R.id.rootHome)
        val rvBaseBottom = rvRecentFiles.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(rootHome) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, 0)
            rvRecentFiles.setPadding(
                rvRecentFiles.paddingLeft,
                rvRecentFiles.paddingTop,
                rvRecentFiles.paddingRight,
                rvBaseBottom + bars.bottom
            )
            insets
        }

        adapter = RecentFilesAdapter(
            onClick = { recent ->
                openViewer(Uri.parse(recent.uriString), recent.displayName, fromRecent = true)
            },
            onLongClick = { recent -> showRemoveConfirmDialog(recent) }
        )

        rvRecentFiles.layoutManager = LinearLayoutManager(this)
        rvRecentFiles.itemAnimator = null
        rvRecentFiles.adapter = adapter

        findViewById<View>(R.id.btnOpenPdf).setOnClickListener {
            launchDocumentPicker()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshRecentFiles()
    }

    private fun refreshRecentFiles() {
        val list = store.getRecentFiles()
        adapter.submitList(list)
        val isEmpty = list.isEmpty()
        emptyStateCard.visibility = if (isEmpty) View.VISIBLE else View.GONE
        rvRecentFiles.visibility = if (isEmpty) View.GONE else View.VISIBLE
        tvRecentHint.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    @Suppress("DEPRECATION")
    private fun launchDocumentPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        try {
            startActivityForResult(intent, REQ_OPEN_PDF)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.error_unknown, Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_OPEN_PDF || resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Provider did not grant persistable permission; still openable for this session
        }

        val displayName = RecentFilesStore.resolveDisplayName(this, uri)
        openViewer(uri, displayName, fromRecent = false)
    }

    private fun openViewer(uri: Uri, displayName: String?, fromRecent: Boolean) {
        val intent = Intent(this, ViewerActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (!displayName.isNullOrBlank()) {
                putExtra(ViewerActivity.EXTRA_DISPLAY_NAME, displayName)
            }
            putExtra(ViewerActivity.EXTRA_FROM_RECENT, fromRecent)
        }
        startActivity(intent)
    }

    private fun showRemoveConfirmDialog(recent: RecentFile) {
        confirmDialog?.dismiss()
        val dialog = Dialog(this, R.style.Theme_LightPdf_Dialog)
        confirmDialog = dialog
        dialog.setOnDismissListener {
            if (confirmDialog === dialog) {
                confirmDialog = null
            }
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val rootParent = findViewById<ViewGroup>(android.R.id.content)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_confirm, rootParent, false)
        dialog.setContentView(view)

        val name = recent.displayName.ifBlank { getString(R.string.untitled_pdf) }
        view.findViewById<TextView>(R.id.tvConfirmTitle).setText(R.string.dialog_remove_recent_title)
        view.findViewById<TextView>(R.id.tvConfirmMessage).text =
            getString(R.string.dialog_remove_recent_message, name)

        view.findViewById<Button>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }
        view.findViewById<Button>(R.id.btnDelete).setOnClickListener {
            store.removeRecent(recent.uriString)
            try {
                contentResolver.releasePersistableUriPermission(
                    Uri.parse(recent.uriString),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
                // Ignore if permission wasn't persisted
            }
            refreshRecentFiles()
            Toast.makeText(this, R.string.toast_removed_recent, Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        dialog.show()
    }

    override fun onDestroy() {
        confirmDialog?.dismiss()
        confirmDialog = null
        super.onDestroy()
    }

    companion object {
        private const val REQ_OPEN_PDF = 1001
    }
}
