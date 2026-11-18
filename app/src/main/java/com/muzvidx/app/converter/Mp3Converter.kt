package com.muzvidx.app.converter

import android.content.Context
import android.media.MediaScannerConnection
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object Mp3Converter {

    private const val TAG = "Mp3Converter"

    /**
     * Convierte de forma robusta y rápida un archivo de audio (M4A/AAC) a MP3 usando FFmpeg
     * con multi-hilo (-threads 4) y paso de argumentos directos para evitar problemas de escape.
     */
    suspend fun convertToMp3(
        inputPath: String,
        outputPath: String,
        bitrate: String = "192k",
        context: Context? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val inFile = File(inputPath)
        if (!inFile.exists() || inFile.length() < 1000) {
            Log.e(TAG, "El archivo de entrada no existe o está corrupto: $inputPath")
            return@withContext false
        }

        val outFile = File(outputPath)
        outFile.parentFile?.mkdirs()

        val args = arrayOf(
            "-i", inputPath,
            "-vn",
            "-acodec", "libmp3lame",
            "-b:a", bitrate,
            "-threads", "4",
            "-y", outputPath
        )
        Log.i(TAG, "Iniciando conversión FFmpeg a MP3: $inputPath -> $outputPath")
        try {
            val session = FFmpegKit.executeWithArguments(args)
            val returnCode = session?.returnCode
            val ok = (returnCode?.isValueSuccess == true) && outFile.exists() && outFile.length() > 50000

            if (ok) {
                Log.i(TAG, "MP3 convertido exitosamente: $outputPath (${outFile.length()} bytes)")
                context?.let { ctx ->
                    try {
                        MediaScannerConnection.scanFile(ctx, arrayOf(outputPath), arrayOf("audio/mpeg"), null)
                    } catch (e: Exception) {
                        Log.w(TAG, "MediaScanner error: ${e.message}")
                    }
                }
                true
            } else {
                val err = session?.allLogsAsString ?: "Error desconocido en FFmpeg"
                Log.e(TAG, "Fallo al convertir a MP3 (returnCode=${returnCode?.value}): $err")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Excepción durante la conversión a MP3: ${e.message}", e)
            false
        }
    }

    /**
     * Une una pista de video MP4 y una pista de audio AAC en un único contenedor MP4
     * usando muxing directo sin recodificar (-c copy). Se completa en 1-2 segundos.
     */
    suspend fun muxVideoAndAudio(
        videoPath: String,
        audioPath: String,
        outputPath: String,
        context: Context? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val vFile = File(videoPath)
        val aFile = File(audioPath)
        val outFile = File(outputPath)
        outFile.parentFile?.mkdirs()

        if (!vFile.exists() || vFile.length() < 1000) {
            Log.e(TAG, "Pista de video ausente o corrupta: $videoPath")
            return@withContext false
        }
        if (!aFile.exists() || aFile.length() < 1000) {
            Log.e(TAG, "Pista de audio ausente o corrupta: $audioPath")
            return@withContext false
        }

        val args = arrayOf(
            "-i", videoPath,
            "-i", audioPath,
            "-c", "copy",
            "-threads", "4",
            "-y", outputPath
        )
        Log.i(TAG, "Iniciando muxing de video + audio: ${vFile.name} + ${aFile.name} -> ${outFile.name}")
        try {
            val session = FFmpegKit.executeWithArguments(args)
            val returnCode = session?.returnCode
            val ok = (returnCode?.isValueSuccess == true) && outFile.exists() && outFile.length() > 50000

            if (ok) {
                Log.i(TAG, "Video y Audio combinados con éxito: $outputPath (${outFile.length()} bytes)")
                // Eliminar archivos temporales de pistas separadas
                try {
                    vFile.delete()
                    aFile.delete()
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudieron borrar temporales: ${e.message}")
                }
                context?.let { ctx ->
                    try {
                        MediaScannerConnection.scanFile(ctx, arrayOf(outputPath), arrayOf("video/mp4"), null)
                    } catch (e: Exception) {
                        Log.w(TAG, "MediaScanner error: ${e.message}")
                    }
                }
                true
            } else {
                val err = session?.allLogsAsString ?: "Error en muxing"
                Log.e(TAG, "Fallo al combinar video y audio (returnCode=${returnCode?.value}): $err")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Excepción durante muxing de video y audio: ${e.message}", e)
            false
        }
    }
}
