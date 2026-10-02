package org.daylight.mediakeyboard

data class MediaItem(
    val title: String,
    val url: String,          // ссылка, которую будем вставлять
    val previewUrl: String? = null // пока не используем
)
