package org.daylight.mediakeyboard.data.dto

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "media_category_cross_ref",
    primaryKeys = ["mediaSourceUrl", "categoryId"],
    indices = [
        Index("mediaSourceUrl"),
        Index("categoryId")
    ]
)
data class MediaCategoryCrossRef(
    val mediaSourceUrl: String,
    val categoryId: Long
)
