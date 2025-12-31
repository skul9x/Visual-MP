package com.skul9x.visualmp.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.skul9x.visualmp.databinding.ItemFolderPickerBinding
import com.skul9x.visualmp.util.MusicScanner

class FolderPickerAdapter(
    private val onFolderClick: (MusicScanner.MusicFolder) -> Unit
) : RecyclerView.Adapter<FolderPickerAdapter.FolderViewHolder>() {

    private var folders: List<MusicScanner.MusicFolder> = emptyList()
    private var selectedPaths: Set<String> = emptySet()

    fun setFolders(newFolders: List<MusicScanner.MusicFolder>) {
        folders = newFolders
        notifyDataSetChanged()
    }

    fun setSelectedPaths(paths: Set<String>) {
        selectedPaths = paths
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        val binding = ItemFolderPickerBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return FolderViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
        holder.bind(folders[position])
    }

    override fun getItemCount(): Int = folders.size

    inner class FolderViewHolder(
        private val binding: ItemFolderPickerBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(folder: MusicScanner.MusicFolder) {
            binding.apply {
                tvFolderName.text = folder.name
                tvSongCount.text = "${folder.songCount} bài hát"
                
                val isSelected = selectedPaths.contains(folder.path)
                ivSelected.visibility = if (isSelected) View.VISIBLE else View.GONE
                
                // Highlight selected items
                if (isSelected) {
                    ivFolder.setColorFilter(root.context.getColor(com.skul9x.visualmp.R.color.purple_primary))
                } else {
                    ivFolder.setColorFilter(root.context.getColor(com.skul9x.visualmp.R.color.text_secondary))
                }
                
                root.setOnClickListener {
                    onFolderClick(folder)
                }
            }
        }
    }
}
