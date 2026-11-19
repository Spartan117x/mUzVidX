package com.muzvidx.app.ui.adapters

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.muzvidx.app.R
import com.muzvidx.app.databinding.ItemSearchResultBinding
import com.muzvidx.app.model.ResultType
import com.muzvidx.app.model.SearchResult

class SearchResultsAdapter(
    private var results: List<SearchResult>,
    private val onPlayItem: (SearchResult, Int) -> Unit,
    private val onDownloadAudio: (SearchResult) -> Unit,
    private val onDownloadVideo: (SearchResult) -> Unit
) : RecyclerView.Adapter<SearchResultsAdapter.ResultViewHolder>() {

    inner class ResultViewHolder(val binding: ItemSearchResultBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultViewHolder {
        val binding = ItemSearchResultBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ResultViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ResultViewHolder, position: Int) {
        val item = results[position]
        holder.binding.tvTitle.text = item.title
        holder.binding.tvArtist.text = item.artist
        holder.binding.tvDuration.text = formatDuration(item.durationSec)

        val (tagText, tagColorRes) = when {
            item.type == ResultType.TRACK -> Pair("Canción", R.color.tag_official)
            item.isOfficial -> Pair("Video Oficial", R.color.accent)
            else -> Pair("Video", R.color.text_secondary)
        }
        holder.binding.tvSource.text = tagText
        holder.binding.tvSource.setTextColor(holder.itemView.context.getColor(tagColorRes))

        if (item.thumbUrl.isNotEmpty()) {
            Glide.with(holder.itemView.context)
                .load(item.thumbUrl)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.ic_music_note)
                .error(R.drawable.ic_music_note)
                .into(holder.binding.ivThumb)
        } else {
            holder.binding.ivThumb.setImageResource(R.drawable.ic_music_note)
        }

        // Animación táctil de pulsación
        applyTouchFeedback(holder.binding.btnDownloadAudio)
        applyTouchFeedback(holder.binding.btnDownloadVideo)
        applyTouchFeedback(holder.binding.root)

        // Tap en el elemento -> Reproducir en mUzVidX
        holder.binding.root.setOnClickListener {
            onPlayItem(item, position)
        }

        holder.binding.btnDownloadVideo.visibility = View.VISIBLE
        holder.binding.btnDownloadAudio.setOnClickListener {
            onDownloadAudio(item)
        }
        holder.binding.btnDownloadVideo.setOnClickListener {
            onDownloadVideo(item)
        }
    }

    private fun applyTouchFeedback(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(70).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120)
                        .setInterpolator(OvershootInterpolator(2.0f)).start()
                }
            }
            false
        }
    }

    override fun getItemCount(): Int = results.size

    fun updateResults(newResults: List<SearchResult>) {
        results = newResults
        notifyDataSetChanged()
    }

    private fun formatDuration(sec: Long): String {
        if (sec <= 0) return "▶"
        val min = sec / 60
        val s = sec % 60
        return "$min:${s.toString().padStart(2, '0')}"
    }
}
