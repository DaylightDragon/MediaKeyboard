package org.daylight.mediakeyboard.data

import android.util.Log
import org.daylight.mediakeyboard.data.dto.MediaDao
import org.daylight.mediakeyboard.data.dto.MediaEntity

class MediaRepository(
    private val dao: MediaDao,
    private val registry: ResolverRegistry,
    private val apiKeyStore: ApiKeyStore
) {

    /**
     * Возвращает ResolvedMedia для отображения/вставки.
     * Никогда не бросает: ошибки возвращает как FailedMedia.
     */
    suspend fun resolve(sourceUrl: String, forceReload: Boolean = false): MediaResolution {
        if (!forceReload) {
            val cached = dao.get(sourceUrl)
            if (cached != null) {
                return if (cached.failed) {
                    MediaResolution.Failed(cached.serviceId ?: "unknown", cached.failReason)
                } else {
                    MediaResolution.Ready(
                        sourceUrl = sourceUrl,
                        previewUrl = cached.previewUrl,
                        contentUrl = cached.contentUrl,
                        aspectRatio = cached.aspectRatio,
                        title = cached.title
                    )
                }
            }
        }

        val resolver = registry.find(sourceUrl)
        if (resolver == null) {
            // Прямая ссылка — нечего резолвить, просто используем её как есть.
            // Аспект-ratio неизвестен, ставим 1.0, скорректируем после загрузки (см. п.8).
            dao.upsert(
                MediaEntity(
                    sourceUrl = sourceUrl,
                    serviceId = null,
                    previewUrl = sourceUrl,
                    contentUrl = sourceUrl,
                    aspectRatio = 1f,
                    title = null,
                    resolvedAt = System.currentTimeMillis(),
                    failed = false
                )
            )
            return MediaResolution.Ready(sourceUrl, sourceUrl, sourceUrl, 1f, null)
        }

        val ref = resolver.extractRef(sourceUrl)
            ?: return MediaResolution.Failed(resolver.serviceId, "cannot extract ref")

        return try {
            val apiKey = apiKeyStore.get(resolver.serviceId)
            Log.d("MediaRepo", "apiKey for ${resolver.serviceId} = ${apiKey?.take(6)}… (len=${apiKey?.length})")
            val resolved = resolver.resolve(ref, apiKeyStore.get(resolver.serviceId))
            dao.upsert(
                MediaEntity(
                    sourceUrl = sourceUrl,
                    serviceId = resolver.serviceId,
                    previewUrl = resolved.previewUrl,
                    contentUrl = resolved.contentUrl,
                    aspectRatio = resolved.aspectRatio,
                    title = resolved.title,
                    resolvedAt = System.currentTimeMillis(),
                    failed = false
                )
            )
            MediaResolution.Ready(
                sourceUrl, resolved.previewUrl, resolved.contentUrl,
                resolved.aspectRatio, resolved.title
            )
        } catch (e: MissingApiKeyException) {
            // НЕ сохраняем в failed — ключ может появиться позже, тогда повторим.
            MediaResolution.MissingKey(resolver.serviceId, resolver.displayName)
        } catch (e: Exception) {
            // Ошибку API сохраняем как failed — больше не дёргаем.
            dao.upsert(
                MediaEntity(
                    sourceUrl = sourceUrl,
                    serviceId = resolver.serviceId,
                    previewUrl = "",
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
}

sealed class MediaResolution {
    data class Ready(
        val sourceUrl: String,
        val previewUrl: String,
        val contentUrl: String,
        val aspectRatio: Float,
        val title: String?
    ) : MediaResolution()

    data class MissingKey(val serviceId: String, val displayName: String) : MediaResolution()

    data class Failed(val serviceId: String, val reason: String?) : MediaResolution()
}
