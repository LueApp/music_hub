package com.musichub.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.musichub.R
import com.musichub.data.model.Song
import com.musichub.databinding.ItemSongSelectableBinding
import com.musichub.platform.Platforms

class SelectableSongAdapter(
    private val onSelectionChanged: (Set<Long>) -> Unit
) : ListAdapter<Song, SelectableSongAdapter.ViewHolder>(SongDiffCallback()) {

    private val selectedIds = mutableSetOf<Long>()

    fun getSelectedIds(): Set<Long> = selectedIds.toSet()

    fun selectAll(songs: List<Song>) {
        selectedIds.clear()
        selectedIds.addAll(songs.map { it.id })
        notifyDataSetChanged()
        onSelectionChanged(getSelectedIds())
    }

    fun deselectAll() {
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged(getSelectedIds())
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSongSelectableBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemSongSelectableBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            val toggle = {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val song = getItem(position)
                    if (selectedIds.contains(song.id)) {
                        selectedIds.remove(song.id)
                    } else {
                        selectedIds.add(song.id)
                    }
                    notifyItemChanged(position)
                    onSelectionChanged(getSelectedIds())
                }
            }

            binding.root.setOnClickListener { toggle() }
            binding.cbSelect.setOnClickListener { toggle() }
        }

        fun bind(song: Song) {
            binding.tvTitle.text = song.title
            binding.tvArtist.text = song.artist
            binding.cbSelect.isChecked = selectedIds.contains(song.id)

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
                else -> {
                    binding.tvPlatform.text = song.platform
                }
            }

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
