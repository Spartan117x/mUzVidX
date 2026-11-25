package com.muzvidx.app.ui.fragments

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.muzvidx.app.R
import com.muzvidx.app.databinding.FragmentSearchBinding
import com.muzvidx.app.downloader.DownloadQueueManager
import com.muzvidx.app.extractor.YouTubeService
import com.muzvidx.app.model.SearchMode
import com.muzvidx.app.model.SearchResult
import com.muzvidx.app.ui.activities.MainActivity
import com.muzvidx.app.ui.adapters.SearchResultsAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                hideKeyboard()
                doSearch(query)
            }
        }

        // Click en la caja de búsqueda abre el teclado en Smart TV
        binding.etSearch.setOnClickListener {
            showKeyboard()
        }

        binding.etSearch.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                hideKeyboard()
                binding.btnSearch.performClick()
                true
            } else {
                false
            }
        }

        binding.swipeRefresh.setColorSchemeResources(R.color.accent)
        binding.swipeRefresh.setProgressBackgroundColorSchemeResource(R.color.bg_card)
        // En Smart TV se desactiva el pull-to-refresh táctil para evitar recargas accidentales al subir con el D-pad
        binding.swipeRefresh.isEnabled = false

        // Configuración de foco y resaltado visual para Smart TV / Control Remoto
        setupTvFocusStyles()
        updateNavigationPointers(false)

        // Foco inicial en el botón "Canciones" para que el usuario pueda empezar a navegar de inmediato
        binding.btnModeLibrary.post {
            binding.btnModeLibrary.requestFocus()
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun setupTvFocusStyles() {
        val accentColor = ContextCompat.getColor(requireContext(), R.color.accent)
        val dividerColor = ContextCompat.getColor(requireContext(), R.color.divider)

        val modeButtons = listOf(binding.btnModeLibrary, binding.btnModeVideos, binding.btnModeFree)
        for (btn in modeButtons) {
            btn.setOnFocusChangeListener { v, hasFocus ->
                val mb = v as? MaterialButton ?: return@setOnFocusChangeListener
                val isActive = when (mb.id) {
                    R.id.btnModeLibrary -> currentMode == SearchMode.LIBRARY
                    R.id.btnModeVideos -> currentMode == SearchMode.VIDEOS
                    R.id.btnModeFree -> currentMode == SearchMode.FREE
                    else -> false
                }
                if (hasFocus) {
                    mb.animate().scaleX(1.08f).scaleY(1.08f).setDuration(100).start()
                    mb.strokeWidth = dpToPx(3)
                    mb.strokeColor = ColorStateList.valueOf(accentColor)
                    mb.elevation = dpToPx(8).toFloat()
                } else {
                    mb.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                    mb.strokeWidth = if (isActive) 0 else dpToPx(1)
                    mb.strokeColor = ColorStateList.valueOf(dividerColor)
                    mb.elevation = dpToPx(2).toFloat()
                }
            }
        }

        binding.btnSearch.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.animate().scaleX(1.15f).scaleY(1.15f).setDuration(100).start()
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
            }
        }

        binding.etSearch.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.searchContainer.animate().scaleX(1.02f).scaleY(1.02f).setDuration(100).start()
            } else {
                binding.searchContainer.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
            }
        }
    }

    private fun updateNavigationPointers(hasResults: Boolean) {
        val downTargetId = if (hasResults) R.id.rvResults else R.id.btnPlay
        binding.btnModeLibrary.nextFocusDownId = downTargetId
        binding.btnModeVideos.nextFocusDownId = downTargetId
        // "Modo Libre" proporciona un atajo directo para bajar hacia el reproductor
        binding.btnModeFree.nextFocusDownId = R.id.btnPlay
    }

    private fun showKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(binding.etSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
    }

    private fun setMode(mode: SearchMode) {
        currentMode = mode
        val accentColor = ContextCompat.getColor(requireContext(), R.color.accent)
        val darkBg = ContextCompat.getColor(requireContext(), R.color.bg_dark)
        val cardBg = ContextCompat.getColor(requireContext(), R.color.bg_card)
        val mutedText = ContextCompat.getColor(requireContext(), R.color.text_secondary)
        val dividerColor = ContextCompat.getColor(requireContext(), R.color.divider)

        // Reset todos a estilo inactivo
        val buttons = listOf(binding.btnModeLibrary, binding.btnModeVideos, binding.btnModeFree)
        for (btn in buttons) {
            btn.backgroundTintList = ColorStateList.valueOf(cardBg)
            btn.setTextColor(mutedText)
            btn.strokeWidth = if (btn.hasFocus()) dpToPx(3) else dpToPx(1)
            btn.strokeColor = ColorStateList.valueOf(if (btn.hasFocus()) accentColor else dividerColor)
        }

        // Resaltar el botón activo
        val activeBtn = when (mode) {
            SearchMode.LIBRARY -> {
                binding.etSearch.hint = "Buscar canciones o artistas…"
                binding.btnModeLibrary
            }
            SearchMode.VIDEOS -> {
                binding.etSearch.hint = "Buscar videos oficiales…"
                binding.btnModeVideos
            }
            SearchMode.FREE -> {
                binding.etSearch.hint = "Búsqueda libre en YouTube…"
                binding.btnModeFree
            }
        }
        activeBtn.backgroundTintList = ColorStateList.valueOf(accentColor)
        activeBtn.setTextColor(darkBg)
        activeBtn.strokeWidth = if (activeBtn.hasFocus()) dpToPx(3) else 0

        val q = binding.etSearch.text.toString().trim()
        if (q.isNotEmpty()) {
            doSearch(q)
        }
    }

    private fun doSearch(query: String) {
        val b = _binding ?: return
        if (isSearching) return
        isSearching = true
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
                updateNavigationPointers(results.isNotEmpty())

                _binding?.let { bindingRef ->
                    if (results.isEmpty()) {
                        bindingRef.tvStatus.text = "No se encontraron resultados"
                        bindingRef.tvStatus.visibility = View.VISIBLE
                    } else {
                        bindingRef.tvStatus.visibility = View.GONE
                        // En Smart TV: enfocar automáticamente la primera tarjeta de resultados
                        bindingRef.rvResults.post {
                            bindingRef.rvResults.layoutManager?.findViewByPosition(0)?.requestFocus()
                        }
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
                    bindingRef.tvStatus.visibility = View.GONE
                }
            }
        }
    }

    private fun downloadAudio(result: SearchResult) {
        val appCtx = context?.applicationContext ?: return
        val prefs = appCtx.getSharedPreferences("muzvidx_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("v103_autoconvert_init", false)) {
            prefs.edit()
                .putBoolean("auto_convert_mp3", true)
                .putBoolean("v103_autoconvert_init", true)
                .apply()
        }
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
