package com.muzvidx.app.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.muzvidx.app.R
import com.muzvidx.app.databinding.ItemAudioFileBinding
import com.muzvidx.app.db.DownloadEntity

class AudioFilesAdapter(
    private val onPlay: (DownloadEntity) -> Unit,
    private val onConvert: (DownloadEntity) -> Unit = {}
) : RecyclerView.Adapter<AudioFilesAdapter.AudioViewHolder>() {

    private var items: List<DownloadEntity> = emptyList()

    inner class AudioViewHolder(val binding: ItemAudioFileBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AudioViewHolder {
        val binding = ItemAudioFileBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return AudioViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AudioViewHolder, position: Int) {
        val item = items[position]
        holder.binding.tvFileName.text = item.title
        holder.binding.tvFilePath.text = item.originalPath

        holder.binding.btnSelectFile.setOnClickListener {
            onConvert(item)
            Toast.makeText(holder.itemView.context, "Convirtiendo ${item.title}…", Toast.LENGTH_SHORT).show()
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateList(newList: List<DownloadEntity>) {
        items = newList
        notifyDataSetChanged()
    }
}