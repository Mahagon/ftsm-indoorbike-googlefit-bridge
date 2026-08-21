package dev.frakw.ftmsbridge.health

import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import dev.frakw.ftmsbridge.data.WorkoutEntity
import dev.frakw.ftmsbridge.data.WorkoutWithSamples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class HealthConnectWorkoutVerifierTest {
    private val start = Instant.parse("2026-08-21T08:00:00Z").toEpochMilli()
    private val value = WorkoutWithSamples(
        WorkoutEntity(
            id = "ride",
            startedAtMillis = start,
            endedAtMillis = start + 60_000,
            distanceMeters = 1_000.0,
            state = WorkoutEntity.STATE_COMPLETE,
        ),
        emptyList(),
    )
    private val mapped = HealthConnectRecordMapper(ZoneOffset.UTC).map(value)
    private val session = mapped.filterIsInstance<ExerciseSessionRecord>().single()
    private val distances = mapped.filterIsInstance<DistanceRecord>()

    @Test
    fun verifiesMappedWorkout() {
        val result = verifyHealthConnectRecords(value, listOf(session), distances)
        assertTrue(result.verified)
        assertEquals(1_000.0, result.storedDistanceMeters, 0.001)
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_BIKING, result.exerciseType)
        assertEquals(HealthConnectRecordMapper.CLIENT_RECORD_VERSION, result.sessionVersion)
    }

    @Test
    fun reportsMissingSessionAndDistance() {
        val result = verifyHealthConnectRecords(value, emptyList(), emptyList())
        assertFalse(result.verified)
        assertTrue(result.issues.contains(HealthConnectVerificationIssue.SESSION_MISSING))
        assertTrue(result.issues.contains(HealthConnectVerificationIssue.DISTANCE_MISSING))
        assertTrue(result.issues.contains(HealthConnectVerificationIssue.DISTANCE_TOTAL_MISMATCH))
    }

    @Test
    fun ignoresRecordsBelongingToAnotherWorkout() {
        val other = WorkoutWithSamples(
            value.workout.copy(id = "other"),
            emptyList(),
        )
        val otherRecords = HealthConnectRecordMapper(ZoneOffset.UTC).map(other)
        val result = verifyHealthConnectRecords(
            value,
            listOf(session) + otherRecords.filterIsInstance<ExerciseSessionRecord>(),
            distances + otherRecords.filterIsInstance<DistanceRecord>(),
        )
        assertTrue(result.verified)
        assertEquals(1, result.sessionCount)
        assertEquals(1, result.distanceRecordCount)
    }

    @Test
    fun reportsDistanceTotalMismatch() {
        val result = verifyHealthConnectRecords(value.copy(workout = value.workout.copy(distanceMeters = 1_001.0)), listOf(session), distances)
        assertFalse(result.verified)
        assertEquals(listOf(HealthConnectVerificationIssue.DISTANCE_TOTAL_MISMATCH), result.issues)
    }
}
