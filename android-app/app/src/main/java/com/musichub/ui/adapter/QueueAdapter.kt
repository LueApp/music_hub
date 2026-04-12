package com.musichub.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.musichub.R
import com.musichub.data.model.Song
import com.musichub.platform.Platforms

/**
 * Adapter for displaying queue songs in the floating window.
 */
class QueueAdapter(
    private var songs: List<Song> = emptyList(),
    private var currentIndex: Int = -1,
    private var playOrder: List<Int>? = null,  // For shuffle mode - shows order of play
    private val onItemClick: (Int) -> Unit = {}
) : RecyclerView.Adapter<QueueAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvPosition: TextView = view.findViewById(R.id.tvPosition)
        val tvSongTitle: TextView = view.findViewById(R.id.tvSongTitle)
        val tvArtist: TextView = view.findViewById(R.id.tvArtist)
        val ivPlatform: ImageView = view.findViewById(R.id.ivPlatform)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_queue_song, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        // If we have a play order (shuffle mode), show songs in that order
        val displayIndex = if (playOrder != null && position < playOrder!!.size) {
            playOrder!![position]
        } else {
            position
        }

        if (displayIndex !in songs.indices) return

        val song = songs[displayIndex]
        val isCurrentSong = displayIndex == currentIndex

        // Show position number (1-based)
        holder.tvPosition.text = "${position + 1}"

        holder.tvSongTitle.text = song.title
        holder.tvArtist.text = song.artist

        // Highlight current song
        if (isCurrentSong) {
            holder.tvSongTitle.setTextColor(holder.itemView.context.getColor(R.color.primary))
            holder.tvPosition.setTextColor(holder.itemView.context.getColor(R.color.primary))
        } else {
            holder.tvSongTitle.setTextColor(holder.itemView.context.getColor(R.color.text_primary))
            holder.tvPosition.setTextColor(holder.itemView.context.getColor(R.color.text_secondary))
        }

        // Set platform icon
        val platformIcon = when (song.platform) {
            Platforms.NETEASE -> R.drawable.ic_netease
            Platforms.QQMUSIC -> R.drawable.ic_qqmusic
            Platforms.BILIBILI -> R.drawable.ic_bilibili
            else -> R.drawable.ic_music_note
        }
        holder.ivPlatform.setImageResource(platformIcon)

        holder.itemView.setOnClickListener {
            onItemClick(displayIndex)
        }
    }

    override fun getItemCount(): Int {
        return playOrder?.size ?: songs.size
    }

    fun updateData(newSongs: List<Song>, newCurrentIndex: Int, newPlayOrder: List<Int>? = null) {
        songs = newSongs
        currentIndex = newCurrentIndex
        playOrder = newPlayOrder
        notifyDataSetChanged()
    }

    /** Get the adapter position for a given actual queue index. */
    fun getDisplayPosition(queueIndex: Int): Int {
        val order = playOrder
        return if (order != null) {
            order.indexOf(queueIndex).takeIf { it >= 0 } ?: queueIndex
        } else {
            queueIndex
        }
    }

    /** Get the actual queue index for a given adapter position. */
    fun getQueueIndex(adapterPosition: Int): Int {
        val order = playOrder
        return if (order != null && adapterPosition in order.indices) {
            order[adapterPosition]
        } else {
            adapterPosition
        }
    }

    /** Move an item within the adapter for immediate visual feedback during drag. */
    fun moveItem(fromPosition: Int, toPosition: Int) {
        if (playOrder != null) {
            // Shuffle mode: reorder the play order, not the songs list
            val mutableOrder = playOrder!!.toMutableList()
            val idx = mutableOrder.removeAt(fromPosition)
            mutableOrder.add(toPosition, idx)
            playOrder = mutableOrder
        } else {
            val mutableSongs = songs.toMutableList()
            val song = mutableSongs.removeAt(fromPosition)
            mutableSongs.add(toPosition, song)
            songs = mutableSongs

            // Adjust currentIndex to follow the currently playing song
            currentIndex = when {
                currentIndex == fromPosition -> toPosition
                fromPosition < currentIndex && toPosition >= currentIndex -> currentIndex - 1
                fromPosition > currentIndex && toPosition <= currentIndex -> currentIndex + 1
                else -> currentIndex
            }
        }

        notifyItemMoved(fromPosition, toPosition)
    }

    /** Whether this adapter is currently displaying in shuffle order. */
    fun isShuffleMode(): Boolean = playOrder != null
}
