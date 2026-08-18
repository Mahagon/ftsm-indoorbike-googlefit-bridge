package dev.frakw.ftmsbridge.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface WorkoutDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorkout(workout: WorkoutEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSample(sample: SampleEntity)

    @Query("SELECT * FROM workouts WHERE state = 'ACTIVE' ORDER BY startedAtMillis DESC LIMIT 1")
    suspend fun activeWorkout(): WorkoutEntity?

    @Query("SELECT MAX(timestampMillis) FROM samples WHERE workoutId = :workoutId")
    suspend fun latestSampleTimestamp(workoutId: String): Long?

    @Transaction
    @Query("SELECT * FROM workouts WHERE id = :id")
    suspend fun workout(id: String): WorkoutWithSamples?

    @Query("SELECT * FROM workouts WHERE state = 'COMPLETE' AND synced = 0 ORDER BY startedAtMillis")
    suspend fun pendingSync(): List<WorkoutEntity>

    @Query("UPDATE workouts SET synced = 1, syncError = NULL WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("UPDATE workouts SET syncError = :message WHERE id = :id")
    suspend fun markSyncFailed(
        id: String,
        message: String,
    )
}
