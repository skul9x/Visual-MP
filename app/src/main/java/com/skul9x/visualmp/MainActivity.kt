package com.skul9x.visualmp

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.skul9x.visualmp.util.SettingsManager
import com.skul9x.visualmp.databinding.ActivityMainBinding
import com.skul9x.visualmp.databinding.DialogEditMetadataBinding
import com.skul9x.visualmp.model.Song
import com.skul9x.visualmp.service.MusicPlayerService
import com.skul9x.visualmp.util.MetadataEditor
import com.skul9x.visualmp.viewmodel.MainViewModel
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.net.Uri
import android.provider.Settings
import android.graphics.BitmapFactory
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.signature.ObjectKey
import android.view.GestureDetector
import android.view.MotionEvent
import kotlin.math.abs
import com.skul9x.visualmp.util.CoverArtFetcher
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import androidx.viewpager2.widget.ViewPager2
import com.skul9x.visualmp.adapter.AlbumArtAdapter
import android.graphics.drawable.Drawable
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.transition.TransitionManager
import com.skul9x.visualmp.util.ResponsiveUtils

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    
    // For Metadata Editor
    private var currentEditingSong: Song? = null
    private var currentEditImageView: android.widget.ImageView? = null
    
    // Track the song currently visible in ViewPager (Browsing)
    private var currentDisplayedSong: Song? = null
    
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null && currentEditingSong != null) {
            handleCoverImageSelection(uri)
        }
    }
    
    private var musicService: MusicPlayerService? = null
    private var isBound = false
    private val handler = Handler(Looper.getMainLooper())
    private var isSeekBarTracking = false
    private lateinit var albumArtAdapter: AlbumArtAdapter
    private var isUpdateFromService = false
    private var isInitialLoad = true  // Flag to ignore first onPageSelected
    
    // Smooth seekbar animation
    private var progressAnimator: android.animation.ValueAnimator? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val readAudioGranted = permissions[Manifest.permission.READ_MEDIA_AUDIO] == true ||
                               permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        
        if (readAudioGranted) {
            viewModel.loadSongs()
        } else {
            Toast.makeText(this, "Cần cấp quyền để quét nhạc", Toast.LENGTH_LONG).show()
        }
    }
    
    // Launcher for SettingsActivity - reload songs if settings changed
    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // Settings changed (folder selection, cover art, etc.) - force reload
            // The songs observer will handle updating the service when new songs are loaded
            viewModel.loadSongs(forceReload = true)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicPlayerService.MusicBinder
            musicService = binder.getService()
            isBound = true
            
            setupServiceCallbacks()
            
            // Initial sync - check if service already has state (e.g., was running before)
            val currentSong = musicService?.getCurrentSong()
            if (currentSong != null) {
                // Service is already playing/paused - just sync UI
                updateUI()
            } else {
                // Service is idle (freshly created), set playlist from ViewModel WITHOUT auto-play
                // This handles the case when app is reopened after being killed from recents
                viewModel.songs.value?.let { songs ->
                    if (songs.isNotEmpty()) {
                        musicService?.setPlaylist(songs, autoPlay = false)
                    }
                }
            }
            
            startProgressUpdates()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupClickListeners()
        setupSeekBar()
        setupViewModel()
        setupViewPager()
        
        // Kích hoạt hiệu ứng marquee cho tên bài hát dài
        binding.tvTitle.isSelected = true
        
        checkPermissionsAndLoad()
        
        // Apply responsive layout
        binding.root.post {
            applyResponsiveLayout()
        }
    }

    override fun onStart() {
        super.onStart()
        if (!isBound) {
            bindMusicService()
        } else {
            // Re-setup callbacks when returning from background
            setupServiceCallbacks()
        }
    }

    override fun onStop() {
        super.onStop()
        stopProgressUpdates()
        // Don't unbind service here - keep it running in background!
        // Just clear callbacks to prevent memory leaks
        if (isBound) {
            musicService?.onSongChanged = null
            musicService?.onPlaybackStateChanged = null
        }
    }
    
    override fun onDestroy() {
        // Only unbind when Activity is truly destroyed
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }

    private fun checkPermissionsAndLoad() {
        val permissionsToRequest = mutableListOf<String>()

        // 1. Audio file permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.READ_MEDIA_AUDIO)
            // 2. Notification permission (required for foreground service on Android 13+)
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val notGrantedPermissions = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGrantedPermissions.isEmpty()) {
            viewModel.loadSongs()
        } else {
            permissionLauncher.launch(notGrantedPermissions.toTypedArray())
        }
    }

    private fun setupViewModel() {
        viewModel.songs.observe(this) { songs ->
            albumArtAdapter.submitList(songs)
            
            if (songs.isNotEmpty()) {
                // Always update service playlist when songs change
                // This handles initial load, folder changes, metadata updates, etc.
                if (isBound) {
                    // Preserve current playing state - don't auto-play
                    musicService?.setPlaylist(songs, autoPlay = false)
                }
            } else if (viewModel.isLoaded) {
                 Toast.makeText(this, "Không tìm thấy bài hát nào", Toast.LENGTH_LONG).show()
                 binding.tvTitle.text = "Không có bài hát"
                 binding.tvArtist.text = "Vui lòng chép nhạc vào máy"
                 updatePlayPauseButton(false)
            }
        }
    }

    private fun setupViewPager() {
        albumArtAdapter = AlbumArtAdapter()
        binding.vpAlbumArt?.adapter = albumArtAdapter
        
        // Add Transformer for Zoom/Depth effect if desired, or default slide
        // binding.vpAlbumArt?.setPageTransformer(ZoomOutPageTransformer()) 

        binding.vpAlbumArt?.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                val songs = viewModel.songs.value
                if (songs != null && position in songs.indices) {
                    currentDisplayedSong = songs[position]
                    
                    // If user swiped (not programmatic update or initial load), play immediately
                    // Reset the flag INSIDE the callback to avoid race condition
                    val wasServiceUpdate = isUpdateFromService
                    isUpdateFromService = false  // Reset flag here, after callback fires
                    
                    if (!wasServiceUpdate && !isInitialLoad) {
                        // Keep current play/pause state when swiping:
                        // - If music is playing, play the new song immediately
                        // - If music is paused, switch to new song but stay paused
                        val wasPlaying = musicService?.isPlaying() == true
                        musicService?.playSong(songs[position], autoPlay = wasPlaying)
                    }
                    
                    // After first page selection, clear the initial load flag
                    if (isInitialLoad) {
                        isInitialLoad = false
                    }
                    
                    // Update UI (Text, Seekbar state, Play Button) for the new slide
                    updatePlayerUI()
                }
            }
        })
    }

    private fun setupClickListeners() {
        binding.btnSettings.setOnClickListener {
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }

        binding.btnWifi?.setOnClickListener {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }

        binding.btnPlayPause.setOnClickListener {
            val service = musicService ?: return@setOnClickListener
            val displayed = currentDisplayedSong
            val playing = service.getCurrentSong()

            if (displayed != null && displayed.id != playing?.id) {
                // Browsing a different song -> Start playing it
                service.playSong(displayed, autoPlay = true)
            } else {
                // Viewing current song -> Toggle Play/Pause
                service.togglePlayPause()
            }
        }

        binding.btnNext.setOnClickListener {
            musicService?.playNext()
        }

        binding.btnPrevious.setOnClickListener {
            musicService?.playPrevious()
        }

        binding.btnShuffle.setOnClickListener {
            musicService?.toggleShuffle()
            updateShuffleButton()
        }

        binding.btnRepeat.setOnClickListener {
            musicService?.toggleRepeat()
            updateRepeatButton()
        }

        binding.btnEditMetadata.setOnClickListener {
            showEditMetadataDialog()
        }
    }

    private fun bindMusicService() {
        val intent = Intent(this, MusicPlayerService::class.java)
        // Start as foreground service first - MediaSessionService will handle notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        // Then bind for direct communication
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun setupServiceCallbacks() {
        musicService?.apply {
            onSongChanged = { song ->
                safeRunOnUiThread { updateSongInfo(song) }
            }
            
            onPlaybackStateChanged = { isPlaying ->
                safeRunOnUiThread { updatePlayPauseButton(isPlaying) }
            }
        }
    }

    private fun safeRunOnUiThread(action: () -> Unit) {
        if (!isDestroyed && !isFinishing) {
            runOnUiThread {
                try {
                    action()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun updateUI() {
        musicService?.let { service ->
            // updateSongInfo() sẽ:
            // 1. Nếu ViewPager chưa ở đúng trang -> set ViewPager -> onPageSelected kích hoạt -> gọi updatePlayerUI()
            // 2. Nếu ViewPager đã ở đúng trang -> gọi trực tiếp updatePlayerUI() (dòng 369)
            // => Không cần gọi updatePlayerUI() ở đây nữa để tránh cập nhật 2 lần
            updateSongInfo(service.getCurrentSong())
            
            updateShuffleButton()
            updateRepeatButton()
            updateProgress()
        }
    }

    private fun updateSongInfo(song: Song?) {
        if (song == null) return
        
        // Note: We do NOT set TextViews here directly anymore, 
        // because we might be browsing a different song.
        // We only forcefully snap the ViewPager to the playing song.
        
        // Sync ViewPager
        val songs = viewModel.songs.value
        if (songs != null) {
            val index = songs.indexOfFirst { it.id == song.id }
            if (index != -1 && binding.vpAlbumArt?.currentItem != index) {
                isUpdateFromService = true  // Flag will be reset inside onPageSelected callback
                binding.vpAlbumArt?.setCurrentItem(index, true)
                // Note: Do NOT reset flag here - it will be reset in onPageSelected after animation
            } else {
                // Already on the correct page, or song not found.
                // If we are on the page, updatePlayerUI to ensure Play/Pause icon is correct
                if (currentDisplayedSong?.id == song.id) {
                    updatePlayerUI()
                }
            }
        }
    }

    /**
     * Updates Title, Artist, Seekbar setup, and Play/Pause button
     * based on currentDisplayedSong vs musicService.currentSong
     */
    private fun updatePlayerUI() {
        val displayed = currentDisplayedSong ?: return
        val playing = musicService?.getCurrentSong()
        val isPlaying = musicService?.isPlaying() == true
        
        binding.tvTitle.text = displayed.title
        binding.tvArtist.text = displayed.artist
        binding.tvTotalTime.text = displayed.getFormattedDuration()
        binding.seekBar.max = displayed.duration.toInt()
        
        val isViewingPlayingSong = (displayed.id == playing?.id)
        
        if (isViewingPlayingSong) {
            // Viewing current song: Show actual progress, correct Play/Pause state
            binding.btnPlayPause.setImageResource(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )
            binding.seekBar.isEnabled = true
            // Progress will be updated by updateProgress()
        } else {
            // Browsing other song: Show "Play" icon, Reset Seekbar/Time
            binding.btnPlayPause.setImageResource(R.drawable.ic_play)
            
            // Optional: Disable seekbar or set to 0
            binding.seekBar.progress = 0
            binding.tvCurrentTime.text = "0:00"
            // We can disable seekbar to avoid confusion, or let user seek (start at pos?)
            // For now, simple interaction: Play starts from 0.
            binding.seekBar.isEnabled = false 
        }
    }
    
    private fun updatePlayPauseButton(isPlaying: Boolean) {
        // Just trigger UI update to check state matches
        updatePlayerUI()
    }
    
    private fun updateShuffleButton() {
        val isActive = musicService?.isShuffleEnabled() == true
        binding.btnShuffle.alpha = if (isActive) 1.0f else 0.5f
        binding.btnShuffle.setColorFilter(
            if (isActive) getColor(R.color.purple_primary)
            else getColor(com.google.android.material.R.color.m3_ref_palette_neutral60) // Fallback color
        )
        // Reset tint for white icons if needed, but in player layout they are usually white text/icon.
        // We tint them purple if active.
        if (!isActive) binding.btnShuffle.clearColorFilter()
    }

    private fun updateRepeatButton() {
        val repeatMode = musicService?.getRepeatMode() ?: MusicPlayerService.RepeatMode.OFF
        
        when (repeatMode) {
            MusicPlayerService.RepeatMode.OFF -> {
                binding.btnRepeat.setImageResource(R.drawable.ic_repeat)
                binding.btnRepeat.alpha = 0.5f
                binding.btnRepeat.clearColorFilter()
            }
            MusicPlayerService.RepeatMode.ALL -> {
                binding.btnRepeat.setImageResource(R.drawable.ic_repeat)
                binding.btnRepeat.alpha = 1.0f
                binding.btnRepeat.setColorFilter(getColor(R.color.purple_primary))
            }
            MusicPlayerService.RepeatMode.ONE -> {
                binding.btnRepeat.setImageResource(R.drawable.ic_repeat_one)
                binding.btnRepeat.alpha = 1.0f
                binding.btnRepeat.setColorFilter(getColor(R.color.purple_primary))
            }
        }
    }

    private fun setupSeekBar() {
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvCurrentTime.text = formatTime(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeekBarTracking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeekBarTracking = false
                musicService?.seekTo(binding.seekBar.progress)
            }
        })
    }

    private fun updateProgress() {
        if (!isSeekBarTracking) {
            val service = musicService
            val playing = service?.getCurrentSong()
            val displayed = currentDisplayedSong
            
            // Only update progress if we are looking at the playing song
            if (service != null && playing != null && displayed?.id == playing.id) {
                val currentPosition = service.getCurrentPosition()
                binding.seekBar.progress = currentPosition
                binding.tvCurrentTime.text = formatTime(currentPosition)
            }
        }
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        
        val service = musicService ?: return
        
        // Create smooth animator that updates every frame
        progressAnimator = android.animation.ValueAnimator.ofInt(0, 1000).apply {
            duration = 1000 // 1 second cycle
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            
            addUpdateListener {
                if (!isSeekBarTracking) {
                    val playing = service.getCurrentSong()
                    val displayed = currentDisplayedSong
                    
                    if (playing != null && displayed?.id == playing.id && service.isPlaying()) {
                        val currentPosition = service.getCurrentPosition()
                        val duration = service.getDuration()
                        
                        if (duration > 0) {
                            binding.seekBar.max = duration
                            binding.seekBar.progress = currentPosition
                            binding.tvCurrentTime.text = formatTime(currentPosition)
                        }
                    }
                }
            }
        }
        progressAnimator?.start()
    }

    private fun stopProgressUpdates() {
        progressAnimator?.cancel()
        progressAnimator = null
    }

    private fun formatTime(milliseconds: Int): String {
        val minutes = (milliseconds / 1000) / 60
        val seconds = (milliseconds / 1000) % 60
        return String.format("%d:%02d", minutes, seconds)
    }
    
    // Metadata Edit Dialog reusing logic from PlayerActivity
    private fun showEditMetadataDialog() {
        val currentSong = currentDisplayedSong ?: musicService?.getCurrentSong() ?: return
        
        val dialog = BottomSheetDialog(this, R.style.Theme_VisualMP_BottomSheet)
        val dialogBinding = DialogEditMetadataBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        
        dialog.setOnDismissListener {
            currentEditingSong = null
            currentEditImageView = null
        }
        
        dialogBinding.apply {
            etTitle.setText(currentSong.title)
            etArtist.setText(currentSong.artist)
            etAlbum.setText(currentSong.album)
            tvFilePath.text = currentSong.path
            
            currentEditingSong = currentSong
            currentEditImageView = ivAlbumArt
            
            // Load current art (custom or default)
            Glide.with(this@MainActivity)
                .load(com.skul9x.visualmp.util.CoverArtFetcher.getCoverArtUri(this@MainActivity, currentSong.id, currentSong.albumArtUri))
                .signature(ObjectKey(com.skul9x.visualmp.util.CoverArtFetcher.getCoverArtSignature(this@MainActivity, currentSong.id)))
                .placeholder(R.drawable.ic_music_note)
                .error(R.drawable.ic_music_note)
                .centerCrop()
                .into(ivAlbumArt)
                
            // Setup Listeners
            btnDeleteCover.setOnClickListener {
                 com.skul9x.visualmp.util.CoverArtFetcher.deleteCustomCoverArt(this@MainActivity, currentSong.id)
                 Glide.with(this@MainActivity)
                      .load(currentSong.albumArtUri)
                      .signature(ObjectKey(com.skul9x.visualmp.util.CoverArtFetcher.getCoverArtSignature(this@MainActivity, currentSong.id)))
                      .into(ivAlbumArt)
                 if (musicService?.getCurrentSong()?.id == currentSong.id) {
                     updateSongInfo(currentSong)
                 }
                 Toast.makeText(this@MainActivity, "Đã xóa ảnh bìa tùy chỉnh", Toast.LENGTH_SHORT).show()
            }
            
            btnGenerateCover.setOnClickListener {
                pickImageLauncher.launch("image/*")
            }
            
            btnSearchOnline?.setOnClickListener {
                val query = "${etTitle.text} ${etArtist.text}".trim()
                if (query.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Vui lòng nhập tên bài hát hoặc nghệ sĩ", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                
                // Capture songId before launching coroutine to prevent NPE if dialog is dismissed
                val songIdToSave = currentEditingSong?.id ?: return@setOnClickListener
                
                Toast.makeText(this@MainActivity, "Đang tìm kiếm thông tin...", Toast.LENGTH_SHORT).show()
                btnSearchOnline?.isEnabled = false
                
                lifecycleScope.launch(Dispatchers.IO) {
                    // 1. Tìm metadata từ Deezer
                    val result = com.skul9x.visualmp.util.DeezerApi.searchTrack(query)
                    
                    // 2. Tìm cover art từ Google Images (theo thuật toán mới)
                    val (googleCoverUrl, _) = com.skul9x.visualmp.util.CoverArtFetcher.searchCoverArt(etTitle.text.toString(), etArtist.text.toString())
                    
                    withContext(Dispatchers.Main) {
                        btnSearchOnline?.isEnabled = true
                        
                        // Cập nhật metadata nếu tìm thấy
                        if (result != null) {
                            if (etTitle.text.isNullOrEmpty()) etTitle.setText(result.title)
                            if (etArtist.text.isNullOrEmpty()) etArtist.setText(result.artist)
                            if (etAlbum.text.isNullOrEmpty()) etAlbum.setText(result.album)
                        }
                        
                        // Ưu tiên dùng ảnh từ Google Images, nếu không có mới dùng từ Deezer
                        val finalCoverUrl = googleCoverUrl ?: result?.coverUrl
                        
                        if (!finalCoverUrl.isNullOrEmpty()) {
                            Toast.makeText(this@MainActivity, "Đã tìm thấy ảnh từ ${if (googleCoverUrl != null) "Google" else "Deezer"}", Toast.LENGTH_SHORT).show()
                            
                            currentEditImageView?.let { iv ->
                                Glide.with(this@MainActivity)
                                    .asBitmap()
                                    .load(finalCoverUrl)
                                    .into(object : com.bumptech.glide.request.target.CustomTarget<android.graphics.Bitmap>() {
                                        override fun onResourceReady(resource: android.graphics.Bitmap, transition: com.bumptech.glide.request.transition.Transition<in android.graphics.Bitmap>?) {
                                            iv.setImageBitmap(resource)
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                com.skul9x.visualmp.util.CoverArtFetcher.saveCoverArt(this@MainActivity, songIdToSave, resource)
                                            }
                                        }
                                        override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {}
                                    })
                            }
                        } else {
                            Toast.makeText(this@MainActivity, "Không tìm thấy kết quả nào", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            
            btnClose.setOnClickListener { dialog.dismiss() }
            btnCancel.setOnClickListener { dialog.dismiss() }
            
            btnSave.setOnClickListener {
                val newTitle = etTitle.text?.toString()?.trim() ?: ""
                val newArtist = etArtist.text?.toString()?.trim() ?: ""
                val newAlbum = etAlbum.text?.toString()?.trim() ?: ""
                
                if (newTitle.isEmpty()) {
                    tilTitle.error = "Tiêu đề không được để trống"
                    return@setOnClickListener
                }
                
                saveMetadata(currentSong, newTitle, newArtist, newAlbum, dialog)
            }
        }
        dialog.show()
    }
    
    private fun saveMetadata(song: Song, title: String, artist: String, album: String, dialog: BottomSheetDialog) {
         val metadata = MetadataEditor.SongMetadata(title, artist.ifEmpty { "Unknown" }, album.ifEmpty { "Unknown" })
         dialog.findViewById<View>(R.id.btnSave)?.isEnabled = false
         
         lifecycleScope.launch(Dispatchers.IO) {
             val result = MetadataEditor.updateMetadata(this@MainActivity, song, metadata)
             withContext(Dispatchers.Main) {
                 result.fold(
                     onSuccess = {
                         Toast.makeText(this@MainActivity, "Đã lưu!", Toast.LENGTH_SHORT).show()
                         dialog.dismiss()
                         
                         // Create a copy to trigger DiffUtil update (Reference equality change)
                         val newSong = song.copy(title = title, artist = artist, album = album)
                         
                         // Update ViewModel with the NEW object
                         viewModel.updateSong(newSong)
                         
                         // If this song is currently displayed/playing, update UI directly too
                         if (currentDisplayedSong?.id == newSong.id) {
                             currentDisplayedSong = newSong
                             updatePlayerUI()
                         }
                         if (musicService?.getCurrentSong()?.id == newSong.id) {
                             updateSongInfo(newSong)
                         }
                     },
                     onFailure = {
                         Toast.makeText(this@MainActivity, "Lỗi: ${it.message}", Toast.LENGTH_SHORT).show()
                         dialog.findViewById<View>(R.id.btnSave)?.isEnabled = true
                     }
                 )
             }
         }
    }



    private fun handleCoverImageSelection(uri: Uri) {
        // Capture song info before launching coroutine to prevent NPE if dialog is dismissed
        val songToUpdate = currentEditingSong ?: return
        val songIdToSave = songToUpdate.id
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                if (bitmap != null) {
                    val saved = CoverArtFetcher.saveCoverArt(this@MainActivity, songIdToSave, bitmap)
                    if (saved) {
                        withContext(Dispatchers.Main) {
                            // Update ImageView in Dialog
                            currentEditImageView?.let { iv ->
                                Glide.with(this@MainActivity)
                                    .load(CoverArtFetcher.getCoverArtUri(this@MainActivity, songIdToSave, songToUpdate.albumArtUri))
                                    .signature(ObjectKey(CoverArtFetcher.getCoverArtSignature(this@MainActivity, songIdToSave)))
                                    .into(iv)
                            }
                            // Update Main Player Art if needed
                            val playingSong = musicService?.getCurrentSong()
                            if (playingSong?.id == songIdToSave) {
                                // Reload current song to refresh art
                                updateSongInfo(playingSong)
                            }
                            
                            // FORCE UPDATE LIST to refresh ViewPager
                            val newSong = songToUpdate.copy()
                            viewModel.updateSong(newSong)
                            
                            Toast.makeText(this@MainActivity, "Đã cập nhật ảnh bìa!", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch(e: Exception) { e.printStackTrace() }
        }
    }

    private fun applyResponsiveLayout() {
        val isLandscape = ResponsiveUtils.shouldUseLandscapeLayout(resources)
        val constraintSet = ConstraintSet()
        constraintSet.clone(binding.main)

        if (isLandscape) {
            // LANDSCAPE LAYOUT: Side-by-Side
            // Album Art: Left side, match parent height (minus margins), width constrained by percent or ratio
            val artPercent = ResponsiveUtils.getAlbumArtSizePercent(resources)
            
            constraintSet.clear(R.id.albumArtContainer, ConstraintSet.TOP)
            constraintSet.clear(R.id.albumArtContainer, ConstraintSet.START)
            constraintSet.clear(R.id.albumArtContainer, ConstraintSet.END)
            constraintSet.clear(R.id.albumArtContainer, ConstraintSet.BOTTOM)
            
            constraintSet.connect(R.id.albumArtContainer, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, ResponsiveUtils.dpToPx(resources, 16f))
            constraintSet.connect(R.id.albumArtContainer, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, ResponsiveUtils.dpToPx(resources, 16f))
            constraintSet.connect(R.id.albumArtContainer, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, ResponsiveUtils.dpToPx(resources, 16f))
            
            // Important: In landscape, we want height to fill parent, and width to be 1:1 ratio of that height
            // OR use percent width. Using percent width is safer for "Side-by-Side" logic.
            constraintSet.constrainPercentWidth(R.id.albumArtContainer, artPercent)
            constraintSet.setDimensionRatio(R.id.albumArtContainer, "1:1") 
            // If we set Ratio 1:1 and Percent Width, Height will be determined by Width.
            // If that Height > Parent Height, it might clip or push.
            // Let's set Height to MATCH_CONSTRAINT (0dp) and Width to MATCH_CONSTRAINT (0dp).
            // Then set Ratio 1:1.
            // And constrain Top/Bottom to Parent.
            // And constrain Width Percent.
            // This should make it fit within the box defined by Width Percent and Parent Height.
            
            // Right Side Container (Title, Artist, SeekBar, Controls)
            // Title
            constraintSet.clear(R.id.tvTitle, ConstraintSet.TOP)
            constraintSet.clear(R.id.tvTitle, ConstraintSet.START)
            constraintSet.clear(R.id.tvTitle, ConstraintSet.END)
            
            constraintSet.connect(R.id.tvTitle, ConstraintSet.START, R.id.albumArtContainer, ConstraintSet.END, ResponsiveUtils.dpToPx(resources, 24f))
            constraintSet.connect(R.id.tvTitle, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, ResponsiveUtils.dpToPx(resources, 24f))
            constraintSet.connect(R.id.tvTitle, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, ResponsiveUtils.dpToPx(resources, 32f))
            constraintSet.setHorizontalBias(R.id.tvTitle, 0.5f)
            
            // Artist
            constraintSet.clear(R.id.tvArtist, ConstraintSet.TOP)
            constraintSet.clear(R.id.tvArtist, ConstraintSet.START)
            constraintSet.clear(R.id.tvArtist, ConstraintSet.END)
            
            constraintSet.connect(R.id.tvArtist, ConstraintSet.TOP, R.id.tvTitle, ConstraintSet.BOTTOM, ResponsiveUtils.dpToPx(resources, 8f))
            constraintSet.connect(R.id.tvArtist, ConstraintSet.START, R.id.tvTitle, ConstraintSet.START)
            constraintSet.connect(R.id.tvArtist, ConstraintSet.END, R.id.tvTitle, ConstraintSet.END)
            
            // Controls (Bottom Right)
            constraintSet.clear(R.id.controlsLayout, ConstraintSet.TOP)
            constraintSet.clear(R.id.controlsLayout, ConstraintSet.BOTTOM)
            constraintSet.clear(R.id.controlsLayout, ConstraintSet.START)
            constraintSet.clear(R.id.controlsLayout, ConstraintSet.END)
            
            constraintSet.connect(R.id.controlsLayout, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, ResponsiveUtils.dpToPx(resources, 24f))
            constraintSet.connect(R.id.controlsLayout, ConstraintSet.START, R.id.albumArtContainer, ConstraintSet.END, ResponsiveUtils.dpToPx(resources, 24f))
            constraintSet.connect(R.id.controlsLayout, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, ResponsiveUtils.dpToPx(resources, 24f))
            
            // SeekBar (Above controls)
            constraintSet.clear(R.id.seekBar, ConstraintSet.TOP)
            constraintSet.clear(R.id.seekBar, ConstraintSet.BOTTOM)
            constraintSet.clear(R.id.seekBar, ConstraintSet.START)
            constraintSet.clear(R.id.seekBar, ConstraintSet.END)
            
            constraintSet.connect(R.id.seekBar, ConstraintSet.BOTTOM, R.id.controlsLayout, ConstraintSet.TOP, ResponsiveUtils.dpToPx(resources, 24f))
            constraintSet.connect(R.id.seekBar, ConstraintSet.START, R.id.albumArtContainer, ConstraintSet.END, ResponsiveUtils.dpToPx(resources, 24f))
            constraintSet.connect(R.id.seekBar, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, ResponsiveUtils.dpToPx(resources, 24f))
            
            // Time Labels
            constraintSet.clear(R.id.tvCurrentTime, ConstraintSet.TOP)
            constraintSet.clear(R.id.tvCurrentTime, ConstraintSet.START)
            constraintSet.connect(R.id.tvCurrentTime, ConstraintSet.TOP, R.id.seekBar, ConstraintSet.BOTTOM, ResponsiveUtils.dpToPx(resources, 4f))
            constraintSet.connect(R.id.tvCurrentTime, ConstraintSet.START, R.id.seekBar, ConstraintSet.START)
            
            constraintSet.clear(R.id.tvTotalTime, ConstraintSet.TOP)
            constraintSet.clear(R.id.tvTotalTime, ConstraintSet.END)
            constraintSet.connect(R.id.tvTotalTime, ConstraintSet.TOP, R.id.seekBar, ConstraintSet.BOTTOM, ResponsiveUtils.dpToPx(resources, 4f))
            constraintSet.connect(R.id.tvTotalTime, ConstraintSet.END, R.id.seekBar, ConstraintSet.END)
            
            constraintSet.setVisibility(R.id.tvNowPlaying, View.GONE)
            
        } else {
            // PORTRAIT / SQUARE LAYOUT
            // Reset to XML defaults roughly, BUT enforce height limit on Album Art
            constraintSet.clone(this, R.layout.activity_main)
            
            // Enforce Max Height for Album Art to ensure controls fit
            // In square/compact mode, Art should not exceed ~55% of screen height
            constraintSet.constrainPercentHeight(R.id.albumArtContainer, 0.55f)
            
            // Ensure it's still 1:1 ratio if possible, but height is the limiter now
            constraintSet.setDimensionRatio(R.id.albumArtContainer, "1:1")
            
            // Ensure controls are visible
            constraintSet.setVisibility(R.id.tvNowPlaying, View.VISIBLE)
        }

        TransitionManager.beginDelayedTransition(binding.main)
        constraintSet.applyTo(binding.main)
    }

}