package com.skul9x.visualmp.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.skul9x.visualmp.model.Song
import com.skul9x.visualmp.util.MetadataEditor
import com.skul9x.visualmp.util.MusicScanner

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _songs = MutableLiveData<List<Song>>()
    val songs: LiveData<List<Song>> = _songs
    
    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    // Check if initial load is done
    val isLoaded: Boolean
        get() = _songs.value != null

    fun loadSongs(forceReload: Boolean = false) {
        if (isLoaded && !forceReload) return
        
        _isLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
             val context = getApplication<Application>().applicationContext
             val scanned = MusicScanner.scanMusic(context)
             val processed = MetadataEditor.applyCustomMetadataToList(context, scanned)
             _songs.postValue(processed)
             _isLoading.postValue(false)
        }
    }
    
    fun updateSong(song: Song) {
        val currentList = _songs.value?.toMutableList() ?: return
        val index = currentList.indexOfFirst { it.id == song.id }
        if (index != -1) {
            currentList[index] = song
            _songs.postValue(currentList)
        }
    }
}
