package com.skul9x.visualmp.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.skul9x.visualmp.databinding.ItemSelectedFolderBinding

class SelectedFolderAdapter(
    private val onDelete: (String) -> Unit
) : RecyclerView.Adapter<SelectedFolderAdapter.FolderViewHolder>() {

    private var folders: List<String> = emptyList()

    fun setFolders(newFolders: List<String>) {
        folders = newFolders
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        val binding = ItemSelectedFolderBinding.inflate(
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
        private val binding: ItemSelectedFolderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(folderPath: String) {
            binding.apply {
                tvFolderName.text = folderPath.substringAfterLast("/")
                tvFolderPath.text = folderPath
                
                btnDelete.setOnClickListener {
                    onDelete(folderPath)
                }
            }
        }
    }
}
