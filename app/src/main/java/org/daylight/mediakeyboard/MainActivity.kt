package org.daylight.mediakeyboard

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import kotlinx.coroutines.launch
import org.daylight.mediakeyboard.data.ApiKeyStore
import org.daylight.mediakeyboard.data.AppDatabase
import org.daylight.mediakeyboard.data.LocalMediaCacher
import org.daylight.mediakeyboard.data.MediaRepository
import org.daylight.mediakeyboard.data.MediaResolution
import org.daylight.mediakeyboard.data.ResolverRegistry
import org.daylight.mediakeyboard.data.dto.MediaEntity

class MainActivity : AppCompatActivity() {

    private lateinit var repository: MediaRepository
    private lateinit var adapter: MediaAdapter
    private val savedEntries = mutableListOf<MediaEntry>()
    private val categoryCheckBoxes = mutableListOf<Pair<Long, CheckBox>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val registry = ResolverRegistry.createDefault()
        val localCacher = LocalMediaCacher(this)
        repository = MediaRepository(
            dao = AppDatabase.get(this).mediaDao(),
            registry = registry,
            apiKeyStore = ApiKeyStore.get(this),
            localCacher = localCacher
        )

        initStatusSection()
        initMediaGrid()
        initAddMediaSection()
        initCategoryManagementSection()

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

    private fun initMediaGrid() {
        val grid = findViewById<RecyclerView>(R.id.mainMediaGrid)
        val dm = resources.displayMetrics
        val columns = maxOf(2, dm.widthPixels / (120 * dm.density).toInt())
        val columnWidth = dm.widthPixels / columns

        val lm = StaggeredGridLayoutManager(columns, StaggeredGridLayoutManager.VERTICAL)
        lm.gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_NONE
        grid.layoutManager = lm

        adapter = MediaAdapter(
            items = savedEntries,
            onClick = { entry ->
                val res = entry.resolution as? MediaResolution.Ready
                if (res != null) {
                    Toast.makeText(this, "Ссылка скопирована или готова: ${res.sourceUrl}", Toast.LENGTH_SHORT).show()
                }
            },
            columnWidth = columnWidth,
            onLongClick = { entry ->
                lifecycleScope.launch {
                    repository.deleteMedia(entry.sourceUrl)
                    Toast.makeText(this@MainActivity, "Удалено из медиатеки", Toast.LENGTH_SHORT).show()
                    refreshAllData()
                }
            }
        )
        grid.adapter = adapter
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
                    lifecycleScope.launch {
                        repository.deleteCategory(cat.id)
                        refreshAllData()
                    }
                }
            }

            row.addView(nameTv)
            row.addView(btnDelete)
            container.addView(row)
        }
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
