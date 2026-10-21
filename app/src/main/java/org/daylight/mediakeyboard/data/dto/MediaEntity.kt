package org.daylight.mediakeyboard.data.dto

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "media_cache")
data class MediaEntity(
    @PrimaryKey val sourceUrl: String,      // исходная ссылка, введённая пользователем
    val serviceId: String?,                 // null — прямой URL (не через API)
    val previewUrl: String,
    val contentUrl: String,
    val aspectRatio: Float,
    val title: String?,
    val resolvedAt: Long,
    val failed: Boolean = false,            // если API не сработал — не пытаться снова
    val failReason: String? = null
)
