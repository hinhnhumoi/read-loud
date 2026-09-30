package com.tung.readloud.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Novel::class, TocEntry::class, ReplaceRule::class, Bookmark::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun novels(): NovelDao
    abstract fun toc(): TocDao
    abstract fun rules(): ReplaceRuleDao
    abstract fun bookmarks(): BookmarkDao

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

        /** Listening time, new-chapter tracking, per-novel voice settings and rules, and bookmarks. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `novels` ADD COLUMN `listenedMs` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `novels` ADD COLUMN `followNew` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `novels` ADD COLUMN `seenTocCount` INTEGER")
                db.execSQL("ALTER TABLE `novels` ADD COLUMN `voice` TEXT")
                db.execSQL("ALTER TABLE `novels` ADD COLUMN `rate` REAL")
                db.execSQL("ALTER TABLE `novels` ADD COLUMN `skipAuthorNotes` INTEGER NOT NULL DEFAULT 0")
                // Chapter lists saved before now count as seen, so nothing is flagged new on upgrade.
                db.execSQL(
                    "UPDATE `novels` SET `seenTocCount` = (SELECT COUNT(*) FROM `toc_entries` WHERE `toc_entries`.`novelId` = `novels`.`id`) " +
                        "WHERE EXISTS (SELECT 1 FROM `toc_entries` WHERE `toc_entries`.`novelId` = `novels`.`id`)",
                )
                db.execSQL("ALTER TABLE `replace_rules` ADD COLUMN `novelId` INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bookmarks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`novelId` INTEGER NOT NULL, `chapterUrl` TEXT NOT NULL, `chapterTitle` TEXT NOT NULL, " +
                        "`chunkIndex` INTEGER NOT NULL, `text` TEXT NOT NULL, `note` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_novelId` ON `bookmarks` (`novelId`)")
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "readloud.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                .also { instance = it }
        }
    }
}
