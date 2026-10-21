package org.daylight.mediakeyboard.data

/**
 * Ссылка на медиа в конкретном сервисе.
 * serviceId — например, "klipy", "giphy", "tenor".
 * params — набор параметров, специфичных для сервиса.
 *   Для Klipy: params["slug"] = "cat-cat-crazy-2"
 *   Для Giphy: params["id"]   = "JIX9t2j0ZTN9S"
 */
data class ServiceRef(
        val serviceId: String,
        val params: Map<String, String>
) {
    val primaryId: String? get() = params.values.firstOrNull()
}

/**
 * Результат разрешения ссылки: то, что можно вставить в поле ввода
 * и то, что можно показать как превью.
 */
data class ResolvedMedia(
        val previewUrl: String,     // для отображения в плитке (например, .webp small)
        val contentUrl: String,     // что вставлять при клике (например, .gif или .mp4)
        val aspectRatio: Float,     // width / height
        val title: String? = null
)
