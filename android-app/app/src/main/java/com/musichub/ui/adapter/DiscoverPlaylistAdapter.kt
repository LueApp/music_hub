package com.musichub.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.musichub.R
import com.musichub.databinding.ItemDiscoverPlaylistBinding
import com.musichub.platform.DiscoverPlaylistInfo

class DiscoverPlaylistAdapter(
    private val onPlaylistClick: (DiscoverPlaylistInfo) -> Unit
) : ListAdapter<DiscoverPlaylistInfo, DiscoverPlaylistAdapter.PlaylistViewHolder>(PlaylistDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val binding = ItemDiscoverPlaylistBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PlaylistViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PlaylistViewHolder(
        private val binding: ItemDiscoverPlaylistBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onPlaylistClick(getItem(position))
                }
            }
        }

        fun bind(playlist: DiscoverPlaylistInfo) {
            binding.tvPlaylistName.text = playlist.name

            // Format play count
            binding.tvPlayCount.text = formatPlayCount(playlist.playCount)

            // Cover image
            if (playlist.coverUrl.isNotEmpty()) {
                binding.ivPlaylistCover.load(playlist.coverUrl) {
                    placeholder(R.drawable.ic_album)
                    error(R.drawable.ic_album)
                    crossfade(true)
                }
            } else {
                binding.ivPlaylistCover.setImageResource(R.drawable.ic_album)
            }
        }

        private fun formatPlayCount(count: Long): String {
            return when {
                count >= 100_000_000 -> "播放 ${count / 100_000_000}亿"
                count >= 10_000 -> "播放 ${count / 10_000}万"
                count > 0 -> "播放 $count"
                else -> ""
            }
        }
    }

    private class PlaylistDiffCallback : DiffUtil.ItemCallback<DiscoverPlaylistInfo>() {
        override fun areItemsTheSame(oldItem: DiscoverPlaylistInfo, newItem: DiscoverPlaylistInfo): Boolean {
            return oldItem.platform == newItem.platform && oldItem.playlistId == newItem.playlistId
        }

        override fun areContentsTheSame(oldItem: DiscoverPlaylistInfo, newItem: DiscoverPlaylistInfo): Boolean {
            return oldItem == newItem
        }
    }
}
