package com.muzvidx.app.ui.activities

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.KeyEvent
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
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.button.MaterialButton
import com.muzvidx.app.R
import com.muzvidx.app.databinding.ActivityLocalPlayerBinding
import com.muzvidx.app.db.DownloadDatabase
import com.muzvidx.app.db.FavoriteEntity
import com.muzvidx.app.db.PlaylistItemEntity
import com.muzvidx.app.model.ResultType
import com.muzvidx.app.model.SearchResult
import com.muzvidx.app.player.AudioEqualizerManager
import com.muzvidx.app.player.PlaybackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reproductor Local dedicado para archivos descargados (.m4a, .mp3, .mp4).
 * Posee una paleta brillante diferenciada del reproductor en línea, insignia de formato local,
 * extracción de carátulas embebidas y control total sobre la cola de descargas locales.
 */
class LocalPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLocalPlayerBinding
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
        binding = ActivityLocalPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("muzvidx_prefs", Context.MODE_PRIVATE)
        isVisualizerActive = prefs.getBoolean("visualizer_active", true)

        binding.visualizerView.setLocalTheme(true)

        PlaybackManager.init(this)
        binding.playerView.player = PlaybackManager.player

        applyPressAnimations()

        binding.btnMinimize.setOnClickListener { finish() }
        binding.btnClose.setOnClickListener { finish() }

        binding.btnRotate.setOnClickListener { toggleOrientation() }
        binding.btnPip.setOnClickListener { enterPipMode() }
        binding.btnVisualizer.setOnClickListener { toggleVisualizer() }

        binding.btnModeAudio.setOnClickListener { setVisualMode(false) }
        binding.btnModeVideo.setOnClickListener { setVisualMode(true) }

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

        binding.switchContinuous.isChecked = PlaybackManager.isContinuous
        binding.switchContinuous.setOnCheckedChangeListener { _, checked ->
            PlaybackManager.toggleContinuous(checked)
        }

        binding.btnRepeat.setOnClickListener {
            val newMode = PlaybackManager.cycleRepeatMode()
            updateRepeatButtonUi(newMode)
        }
        updateRepeatButtonUi(PlaybackManager.getCurrentRepeatMode())

        binding.btnFavorite.setOnClickListener { toggleFavorite() }
        binding.btnAddToPlaylist.setOnClickListener { showAddToPlaylistDialog() }
        binding.btnEqualizer.setOnClickListener { toggleEqualizerView() }

        // Doble toque izquierdo/derecho para retroceder/adelantar 10s
        var lastTapTime = 0L
        var lastTapX = 0f
        binding.mediaContainer.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val now = System.currentTimeMillis()
                val width = v.width
                if (now - lastTapTime < 300 && Math.abs(event.x - lastTapX) < 150) {
                    if (event.x < width / 2f) {
                        val pos = Math.max(0, PlaybackManager.getCurrentPositionMs() - 10000)
                        PlaybackManager.seekTo(pos)
                        Toast.makeText(this, "-10 seg", Toast.LENGTH_SHORT).show()
                    } else {
                        val pos = PlaybackManager.getCurrentPositionMs() + 10000
                        PlaybackManager.seekTo(pos)
                        Toast.makeText(this, "+10 seg", Toast.LENGTH_SHORT).show()
                    }
                    lastTapTime = 0L
                } else {
                    lastTapTime = now
                    lastTapX = event.x
                    if (PlaybackManager.isVideo) {
                        toggleControlsVisibility()
                    }
                }
            }
            true
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) PlaybackManager.seekTo(progress.toLong())
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        PlaybackManager.onTrackChangedListener = {
            runOnUiThread {
                updateUiForCurrentTrack()
                checkFavoriteStatus()
            }
        }

        PlaybackManager.onLoadingStateListener = { isLoading ->
            runOnUiThread {
                binding.pbLoadingTrack.visibility = if (isLoading) View.VISIBLE else View.GONE
            }
        }

        isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        setupEqualizerUi()
        updateUiForCurrentTrack()
        checkFavoriteStatus()
        startUiUpdater()

        binding.btnPlay.post {
            binding.btnPlay.requestFocus()
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
        val currentTrack = PlaybackManager.getCurrentTrack()
        if (isVideo && currentTrack?.type != ResultType.VIDEO) {
            Toast.makeText(this, "Archivo local de solo audio", Toast.LENGTH_SHORT).show()
            return
        }
        PlaybackManager.isVideoMode = isVideo
        updateVisualLayout()
    }

    private fun updateVisualLayout() {
        val isVideo = PlaybackManager.isVideo
        val localAccent = getColor(R.color.local_accent)
        val normalColor = getColor(R.color.text_secondary)
        val localBg = getColor(R.color.local_bg)
        val trans = getColor(android.R.color.transparent)

        binding.btnVisualizer.setColorFilter(if (isVisualizerActive) localAccent else normalColor)

        val sessionId = PlaybackManager.player?.audioSessionId ?: 0
        if (sessionId != 0) {
            binding.visualizerView.linkAudioSession(sessionId)
        }

        if (isVideo) {
            binding.btnModeVideo.backgroundTintList = ColorStateList.valueOf(localAccent)
            binding.btnModeVideo.setTextColor(localBg)
            binding.btnModeAudio.backgroundTintList = ColorStateList.valueOf(trans)
            binding.btnModeAudio.setTextColor(normalColor)

            binding.playerView.visibility = View.VISIBLE
            binding.ivThumb.visibility = View.GONE
            binding.equalizerContainer.visibility = if (isEqualizerVisible) View.VISIBLE else View.GONE

            if (isLandscape && isVisualizerActive && !isEqualizerVisible) {
                binding.videoContainer.visibility = View.VISIBLE
                binding.videoContainer.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f)

                binding.visualizerContainer.visibility = View.VISIBLE
                binding.visualizerContainer.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f)
                binding.visualizerContainer.setPadding(16, 56, 16, 28)
            } else {
                binding.videoContainer.visibility = View.VISIBLE
                binding.videoContainer.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f)
                binding.visualizerContainer.visibility = View.GONE
            }

            if (PlaybackManager.isPlaying) {
                scheduleControlsAutoHide()
            }
        } else {
            binding.btnModeAudio.backgroundTintList = ColorStateList.valueOf(localAccent)
            binding.btnModeAudio.setTextColor(localBg)
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
                binding.equalizerContainer.visibility = View.GONE
                binding.ivThumb.visibility = View.VISIBLE
                binding.visualizerContainer.visibility = View.GONE
                binding.videoContainer.visibility = View.VISIBLE
                binding.videoContainer.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f)
            }
        }
    }

    private fun applyPressAnimations() {
        val interactiveViews = listOf(
            binding.btnMinimize,
            binding.btnClose,
            binding.btnRotate,
            binding.btnPip,
            binding.btnVisualizer,
            binding.btnEqualizer,
            binding.btnPrev,
            binding.btnRewind,
            binding.btnPlay,
            binding.btnForward,
            binding.btnNext,
            binding.btnFavorite,
            binding.btnAddToPlaylist,
            binding.btnRepeat,
            binding.btnModeAudio,
            binding.btnModeVideo
        )
        for (v in interactiveViews) {
            v.setOnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        view.animate().scaleX(0.90f).scaleY(0.90f).setDuration(90).start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.animate().scaleX(1.0f).scaleY(1.0f)
                            .setInterpolator(OvershootInterpolator(2.0f)).setDuration(180).start()
                    }
                }
                false
            }
            v.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.animate().scaleX(1.18f).scaleY(1.18f).setDuration(120).start()
                } else {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                }
            }
        }
    }

    private fun toggleOrientation() {
        requestedOrientation = if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        updateVisualLayout()
    }

    private fun toggleControlsVisibility() {
        isControlsVisible = !isControlsVisible
        val vis = if (isControlsVisible) View.VISIBLE else View.GONE
        val currentTrack = PlaybackManager.getCurrentTrack()
        binding.topBar.visibility = vis
        binding.modeSwitchBar.visibility = if (isControlsVisible && currentTrack?.type == ResultType.VIDEO) View.VISIBLE else View.GONE
        binding.actionsBar.visibility = vis
        binding.bottomControls.visibility = vis

        if (isControlsVisible && PlaybackManager.isVideo) {
            scheduleControlsAutoHide()
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
        autoHideRunnable?.let { handler.postDelayed(it, 4000) }
    }

    private fun toggleEqualizerView() {
        isEqualizerVisible = !isEqualizerVisible
        binding.btnEqualizer.setColorFilter(
            if (isEqualizerVisible) getColor(R.color.local_accent_gold) else getColor(R.color.local_accent)
        )
        if (isEqualizerVisible) {
            autoHideRunnable?.let { handler.removeCallbacks(it) }
        }
        updateVisualLayout()
    }

    private fun setupEqualizerUi() {
        val ctx = this
        val sessionId = PlaybackManager.player?.audioSessionId ?: 0
        if (sessionId != 0) {
            AudioEqualizerManager.init(ctx, sessionId)
        }

        binding.switchEqEnabled.isChecked = AudioEqualizerManager.isEnabled
        binding.switchEqEnabled.setOnCheckedChangeListener { _, isChecked ->
            AudioEqualizerManager.setEnabled(isChecked, ctx)
        }

        val presets = AudioEqualizerManager.getPresets()
        val defaultPresets = if (presets.isNotEmpty()) presets else listOf(
            "Normal", "Rock", "Pop", "Jazz", "Bass Boost", "Electrónica", "Clásica", "Vocal"
        )

        binding.presetChipsContainer.removeAllViews()
        for ((idx, presetName) in defaultPresets.withIndex()) {
            val btn = MaterialButton(ctx).apply {
                text = presetName
                textSize = 11f
                setTextColor(getColor(R.color.text_primary))
                setBackgroundColor(getColor(R.color.local_bg))
                cornerRadius = 24
                setPadding(28, 8, 28, 8)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(8, 0, 8, 0) }
                layoutParams = lp

                setOnClickListener {
                    AudioEqualizerManager.usePreset(idx.toShort(), ctx)
                    Toast.makeText(ctx, "Modo: $presetName", Toast.LENGTH_SHORT).show()
                    refreshBandSliders()
                }
            }
            binding.presetChipsContainer.addView(btn)
        }

        refreshBandSliders()

        binding.seekBassBoost.progress = AudioEqualizerManager.getBassBoostStrength().toInt()
        binding.seekBassBoost.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) AudioEqualizerManager.setBassBoost(p.toShort(), ctx)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        binding.seekVirtualizer.progress = AudioEqualizerManager.getVirtualizerStrength().toInt()
        binding.seekVirtualizer.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) AudioEqualizerManager.setVirtualizer(p.toShort(), ctx)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun refreshBandSliders() {
        val ctx = this
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

        val localAccent = getColor(R.color.local_accent)
        val localGold = getColor(R.color.local_accent_gold)
        for (band in displayBands) {
            val bandRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 4, 0, 4) }
            }

            val freqText = if (band.centerFreqHz >= 1000) "${band.centerFreqHz / 1000} kHz" else "${band.centerFreqHz} Hz"
            val tvLabel = TextView(ctx).apply {
                text = freqText
                width = 140
                textSize = 11f
                setTextColor(localAccent)
            }

            val seekBar = SeekBar(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                val range = (band.maxLevel - band.minLevel).toInt().coerceAtLeast(1)
                max = range
                progress = (band.currentLevel - band.minLevel).toInt()
                progressTintList = ColorStateList.valueOf(localAccent)
                thumbTintList = ColorStateList.valueOf(localGold)

                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        if (fromUser) {
                            val newLevel = (p + band.minLevel).toShort()
                            AudioEqualizerManager.setBandLevel(band.index, newLevel, ctx)
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

    private fun updateRepeatButtonUi(mode: Int) {
        when (mode) {
            Player.REPEAT_MODE_OFF -> {
                binding.btnRepeat.setColorFilter(getColor(R.color.text_secondary))
                binding.btnRepeat.alpha = 0.6f
            }
            Player.REPEAT_MODE_ALL -> {
                binding.btnRepeat.setColorFilter(getColor(R.color.local_accent))
                binding.btnRepeat.alpha = 1.0f
            }
            Player.REPEAT_MODE_ONE -> {
                binding.btnRepeat.setColorFilter(getColor(R.color.local_accent_gold))
                binding.btnRepeat.alpha = 1.0f
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
            binding.btnFavorite.setImageResource(
                if (isFav) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
            )
            binding.btnFavorite.setColorFilter(
                if (isFav) getColor(R.color.local_accent_gold) else getColor(R.color.text_primary)
            )
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
                Toast.makeText(this@LocalPlayerActivity, "Eliminado de favoritos", Toast.LENGTH_SHORT).show()
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
                binding.btnFavorite.setColorFilter(getColor(R.color.local_accent_gold))
                Toast.makeText(this@LocalPlayerActivity, "Añadido a favoritos", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showAddToPlaylistDialog() {
        val track = PlaybackManager.getCurrentTrack() ?: return
        val db = DownloadDatabase.getInstance(this)
        scope.launch {
            val playlists = withContext(Dispatchers.IO) { db.downloadDao().getAllPlaylists() }
            val options = mutableListOf("Crear nueva playlist")
            options.addAll(playlists.map { "${it.name} (${it.trackCount} temas)" })

            AlertDialog.Builder(this@LocalPlayerActivity)
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
                        val newId = withContext(Dispatchers.IO) { db.downloadDao().createPlaylist(name) }
                        addTrackToPlaylist(newId, name, track)
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
            Toast.makeText(this@LocalPlayerActivity, "Añadido a \"$playlistName\"", Toast.LENGTH_SHORT).show()
        }
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        } else {
            Toast.makeText(this, "Modo ventana flotante requiere Android 8+", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            isControlsVisible = false
            binding.topBar.visibility = View.GONE
            binding.modeSwitchBar.visibility = View.GONE
            binding.actionsBar.visibility = View.GONE
            binding.bottomControls.visibility = View.GONE
        } else {
            isControlsVisible = true
            val currentTrack = PlaybackManager.getCurrentTrack()
            binding.topBar.visibility = View.VISIBLE
            binding.modeSwitchBar.visibility = if (currentTrack?.type == ResultType.VIDEO) View.VISIBLE else View.GONE
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

            binding.tvArtist.text = if (cleanArtist.isNotEmpty() && !cleanArtist.equals("Oficial", true)) cleanArtist else "Archivo Local"
            binding.tvArtist.isSelected = true

            // Insignia de formato local (.MP3 / .M4A / .MP4)
            val filePath = track.audioUrl.ifEmpty { track.videoUrl }
            val ext = filePath.substringAfterLast('.', "").uppercase()
            val formatLabel = when (ext) {
                "MP3", "M4A", "MP4" -> "LOCAL • $ext"
                else -> if (track.type == ResultType.VIDEO) "LOCAL • MP4" else "LOCAL • AUDIO"
            }
            binding.tvFormatBadge.text = formatLabel

            // Mostrar switch Audio/Video solo para archivos MP4 locales
            binding.modeSwitchBar.visibility = if (isControlsVisible && track.type == ResultType.VIDEO) View.VISIBLE else View.GONE

            // Cargar carátula embebida del archivo local (M4A/MP3) o frame de MP4
            loadLocalArtwork(filePath, track.thumbUrl)

            updateVisualLayout()

            val isPlaying = PlaybackManager.isPlaying
            binding.btnPlay.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        }
    }

    private fun loadLocalArtwork(filePath: String, fallbackThumbUrl: String) {
        if (fallbackThumbUrl.isNotEmpty()) {
            Glide.with(this)
                .load(fallbackThumbUrl)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.ic_music_note)
                .error(R.drawable.ic_music_note)
                .into(binding.ivThumb)
            return
        }

        val cleanPath = filePath.removePrefix("file://")
        val f = File(cleanPath)
        if (!f.exists()) {
            binding.ivThumb.setImageResource(R.drawable.ic_music_note)
            return
        }

        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(cleanPath)
                    val art = retriever.embeddedPicture
                    if (art != null) {
                        BitmapFactory.decodeByteArray(art, 0, art.size)
                    } else if (cleanPath.endsWith(".mp4", ignoreCase = true)) {
                        retriever.getFrameAtTime(1_000_000L)
                    } else {
                        null
                    }
                } catch (_: Exception) {
                    null
                } finally {
                    try { retriever.release() } catch (_: Exception) {}
                }
            }
            if (bitmap != null) {
                binding.ivThumb.setImageBitmap(bitmap)
            } else {
                binding.ivThumb.setImageResource(R.drawable.ic_music_note)
            }
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (isControlsVisible && PlaybackManager.isVideo) {
                scheduleControlsAutoHide()
            }

            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK -> {
                    binding.btnPlay.performClick()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    if (!PlaybackManager.isPlaying) binding.btnPlay.performClick()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    if (PlaybackManager.isPlaying) binding.btnPlay.performClick()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    binding.btnNext.performClick()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    binding.btnPrev.performClick()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    val pos = PlaybackManager.getCurrentPositionMs() + 10000
                    PlaybackManager.seekTo(pos)
                    showControlsBriefly()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    val pos = Math.max(0, PlaybackManager.getCurrentPositionMs() - 10000)
                    PlaybackManager.seekTo(pos)
                    showControlsBriefly()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    if (!isControlsVisible) {
                        toggleControlsVisibility()
                        return true
                    } else if (currentFocus == null) {
                        binding.btnPlay.performClick()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (!isControlsVisible) {
                        val pos = Math.max(0, PlaybackManager.getCurrentPositionMs() - 10000)
                        PlaybackManager.seekTo(pos)
                        showControlsBriefly()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!isControlsVisible) {
                        val pos = PlaybackManager.getCurrentPositionMs() + 10000
                        PlaybackManager.seekTo(pos)
                        showControlsBriefly()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (!isControlsVisible) {
                        toggleControlsVisibility()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (!isControlsVisible) {
                        toggleControlsVisibility()
                        return true
                    } else {
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
                KeyEvent.KEYCODE_BACK -> {
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
        PlaybackManager.onResolutionChangedListener = null
        if (!isChangingConfigurations) {
            binding.playerView.player = null
        }
    }
}
