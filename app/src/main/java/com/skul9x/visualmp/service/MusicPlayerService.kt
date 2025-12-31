package com.skul9x.visualmp.service

import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.skul9x.visualmp.model.Song
import com.skul9x.visualmp.util.CoverArtFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

@OptIn(UnstableApi::class)
class MusicPlayerService : MediaSessionService() {
    
    // Binder for backward compatibility with MainActivity binding
    private val binder = MusicBinder()
    
    inner class MusicBinder : Binder() {
        fun getService(): MusicPlayerService = this@MusicPlayerService
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        // Check if this is a media browser connection or a direct service bind
        val superBinder = super.onBind(intent)
        // Return our custom binder for direct binds from MainActivity
        return if (intent?.action == null) binder else superBinder
    }
    
    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    
    private var playlist: List<Song> = emptyList()
    private var currentIndex: Int = 0
    private var repeatMode = RepeatMode.OFF
    
    // Coroutine Scope tied to Service Lifecycle
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // Callback để cập nhật UI (for backward compatibility during migration)
    var onSongChanged: ((Song?) -> Unit)? = null
    var onPlaybackStateChanged: ((Boolean) -> Unit)? = null
    var onProgressChanged: ((Int, Int) -> Unit)? = null
    
    enum class RepeatMode {
        OFF, ONE, ALL
    }
    
    // Custom session commands
    companion object {
        const val COMMAND_TOGGLE_SHUFFLE = "TOGGLE_SHUFFLE"
        const val COMMAND_TOGGLE_REPEAT = "TOGGLE_REPEAT"
        const val COMMAND_GET_SHUFFLE_STATE = "GET_SHUFFLE_STATE"
        const val COMMAND_GET_REPEAT_MODE = "GET_REPEAT_MODE"
    }
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize ExoPlayer
        exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true // handleAudioFocus = true
            )
            .setHandleAudioBecomingNoisy(true) // Pause when headphones unplugged
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        
        // Setup player listener
        exoPlayer?.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Find the corresponding Song from our playlist
                val songId = mediaItem?.mediaId?.toLongOrNull()
                val song = playlist.find { it.id == songId }
                currentIndex = playlist.indexOfFirst { it.id == songId }.coerceAtLeast(0)
                onSongChanged?.invoke(song)
            }
            
            override fun onPlaybackStateChanged(playbackState: Int) {
                val isPlaying = exoPlayer?.isPlaying == true
                onPlaybackStateChanged?.invoke(isPlaying)
            }
            
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onPlaybackStateChanged?.invoke(isPlaying)
            }
            
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                onPlaybackStateChanged?.invoke(exoPlayer?.isPlaying == true)
            }
        })
        
        // Create MediaSession with custom callback
        mediaSession = MediaSession.Builder(this, exoPlayer!!)
            .setCallback(MediaSessionCallback())
            .build()
    }
    
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }
    
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }
    
    override fun onDestroy() {
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        exoPlayer = null
        super.onDestroy()
    }
    
    // Custom callback for handling custom commands
    private inner class MediaSessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(COMMAND_TOGGLE_SHUFFLE, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_TOGGLE_REPEAT, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_GET_SHUFFLE_STATE, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_GET_REPEAT_MODE, Bundle.EMPTY))
                .build()
            
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }
        
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            return when (customCommand.customAction) {
                COMMAND_TOGGLE_SHUFFLE -> {
                    toggleShuffle()
                    val result = Bundle().apply { putBoolean("shuffleEnabled", isShuffleEnabled()) }
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, result))
                }
                COMMAND_TOGGLE_REPEAT -> {
                    toggleRepeat()
                    val result = Bundle().apply { putInt("repeatMode", repeatMode.ordinal) }
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, result))
                }
                COMMAND_GET_SHUFFLE_STATE -> {
                    val result = Bundle().apply { putBoolean("shuffleEnabled", isShuffleEnabled()) }
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, result))
                }
                COMMAND_GET_REPEAT_MODE -> {
                    val result = Bundle().apply { putInt("repeatMode", repeatMode.ordinal) }
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, result))
                }
                else -> Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
        }
    }
    
    // ============ Public API (keeping similar interface for easier migration) ============
    
    fun setPlaylist(songs: List<Song>, startIndex: Int = 0, autoPlay: Boolean = true) {
        val previousSong = getCurrentSong()
        playlist = songs
        
        if (songs.isEmpty()) {
            currentIndex = 0
            exoPlayer?.clearMediaItems()
            return
        }
        
        // If there's a current song playing, try to find it in the new playlist
        if (previousSong != null) {
            val newIndex = songs.indexOfFirst { it.id == previousSong.id }
            if (newIndex != -1) {
                currentIndex = newIndex
                // Don't change playback - just update internal index
                // But we should update the media items for consistency
                updateMediaItems(songs, newIndex, autoPlay = false)
                return
            }
        }
        
        // No current song or current song not in new playlist
        currentIndex = startIndex.coerceIn(0, songs.size - 1)
        updateMediaItems(songs, currentIndex, autoPlay)
    }
    
    private fun updateMediaItems(songs: List<Song>, startIndex: Int, autoPlay: Boolean) {
        val mediaItems = songs.map { song ->
            // Get correct artwork URI (Custom or Original) - Media3 handles loading efficiently
            val customPath = CoverArtFetcher.getCustomCoverArtPath(this, song.id)
            val artworkUri = if (customPath != null) {
                android.net.Uri.fromFile(java.io.File(customPath))
            } else {
                song.albumArtUri
            }
            
            MediaItem.Builder()
                .setMediaId(song.id.toString())
                .setUri(song.path)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(song.title)
                        .setArtist(song.artist)
                        .setAlbumTitle(song.album)
                        .setArtworkUri(artworkUri) // Media3 handles artwork loading & memory management
                        .build()
                )
                .build()
        }
        
        exoPlayer?.apply {
            setMediaItems(mediaItems, startIndex, 0)
            prepare()
            playWhenReady = autoPlay
        }
    }
    
    fun playSong(song: Song, autoPlay: Boolean = true) {
        val index = playlist.indexOfFirst { it.id == song.id }
        if (index != -1) {
            currentIndex = index
            exoPlayer?.apply {
                seekTo(index, 0)
                playWhenReady = autoPlay
            }
            onSongChanged?.invoke(song)
        }
    }
    
    fun playNext() {
        if (playlist.isEmpty()) return
        // ExoPlayer handles shuffle order automatically when shuffleModeEnabled = true
        exoPlayer?.seekToNextMediaItem()
    }
    
    fun playPrevious() {
        if (playlist.isEmpty()) return
        
        // If played more than 3 seconds, restart current song
        if ((exoPlayer?.currentPosition ?: 0) > 3000) {
            seekTo(0)
            return
        }
        
        // ExoPlayer handles shuffle order automatically when shuffleModeEnabled = true
        exoPlayer?.seekToPreviousMediaItem()
    }
    
    fun pause() {
        exoPlayer?.pause()
    }
    
    fun resume() {
        exoPlayer?.play()
    }
    
    fun togglePlayPause() {
        if (isPlaying()) {
            pause()
        } else {
            resume()
        }
    }
    
    fun seekTo(position: Int) {
        exoPlayer?.seekTo(position.toLong())
    }
    
    fun isPlaying(): Boolean = exoPlayer?.isPlaying == true
    
    fun getCurrentPosition(): Int = exoPlayer?.currentPosition?.toInt() ?: 0
    
    fun getDuration(): Int = exoPlayer?.duration?.toInt() ?: 0
    
    fun getCurrentSong(): Song? {
        val mediaItem = exoPlayer?.currentMediaItem ?: return null
        val songId = mediaItem.mediaId.toLongOrNull() ?: return null
        return playlist.find { it.id == songId }
    }
    
    fun toggleShuffle() {
        val newShuffleState = !(exoPlayer?.shuffleModeEnabled ?: false)
        exoPlayer?.shuffleModeEnabled = newShuffleState
    }
    
    fun isShuffleEnabled(): Boolean = exoPlayer?.shuffleModeEnabled ?: false
    
    fun toggleRepeat() {
        repeatMode = when (repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        // Update ExoPlayer repeat mode
        exoPlayer?.repeatMode = when (repeatMode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
        }
    }
    
    fun getRepeatMode(): RepeatMode = repeatMode
    
    // For binding from Activity (backward compatibility)
    fun getPlayer(): ExoPlayer? = exoPlayer
    fun getSession(): MediaSession? = mediaSession
}
