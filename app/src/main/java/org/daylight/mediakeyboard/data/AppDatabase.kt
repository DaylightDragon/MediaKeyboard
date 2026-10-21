package org.daylight.mediakeyboard.data;

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import org.daylight.mediakeyboard.data.dto.MediaDao;
import org.daylight.mediakeyboard.data.dto.MediaEntity;

import kotlin.jvm.Volatile;

@Database(entities = [MediaEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaDao():MediaDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(ctx: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                ctx.applicationContext, AppDatabase::class.java, "media.db"
            ).build().also { INSTANCE = it }
        }
    }
}
