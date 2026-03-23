package com.musichub.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.musichub.R
import com.musichub.databinding.ItemChartCardBinding
import com.musichub.platform.ChartInfo
import com.musichub.platform.Platforms

class ChartAdapter(
    private val onChartClick: (ChartInfo) -> Unit
) : ListAdapter<ChartInfo, ChartAdapter.ChartViewHolder>(ChartDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChartViewHolder {
        val binding = ItemChartCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ChartViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChartViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ChartViewHolder(
        private val binding: ItemChartCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onChartClick(getItem(position))
                }
            }
        }

        fun bind(chart: ChartInfo) {
            binding.tvChartName.text = chart.name
            binding.tvUpdateFreq.text = chart.updateFrequency

            when (chart.platform) {
                Platforms.NETEASE -> {
                    binding.tvPlatform.text = "网易云"
                    binding.tvPlatform.setBackgroundResource(R.drawable.bg_badge_netease)
                    binding.ivChartCover.setImageResource(R.drawable.ic_netease)
                    binding.ivChartCover.imageTintList = null
                }
                Platforms.QQMUSIC -> {
                    binding.tvPlatform.text = "QQ音乐"
                    binding.tvPlatform.setBackgroundResource(R.drawable.bg_badge_qqmusic)
                    binding.ivChartCover.setImageResource(R.drawable.ic_qqmusic)
                    binding.ivChartCover.imageTintList = null
                }
                Platforms.BILIBILI -> {
                    binding.tvPlatform.text = "B站"
                    binding.tvPlatform.setBackgroundResource(R.drawable.bg_badge_bilibili)
                    binding.ivChartCover.setImageResource(R.drawable.ic_bilibili)
                    binding.ivChartCover.imageTintList = null
                }
                else -> {
                    binding.tvPlatform.text = chart.platform
                }
            }
        }
    }

    private class ChartDiffCallback : DiffUtil.ItemCallback<ChartInfo>() {
        override fun areItemsTheSame(oldItem: ChartInfo, newItem: ChartInfo): Boolean {
            return oldItem.platform == newItem.platform && oldItem.chartId == newItem.chartId
        }

        override fun areContentsTheSame(oldItem: ChartInfo, newItem: ChartInfo): Boolean {
            return oldItem == newItem
        }
    }
}
