package org.daylight.mediakeyboard.data.dto

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MediaDao {
    @Query("SELECT * FROM media_cache WHERE sourceUrl = :url LIMIT 1")
    suspend fun get(url: String): MediaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MediaEntity)

    @Query("SELECT * FROM media_cache")
    suspend fun all(): List<MediaEntity>

    @Query("DELETE FROM media_cache WHERE sourceUrl = :url")
    suspend fun delete(url: String)
}
