package com.skul9x.visualmp

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.skul9x.visualmp.adapter.FolderPickerAdapter
import com.skul9x.visualmp.adapter.SelectedFolderAdapter
import com.skul9x.visualmp.databinding.ActivitySettingsBinding
import com.skul9x.visualmp.util.CoverArtFetcher
import com.skul9x.visualmp.util.MusicScanner
import com.skul9x.visualmp.util.SettingsManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var selectedFolderAdapter: SelectedFolderAdapter
    private var hasChanges = false
    private var isFetching = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        
        setupViews()
        loadSettings()
    }

    private fun setupViews() {
        // Back button
        binding.btnBack.setOnClickListener {
            if (!isFetching) {
                finish()
            } else {
                Toast.makeText(this, "Đang tải cover art, vui lòng chờ...", Toast.LENGTH_SHORT).show()
            }
        }
        
        // Selected folders RecyclerView
        selectedFolderAdapter = SelectedFolderAdapter { folderPath ->
            removeFolder(folderPath)
        }
        binding.rvSelectedFolders.apply {
            layoutManager = LinearLayoutManager(this@SettingsActivity)
            adapter = selectedFolderAdapter
        }
        
        // Scan all switch
        binding.switchScanAll.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Reset to scan all
                SettingsManager.resetToScanAll(this)
                binding.layoutSelectedFolders.visibility = View.GONE
                hasChanges = true
            } else {
                // Show folder selection UI
                binding.layoutSelectedFolders.visibility = View.VISIBLE
            }
            updateSelectedFolders()
        }
        
        // Add folder button
        binding.cardAddFolder.setOnClickListener {
            showFolderPickerDialog()
        }

        // Cover art fetch button
        binding.btnFetchCoverArt.setOnClickListener {
            startCoverArtFetch()
        }

        // Find Metadata button
        binding.btnFindMetadata.setOnClickListener {
            startMetadataFetch()
        }
        
        binding.btnFindMetadata.setOnClickListener {
            startMetadataFetch()
        }

        // User Guide button
        binding.cardUserGuide.setOnClickListener {
            showUserGuide()
        }

        // View Debug Logs button
        binding.btnViewDebugLogs.setOnClickListener {
            showDebugLogsDialog()
        }
    }

    private fun showDebugLogsDialog() {
        val logs = CoverArtFetcher.getDebugLog()
        val dialogView = layoutInflater.inflate(R.layout.dialog_debug_logs, null)
        val tvLogs = dialogView.findViewById<android.widget.TextView>(R.id.tvLogs)
        val btnCopy = dialogView.findViewById<android.widget.Button>(R.id.btnCopy)
        val btnClear = dialogView.findViewById<android.widget.Button>(R.id.btnClear)
        
        tvLogs.text = if (logs.isNotEmpty()) logs else "Chưa có log nào..."
        
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Debug Logs")
            .setView(dialogView)
            .setPositiveButton("Đóng", null)
            .create()

        btnCopy.setOnClickListener {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Debug Logs", logs)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Đã copy log vào bộ nhớ tạm!", Toast.LENGTH_SHORT).show()
        }
        
        btnClear.setOnClickListener {
            CoverArtFetcher.clearDebugLog()
            tvLogs.text = "Chưa có log nào..."
            Toast.makeText(this, "Đã xóa log!", Toast.LENGTH_SHORT).show()
        }
        
        dialog.show()
    }

    private fun startCoverArtFetch() {
        if (isFetching) {
            Toast.makeText(this, "Đang tải, vui lòng chờ...", Toast.LENGTH_SHORT).show()
            return
        }

        // First, scan songs
        binding.cardFetchProgress.visibility = View.VISIBLE
        binding.tvFetchProgress.text = "Đang quét danh sách nhạc..."
        binding.tvFetchSongName.text = ""
        binding.tvFetchCount.text = ""
        binding.btnFetchCoverArt.isEnabled = false
        isFetching = true

        lifecycleScope.launch(Dispatchers.IO) {
            val scannedSongs = MusicScanner.scanMusic(this@SettingsActivity)
            // Áp dụng custom metadata trước khi tìm cover art
            // Nếu không áp dụng, app sẽ tìm theo tên gốc -> sai lệch nếu user đã sửa tên
            val songs = com.skul9x.visualmp.util.MetadataEditor.applyCustomMetadataToList(this@SettingsActivity, scannedSongs)
            val songsWithoutArt = CoverArtFetcher.getSongsWithoutCoverArt(this@SettingsActivity, songs)

            withContext(Dispatchers.Main) {
                if (songsWithoutArt.isEmpty()) {
                    binding.cardFetchProgress.visibility = View.GONE
                    binding.btnFetchCoverArt.isEnabled = true
                    isFetching = false
                    Toast.makeText(
                        this@SettingsActivity,
                        "Tất cả bài hát đã có cover art! 🎉",
                        Toast.LENGTH_LONG
                    ).show()
                    return@withContext
                }

                // Show confirmation dialog
                MaterialAlertDialogBuilder(this@SettingsActivity)
                    .setTitle("Tải Cover Art")
                    .setMessage("Tìm thấy ${songsWithoutArt.size} bài hát chưa có cover art.\n\nBạn có muốn tải từ Google không?")
                    .setPositiveButton("Tải ngay") { _, _ ->
                        fetchCoverArtForSongs(songsWithoutArt)
                    }
                    .setNegativeButton("Hủy") { _, _ ->
                        binding.cardFetchProgress.visibility = View.GONE
                        binding.btnFetchCoverArt.isEnabled = true
                        isFetching = false
                    }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    private fun fetchCoverArtForSongs(songs: List<com.skul9x.visualmp.model.Song>) {
        binding.tvFetchProgress.text = "Đang tải cover art..."
        
        lifecycleScope.launch(Dispatchers.IO) {
            CoverArtFetcher.batchFetchCoverArt(
                this@SettingsActivity,
                songs,
                object : CoverArtFetcher.ProgressCallback {
                    override fun onProgress(current: Int, total: Int, currentSong: String) {
                        runOnUiThread {
                            binding.tvFetchCount.text = "$current/$total"
                            binding.tvFetchSongName.text = currentSong
                        }
                    }

                    override fun onComplete(successCount: Int, failCount: Int) {
                        runOnUiThread {
                            binding.cardFetchProgress.visibility = View.GONE
                            binding.btnFetchCoverArt.isEnabled = true
                            isFetching = false

                            val message = if (successCount > 0) {
                                "Đã tải $successCount cover art thành công! 🎉"
                            } else {
                                "Không tìm thấy cover art phù hợp"
                            }
                            
                            Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_LONG).show()
                            
                            if (successCount > 0) {
                                hasChanges = true
                            }
                        }
                    }

                    override fun onError(message: String) {
                        runOnUiThread {
                            binding.cardFetchProgress.visibility = View.GONE
                            binding.btnFetchCoverArt.isEnabled = true
                            isFetching = false
                            Toast.makeText(this@SettingsActivity, "Lỗi: $message", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            )
        }
    }

    private fun startMetadataFetch() {
        if (isFetching) {
            Toast.makeText(this, "Đang tải, vui lòng chờ...", Toast.LENGTH_SHORT).show()
            return
        }

        binding.cardFetchProgress.visibility = View.VISIBLE
        binding.tvFetchProgress.text = "Đang quét metadata..."
        binding.tvFetchSongName.text = ""
        binding.tvFetchCount.text = ""
        binding.btnFindMetadata.isEnabled = false
        binding.btnFetchCoverArt.isEnabled = false
        isFetching = true

        lifecycleScope.launch(Dispatchers.IO) {
            val scannedSongs = MusicScanner.scanMusic(this@SettingsActivity)
            val songs = com.skul9x.visualmp.util.MetadataEditor.applyCustomMetadataToList(this@SettingsActivity, scannedSongs)
            
            withContext(Dispatchers.Main) {
                MaterialAlertDialogBuilder(this@SettingsActivity)
                    .setTitle("Tìm Metadata")
                    .setMessage("App sẽ tìm tên bài hát, nghệ sĩ cho các bài bị thiếu thông tin.\n\nQuá trình này cần mạng internet.")
                    .setPositiveButton("Bắt đầu") { _, _ ->
                        fetchMetadataForSongs(songs)
                    }
                    .setNegativeButton("Hủy") { _, _ ->
                        stopFetchingUI()
                    }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    private fun fetchMetadataForSongs(songs: List<com.skul9x.visualmp.model.Song>) {
        binding.tvFetchProgress.text = "Đang tìm thông tin..."
        
        lifecycleScope.launch(Dispatchers.IO) {
            com.skul9x.visualmp.util.MetadataFetcher.batchFetchMetadata(
                this@SettingsActivity,
                songs,
                object : com.skul9x.visualmp.util.MetadataFetcher.ProgressCallback {
                    override fun onProgress(current: Int, total: Int, currentSong: String) {
                        runOnUiThread {
                            binding.tvFetchCount.text = "$current/$total"
                            binding.tvFetchSongName.text = currentSong
                        }
                    }

                    override fun onComplete(successCount: Int, failCount: Int) {
                        runOnUiThread {
                            stopFetchingUI()

                            val message = if (successCount > 0) {
                                hasChanges = true
                                "Đã cập nhật $successCount bài hát! 🎉"
                            } else {
                                "Không tìm thấy thông tin mới nào"
                            }
                            
                            Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_LONG).show()
                        }
                    }

                    override fun onError(message: String) {
                        runOnUiThread {
                            stopFetchingUI()
                            Toast.makeText(this@SettingsActivity, "Lỗi: $message", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            )
        }
    }
    
    private fun stopFetchingUI() {
        binding.cardFetchProgress.visibility = View.GONE
        binding.btnFetchCoverArt.isEnabled = true
        binding.btnFindMetadata.isEnabled = true
        isFetching = false
    }

    private fun loadSettings() {
        val selectedFolders = SettingsManager.getScanFolders(this)
        val scanAll = selectedFolders.isEmpty()
        
        binding.switchScanAll.isChecked = scanAll
        binding.layoutSelectedFolders.visibility = if (scanAll) View.GONE else View.VISIBLE
        
        updateSelectedFolders()
        

    }

    private fun updateSelectedFolders() {
        val folders = SettingsManager.getScanFolders(this).toList().sorted()
        selectedFolderAdapter.setFolders(folders)
        
        // Update scan all switch based on folders
        if (folders.isEmpty() && !binding.switchScanAll.isChecked) {
            binding.switchScanAll.isChecked = true
        }
    }

    private fun removeFolder(folderPath: String) {
        SettingsManager.removeScanFolder(this, folderPath)
        updateSelectedFolders()
        hasChanges = true
        
        // If no folders left, switch to scan all
        if (SettingsManager.getScanFolders(this).isEmpty()) {
            binding.switchScanAll.isChecked = true
        }
    }

    private fun showFolderPickerDialog() {
        // Show loading
        val loadingDialog = MaterialAlertDialogBuilder(this)
            .setView(layoutInflater.inflate(R.layout.dialog_loading, null))
            .setCancelable(false)
            .create()
        loadingDialog.show()
        
        // Load folders in background
        lifecycleScope.launch(Dispatchers.IO) {
            val folders = MusicScanner.getMusicFolders(this@SettingsActivity)
            
            withContext(Dispatchers.Main) {
                loadingDialog.dismiss()
                
                if (folders.isEmpty()) {
                    Toast.makeText(this@SettingsActivity, "Không tìm thấy thư mục nhạc nào", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                
                showFolderSelectionDialog(folders)
            }
        }
    }

    private fun showFolderSelectionDialog(folders: List<MusicScanner.MusicFolder>) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_folder_picker, null)
        val recyclerView = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvFolders)
        
        val currentSelected = SettingsManager.getScanFolders(this).toMutableSet()
        
        val adapter = FolderPickerAdapter { folder ->
            if (currentSelected.contains(folder.path)) {
                currentSelected.remove(folder.path)
            } else {
                currentSelected.add(folder.path)
            }
            (recyclerView.adapter as FolderPickerAdapter).setSelectedPaths(currentSelected)
        }
        
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        adapter.setFolders(folders)
        adapter.setSelectedPaths(currentSelected)
        
        MaterialAlertDialogBuilder(this)
            .setTitle("Chọn thư mục")
            .setView(dialogView)
            .setPositiveButton("Xác nhận") { _, _ ->
                SettingsManager.setScanFolders(this, currentSelected)
                updateSelectedFolders()
                hasChanges = true
                
                // Update switch state
                if (currentSelected.isNotEmpty()) {
                    binding.switchScanAll.isChecked = false
                    binding.layoutSelectedFolders.visibility = View.VISIBLE
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    override fun finish() {
        if (hasChanges) {
            setResult(RESULT_OK)
        }
        super.finish()
    }

    override fun onBackPressed() {
        if (isFetching) {
            Toast.makeText(this, "Đang tải cover art, vui lòng chờ...", Toast.LENGTH_SHORT).show()
        } else {
            super.onBackPressed()
        }
    }

    private fun showUserGuide() {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.Theme_VisualMP_BottomSheet)
        val dialogView = layoutInflater.inflate(R.layout.dialog_user_guide, null)
        dialog.setContentView(dialogView)
        
        dialogView.findViewById<View>(R.id.btnGotIt)?.setOnClickListener {
            dialog.dismiss()
        }
        
        dialog.show()
    }

    companion object {
        const val REQUEST_CODE = 100
    }
}

