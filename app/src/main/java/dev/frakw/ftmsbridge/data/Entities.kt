package dev.frakw.ftmsbridge.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "workouts")
data class WorkoutEntity(
    @PrimaryKey val id: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long? = null,
    val distanceMeters: Double = 0.0,
    val state: String = STATE_ACTIVE,
    val synced: Boolean = false,
    val syncError: String? = null,
) {
    companion object {
        const val STATE_ACTIVE = "ACTIVE"
        const val STATE_COMPLETE = "COMPLETE"
    }
}

@Entity(
    tableName = "samples",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutEntity::class,
            parentColumns = ["id"],
            childColumns = ["workoutId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("workoutId")],
    primaryKeys = ["workoutId", "timestampMillis"],
)
data class SampleEntity(
    val workoutId: String,
    val timestampMillis: Long,
    val speedKph: Double?,
    val cadenceRpm: Double?,
    val powerWatts: Int?,
    val bikeDistanceMeters: Long?,
)

data class WorkoutWithSamples(
    @androidx.room.Embedded val workout: WorkoutEntity,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "workoutId")
    val samples: List<SampleEntity>,
)
