package org.daylight.mediakeyboard

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager

class MediaKeyboardService : InputMethodService() {

    companion object {
        private const val TAG = "MediaKB"
        private const val MIN_COLUMNS = 2
        private const val TARGET_TILE_WIDTH_DP = 100 // Желаемая ширина плитки
    }

    // Теперь у нас есть список ссылок. Соотношение сторон пока поставим заглушкой.
    // В реальном приложении вы будете получать его при загрузке.
    private val demoItems = listOf(
        MediaItem("https://klipy.com/gifs/cat-hitting-blanket", aspectRatio = 1.2f),
        MediaItem("https://klipy.com/gifs/cat-wiggle-15", aspectRatio = 0.8f),
        MediaItem("https://klipy.com/gifs/cat-cat-crazy-2", aspectRatio = 1.5f),
        MediaItem("https://klipy.com/gifs/cat-tuxedo-2", aspectRatio = 1.0f),
        MediaItem("https://klipy.com/gifs/sergal-4", aspectRatio = 0.9f),
        MediaItem("https://klipy.com/gifs/cat-sniff-7CA", aspectRatio = 1.1f),
        MediaItem("https://klipy.com/gifs/cat-kitty-OzS", aspectRatio = 0.7f),
        MediaItem("https://klipy.com/gifs/stewly-blume-17", aspectRatio = 1.3f),
        MediaItem("https://klipy.com/gifs/brain-damage-cat", aspectRatio = 1.0f),
        // Discord ссылки (в реальном приложении они могут не работать из-за авторизации)
        MediaItem("https://cdn.discordapp.com/attachments/1054140785731309669/1444342699426713681/dragon-cute-paws.gif", aspectRatio = 1.0f),
        MediaItem("https://media.discordapp.net/attachments/1023212236921118811/1333008904371699743/attachment.gif", aspectRatio = 1.0f),
    )

    override fun onCreateInputView(): View {
        Log.d(TAG, "onCreateInputView called")
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)

        val grid = view.findViewById<RecyclerView>(R.id.mediaGrid)

        // --- Расчет количества столбцов и ширины плитки ---
        val displayMetrics = resources.displayMetrics
        val screenWidthDp = displayMetrics.widthPixels / displayMetrics.density
        val targetWidthPx = (TARGET_TILE_WIDTH_DP * displayMetrics.density).toInt()

        // Количество столбцов = ширина экрана / желаемая ширина, но не менее MIN_COLUMNS
        val columns = maxOf(MIN_COLUMNS, (displayMetrics.widthPixels / targetWidthPx))

        // Ширина одной плитки = (ширина экрана - отступы) / количество столбцов
        // Отступы: padding у RecyclerView (4dp) * 2 + padding у item (2dp) * 2 * columns
        // Упростим: просто разделим ширину на columns, а отступы учтем в item_media.xml
        val columnWidth = displayMetrics.widthPixels / columns

        Log.d(TAG, "Screen width: ${displayMetrics.widthPixels}px, columns: $columns, column width: ${columnWidth}px")

        // --- Настройка StaggeredGridLayoutManager ---
        // Orientation: VERTICAL (скролл вниз), spanCount: columns
        val layoutManager = StaggeredGridLayoutManager(columns, StaggeredGridLayoutManager.VERTICAL)
        // Чтобы элементы не "прыгали" при скролле
        layoutManager.gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_NONE

        grid.layoutManager = layoutManager
        grid.adapter = MediaAdapter(demoItems, { item ->
            insertText(item.url)
        }, columnWidth)

        return view
    }

    private fun insertText(text: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(text, 1)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.d(TAG, "onStartInputView inputType=${info?.inputType} restarting=$restarting")
    }
}
