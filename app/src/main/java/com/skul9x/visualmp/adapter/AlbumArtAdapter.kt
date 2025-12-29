package com.skul9x.visualmp.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.signature.ObjectKey
import com.skul9x.visualmp.R
import com.skul9x.visualmp.model.Song
import com.skul9x.visualmp.util.CoverArtFetcher
import java.io.File

class AlbumArtAdapter(private var songs: List<Song> = emptyList()) : RecyclerView.Adapter<AlbumArtAdapter.AlbumArtViewHolder>() {

    class AlbumArtViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.ivAlbumArt)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumArtViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_album_art, parent, false)
        return AlbumArtViewHolder(view)
    }

    override fun onBindViewHolder(holder: AlbumArtViewHolder, position: Int) {
        val song = songs[position]
        val context = holder.itemView.context

        val customCoverPath = CoverArtFetcher.getCustomCoverArtPath(context, song.id)
        val imageSource: Any? = if (customCoverPath != null) {
            File(customCoverPath)
        } else {
            song.albumArtUri
        }

        Glide.with(context)
            .load(imageSource)
            .signature(ObjectKey(CoverArtFetcher.getCoverArtSignature(context, song.id)))
            .placeholder(R.drawable.ic_music_note)
            .error(R.drawable.ic_music_note)
            .transition(DrawableTransitionOptions.withCrossFade())
            .centerCrop()
            .into(holder.imageView)
    }

    override fun getItemCount(): Int = songs.size

    fun submitList(newSongs: List<Song>) {
        songs = newSongs
        notifyDataSetChanged()
    }
}
