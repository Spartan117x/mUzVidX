package com.muzvidx.app.ui.activities

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.muzvidx.app.R
import com.muzvidx.app.databinding.ActivityMainBinding
import com.muzvidx.app.player.PlaybackManager
import com.muzvidx.app.ui.fragments.ConverterFragment
import com.muzvidx.app.ui.fragments.DownloadsFragment
import com.muzvidx.app.ui.fragments.SearchFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        fun openPlayer(
            context: android.content.Context,
            queue: List<com.muzvidx.app.model.SearchResult> = emptyList(),
            startIndex: Int = 0
        ) {
            if (queue.isNotEmpty()) {
                PlaybackManager.setQueue(queue, startIndex)
            }
            context.startActivity(Intent(context, PlayerActivity::class.java))
        }
    }

    private lateinit var binding: ActivityMainBinding
    private val searchFragment = SearchFragment()
    private val downloadsFragment = DownloadsFragment()
    private val converterFragment = ConverterFragment()
    private val mainScope = CoroutineScope(Dispatchers.Main)
    private var miniPollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Permiso de notificaciones en Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        PlaybackManager.init(this)
        com.muzvidx.app.downloader.DownloadQueueManager.recoverStuckDownloads(this)

        // Bottom navigation
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_search -> showFragment(searchFragment)
                R.id.nav_downloads -> showFragment(downloadsFragment)
                R.id.nav_converter -> showFragment(converterFragment)
            }
            true
        }

        // Mini player buttons con animaciones táctiles y foco para Smart TV
        applyTouchFeedback(binding.miniPlayer.btnPlay)
        applyTouchFeedback(binding.miniPlayer.btnNext)
        applyTouchFeedback(binding.miniPlayer.btnPrev)

        listOf(
            binding.miniPlayer.btnPlay,
            binding.miniPlayer.btnPrev,
            binding.miniPlayer.btnNext,
            binding.miniPlayer.root
        ).forEach { v ->
            v.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    val s = if (view == binding.miniPlayer.root) 1.02f else 1.15f
                    view.animate().scaleX(s).scaleY(s).setDuration(120).start()
                } else {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                }
            }
        }

        binding.miniPlayer.btnPlay.setOnClickListener {
            if (PlaybackManager.player?.isPlaying == true) {
                PlaybackManager.pause()
            } else {
                PlaybackManager.resume()
            }
            updateMiniPlayer()
        }
        binding.miniPlayer.btnNext.setOnClickListener {
            PlaybackManager.playNext()
            updateMiniPlayer()
        }
        binding.miniPlayer.btnPrev.setOnClickListener {
            PlaybackManager.playPrev()
            updateMiniPlayer()
        }
        binding.miniPlayer.root.setOnClickListener {
            openPlayer(this)
        }

        if (savedInstanceState == null) {
            binding.bottomNav.selectedItemId = R.id.nav_search
        }

        handleIntent(intent)

        miniPollJob = mainScope.launch {
            while (true) {
                updateMiniPlayer()
                delay(500)
            }
        }
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

    private fun showFragment(f: androidx.fragment.app.Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, f)
            .commit()
    }

    private fun updateMiniPlayer() {
        val queue = PlaybackManager.queue
        if (queue.isEmpty()) {
            binding.miniPlayer.root.visibility = View.GONE
            return
        }
        binding.miniPlayer.root.visibility = View.VISIBLE
        val track = queue.getOrNull(PlaybackManager.currentIndex) ?: return
        binding.miniPlayer.tvTitle.text = track.title
        binding.miniPlayer.tvArtist.text = track.artist

        if (track.thumbUrl.isNotEmpty()) {
            Glide.with(this)
                .load(track.thumbUrl)
                .circleCrop()
                .placeholder(R.drawable.ic_music_note)
                .into(binding.miniPlayer.miniThumb)
        } else {
            binding.miniPlayer.miniThumb.setImageResource(R.drawable.ic_music_note)
        }

        val isPlaying = PlaybackManager.isPlaying
        binding.miniPlayer.btnPlay.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )

        val pos = PlaybackManager.getCurrentPositionMs().toInt()
        val dur = PlaybackManager.getDurationMs().toInt()
        if (dur > 0) {
            binding.miniPlayer.miniProgress.max = dur
            binding.miniPlayer.miniProgress.progress = pos
        }
    }

    fun refreshMiniPlayer() {
        updateMiniPlayer()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val testAction = intent.getStringExtra("test_action") ?: return
        android.util.Log.i("mUzVidX_Test", "Executing test_action: $testAction")

        mainScope.launch(Dispatchers.IO) {
            when (testAction) {
                "search" -> {
                    val q = intent.getStringExtra("query") ?: "Coldplay"
                    val lib = com.muzvidx.app.extractor.YouTubeService().search(q, com.muzvidx.app.model.SearchMode.LIBRARY)
                    val free = com.muzvidx.app.extractor.YouTubeService().search(q, com.muzvidx.app.model.SearchMode.FREE)
                    android.util.Log.i("mUzVidX_Test", "SEARCH_SUCCESS: libCount=${lib.size}, freeCount=${free.size}")
                }
                "download" -> {
                    val vid = intent.getStringExtra("video_id") ?: "e-S1k9-soT4"
                    val title = intent.getStringExtra("title") ?: "Cold Heart"
                    val typeStr = intent.getStringExtra("type") ?: "AUDIO"
                    val type = if (typeStr == "VIDEO") com.muzvidx.app.model.DownloadType.VIDEO else com.muzvidx.app.model.DownloadType.AUDIO
                    val record = com.muzvidx.app.downloader.AudioVideoDownloader.download(this@MainActivity, vid, title, type) {}
                    android.util.Log.i("mUzVidX_Test", "DOWNLOAD_RESULT: status=${record.status}, path=${record.originalPath}")
                    if (record.status == com.muzvidx.app.model.DownloadStatus.READY) {
                        val db = com.muzvidx.app.db.DownloadDatabase.getInstance(this@MainActivity)
                        val entity = com.muzvidx.app.db.DownloadEntity(
                            id = record.id,
                            title = record.title,
                            artist = "Test Artist",
                            type = typeStr,
                            sourceUrl = vid,
                            originalPath = record.originalPath,
                            mp3Path = null,
                            status = "READY",
                            progress = 100,
                            downloadedAt = System.currentTimeMillis()
                        )
                        db.downloadDao().insert(entity)
                        android.util.Log.i("mUzVidX_Test", "DB_INSERTED: id=${record.id}, totalCount=${db.downloadDao().getAll().size}")
                    }
                }
                "convert" -> {
                    val db = com.muzvidx.app.db.DownloadDatabase.getInstance(this@MainActivity)
                    val audioItems = db.downloadDao().getByType("AUDIO")
                    if (audioItems.isNotEmpty()) {
                        val item = audioItems.first()
                        val input = item.originalPath
                        val output = java.io.File(input).parentFile?.let {
                            java.io.File(it, java.io.File(input).nameWithoutExtension + ".mp3")
                        }?.absolutePath ?: (input + ".mp3")
                        android.util.Log.i("mUzVidX_Test", "Converting: $input -> $output")
                        val ok = com.muzvidx.app.converter.Mp3Converter.convertToMp3(
                            input,
                            output,
                            context = this@MainActivity,
                            title = item.title,
                            artist = item.artist
                        )
                        android.util.Log.i("mUzVidX_Test", "CONVERT_RESULT: ok=$ok, exists=${java.io.File(output).exists()}, bytes=${java.io.File(output).length()}")
                    } else {
                        android.util.Log.w("mUzVidX_Test", "CONVERT: No audio items in DB to convert")
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        miniPollJob?.cancel()
    }
}
