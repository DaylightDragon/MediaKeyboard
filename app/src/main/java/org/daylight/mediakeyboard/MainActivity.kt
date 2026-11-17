package org.daylight.mediakeyboard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.room.InvalidationTracker
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.daylight.mediakeyboard.data.ApiKeyStore
import org.daylight.mediakeyboard.data.AppDatabase
import org.daylight.mediakeyboard.data.LibraryExporter
import org.daylight.mediakeyboard.data.LocalMediaCacher
import org.daylight.mediakeyboard.data.MediaRepository
import org.daylight.mediakeyboard.data.MediaResolution
import org.daylight.mediakeyboard.data.ResolverRegistry
import org.daylight.mediakeyboard.data.dto.CategoryEntity
import org.daylight.mediakeyboard.data.dto.MediaEntity
import java.util.Collections
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {

    private lateinit var repository: MediaRepository
    private lateinit var adapter: MediaAdapter
    private lateinit var exporter: LibraryExporter
    private val savedEntries = mutableListOf<MediaEntry>()
    private val categoryCheckBoxes = mutableListOf<Pair<Long, CheckBox>>()
    private var dbObserver: InvalidationTracker.Observer? = null

    private var pendingExportCategories = setOf<Long>()

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                try {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        exporter.exportLibrary(pendingExportCategories, out)
                    }
                    Toast.makeText(this@MainActivity, "Экспорт успешно завершен!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e("MainActivity", "Export failed", e)
                    Toast.makeText(this@MainActivity, "Ошибка при экспорте", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                try {
                    val inputStream = contentResolver.openInputStream(uri)
                    if (inputStream != null) {
                        val result = exporter.importLibrary(
                            inputStream = inputStream,
                            onCategoryConflict = { catName ->
                                withContext(Dispatchers.Main) {
                                    suspendCancellableCoroutine { cont ->
                                        AlertDialog.Builder(this@MainActivity)
                                            .setTitle("Категория уже существует")
                                            .setMessage("Категория '$catName' уже есть в медиатеке. Выберите действие:")
                                            .setPositiveButton("Объединить") { _, _ ->
                                                if (cont.isActive) cont.resume(LibraryExporter.ConflictAction.MERGE)
                                            }
                                            .setNegativeButton("Создать как '$catName (1)'") { _, _ ->
                                                if (cont.isActive) cont.resume(LibraryExporter.ConflictAction.CREATE_DUPLICATE)
                                            }
                                            .setCancelable(false)
                                            .show()
                                    }
                                }
                            }
                        )
                        result.fold(
                            onSuccess = {
                                Toast.makeText(this@MainActivity, "Импорт успешно завершен!", Toast.LENGTH_SHORT).show()
                                refreshAllData()
                            },
                            onFailure = { error ->
                                Log.e("MainActivity", "Import error", error)
                                Toast.makeText(this@MainActivity, "Ошибка импорта: ${error.message}", Toast.LENGTH_LONG).show()
                            }
                        )
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Import failed", e)
                    Toast.makeText(this@MainActivity, "Ошибка обработки файла: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val registry = ResolverRegistry.createDefault()
        val localCacher = LocalMediaCacher(this)
        val db = AppDatabase.get(this)
        repository = MediaRepository(
            dao = db.mediaDao(),
            registry = registry,
            apiKeyStore = ApiKeyStore.get(this),
            localCacher = localCacher
        )
        exporter = LibraryExporter(this)

        initStatusSection()
        initKeyboardSettingsSection()
        initBackupSection()
        initMediaGrid()
        initAddMediaSection()
        initCategoryManagementSection()

        // Двусторонняя реактивная синхронизация при изменениях в БД (например из клавиатуры)
        dbObserver = object : InvalidationTracker.Observer(
            arrayOf("media_cache", "categories", "media_category_cross_ref")
        ) {
            override fun onInvalidated(tables: Set<String>) {
                lifecycleScope.launch(Dispatchers.Main.immediate) {
                    refreshAllData()
                }
            }
        }
        dbObserver?.let { db.invalidationTracker.addObserver(it) }

        refreshAllData()
    }

    private fun initStatusSection() {
        val statusText = findViewById<TextView>(R.id.statusText)
        val btnEnable = findViewById<Button>(R.id.btnEnable)
        val btnPick = findViewById<Button>(R.id.btnPick)
        val btnSettings = findViewById<Button>(R.id.btnOpenSettings)

        btnEnable.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        btnPick.setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        updateStatus(statusText)
    }

    private fun initKeyboardSettingsSection() {
        val spinnerColumns = findViewById<Spinner>(R.id.spinnerColumns)
        val options = arrayOf(
            "Авто (по ширине экрана)",
            "2 колонки",
            "3 колонки",
            "4 колонки",
            "5 колонок"
        )
        val adapter = ArrayAdapter(this, R.layout.spinner_item, options)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinnerColumns.adapter = adapter

        val prefs = getSharedPreferences("media_keyboard_prefs", MODE_PRIVATE)
        val currentColumns = prefs.getInt("pref_keyboard_columns", 0)
        val selectedIndex = when (currentColumns) {
            2 -> 1
            3 -> 2
            4 -> 3
            5 -> 4
            else -> 0
        }
        spinnerColumns.setSelection(selectedIndex)

        spinnerColumns.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val targetColumns = when (position) {
                    1 -> 2
                    2 -> 3
                    3 -> 4
                    4 -> 5
                    else -> 0
                }
                prefs.edit().putInt("pref_keyboard_columns", targetColumns).apply()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val switchAutoSend = findViewById<SwitchMaterial>(R.id.switchAutoSend)
        val autoSend = prefs.getBoolean("pref_auto_send", true)
        switchAutoSend.isChecked = autoSend

        switchAutoSend.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("pref_auto_send", isChecked).apply()
        }
    }

    private fun initBackupSection() {
        val btnExport = findViewById<Button>(R.id.btnExportLibrary)
        val btnImport = findViewById<Button>(R.id.btnImportLibrary)

        btnExport.setOnClickListener {
            showExportCategoryPickerDialog()
        }

        btnImport.setOnClickListener {
            importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*"))
        }
    }

    private fun showExportCategoryPickerDialog() {
        lifecycleScope.launch {
            val categories = repository.getAllCategories()
            val container = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 20, 40, 20)
            }

            val cbAll = CheckBox(this@MainActivity).apply {
                text = "Все категории и медиа"
                isChecked = true
            }
            container.addView(cbAll)

            val checkBoxes = mutableListOf<Pair<Long, CheckBox>>()
            categories.forEach { cat ->
                val cb = CheckBox(this@MainActivity).apply {
                    text = cat.name
                    isChecked = true
                    isEnabled = false
                }
                container.addView(cb)
                checkBoxes.add(cat.id to cb)
            }

            cbAll.setOnCheckedChangeListener { _, isChecked ->
                checkBoxes.forEach { it.second.isEnabled = !isChecked }
            }

            AlertDialog.Builder(this@MainActivity)
                .setTitle("Экспорт библиотеки")
                .setMessage("Выберите категории для включения в файл экспорта:")
                .setView(container)
                .setPositiveButton("Экспортировать") { _, _ ->
                    pendingExportCategories = if (cbAll.isChecked) {
                        emptySet()
                    } else {
                        checkBoxes.filter { it.second.isChecked }.map { it.first }.toSet()
                    }
                    exportLauncher.launch("mediakeyboard_export.zip")
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    private fun initMediaGrid() {
        val grid = findViewById<RecyclerView>(R.id.mainMediaGrid)
        val btnClearAll = findViewById<Button>(R.id.btnClearAllMedia)
        val dm = resources.displayMetrics
        val columns = maxOf(2, dm.widthPixels / (120 * dm.density).toInt())
        val columnWidth = dm.widthPixels / columns

        val lm = StaggeredGridLayoutManager(columns, StaggeredGridLayoutManager.VERTICAL)
        lm.gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_NONE
        grid.layoutManager = lm

        lateinit var itemTouchHelper: ItemTouchHelper

        adapter = MediaAdapter(
            items = savedEntries,
            onClick = { entry ->
                val res = entry.resolution as? MediaResolution.Ready
                if (res != null) {
                    Toast.makeText(this, "Ссылка: ${res.sourceUrl}", Toast.LENGTH_SHORT).show()
                }
            },
            columnWidth = columnWidth,
            onLongClick = { entry ->
                showMainItemOptionsDialog(entry)
            },
            onStartDrag = { viewHolder -> itemTouchHelper.startDrag(viewHolder) }
        )
        grid.adapter = adapter

        itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or
                    ItemTouchHelper.DOWN or
                    ItemTouchHelper.LEFT or
                    ItemTouchHelper.RIGHT,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.adapterPosition
                val toPos = target.adapterPosition
                if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION) return false

                Collections.swap(savedEntries, fromPos, toPos)
                adapter.notifyItemMoved(fromPos, toPos)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                lifecycleScope.launch {
                    val currentList = repository.getAllSaved()
                    val updatedEntities = savedEntries.mapNotNull { entry ->
                        currentList.find { it.sourceUrl == entry.sourceUrl }
                    }
                    if (updatedEntities.isNotEmpty()) {
                        repository.saveListOrder(updatedEntities)
                    }
                }
            }
        })
        itemTouchHelper.attachToRecyclerView(grid)

        btnClearAll.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Очистка всей медиатеки")
                .setMessage("Вы абсолютно уверены, что хотите НАВСЕГДА удалить все категории, гифки и локальные файлы превью? Это действие нельзя отменить.")
                .setPositiveButton("Да, очистить всё") { _, _ ->
                    lifecycleScope.launch {
                        repository.clearAllMedia(this@MainActivity)
                        refreshAllData()
                        Toast.makeText(this@MainActivity, "Медиатека успешно очищена", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    private fun showMainItemOptionsDialog(entry: MediaEntry) {
        val readyRes = entry.resolution as? MediaResolution.Ready
        val isFav = readyRes?.isFavorite == true
        val isPinned = readyRes?.isPinned == true

        val options = arrayOf(
            if (isPinned) "Открепить" else "Закрепить вверху",
            "Сдвинуть выше",
            "Сдвинуть ниже",
            if (isFav) "Убрать из избранного" else "В избранное",
            "Настроить категории",
            "Удалить"
        )

        AlertDialog.Builder(this)
            .setTitle("Управление медиа")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        lifecycleScope.launch {
                            repository.togglePinned(entry.sourceUrl)
                            refreshAllData()
                        }
                    }
                    1 -> {
                        lifecycleScope.launch {
                            val currentList = repository.getAllSaved()
                            repository.moveMediaUp(entry.sourceUrl, currentList)
                            refreshAllData()
                        }
                    }
                    2 -> {
                        lifecycleScope.launch {
                            val currentList = repository.getAllSaved()
                            repository.moveMediaDown(entry.sourceUrl, currentList)
                            refreshAllData()
                        }
                    }
                    3 -> {
                        lifecycleScope.launch {
                            repository.toggleFavorite(entry.sourceUrl)
                            refreshAllData()
                        }
                    }
                    4 -> showMainEditCategoriesDialog(entry)
                    5 -> {
                        lifecycleScope.launch {
                            repository.deleteMedia(entry.sourceUrl)
                            Toast.makeText(this@MainActivity, "Удалено из медиатеки", Toast.LENGTH_SHORT).show()
                            refreshAllData()
                        }
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showMainEditCategoriesDialog(entry: MediaEntry) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        val checkBoxes = mutableListOf<Pair<Long, CheckBox>>()

        lifecycleScope.launch {
            val categories = repository.getAllCategories()
            val assignedCatIds = repository.getCategoryIdsForMedia(entry.sourceUrl).toSet()

            categories.forEach { cat ->
                val cb = CheckBox(this@MainActivity).apply {
                    text = cat.name
                    isChecked = assignedCatIds.contains(cat.id)
                }
                container.addView(cb)
                checkBoxes.add(cat.id to cb)
            }

            AlertDialog.Builder(this@MainActivity)
                .setTitle("Настройка категорий")
                .setView(container)
                .setPositiveButton("Сохранить") { _, _ ->
                    lifecycleScope.launch {
                        val selectedCatIds = checkBoxes.filter { it.second.isChecked }.map { it.first }
                        repository.setCategoriesForMedia(entry.sourceUrl, selectedCatIds)
                        refreshAllData()
                    }
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    private fun initAddMediaSection() {
        val inputUrl = findViewById<EditText>(R.id.inputMainUrl)
        val btnAdd = findViewById<Button>(R.id.btnAddMainMedia)

        btnAdd.setOnClickListener {
            var url = inputUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "https://$url"
                }
                val finalUrl = url
                lifecycleScope.launch {
                    try {
                        val res = repository.resolve(finalUrl, forceReload = true)
                        if (res is MediaResolution.Ready) {
                            val selectedCatIds = categoryCheckBoxes.filter { it.second.isChecked }.map { it.first }
                            if (selectedCatIds.isNotEmpty()) {
                                repository.setCategoriesForMedia(finalUrl, selectedCatIds)
                            }
                            Toast.makeText(this@MainActivity, "Успешно добавлено!", Toast.LENGTH_SHORT).show()
                            inputUrl.text.clear()
                            refreshAllData()
                        } else if (res is MediaResolution.Failed) {
                            Toast.makeText(this@MainActivity, "Ошибка: ${res.reason}", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Failed to resolve URL $finalUrl", e)
                        Toast.makeText(this@MainActivity, "Ошибка обработки ссылки", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun initCategoryManagementSection() {
        val inputCatName = findViewById<EditText>(R.id.inputMainCategoryName)
        val btnAddCategory = findViewById<Button>(R.id.btnAddMainCategory)

        btnAddCategory.setOnClickListener {
            val name = inputCatName.text.toString().trim()
            if (name.isNotEmpty()) {
                lifecycleScope.launch {
                    repository.addCategory(name)
                    inputCatName.text.clear()
                    refreshAllData()
                }
            }
        }
    }

    private fun refreshAllData() {
        lifecycleScope.launch {
            loadAddCategoryCheckBoxes()
            loadCategoryManagementList()
            loadSavedMediaList()
        }
    }

    private suspend fun loadAddCategoryCheckBoxes() {
        val container = findViewById<LinearLayout>(R.id.mainCategoriesContainer)
        container.removeAllViews()
        categoryCheckBoxes.clear()

        val categories = repository.getAllCategories()
        categories.forEach { cat ->
            val cb = CheckBox(this).apply {
                text = cat.name
                setTextColor(0xFFFFFFFF.toInt())
            }
            container.addView(cb)
            categoryCheckBoxes.add(cat.id to cb)
        }
    }

    private suspend fun loadCategoryManagementList() {
        val container = findViewById<LinearLayout>(R.id.mainCategoryListContainer)
        container.removeAllViews()

        val categories = repository.getAllCategories()
        categories.forEach { cat ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
            }

            val nameTv = TextView(this).apply {
                text = cat.name
                setTextColor(0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val btnDelete = Button(this).apply {
                text = "Удалить"
                textSize = 11f
                setOnClickListener {
                    showCategoryDeleteConfirmDialog(cat)
                }
            }

            row.addView(nameTv)
            row.addView(btnDelete)
            container.addView(row)
        }
    }

    private fun showCategoryDeleteConfirmDialog(category: CategoryEntity) {
        AlertDialog.Builder(this)
            .setTitle("Удаление категории '${category.name}'")
            .setMessage("Выберите способ удаления категории:")
            .setPositiveButton("Категорию и все её гифки") { _, _ ->
                lifecycleScope.launch {
                    repository.deleteCategory(category.id, deleteAttachedMedia = true)
                    refreshAllData()
                }
            }
            .setNeutralButton("Только категорию") { _, _ ->
                lifecycleScope.launch {
                    repository.deleteCategory(category.id, deleteAttachedMedia = false)
                    refreshAllData()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private suspend fun loadSavedMediaList() {
        val mediaList: List<MediaEntity> = repository.getAllSaved()
        savedEntries.clear()

        mediaList.forEach { entity ->
            savedEntries.add(
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
    }

    override fun onResume() {
        super.onResume()
        updateStatus(findViewById(R.id.statusText))
        refreshAllData()
    }

    override fun onDestroy() {
        dbObserver?.let { AppDatabase.get(this).invalidationTracker.removeObserver(it) }
        super.onDestroy()
    }

    private fun updateStatus(tv: TextView) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any {
            it.packageName == packageName
        }
        val current = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        )
        val isCurrent = current?.startsWith(packageName) == true

        tv.text = buildString {
            append("Включена: ")
            append(if (enabled) "да" else "нет")
            append(" | Выбрана: ")
            append(if (isCurrent) "да" else "нет")
        }
    }
}
