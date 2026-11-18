package com.muzvidx.app.extractor

import com.google.gson.Gson
import com.muzvidx.app.model.SearchMode
import com.muzvidx.app.model.SearchResult
import com.muzvidx.app.model.Source
import com.muzvidx.app.model.ResultType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

class SoundCloudService {

    companion object {
        private const val BASE_URL = "https://api.soundcloud.com"
        // Configurable mediante variable de entorno o inyección dinámica
        private val CLIENT_ID = System.getenv("SOUNDCLOUD_CLIENT_ID") ?: ""
    }

    data class ScTrack(
        val id: Long,
        val title: String,
        val user: ScUser?,
        val streamable: Boolean,
        val stream_url: String?,
        val duration: Long,
        val artwork_url: String?,
        val permalink_url: String?,
        val genre: String?,
        val kind: String?,
        val user_id: Long?
    )

    data class ScUser(
        val username: String,
        val full_name: String?,
        val permalink_url: String?,
        val followers_count: Int?,
        val verified: Boolean?,
        val id: Long?
    )

    data class ScArtistList(val collection: List<ScUser> = emptyList(), val total: Int = 0)

    suspend fun searchTracks(query: String, mode: SearchMode, limit: Int = 30): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<SearchResult>()
            try {
                if (mode == SearchMode.LIBRARY) {
                    // Buscar artistas verificados / canales oficiales
                    results += searchArtists(query, limit)
                    // También buscar tracks de artistas oficiales
                    results += searchTracksFromOfficialArtists(query, limit)
                } else {
                    // Modo libre: búsqueda general de tracks
                    results += searchTracksGeneral(query, limit)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            results
        }

    private fun searchTracksGeneral(query: String, limit: Int): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$BASE_URL/search/tracks?q=$encoded&limit=$limit&client_id=$CLIENT_ID"
        val json = fetchJson(url) ?: return emptyList()
        return parseTracks(json)
    }

    private fun searchArtists(query: String, limit: Int): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$BASE_URL/search/artists?q=$encoded&limit=$limit&client_id=$CLIENT_ID"
        val json = fetchJson(url) ?: return emptyList()
        return parseArtists(json)
    }

    private fun searchTracksFromOfficialArtists(query: String, limit: Int): List<SearchResult> {
        // Buscar tracks y filtrar por artistas verificados (canales oficiales)
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$BASE_URL/search/tracks?q=$encoded&limit=$limit&client_id=$CLIENT_ID"
        val json = fetchJson(url) ?: return emptyList()
        return parseTracks(json).filter { it.isOfficial }
    }

    private fun parseTracks(json: String): List<SearchResult> {
        val gson = Gson()
        // SoundCloud /search/tracks devuelve un array directo
        val data = gson.fromJson(json, Array<ScTrack>::class.java) ?: return emptyList()
        return data.mapNotNull { track ->
            if (!track.streamable) return@mapNotNull null
            SearchResult(
                id = track.id.toString(),
                title = track.title,
                artist = track.user?.full_name ?: track.user?.username ?: "Desconocido",
                source = Source.SOUNDCLOUD,
                type = if (track.artwork_url != null) ResultType.TRACK else ResultType.VIDEO,
                durationSec = track.duration / 1000,
                thumbUrl = track.artwork_url?.replace("large", "t300x300") ?: "",
                audioUrl = track.stream_url ?: "",
                videoUrl = "",
                isOfficial = track.user?.verified ?: false,
                officialChannel = track.user?.username ?: "",
                isCommunity = !(track.user?.verified ?: false)
            )
        }
    }

    private fun parseArtists(json: String): List<SearchResult> {
        val gson = Gson()
        // SoundCloud /search/artists devuelve {"collection":[...], "total":...}
        val wrapper = gson.fromJson(json, ScArtistList::class.java)
        val artistArray = wrapper?.collection ?: return emptyList()
        return artistArray.mapNotNull { user ->
            SearchResult(
                id = "artist_${user.id}",
                title = user.full_name ?: user.username,
                artist = user.username,
                source = Source.SOUNDCLOUD,
                type = ResultType.TRACK,
                durationSec = 0,
                thumbUrl = "",
                audioUrl = "",
                videoUrl = "",
                isOfficial = user.verified ?: false,
                officialChannel = user.username,
                isCommunity = !(user.verified ?: false)
            )
        }
    }

    private fun fetchJson(url: String): String? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            if (code == 200) {
                conn.inputStream.bufferedReader().readText()
            } else if (code == 429) {
                // Rate limit - esperar y reintentar
                Thread.sleep(3000)
                conn.disconnect()
                val conn2 = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "application/json")
                }
                if (conn2.responseCode == 200) {
                    conn2.inputStream.bufferedReader().readText()
                } else {
                    null
                }
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}