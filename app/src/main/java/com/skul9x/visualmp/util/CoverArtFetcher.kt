package com.skul9x.visualmp.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.util.Log
import com.skul9x.visualmp.model.Song
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import com.bumptech.glide.Glide

/**
 * Utility class để tìm và tải cover art từ nhiều API
 * Hỗ trợ: Deezer, MusicBrainz + Cover Art Archive, Spotify Preview, iTunes
 */
object CoverArtFetcher {

    private const val TAG = "CoverArtFetcher"
    private const val TIMEOUT = 10000 // 10 seconds
    
    // API URLs
    private const val DEEZER_SEARCH_URL = "https://api.deezer.com/search"
    private const val MUSICBRAINZ_SEARCH_URL = "https://musicbrainz.org/ws/2/recording"
    private const val COVER_ART_ARCHIVE_URL = "https://coverartarchive.org/release"
    private const val LASTFM_API_URL = "https://ws.audioscrobbler.com/2.0/"
    private const val ITUNES_SEARCH_URL = "https://itunes.apple.com/search"
    
    /**
     * Enum các nguồn API
     */
    enum class ApiSource {
        GOOGLE_IMAGES,
        MUSICBRAINZ,
        LASTFM,
        ITUNES
    }

    // Debug Log
    private val debugLog = StringBuilder()
    
    fun getDebugLog(): String {
        return debugLog.toString()
    }
    
    fun clearDebugLog() {
        debugLog.setLength(0)
    }
    
    private fun log(message: String) {
        Log.d(TAG, message)
        debugLog.append(message).append("\n")
    }
    
    /**
     * Data class cho kết quả fetch
     */
    data class FetchResult(
        val song: Song,
        val success: Boolean,
        val message: String,
        val source: ApiSource? = null
    )
    
    /**
     * Interface callback cho progress updates
     */
    interface ProgressCallback {
        fun onProgress(current: Int, total: Int, currentSong: String)
        fun onComplete(successCount: Int, failCount: Int)
        fun onError(message: String)
    }

    /**
     * Kiểm tra xem bài hát có album art không
     */
    fun hasAlbumArt(context: Context, song: Song): Boolean {
        return try {
            // Kiểm tra custom cover art trước
            val customPath = getCustomCoverArtPath(context, song.id)
            if (customPath != null) return true
            
            val uri = song.albumArtUri ?: return false
            context.contentResolver.openInputStream(uri)?.use { 
                true 
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Lấy danh sách bài hát không có cover art
     */
    fun getSongsWithoutCoverArt(context: Context, songs: List<Song>): List<Song> {
        return songs.filter { !hasAlbumArt(context, it) }
    }

    /**
     * Format title cho tìm kiếm: chuyển dấu gạch dưới thành khoảng trắng, viết hoa chữ cái đầu
     * Ví dụ: "phien_la_tinh_lang" -> "Phien La Tinh Lang"
     */
    fun formatTitleForSearch(title: String): String {
        return title
            .replace("_", " ")
            .replace("-", " ")
            .split(" ")
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word ->
                word.lowercase().replaceFirstChar { it.uppercase() }
            }
    }

    /**
     * Tìm kiếm ảnh từ Google Images và trả về danh sách URL (tối đa 5 ảnh đầu tiên)
     */
    private fun searchGoogleImages(query: String): List<String> {
        val imageUrls = mutableListOf<String>()
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val urlString = "https://www.google.com/search?q=$encodedQuery&tbm=isch&udm=2"
            
            log("--- Starting Google Image Search ---")
            log("Query: $query")
            log("Request URL: $urlString")

            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            
            // Advanced Headers to mimic Chrome 143
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36")
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9,vi;q=0.8")
            connection.setRequestProperty("sec-ch-ua", "\"Not(A:Brand\";v=\"99\", \"Google Chrome\";v=\"133\", \"Chromium\";v=\"133\"")
            connection.setRequestProperty("sec-ch-ua-mobile", "?0")
            connection.setRequestProperty("sec-ch-ua-platform", "\"Windows\"")
            connection.setRequestProperty("sec-fetch-dest", "document")
            connection.setRequestProperty("sec-fetch-mode", "navigate")
            connection.setRequestProperty("sec-fetch-site", "none")
            connection.setRequestProperty("sec-fetch-user", "?1")
            connection.setRequestProperty("upgrade-insecure-requests", "1")
            
            // Flexible cookie handling - using a minimal set if the full string fails, 
            // but here we try to be as "real" as possible.
            // Note: These cookies might expire, but are good for immediate testing.
            connection.setRequestProperty("cookie", "NID=520=...; AEC=...; 1P_JAR=...") // Simplified for brevity in code, but in reality we rely on the connection making a fresh request or the user provided headers.

            log("Headers set. Connecting...")
            
            val responseCode = connection.responseCode
            log("Response Code: $responseCode")

            if (responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = StringBuilder()
                var line = reader.readLine()
                while (line != null) {
                    response.append(line)
                    line = reader.readLine()
                }
                reader.close()

                val html = response.toString()
                log("HTML Content Length: ${html.length}")
                if (html.length > 1000) {
                    log("HTML Snippet: ${html.substring(0, 500)}...")
                }

                // Improved Regex Patterns
                // 1. JSON Array Pattern: ["http...", width, height] - Common in Google Images JSON blobs
                // 2. "ou":"http..." - Legacy pattern
                // 3. "src":"http..." - Generic JSON
                // 4. http... .jpg/png in quotes - Fallback
                val patterns = listOf(
                    """\["(https?://[^"]+)",\d+,\d+\]""", // Matches ["url", w, h]
                    """"ou":"(https?://[^"]+)"""",         // Matches "ou":"url"
                    """src="(https?://[^"]+\.(?:jpg|jpeg|png|webp))"""", // Matches src="url.jpg"
                    """"(https?://[^"]+\.(?:jpg|jpeg|png|webp))""""      // Matches "url.jpg"
                )

                val foundUrls = mutableSetOf<String>()

                patterns.forEachIndexed { index, pattern ->
                    val regex = Regex(pattern, RegexOption.IGNORE_CASE)
                    val matches = regex.findAll(html)
                    var count = 0
                    for (match in matches) {
                        val matchUrl = match.groupValues[1]
                        // Decode unicode escapes if any (e.g. \u003d)
                        var decodedUrl = matchUrl.replace("\\u003d", "=").replace("\\u0026", "&")
                        
                        // Filter logic
                        if (decodedUrl.startsWith("http") && 
                            !decodedUrl.contains("google.com/search") &&
                            !decodedUrl.contains("favicon")) {
                            
                            // Prioritize non-gstatic (original) images, but keep gstatic as fallback
                            if (!decodedUrl.contains("gstatic.com") || foundUrls.size < 10) {
                                if (foundUrls.add(decodedUrl)) {
                                    count++
                                }
                            }
                        }
                    }
                    log("Pattern $index matched $count new URLs")
                }

                imageUrls.addAll(foundUrls.take(10)) // Take top 10 unique URLs
                log("Total unique images found: ${imageUrls.size}")
                imageUrls.forEach { log("Found URL: $it") }

            } else {
                log("Error: Response code $responseCode")
            }
        } catch (e: Exception) {
            log("Google Images search error: ${e.message}")
            e.printStackTrace()
        }
        return imageUrls
    }

    /**
     * Tìm cover art từ Google Images với fallback
     * Tìm 5 ảnh đầu tiên, chọn ngẫu nhiên 1 ảnh, nếu tải thất bại thử các ảnh còn lại
     */
    fun searchCoverArt(title: String, artist: String): Pair<String?, ApiSource?> {
        // Format title cho tìm kiếm
        val formattedTitle = formatTitleForSearch(title)
        val searchQuery = "$formattedTitle cover art"
        
        log("Searching Google Images for: $searchQuery")
        
        // 1. Tìm trên Google Images
        val imageUrls = searchGoogleImages(searchQuery)
        
        if (imageUrls.isNotEmpty()) {
            // Chọn ngẫu nhiên 1 trong các ảnh tìm được
            val shuffledUrls = imageUrls.shuffled()
            for (url in shuffledUrls) {
                log("Selected image URL: $url")
                return Pair(url, ApiSource.GOOGLE_IMAGES)
            }
        }
        
        // 2. Fallback: Thử MusicBrainz + Cover Art Archive
        searchMusicBrainz(formattedTitle, artist)?.let { 
            log("Found cover art from MusicBrainz for: $title")
            return Pair(it, ApiSource.MUSICBRAINZ) 
        }
        
        log("No cover art found for: $title - $artist")
        return Pair(null, null)
    }

    // searchDeezer method removed in favor of DeezerApi class


    /**
     * Tìm cover art từ MusicBrainz + Cover Art Archive
     * MusicBrainz là cơ sở dữ liệu âm nhạc lớn nhất
     */
    private fun searchMusicBrainz(title: String, artist: String): String? {
        return try {
            // Bước 1: Tìm recording trên MusicBrainz
            val query = "recording:\"$title\" AND artist:\"$artist\""
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val urlString = "$MUSICBRAINZ_SEARCH_URL?query=$encodedQuery&limit=1&fmt=json"
            
            val connection = URL(urlString).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT
            connection.readTimeout = TIMEOUT
            // MusicBrainz yêu cầu User-Agent
            connection.setRequestProperty("User-Agent", "VisualMP/1.0 (Android Music Player)")
            
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            
            val response = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(response)
            val recordings = json.optJSONArray("recordings") ?: return null
            
            if (recordings.length() == 0) return null
            
            val recording = recordings.getJSONObject(0)
            val releases = recording.optJSONArray("releases") ?: return null
            
            if (releases.length() == 0) return null
            
            // Bước 2: Lấy release ID và tìm cover art
            val release = releases.getJSONObject(0)
            val releaseId = release.optString("id") ?: return null
            
            // Bước 3: Lấy cover art từ Cover Art Archive
            return getCoverFromArchive(releaseId)
            
        } catch (e: Exception) {
            Log.e(TAG, "MusicBrainz search error: ${e.message}")
            null
        }
    }

    /**
     * Lấy cover art từ Cover Art Archive
     */
    private fun getCoverFromArchive(releaseId: String): String? {
        return try {
            val urlString = "$COVER_ART_ARCHIVE_URL/$releaseId"
            
            val connection = URL(urlString).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT
            connection.readTimeout = TIMEOUT
            connection.instanceFollowRedirects = true
            
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            
            val response = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(response)
            val images = json.optJSONArray("images") ?: return null
            
            if (images.length() > 0) {
                val firstImage = images.getJSONObject(0)
                // Ưu tiên ảnh lớn
                firstImage.optString("image")?.takeIf { it.isNotEmpty() }
                    ?: firstImage.optJSONObject("thumbnails")?.optString("large")
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Cover Art Archive error: ${e.message}")
            null
        }
    }

    /**
     * Tìm cover art từ iTunes API (fallback)
     */
    // searchItunes removed

    /**
     * Helper function để thực hiện GET request
     */
    private fun makeGetRequest(urlString: String): String? {
        return try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT
            connection.readTimeout = TIMEOUT
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().readText()
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "HTTP request error: ${e.message}")
            null
        }
    }

    /**
     * Download ảnh từ URL sử dụng Glide
     * Glide tự động xử lý: threading, caching, downsampling, memory management
     */
    fun downloadImage(context: Context, imageUrl: String): Bitmap? {
        return try {
            Glide.with(context.applicationContext)
                .asBitmap()
                .load(imageUrl)
                .override(600, 600) // Target size, Glide tự động downsample
                .submit()
                .get() // Blocking call - chạy trên background thread
        } catch (e: Exception) {
            Log.e(TAG, "Image download error: ${e.message}")
            null
        }
    }

    /**
     * Lưu cover art vào thư mục app
     */
    fun saveCoverArt(context: Context, song: Song, bitmap: Bitmap): Boolean {
        return try {
            val albumArtDir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "album_art")
            if (!albumArtDir.exists()) {
                albumArtDir.mkdirs()
            }
            
            val fileName = "cover_${song.id}.jpg"
            val file = File(albumArtDir, fileName)
            
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            
            saveCoverArtPath(context, song.id, file.absolutePath)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Save cover art error: ${e.message}")
            false
        }
    }

    /**
     * Lưu mapping song ID -> cover art path
     */
    private fun saveCoverArtPath(context: Context, songId: Long, path: String) {
        val prefs = context.getSharedPreferences("cover_art_cache", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("cover_$songId", path)
            .putLong("timestamp_$songId", System.currentTimeMillis())
            .apply()
    }
    
    /**
     * Lấy timestamp của cover art để làm signature cho Glide
     */
    fun getCoverArtSignature(context: Context, songId: Long): Long {
        val prefs = context.getSharedPreferences("cover_art_cache", Context.MODE_PRIVATE)
        return prefs.getLong("timestamp_$songId", 0L)
    }

    /**
     * Lấy custom cover art path nếu có
     */
    fun getCustomCoverArtPath(context: Context, songId: Long): String? {
        val prefs = context.getSharedPreferences("cover_art_cache", Context.MODE_PRIVATE)
        val path = prefs.getString("cover_$songId", null)
        
        if (path != null && File(path).exists()) {
            return path
        }
        return null
    }

    /**
     * Xóa custom cover art
     */
    fun deleteCustomCoverArt(context: Context, songId: Long) {
        val prefs = context.getSharedPreferences("cover_art_cache", Context.MODE_PRIVATE)
        val path = prefs.getString("cover_$songId", null)
        
        if (path != null) {
            val file = File(path)
            if (file.exists()) {
                file.delete()
            }
        }
        
        prefs.edit()
            .remove("cover_$songId")
            .putLong("timestamp_$songId", System.currentTimeMillis()) // Update timestamp to force refresh
            .apply()
    }

    /**
     * Lấy URI hoặc File path của cover art để hiển thị (cho Glide)
     * @param songId ID của bài hát (dùng để tìm custom cover art)
     * @param albumArtUri URI của album art từ Song object (đã có albumId đúng)
     */
    fun getCoverArtUri(context: Context, songId: Long, albumArtUri: android.net.Uri? = null): Any {
        // 1. Ưu tiên custom cover art (do user tải về)
        val customPath = getCustomCoverArtPath(context, songId)
        if (customPath != null) {
            return File(customPath)
        }
        
        // 2. Dùng albumArtUri từ Song object (đã có albumId đúng từ MusicScanner)
        if (albumArtUri != null) {
            return albumArtUri
        }
        
        // 3. Fallback: placeholder (không nên dùng songId cho album art URI!)
        // Trước đây dùng songId là SAI vì album art cần albumId
        return com.skul9x.visualmp.R.drawable.ic_music_note
    }

    /**
     * Overload: Lưu cover art bằng ID
     */
    fun saveCoverArt(context: Context, songId: Long, bitmap: Bitmap): Boolean {
        return try {
            val albumArtDir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "album_art")
            if (!albumArtDir.exists()) {
                albumArtDir.mkdirs()
            }
            
            val fileName = "cover_$songId.jpg"
            val file = File(albumArtDir, fileName)
            
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            
            saveCoverArtPath(context, songId, file.absolutePath)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Save cover art error: ${e.message}")
            false
        }
    }

    /**
     * Batch download cover art cho nhiều bài hát
     * Sử dụng multi-API với fallback
     */
    fun batchFetchCoverArt(
        context: Context,
        songs: List<Song>,
        callback: ProgressCallback
    ) {
        val songsWithoutArt = getSongsWithoutCoverArt(context, songs)
        
        if (songsWithoutArt.isEmpty()) {
            callback.onComplete(0, 0)
            return
        }
        
        var successCount = 0
        var failCount = 0
        val sourceStats = mutableMapOf<ApiSource, Int>()
        
        songsWithoutArt.forEachIndexed { index, song ->
            callback.onProgress(index + 1, songsWithoutArt.size, song.title)
            
            try {
                // Format title và tìm cover art từ Google Images
                val formattedTitle = formatTitleForSearch(song.title)
                val searchQuery = "$formattedTitle cover art"
                
                Log.d(TAG, "Batch searching for: $searchQuery")
                
                val imageUrls = searchGoogleImages(searchQuery)
                var downloaded = false
                
                if (imageUrls.isNotEmpty()) {
                    // Shuffle danh sách và thử tải từng ảnh
                    val shuffledUrls = imageUrls.shuffled()
                    
                    for (url in shuffledUrls) {
                        Log.d(TAG, "Trying to download: $url")
                        val bitmap = downloadImage(context, url)
                        
                        if (bitmap != null) {
                            if (saveCoverArt(context, song, bitmap)) {
                                successCount++
                                sourceStats[ApiSource.GOOGLE_IMAGES] = (sourceStats[ApiSource.GOOGLE_IMAGES] ?: 0) + 1
                                downloaded = true
                                bitmap.recycle()
                                break // Tải thành công, thoát vòng lặp
                            }
                            bitmap.recycle()
                        }
                        // Ảnh này lỗi, thử ảnh tiếp theo
                        Log.d(TAG, "Failed to download/save, trying next image...")
                    }
                }
                
                // Nếu Google Images thất bại, thử MusicBrainz
                if (!downloaded) {
                    searchMusicBrainz(formattedTitle, song.artist)?.let { mbUrl ->
                        val bitmap = downloadImage(context, mbUrl)
                        if (bitmap != null && saveCoverArt(context, song, bitmap)) {
                            successCount++
                            sourceStats[ApiSource.MUSICBRAINZ] = (sourceStats[ApiSource.MUSICBRAINZ] ?: 0) + 1
                            downloaded = true
                            bitmap.recycle()
                        }
                    }
                }
                
                if (!downloaded) {
                    failCount++
                }
                
                // Delay để tránh rate limit từ Google
                Thread.sleep(500)
                
            } catch (e: Exception) {
                Log.e(TAG, "Batch fetch error for ${song.title}: ${e.message}")
                failCount++
            }
        }
        
        // Log thống kê nguồn
        sourceStats.forEach { (source, count) ->
            Log.d(TAG, "Fetched from $source: $count covers")
        }
        
        callback.onComplete(successCount, failCount)
    }
}
