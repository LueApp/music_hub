package com.musichub.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.musichub.R
import com.musichub.data.model.ParsedSong
import com.musichub.databinding.ItemDiscoverSongBinding
import com.musichub.platform.Platforms

class DiscoverSongAdapter(
    private val onPreviewClick: (ParsedSong) -> Unit,
    private val onAddClick: (ParsedSong) -> Unit
) : ListAdapter<ParsedSong, DiscoverSongAdapter.SongViewHolder>(ParsedSongDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val binding = ItemDiscoverSongBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SongViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class SongViewHolder(
        private val binding: ItemDiscoverSongBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.btnPreview.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onPreviewClick(getItem(position))
                }
            }

            binding.btnAdd.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onAddClick(getItem(position))
                }
            }
        }

        fun bind(song: ParsedSong) {
            binding.tvTitle.text = song.title.ifEmpty { song.platformSongId }
            binding.tvArtist.text = song.artist.ifEmpty { "未知歌手" }

            // Platform badge
            when (song.platform) {
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
                else -> {
                    binding.tvPlatform.text = song.platform
                }
            }

            // Album cover
            if (song.coverUrl.isNotEmpty()) {
                binding.ivAlbumCover.load(song.coverUrl) {
                    placeholder(R.drawable.ic_album)
                    error(R.drawable.ic_album)
                    crossfade(true)
                }
            } else {
                binding.ivAlbumCover.setImageResource(R.drawable.ic_album)
            }
        }
    }

    private class ParsedSongDiffCallback : DiffUtil.ItemCallback<ParsedSong>() {
        override fun areItemsTheSame(oldItem: ParsedSong, newItem: ParsedSong): Boolean {
            return oldItem.platform == newItem.platform && oldItem.platformSongId == newItem.platformSongId
        }

        override fun areContentsTheSame(oldItem: ParsedSong, newItem: ParsedSong): Boolean {
            return oldItem == newItem
        }
    }
}
