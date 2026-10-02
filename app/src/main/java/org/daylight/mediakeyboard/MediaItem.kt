package org.daylight.mediakeyboard

data class MediaItem(
    val url: String,
    val previewUrl: String? = null,
    // Соотношение сторон (ширина / высота). Нужно для расчета высоты плитки.
    // Например, 1.0 для квадрата, 1.5 для широкой, 0.6 для высокой.
    val aspectRatio: Float = 1.0f
)
