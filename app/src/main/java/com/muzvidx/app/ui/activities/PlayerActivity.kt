package com.muzvidx.app.ui.activities

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.button.MaterialButton
import com.muzvidx.app.R
import com.muzvidx.app.databinding.ActivityPlayerBinding
import com.muzvidx.app.db.DownloadDatabase
import com.muzvidx.app.db.FavoriteEntity
import com.muzvidx.app.db.PlaylistItemEntity
import com.muzvidx.app.extractor.YouTubeService
import com.muzvidx.app.model.ResultType
import com.muzvidx.app.model.SearchResult
import com.muzvidx.app.player.AudioEqualizerManager
import com.muzvidx.app.player.PlaybackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private val handler = Handler(Looper.getMainLooper())
    private var uiRunnable: Runnable? = null
    private var autoHideRunnable: Runnable? = null
    private var isControlsVisible = true
    private var isEqualizerVisible = false
    private var isVisualizerActive = true
    private var isLandscape = false
    private val scope = CoroutineScope(Dispatchers.Main)

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("muzvidx_prefs", Context.MODE_PRIVATE)
        isVisualizerActive = prefs.getBoolean("visualizer_active", true)

        PlaybackManager.init(this)
        binding.playerView.player = PlaybackManager.player

        applyPressAnimations()

        binding.btnMinimize.setOnClickListener { finish() }
        binding.btnClose.setOnClickListener { finish() }

        // Rotación de pantalla horizontal/vertical
        binding.btnRotate.setOnClickListener {
            toggleOrientation()
        }

        // Modo Ventana Flotante (PiP)
        binding.btnPip.setOnClickListener {
            enterPipMode()
        }

        // Toggle Barras de Audio Visuales (Graves / Medios / Agudos)
        binding.btnVisualizer.setOnClickListener {
            toggleVisualizer()
        }

        // Switch entre Modo AUDIO y Modo VIDEO en vivo
        binding.btnModeAudio.setOnClickListener {
            setVisualMode(false)
        }
        binding.btnModeVideo.setOnClickListener {
            setVisualMode(true)
        }

        // Botón Play / Pause
        binding.btnPlay.setOnClickListener {
            if (PlaybackManager.isPlaying) {
                PlaybackManager.pause()
                binding.btnPlay.setImageResource(R.drawable.ic_play)
            } else {
                PlaybackManager.resume()
                binding.btnPlay.setImageResource(R.drawable.ic_pause)
            }
        }

        binding.btnPrev.setOnClickListener {
            PlaybackManager.playPrev()
            updateUiForCurrentTrack()
        }

        binding.btnRewind.setOnClickListener {
            val pos = Math.max(0, PlaybackManager.getCurrentPositionMs() - 10000)
            PlaybackManager.seekTo(pos)
            scheduleControlsAutoHide()
        }

        binding.btnForward.setOnClickListener {
            val pos = PlaybackManager.getCurrentPositionMs() + 10000
            PlaybackManager.seekTo(pos)
            scheduleControlsAutoHide()
        }

        binding.btnNext.setOnClickListener {
            PlaybackManager.playNext()
            updateUiForCurrentTrack()
        }

        // Reproducción continua
        binding.switchContinuous.isChecked = PlaybackManager.isContinuous
        binding.switchContinuous.setOnCheckedChangeListener { _, checked ->
            PlaybackManager.toggleContinuous(checked)
        }

        // Repetición cíclica
        binding.btnRepeat.setOnClickListener {
            val newMode = PlaybackManager.cycleRepeatMode()
            updateRepeatButtonUi(newMode)
        }
        updateRepeatButtonUi(PlaybackManager.getCurrentRepeatMode())

        // Favoritos (Me gusta)
        binding.btnFavorite.setOnClickListener {
            toggleFavorite()
        }

        // Añadir a playlist personalizada
        binding.btnAddToPlaylist.setOnClickListener {
            showAddToPlaylistDialog()
        }

        // Toggle Ecualizador
        binding.btnEqualizer.setOnClickListener {
            toggleEqualizerView()
        }

        // Selector de Calidad / Resolución de Video
        binding.btnQuality.setOnClickListener {
            showQualitySelectorDialog()
        }

        PlaybackManager.onResolutionChangedListener = { res ->
            runOnUiThread {
                binding.btnQuality.text = res
            }
        }

        // Seekbar de progreso
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            var isSeeking = false
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    isSeeking = true
                    PlaybackManager.seekTo(progress.toLong())
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) { isSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar) { isSeeking = false }
        })

        // Tap en playerView o mediaContainer para mostrar/ocultar controles en modo video
        binding.playerView.setOnClickListener {
            if (PlaybackManager.isVideo) {
                toggleControlsVisibility()
            }
        }
        binding.mediaContainer.setOnClickListener {
            if (PlaybackManager.isVideo) {
                toggleControlsVisibility()
            }
        }

        PlaybackManager.onTrackChangedListener = {
            runOnUiThread {
                updateUiForCurrentTrack()
                checkFavoriteStatus()
            }
        }

        PlaybackManager.onLoadingStateListener = { loading ->
            runOnUiThread {
                binding.pbLoadingTrack.visibility = if (loading) View.VISIBLE else View.GONE
            }
        }

        setupEqualizerUi()
        updateUiForCurrentTrack()
        checkFavoriteStatus()
        startUiUpdater()

        // Habilitar auto-scroll (marquee) en textos sin requerir foco
        binding.tvHeaderTitle.isSelected = true
        binding.tvTitle.isSelected = true
        binding.tvArtist.isSelected = true

        // Configuración de foco y resaltado para control remoto de Smart TV
        setupTvFocusStyles()
        binding.btnPlay.post {
            binding.btnPlay.requestFocus()
        }

        // Configuración inicial de orientación y layout
        checkCurrentOrientation(resources.configuration.orientation)
    }

    private fun setupTvFocusStyles() {
        val focusViews = listOf(
            binding.btnPlay, binding.btnRewind, binding.btnForward,
            binding.btnPrev, binding.btnNext,
            binding.btnMinimize, binding.btnEqualizer, binding.btnQuality,
            binding.btnVisualizer, binding.btnRotate, binding.btnPip, binding.btnClose,
            binding.btnModeAudio, binding.btnModeVideo, binding.btnFavorite,
            binding.btnAddToPlaylist, binding.btnRepeat, binding.switchContinuous
        )
        for (v in focusViews) {
            v.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    val scale = if (view.id == R.id.btnPlay) 1.15f else 1.12f
                    view.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
                } else {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                }
            }
        }
    }

    private fun toggleVisualizer() {
        isVisualizerActive = !isVisualizerActive
        val prefs = getSharedPreferences("muzvidx_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("visualizer_active", isVisualizerActive).apply()

        val msg = if (isVisualizerActive) "Barras Visuales: ACTIVADAS" else "Barras Visuales: DESACTIVADAS"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

        updateVisualLayout()
    }

    private fun setVisualMode(isVideo: Boolean) {
        PlaybackManager.isVideoMode = isVideo
        updateVisualLayout()
    }

    /**
     * Organiza de forma dinámica la vista de video, carátula y barras de audio visuales
     * respetando estrictamente las especificaciones:
     * - En Audio: si barras visuales activas -> carátula desaparece y barras ocupan el centro.
     * - En Video Horizontal: si barras visuales activas -> video toma 50% y barras visuales 50%.
     * - En Video Vertical: las barras NO se activan (video a tamaño completo).
     */
    private fun updateVisualLayout() {
        val isVideo = PlaybackManager.isVideo
        val accentColor = getColor(R.color.accent)
        val normalColor = getColor(R.color.text_secondary)
        val darkBg = getColor(R.color.bg_dark)
        val trans = getColor(android.R.color.transparent)

        // 1. Icono del botón de barras visuales
        binding.btnVisualizer.setColorFilter(if (isVisualizerActive) accentColor else normalColor)

        // 2. Vincular sesión de audio al visualizador si hay ExoPlayer activo
        val sessionId = PlaybackManager.player?.audioSessionId ?: 0
        if (sessionId != 0) {
            binding.visualizerView.linkAudioSession(sessionId)
        }

        if (isVideo) {
            // MODO VIDEO
            binding.btnQuality.visibility = View.VISIBLE
            binding.btnQuality.text = PlaybackManager.currentVideoResolution
            binding.btnModeVideo.backgroundTintList = ColorStateList.valueOf(accentColor)
            binding.btnModeVideo.setTextColor(darkBg)
            binding.btnModeAudio.backgroundTintList = ColorStateList.valueOf(trans)
            binding.btnModeAudio.setTextColor(normalColor)

            binding.playerView.visibility = View.VISIBLE
            binding.ivThumb.visibility = View.GONE
            binding.equalizerContainer.visibility = if (isEqualizerVisible) View.VISIBLE else View.GONE

            if (isLandscape && isVisualizerActive && !isEqualizerVisible) {
                // En HORIZONTAL con visualizador activo:
                // El video toma la mitad (50%) y las barras visuales la otra mitad (50%)
                binding.videoContainer.visibility = View.VISIBLE
                binding.videoContainer.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f)

                binding.visualizerContainer.visibility = View.VISIBLE
                binding.visualizerContainer.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f)
                binding.visualizerContainer.setPadding(16, 56, 16, 28)
            } else {
                // En VERTICAL (o con visualizador apagado):
                // El video toma el 100% del espacio disponible, el visualizador se oculta
                binding.videoContainer.visibility = View.VISIBLE
                binding.videoContainer.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f)
                binding.visualizerContainer.visibility = View.GONE
            }

            if (PlaybackManager.isPlaying) {
                scheduleControlsAutoHide()
            }
        } else {
            // MODO AUDIO (en línea o descarga local)
            binding.btnQuality.visibility = View.GONE
            binding.btnModeAudio.backgroundTintList = ColorStateList.valueOf(accentColor)
            binding.btnModeAudio.setTextColor(darkBg)
            binding.btnModeVideo.backgroundTintList = ColorStateList.valueOf(trans)
            binding.btnModeVideo.setTextColor(normalColor)

            binding.playerView.visibility = View.GONE

            if (isEqualizerVisible) {
                binding.equalizerContainer.visibility = View.VISIBLE
                binding.ivThumb.visibility = View.GONE
                binding.visualizerContainer.visibility = View.GONE
                binding.videoContainer.visibility = View.VISIBLE
                binding.videoContainer.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f)
            } else if (isVisualizerActive) {
                // Visualizador activo en audio: la carátula desaparece y las barras visuales ocupan la pantalla
                binding.equalizerContainer.visibility = View.GONE
                binding.ivThumb.visibility = View.GONE
                binding.videoContainer.visibility = View.GONE

                binding.visualizerContainer.visibility = View.VISIBLE
                binding.visualizerContainer.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f)
                if (isLandscape) {
                    binding.visualizerContainer.setPadding(32, 64, 32, 36)
                } else {
                    binding.visualizerContainer.setPadding(24, 70, 24, 160)
                }
            } else {
                // Visualizador apagado en audio: carátula visible
                binding.equalizerContainer.visibility = View.GONE
                binding.ivThumb.visibility = View.VISIBLE
                binding.visualizerContainer.visibility = View.GONE

                binding.videoContainer.visibility = View.VISIBLE
                binding.videoContainer.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f)
            }

            // Mostrar controles permanentes en audio
            isControlsVisible = true
            autoHideRunnable?.let { handler.removeCallbacks(it) }
            binding.topBar.visibility = View.VISIBLE
            binding.modeSwitchBar.visibility = if (isLandscape) View.GONE else View.VISIBLE
            binding.actionsBar.visibility = if (isLandscape) View.GONE else View.VISIBLE
            binding.bottomControls.visibility = View.VISIBLE
        }
    }

    private fun applyPressAnimations() {
        val views = listOf(
            binding.btnPlay,
            binding.btnPrev,
            binding.btnNext,
            binding.btnFavorite,
            binding.btnAddToPlaylist,
            binding.btnRepeat,
            binding.btnRotate,
            binding.btnMinimize,
            binding.btnPip,
            binding.btnEqualizer,
            binding.btnVisualizer,
            binding.btnClose,
            binding.btnModeAudio,
            binding.btnModeVideo
        )

        for (v in views) {
            v.setOnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        view.animate().scaleX(0.86f).scaleY(0.86f).setDuration(80).start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120)
                            .setInterpolator(OvershootInterpolator(2.2f)).start()
                    }
                }
                false
            }
        }
    }

    private fun toggleOrientation() {
        isLandscape = !isLandscape
        requestedOrientation = if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        checkCurrentOrientation(newConfig.orientation)
    }

    private fun checkCurrentOrientation(orientation: Int) {
        isLandscape = (orientation == Configuration.ORIENTATION_LANDSCAPE)
        if (isLandscape) {
            // Modo Horizontal: Diseño ultra-compacto flotante para máxima visibilidad del video
            binding.tvTitle.visibility = View.GONE
            binding.tvArtist.visibility = View.GONE
            binding.modeSwitchBar.visibility = View.GONE
            binding.actionsBar.visibility = View.GONE
            binding.bottomControls.setPadding(40, 0, 40, 4)
            binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            if (PlaybackManager.isVideo && PlaybackManager.isPlaying) {
                scheduleControlsAutoHide()
            }
        } else {
            // Modo Vertical: Restaurar vista completa
            binding.tvTitle.visibility = View.VISIBLE
            binding.tvArtist.visibility = View.VISIBLE
            binding.modeSwitchBar.visibility = View.VISIBLE
            binding.actionsBar.visibility = View.VISIBLE
            binding.bottomControls.setPadding(32, 12, 32, 24)
            binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        updateVisualLayout()
    }

    private fun toggleEqualizerView() {
        isEqualizerVisible = !isEqualizerVisible
        updateVisualLayout()

        val accentColor = getColor(R.color.accent)
        val normalColor = getColor(R.color.text_primary)
        binding.btnEqualizer.setColorFilter(if (isEqualizerVisible) accentColor else normalColor)
    }

    private fun setupEqualizerUi() {
        binding.switchEqEnabled.isChecked = AudioEqualizerManager.isEnabled
        binding.switchEqEnabled.setOnCheckedChangeListener { _, isChecked ->
            AudioEqualizerManager.setEnabled(isChecked, this)
        }

        val presets = AudioEqualizerManager.getPresets()
        val defaultPresets = if (presets.isNotEmpty()) presets else listOf(
            "Normal", "Rock", "Pop", "Jazz", "Bass Boost", "Electrónica", "Clásica", "Vocal"
        )

        binding.presetChipsContainer.removeAllViews()
        for ((idx, presetName) in defaultPresets.withIndex()) {
            val btn = MaterialButton(this).apply {
                text = presetName
                textSize = 11f
                setTextColor(getColor(R.color.text_primary))
                setBackgroundColor(getColor(R.color.bg_card_surface))
                cornerRadius = 24
                setPadding(28, 8, 28, 8)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(8, 0, 8, 0) }
                layoutParams = params

                setOnClickListener {
                    AudioEqualizerManager.usePreset(idx.toShort(), this@PlayerActivity)
                    Toast.makeText(this@PlayerActivity, "Modo: $presetName", Toast.LENGTH_SHORT).show()
                    refreshBandSliders()
                }
            }
            binding.presetChipsContainer.addView(btn)
        }

        refreshBandSliders()

        binding.seekBassBoost.progress = AudioEqualizerManager.getBassBoostStrength().toInt()
        binding.seekBassBoost.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) AudioEqualizerManager.setBassBoost(p.toShort(), this@PlayerActivity)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        binding.seekVirtualizer.progress = AudioEqualizerManager.getVirtualizerStrength().toInt()
        binding.seekVirtualizer.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) AudioEqualizerManager.setVirtualizer(p.toShort(), this@PlayerActivity)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun refreshBandSliders() {
        binding.bandsContainer.removeAllViews()
        val bands = AudioEqualizerManager.getBands()
        val displayBands = if (bands.isNotEmpty()) bands else {
            listOf(
                com.muzvidx.app.player.BandInfo(0, 60, -1500, 1500, 0),
                com.muzvidx.app.player.BandInfo(1, 230, -1500, 1500, 0),
                com.muzvidx.app.player.BandInfo(2, 910, -1500, 1500, 0),
                com.muzvidx.app.player.BandInfo(3, 3600, -1500, 1500, 0),
                com.muzvidx.app.player.BandInfo(4, 14000, -1500, 1500, 0)
            )
        }

        for (band in displayBands) {
            val bandRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 4, 0, 4) }
            }

            val freqText = if (band.centerFreqHz >= 1000) "${band.centerFreqHz / 1000} kHz" else "${band.centerFreqHz} Hz"
            val tvLabel = TextView(this).apply {
                text = freqText
                width = 140
                textSize = 11f
                setTextColor(getColor(R.color.text_secondary))
            }

            val seekBar = SeekBar(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                val range = (band.maxLevel - band.minLevel).toInt().coerceAtLeast(1)
                max = range
                progress = (band.currentLevel - band.minLevel).toInt()
                progressTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.accent))
                thumbTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.accent))

                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        if (fromUser) {
                            val newLevel = (p + band.minLevel).toShort()
                            AudioEqualizerManager.setBandLevel(band.index, newLevel, this@PlayerActivity)
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }

            bandRow.addView(tvLabel)
            bandRow.addView(seekBar)
            binding.bandsContainer.addView(bandRow)
        }
    }

    private fun toggleControlsVisibility() {
        isControlsVisible = !isControlsVisible
        val vis = if (isControlsVisible) View.VISIBLE else View.GONE
        binding.topBar.visibility = vis
        if (!isLandscape) {
            binding.modeSwitchBar.visibility = vis
            binding.actionsBar.visibility = vis
        }
        binding.bottomControls.visibility = vis

        autoHideRunnable?.let { handler.removeCallbacks(it) }
        if (isControlsVisible) {
            binding.btnPlay.post { binding.btnPlay.requestFocus() }
            if (PlaybackManager.isVideo && PlaybackManager.isPlaying) {
                scheduleControlsAutoHide()
            }
        }
    }

    private fun scheduleControlsAutoHide() {
        autoHideRunnable?.let { handler.removeCallbacks(it) }
        autoHideRunnable = Runnable {
            if (PlaybackManager.isVideo && PlaybackManager.isPlaying && !isEqualizerVisible) {
                isControlsVisible = false
                binding.topBar.visibility = View.GONE
                binding.modeSwitchBar.visibility = View.GONE
                binding.actionsBar.visibility = View.GONE
                binding.bottomControls.visibility = View.GONE
            }
        }
        handler.postDelayed(autoHideRunnable!!, 8000)
    }

    private fun updateRepeatButtonUi(mode: Int) {
        when (mode) {
            Player.REPEAT_MODE_ALL -> {
                binding.btnRepeat.setImageResource(R.drawable.ic_repeat)
                binding.btnRepeat.setColorFilter(getColor(R.color.accent))
            }
            Player.REPEAT_MODE_ONE -> {
                binding.btnRepeat.setImageResource(R.drawable.ic_repeat_one)
                binding.btnRepeat.setColorFilter(getColor(R.color.accent))
            }
            else -> {
                binding.btnRepeat.setImageResource(R.drawable.ic_repeat)
                binding.btnRepeat.setColorFilter(getColor(R.color.text_secondary))
            }
        }
    }

    private fun checkFavoriteStatus() {
        val track = PlaybackManager.getCurrentTrack() ?: return
        val db = DownloadDatabase.getInstance(this)
        scope.launch {
            val isFav = withContext(Dispatchers.IO) {
                db.downloadDao().isFavorite(track.id)
            }
            if (isFav) {
                binding.btnFavorite.setImageResource(R.drawable.ic_heart_filled)
                binding.btnFavorite.setColorFilter(getColor(R.color.accent_magenta))
            } else {
                binding.btnFavorite.setImageResource(R.drawable.ic_heart_outline)
                binding.btnFavorite.setColorFilter(getColor(R.color.text_primary))
            }
        }
    }

    private fun toggleFavorite() {
        val track = PlaybackManager.getCurrentTrack() ?: return
        val db = DownloadDatabase.getInstance(this)
        scope.launch {
            val isFav = withContext(Dispatchers.IO) { db.downloadDao().isFavorite(track.id) }
            if (isFav) {
                withContext(Dispatchers.IO) { db.downloadDao().removeFavorite(track.id) }
                binding.btnFavorite.setImageResource(R.drawable.ic_heart_outline)
                binding.btnFavorite.setColorFilter(getColor(R.color.text_primary))
                Toast.makeText(this@PlayerActivity, "Eliminado de favoritos", Toast.LENGTH_SHORT).show()
            } else {
                withContext(Dispatchers.IO) {
                    db.downloadDao().addFavorite(
                        FavoriteEntity(
                            id = track.id,
                            title = track.title,
                            artist = track.artist,
                            thumbUrl = track.thumbUrl,
                            sourceUrl = track.audioUrl.ifEmpty { track.videoUrl },
                            type = track.type.name,
                            addedAt = System.currentTimeMillis()
                        )
                    )
                }
                binding.btnFavorite.setImageResource(R.drawable.ic_heart_filled)
                binding.btnFavorite.setColorFilter(getColor(R.color.accent_magenta))
                Toast.makeText(this@PlayerActivity, "Añadido a favoritos", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showQualitySelectorDialog() {
        val currentTrack = PlaybackManager.getCurrentTrack()
        val isLocalFile = currentTrack?.videoUrl?.startsWith("/") == true || currentTrack?.videoUrl?.startsWith("file://") == true
        if (isLocalFile) {
            Toast.makeText(this, "El archivo descargado se reproduce en su resolución original", Toast.LENGTH_SHORT).show()
            return
        }

        val options = arrayOf(
            "Automática (Calidad adaptable)",
            "1080p • Full HD",
            "720p • Alta definición (HD)",
            "480p • Definición estándar (SD)",
            "360p • Ahorro de datos",
            "240p • Modo ligero"
        )
        val tags = arrayOf("Auto", "1080p", "720p", "480p", "360p", "240p")

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Calidad de Reproducción")
            .setItems(options) { _, which ->
                val chosen = tags[which]
                binding.btnQuality.text = chosen
                PlaybackManager.switchVideoResolution(chosen)
                Toast.makeText(this, "Calidad: $chosen", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showAddToPlaylistDialog() {
        val track = PlaybackManager.getCurrentTrack() ?: return
        val db = DownloadDatabase.getInstance(this)

        scope.launch {
            val playlists = withContext(Dispatchers.IO) { db.downloadDao().getAllPlaylists() }
            val options = mutableListOf("Crear nueva playlist")
            options.addAll(playlists.map { "${it.name} (${it.trackCount} temas)" })

            AlertDialog.Builder(this@PlayerActivity)
                .setTitle("Añadir a Playlist")
                .setItems(options.toTypedArray()) { _, which ->
                    if (which == 0) {
                        showCreatePlaylistDialog(track)
                    } else {
                        val selected = playlists[which - 1]
                        addTrackToPlaylist(selected.id, selected.name, track)
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun showCreatePlaylistDialog(track: SearchResult) {
        val input = EditText(this).apply {
            hint = "Nombre de la playlist..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        AlertDialog.Builder(this)
            .setTitle("Nueva Playlist")
            .setView(input)
            .setPositiveButton("Crear y Añadir") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val db = DownloadDatabase.getInstance(this)
                    scope.launch {
                        val plId = withContext(Dispatchers.IO) { db.downloadDao().createPlaylist(name) }
                        addTrackToPlaylist(plId, name, track)
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun addTrackToPlaylist(playlistId: String, playlistName: String, track: SearchResult) {
        val db = DownloadDatabase.getInstance(this)
        scope.launch {
            withContext(Dispatchers.IO) {
                db.downloadDao().addTrackToPlaylist(
                    PlaylistItemEntity(
                        id = "",
                        playlistId = playlistId,
                        trackId = track.id,
                        title = track.title,
                        artist = track.artist,
                        thumbUrl = track.thumbUrl,
                        sourceUrl = track.audioUrl.ifEmpty { track.videoUrl },
                        type = track.type.name,
                        addedAt = System.currentTimeMillis()
                    )
                )
            }
            Toast.makeText(this@PlayerActivity, "Añadido a \"$playlistName\"", Toast.LENGTH_SHORT).show()
        }
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
            } catch (e: Exception) {
                enterPictureInPictureMode()
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (PlaybackManager.isVideo && PlaybackManager.isPlaying) {
            enterPipMode()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            binding.topBar.visibility = View.GONE
            binding.modeSwitchBar.visibility = View.GONE
            binding.actionsBar.visibility = View.GONE
            binding.bottomControls.visibility = View.GONE
            binding.equalizerContainer.visibility = View.GONE
            binding.ivThumb.visibility = View.GONE
            binding.visualizerContainer.visibility = View.GONE
            binding.playerView.visibility = View.VISIBLE
        } else {
            binding.topBar.visibility = View.VISIBLE
            binding.modeSwitchBar.visibility = View.VISIBLE
            binding.actionsBar.visibility = View.VISIBLE
            binding.bottomControls.visibility = View.VISIBLE
            updateUiForCurrentTrack()
        }
    }

    private fun startUiUpdater() {
        uiRunnable = object : Runnable {
            override fun run() {
                val isPlaying = PlaybackManager.isPlaying
                val pos = PlaybackManager.getCurrentPositionMs().toInt()
                val dur = PlaybackManager.getDurationMs().toInt()

                if (dur > 0) {
                    binding.seekBar.max = dur
                    if (!binding.seekBar.isPressed) {
                        binding.seekBar.progress = pos
                    }
                    binding.tvCurrentPos.text = formatTime(pos)
                    binding.tvDuration.text = formatTime(dur)
                }
                binding.btnPlay.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)

                handler.postDelayed(this, 350)
            }
        }
        uiRunnable?.let { handler.post(it) }
    }

    private fun updateUiForCurrentTrack() {
        val track: SearchResult? = PlaybackManager.getCurrentTrack()
        if (track != null) {
            val cleanArtist = track.artist.trim().replace(Regex("(?i)\\s*-\\s*topic|vevo"), "").trim()
            val fullHeader = if (cleanArtist.isNotEmpty() && !cleanArtist.equals("Oficial", true) &&
                !cleanArtist.equals("Video Oficial", true) && !track.title.contains(cleanArtist, true)
            ) {
                "${track.title}   •   $cleanArtist"
            } else {
                track.title
            }
            binding.tvHeaderTitle.text = fullHeader
            binding.tvHeaderTitle.isSelected = true

            binding.tvTitle.text = track.title
            binding.tvTitle.isSelected = true

            binding.tvArtist.text = if (cleanArtist.isNotEmpty() && !cleanArtist.equals("Oficial", true)) cleanArtist else "mUzVidX"
            binding.tvArtist.isSelected = true

            if (track.thumbUrl.isNotEmpty()) {
                Glide.with(this)
                    .load(track.thumbUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.ic_music_note)
                    .error(R.drawable.ic_music_note)
                    .into(binding.ivThumb)
            } else {
                binding.ivThumb.setImageResource(R.drawable.ic_music_note)
            }

            updateVisualLayout()

            val isPlaying = PlaybackManager.isPlaying
            binding.btnPlay.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        }
    }

    private fun formatTime(ms: Int): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val s = totalSec % 60
        return "$min:${s.toString().padStart(2, '0')}"
    }

    override fun onResume() {
        super.onResume()
        binding.playerView.player = PlaybackManager.player
        updateUiForCurrentTrack()
        checkFavoriteStatus()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            // Reiniciar el temporizador de auto-ocultación con cada pulsación del control
            if (isControlsVisible && PlaybackManager.isVideo) {
                scheduleControlsAutoHide()
            }

            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                android.view.KeyEvent.KEYCODE_HEADSETHOOK -> {
                    binding.btnPlay.performClick()
                    return true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    if (!PlaybackManager.isPlaying) binding.btnPlay.performClick()
                    return true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    if (PlaybackManager.isPlaying) binding.btnPlay.performClick()
                    return true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    binding.btnNext.performClick()
                    return true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    binding.btnPrev.performClick()
                    return true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    val pos = PlaybackManager.getCurrentPositionMs() + 10000
                    PlaybackManager.seekTo(pos)
                    showControlsBriefly()
                    return true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    val pos = Math.max(0, PlaybackManager.getCurrentPositionMs() - 10000)
                    PlaybackManager.seekTo(pos)
                    showControlsBriefly()
                    return true
                }
                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                android.view.KeyEvent.KEYCODE_ENTER -> {
                    if (!isControlsVisible) {
                        toggleControlsVisibility()
                        return true
                    } else if (currentFocus == null) {
                        binding.btnPlay.performClick()
                        return true
                    }
                }
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (!isControlsVisible) {
                        val pos = Math.max(0, PlaybackManager.getCurrentPositionMs() - 10000)
                        PlaybackManager.seekTo(pos)
                        showControlsBriefly()
                        return true
                    }
                }
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!isControlsVisible) {
                        val pos = PlaybackManager.getCurrentPositionMs() + 10000
                        PlaybackManager.seekTo(pos)
                        showControlsBriefly()
                        return true
                    }
                }
                android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                    if (!isControlsVisible) {
                        toggleControlsVisibility()
                        return true
                    }
                }
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (!isControlsVisible) {
                        toggleControlsVisibility()
                        return true
                    } else {
                        // Si los controles están visibles y el foco está en la fila de reproducción, flecha ABAJO los oculta
                        val bottomFocusIds = listOf(
                            binding.btnPlay.id,
                            binding.btnRewind.id,
                            binding.btnForward.id,
                            binding.btnPrev.id,
                            binding.btnNext.id
                        )
                        if (currentFocus?.id in bottomFocusIds) {
                            toggleControlsVisibility()
                            return true
                        }
                    }
                }
                android.view.KeyEvent.KEYCODE_BACK -> {
                    if (isEqualizerVisible) {
                        binding.btnEqualizer.performClick()
                        return true
                    } else {
                        finish()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showControlsBriefly() {
        if (!isControlsVisible) {
            toggleControlsVisibility()
        }
        scheduleControlsAutoHide()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiRunnable?.let { handler.removeCallbacks(it) }
        autoHideRunnable?.let { handler.removeCallbacks(it) }
        binding.visualizerView.releaseVisualizer()
        PlaybackManager.onTrackChangedListener = null
        PlaybackManager.onLoadingStateListener = null
        if (!isChangingConfigurations) {
            binding.playerView.player = null
        }
    }
}
