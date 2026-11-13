package org.daylight.mediakeyboard.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import org.daylight.mediakeyboard.data.dto.CategoryEntity
import org.daylight.mediakeyboard.data.dto.MediaCategoryCrossRef
import org.daylight.mediakeyboard.data.dto.MediaDao
import org.daylight.mediakeyboard.data.dto.MediaEntity

@Database(
    entities = [
        MediaEntity::class,
        CategoryEntity::class,
        MediaCategoryCrossRef::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(ctx: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                ctx.applicationContext,
                AppDatabase::class.java,
                "media.db"
            )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                .also { INSTANCE = it }
        }
    }
}
