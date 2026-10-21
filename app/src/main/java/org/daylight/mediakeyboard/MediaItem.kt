package org.daylight.mediakeyboard

import org.daylight.mediakeyboard.data.MediaResolution

data class MediaEntry(
    val sourceUrl: String,
    val resolution: MediaResolution? = null  // null = ещё не резолвили
)
