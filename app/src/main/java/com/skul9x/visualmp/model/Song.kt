package com.skul9x.visualmp.model

import android.net.Uri

/**
 * Data class đại diện cho một bài hát
 */
data class Song(
    val id: Long,
    var title: String,
    var artist: String,
    var album: String,
    val duration: Long,
    val path: String,
    val albumArtUri: Uri?
) {
    /**
     * Format thời lượng bài hát thành chuỗi MM:SS
     */
    fun getFormattedDuration(): String {
        val minutes = (duration / 1000) / 60
        val seconds = (duration / 1000) % 60
        return String.format("%02d:%02d", minutes, seconds)
    }
}
