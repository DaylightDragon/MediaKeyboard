package org.daylight.mediakeyboard

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import coil3.load // Предполагаем, что вы добавите Coil для загрузки картинок

class MediaAdapter(
    private val items: List<MediaItem>,
    private val onClick: (MediaItem) -> Unit,
    private val columnWidth: Int // Ширина одной колонки в пикселях
) : RecyclerView.Adapter<MediaAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_media, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]

        // 1. Устанавливаем ширину и высоту
        val params = holder.itemView.layoutParams
        params.width = columnWidth
        // Высота = ширина / соотношение сторон
        params.height = (columnWidth / item.aspectRatio).toInt()
        holder.itemView.layoutParams = params

        // 2. Загружаем картинку (используем Coil как пример)
        // В реальном приложении здесь будет загрузка по item.previewUrl или item.url
        holder.thumb.load(item.url) {
            // Можно добавить placeholder и error
        }

        // 3. Клик
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = items.size
}
