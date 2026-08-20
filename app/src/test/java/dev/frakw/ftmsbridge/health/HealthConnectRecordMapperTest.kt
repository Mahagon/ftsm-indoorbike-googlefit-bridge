package dev.frakw.ftmsbridge.health

import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import dev.frakw.ftmsbridge.data.SampleEntity
import dev.frakw.ftmsbridge.data.WorkoutEntity
import dev.frakw.ftmsbridge.data.WorkoutWithSamples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class HealthConnectRecordMapperTest {
    private val start = Instant.parse("2026-08-18T12:00:00Z").toEpochMilli()
    private val mapper = HealthConnectRecordMapper(ZoneOffset.UTC)

    @Test
    fun mapsCompletedWorkoutAndAllSupportedSeries() {
        val records = mapper.map(workout(distance = 321.5, samples = listOf(sample(start + 1_000))))
        assertEquals(5, records.size)
        assertTrue(records.any { it is ExerciseSessionRecord })
        assertTrue(records.any { it is DistanceRecord })
        assertEquals(
            1,
            records
                .filterIsInstance<SpeedRecord>()
                .single()
                .samples.size,
        )
        assertEquals(
            1,
            records
                .filterIsInstance<CyclingPedalingCadenceRecord>()
                .single()
                .samples.size,
        )
        assertEquals(
            1,
            records
                .filterIsInstance<PowerRecord>()
                .single()
                .samples.size,
        )
        assertEquals(
            "ride-1:session",
            records
                .filterIsInstance<ExerciseSessionRecord>()
                .single()
                .metadata.clientRecordId,
        )
    }

    @Test
    fun clipsSamplesOutsideWorkoutAndSortsRemainingSamples() {
        val records =
            mapper.map(
                workout(
                    samples =
                    listOf(
                        sample(start + 8_000),
                        sample(start - 1),
                        sample(start + 2_000),
                        sample(start + 10_001),
                    ),
                ),
            )
        val times =
            records
                .filterIsInstance<SpeedRecord>()
                .single()
                .samples
                .map { it.time.toEpochMilli() }
        assertEquals(listOf(start + 2_000, start + 8_000), times)
    }

    @Test
    fun omitsDistanceAndEmptyMetricSeries() {
        val records = mapper.map(workout(distance = 0.0, samples = emptyList()))
        assertEquals(1, records.size)
        assertTrue(records.single() is ExerciseSessionRecord)
        assertFalse(records.any { it is DistanceRecord })
    }

    @Test
    fun rejectsActiveWorkout() {
        val value = WorkoutWithSamples(WorkoutEntity("active", start), emptyList())
        assertThrows(IllegalArgumentException::class.java) { mapper.map(value) }
    }

    @Test
    fun splitsDistanceIntoMinuteIntervalsAndPreservesTotal() {
        val samples = listOf(
            sample(start + 30_000).copy(sessionDistanceMeters = 100.0),
            sample(start + 70_000).copy(sessionDistanceMeters = 250.0),
        )
        val value = WorkoutWithSamples(
            WorkoutEntity("ride-1", start, start + 120_000, 300.0, WorkoutEntity.STATE_COMPLETE),
            samples,
        )
        val distances = mapper.map(value).filterIsInstance<DistanceRecord>()
        assertEquals(2, distances.size)
        assertEquals(300.0, distances.sumOf { it.distance.inMeters }, 0.001)
        assertTrue(distances.zipWithNext().all { (first, second) -> first.endTime <= second.startTime })
    }

    @Test
    fun exportsReportedCaloriesWithLocalizedTitle() {
        val value = WorkoutWithSamples(
            WorkoutEntity(
                id = "ride-1",
                startedAtMillis = start,
                endedAtMillis = start + 10_000,
                state = WorkoutEntity.STATE_COMPLETE,
                caloriesKcal = 42.0,
            ),
            emptyList(),
        )
        val records = mapper.map(value, "Heimtrainer")
        assertEquals("Heimtrainer", records.filterIsInstance<ExerciseSessionRecord>().single().title)
        assertEquals(42.0, records.filterIsInstance<TotalCaloriesBurnedRecord>().single().energy.inKilocalories, 0.001)
    }

    private fun workout(
        distance: Double = 0.0,
        samples: List<SampleEntity> = emptyList(),
    ) = WorkoutWithSamples(
        WorkoutEntity("ride-1", start, start + 10_000, distance, WorkoutEntity.STATE_COMPLETE),
        samples,
    )

    private fun sample(timestamp: Long) = SampleEntity("ride-1", timestamp, 25.0, 80.0, 180, 100)
}
