package com.muzvidx.app.ui.adapters

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.muzvidx.app.R
import com.muzvidx.app.databinding.ItemDownloadBinding
import com.muzvidx.app.db.DownloadEntity
import java.io.File

class DownloadsAdapter(
    private val onPlay: (DownloadEntity) -> Unit,
    private val onDelete: (DownloadEntity) -> Unit = {},
    private val onConvert: (DownloadEntity) -> Unit = {}
) : RecyclerView.Adapter<DownloadsAdapter.DownloadViewHolder>() {

    private var items: List<DownloadEntity> = emptyList()

    inner class DownloadViewHolder(val binding: ItemDownloadBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DownloadViewHolder {
        val binding = ItemDownloadBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return DownloadViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DownloadViewHolder, position: Int) {
        val item = items[position]
        holder.binding.tvTitle.text = item.title
        holder.binding.tvArtist.text = item.artist.ifEmpty { "mUzVidX" }

        val isReady = (item.status == "READY")
        val filePath = item.mp3Path ?: item.originalPath
        val fileExists = filePath.isNotEmpty() && File(filePath).exists()

        // Formato
        if (item.type == "VIDEO") {
            val resLabel = when {
                item.title.contains("1080p") || item.originalPath.contains("1080p") -> "1080p"
                item.title.contains("720p") || item.originalPath.contains("720p") -> "720p"
                item.title.contains("480p") || item.originalPath.contains("480p") -> "480p"
                item.title.contains("360p") || item.originalPath.contains("360p") -> "360p"
                else -> "MP4"
            }
            holder.binding.tvFormat.text = resLabel
            holder.binding.tvFormat.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.accent_magenta))
            holder.binding.tvFormat.setBackgroundResource(R.drawable.bg_circle_magenta)
            holder.binding.btnConvertMp3.visibility = View.GONE
        } else {
            val isMp3 = item.mp3Path != null
            holder.binding.tvFormat.text = if (isMp3) "MP3" else "M4A"
            holder.binding.tvFormat.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.accent))
            holder.binding.tvFormat.setBackgroundResource(R.drawable.bg_circle_accent)
            holder.binding.btnConvertMp3.visibility = if (!isMp3 && isReady && fileExists) View.VISIBLE else View.GONE
        }

        when (item.status) {
            "DOWNLOADING" -> {
                holder.binding.tvStatus.text = "Descargando… ${item.progress}%"
                holder.binding.tvStatus.setTextColor(
                    ContextCompat.getColor(holder.itemView.context, R.color.status_progress)
                )
                holder.binding.progressBar.visibility = View.VISIBLE
                holder.binding.progressBar.isIndeterminate = false
                holder.binding.progressBar.max = 100
                holder.binding.progressBar.progress = item.progress
                holder.binding.btnPlay.visibility = View.GONE
            }
            "CONVERTING" -> {
                holder.binding.tvStatus.text = "Convirtiendo a MP3…"
                holder.binding.tvStatus.setTextColor(
                    ContextCompat.getColor(holder.itemView.context, R.color.accent_magenta)
                )
                holder.binding.progressBar.visibility = View.VISIBLE
                holder.binding.progressBar.isIndeterminate = true
                holder.binding.btnPlay.visibility = View.GONE
            }
            "READY" -> {
                if (fileExists) {
                    holder.binding.tvStatus.text = if (item.mp3Path != null) "MP3 Listo ✓" else "Listo ✓"
                    holder.binding.tvStatus.setTextColor(
                        ContextCompat.getColor(holder.itemView.context, R.color.accent)
                    )
                    holder.binding.btnPlay.visibility = View.VISIBLE
                } else {
                    holder.binding.tvStatus.text = "Archivo no encontrado"
                    holder.binding.tvStatus.setTextColor(
                        ContextCompat.getColor(holder.itemView.context, R.color.status_error)
                    )
                    holder.binding.btnPlay.visibility = View.GONE
                }
                holder.binding.progressBar.visibility = View.GONE
            }
            else -> {
                holder.binding.tvStatus.text = "Error al descargar"
                holder.binding.tvStatus.setTextColor(
                    ContextCompat.getColor(holder.itemView.context, R.color.status_error)
                )
                holder.binding.progressBar.visibility = View.GONE
                holder.binding.btnPlay.visibility = View.GONE
            }
        }

        applyTouchFeedback(holder.binding.btnPlay)
        applyTouchFeedback(holder.binding.btnDelete)
        applyTouchFeedback(holder.binding.btnConvertMp3)

        holder.binding.btnPlay.setOnClickListener {
            if (isReady && fileExists) {
                onPlay(item)
            }
        }

        holder.binding.root.setOnClickListener {
            if (isReady && fileExists) {
                onPlay(item)
            } else if (item.status == "DOWNLOADING") {
                Toast.makeText(holder.itemView.context, "Descarga en curso (${item.progress}%)", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(holder.itemView.context, "El archivo no está disponible. Puedes eliminarlo y descargarlo de nuevo.", Toast.LENGTH_SHORT).show()
            }
        }

        holder.binding.btnDelete.setOnClickListener { onDelete(item) }
        holder.binding.btnConvertMp3.setOnClickListener { onConvert(item) }
    }

    private fun applyTouchFeedback(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(70).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120)
                        .setInterpolator(OvershootInterpolator(2.0f)).start()
                }
            }
            false
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateList(newList: List<DownloadEntity>) {
        items = newList
        notifyDataSetChanged()
    }
}
