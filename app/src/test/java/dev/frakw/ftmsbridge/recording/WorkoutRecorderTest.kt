package dev.frakw.ftmsbridge.recording

import dev.frakw.ftmsbridge.data.SampleEntity
import dev.frakw.ftmsbridge.data.WorkoutDao
import dev.frakw.ftmsbridge.data.WorkoutEntity
import dev.frakw.ftmsbridge.data.WorkoutWithSamples
import dev.frakw.ftmsbridge.model.IndoorBikeSample
import dev.frakw.ftmsbridge.model.WorkoutTarget
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.Instant

class WorkoutRecorderTest {
    @Test
    fun `start persists selected target`() = runTest {
        val dao = FakeDao()
        val recorder = WorkoutRecorder(dao)
        val duration = recorder.start(Instant.EPOCH, WorkoutTarget.Duration(1_200))
        assertEquals(1_200L, duration.targetDurationSeconds)
        assertEquals(null, duration.targetDistanceMeters)

        recorder.stop(Instant.EPOCH.plusSeconds(1))
        val distance = recorder.start(Instant.EPOCH.plusSeconds(2), WorkoutTarget.Distance(5_500.0))
        assertEquals(5_500.0, distance.targetDistanceMeters ?: 0.0, 0.0)
        assertEquals(null, distance.targetDurationSeconds)
    }

    @Test
    fun integratesSpeedWhenBikeDistanceIsMissing() = runTest {
        val dao = FakeDao()
        val recorder = WorkoutRecorder(dao)
        val start = Instant.parse("2026-08-18T12:00:00Z")
        recorder.start(start)
        recorder.accept(IndoorBikeSample(start, 36.0, 90.0, 200, null, null))
        val distance = recorder.accept(IndoorBikeSample(start.plusSeconds(10), 36.0, 90.0, 200, null, null))
        assertEquals(100.0, distance, 0.001)
    }

    @Test
    fun storesAtMostOneSamplePerSecond() = runTest {
        val dao = FakeDao()
        val recorder = WorkoutRecorder(dao)
        val start = Instant.parse("2026-08-18T12:00:00Z")
        recorder.start(start)
        recorder.accept(IndoorBikeSample(start, 20.0, null, null, null, null))
        recorder.accept(IndoorBikeSample(start.plusMillis(500), 21.0, null, null, null, null))
        recorder.accept(IndoorBikeSample(start.plusSeconds(1), 22.0, null, null, null, null))
        assertEquals(2, dao.samples.size)
        assertNotNull(recorder.stop(start.plusSeconds(2)))
    }

    @Test
    fun accumulatesBikeDistanceAcrossCounterReset() = runTest {
        val recorder = WorkoutRecorder(FakeDao())
        val start = Instant.parse("2026-08-18T12:00:00Z")
        recorder.start(start)
        recorder.accept(IndoorBikeSample(start, null, null, null, 1_000, null))
        recorder.accept(IndoorBikeSample(start.plusSeconds(1), null, null, null, 1_120, null))
        recorder.accept(IndoorBikeSample(start.plusSeconds(2), null, null, null, 5, null))
        val distance = recorder.accept(IndoorBikeSample(start.plusSeconds(3), null, null, null, 35, null))
        assertEquals(150.0, distance, 0.001)
    }

    @Test
    fun restorePreservesPreviouslyCalculatedDistance() = runTest {
        val dao = FakeDao()
        val start = Instant.parse("2026-08-18T12:00:00Z")
        dao.upsertWorkout(WorkoutEntity("restored", start.toEpochMilli(), distanceMeters = 42.0))
        val recorder = WorkoutRecorder(dao)
        assertNotNull(recorder.restore())
        assertEquals(42.0, recorder.distanceMeters(), 0.001)
    }

    @Test
    fun stopCompletesWorkoutAndKeepsDistance() = runTest {
        val dao = FakeDao()
        val recorder = WorkoutRecorder(dao)
        val start = Instant.parse("2026-08-18T12:00:00Z")
        recorder.start(start)
        recorder.accept(IndoorBikeSample(start, null, null, null, 500, null))
        recorder.accept(IndoorBikeSample(start.plusSeconds(1), null, null, null, 575, null))
        val completed = recorder.stop(start)
        assertEquals(WorkoutEntity.STATE_COMPLETE, completed?.state)
        assertEquals(75.0, completed?.distanceMeters ?: 0.0, 0.001)
        assertEquals(start.toEpochMilli() + 1, completed?.endedAtMillis)
    }

    private class FakeDao : WorkoutDao {
        val workouts = linkedMapOf<String, WorkoutEntity>()
        val samples = linkedMapOf<Pair<String, Long>, SampleEntity>()

        override suspend fun upsertWorkout(workout: WorkoutEntity) {
            workouts[workout.id] = workout
        }

        override suspend fun upsertSample(sample: SampleEntity) {
            samples[sample.workoutId to sample.timestampMillis] = sample
        }

        override suspend fun activeWorkout() = workouts.values.lastOrNull { it.state == WorkoutEntity.STATE_ACTIVE }

        override suspend fun latestSampleTimestamp(workoutId: String) = samples.values.filter { it.workoutId == workoutId }.maxOfOrNull { it.timestampMillis }

        override suspend fun workout(id: String) = workouts[id]?.let { workout ->
            WorkoutWithSamples(workout, samples.values.filter { it.workoutId == id })
        }

        override fun completedWorkouts(limit: Int) = flowOf(
            workouts.values.filter { it.state == WorkoutEntity.STATE_COMPLETE }.sortedByDescending { it.startedAtMillis }.take(limit),
        )

        override suspend fun completedWorkoutsForRetention() = workouts.values.filter { it.state == WorkoutEntity.STATE_COMPLETE }.sortedBy { it.startedAtMillis }

        override suspend fun deleteCompletedWorkout(id: String): Int {
            val removed = workouts[id]?.takeIf { it.state == WorkoutEntity.STATE_COMPLETE } ?: return 0
            workouts.remove(removed.id)
            samples.entries.removeAll { it.value.workoutId == removed.id }
            return 1
        }

        override fun observeCompletedWorkout(id: String) = flowOf(
            workouts[id]
                ?.takeIf { it.state == WorkoutEntity.STATE_COMPLETE }
                ?.let { WorkoutWithSamples(it, samples.values.filter { sample -> sample.workoutId == id }) },
        )

        override suspend fun pendingSync() = workouts.values.filter { it.state == WorkoutEntity.STATE_COMPLETE && !it.synced }

        override suspend fun markSynced(id: String) {
            workouts[id]?.let { workouts[id] = it.copy(synced = true) }
        }

        override suspend fun markSyncFailed(
            id: String,
            message: String,
        ) {
            workouts[id]?.let { workouts[id] = it.copy(syncError = message) }
        }
    }
}
