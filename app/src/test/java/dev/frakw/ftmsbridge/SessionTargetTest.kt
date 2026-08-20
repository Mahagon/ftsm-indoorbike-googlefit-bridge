package dev.frakw.ftmsbridge

import dev.frakw.ftmsbridge.data.WorkoutEntity
import dev.frakw.ftmsbridge.model.WorkoutTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTargetTest {
    @Test
    fun `parses whole minutes and decimal kilometers`() {
        assertEquals(WorkoutTarget.Duration(1_800), parseTarget(TargetKind.DURATION, "30"))
        assertEquals(WorkoutTarget.Distance(12_500.0), parseTarget(TargetKind.DISTANCE, "12.5"))
        assertEquals(WorkoutTarget.Distance(12_500.0), parseTarget(TargetKind.DISTANCE, "12,5"))
    }

    @Test
    fun `rejects invalid target input`() {
        listOf("", "0", "-1", "1.5", "text").forEach { assertNull(parseTarget(TargetKind.DURATION, it)) }
        listOf("", "0", "-1", "NaN", "Infinity", "text").forEach { assertNull(parseTarget(TargetKind.DISTANCE, it)) }
    }

    @Test
    fun `progress is proportional and clamps overshoot`() {
        val duration = WorkoutTarget.Duration(600)
        assertEquals(0f, targetProgress(duration, 0, 0.0) ?: -1f, 0f)
        assertEquals(0.5f, targetProgress(duration, 300, 0.0) ?: -1f, 0.001f)
        assertEquals(1f, targetProgress(duration, 900, 0.0) ?: -1f, 0f)

        val distance = WorkoutTarget.Distance(10_000.0)
        assertEquals(0.25f, targetProgress(distance, 0, 2_500.0) ?: -1f, 0.001f)
        assertEquals(1f, targetProgress(distance, 0, 12_000.0) ?: -1f, 0f)
        assertNull(targetProgress(null, 100, 100.0))
    }

    @Test
    fun `completed workout target result uses matching metric`() {
        val duration = workout(targetDurationSeconds = 600, endedAtMillis = 601_000)
        assertTrue(targetReached(duration) == true)
        assertFalse(targetReached(duration.copy(endedAtMillis = 599_000)) == true)

        val distance = workout(targetDistanceMeters = 10_000.0, distanceMeters = 10_000.0)
        assertTrue(targetReached(distance) == true)
        assertFalse(targetReached(distance.copy(distanceMeters = 9_999.0)) == true)
        assertNull(targetReached(workout()))
    }

    private fun workout(
        endedAtMillis: Long = 1_000,
        distanceMeters: Double = 0.0,
        targetDurationSeconds: Long? = null,
        targetDistanceMeters: Double? = null,
    ) = WorkoutEntity(
        id = "ride",
        startedAtMillis = 1_000,
        endedAtMillis = endedAtMillis,
        distanceMeters = distanceMeters,
        state = WorkoutEntity.STATE_COMPLETE,
        targetDurationSeconds = targetDurationSeconds,
        targetDistanceMeters = targetDistanceMeters,
    )
}
