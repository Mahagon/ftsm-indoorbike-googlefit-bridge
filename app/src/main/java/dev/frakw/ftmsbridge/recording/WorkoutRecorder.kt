package dev.frakw.ftmsbridge.recording

import dev.frakw.ftmsbridge.data.SampleEntity
import dev.frakw.ftmsbridge.data.WorkoutDao
import dev.frakw.ftmsbridge.data.WorkoutEntity
import dev.frakw.ftmsbridge.model.IndoorBikeSample
import java.time.Instant
import java.util.UUID
import kotlin.math.max

class WorkoutRecorder(
    private val dao: WorkoutDao,
) {
    private var active: WorkoutEntity? = null
    private var lastStoredSecond: Long? = null
    private var lastSample: IndoorBikeSample? = null
    private var lastBikeDistance: Long? = null
    private var calculatedDistance = 0.0

    suspend fun restore(): WorkoutEntity? = dao.activeWorkout()?.also {
        active = it
        calculatedDistance = it.distanceMeters
    }

    suspend fun start(at: Instant = Instant.now()): WorkoutEntity {
        val workout = WorkoutEntity(id = UUID.randomUUID().toString(), startedAtMillis = at.toEpochMilli())
        dao.upsertWorkout(workout)
        active = workout
        lastStoredSecond = null
        lastSample = null
        lastBikeDistance = null
        calculatedDistance = 0.0
        return workout
    }

    suspend fun accept(sample: IndoorBikeSample): Double {
        val workout = active ?: return calculatedDistance
        val previous = lastSample
        val bikeDistance = sample.totalDistanceMeters
        if (bikeDistance != null) {
            lastBikeDistance?.let { previousDistance ->
                if (bikeDistance >= previousDistance) calculatedDistance += bikeDistance - previousDistance
            }
            lastBikeDistance = bikeDistance
        } else if (previous?.speedKph != null && sample.speedKph != null) {
            val seconds = (sample.timestamp.toEpochMilli() - previous.timestamp.toEpochMilli()) / 1000.0
            if (seconds in 0.0..10.0) {
                calculatedDistance += ((previous.speedKph + sample.speedKph) / 2.0) / 3.6 * seconds
            }
        }
        lastSample = sample

        val second = sample.timestamp.epochSecond
        if (second != lastStoredSecond) {
            dao.upsertSample(
                SampleEntity(
                    workoutId = workout.id,
                    timestampMillis = sample.timestamp.toEpochMilli(),
                    speedKph = sample.speedKph,
                    cadenceRpm = sample.cadenceRpm,
                    powerWatts = sample.powerWatts,
                    bikeDistanceMeters = sample.totalDistanceMeters,
                ),
            )
            lastStoredSecond = second
            active = workout.copy(distanceMeters = calculatedDistance)
            dao.upsertWorkout(active!!)
        }
        return calculatedDistance
    }

    suspend fun stop(at: Instant = Instant.now()): WorkoutEntity? {
        val workout = active ?: return null
        val completed =
            workout.copy(
                endedAtMillis = max(at.toEpochMilli(), workout.startedAtMillis + 1),
                distanceMeters = calculatedDistance,
                state = WorkoutEntity.STATE_COMPLETE,
            )
        dao.upsertWorkout(completed)
        active = null
        return completed
    }

    fun activeId(): String? = active?.id

    fun distanceMeters(): Double = calculatedDistance
}
