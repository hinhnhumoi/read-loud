package com.tung.readloud.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Novel::class, TocEntry::class, ReplaceRule::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun novels(): NovelDao
    abstract fun toc(): TocDao
    abstract fun rules(): ReplaceRuleDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `novels` ADD COLUMN `tocUrl` TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `toc_entries` (`novelId` INTEGER NOT NULL, `position` INTEGER NOT NULL, " +
                        "`title` TEXT NOT NULL, `url` TEXT NOT NULL, PRIMARY KEY(`novelId`, `position`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `replace_rules` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`pattern` TEXT NOT NULL, `replacement` TEXT NOT NULL, `isRegex` INTEGER NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)",
                )
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "readloud.db")
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}
