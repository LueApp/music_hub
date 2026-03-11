package com.musichub.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.musichub.R
import com.musichub.data.model.Song
import com.musichub.databinding.ItemSongBinding
import com.musichub.platform.Platforms

class SongAdapter(
    private val onSongClick: (Song) -> Unit,
    private val onPlayClick: (Song) -> Unit,
    private val onDeleteClick: ((Song) -> Unit)? = null
) : ListAdapter<Song, SongAdapter.SongViewHolder>(SongDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val binding = ItemSongBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SongViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun removeItem(position: Int) {
        if (position in 0 until itemCount) {
            val song = getItem(position)
            onDeleteClick?.invoke(song)
        }
    }

    inner class SongViewHolder(
        private val binding: ItemSongBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onSongClick(getItem(position))
                }
            }

            binding.btnPlay.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onPlayClick(getItem(position))
                }
            }
        }

        fun bind(song: Song) {
            binding.tvTitle.text = song.title
            binding.tvArtist.text = song.artist

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

    private class SongDiffCallback : DiffUtil.ItemCallback<Song>() {
        override fun areItemsTheSame(oldItem: Song, newItem: Song): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Song, newItem: Song): Boolean {
            return oldItem == newItem
        }
    }
}
