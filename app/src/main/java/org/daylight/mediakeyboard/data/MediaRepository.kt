package org.daylight.mediakeyboard.data

import android.content.Context
import android.util.Log
import org.daylight.mediakeyboard.data.dto.CategoryEntity
import org.daylight.mediakeyboard.data.dto.MediaDao
import org.daylight.mediakeyboard.data.dto.MediaEntity
import java.io.File

class MediaRepository(
    private val dao: MediaDao,
    private val registry: ResolverRegistry,
    private val apiKeyStore: ApiKeyStore,
    private val localCacher: LocalMediaCacher? = null
) {

    /**
     * Возвращает MediaResolution для отображения/вставки.
     * Автоматически скачивает превью локально для ссылок Discord или прямых ссылок.
     */
    suspend fun resolve(sourceUrl: String, forceReload: Boolean = false): MediaResolution {
        if (!forceReload) {
            val cached = dao.get(sourceUrl)
            if (cached != null) {
                if (cached.failed) {
                    return MediaResolution.Failed(cached.serviceId ?: "unknown", cached.failReason)
                }

                var displayPreviewUrl = cached.previewUrl
                if (!cached.localPreviewPath.isNullOrEmpty()) {
                    val path = cached.localPreviewPath.removePrefix("file://")
                    val file = File(path)
                    if (file.exists() && file.length() > 100) {
                        displayPreviewUrl = cached.localPreviewPath
                    } else {
                        dao.upsert(cached.copy(localPreviewPath = null))
                    }
                } else if (localCacher?.shouldCacheLocally(cached.previewUrl) == true) {
                    val downloadedPath = localCacher.cachePreview(cached.previewUrl)
                    if (downloadedPath != null) {
                        displayPreviewUrl = downloadedPath
                        dao.upsert(cached.copy(localPreviewPath = downloadedPath))
                    }
                }

                if (displayPreviewUrl.isBlank()) {
                    displayPreviewUrl = cached.contentUrl.ifBlank { sourceUrl }
                }

                return MediaResolution.Ready(
                    sourceUrl = sourceUrl,
                    previewUrl = displayPreviewUrl,
                    contentUrl = cached.contentUrl,
                    aspectRatio = cached.aspectRatio,
                    title = cached.title,
                    isFavorite = cached.isFavorite
                )
            }
        }

        val resolver = registry.find(sourceUrl)
        if (resolver == null) {
            // Прямая ссылка (или Discord) — не через API-резолвер.
            var localPath: String? = null
            if (localCacher?.shouldCacheLocally(sourceUrl) == true) {
                localPath = localCacher.cachePreview(sourceUrl)
            }

            val entity = MediaEntity(
                sourceUrl = sourceUrl,
                serviceId = null,
                previewUrl = sourceUrl,
                localPreviewPath = localPath,
                contentUrl = sourceUrl,
                aspectRatio = 1f,
                title = null,
                resolvedAt = System.currentTimeMillis(),
                failed = false
            )
            dao.upsert(entity)

            return MediaResolution.Ready(
                sourceUrl = sourceUrl,
                previewUrl = localPath ?: sourceUrl,
                contentUrl = sourceUrl,
                aspectRatio = 1f,
                title = null,
                isFavorite = false
            )
        }

        val ref = resolver.extractRef(sourceUrl)
            ?: return MediaResolution.Failed(resolver.serviceId, "cannot extract ref")

        return try {
            val apiKey = apiKeyStore.get(resolver.serviceId)
            Log.d("MediaRepo", "apiKey for ${resolver.serviceId} = ${apiKey?.take(6)}… (len=${apiKey?.length})")
            val resolved = resolver.resolve(ref, apiKey)

            var localPath: String? = null
            if (localCacher?.shouldCacheLocally(resolved.previewUrl) == true) {
                localPath = localCacher.cachePreview(resolved.previewUrl)
            }

            val entity = MediaEntity(
                sourceUrl = sourceUrl,
                serviceId = resolver.serviceId,
                previewUrl = resolved.previewUrl,
                localPreviewPath = localPath,
                contentUrl = resolved.contentUrl,
                aspectRatio = resolved.aspectRatio,
                title = resolved.title,
                resolvedAt = System.currentTimeMillis(),
                failed = false
            )
            dao.upsert(entity)

            MediaResolution.Ready(
                sourceUrl = sourceUrl,
                previewUrl = localPath ?: resolved.previewUrl,
                contentUrl = resolved.contentUrl,
                aspectRatio = resolved.aspectRatio,
                title = resolved.title,
                isFavorite = false
            )
        } catch (e: MissingApiKeyException) {
            MediaResolution.MissingKey(resolver.serviceId, resolver.displayName)
        } catch (e: Exception) {
            dao.upsert(
                MediaEntity(
                    sourceUrl = sourceUrl,
                    serviceId = resolver.serviceId,
                    previewUrl = "",
                    localPreviewPath = null,
                    contentUrl = "",
                    aspectRatio = 1f,
                    title = null,
                    resolvedAt = System.currentTimeMillis(),
                    failed = true,
                    failReason = e.message
                )
            )
            MediaResolution.Failed(resolver.serviceId, e.message)
        }
    }

    // --- Избранное и управление элементами ---

    suspend fun getAllSaved(): List<MediaEntity> = dao.all()

    suspend fun getFavorites(): List<MediaEntity> = dao.getFavorites()

    suspend fun toggleFavorite(sourceUrl: String): Boolean {
        val cached = dao.get(sourceUrl) ?: return false
        val newFav = !cached.isFavorite
        dao.setFavorite(sourceUrl, newFav)
        return newFav
    }

    suspend fun deleteMedia(sourceUrl: String) {
        dao.delete(sourceUrl)
    }

    // --- Категории ---

    suspend fun getAllCategories(): List<CategoryEntity> = dao.getAllCategories()

    suspend fun addCategory(name: String): Long {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return -1L
        return dao.insertCategory(CategoryEntity(name = trimmed))
    }

    suspend fun deleteCategory(categoryId: Long, deleteAttachedMedia: Boolean = false) {
        if (deleteAttachedMedia) {
            val items = dao.getMediaForCategory(categoryId)
            items.forEach { media ->
                if (!media.localPreviewPath.isNullOrEmpty()) {
                    val file = File(media.localPreviewPath.removePrefix("file://"))
                    if (file.exists()) file.delete()
                }
                dao.delete(media.sourceUrl)
            }
        }
        dao.deleteCategory(categoryId)
    }

    suspend fun clearAllMedia(context: Context) {
        dao.deleteAllCrossRefs()
        dao.deleteAllCategories()
        dao.deleteAllMedia()

        val previewsDir = File(context.filesDir, "previews")
        if (previewsDir.exists()) {
            previewsDir.listFiles()?.forEach { it.delete() }
        }
    }

    suspend fun getMediaForCategory(categoryId: Long): List<MediaEntity> =
        dao.getMediaForCategory(categoryId)

    suspend fun getCategoryIdsForMedia(sourceUrl: String): List<Long> =
        dao.getCategoryIdsForMedia(sourceUrl)

    suspend fun setCategoriesForMedia(sourceUrl: String, categoryIds: List<Long>) {
        dao.setCategoriesForMedia(sourceUrl, categoryIds)
    }
}

sealed class MediaResolution {
    data class Ready(
        val sourceUrl: String,
        val previewUrl: String,
        val contentUrl: String,
        val aspectRatio: Float,
        val title: String?,
        val isFavorite: Boolean = false
    ) : MediaResolution()

    data class MissingKey(val serviceId: String, val displayName: String) : MediaResolution()

    data class Failed(val serviceId: String, val reason: String?) : MediaResolution()
}
