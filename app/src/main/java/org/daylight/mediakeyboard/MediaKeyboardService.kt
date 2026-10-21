package org.daylight.mediakeyboard

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.daylight.mediakeyboard.data.ApiKeyStore
import org.daylight.mediakeyboard.data.AppDatabase
import org.daylight.mediakeyboard.data.MediaRepository
import org.daylight.mediakeyboard.data.MediaResolution
import org.daylight.mediakeyboard.data.ResolverRegistry

class MediaKeyboardService : InputMethodService() {
    public final val TAG = "MediaKeyboard";
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: MediaRepository
    private lateinit var adapter: MediaAdapter
    private val entries = mutableListOf<MediaEntry>()

    override fun onCreate() {
        super.onCreate()
        val registry = ResolverRegistry.createDefault()
        repository = MediaRepository(
            dao = AppDatabase.get(this).mediaDao(),
            registry = registry,
            apiKeyStore = ApiKeyStore.get(this)
        )
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        val grid = view.findViewById<RecyclerView>(R.id.mediaGrid)

        val dm = resources.displayMetrics
        Log.d(TAG, "displayMetrics.widthPixels=${dm.widthPixels}, density=${dm.density}")
        val targetPx = (100 * dm.density).toInt()
        val columns = maxOf(2, dm.widthPixels / targetPx)
        val columnWidth = dm.widthPixels / columns
        Log.d(TAG, "columns=$columns, columnWidth=$columnWidth")

        val lm = StaggeredGridLayoutManager(columns, StaggeredGridLayoutManager.VERTICAL)
        lm.gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_NONE
        grid.layoutManager = lm

        // Заполняем исходные URL
        entries.clear()
        demoUrls.forEach { entries.add(MediaEntry(it)) }

        adapter = MediaAdapter(entries, { entry -> onTileClick(entry) }, columnWidth)
        grid.adapter = adapter

        // Запускаем резолв по одной ссылке
        entries.forEachIndexed { index, entry ->
            scope.launch {
                val res = repository.resolve(entry.sourceUrl)
                entries[index] = entry.copy(resolution = res)
                adapter.notifyItemChanged(index)
            }
        }

        return view
    }

    private fun onTileClick(entry: MediaEntry) {
        val res = entry.resolution
        if (res is MediaResolution.Ready) {
            insertText(res.contentUrl)
        } else {
            // Подсказка пользователю — можно через Toast, но в IME он не всегда виден.
            // Лучше — визуально в плитке (уже сделано оверлеем).
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private val demoUrls = listOf(
        "https://klipy.com/gifs/cat-hitting-blanket",
        "https://klipy.com/gifs/cat-wiggle-15",
//        "https://klipy.com/gifs/cat-cat-crazy-2",
//        "https://klipy.com/gifs/cat-tuxedo-2",
//        "https://klipy.com/gifs/sergal-4",
//        "https://klipy.com/gifs/cat-sniff-7CA",
//        "https://klipy.com/gifs/cat-kitty-OzS",
//        "https://klipy.com/gifs/stewly-blume-17",
//        "https://klipy.com/gifs/brain-damage-cat",
//        "https://cdn.discordapp.com/attachments/1054140785731309669/1444342699426713681/dragon-cute-paws.gif",
//        "https://media.discordapp.net/attachments/1023212236921118811/1333008904371699743/attachment.gif",
    )

    private fun insertText(text: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(text, 1)
    }
}
