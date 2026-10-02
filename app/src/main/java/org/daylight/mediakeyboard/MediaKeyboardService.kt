package org.daylight.mediakeyboard

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MediaKeyboardService : InputMethodService() {

    companion object {
        private const val TAG = "MediaKB"
    }

    private val demoItems = listOf(
        MediaItem("Кот", "https://example.com/cat.gif"),
        MediaItem("Собака", "https://example.com/dog.gif"),
        MediaItem("Реакция", "https://example.com/wow.gif"),
        MediaItem("Аплодисменты", "https://example.com/clap.gif"),
        MediaItem("Смех", "https://example.com/lol.gif"),
        MediaItem("Плач", "https://example.com/cry.gif"),
    )

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
    }

    override fun onCreateInputView(): View {
        Log.d(TAG, "onCreateInputView called")
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)

        val grid = view.findViewById<RecyclerView>(R.id.mediaGrid)
        grid.layoutManager = GridLayoutManager(this, 3)
        grid.adapter = MediaAdapter(demoItems) { item ->
            insertText(item.url)
        }

        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.d(TAG, "onStartInputView inputType=${info?.inputType} restarting=$restarting")
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        Log.d(TAG, "onStartInput")
    }

    private fun insertText(text: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(text, 1)
    }
}