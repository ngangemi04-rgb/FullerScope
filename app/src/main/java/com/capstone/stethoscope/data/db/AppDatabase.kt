package com.capstone.stethoscope.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// v4 renamed rhythm -> finding and confidence -> probabilityAbnormal, and added
// cycleCount, when the real classifier replaced the rhythm placeholder.
// v5 added auscultationSite + siteNoteEnc: where on the chest the recording was taken.
// v6 replaced finding/probabilityAbnormal/cycleCount with the segmenter's results
// (signalQuality, rhythm, heartRateBpm, beatCount, signalConfidence, bpmVariability).
// v7 added segmentStates: the segmenter's per-frame phases, for the segmented plot.
// v8 added murmurOutcome + murmurScore: the murmur classifier's result.
@Database(entities = [RecordingEntity::class], version = 8, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun recordingDao(): RecordingDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "stethoscope.db",
                )
                    .addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    // Schemas older than v5 are still simply recreated.
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build().also { INSTANCE = it }
            }

        /**
         * Keeps every recording and its patient details; drops the old algorithm's
         * normal/abnormal results and marks each recording un-analysed, so it is analysed
         * afresh by the new segmenter the next time it is opened. Showing a result from a
         * different algorithm under the new screen's headings would be worse than none.
         *
         * SQLite on API 26 cannot DROP COLUMN, so the table is rebuilt. [CREATE_V6] must
         * match what Room generates for [RecordingEntity] exactly, or Room rejects the
         * database on open.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(CREATE_V6)
                db.execSQL(
                    "INSERT INTO recordings_new (id, filePath, createdAt, durationMs, sampleRate, " +
                        "patientNameEnc, patientIdEnc, patientNotesEnc, siteNoteEnc, patientAgeYears, " +
                        "patientSex, auscultationSite) " +
                        "SELECT id, filePath, createdAt, durationMs, sampleRate, patientNameEnc, " +
                        "patientIdEnc, patientNotesEnc, siteNoteEnc, patientAgeYears, patientSex, " +
                        "auscultationSite FROM recordings",
                )
                db.execSQL("DROP TABLE recordings")
                db.execSQL("ALTER TABLE recordings_new RENAME TO recordings")
            }
        }

        /**
         * Adds the segmentation column. Recordings analysed under v6 have no segmentation
         * stored, so ResultViewModel re-analyses them on open.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recordings ADD COLUMN `segmentStates` TEXT")
            }
        }

        /**
         * Adds the murmur columns. Recordings analysed under v7 have no murmur result, so
         * ResultViewModel re-analyses them on open.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recordings ADD COLUMN `murmurOutcome` TEXT")
                db.execSQL("ALTER TABLE recordings ADD COLUMN `murmurScore` REAL")
            }
        }

        // v6's table, copied from Room's generated AppDatabase_Impl (table renamed to recordings_new).
        private const val CREATE_V6 =
            "CREATE TABLE IF NOT EXISTS `recordings_new` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `filePath` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, " +
                "`sampleRate` INTEGER NOT NULL, `patientNameEnc` TEXT, `patientIdEnc` TEXT, " +
                "`patientNotesEnc` TEXT, `siteNoteEnc` TEXT, `patientAgeYears` INTEGER, " +
                "`patientSex` TEXT, `auscultationSite` TEXT, `signalQuality` TEXT, `rhythm` TEXT, " +
                "`heartRateBpm` REAL, `beatCount` INTEGER, `signalConfidence` REAL, " +
                "`bpmVariability` REAL, `analyzedAt` INTEGER)"
    }
}
