package com.nyoki.lightpdf.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.nyoki.lightpdf.R
import com.nyoki.lightpdf.data.RecentFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecentFilesAdapter(
    private val onClick: (RecentFile) -> Unit,
    private val onLongClick: (RecentFile) -> Unit
) : RecyclerView.Adapter<RecentFilesAdapter.ViewHolder>() {

    private val items = ArrayList<RecentFile>()
    private val dateFormat = SimpleDateFormat("MM/dd HH:mm", Locale.JAPAN)

    fun submitList(newItems: List<RecentFile>) {
        val oldItems = ArrayList(items)
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldItems.size
            override fun getNewListSize(): Int = newItems.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldItems[oldItemPosition].uriString == newItems[newItemPosition].uriString
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldItems[oldItemPosition] == newItems[newItemPosition]
            }
        })
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recent_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context

        holder.tvFileName.text = item.displayName.ifBlank {
            ctx.getString(R.string.untitled_pdf)
        }

        val pagePart = if (item.pageCount > 0) {
            ctx.getString(R.string.recent_page_progress, item.lastPage + 1, item.pageCount)
        } else {
            ctx.getString(R.string.recent_page_only, item.lastPage + 1)
        }

        val timePart = if (item.lastOpenedAt > 0L) {
            dateFormat.format(Date(item.lastOpenedAt))
        } else {
            ""
        }

        holder.tvFileMeta.text = if (timePart.isNotEmpty()) {
            "$pagePart  •  $timePart"
        } else {
            pagePart
        }

        if (item.lastZoom > 1.05f) {
            holder.tvZoomBadge.visibility = View.VISIBLE
            holder.tvZoomBadge.text = ctx.getString(R.string.recent_zoom_badge, item.lastZoom)
        } else {
            holder.tvZoomBadge.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvFileName: TextView = view.findViewById(R.id.tvFileName)
        val tvFileMeta: TextView = view.findViewById(R.id.tvFileMeta)
        val tvZoomBadge: TextView = view.findViewById(R.id.tvZoomBadge)
    }
}
