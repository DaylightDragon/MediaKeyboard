package org.daylight.mediakeyboard.data.dto

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

data class MediaWithCategories(
    @Embedded val media: MediaEntity,
    @Relation(
        parentColumn = "sourceUrl",
        entityColumn = "id",
        associateBy = Junction(
            value = MediaCategoryCrossRef::class,
            parentColumn = "mediaSourceUrl",
            entityColumn = "categoryId"
        )
    )
    val categories: List<CategoryEntity>
)
