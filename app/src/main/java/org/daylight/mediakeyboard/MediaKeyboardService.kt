package org.daylight.mediakeyboard

import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.room.InvalidationTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.daylight.mediakeyboard.data.ApiKeyStore
import org.daylight.mediakeyboard.data.AppDatabase
import org.daylight.mediakeyboard.data.LocalMediaCacher
import org.daylight.mediakeyboard.data.MediaRepository
import org.daylight.mediakeyboard.data.MediaResolution
import org.daylight.mediakeyboard.data.ResolverRegistry
import org.daylight.mediakeyboard.data.dto.CategoryEntity
import org.daylight.mediakeyboard.data.dto.MediaEntity

class MediaKeyboardService : InputMethodService() {
    companion object {
        const val TAG = "MediaKeyboard"
        const val FILTER_ALL = -1L
        const val FILTER_FAVORITES = -2L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: MediaRepository
    private lateinit var adapter: MediaAdapter
    private val entries = mutableListOf<MediaEntry>()

    private var groupsContainer: LinearLayout? = null
    private var panelOverlay: ViewGroup? = null
    private var selectedCategoryId: Long = FILTER_ALL
    private var columnWidth: Int = 200

    private var dbObserver: InvalidationTracker.Observer? = null

    override fun onCreate() {
        super.onCreate()
        val registry = ResolverRegistry.createDefault()
        val localCacher = LocalMediaCacher(this)
        val db = AppDatabase.get(this)
        repository = MediaRepository(
            dao = db.mediaDao(),
            registry = registry,
            apiKeyStore = ApiKeyStore.get(this),
            localCacher = localCacher
        )

        dbObserver = object : InvalidationTracker.Observer(
            arrayOf("media_cache", "categories", "media_category_cross_ref")
        ) {
            override fun onInvalidated(tables: Set<String>) {
                scope.launch {
                    refreshCategoriesAndMedia()
                }
            }
        }
        dbObserver?.let { db.invalidationTracker.addObserver(it) }
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        groupsContainer = view.findViewById(R.id.groupsContainer)
        panelOverlay = view.findViewById(R.id.panelOverlay)
        val grid = view.findViewById<RecyclerView>(R.id.mediaGrid)

        val dm = resources.displayMetrics
        Log.d(TAG, "displayMetrics.widthPixels=${dm.widthPixels}, density=${dm.density}")
        val targetPx = (100 * dm.density).toInt()
        val columns = maxOf(2, dm.widthPixels / targetPx)
        columnWidth = dm.widthPixels / columns

        val lm = StaggeredGridLayoutManager(columns, StaggeredGridLayoutManager.VERTICAL)
        lm.gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_NONE
        grid.layoutManager = lm

        adapter = MediaAdapter(
            items = entries,
            onClick = { entry -> onTileClick(entry) },
            columnWidth = columnWidth,
            onLongClick = { entry -> showItemOptionsDialog(entry) }
        )
        grid.adapter = adapter

        refreshCategoriesAndMedia()

        return view
    }

    private fun showOverlay(contentView: View) {
        val overlay = panelOverlay ?: return
        overlay.removeAllViews()
        overlay.addView(contentView)
        overlay.visibility = View.VISIBLE
    }

    private fun hideOverlay() {
        val overlay = panelOverlay ?: return
        overlay.removeAllViews()
        overlay.visibility = View.GONE
    }

    private fun refreshCategoriesAndMedia() {
        scope.launch {
            loadCategoryTabs()
            loadMediaForSelectedCategory()
        }
    }

    private suspend fun loadCategoryTabs() {
        val container = groupsContainer ?: return
        container.removeAllViews()

        val categories = repository.getAllCategories()

        addTabChip("Все", FILTER_ALL)
        addTabChip("Избранное", FILTER_FAVORITES)

        categories.forEach { cat ->
            addTabChip(cat.name, cat.id)
        }

        addActionChip("+ Ссылка") { showAddMediaDialog() }
        addActionChip("Категории") { showManageCategoriesDialog() }
        addActionChip("Приложение") { openMainActivity() }
    }

    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun addTabChip(label: String, categoryId: Long) {
        val container = groupsContainer ?: return
        val chip = LayoutInflater.from(this)
            .inflate(R.layout.item_category_chip, container, false) as TextView
        chip.text = label
        chip.isSelected = (selectedCategoryId == categoryId)

        chip.setOnClickListener {
            selectedCategoryId = categoryId
            hideOverlay()
            scope.launch {
                loadCategoryTabs()
                loadMediaForSelectedCategory()
            }
        }
        container.addView(chip)
    }

    private fun addActionChip(label: String, onClick: () -> Unit) {
        val container = groupsContainer ?: return
        val chip = LayoutInflater.from(this)
            .inflate(R.layout.item_category_chip, container, false) as TextView
        chip.text = label
        chip.isSelected = false
        chip.setOnClickListener { onClick() }
        container.addView(chip)
    }

    private suspend fun loadMediaForSelectedCategory() {
        val prefs = getSharedPreferences("media_keyboard_prefs", MODE_PRIVATE)
        val isDemoSeeded = prefs.getBoolean("demo_seeded", false)

        var mediaList: List<MediaEntity> = when (selectedCategoryId) {
            FILTER_ALL -> repository.getAllSaved()
            FILTER_FAVORITES -> repository.getFavorites()
            else -> repository.getMediaForCategory(selectedCategoryId)
        }

        // Демо ссылки добавляем только один раз при самом первом запуске
        if (!isDemoSeeded && mediaList.isEmpty()) {
            demoUrls.forEach { url ->
                repository.resolve(url)
            }
            prefs.edit().putBoolean("demo_seeded", true).apply()
            mediaList = repository.getAllSaved()
        }

        entries.clear()
        mediaList.forEach { entity ->
            entries.add(
                MediaEntry(
                    sourceUrl = entity.sourceUrl,
                    resolution = if (entity.failed) {
                        MediaResolution.Failed(entity.serviceId ?: "unknown", entity.failReason)
                    } else {
                        MediaResolution.Ready(
                            sourceUrl = entity.sourceUrl,
                            previewUrl = entity.localPreviewPath ?: entity.previewUrl,
                            contentUrl = entity.contentUrl,
                            aspectRatio = entity.aspectRatio,
                            title = entity.title,
                            isFavorite = entity.isFavorite
                        )
                    }
                )
            )
        }
        adapter.notifyDataSetChanged()

        entries.forEachIndexed { index, entry ->
            if (entry.resolution == null) {
                scope.launch {
                    val res = repository.resolve(entry.sourceUrl)
                    entries[index] = entry.copy(resolution = res)
                    adapter.notifyItemChanged(index)
                }
            }
        }
    }

    // --- Панель Добавления Новой Ссылки ---

    private fun showAddMediaDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_media, null)
        val inputUrl = dialogView.findViewById<EditText>(R.id.inputUrl)
        val categoriesContainer = dialogView.findViewById<LinearLayout>(R.id.categoriesContainer)
        val btnAdd = dialogView.findViewById<Button>(R.id.btnAdd)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        val checkBoxes = mutableListOf<Pair<Long, CheckBox>>()

        scope.launch {
            val categories = repository.getAllCategories()
            categories.forEach { cat ->
                val cb = CheckBox(this@MediaKeyboardService).apply {
                    text = cat.name
                    setTextColor(0xFFFFFFFF.toInt())
                }
                categoriesContainer.addView(cb)
                checkBoxes.add(cat.id to cb)
            }
        }

        btnCancel.setOnClickListener { hideOverlay() }

        btnAdd.setOnClickListener {
            var url = inputUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "https://$url"
                }
                val finalUrl = url
                scope.launch {
                    try {
                        val res = repository.resolve(finalUrl, forceReload = true)
                        if (res is MediaResolution.Ready) {
                            val selectedCatIds = checkBoxes.filter { it.second.isChecked }.map { it.first }
                            if (selectedCatIds.isNotEmpty()) {
                                repository.setCategoriesForMedia(finalUrl, selectedCatIds)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error adding media URL $finalUrl", e)
                    }
                    hideOverlay()
                    refreshCategoriesAndMedia()
                }
            }
        }

        showOverlay(dialogView)
    }

    // --- Панель Управления Категориями ---

    private fun showManageCategoriesDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_manage_categories, null)
        val inputName = dialogView.findViewById<EditText>(R.id.inputCategoryName)
        val btnAddCategory = dialogView.findViewById<Button>(R.id.btnAddCategory)
        val existingContainer = dialogView.findViewById<LinearLayout>(R.id.existingCategoriesContainer)
        val btnClose = dialogView.findViewById<Button>(R.id.btnClose)

        fun reloadCategoriesList() {
            scope.launch {
                existingContainer.removeAllViews()
                val categories = repository.getAllCategories()
                categories.forEach { cat ->
                    val row = LinearLayout(this@MediaKeyboardService).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, 8, 0, 8)
                    }

                    val nameTv = TextView(this@MediaKeyboardService).apply {
                        text = cat.name
                        setTextColor(0xFFFFFFFF.toInt())
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }

                    val btnDelete = Button(this@MediaKeyboardService).apply {
                        text = "Удалить"
                        textSize = 11f
                        setOnClickListener {
                            scope.launch {
                                repository.deleteCategory(cat.id)
                                reloadCategoriesList()
                                loadCategoryTabs()
                            }
                        }
                    }

                    row.addView(nameTv)
                    row.addView(btnDelete)
                    existingContainer.addView(row)
                }
            }
        }

        btnAddCategory.setOnClickListener {
            val name = inputName.text.toString().trim()
            if (name.isNotEmpty()) {
                scope.launch {
                    repository.addCategory(name)
                    inputName.text.clear()
                    reloadCategoriesList()
                    loadCategoryTabs()
                }
            }
        }

        btnClose.setOnClickListener {
            hideOverlay()
            refreshCategoriesAndMedia()
        }

        reloadCategoriesList()
        showOverlay(dialogView)
    }

    // --- Панель Опций Элемента (Long Click) ---

    private fun showItemOptionsDialog(entry: MediaEntry) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_entry_options, null)
        val titleText = dialogView.findViewById<TextView>(R.id.titleText)
        val btnToggleFav = dialogView.findViewById<Button>(R.id.btnToggleFavorite)
        val btnEditCat = dialogView.findViewById<Button>(R.id.btnEditCategories)
        val btnDelete = dialogView.findViewById<Button>(R.id.btnDeleteMedia)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelOptions)

        val title = (entry.resolution as? MediaResolution.Ready)?.title ?: entry.sourceUrl
        titleText.text = title

        btnCancel.setOnClickListener { hideOverlay() }

        btnToggleFav.setOnClickListener {
            scope.launch {
                repository.toggleFavorite(entry.sourceUrl)
                hideOverlay()
                refreshCategoriesAndMedia()
            }
        }

        btnEditCat.setOnClickListener {
            hideOverlay()
            showEditItemCategoriesDialog(entry)
        }

        btnDelete.setOnClickListener {
            scope.launch {
                repository.deleteMedia(entry.sourceUrl)
                hideOverlay()
                refreshCategoriesAndMedia()
            }
        }

        showOverlay(dialogView)
    }

    // --- Панель Изменения Категорий для Элемента ---

    private fun showEditItemCategoriesDialog(entry: MediaEntry) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_media, null)
        val inputUrl = dialogView.findViewById<EditText>(R.id.inputUrl)
        val categoriesContainer = dialogView.findViewById<LinearLayout>(R.id.categoriesContainer)
        val btnAdd = dialogView.findViewById<Button>(R.id.btnAdd)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        inputUrl.setText(entry.sourceUrl)
        inputUrl.isEnabled = false
        btnAdd.text = "Сохранить"

        val checkBoxes = mutableListOf<Pair<Long, CheckBox>>()

        scope.launch {
            val categories = repository.getAllCategories()
            val assignedCatIds = repository.getCategoryIdsForMedia(entry.sourceUrl).toSet()

            categories.forEach { cat ->
                val cb = CheckBox(this@MediaKeyboardService).apply {
                    text = cat.name
                    setTextColor(0xFFFFFFFF.toInt())
                    isChecked = assignedCatIds.contains(cat.id)
                }
                categoriesContainer.addView(cb)
                checkBoxes.add(cat.id to cb)
            }
        }

        btnCancel.setOnClickListener { hideOverlay() }

        btnAdd.setOnClickListener {
            scope.launch {
                val selectedCatIds = checkBoxes.filter { it.second.isChecked }.map { it.first }
                repository.setCategoriesForMedia(entry.sourceUrl, selectedCatIds)
                hideOverlay()
                refreshCategoriesAndMedia()
            }
        }

        showOverlay(dialogView)
    }

    private fun onTileClick(entry: MediaEntry) {
        val res = entry.resolution
        if (res is MediaResolution.Ready) {
            insertText(res.sourceUrl)
            Log.d(TAG, "Content URL: " + res.contentUrl)
        }
    }

    override fun onDestroy() {
        dbObserver?.let { AppDatabase.get(this).invalidationTracker.removeObserver(it) }
        scope.cancel()
        super.onDestroy()
    }

    private val demoUrls = listOf(
        "https://klipy.com/gifs/cat-hitting-blanket",
        "https://klipy.com/gifs/cat-wiggle-15",
        "https://cdn.discordapp.com/attachments/1054140785731309669/1444342699426713681/dragon-cute-paws.gif"
    )

    override fun onStartInputView(editorInfo: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)
        refreshCategoriesAndMedia()
    }

    private fun insertText(text: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(text, 1)
    }
}
