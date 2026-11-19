package com.muzvidx.app.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * DataSource transparente que divide peticiones de streams de YouTube / GoogleVideo
 * en chunks acotados (ej. 1MB para video, 512KB para audio).
 *
 * GoogleVideo responde con HTTP 403 Forbidden si la petición tiene rango no acotado ("bytes=0-")
 * o si se solicitan más de 1MB en streams de audio (itag 140/139).
 * Con chunks acotados (<= 1MB), GoogleVideo responde fluidamente con HTTP 206 Partial Content.
 */
class ChunkedHttpDataSource(
    private val upstreamFactory: DataSource.Factory,
    private val defaultChunkSize: Long = 1024 * 1024L // 1MB para video
) : DataSource {

    companion object {
        private const val TAG = "ChunkedHttpDataSource"
        private const val AUDIO_CHUNK_SIZE = 512 * 1024L // 512KB seguro para audio
    }

    private var upstream: DataSource? = null
    private var baseDataSpec: DataSpec? = null
    private var currentPosition: Long = 0L
    private var totalBytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var chunkRemaining: Long = 0L
    private var isOpened = false
    private val listeners = mutableListOf<TransferListener>()

    override fun addTransferListener(transferListener: TransferListener) {
        listeners.add(transferListener)
        upstream?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        baseDataSpec = dataSpec
        currentPosition = dataSpec.position
        isOpened = true

        val itagMatch = Regex("itag=(\\d+)").find(dataSpec.uri.toString())?.groupValues?.get(1) ?: "?"
        
        // Determinar longitud total disponible
        var knownTotalLength: Long = dataSpec.length
        if (knownTotalLength == C.LENGTH_UNSET.toLong()) {
            val clenStr = dataSpec.uri.getQueryParameter("clen")
            if (!clenStr.isNullOrEmpty()) {
                val clen = clenStr.toLongOrNull()
                if (clen != null && clen > currentPosition) {
                    knownTotalLength = clen - currentPosition
                }
            }
        }
        totalBytesRemaining = knownTotalLength

        Log.i(TAG, "OPEN: itag=$itagMatch, pos=${dataSpec.position}, specLen=${dataSpec.length}, totalRemaining=$totalBytesRemaining")

        openChunk(currentPosition)

        return totalBytesRemaining
    }

    private fun openChunk(position: Long) {
        upstream?.close()
        val spec = baseDataSpec ?: return
        val uriStr = spec.uri.toString()
        val itagMatch = Regex("itag=(\\d+)").find(uriStr)?.groupValues?.get(1) ?: "?"
        val isAudio = uriStr.contains("mime=audio") || uriStr.contains("mime%3Daudio") ||
                itagMatch in listOf("140", "139", "251", "250", "249")

        val effectiveChunkSize = if (isAudio) AUDIO_CHUNK_SIZE else defaultChunkSize

        val currentUpstream = upstreamFactory.createDataSource().also {
            listeners.forEach { l -> it.addTransferListener(l) }
        }
        upstream = currentUpstream

        val lengthToRequest = if (totalBytesRemaining != C.LENGTH_UNSET.toLong()) {
            Math.min(effectiveChunkSize, totalBytesRemaining)
        } else {
            effectiveChunkSize
        }

        val chunkSpec = spec.buildUpon()
            .setPosition(position)
            .setLength(lengthToRequest)
            .build()

        try {
            val openedLength = currentUpstream.open(chunkSpec)
            chunkRemaining = if (openedLength != C.LENGTH_UNSET.toLong()) openedLength else lengthToRequest

            // Si aún no conocíamos la longitud total, intentar extraerla de la cabecera Content-Range
            if (totalBytesRemaining == C.LENGTH_UNSET.toLong()) {
                val cr = currentUpstream.responseHeaders["Content-Range"]?.firstOrNull()
                if (!cr.isNullOrEmpty()) {
                    val fullSize = Regex("/(\\d+)").find(cr)?.groupValues?.get(1)?.toLongOrNull()
                    if (fullSize != null && fullSize > position) {
                        totalBytesRemaining = fullSize - position
                    }
                }
            }

            Log.i(TAG, "OPEN_CHUNK_OK: itag=$itagMatch, pos=$position, reqLen=$lengthToRequest, chunkRemaining=$chunkRemaining, totalRemaining=$totalBytesRemaining")
        } catch (e: androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
            if (e.responseCode == 416) {
                Log.i(TAG, "EOF alcanzado (HTTP 416): itag=$itagMatch, pos=$position")
                chunkRemaining = 0L
                totalBytesRemaining = 0L
                return
            }
            Log.e(TAG, "OPEN_CHUNK_ERR: itag=$itagMatch, pos=$position, reqLen=$lengthToRequest, code=${e.responseCode}", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "OPEN_CHUNK_ERR: itag=$itagMatch, pos=$position, reqLen=$lengthToRequest, error=${e.message}", e)
            throw e
        }
    }

    override fun getResponseHeaders(): Map<String, List<String>> =
        upstream?.responseHeaders ?: emptyMap()

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (totalBytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        if (chunkRemaining <= 0L) {
            openChunk(currentPosition)
            if (chunkRemaining <= 0L) {
                return C.RESULT_END_OF_INPUT
            }
        }

        val bytesToRead = if (totalBytesRemaining != C.LENGTH_UNSET.toLong()) {
            Math.min(length.toLong(), Math.min(chunkRemaining, totalBytesRemaining)).toInt()
        } else {
            Math.min(length.toLong(), chunkRemaining).toInt()
        }

        val bytesRead = upstream?.read(buffer, offset, bytesToRead) ?: C.RESULT_END_OF_INPUT
        if (bytesRead == C.RESULT_END_OF_INPUT) {
            // El servidor terminó este chunk, avanzar al siguiente bloque
            openChunk(currentPosition)
            if (chunkRemaining <= 0L) {
                return C.RESULT_END_OF_INPUT
            }
            val retryBytesToRead = if (totalBytesRemaining != C.LENGTH_UNSET.toLong()) {
                Math.min(length.toLong(), Math.min(chunkRemaining, totalBytesRemaining)).toInt()
            } else {
                Math.min(length.toLong(), chunkRemaining).toInt()
            }
            val retryRead = upstream?.read(buffer, offset, retryBytesToRead) ?: C.RESULT_END_OF_INPUT
            if (retryRead > 0) {
                currentPosition += retryRead
                chunkRemaining -= retryRead
                if (totalBytesRemaining != C.LENGTH_UNSET.toLong()) {
                    totalBytesRemaining -= retryRead
                }
            }
            return retryRead
        }

        currentPosition += bytesRead
        chunkRemaining -= bytesRead
        if (totalBytesRemaining != C.LENGTH_UNSET.toLong()) {
            totalBytesRemaining -= bytesRead
        }

        return bytesRead
    }

    override fun getUri(): Uri? = upstream?.uri ?: baseDataSpec?.uri

    override fun close() {
        isOpened = false
        try {
            upstream?.close()
        } finally {
            upstream = null
            baseDataSpec = null
        }
    }

    class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val defaultChunkSize: Long = 1024 * 1024L
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return ChunkedHttpDataSource(upstreamFactory, defaultChunkSize)
        }
    }
}
