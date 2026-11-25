package com.muzvidx.app.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.muzvidx.app.R
import com.muzvidx.app.ui.activities.LocalPlayerActivity
import com.muzvidx.app.ui.activities.PlayerActivity

class MusicPlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "muzvidx_playback_channel"
        const val NOTIFICATION_ID = 2001

        const val ACTION_PLAY = "com.muzvidx.app.ACTION_PLAY"
        const val ACTION_PAUSE = "com.muzvidx.app.ACTION_PAUSE"
        const val ACTION_NEXT = "com.muzvidx.app.ACTION_NEXT"
        const val ACTION_PREV = "com.muzvidx.app.ACTION_PREV"
        const val ACTION_STOP = "com.muzvidx.app.ACTION_STOP"

        fun start(context: Context, title: String, artist: String) {
            val intent = Intent(context, MusicPlaybackService::class.java).apply {
                putExtra("title", title)
                putExtra("artist", artist)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MusicPlaybackService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_PLAY -> PlaybackManager.resume()
            ACTION_PAUSE -> PlaybackManager.pause()
            ACTION_NEXT -> PlaybackManager.playNext()
            ACTION_PREV -> PlaybackManager.playPrev()
            ACTION_STOP -> {
                PlaybackManager.pause()
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val track = PlaybackManager.getCurrentTrack()
        val title = track?.title ?: intent?.getStringExtra("title") ?: "mUzVidX"
        val artist = track?.artist ?: intent?.getStringExtra("artist") ?: "Reproduciendo"

        startForeground(NOTIFICATION_ID, buildNotification(title, artist, PlaybackManager.isPlaying))
        return START_STICKY
    }

    private fun buildNotification(title: String, artist: String, isPlaying: Boolean): Notification {
        val current = PlaybackManager.getCurrentTrack()
        val url = current?.audioUrl?.ifEmpty { current.videoUrl } ?: ""
        val isLocal = url.startsWith("/") || url.startsWith("file://")
        val targetActivity = if (isLocal) LocalPlayerActivity::class.java else PlayerActivity::class.java
        val openIntent = Intent(this, targetActivity).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPI = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseAction = if (isPlaying) {
            val pauseIntent = Intent(this, MusicPlaybackService::class.java).apply { action = ACTION_PAUSE }
            val pausePI = PendingIntent.getService(this, 1, pauseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            NotificationCompat.Action.Builder(R.drawable.ic_pause, "Pausar", pausePI).build()
        } else {
            val playIntent = Intent(this, MusicPlaybackService::class.java).apply { action = ACTION_PLAY }
            val playPI = PendingIntent.getService(this, 1, playIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            NotificationCompat.Action.Builder(R.drawable.ic_play, "Reproducir", playPI).build()
        }

        val nextIntent = Intent(this, MusicPlaybackService::class.java).apply { action = ACTION_NEXT }
        val nextPI = PendingIntent.getService(this, 2, nextIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val nextAction = NotificationCompat.Action.Builder(R.drawable.ic_skip_next, "Siguiente", nextPI).build()

        val prevIntent = Intent(this, MusicPlaybackService::class.java).apply { action = ACTION_PREV }
        val prevPI = PendingIntent.getService(this, 3, prevIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val prevAction = NotificationCompat.Action.Builder(R.drawable.ic_skip_prev, "Anterior", prevPI).build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(openPI)
            .setOngoing(isPlaying)
            .addAction(prevAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0, 1, 2))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Reproductor mUzVidX",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controles de reproducción en segundo plano"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
    }
}
