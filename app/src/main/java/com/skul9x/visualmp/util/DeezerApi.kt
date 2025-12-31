package com.skul9x.visualmp.util

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

object DeezerApi {

    data class DeezerResult(
        val title: String,
        val artist: String,
        val album: String,
        val coverUrl: String
    )

    fun searchTrack(query: String): DeezerResult? {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://api.deezer.com/search?q=$encodedQuery")
            
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val inputStream = connection.inputStream
                val response = inputStream.bufferedReader().use { it.readText() }
                
                val json = JSONObject(response)
                val dataArray = json.optJSONArray("data")
                
                if (dataArray != null && dataArray.length() > 0) {
                    val track = dataArray.getJSONObject(0)
                    val artistObj = track.optJSONObject("artist")
                    val albumObj = track.optJSONObject("album")
                    
                    val title = track.optString("title", "")
                    val artist = artistObj?.optString("name", "") ?: ""
                    val album = albumObj?.optString("title", "") ?: ""
                    val coverUrl = albumObj?.optString("cover_xl", "") ?: albumObj?.optString("cover_medium", "") ?: ""
                    
                    return DeezerResult(title, artist, album, coverUrl)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("DeezerApi", "Error searching Deezer: ${e.message}")
        }
        return null
    }
}
