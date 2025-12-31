package com.skul9x.visualmp.util

import android.content.Context
import android.util.Log
import com.skul9x.visualmp.model.Song
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Utility class để tìm và tự động cập nhật metadata cho bài hát
 * Kết hợp đoán từ tên file và tìm qua iTunes API
 */
object MetadataFetcher {
    private const val TAG = "MetadataFetcher"
    private const val ITUNES_SEARCH_URL = "https://itunes.apple.com/search"

    interface ProgressCallback {
        fun onProgress(current: Int, total: Int, currentSong: String)
        fun onComplete(successCount: Int, failCount: Int)
        fun onError(message: String)
    }

    /**
     * Tìm và cập nhật metadata cho danh sách bài hát
     */
    fun batchFetchMetadata(
        context: Context,
        songs: List<Song>,
        callback: ProgressCallback
    ) {
        val songsToFix = songs.filter { isMetadataIncomplete(it) }

        if (songsToFix.isEmpty()) {
            callback.onComplete(0, 0)
            return
        }

        var successCount = 0
        var failCount = 0

        songsToFix.forEachIndexed { index, song ->
            callback.onProgress(index + 1, songsToFix.size, song.title)

            try {
                // 1. Tìm kiếm dựa trên Tên bài hát + Nghệ sĩ (nếu có)
                val searchTerm = if (!song.artist.contains("Unknown", ignoreCase = true)) {
                    "${song.title} ${song.artist}"
                } else {
                    song.title
                }
                
                // 2. Tìm online
                val onlineResult = searchMetadataOnline(searchTerm)
                
                // 3. Xử lý kết quả
                var finalMetadata: MetadataEditor.SongMetadata? = null
                
                if (onlineResult != null) {
                    finalMetadata = MetadataEditor.SongMetadata(
                        // QUAN TRỌNG: Giữ nguyên Title gốc của bài hát theo yêu cầu
                        title = song.title, 
                        // Chỉ cập nhật Artist/Album nếu nó đang thiếu hoặc là "Unknown"
                        artist = if (isUnknown(song.artist)) onlineResult.artist else song.artist,
                        album = if (isUnknown(song.album)) onlineResult.album else song.album
                    )
                } else {
                    // Fallback: Đoán từ tên file (chỉ cho Artist/Album)
                    val filename = File(song.path).nameWithoutExtension
                    val guess = guessFromFilename(filename)
                    if (guess != null) {
                        finalMetadata = MetadataEditor.SongMetadata(
                            title = song.title,
                            artist = if (isUnknown(song.artist)) guess.artist else song.artist,
                            album = if (isUnknown(song.album)) guess.album else song.album
                        )
                    }
                }

                if (finalMetadata != null) {
                    // Update vào SharedPreferences
                    val result = MetadataEditor.updateMetadata(context, song, finalMetadata)
                    if (result.isSuccess) {
                        successCount++
                    } else {
                        failCount++
                    }
                } else {
                    failCount++
                }
                
                Thread.sleep(300) // Tránh rate limit

            } catch (e: Exception) {
                Log.e(TAG, "Error fetching metadata for ${song.title}: ${e.message}")
                failCount++
            }
        }
        callback.onComplete(successCount, failCount)
    }

    private fun isUnknown(text: String): Boolean {
        val unknownKeywords = listOf("<unknown>", "unknown", "unknown artist", "unknown album")
        return text.isEmpty() || unknownKeywords.any { text.lowercase().contains(it) }
    }

    private fun isMetadataIncomplete(song: Song): Boolean {
        return isUnknown(song.artist) || isUnknown(song.album)
    }

    /**
     * Đoán metadata từ tên file định dạng "Artist - Title"
     * Trả về metadata, nhưng lưu ý khi dùng phải giữ lại Title gốc nếu muốn
     */
    private fun guessFromFilename(filename: String): MetadataEditor.SongMetadata? {
        if (filename.contains(" - ")) {
            val parts = filename.split(" - ")
            if (parts.size >= 2) {
                val artist = parts[0].trim()
                val title = parts[1].trim() // Cái này có thể không dùng nhưng vẫn parse
                return MetadataEditor.SongMetadata(title, artist, "Unknown Album")
            }
        }
        return null
    }

    /**
     * Tìm metadata từ iTunes API
     */
    private fun searchMetadataOnline(query: String): MetadataEditor.SongMetadata? {
        return try {
            val encodedTerm = URLEncoder.encode(query, "UTF-8")
            val urlString = "$ITUNES_SEARCH_URL?term=$encodedTerm&media=music&limit=1"

            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                val results = json.optJSONArray("results")
                
                if (results != null && results.length() > 0) {
                    val item = results.getJSONObject(0)
                    MetadataEditor.SongMetadata(
                        title = item.optString("trackName"),
                        artist = item.optString("artistName"),
                        album = item.optString("collectionName")
                    )
                } else null
            } else null
        } catch (e: Exception) {
            null
        }
    }
}
