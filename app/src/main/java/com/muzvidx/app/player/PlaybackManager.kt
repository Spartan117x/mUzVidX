package com.muzvidx.app.player

import android.content.Context
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.hls.HlsMediaSource
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
import android.widget.Toast
import java.io.File

object PlaybackManager {
    private const val TAG = "PlaybackManager"
    private const val USER_AGENT_ANDROID =
        "com.google.android.youtube/21.03.36(Linux; U; Android 16; en_US; SM-S908E Build/TP1A.220624.014) gzip"
    private const val USER_AGENT_IOS =
        "com.google.ios.youtube/20.11.6 (iPhone10,4; U; CPU iOS 16_7_7 like Mac OS X)"
    private const val USER_AGENT_VISIONOS =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"

    fun getHttpDataSourceFactory(url: String): DefaultHttpDataSource.Factory {
        val ua = if (url.contains("manifest") || url.contains("hls")) {
            USER_AGENT_VISIONOS
        } else if (url.contains("c=IOS") || url.contains("c%3DIOS")) {
            USER_AGENT_IOS
        } else {
            USER_AGENT_ANDROID
        }
        return DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(30000)
            .setAllowCrossProtocolRedirects(true)
    }

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

    var userSelectedResolution: String = "1080p"

    val isPlaying: Boolean get() = player?.isPlaying == true
    val isVideo: Boolean get() = isVideoMode

    var onTrackChangedListener: ((SearchResult?) -> Unit)? = null
    var onLoadingStateListener: ((Boolean) -> Unit)? = null
    var onResolutionChangedListener: ((String) -> Unit)? = null

    var currentVideoResolution: String = "1080p"
    var currentVideoStreamsBundle: VideoStreamsBundle? = null
    var desiredVideoHeight: Int? = null
    val visualizerAudioProcessor = RealtimeVisualizerAudioProcessor()

    fun saveSelectedResolution(context: Context, res: String) {
        userSelectedResolution = res
        currentVideoResolution = res
        try {
            context.getSharedPreferences("muzvidx_prefs", Context.MODE_PRIVATE)
                .edit().putString("user_selected_resolution", res).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Error saving resolution preference", e)
        }
        onResolutionChangedListener?.invoke(res)
    }

    fun initResolution(context: Context) {
        try {
            val prefs = context.getSharedPreferences("muzvidx_prefs", Context.MODE_PRIVATE)
            val saved = prefs.getString("user_selected_resolution", "1080p") ?: "1080p"
            userSelectedResolution = saved
            currentVideoResolution = saved
        } catch (e: Exception) {
            Log.w(TAG, "Error loading resolution preference", e)
        }
    }

    fun init(context: Context) {
        if (player != null) return
        val app = context.applicationContext
        appContext = app
        initResolution(app)

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

                    override fun onTracksChanged(tracks: Tracks) {
                        if (!isVideoMode) return
                        val targetHeight = desiredVideoHeight ?: return
                        val targetVideoGroup = tracks.groups.find { group ->
                            group.type == C.TRACK_TYPE_VIDEO && group.length > 0
                        } ?: return

                        var bestIndex = -1
                        var bestDiff = Int.MAX_VALUE
                        for (i in 0 until targetVideoGroup.length) {
                            val f = targetVideoGroup.getTrackFormat(i)
                            val h = f.height
                            if (h <= 0) continue
                            if (h == targetHeight) {
                                bestIndex = i
                                break
                            }
                            val diff = Math.abs(h - targetHeight)
                            if (diff < bestDiff) {
                                bestDiff = diff
                                bestIndex = i
                            }
                        }

                        if (bestIndex != -1 && !targetVideoGroup.isTrackSelected(bestIndex)) {
                            Log.i(TAG, "Forzando TrackSelectionOverride para resolución ${targetHeight}p (track $bestIndex, ${targetVideoGroup.getTrackFormat(bestIndex).height}p)")
                            player?.trackSelectionParameters = player?.trackSelectionParameters?.buildUpon()
                                ?.setOverrideForType(TrackSelectionOverride(targetVideoGroup.mediaTrackGroup, bestIndex))
                                ?.build() ?: return
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        isLoadingTrack = false
                        onLoadingStateListener?.invoke(false)
                        Log.e(TAG, "Error en ExoPlayer: ${error.errorCodeName} - ${error.message}", error)

                        // Si falló un formato adaptativo (720p / 1080p), continuar fluidamente
                        // con el stream progresivo oficial garantizado (itag 18 / 360p) para ESTE MISMO video
                        // manteniendo la posición exacta de reproducción (SIN reiniciar desde 0:00 ni crear bucle)
                        val progFormat = currentVideoStreamsBundle?.formats?.find { it.isProgressive }
                        if (isVideo && progFormat != null && desiredVideoHeight != 360) {
                            val currentPos = getCurrentPositionMs()
                            Log.w(TAG, "Resolución adaptativa requiere fallback en posición ${currentPos}ms. Continuando fluidamente en 360p...")
                            desiredVideoHeight = 360
                            currentVideoResolution = "360p"
                            onResolutionChangedListener?.invoke("360p")

                            scope.launch {
                                try {
                                    val httpFactory = getHttpDataSourceFactory(progFormat.videoUrl)
                                    val source = ProgressiveMediaSource.Factory(httpFactory)
                                        .createMediaSource(MediaItem.fromUri(Uri.parse(progFormat.videoUrl)))
                                    player?.trackSelectionParameters = player?.trackSelectionParameters?.buildUpon()
                                        ?.clearVideoSizeConstraints()
                                        ?.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                                        ?.build() ?: return@launch
                                    player?.setMediaSource(source, currentPos)
                                    player?.prepare()
                                    player?.play()
                                    appContext?.let {
                                        Toast.makeText(it, "Continuando reproducción en calidad fluida (360p)", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Fallback progresivo falló", e)
                                    appContext?.let {
                                        Toast.makeText(it, "Error al reproducir video", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            return
                        }

                        // Si no es posible recuperar, NO entrar en bucle infinito saltando temas.
                        // Mantener la pista actual y avisar al usuario.
                        appContext?.let {
                            Toast.makeText(it, "No se pudo reproducir este contenido", Toast.LENGTH_SHORT).show()
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

    fun stop() {
        loadJob?.cancel()
        player?.stop()
        player?.clearMediaItems()
        queue.clear()
        currentIndex = 0
        appContext?.let { ctx ->
            MusicPlaybackService.stop(ctx)
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

    fun cleanVideoId(rawUrl: String): String = YouTubeService.cleanVideoId(rawUrl)

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
                    val httpFactory = getHttpDataSourceFactory(streamUrl)
                    val source = ProgressiveMediaSource.Factory(httpFactory)
                        .createMediaSource(MediaItem.fromUri(uri))
                    player?.setMediaSource(source, startPosMs)
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
        userSelectedResolution = quality
        currentVideoResolution = quality
        appContext?.let { saveSelectedResolution(it, quality) }
        isVideoMode = true
        onResolutionChangedListener?.invoke(quality)

        val bundle = currentVideoStreamsBundle
        val p = player
        if (bundle != null && !bundle.hlsManifestUrl.isNullOrEmpty() && p != null) {
            val targetHeight = when (quality) {
                "1080p" -> 1080
                "720p" -> 720
                "480p" -> 480
                "360p" -> 360
                "240p" -> 240
                else -> null
            }
            desiredVideoHeight = targetHeight
            val paramBuilder = p.trackSelectionParameters.buildUpon()
            if (targetHeight != null) {
                val maxW = (targetHeight * 16) / 9 + 100
                val minW = (targetHeight * 16) / 9 - 100
                paramBuilder.setMaxVideoSize(maxW, targetHeight)
                    .setMinVideoSize(minW.coerceAtLeast(1), (targetHeight - 20).coerceAtLeast(1))

                val videoGroup = p.currentTracks.groups.find { it.type == C.TRACK_TYPE_VIDEO && it.length > 0 }
                if (videoGroup != null) {
                    var bestIndex = -1
                    var bestDiff = Int.MAX_VALUE
                    for (i in 0 until videoGroup.length) {
                        val f = videoGroup.getTrackFormat(i)
                        val h = f.height
                        if (h <= 0) continue
                        if (h == targetHeight) {
                            bestIndex = i
                            break
                        }
                        val diff = Math.abs(h - targetHeight)
                        if (diff < bestDiff) {
                            bestDiff = diff
                            bestIndex = i
                        }
                    }
                    if (bestIndex != -1) {
                        paramBuilder.setOverrideForType(TrackSelectionOverride(videoGroup.mediaTrackGroup, bestIndex))
                    }
                }
            } else {
                paramBuilder.clearVideoSizeConstraints()
                paramBuilder.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            }
            p.trackSelectionParameters = paramBuilder.build()
            Log.i(TAG, "Resolución HLS cambiada dinámicamente a $quality (targetHeight=$targetHeight)")
            return
        }

        val track = getCurrentTrack() ?: return
        val rawUrl = track.videoUrl.ifEmpty { track.audioUrl }
        val currentPos = getCurrentPositionMs()

        if (rawUrl.startsWith("/") || rawUrl.startsWith("file://")) {
            return
        }

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

        try {
            currentVideoStreamsBundle = bundle

            // 0. Si existe manifiesto HLS oficial, usar HlsMediaSource.
            // HLS provee streams continuos en 1080p, 720p, 480p, 360p con audio integrado
            // y ratebypass=yes sin bloqueos 403 a los 60 segundos.
            if (!bundle.hlsManifestUrl.isNullOrEmpty()) {
                val targetHeight = when (quality) {
                    "1080p" -> 1080
                    "720p" -> 720
                    "480p" -> 480
                    "360p" -> 360
                    "240p" -> 240
                    else -> null // Auto
                }
                desiredVideoHeight = targetHeight

                val hlsHttpFactory = getHttpDataSourceFactory(bundle.hlsManifestUrl)
                val hlsSource = HlsMediaSource.Factory(hlsHttpFactory)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(
                        MediaItem.Builder()
                            .setUri(Uri.parse(bundle.hlsManifestUrl))
                            .setMimeType(MimeTypes.APPLICATION_M3U8)
                            .build()
                    )

                val paramBuilder = p.trackSelectionParameters.buildUpon()
                if (targetHeight != null) {
                    val maxW = (targetHeight * 16) / 9 + 100
                    val minW = (targetHeight * 16) / 9 - 100
                    paramBuilder.setMaxVideoSize(maxW, targetHeight)
                        .setMinVideoSize(minW.coerceAtLeast(1), (targetHeight - 20).coerceAtLeast(1))
                } else {
                    paramBuilder.clearVideoSizeConstraints()
                }
                p.trackSelectionParameters = paramBuilder.build()

                Log.i(TAG, "Reproduciendo mediante HlsMediaSource (calidad=$quality, targetHeight=$targetHeight)")
                p.setMediaSource(hlsSource, startPosMs)
                p.prepare()
                p.play()
                return
            }

            // 1. Identificar stream progresivo garantizado (itag 18 / 360p)
            val progFormat = bundle.formats.find { it.isProgressive }

            // 2. Si no hay HLS y la calidad elegida es específicamente 360p: usar el stream progresivo oficial
            if (quality == "360p" && progFormat != null) {
                desiredVideoHeight = 360
                val httpSourceFactory = getHttpDataSourceFactory(progFormat.videoUrl)
                val mediaSource = ProgressiveMediaSource.Factory(httpSourceFactory)
                    .createMediaSource(MediaItem.fromUri(Uri.parse(progFormat.videoUrl)))

                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .clearVideoSizeConstraints()
                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                    .build()

                Log.i(TAG, "Cargando video progresivo directo 360p (itag 18): ${progFormat.videoUrl}")
                p.setMediaSource(mediaSource, startPosMs)
                p.prepare()
                p.play()
                return
            }

            // 3. Seleccionar el formato objetivo según la resolución deseada
            val selectedFormat = when (quality) {
                "1080p" -> bundle.formats.find { it.qualityLabel == "1080p" }
                    ?: bundle.formats.find { it.qualityLabel == "720p" }
                    ?: progFormat
                "720p" -> bundle.formats.find { it.qualityLabel == "720p" }
                    ?: bundle.formats.find { it.qualityLabel == "1080p" }
                    ?: progFormat
                "480p" -> bundle.formats.find { it.qualityLabel == "480p" }
                    ?: bundle.formats.find { it.qualityLabel == "720p" }
                    ?: progFormat
                "240p" -> bundle.formats.find { it.qualityLabel == "240p" }
                    ?: bundle.formats.find { it.qualityLabel == "360p" }
                    ?: progFormat
                "Auto" -> {
                    bundle.formats.find { it.qualityLabel == "720p" }
                        ?: bundle.formats.find { it.qualityLabel == "1080p" }
                        ?: progFormat
                        ?: bundle.formats.firstOrNull()
                }
                else -> bundle.formats.find { it.qualityLabel == quality } ?: progFormat ?: bundle.formats.firstOrNull()
            }

            if (selectedFormat == null) {
                Log.w(TAG, "No se encontró formato disponible en el bundle para calidad=$quality")
                return
            }

            Log.i(TAG, "Aplicando resolución: solicitada=$quality, formato=${selectedFormat.qualityLabel}, isProg=${selectedFormat.isProgressive}")

            if (selectedFormat.isProgressive) {
                // Caso Progresivo Directo (360p muxed)
                desiredVideoHeight = 360
                val httpSourceFactory = getHttpDataSourceFactory(selectedFormat.videoUrl)
                val mediaSource = ProgressiveMediaSource.Factory(httpSourceFactory)
                    .createMediaSource(MediaItem.fromUri(Uri.parse(selectedFormat.videoUrl)))

                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .clearVideoSizeConstraints()
                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                    .build()

                p.setMediaSource(mediaSource, startPosMs)
                p.prepare()
                p.play()
                return
            }

            // Caso Adaptativo HD (1080p, 720p, 480p, 240p)
            val targetHeight = when (selectedFormat.qualityLabel) {
                "1080p" -> 1080
                "720p" -> 720
                "480p" -> 480
                "240p" -> 240
                else -> Regex("(\\d+)").find(selectedFormat.qualityLabel)?.groupValues?.get(1)?.toIntOrNull() ?: 720
            }
            desiredVideoHeight = targetHeight

            // ChunkedHttpDataSource para video adaptativo para evitar el bloqueo HTTP 403 de GoogleVideo
            val vFactory = getHttpDataSourceFactory(selectedFormat.videoUrl)
            val chunkedVFactory = ChunkedHttpDataSource.Factory(vFactory, 1024 * 1024L)
            val videoSource = ProgressiveMediaSource.Factory(chunkedVFactory)
                .createMediaSource(MediaItem.fromUri(Uri.parse(selectedFormat.videoUrl)))

            // Audio: itag 18 contiene audio estéreo AAC completo sin límites de descarga ni 403
            val audioSource = if (progFormat != null) {
                val progFactory = getHttpDataSourceFactory(progFormat.videoUrl)
                ProgressiveMediaSource.Factory(progFactory)
                    .createMediaSource(MediaItem.fromUri(Uri.parse(progFormat.videoUrl)))
            } else if (!selectedFormat.audioUrl.isNullOrEmpty()) {
                val aFactory = getHttpDataSourceFactory(selectedFormat.audioUrl)
                val chunkedAFactory = ChunkedHttpDataSource.Factory(aFactory, 512 * 1024L)
                ProgressiveMediaSource.Factory(chunkedAFactory)
                    .createMediaSource(MediaItem.fromUri(Uri.parse(selectedFormat.audioUrl)))
            } else {
                null
            }

            // Guiar al selector de pistas de ExoPlayer a la resolución objetivo
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setMinVideoSize(targetHeight - 20, targetHeight - 20)
                .build()

            val mediaSource = if (audioSource != null) {
                MergingMediaSource(videoSource, audioSource)
            } else {
                videoSource
            }

            p.setMediaSource(mediaSource, startPosMs)
            p.prepare()
            p.play()
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
        currentVideoResolution = userSelectedResolution

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
                        val httpFactory = getHttpDataSourceFactory(streamUrl)
                        val source = ProgressiveMediaSource.Factory(httpFactory)
                            .createMediaSource(MediaItem.fromUri(Uri.parse(streamUrl)))
                        player?.setMediaSource(source)
                        player?.prepare()
                        player?.play()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error preparando ExoPlayer", e)
                    }
                } else {
                    Log.e(TAG, "No se pudo obtener stream para: ${track.title}")
                    appContext?.let {
                        Toast.makeText(it, "No se pudo cargar este video", Toast.LENGTH_SHORT).show()
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
