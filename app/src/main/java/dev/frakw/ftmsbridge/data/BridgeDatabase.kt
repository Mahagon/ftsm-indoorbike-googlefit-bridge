package dev.frakw.ftmsbridge.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [WorkoutEntity::class, SampleEntity::class], version = 1, exportSchema = false)
abstract class BridgeDatabase : RoomDatabase() {
    abstract fun workouts(): WorkoutDao

    companion object {
        fun create(context: Context): BridgeDatabase = Room
            .databaseBuilder(
                context.applicationContext,
                BridgeDatabase::class.java,
                "ftms-bridge.db",
            ).build()
    }
}
