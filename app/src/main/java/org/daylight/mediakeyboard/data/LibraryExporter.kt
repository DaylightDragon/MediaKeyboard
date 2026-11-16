package org.daylight.mediakeyboard.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.daylight.mediakeyboard.data.dto.CategoryEntity
import org.daylight.mediakeyboard.data.dto.MediaEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class LibraryExporter(private val context: Context) {

    enum class ConflictAction {
        MERGE,
        CREATE_DUPLICATE
    }

    /**
     * Экспортирует выбранные категории и медиафайлы в файл архива (.mkzip / .zip).
     * @param selectedCategoryIds если пуст, экспортирует абсолютно все данные.
     */
    suspend fun exportLibrary(
        selectedCategoryIds: Set<Long>,
        outputStream: OutputStream
    ) = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(context)
        val dao = db.mediaDao()

        val allCategories = dao.getAllCategories()
        val exportCategories = if (selectedCategoryIds.isEmpty()) {
            allCategories
        } else {
            allCategories.filter { selectedCategoryIds.contains(it.id) }
        }

        val exportMediaList = mutableListOf<MediaEntity>()
        val mediaCategoryNamesMap = mutableMapOf<String, MutableList<String>>()

        if (selectedCategoryIds.isEmpty()) {
            exportMediaList.addAll(dao.all())
            exportMediaList.forEach { media ->
                val catIds = dao.getCategoryIdsForMedia(media.sourceUrl)
                val names = allCategories.filter { catIds.contains(it.id) }.map { it.name }
                mediaCategoryNamesMap[media.sourceUrl] = names.toMutableList()
            }
        } else {
            val mediaSet = mutableSetOf<MediaEntity>()
            exportCategories.forEach { cat ->
                val items = dao.getMediaForCategory(cat.id)
                mediaSet.addAll(items)
            }
            exportMediaList.addAll(mediaSet)

            exportMediaList.forEach { media ->
                val catIds = dao.getCategoryIdsForMedia(media.sourceUrl).toSet()
                val names = exportCategories.filter { catIds.contains(it.id) }.map { it.name }
                mediaCategoryNamesMap[media.sourceUrl] = names.toMutableList()
            }
        }

        val zipOut = ZipOutputStream(outputStream)

        // 1. Создаем manifest.json
        val manifestJson = JSONObject()
        val categoriesArray = JSONArray()
        exportCategories.forEach { cat ->
            val catObj = JSONObject().apply {
                put("name", cat.name)
            }
            categoriesArray.put(catObj)
        }
        manifestJson.put("categories", categoriesArray)

        val mediaArray = JSONArray()
        val previewFilesToZip = mutableListOf<Pair<String, File>>()

        exportMediaList.forEach { media ->
            val mediaObj = JSONObject().apply {
                put("sourceUrl", media.sourceUrl)
                put("serviceId", media.serviceId ?: "")
                put("previewUrl", media.previewUrl)
                put("contentUrl", media.contentUrl)
                put("aspectRatio", media.aspectRatio.toDouble())
                put("title", media.title ?: "")
                put("isFavorite", media.isFavorite)

                val catNames = JSONArray()
                mediaCategoryNamesMap[media.sourceUrl]?.forEach { catNames.put(it) }
                put("categoryNames", catNames)

                var previewFileName = ""
                if (!media.localPreviewPath.isNullOrEmpty()) {
                    val file = File(media.localPreviewPath.removePrefix("file://"))
                    if (file.exists() && file.length() > 0) {
                        previewFileName = file.name
                        previewFilesToZip.add(previewFileName to file)
                    }
                }
                put("previewFileName", previewFileName)
            }
            mediaArray.put(mediaObj)
        }
        manifestJson.put("media", mediaArray)

        // Записываем manifest.json
        val manifestEntry = ZipEntry("manifest.json")
        zipOut.putNextEntry(manifestEntry)
        zipOut.write(manifestJson.toString(2).toByteArray(Charsets.UTF_8))
        zipOut.closeEntry()

        // 2. Упаковываем локальные файлы превью
        previewFilesToZip.distinctBy { it.first }.forEach { (fileName, file) ->
            if (file.exists()) {
                val entry = ZipEntry("previews/$fileName")
                zipOut.putNextEntry(entry)
                FileInputStream(file).use { input ->
                    input.copyTo(zipOut)
                }
                zipOut.closeEntry()
            }
        }

        zipOut.finish()
        Log.d("LibraryExporter", "Export completed. Exported ${exportMediaList.size} media items.")
    }

    /**
     * Импортирует библиотеку из архива.
     */
    suspend fun importLibrary(
        inputStream: InputStream,
        onCategoryConflict: suspend (categoryName: String) -> ConflictAction
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(context)
        val dao = db.mediaDao()
        val previewsDir = File(context.filesDir, "previews").apply { if (!exists()) mkdirs() }

        var manifestText: String? = null
        val extractedFiles = mutableMapOf<String, File>()

        val tempDir = File(context.cacheDir, "import_temp_${System.currentTimeMillis()}").apply { mkdirs() }

        try {
            val zipIn = ZipInputStream(inputStream)
            var entry: ZipEntry? = zipIn.nextEntry
            while (entry != null) {
                val normalizedName = entry.name.replace('\\', '/')
                if (!entry.isDirectory) {
                    if (normalizedName.endsWith("manifest.json")) {
                        val baos = ByteArrayOutputStream()
                        zipIn.copyTo(baos)
                        manifestText = baos.toString("UTF-8")
                    } else if (normalizedName.contains("previews/")) {
                        val fileName = normalizedName.substringAfter("previews/")
                        if (fileName.isNotEmpty()) {
                            val tempFile = File(tempDir, fileName)
                            FileOutputStream(tempFile).use { out ->
                                zipIn.copyTo(out)
                            }
                            extractedFiles[fileName] = tempFile
                        }
                    }
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }

            if (manifestText.isNullOrEmpty()) {
                val errorMsg = "Манифест manifest.json не найден в архиве"
                Log.e("LibraryExporter", errorMsg)
                return@withContext Result.failure(Exception(errorMsg))
            }

            val manifestObj = JSONObject(manifestText)
            val categoriesArr = manifestObj.optJSONArray("categories") ?: JSONArray()
            val mediaArr = manifestObj.optJSONArray("media") ?: JSONArray()

            // 1. Импортируем категории с обработкой дубликатов
            val categoryNameToIdMap = mutableMapOf<String, Long>()

            for (i in 0 until categoriesArr.length()) {
                val catName = categoriesArr.getJSONObject(i).getString("name")
                val existing = dao.getCategoryByName(catName)

                if (existing != null) {
                    val action = onCategoryConflict(catName)
                    if (action == ConflictAction.MERGE) {
                        categoryNameToIdMap[catName] = existing.id
                    } else {
                        var counter = 1
                        var newName = "$catName ($counter)"
                        while (dao.getCategoryByName(newName) != null) {
                            counter++
                            newName = "$catName ($counter)"
                        }
                        val newId = dao.insertCategory(CategoryEntity(name = newName))
                        categoryNameToIdMap[catName] = newId
                    }
                } else {
                    val newId = dao.insertCategory(CategoryEntity(name = catName))
                    categoryNameToIdMap[catName] = newId
                }
            }

            // 2. Импортируем медиафайлы
            for (i in 0 until mediaArr.length()) {
                val mediaObj = mediaArr.getJSONObject(i)
                val sourceUrl = mediaObj.getString("sourceUrl")
                val serviceId = mediaObj.optString("serviceId").ifEmpty { null }
                val previewUrl = mediaObj.optString("previewUrl")
                val contentUrl = mediaObj.optString("contentUrl")
                val aspectRatio = mediaObj.optDouble("aspectRatio", 1.0).toFloat()
                val title = mediaObj.optString("title").ifEmpty { null }
                val previewFileName = mediaObj.optString("previewFileName")

                var localPath: String? = null
                if (previewFileName.isNotEmpty() && extractedFiles.containsKey(previewFileName)) {
                    val tempFile = extractedFiles[previewFileName]!!
                    val targetFile = File(previewsDir, previewFileName)
                    tempFile.copyTo(targetFile, overwrite = true)
                    localPath = "file://${targetFile.absolutePath}"
                }

                // Импортируемые гифки никогда не добавляются в Избранное.
                // Если гифка уже была в локальной БД — сохраняем текущий локальный статус isFavorite.
                val existingLocal = dao.get(sourceUrl)
                val keepFavorite = existingLocal?.isFavorite ?: false

                val entity = MediaEntity(
                    sourceUrl = sourceUrl,
                    serviceId = serviceId,
                    previewUrl = previewUrl,
                    localPreviewPath = localPath ?: existingLocal?.localPreviewPath,
                    contentUrl = contentUrl,
                    aspectRatio = aspectRatio,
                    title = title,
                    resolvedAt = System.currentTimeMillis(),
                    isFavorite = keepFavorite,
                    failed = false
                )
                dao.upsert(entity)

                // Связываем с категориями
                val catNamesArr = mediaObj.optJSONArray("categoryNames") ?: JSONArray()
                val targetCatIds = mutableListOf<Long>()
                for (j in 0 until catNamesArr.length()) {
                    val name = catNamesArr.getString(j)
                    if (categoryNameToIdMap.containsKey(name)) {
                        targetCatIds.add(categoryNameToIdMap[name]!!)
                    }
                }
                if (targetCatIds.isNotEmpty()) {
                    dao.setCategoriesForMedia(sourceUrl, targetCatIds)
                }
            }

            Log.d("LibraryExporter", "Import successful. Imported ${mediaArr.length()} media items.")
            return@withContext Result.success(Unit)
        } catch (e: Exception) {
            Log.e("LibraryExporter", "Failed to import library", e)
            return@withContext Result.failure(e)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
