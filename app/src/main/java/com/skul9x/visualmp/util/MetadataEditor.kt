package com.skul9x.visualmp.util

import android.content.Context
import com.skul9x.visualmp.model.Song
import org.json.JSONObject

/**
 * Utility class để chỉnh sửa metadata của file nhạc
 * Lưu metadata custom vào SharedPreferences vì Android 10+ không cho phép sửa MediaStore
 */
object MetadataEditor {

    private const val PREFS_NAME = "custom_metadata"
    private const val KEY_PREFIX = "song_"

    /**
     * Data class chứa thông tin metadata để cập nhật
     */
    data class SongMetadata(
        val title: String,
        val artist: String,
        val album: String
    ) {
        fun toJson(): String {
            return JSONObject().apply {
                put("title", title)
                put("artist", artist)
                put("album", album)
            }.toString()
        }

        companion object {
            fun fromJson(json: String): SongMetadata? {
                return try {
                    val obj = JSONObject(json)
                    SongMetadata(
                        title = obj.optString("title", ""),
                        artist = obj.optString("artist", ""),
                        album = obj.optString("album", "")
                    )
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    /**
     * Lưu metadata custom vào SharedPreferences
     * Giải pháp này hoạt động trên tất cả các phiên bản Android
     */
    fun updateMetadata(
        context: Context,
        song: Song,
        newMetadata: SongMetadata
    ): Result<Boolean> {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val key = "$KEY_PREFIX${song.id}"
            
            prefs.edit()
                .putString(key, newMetadata.toJson())
                .apply()
            
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Lấy metadata custom đã lưu (nếu có)
     */
    fun getCustomMetadata(context: Context, songId: Long): SongMetadata? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = "$KEY_PREFIX$songId"
        val json = prefs.getString(key, null) ?: return null
        return SongMetadata.fromJson(json)
    }

    /**
     * Kiểm tra bài hát có metadata custom không
     */
    fun hasCustomMetadata(context: Context, songId: Long): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.contains("$KEY_PREFIX$songId")
    }

    /**
     * Xóa metadata custom
     */
    fun clearCustomMetadata(context: Context, songId: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove("$KEY_PREFIX$songId").apply()
    }

    /**
     * Tạo Song mới với metadata đã cập nhật (cho UI update)
     */
    fun createUpdatedSong(song: Song, newMetadata: SongMetadata): Song {
        return song.copy(
            title = newMetadata.title,
            artist = newMetadata.artist,
            album = newMetadata.album
        )
    }

    /**
     * Áp dụng custom metadata nếu có cho một bài hát
     */
    fun applyCustomMetadata(context: Context, song: Song): Song {
        val customMetadata = getCustomMetadata(context, song.id) ?: return song
        return song.copy(
            title = customMetadata.title.ifEmpty { song.title },
            artist = customMetadata.artist.ifEmpty { song.artist },
            album = customMetadata.album.ifEmpty { song.album }
        )
    }

    /**
     * Áp dụng custom metadata cho danh sách bài hát
     */
    fun applyCustomMetadataToList(context: Context, songs: List<Song>): List<Song> {
        return songs.map { applyCustomMetadata(context, it) }
    }
}

