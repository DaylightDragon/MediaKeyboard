package org.daylight.mediakeyboard.data.dto

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface MediaDao {
    @Query("SELECT * FROM media_cache WHERE sourceUrl = :url LIMIT 1")
    suspend fun get(url: String): MediaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MediaEntity)

    @Query("SELECT * FROM media_cache WHERE failed = 0 ORDER BY resolvedAt DESC")
    suspend fun all(): List<MediaEntity>

    @Query("DELETE FROM media_cache WHERE sourceUrl = :url")
    suspend fun delete(url: String)

    // Избранное
    @Query("SELECT * FROM media_cache WHERE isFavorite = 1 AND failed = 0 ORDER BY resolvedAt DESC")
    suspend fun getFavorites(): List<MediaEntity>

    @Query("UPDATE media_cache SET isFavorite = :isFavorite WHERE sourceUrl = :url")
    suspend fun setFavorite(url: String, isFavorite: Boolean)

    // Категории
    @Query("SELECT * FROM categories ORDER BY sortOrder ASC, name ASC")
    suspend fun getAllCategories(): List<CategoryEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategory(category: CategoryEntity): Long

    @Query("DELETE FROM categories WHERE id = :categoryId")
    suspend fun deleteCategory(categoryId: Long)

    // Связи Медиа <-> Категории
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCrossRef(ref: MediaCategoryCrossRef)

    @Query("DELETE FROM media_category_cross_ref WHERE mediaSourceUrl = :url AND categoryId = :categoryId")
    suspend fun deleteCrossRef(url: String, categoryId: Long)

    @Query("DELETE FROM media_category_cross_ref WHERE mediaSourceUrl = :url")
    suspend fun deleteAllCrossRefsForMedia(url: String)

    @Query("SELECT categoryId FROM media_category_cross_ref WHERE mediaSourceUrl = :url")
    suspend fun getCategoryIdsForMedia(url: String): List<Long>

    @Query("""
        SELECT m.* FROM media_cache m
        INNER JOIN media_category_cross_ref ref ON m.sourceUrl = ref.mediaSourceUrl
        WHERE ref.categoryId = :categoryId AND m.failed = 0
        ORDER BY m.resolvedAt DESC
    """)
    suspend fun getMediaForCategory(categoryId: Long): List<MediaEntity>

    @Transaction
    @Query("SELECT * FROM media_cache WHERE sourceUrl = :url")
    suspend fun getMediaWithCategories(url: String): MediaWithCategories?

    @Transaction
    suspend fun setCategoriesForMedia(url: String, categoryIds: List<Long>) {
        deleteAllCrossRefsForMedia(url)
        categoryIds.forEach { catId ->
            insertCategoryRef(MediaCategoryCrossRef(url, catId))
        }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategoryRef(ref: MediaCategoryCrossRef)
}
