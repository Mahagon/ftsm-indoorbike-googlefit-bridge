package dev.frakw.ftmsbridge.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.frakw.ftmsbridge.retention.TrainingRetentionManager

@Database(entities = [WorkoutEntity::class, SampleEntity::class], version = 2, exportSchema = false)
abstract class BridgeDatabase : RoomDatabase() {
    abstract fun workouts(): WorkoutDao

    companion object {
        fun create(context: Context): BridgeDatabase = Room
            .databaseBuilder(
                context.applicationContext,
                BridgeDatabase::class.java,
                TrainingRetentionManager.DATABASE_NAME,
            ).addMigrations(MIGRATION_1_2).build()

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workouts ADD COLUMN targetDurationSeconds INTEGER")
                db.execSQL("ALTER TABLE workouts ADD COLUMN targetDistanceMeters REAL")
            }
        }
    }
}
