package com.musichub.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.musichub.data.model.SyncSource
import com.musichub.databinding.ItemSyncSourceBinding
import com.musichub.platform.Platforms

class SyncSourceAdapter(
    private val onDeleteClick: (SyncSource) -> Unit
) : ListAdapter<SyncSource, SyncSourceAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSyncSourceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemSyncSourceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(source: SyncSource) {
            binding.tvPlatform.text = Platforms.DISPLAY_NAMES[source.platform] ?: source.platform
            binding.tvSourceUrl.text = source.sourceUrl

            // Sync time
            if (source.lastSyncAt == 0L) {
                binding.tvSyncTime.text = "从未同步"
            } else {
                binding.tvSyncTime.text = formatRelativeTime(source.lastSyncAt)
            }

            // Status badge
            when (source.lastSyncStatus) {
                "success" -> {
                    binding.tvSyncStatusBadge.text = "成功"
                    binding.tvSyncStatusBadge.setTextColor(Color.parseColor("#4CAF50"))
                }
                "error" -> {
                    binding.tvSyncStatusBadge.text = "失败"
                    binding.tvSyncStatusBadge.setTextColor(Color.parseColor("#F44336"))
                }
                else -> {
                    binding.tvSyncStatusBadge.text = ""
                }
            }

            binding.btnDelete.setOnClickListener {
                onDeleteClick(source)
            }
        }

        private fun formatRelativeTime(timestamp: Long): String {
            val diff = System.currentTimeMillis() - timestamp
            val minutes = diff / (1000 * 60)
            val hours = minutes / 60
            val days = hours / 24

            return when {
                minutes < 1 -> "刚刚"
                minutes < 60 -> "${minutes}分钟前"
                hours < 24 -> "${hours}小时前"
                else -> "${days}天前"
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<SyncSource>() {
            override fun areItemsTheSame(oldItem: SyncSource, newItem: SyncSource): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: SyncSource, newItem: SyncSource): Boolean =
                oldItem == newItem
        }
    }
}
