package com.musichub.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.musichub.R
import com.musichub.data.model.SkipLogEntry
import com.musichub.databinding.ItemSkipLogBinding
import com.musichub.platform.Platforms
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SkipLogAdapter : ListAdapter<SkipLogEntry, SkipLogAdapter.ViewHolder>(DiffCallback()) {

    private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSkipLogBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemSkipLogBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: SkipLogEntry) {
            binding.tvTitle.text = entry.songTitle
            binding.tvArtist.text = entry.songArtist
            binding.tvReason.text = entry.reason
            binding.tvTime.text = dateFormat.format(Date(entry.timestamp))

            when (entry.platform) {
                Platforms.NETEASE -> {
                    binding.tvPlatform.text = "网易云"
                    binding.tvPlatform.setBackgroundResource(R.drawable.bg_badge_netease)
                }
                Platforms.QQMUSIC -> {
                    binding.tvPlatform.text = "QQ音乐"
                    binding.tvPlatform.setBackgroundResource(R.drawable.bg_badge_qqmusic)
                }
                Platforms.BILIBILI -> {
                    binding.tvPlatform.text = "B站"
                    binding.tvPlatform.setBackgroundResource(R.drawable.bg_badge_bilibili)
                }
                Platforms.KUGOU -> {
                    binding.tvPlatform.text = "酷狗"
                    binding.tvPlatform.setBackgroundResource(R.drawable.bg_badge_kugou)
                }
                else -> binding.tvPlatform.text = entry.platform
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<SkipLogEntry>() {
        override fun areItemsTheSame(oldItem: SkipLogEntry, newItem: SkipLogEntry) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: SkipLogEntry, newItem: SkipLogEntry) =
            oldItem == newItem
    }
}
