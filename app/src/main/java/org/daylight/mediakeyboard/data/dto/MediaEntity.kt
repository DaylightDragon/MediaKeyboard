package org.daylight.mediakeyboard.data.dto

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "media_cache")
data class MediaEntity(
    @PrimaryKey val sourceUrl: String,      // исходная ссылка, введённая пользователем
    val serviceId: String?,                 // null — прямой URL (не через API)
    val previewUrl: String,
    val localPreviewPath: String? = null,   // локальный путь для закэшированных превью (например, Discord CDN)
    val contentUrl: String,
    val aspectRatio: Float,
    val title: String?,
    val resolvedAt: Long,
    val isFavorite: Boolean = false,        // флаг излюбленной гифки
    val failed: Boolean = false,            // если API не сработал — не пытаться снова
    val failReason: String? = null
)
