package dev.frakw.ftmsbridge.recording

import dev.frakw.ftmsbridge.data.SampleEntity
import dev.frakw.ftmsbridge.data.WorkoutDao
import dev.frakw.ftmsbridge.data.WorkoutEntity
import dev.frakw.ftmsbridge.model.IndoorBikeSample
import dev.frakw.ftmsbridge.model.WorkoutTarget
import java.time.Instant
import java.util.UUID
import kotlin.math.max

class WorkoutRecorder(
    private val dao: WorkoutDao,
    private val minimumWorkoutDurationMillis: Long = MINIMUM_WORKOUT_DURATION_MILLIS,
) {
    private var active: WorkoutEntity? = null
    private var lastStoredSecond: Long? = null
    private var lastSample: IndoorBikeSample? = null
    private var lastBikeDistance: Long? = null
    private var calculatedDistance = 0.0
    private var lastBikeEnergy: Int? = null
    private var calculatedEnergy = 0.0

    suspend fun restore(): WorkoutEntity? = dao.activeWorkout()?.also {
        active = it
        calculatedDistance = it.distanceMeters
        calculatedEnergy = it.caloriesKcal ?: 0.0
    }

    suspend fun start(
        at: Instant = Instant.now(),
        target: WorkoutTarget? = null,
    ): WorkoutEntity {
        val workout = WorkoutEntity(
            id = UUID.randomUUID().toString(),
            startedAtMillis = at.toEpochMilli(),
            targetDurationSeconds = (target as? WorkoutTarget.Duration)?.seconds,
            targetDistanceMeters = (target as? WorkoutTarget.Distance)?.meters,
        )
        dao.upsertWorkout(workout)
        active = workout
        lastStoredSecond = null
        lastSample = null
        lastBikeDistance = null
        calculatedDistance = 0.0
        lastBikeEnergy = null
        calculatedEnergy = 0.0
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
        sample.totalEnergyKcal?.let { energy ->
            lastBikeEnergy?.let { previousEnergy ->
                calculatedEnergy += when {
                    energy >= previousEnergy -> (energy - previousEnergy).toDouble()
                    previousEnergy > 60_000 -> (65_536 - previousEnergy + energy).toDouble()
                    else -> energy.toDouble()
                }
            }
            lastBikeEnergy = energy
        }

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
                    sessionDistanceMeters = calculatedDistance,
                    bikeEnergyKcal = sample.totalEnergyKcal,
                    sessionEnergyKcal = sample.totalEnergyKcal?.let { calculatedEnergy },
                ),
            )
            lastStoredSecond = second
            active = workout.copy(
                distanceMeters = calculatedDistance,
                caloriesKcal = sample.totalEnergyKcal?.let { calculatedEnergy },
            )
            dao.upsertWorkout(active!!)
        }
        return calculatedDistance
    }

    suspend fun stop(at: Instant = Instant.now()): WorkoutStopResult? {
        val workout = active ?: return null
        val endMillis = max(at.toEpochMilli(), workout.startedAtMillis + 1)
        if (endMillis - workout.startedAtMillis < minimumWorkoutDurationMillis) {
            dao.deleteActiveWorkout(workout.id)
            active = null
            return WorkoutStopResult.Discarded(workout)
        }
        val completed =
            workout.copy(
                endedAtMillis = endMillis,
                distanceMeters = calculatedDistance,
                caloriesKcal = lastBikeEnergy?.let { calculatedEnergy },
                state = WorkoutEntity.STATE_COMPLETE,
            )
        dao.upsertWorkout(completed)
        active = null
        return WorkoutStopResult.Completed(completed)
    }

    fun activeId(): String? = active?.id

    fun distanceMeters(): Double = calculatedDistance

    suspend fun lastSampleTime(): Instant? = active?.id?.let { id ->
        dao.latestSampleTimestamp(id)?.let(Instant::ofEpochMilli)
    }

    companion object {
        const val MINIMUM_WORKOUT_DURATION_MILLIS = 10_000L
    }
}

sealed interface WorkoutStopResult {
    val workout: WorkoutEntity

    data class Completed(override val workout: WorkoutEntity) : WorkoutStopResult

    data class Discarded(override val workout: WorkoutEntity) : WorkoutStopResult
}
