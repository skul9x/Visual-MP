package com.skul9x.visualmp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.graphics.BitmapFactory
import com.skul9x.visualmp.MainActivity
import com.skul9x.visualmp.R
import com.skul9x.visualmp.model.Song
import com.bumptech.glide.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MusicPlayerService : Service() {
    
    private val binder = MusicBinder()
    private var mediaPlayer: MediaPlayer? = null
    private var currentSong: Song? = null
    private var playlist: List<Song> = emptyList()
    private var currentIndex: Int = 0
    private var isShuffleOn = false
    private var repeatMode = RepeatMode.OFF
    private var isPreparing = false
    
    // Shuffle: Danh sách indices đã xáo trộn và vị trí hiện tại trong danh sách đó
    private var shuffledIndices: List<Int> = emptyList()
    private var shuffleIndex: Int = 0
    
    // Audio Focus
    private lateinit var audioManager: AudioManager

    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var wasPlayingBeforeFocusLoss = false
    
    // Coroutine Scope tied to Service Lifecycle
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // MediaSession
    private lateinit var mediaSession: MediaSessionCompat
    
    // Callback để cập nhật UI
    var onSongChanged: ((Song?) -> Unit)? = null
    var onPlaybackStateChanged: ((Boolean) -> Unit)? = null
    var onProgressChanged: ((Int, Int) -> Unit)? = null
    
    enum class RepeatMode {
        OFF, ONE, ALL
    }
    
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                // Đã lấy lại được audio focus
                hasAudioFocus = true
                if (wasPlayingBeforeFocusLoss) {
                    resume()
                    wasPlayingBeforeFocusLoss = false
                }
                mediaPlayer?.setVolume(1.0f, 1.0f)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Mất audio focus hoàn toàn (app khác chiếm)
                hasAudioFocus = false
                wasPlayingBeforeFocusLoss = isPlaying()
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Mất tạm thời (có cuộc gọi, notification...)
                hasAudioFocus = false
                wasPlayingBeforeFocusLoss = isPlaying()
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Có thể giảm volume thay vì pause
                mediaPlayer?.setVolume(0.2f, 0.2f)
            }
        }
    }
    
    inner class MusicBinder : Binder() {
        fun getService(): MusicPlayerService = this@MusicPlayerService
    }
    
    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        setupMediaSession()
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "MusicPlayerService")
        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                resume()
            }

            override fun onPause() {
                pause()
            }

            override fun onSkipToNext() {
                playNext()
            }

            override fun onSkipToPrevious() {
                playPrevious()
            }
            
            override fun onSeekTo(pos: Long) {
                seekTo(pos.toInt())
            }
        })
        mediaSession.isActive = true 
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> resume()
            ACTION_PAUSE -> pause()
            ACTION_NEXT -> playNext()
            ACTION_PREVIOUS -> playPrevious()
            ACTION_STOP -> {
                abandonAudioFocus()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    
    private fun requestAudioFocus(): Boolean {
        val result: Int
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            
            result = audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            result = audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasAudioFocus
    }
    
    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
        hasAudioFocus = false
    }
    
    fun setPlaylist(songs: List<Song>, startIndex: Int = 0, autoPlay: Boolean = true) {
        val previousSong = currentSong
        playlist = songs
        
        if (songs.isEmpty()) {
            currentIndex = 0
            return
        }
        
        // If there's a current song playing, try to find it in the new playlist
        if (previousSong != null) {
            val newIndex = songs.indexOfFirst { it.id == previousSong.id }
            if (newIndex != -1) {
                // Current song exists in new playlist, just update index
                currentIndex = newIndex
                // Don't call playSong - keep current playback state
                return
            }
            // Current song not in new playlist, will need to start from beginning
        }
        
        // No current song or current song not in new playlist
        currentIndex = startIndex.coerceIn(0, songs.size - 1)
        playSong(songs[currentIndex], autoPlay)
    }
    
    fun playSong(song: Song, autoPlay: Boolean = true) {
        // Request audio focus trước khi phát
        if (!requestAudioFocus()) {
            // Không lấy được audio focus (đang có cuộc gọi, họp online...), không được phát nhạc
            return
        }
        
        mediaPlayer?.release()
        
        currentSong = song
        onSongChanged?.invoke(song)
        // Optimistic update or wait for prepare?
        // Let's wait for prepare to be safe, but update notification to "loading" if needed.
        
        try {
            isPreparing = true
            mediaPlayer = MediaPlayer().apply {
                setWakeMode(applicationContext, android.os.PowerManager.PARTIAL_WAKE_LOCK)
                setDataSource(song.path)
                setOnPreparedListener { mp ->
                    isPreparing = false
                    if (autoPlay) {
                        mp.start()
                        onPlaybackStateChanged?.invoke(true)
                        updateMediaSessionState(PlaybackStateCompat.STATE_PLAYING)
                    } else {
                        onPlaybackStateChanged?.invoke(false)
                        updateMediaSessionState(PlaybackStateCompat.STATE_PAUSED)
                    }
                    updateNotification()
                }
                setOnCompletionListener {
                    handleSongCompletion()
                }
                setOnErrorListener { _, _, _ ->
                    isPreparing = false
                    onPlaybackStateChanged?.invoke(false)
                    updateMediaSessionState(PlaybackStateCompat.STATE_ERROR)
                    true // Custom handling
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            isPreparing = false
            onPlaybackStateChanged?.invoke(false)
        }
        
        // Show notification immediately (might show Play button initially)
        startForeground(NOTIFICATION_ID, createNotification())
    }
    
    private fun handleSongCompletion() {
        when (repeatMode) {
            RepeatMode.ONE -> {
                currentSong?.let { playSong(it) }
            }
            RepeatMode.ALL -> {
                playNext()
            }
            RepeatMode.OFF -> {
                if (currentIndex < playlist.size - 1) {
                    playNext()
                } else {
                    onPlaybackStateChanged?.invoke(false)
                    abandonAudioFocus()
                }
            }
        }
    }
    
    fun playNext() {
        if (playlist.isEmpty()) return
        
        if (isShuffleOn && shuffledIndices.isNotEmpty()) {
            // Duyệt qua danh sách đã xáo trộn
            shuffleIndex = (shuffleIndex + 1) % shuffledIndices.size
            currentIndex = shuffledIndices[shuffleIndex]
        } else {
            currentIndex = (currentIndex + 1) % playlist.size
        }
        
        playSong(playlist[currentIndex], autoPlay = true)
    }
    
    fun playPrevious() {
        if (playlist.isEmpty()) return
        
        // Nếu đã phát hơn 3 giây, quay lại đầu bài
        if ((mediaPlayer?.currentPosition ?: 0) > 3000) {
            seekTo(0)
            return
        }
        
        if (isShuffleOn && shuffledIndices.isNotEmpty()) {
            // Quay lại bài trước trong danh sách đã xáo trộn
            shuffleIndex = if (shuffleIndex > 0) shuffleIndex - 1 else shuffledIndices.size - 1
            currentIndex = shuffledIndices[shuffleIndex]
        } else {
            currentIndex = if (currentIndex > 0) currentIndex - 1 else playlist.size - 1
        }
        playSong(playlist[currentIndex], autoPlay = true)
    }
    
    fun pause() {
        if (isPreparing) return
        mediaPlayer?.pause()
        onPlaybackStateChanged?.invoke(false)
        updateMediaSessionState(PlaybackStateCompat.STATE_PAUSED)
        updateNotification()
    }
    
    fun resume() {
        if (isPreparing) return
        // Request audio focus khi resume
        if (!hasAudioFocus) {
            requestAudioFocus()
        }
        try {
            mediaPlayer?.start()
            onPlaybackStateChanged?.invoke(true)
            updateMediaSessionState(PlaybackStateCompat.STATE_PLAYING)
            updateNotification()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun togglePlayPause() {
        if (isPlaying()) {
            pause()
        } else {
            resume()
        }
    }
    
    fun seekTo(position: Int) {
        mediaPlayer?.seekTo(position)
    }
    
    fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true
    
    fun getCurrentPosition(): Int = mediaPlayer?.currentPosition ?: 0
    
    fun getDuration(): Int = mediaPlayer?.duration ?: 0
    
    fun getCurrentSong(): Song? = currentSong
    
    fun toggleShuffle() {
        isShuffleOn = !isShuffleOn
        if (isShuffleOn) {
            generateShuffledOrder()
        }
    }
    
    /**
     * Tạo danh sách indices đã xáo trộn, đặt bài hiện tại ở đầu
     * để khi bấm Next sẽ chuyển sang bài khác (không lặp lại bài đang nghe)
     */
    private fun generateShuffledOrder() {
        if (playlist.isEmpty()) return
        
        // Tạo danh sách tất cả indices trừ bài hiện tại
        val otherIndices = (playlist.indices).filter { it != currentIndex }.shuffled()
        
        // Đặt bài hiện tại ở đầu, sau đó là các bài đã xáo trộn
        shuffledIndices = listOf(currentIndex) + otherIndices
        shuffleIndex = 0 // Đang ở bài hiện tại (vị trí 0 trong shuffled list)
    }
    
    fun isShuffleEnabled(): Boolean = isShuffleOn
    
    fun toggleRepeat() {
        repeatMode = when (repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
    }
    
    fun getRepeatMode(): RepeatMode = repeatMode
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Music Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Music player controls"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(largeIcon: android.graphics.Bitmap? = null): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val playPauseIntent = Intent(this, MusicPlayerService::class.java).apply {
            action = if (isPlaying()) ACTION_PAUSE else ACTION_PLAY
        }
        val playPausePendingIntent = PendingIntent.getService(
            this, 1, playPauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val nextIntent = Intent(this, MusicPlayerService::class.java).apply {
            action = ACTION_NEXT
        }
        val nextPendingIntent = PendingIntent.getService(
            this, 2, nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val prevIntent = Intent(this, MusicPlayerService::class.java).apply {
            action = ACTION_PREVIOUS
        }
        val prevPendingIntent = PendingIntent.getService(
            this, 3, prevIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        

        // Update Media Metadata
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentSong?.title ?: "VisualMP")
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentSong?.artist ?: "Music Player")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, getDuration().toLong())
        
        // Load bitmap asynchronous? Notification needs to be synchronous or updated later.
        // For simplicity, we can try to use a placeholder or check if we have a bitmap.
        // Ideally we should use Glide to load bitmap target and update notification then.
        // To avoid heavy work on main thread or service thread for notification build,
        // we might leave large icon empty initially or use a resource.
        
        mediaSession.setMetadata(metadataBuilder.build())
        
        // Use MediaStyle
        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
            .setMediaSession(mediaSession.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)
            
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentSong?.title ?: "VisualMP")
            .setContentText(currentSong?.artist ?: "Music Player")
            .setSmallIcon(R.drawable.ic_music_note)
            .setLargeIcon(largeIcon)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(isPlaying())
            .addAction(R.drawable.ic_previous, "Previous", prevPendingIntent)
            .addAction(
                if (isPlaying()) R.drawable.ic_pause else R.drawable.ic_play,
                if (isPlaying()) "Pause" else "Play",
                playPausePendingIntent
            )
            .addAction(R.drawable.ic_next, "Next", nextPendingIntent)
            .setStyle(mediaStyle)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }
    
    private fun updateMediaSessionState(state: Int) {
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                 PlaybackStateCompat.ACTION_PLAY or
                 PlaybackStateCompat.ACTION_PAUSE or
                 PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                 PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                 PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(state, getCurrentPosition().toLong(), 1.0f)
            .build()
        mediaSession.setPlaybackState(playbackState)
    }

    private fun updateNotification() {
        // We really should load album art here
        val notificationManager = getSystemService(NotificationManager::class.java)
        
        // Trigger Glide to load bitmap for notification
        currentSong?.let { song ->
             serviceScope.launch {
                  val bitmap = try {
                      kotlinx.coroutines.withContext(Dispatchers.IO) {
                          Glide.with(this@MusicPlayerService)
                              .asBitmap()
                              .load(com.skul9x.visualmp.util.CoverArtFetcher.getCoverArtUri(this@MusicPlayerService, song.id))
                              .signature(com.bumptech.glide.signature.ObjectKey(com.skul9x.visualmp.util.CoverArtFetcher.getCoverArtSignature(this@MusicPlayerService, song.id)))
                              .submit(500, 500) // limit size
                              .get()
                      }
                  } catch (e: Exception) {
                      BitmapFactory.decodeResource(resources, R.drawable.ic_music_note)
                  }
                  
                  // Update Session Metadata with Bitmap
                  val metadataBuilder = MediaMetadataCompat.Builder()
                      .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
                      .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
                      .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)
                      .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, getDuration().toLong())
                      .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                  
                  mediaSession.setMetadata(metadataBuilder.build())
                  
                  // Re-build notification with Large Icon
                  val notification = createNotification(largeIcon = bitmap)
                  notificationManager.notify(NOTIFICATION_ID, notification)
             }
        } ?: run {
             notificationManager.notify(NOTIFICATION_ID, createNotification(null))
        }
    }
    
    override fun onDestroy() {
        serviceScope.cancel() 
        mediaSession.release()
        abandonAudioFocus()
        mediaPlayer?.release()
        mediaPlayer = null
        super.onDestroy()
    }
    
    companion object {
        const val CHANNEL_ID = "music_playback_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_PLAY = "com.skul9x.visualmp.PLAY"
        const val ACTION_PAUSE = "com.skul9x.visualmp.PAUSE"
        const val ACTION_NEXT = "com.skul9x.visualmp.NEXT"
        const val ACTION_PREVIOUS = "com.skul9x.visualmp.PREVIOUS"
        const val ACTION_STOP = "com.skul9x.visualmp.STOP"
    }
}

