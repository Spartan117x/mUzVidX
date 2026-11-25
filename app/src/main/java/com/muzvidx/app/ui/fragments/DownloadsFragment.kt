package com.muzvidx.app.ui.fragments

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.muzvidx.app.converter.Mp3Converter
import com.muzvidx.app.databinding.FragmentDownloadsBinding
import com.muzvidx.app.db.DownloadDatabase
import com.muzvidx.app.db.DownloadEntity
import com.muzvidx.app.downloader.DownloadQueueManager
import com.muzvidx.app.model.ResultType
import com.muzvidx.app.model.SearchResult
import com.muzvidx.app.model.Source
import com.muzvidx.app.ui.activities.MainActivity
import com.muzvidx.app.ui.adapters.DownloadsAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DownloadsFragment : Fragment() {

    private var _binding: FragmentDownloadsBinding? = null
    private val binding get() = _binding!!

    private var audioAdapter: DownloadsAdapter? = null
    private var videoAdapter: DownloadsAdapter? = null
    private var pollJob: Job? = null
    private var currentTab = 0
    private var currentAudioList: List<DownloadEntity> = emptyList()
    private var currentVideoList: List<DownloadEntity> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDownloadsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val ctx = requireContext()
        val prefs = ctx.getSharedPreferences("muzvidx_prefs", Context.MODE_PRIVATE)
        val db = DownloadDatabase.getInstance(ctx)

        binding.switchAutoConvert.isChecked = prefs.getBoolean("auto_convert_mp3", true)
        binding.switchAutoConvert.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_convert_mp3", isChecked).apply()
            val msg = if (isChecked) "Auto-conversión a MP3 activada" else "Auto-conversión desactivada"
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }

        audioAdapter = DownloadsAdapter(
            onPlay = { entity -> playEntity(entity) },
            onDelete = { entity -> deleteEntity(db, entity) },
            onConvert = { entity -> manualConvert(db, entity) }
        )
        videoAdapter = DownloadsAdapter(
            onPlay = { entity -> playEntity(entity) },
            onDelete = { entity -> deleteEntity(db, entity) },
            onConvert = {}
        )

        binding.rvAudio.layoutManager = LinearLayoutManager(ctx)
        binding.rvVideo.layoutManager = LinearLayoutManager(ctx)
        binding.rvAudio.adapter = audioAdapter
        binding.rvVideo.adapter = videoAdapter

        binding.tabLayoutDownloads.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentTab = tab.position
                updateTabVisibility()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        loadDownloads(db)
        startPolling(db)

        // Foco inicial en las pestañas para Smart TV
        binding.tabLayoutDownloads.post {
            binding.tabLayoutDownloads.getTabAt(currentTab)?.view?.requestFocus()
        }
    }

    private fun startPolling(db: DownloadDatabase) {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (true) {
                loadDownloads(db)
                delay(1200)
            }
        }
    }

    private fun loadDownloads(db: DownloadDatabase) {
        lifecycleScope.launch {
            val all: List<DownloadEntity> = withContext(Dispatchers.IO) {
                db.downloadDao().getAll()
            }
            if (_binding == null) return@launch
            val audio = all.filter { it.type == "AUDIO" }
            val video = all.filter { it.type == "VIDEO" }
            currentAudioList = audio
            currentVideoList = video
            audioAdapter?.updateList(audio)
            videoAdapter?.updateList(video)

            updateEmptyState(audio.size, video.size)
        }
    }

    private fun updateTabVisibility() {
        if (_binding == null) return
        binding.rvAudio.visibility = if (currentTab == 0) View.VISIBLE else View.GONE
        binding.rvVideo.visibility = if (currentTab == 1) View.VISIBLE else View.GONE
        val db = DownloadDatabase.getInstance(requireContext())
        loadDownloads(db)
    }

    private fun updateEmptyState(audioCount: Int, videoCount: Int) {
        if (_binding == null) return
        if (currentTab == 0) {
            if (audioCount == 0) {
                binding.tvEmptyDownloads.text = "No hay canciones descargadas todavía"
                binding.tvEmptyDownloads.visibility = View.VISIBLE
            } else {
                binding.tvEmptyDownloads.visibility = View.GONE
            }
        } else {
            if (videoCount == 0) {
                binding.tvEmptyDownloads.text = "No hay videos descargados todavía"
                binding.tvEmptyDownloads.visibility = View.VISIBLE
            } else {
                binding.tvEmptyDownloads.visibility = View.GONE
            }
        }
    }

    private fun deleteEntity(db: DownloadDatabase, entity: DownloadEntity) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (entity.originalPath.isNotEmpty()) {
                    File(entity.originalPath).delete()
                }
                entity.mp3Path?.let { File(it).delete() }
                db.downloadDao().delete(entity)
            }
            loadDownloads(db)
        }
    }

    private fun manualConvert(db: DownloadDatabase, entity: DownloadEntity) {
        val appCtx = context?.applicationContext ?: return
        Toast.makeText(appCtx, "Iniciando conversión a MP3: ${entity.title}", Toast.LENGTH_SHORT).show()
        DownloadQueueManager.convertManual(appCtx, entity) {
            loadDownloads(db)
        }
        loadDownloads(db)
    }

    private fun playEntity(entity: DownloadEntity) {
        val ctx = context ?: return
        val fileUrl = if (entity.type == "VIDEO") entity.originalPath else (entity.mp3Path ?: entity.originalPath)
        val f = File(fileUrl)
        if (!f.exists() || f.length() < 1000) {
            Toast.makeText(ctx, "El archivo descargado no existe o está incompleto", Toast.LENGTH_SHORT).show()
            return
        }

        val sourceList = if (entity.type == "VIDEO") currentVideoList else currentAudioList
        val readyQueue = sourceList.mapNotNull { item ->
            val path = if (item.type == "VIDEO") item.originalPath else (item.mp3Path ?: item.originalPath)
            val file = File(path)
            if (file.exists() && file.length() >= 1000) {
                SearchResult(
                    id = item.id,
                    title = item.title,
                    artist = item.artist,
                    source = Source.YOUTUBE,
                    type = if (item.type == "VIDEO") ResultType.VIDEO else ResultType.TRACK,
                    durationSec = 0,
                    thumbUrl = "",
                    audioUrl = path,
                    videoUrl = path,
                    isOfficial = false
                )
            } else {
                null
            }
        }

        val startIndex = readyQueue.indexOfFirst { it.id == entity.id }.coerceAtLeast(0)
        val queueToPlay = if (readyQueue.isNotEmpty()) {
            readyQueue
        } else {
            listOf(
                SearchResult(
                    id = entity.id,
                    title = entity.title,
                    artist = entity.artist,
                    source = Source.YOUTUBE,
                    type = if (entity.type == "VIDEO") ResultType.VIDEO else ResultType.TRACK,
                    durationSec = 0,
                    thumbUrl = "",
                    audioUrl = fileUrl,
                    videoUrl = fileUrl,
                    isOfficial = false
                )
            )
        }

        MainActivity.openPlayer(ctx, queueToPlay, startIndex)
    }

    override fun onResume() {
        super.onResume()
        if (context != null) {
            val ctx = requireContext()
            DownloadQueueManager.recoverStuckDownloads(ctx)
            val db = DownloadDatabase.getInstance(ctx)
            loadDownloads(db)
            startPolling(db)
        }
    }

    override fun onPause() {
        super.onPause()
        pollJob?.cancel()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pollJob?.cancel()
        _binding = null
    }
}
