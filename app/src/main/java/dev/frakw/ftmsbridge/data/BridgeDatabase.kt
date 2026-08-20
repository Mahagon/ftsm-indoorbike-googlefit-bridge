package dev.frakw.ftmsbridge.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.frakw.ftmsbridge.retention.TrainingRetentionManager

@Database(entities = [WorkoutEntity::class, SampleEntity::class], version = 3, exportSchema = false)
abstract class BridgeDatabase : RoomDatabase() {
    abstract fun workouts(): WorkoutDao

    companion object {
        fun create(context: Context): BridgeDatabase = Room
            .databaseBuilder(
                context.applicationContext,
                BridgeDatabase::class.java,
                TrainingRetentionManager.DATABASE_NAME,
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workouts ADD COLUMN targetDurationSeconds INTEGER")
                db.execSQL("ALTER TABLE workouts ADD COLUMN targetDistanceMeters REAL")
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workouts ADD COLUMN caloriesKcal REAL")
                db.execSQL("ALTER TABLE samples ADD COLUMN sessionDistanceMeters REAL")
                db.execSQL("ALTER TABLE samples ADD COLUMN bikeEnergyKcal INTEGER")
                db.execSQL("ALTER TABLE samples ADD COLUMN sessionEnergyKcal REAL")
            }
        }
    }
}
