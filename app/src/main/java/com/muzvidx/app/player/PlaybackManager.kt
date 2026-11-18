package com.muzvidx.app.player

import android.content.Context
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.muzvidx.app.extractor.VideoStreamsBundle
import com.muzvidx.app.extractor.YouTubeService
import com.muzvidx.app.model.ResultType
import com.muzvidx.app.model.SearchResult
import com.muzvidx.app.ui.views.RealtimeVisualizerAudioProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object PlaybackManager {
    private const val TAG = "PlaybackManager"
    private const val USER_AGENT_ANDROID =
        "com.google.android.youtube/21.03.36(Linux; U; Android 16; en_US; SM-S908E Build/TP1A.220624.014) gzip"

    var player: ExoPlayer? = null
    var queue: MutableList<SearchResult> = mutableListOf()
    var currentIndex: Int = 0
    var isContinuous: Boolean = true
    var isVideoMode: Boolean = false // false = Audio (carátula), true = Video (reproductor MP4)
    var isLoadingTrack: Boolean = false

    private var appContext: Context? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var loadJob: Job? = null
    val streamCache = LruCache<String, String>(60)

    val isPlaying: Boolean get() = player?.isPlaying == true
    val isVideo: Boolean get() = isVideoMode || (getCurrentTrack()?.type == ResultType.VIDEO)

    var onTrackChangedListener: ((SearchResult?) -> Unit)? = null
    var onLoadingStateListener: ((Boolean) -> Unit)? = null
    var onResolutionChangedListener: ((String) -> Unit)? = null

    var currentVideoResolution: String = "Auto"
    var currentVideoStreamsBundle: VideoStreamsBundle? = null
    val visualizerAudioProcessor = RealtimeVisualizerAudioProcessor()

    fun init(context: Context) {
        if (player != null) return
        val app = context.applicationContext
        appContext = app

        // 1. Fábrica HTTP para streams remotos
        val httpSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT_ANDROID)
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(20000)
            .setAllowCrossProtocolRedirects(true)

        // 2. Fábrica de datos predeterminada: CRUCIAL para reproducir archivos locales (file://) Y streams remotos (http/https)
        val defaultDataSourceFactory = DefaultDataSource.Factory(app, httpSourceFactory)

        val mediaSourceFactory = DefaultMediaSourceFactory(app)
            .setDataSourceFactory(defaultDataSourceFactory)

        // 3. RenderersFactory con AudioProcessor para análisis espectral en tiempo real (PCM direct sink)
        val renderersFactory = object : DefaultRenderersFactory(app) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(visualizerAudioProcessor))
                    .build()
            }
        }

        player = ExoPlayer.Builder(app, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_BUFFERING -> {
                                isLoadingTrack = true
                                onLoadingStateListener?.invoke(true)
                            }
                            Player.STATE_READY -> {
                                isLoadingTrack = false
                                onLoadingStateListener?.invoke(false)
                                val sessionId = player?.audioSessionId ?: 0
                                if (sessionId != 0) {
                                    AudioEqualizerManager.init(app, sessionId)
                                }
                            }
                            Player.STATE_ENDED -> {
                                isLoadingTrack = false
                                onLoadingStateListener?.invoke(false)
                                Log.i(TAG, "Track finalizado. isContinuous=$isContinuous, repeatMode=$repeatMode")
                                if (repeatMode == Player.REPEAT_MODE_ONE) {
                                    seekTo(0)
                                    play()
                                } else if (isContinuous && queue.isNotEmpty()) {
                                    playNext()
                                }
                            }
                            Player.STATE_IDLE -> {
                                isLoadingTrack = false
                                onLoadingStateListener?.invoke(false)
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        isLoadingTrack = false
                        onLoadingStateListener?.invoke(false)
                        Log.e(TAG, "Error en ExoPlayer: ${error.errorCodeName} - ${error.message}", error)
                        // Auto-recuperación: si falla el stream y hay cola continua, pasar al siguiente tema sin congelarse
                        if (isContinuous && queue.size > 1) {
                            scope.launch {
                                kotlinx.coroutines.delay(1200)
                                playNext()
                            }
                        }
                    }

                    override fun onAudioSessionIdChanged(audioSessionId: Int) {
                        if (audioSessionId != 0) {
                            AudioEqualizerManager.init(app, audioSessionId)
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        appContext?.let { ctx ->
                            getCurrentTrack()?.let { track ->
                                MusicPlaybackService.start(ctx, track.title, track.artist)
                            }
                        }
                    }
                })
            }

        if (queue.isNotEmpty()) {
            loadCurrent()
        }
    }

    fun setQueue(newQueue: List<SearchResult>, startIndex: Int = 0) {
        queue.clear()
        queue.addAll(newQueue)
        currentIndex = startIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
        val current = getCurrentTrack()
        if (current != null) {
            isVideoMode = (current.type == ResultType.VIDEO)
        }
        loadCurrent()
    }

    fun playNext() {
        if (queue.isEmpty()) return
        currentIndex = (currentIndex + 1) % queue.size
        loadCurrent()
    }

    fun playPrev() {
        if (queue.isEmpty()) return
        currentIndex = (currentIndex - 1 + queue.size) % queue.size
        loadCurrent()
    }

    fun pause() {
        player?.pause()
        player?.playWhenReady = false
        appContext?.let { ctx ->
            getCurrentTrack()?.let { track ->
                MusicPlaybackService.start(ctx, track.title, track.artist)
            }
        }
    }

    fun resume() {
        player?.play()
        player?.playWhenReady = true
        appContext?.let { ctx ->
            getCurrentTrack()?.let { track ->
                MusicPlaybackService.start(ctx, track.title, track.artist)
            }
        }
    }

    fun release() {
        loadJob?.cancel()
        AudioEqualizerManager.release()
        player?.stop()
        player?.release()
        player = null
        queue.clear()
        currentIndex = 0
    }

    fun toggleContinuous(checked: Boolean) {
        isContinuous = checked
    }

    fun cycleRepeatMode(): Int {
        val p = player ?: return Player.REPEAT_MODE_OFF
        val nextMode = when (p.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        p.repeatMode = nextMode
        return nextMode
    }

    fun getCurrentRepeatMode(): Int = player?.repeatMode ?: Player.REPEAT_MODE_OFF

    fun getCurrentPositionMs(): Long = player?.currentPosition ?: 0L
    fun getDurationMs(): Long = player?.duration ?: 0L
    fun seekTo(ms: Long) {
        player?.seekTo(ms)
    }

    fun getCurrentTrack(): SearchResult? =
        if (queue.isEmpty()) null else queue.getOrNull(currentIndex)

    fun cleanVideoId(rawUrl: String): String {
        return if (rawUrl.contains("v=")) {
            rawUrl.substringAfter("v=").substringBefore("&")
        } else if (rawUrl.contains("youtu.be/")) {
            rawUrl.substringAfter("youtu.be/").substringBefore("?").substringBefore("&")
        } else {
            rawUrl.removePrefix("yt_").removePrefix("yt_vid_")
        }
    }

    /**
     * Cambia el stream de reproducción en caliente manteniendo la posición actual (útil al cambiar de Audio a Video)
     */
    fun switchMediaStream(rawUrl: String, startPosMs: Long = 0L) {
        loadJob?.cancel()
        isLoadingTrack = true
        onLoadingStateListener?.invoke(true)
        loadJob = scope.launch {
            val streamUrl: String? = if (rawUrl.startsWith("/") || rawUrl.startsWith("file://")) {
                rawUrl
            } else if (rawUrl.contains("youtube.com") || rawUrl.contains("youtu.be") || rawUrl.startsWith("yt_") || rawUrl.length == 11) {
                val vid = cleanVideoId(rawUrl)
                val cached = streamCache.get(vid)
                if (!cached.isNullOrEmpty()) {
                    cached
                } else {
                    val resolved = withContext(Dispatchers.IO) {
                        YouTubeService().resolveStreamUrl(vid, isAudioOnly = false)
                    }
                    if (!resolved.isNullOrEmpty()) {
                        streamCache.put(vid, resolved)
                        resolved
                    } else null
                }
            } else rawUrl

            if (!streamUrl.isNullOrEmpty()) {
                val uri = if (streamUrl.startsWith("/")) Uri.fromFile(File(streamUrl)) else Uri.parse(streamUrl)
                try {
                    player?.setMediaItem(MediaItem.fromUri(uri), startPosMs)
                    player?.prepare()
                    player?.play()
                } catch (e: Exception) {
                    Log.e(TAG, "Error switching media stream", e)
                }
            }
            isLoadingTrack = false
            onLoadingStateListener?.invoke(false)
        }
    }

    fun switchVideoResolution(quality: String) {
        currentVideoResolution = quality
        onResolutionChangedListener?.invoke(quality)

        val track = getCurrentTrack() ?: return
        if (!isVideo) return
        val rawUrl = track.videoUrl.ifEmpty { track.audioUrl }
        val currentPos = getCurrentPositionMs()

        if (rawUrl.startsWith("/") || rawUrl.startsWith("file://")) {
            return
        }

        val bundle = currentVideoStreamsBundle
        if (bundle != null) {
            applyVideoStreamsToPlayer(bundle, quality, currentPos)
        } else {
            val vid = cleanVideoId(rawUrl)
            loadJob?.cancel()
            isLoadingTrack = true
            onLoadingStateListener?.invoke(true)
            loadJob = scope.launch {
                try {
                    val resolvedBundle = withContext(Dispatchers.IO) {
                        YouTubeService().resolveVideoStreams(vid)
                    }
                    if (resolvedBundle != null) {
                        currentVideoStreamsBundle = resolvedBundle
                        applyVideoStreamsToPlayer(resolvedBundle, quality, currentPos)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error switching video resolution: ${e.message}", e)
                } finally {
                    isLoadingTrack = false
                    onLoadingStateListener?.invoke(false)
                }
            }
        }
    }

    private fun applyVideoStreamsToPlayer(bundle: VideoStreamsBundle, quality: String, startPosMs: Long) {
        val p = player ?: return
        val httpSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT_ANDROID)
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(20000)
            .setAllowCrossProtocolRedirects(true)

        try {
            // Caso 1: Manifiesto HLS disponible
            if (!bundle.hlsManifestUrl.isNullOrEmpty()) {
                if (quality == "Auto") {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .clearVideoSizeConstraints()
                        .build()
                } else {
                    val (w, h) = when (quality) {
                        "1080p" -> Pair(1920, 1080)
                        "720p" -> Pair(1280, 720)
                        "480p" -> Pair(854, 480)
                        "360p" -> Pair(640, 360)
                        "240p" -> Pair(426, 240)
                        else -> Pair(1280, 720)
                    }
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setMaxVideoSize(w, h)
                        .setMinVideoSize(w, h)
                        .build()
                }
                Log.i(TAG, "Aplicando HLS en ExoPlayer (calidad=$quality): ${bundle.hlsManifestUrl}")
                p.setMediaItem(MediaItem.fromUri(Uri.parse(bundle.hlsManifestUrl)), startPosMs)
                p.prepare()
                p.play()
                return
            }

            // Caso 2: Formatos directos HTTP (1080p, 720p, 480p, 360p)
            val selectedFormat = if (quality == "Auto") {
                bundle.formats.find { it.qualityLabel == "720p" } ?: bundle.formats.firstOrNull()
            } else {
                bundle.formats.find { it.qualityLabel == quality } ?: bundle.formats.firstOrNull()
            }

            if (selectedFormat != null) {
                if (selectedFormat.isProgressive || selectedFormat.audioUrl.isNullOrEmpty()) {
                    Log.i(TAG, "Cargando video progresivo directo ($quality): ${selectedFormat.videoUrl}")
                    p.setMediaItem(MediaItem.fromUri(Uri.parse(selectedFormat.videoUrl)), startPosMs)
                    p.prepare()
                    p.play()
                } else {
                    Log.i(TAG, "Cargando MergingMediaSource video ($quality) + audio AAC")
                    val videoSource = ProgressiveMediaSource.Factory(httpSourceFactory)
                        .createMediaSource(MediaItem.fromUri(Uri.parse(selectedFormat.videoUrl)))
                    val audioSource = ProgressiveMediaSource.Factory(httpSourceFactory)
                        .createMediaSource(MediaItem.fromUri(Uri.parse(selectedFormat.audioUrl)))
                    val merged = MergingMediaSource(videoSource, audioSource)

                    p.setMediaSource(merged, startPosMs)
                    p.prepare()
                    p.play()
                }
            } else {
                Log.i(TAG, "Cargando default stream: ${bundle.defaultStreamUrl}")
                p.setMediaItem(MediaItem.fromUri(Uri.parse(bundle.defaultStreamUrl)), startPosMs)
                p.prepare()
                p.play()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error aplicando stream de video a ExoPlayer", e)
        }
    }

    fun loadCurrent() {
        val track = getCurrentTrack() ?: return
        val rawUrl = if (isVideoMode) track.videoUrl.ifEmpty { track.audioUrl } else track.audioUrl.ifEmpty { track.videoUrl }
        onTrackChangedListener?.invoke(track)

        appContext?.let { ctx ->
            MusicPlaybackService.start(ctx, track.title, track.artist)
        }

        // 1. Detener reproducción anterior de inmediato para evitar que se quede pegada
        player?.stop()
        isLoadingTrack = true
        onLoadingStateListener?.invoke(true)

        loadJob?.cancel()
        loadJob = scope.launch {
            if (rawUrl.startsWith("/") || rawUrl.startsWith("file://")) {
                // Archivo local descargado
                val f = File(rawUrl)
                if (!f.exists() || f.length() < 1000) {
                    Log.e(TAG, "El archivo local no existe o está corrupto: $rawUrl")
                    isLoadingTrack = false
                    onLoadingStateListener?.invoke(false)
                    return@launch
                }
                player?.setMediaItem(MediaItem.fromUri(Uri.fromFile(f)))
                player?.prepare()
                player?.play()
                isLoadingTrack = false
                onLoadingStateListener?.invoke(false)
                return@launch
            }

            // Stream en línea de YouTube
            if (rawUrl.contains("youtube.com") || rawUrl.contains("youtu.be") || rawUrl.startsWith("yt_") || rawUrl.length == 11) {
                val vid = cleanVideoId(rawUrl)

                if (isVideoMode) {
                    // Modo Video: Obtener catálogo de resoluciones múltiples
                    val bundle = withContext(Dispatchers.IO) {
                        YouTubeService().resolveVideoStreams(vid)
                    }
                    if (bundle != null && bundle.formats.isNotEmpty()) {
                        currentVideoStreamsBundle = bundle
                        applyVideoStreamsToPlayer(bundle, currentVideoResolution, 0L)
                        isLoadingTrack = false
                        onLoadingStateListener?.invoke(false)
                        return@launch
                    }
                }

                // Modo Audio o fallback de video
                val cached = streamCache.get(vid)
                val streamUrl = if (!cached.isNullOrEmpty()) {
                    cached
                } else {
                    val resolved = withContext(Dispatchers.IO) {
                        YouTubeService().resolveStreamUrl(vid, isAudioOnly = !isVideoMode)
                    }
                    if (!resolved.isNullOrEmpty()) {
                        streamCache.put(vid, resolved)
                        resolved
                    } else null
                }

                if (!streamUrl.isNullOrEmpty()) {
                    try {
                        player?.setMediaItem(MediaItem.fromUri(Uri.parse(streamUrl)))
                        player?.prepare()
                        player?.play()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error preparando ExoPlayer", e)
                    }
                } else {
                    Log.e(TAG, "No se pudo obtener stream para: ${track.title}")
                    if (isContinuous && queue.size > 1) {
                        kotlinx.coroutines.delay(1000)
                        playNext()
                    }
                }
            } else {
                try {
                    player?.setMediaItem(MediaItem.fromUri(Uri.parse(rawUrl)))
                    player?.prepare()
                    player?.play()
                } catch (e: Exception) {
                    Log.e(TAG, "Error cargando URL externa: $rawUrl", e)
                }
            }

            isLoadingTrack = false
            onLoadingStateListener?.invoke(false)
        }
    }
}
