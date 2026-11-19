package com.muzvidx.app.ui.fragments

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
import com.muzvidx.app.databinding.FragmentConverterBinding
import com.muzvidx.app.db.DownloadDatabase
import com.muzvidx.app.db.DownloadEntity
import com.muzvidx.app.model.ResultType
import com.muzvidx.app.model.SearchResult
import com.muzvidx.app.model.Source
import com.muzvidx.app.ui.activities.MainActivity
import com.muzvidx.app.ui.adapters.AudioFilesAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ConverterFragment : Fragment() {

    private var _binding: FragmentConverterBinding? = null
    private val binding get() = _binding!!

    private var selectedEntity: DownloadEntity? = null
    private lateinit var adapter: AudioFilesAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConverterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = AudioFilesAdapter(
            onPlay = { entity -> playEntity(entity) },
            onConvert = { entity -> convert(entity) }
        )
        binding.rvAudioFiles.layoutManager = LinearLayoutManager(requireContext())
        binding.rvAudioFiles.adapter = adapter

        binding.btnConvert.setOnClickListener {
            selectedEntity?.let { convert(it) }
        }

        loadAudioFiles()
    }

    private fun loadAudioFiles() {
        val ctx = context ?: return
        val db = DownloadDatabase.getInstance(ctx)
        lifecycleScope.launch {
            val audio: List<DownloadEntity> = withContext(Dispatchers.IO) {
                db.downloadDao().getByType("AUDIO").filter { it.status == "READY" }
            }
            _binding?.let { b ->
                adapter.updateList(audio)
                if (audio.isEmpty()) {
                    b.tvSelectedFile.text = "Descarga un audio primero"
                }
            }
        }
    }

    private fun convert(entity: DownloadEntity) {
        val b = _binding ?: return
        val ctx = context ?: return
        selectedEntity = entity
        b.tvSelectedFile.text = entity.title
        b.tvSelectedFile.setTextColor(ctx.getColor(R.color.accent))

        b.btnConvert.isEnabled = false
        b.progressConvert.visibility = View.VISIBLE
        b.tvConvertStatus.visibility = View.VISIBLE
        b.tvConvertStatus.text = "Convirtiendo a MP3…"

        val appCtx = ctx.applicationContext
        lifecycleScope.launch {
            try {
                val inputPath = entity.originalPath
                val outputPath = File(inputPath).parentFile?.let {
                    File(it, File(inputPath).nameWithoutExtension + ".mp3")
                }?.absolutePath ?: (inputPath + ".mp3")

                val success = Mp3Converter.convertToMp3(
                    inputPath = inputPath,
                    outputPath = outputPath,
                    context = appCtx,
                    title = entity.title,
                    artist = entity.artist
                )

                _binding?.let { currentB ->
                    currentB.progressConvert.visibility = View.GONE
                    currentB.btnConvert.isEnabled = true

                    if (success) {
                        currentB.tvConvertStatus.text = "Convertido ✓ → ${File(outputPath).name}"
                        currentB.tvConvertStatus.setTextColor(appCtx.getColor(R.color.accent))
                        val db = DownloadDatabase.getInstance(appCtx)
                        withContext(Dispatchers.IO) {
                            db.downloadDao().update(entity.copy(mp3Path = outputPath))
                        }
                        Toast.makeText(appCtx, "MP3 listo ✓", Toast.LENGTH_SHORT).show()
                        loadAudioFiles()
                    } else {
                        currentB.tvConvertStatus.text = "Error al convertir"
                        currentB.tvConvertStatus.setTextColor(appCtx.getColor(R.color.status_error))
                    }
                }
            } catch (e: Exception) {
                _binding?.let { currentB ->
                    currentB.progressConvert.visibility = View.GONE
                    currentB.btnConvert.isEnabled = true
                    currentB.tvConvertStatus.text = "Error: ${e.message}"
                    currentB.tvConvertStatus.setTextColor(appCtx.getColor(R.color.status_error))
                }
            }
        }
    }

    private fun playEntity(entity: DownloadEntity) {
        val fileUrl = entity.mp3Path ?: entity.originalPath
        val source = if (entity.id.startsWith("yt")) Source.YOUTUBE else Source.SOUNDCLOUD
        MainActivity.openPlayer(
            requireContext(),
            listOf(
                SearchResult(
                    id = entity.id,
                    title = entity.title,
                    artist = entity.artist,
                    source = source,
                    type = ResultType.TRACK,
                    durationSec = 0,
                    thumbUrl = "",
                    audioUrl = fileUrl,
                    videoUrl = fileUrl,
                    isOfficial = false
                )
            ),
            0
        )
    }

    override fun onResume() {
        super.onResume()
        if (context != null) {
            loadAudioFiles()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}