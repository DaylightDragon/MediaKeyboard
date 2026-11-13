package org.daylight.mediakeyboard

import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedImageDrawable
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import coil3.asDrawable
import coil3.load
import coil3.request.error
import coil3.request.placeholder
import org.daylight.mediakeyboard.data.MediaResolution
import java.io.File

class MediaAdapter(
    private val items: List<MediaEntry>,
    private val onClick: (MediaEntry) -> Unit,
    private val columnWidth: Int,
    private val onLongClick: ((MediaEntry) -> Unit)? = null
) : RecyclerView.Adapter<MediaAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_media, parent, false)
        return VH(v)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val overlay: View = v.findViewById(R.id.overlay)
        val stateIcon: ImageView = v.findViewById(R.id.stateIcon)
        val overlayText: TextView = v.findViewById(R.id.overlayText)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        val res = entry.resolution
        Log.d("Adapter", "bind[$position] url=${entry.sourceUrl} res=$res")
        val ratio = when (res) {
            is MediaResolution.Ready -> res.aspectRatio
            else -> 1f
        }

        val lp = holder.itemView.layoutParams as StaggeredGridLayoutManager.LayoutParams
        lp.width = columnWidth
        lp.height = (columnWidth / ratio).toInt().coerceAtLeast(40)
        holder.itemView.layoutParams = lp

        when (res) {
            is MediaResolution.Ready -> {
                Log.d("Adapter", "  -> Ready, load previewUrl=${res.previewUrl}")
                holder.overlay.visibility = View.GONE
                holder.thumb.load(res.previewUrl) {
                    placeholder(R.drawable.ic_placeholder)
                    error(R.drawable.ic_error)
                    listener(
                        onSuccess = { _, result ->
                            val drawable = holder.thumb.drawable ?: result.image.asDrawable(holder.thumb.resources)
                            Log.d("MediaAdapter", "onSuccess: drawable=${drawable::class.java.name}, isAnimatable=${drawable is Animatable}")
                            if (drawable is AnimatedImageDrawable) {
                                drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                                drawable.start()
                            } else if (drawable is Animatable) {
                                drawable.start()
                            }
                        },
                        onError = { _, result ->
                            Log.e("MediaAdapter", "onError: ${result.throwable.message}", result.throwable)
                            if (res.previewUrl.startsWith("file://")) {
                                val path = res.previewUrl.removePrefix("file://")
                                val corruptFile = File(path)
                                if (corruptFile.exists()) {
                                    Log.w("MediaAdapter", "Deleting corrupt cached file: $path")
                                    corruptFile.delete()
                                }
                                holder.thumb.load(res.contentUrl) {
                                    placeholder(R.drawable.ic_placeholder)
                                    error(R.drawable.ic_error)
                                }
                            }
                        }
                    )
                }
            }
            is MediaResolution.MissingKey -> {
                Log.d("Adapter", "  -> MissingKey: ${res.displayName}")
                holder.overlay.visibility = View.VISIBLE
                holder.stateIcon.setImageResource(R.drawable.ic_no_key)
                holder.stateIcon.visibility = View.VISIBLE
                holder.overlayText.text = "Нужен ключ ${res.displayName}"
            }
            is MediaResolution.Failed -> {
                Log.d("Adapter", "  -> Failed: ${res.reason}")
                holder.overlay.visibility = View.VISIBLE
                holder.stateIcon.setImageResource(R.drawable.ic_error)
                holder.stateIcon.visibility = View.VISIBLE
                holder.overlayText.text = res.reason?.take(40) ?: "Ошибка"
            }
            null -> {
                Log.d("Adapter", "  -> null (pending)")
                holder.overlay.visibility = View.VISIBLE
                holder.stateIcon.visibility = View.GONE
                holder.overlayText.text = "…"
            }
        }

        holder.itemView.setOnClickListener { onClick(entry) }
        holder.itemView.setOnLongClickListener {
            onLongClick?.invoke(entry)
            true
        }
    }

    override fun getItemCount(): Int = items.size
}
