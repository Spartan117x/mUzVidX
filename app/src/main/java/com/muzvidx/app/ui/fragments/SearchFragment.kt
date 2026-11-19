package com.muzvidx.app.ui.fragments

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.muzvidx.app.R
import com.muzvidx.app.converter.Mp3Converter
import com.muzvidx.app.databinding.FragmentSearchBinding
import com.muzvidx.app.db.DownloadDatabase
import com.muzvidx.app.db.DownloadEntity
import com.muzvidx.app.downloader.AudioVideoDownloader
import com.muzvidx.app.downloader.DownloadQueueManager
import com.muzvidx.app.extractor.YouTubeService
import com.muzvidx.app.model.DownloadStatus
import com.muzvidx.app.model.DownloadType
import com.muzvidx.app.model.ResultType
import com.muzvidx.app.model.SearchMode
import com.muzvidx.app.model.SearchResult
import com.muzvidx.app.ui.activities.MainActivity
import com.muzvidx.app.ui.adapters.SearchResultsAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class SearchFragment : Fragment() {

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    private var currentMode: SearchMode = SearchMode.LIBRARY
    private var isSearching = false
    private var currentResults: List<SearchResult> = emptyList()
    private lateinit var adapter: SearchResultsAdapter
    private val ytService = YouTubeService()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = SearchResultsAdapter(
            emptyList(),
            onPlayItem = { item, pos ->
                MainActivity.openPlayer(requireContext(), currentResults, pos)
            },
            onDownloadAudio = { result -> downloadAudio(result) },
            onDownloadVideo = { result -> downloadVideo(result) }
        )

        binding.rvResults.layoutManager = LinearLayoutManager(requireContext())
        binding.rvResults.adapter = adapter

        // Selector de 3 Modos: Canciones, Videos Oficiales, Modo Libre
        binding.btnModeLibrary.setOnClickListener {
            setMode(SearchMode.LIBRARY)
        }
        binding.btnModeVideos.setOnClickListener {
            setMode(SearchMode.VIDEOS)
        }
        binding.btnModeFree.setOnClickListener {
            setMode(SearchMode.FREE)
        }

        binding.btnSearch.setOnClickListener {
            val query = binding.etSearch.text.toString().trim()
            if (query.isNotEmpty() && !isSearching) {
                doSearch(query)
            }
        }

        binding.etSearch.setOnEditorActionListener { _, actionId, event ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_DOWN)
            ) {
                binding.btnSearch.performClick()
                true
            } else {
                false
            }
        }

        // Evitar que el teclado virtual se abra automáticamente en Smart TV
        binding.root.post {
            binding.root.requestFocus()
        }

        binding.swipeRefresh.setColorSchemeResources(R.color.accent)
        binding.swipeRefresh.setProgressBackgroundColorSchemeResource(R.color.bg_card)
        binding.swipeRefresh.setOnRefreshListener {
            val query = binding.etSearch.text.toString().trim()
            if (query.isNotEmpty()) doSearch(query) else binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun setMode(mode: SearchMode) {
        currentMode = mode
        val accentColor = requireContext().getColor(R.color.accent)
        val darkBg = requireContext().getColor(R.color.bg_dark)
        val cardBg = requireContext().getColor(R.color.bg_card)
        val mutedText = requireContext().getColor(R.color.text_secondary)

        // Reset todos a estilo inactivo
        binding.btnModeLibrary.backgroundTintList = ColorStateList.valueOf(cardBg)
        binding.btnModeLibrary.setTextColor(mutedText)
        binding.btnModeVideos.backgroundTintList = ColorStateList.valueOf(cardBg)
        binding.btnModeVideos.setTextColor(mutedText)
        binding.btnModeFree.backgroundTintList = ColorStateList.valueOf(cardBg)
        binding.btnModeFree.setTextColor(mutedText)

        // Resaltar el botón activo
        when (mode) {
            SearchMode.LIBRARY -> {
                binding.btnModeLibrary.backgroundTintList = ColorStateList.valueOf(accentColor)
                binding.btnModeLibrary.setTextColor(darkBg)
                binding.etSearch.hint = "Buscar canciones o artistas…"
            }
            SearchMode.VIDEOS -> {
                binding.btnModeVideos.backgroundTintList = ColorStateList.valueOf(accentColor)
                binding.btnModeVideos.setTextColor(darkBg)
                binding.etSearch.hint = "Buscar videos oficiales…"
            }
            SearchMode.FREE -> {
                binding.btnModeFree.backgroundTintList = ColorStateList.valueOf(accentColor)
                binding.btnModeFree.setTextColor(darkBg)
                binding.etSearch.hint = "Búsqueda libre en YouTube…"
            }
        }

        val q = binding.etSearch.text.toString().trim()
        if (q.isNotEmpty()) {
            doSearch(q)
        }
    }

    private fun doSearch(query: String) {
        val b = _binding ?: return
        isSearching = true
        b.swipeRefresh.isRefreshing = true
        b.btnSearch.isEnabled = false
        b.btnSearch.alpha = 0.6f
        b.tvStatus.visibility = View.VISIBLE
        b.tvStatus.text = "Buscando \"$query\"…"

        lifecycleScope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    ytService.search(query, currentMode, limit = 30)
                }

                currentResults = results
                adapter.updateResults(results)

                _binding?.let { bindingRef ->
                    if (results.isEmpty()) {
                        bindingRef.tvStatus.text = "No se encontraron resultados"
                        bindingRef.tvStatus.visibility = View.VISIBLE
                    } else {
                        bindingRef.tvStatus.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                context?.let { ctx ->
                    Toast.makeText(ctx, "Error de búsqueda: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                isSearching = false
                _binding?.let { bindingRef ->
                    bindingRef.btnSearch.isEnabled = true
                    bindingRef.btnSearch.alpha = 1f
                    bindingRef.swipeRefresh.isRefreshing = false
                    bindingRef.tvStatus.visibility = View.GONE
                }
            }
        }
    }

    private fun downloadAudio(result: SearchResult) {
        val appCtx = context?.applicationContext ?: return
        val prefs = appCtx.getSharedPreferences("muzvidx_prefs", Context.MODE_PRIVATE)
        val autoConvert = prefs.getBoolean("auto_convert_mp3", true)

        Toast.makeText(appCtx, "Descargando audio: ${result.title}", Toast.LENGTH_SHORT).show()
        DownloadQueueManager.enqueueAudioDownload(appCtx, result, autoConvert)
    }

    private fun downloadVideo(result: SearchResult) {
        val ctx = context ?: return
        val appCtx = ctx.applicationContext

        val resolutions = arrayOf(
            "1080p • Full HD (Máxima calidad)",
            "720p • Alta definición (Recomendado)",
            "480p • Definición estándar",
            "360p • Ahorro de espacio (Rápido)"
        )
        val resTags = arrayOf("1080p", "720p", "480p", "360p")

        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle("Calidad de Descarga del Video")
            .setItems(resolutions) { _, which ->
                val chosenRes = resTags[which]
                Toast.makeText(appCtx, "Descargando video en $chosenRes...", Toast.LENGTH_SHORT).show()
                DownloadQueueManager.enqueueVideoDownload(appCtx, result, targetResolution = chosenRes)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
