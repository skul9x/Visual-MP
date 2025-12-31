package com.skul9x.visualmp.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Quản lý cài đặt của app, bao gồm các thư mục quét nhạc
 */
object SettingsManager {
    
    private const val PREFS_NAME = "visualmp_settings"
    private const val KEY_SCAN_FOLDERS = "scan_folders"
    private const val KEY_SCAN_ALL = "scan_all_folders"
    private const val KEY_GEMINI_API_KEY = "gemini_api_key"
    
    // ... existings methods ...

    fun getGeminiApiKey(context: Context): String {
        return getPrefs(context).getString(KEY_GEMINI_API_KEY, "") ?: ""
    }

    fun setGeminiApiKey(context: Context, apiKey: String) {
        getPrefs(context).edit().putString(KEY_GEMINI_API_KEY, apiKey).apply()
    }
    
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * Lấy danh sách thư mục được chọn để quét
     */
    fun getScanFolders(context: Context): Set<String> {
        return getPrefs(context).getStringSet(KEY_SCAN_FOLDERS, emptySet()) ?: emptySet()
    }
    
    /**
     * Lưu danh sách thư mục được chọn
     */
    fun setScanFolders(context: Context, folders: Set<String>) {
        getPrefs(context).edit()
            .putStringSet(KEY_SCAN_FOLDERS, folders)
            .apply()
    }
    
    /**
     * Thêm một thư mục vào danh sách quét
     */
    fun addScanFolder(context: Context, folderPath: String) {
        val folders = getScanFolders(context).toMutableSet()
        folders.add(folderPath)
        setScanFolders(context, folders)
    }
    
    /**
     * Xóa một thư mục khỏi danh sách quét
     */
    fun removeScanFolder(context: Context, folderPath: String) {
        val folders = getScanFolders(context).toMutableSet()
        folders.remove(folderPath)
        setScanFolders(context, folders)
    }
    
    /**
     * Kiểm tra xem có quét tất cả thư mục không
     */
    fun isScanAllFolders(context: Context): Boolean {
        val folders = getScanFolders(context)
        return folders.isEmpty() // Nếu không có folder nào được chọn, quét tất cả
    }
    
    /**
     * Reset về quét tất cả thư mục
     */
    fun resetToScanAll(context: Context) {
        setScanFolders(context, emptySet())
    }
}
