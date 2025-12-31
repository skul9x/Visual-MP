package com.skul9x.visualmp.util

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.skul9x.visualmp.model.Song

/**
 * Utility class để quét file âm thanh từ thiết bị
 */
object MusicScanner {
    
    private val ALBUM_ART_URI = Uri.parse("content://media/external/audio/albumart")
    
    /**
     * Data class đại diện cho một thư mục chứa nhạc
     */
    data class MusicFolder(
        val path: String,
        val name: String,
        val songCount: Int
    )
    
    /**
     * Quét tất cả file âm thanh từ MediaStore
     * Nếu có folders được chọn, chỉ lấy nhạc từ các folder đó
     */
    fun scanMusic(context: Context): List<Song> {
        val selectedFolders = SettingsManager.getScanFolders(context)
        return if (selectedFolders.isEmpty()) {
            scanAllMusic(context)
        } else {
            scanMusicFromFolders(context, selectedFolders)
        }
    }
    
    /**
     * Quét tất cả nhạc (không filter folder)
     */
    private fun scanAllMusic(context: Context): List<Song> {
        return queryMusic(context, null, null)
    }
    
    /**
     * Quét nhạc từ các folder cụ thể
     */
    private fun scanMusicFromFolders(context: Context, folders: Set<String>): List<Song> {
        val allSongs = mutableListOf<Song>()
        
        for (folder in folders) {
            val selection = "${MediaStore.Audio.Media.DATA} LIKE ?"
            val selectionArgs = arrayOf("$folder%")
            allSongs.addAll(queryMusic(context, selection, selectionArgs))
        }
        
        // Remove duplicates và sort
        return allSongs.distinctBy { it.id }.sortedBy { it.title.lowercase() }
    }
    
    /**
     * Query nhạc từ MediaStore với filter tùy chọn
     */
    private fun queryMusic(
        context: Context, 
        additionalSelection: String?, 
        selectionArgs: Array<String>?
    ): List<Song> {
        val songs = mutableListOf<Song>()
        
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA
        )
        
        var selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        if (additionalSelection != null) {
            selection += " AND $additionalSelection"
        }
        
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"
        
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn) ?: "Unknown"
                val artist = cursor.getString(artistColumn) ?: "Unknown Artist"
                val album = cursor.getString(albumColumn) ?: "Unknown Album"
                val albumId = cursor.getLong(albumIdColumn)
                val duration = cursor.getLong(durationColumn)
                val path = cursor.getString(dataColumn) ?: ""
                
                // Tạo URI cho album art
                val albumArtUri = ContentUris.withAppendedId(ALBUM_ART_URI, albumId)
                
                // Bỏ qua các file quá ngắn (dưới 10 giây) - thường là ringtone/notification
                if (duration > 10000) {
                    songs.add(
                        Song(
                            id = id,
                            title = title,
                            artist = artist,
                            album = album,
                            duration = duration,
                            path = path,
                            albumArtUri = albumArtUri
                        )
                    )
                }
            }
        }
        
        return songs
    }
    
    /**
     * Lấy danh sách tất cả các thư mục chứa nhạc
     */
    fun getMusicFolders(context: Context): List<MusicFolder> {
        val folderMap = mutableMapOf<String, MutableList<String>>()
        
        val projection = arrayOf(
            MediaStore.Audio.Media.DATA
        )
        
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            null
        )?.use { cursor ->
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            
            while (cursor.moveToNext()) {
                val path = cursor.getString(dataColumn) ?: continue
                val folderPath = path.substringBeforeLast("/")
                
                if (folderMap.containsKey(folderPath)) {
                    folderMap[folderPath]?.add(path)
                } else {
                    folderMap[folderPath] = mutableListOf(path)
                }
            }
        }
        
        return folderMap.map { (path, songs) ->
            MusicFolder(
                path = path,
                name = path.substringAfterLast("/"),
                songCount = songs.size
            )
        }.sortedBy { it.name.lowercase() }
    }
}
