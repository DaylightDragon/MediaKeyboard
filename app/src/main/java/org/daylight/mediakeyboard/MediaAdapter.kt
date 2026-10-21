package org.daylight.mediakeyboard

import android.graphics.drawable.Animatable
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.vectordrawable.graphics.drawable.Animatable2Compat
import coil3.gif.onAnimationStart
import coil3.load
import coil3.request.allowHardware
import coil3.request.error
import coil3.request.placeholder
import org.daylight.mediakeyboard.data.MediaResolution

class MediaAdapter(
    private val items: List<MediaEntry>,
    private val onClick: (MediaEntry) -> Unit,
    private val columnWidth: Int
) : RecyclerView.Adapter<MediaAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_media, parent, false)
        return VH(v)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val overlay: View = v.findViewById(R.id.overlay)
        val stateIcon: ImageView = v.findViewById(R.id.stateIcon)   // ← добавили
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

        // Приводим к типу, который понимает StaggeredGridLayoutManager
        val lp = holder.itemView.layoutParams
                as StaggeredGridLayoutManager.LayoutParams
        lp.width = columnWidth
        lp.height = (columnWidth / ratio).toInt().coerceAtLeast(40) // минимум
        holder.itemView.layoutParams = lp

        when (res) {
            is MediaResolution.Ready -> {
                Log.d("Adapter", "  -> Ready, load ${res.contentUrl}")
                holder.overlay.visibility = View.GONE
                holder.thumb.load(res.contentUrl) {
                    placeholder(R.drawable.ic_placeholder)
                    error(R.drawable.ic_error)
                    onAnimationStart {
                        Log.d("MediaKeyboard", "Animation Started")
                    }
                    listener(
                        onSuccess = { _, result ->
                            // Принудительно запускаем анимацию
                            val drawable = result.image
                            if (drawable is Animatable2Compat) {
                                drawable.start()
                            } else if (drawable is Animatable) {
                                drawable.start()
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
    }

    override fun getItemCount(): Int = items.size
}
