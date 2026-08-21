package dev.frakw.ftmsbridge.health

import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import dev.frakw.ftmsbridge.data.WorkoutWithSamples
import kotlin.math.abs

interface HealthConnectWorkoutVerifier {
    suspend fun verify(value: WorkoutWithSamples): HealthConnectVerification
}

data class HealthConnectVerification(
    val verified: Boolean,
    val sessionCount: Int,
    val exerciseType: Int?,
    val sessionVersion: Long?,
    val distanceRecordCount: Int,
    val storedDistanceMeters: Double,
    val expectedDistanceMeters: Double,
    val issues: List<HealthConnectVerificationIssue>,
)

enum class HealthConnectVerificationIssue {
    SESSION_MISSING,
    SESSION_DUPLICATED,
    SESSION_TYPE_MISMATCH,
    SESSION_INTERVAL_MISMATCH,
    SESSION_VERSION_MISMATCH,
    DISTANCE_MISSING,
    DISTANCE_UNEXPECTED,
    DISTANCE_TOTAL_MISMATCH,
    DISTANCE_VERSION_MISMATCH,
}

internal fun verifyHealthConnectRecords(
    value: WorkoutWithSamples,
    sessions: List<ExerciseSessionRecord>,
    distances: List<DistanceRecord>,
): HealthConnectVerification {
    val workout = value.workout
    val expectedEnd = requireNotNull(workout.endedAtMillis)
    val sessionId = "${workout.id}:session"
    val distancePrefix = "${workout.id}:distance-"
    val matchingSessions = sessions.filter { it.metadata.clientRecordId == sessionId }
    val matchingDistances = distances.filter { it.metadata.clientRecordId?.startsWith(distancePrefix) == true }
    val session = matchingSessions.singleOrNull()
    val storedDistance = matchingDistances.sumOf { it.distance.inMeters }
    val issues = mutableListOf<HealthConnectVerificationIssue>()

    when (matchingSessions.size) {
        0 -> issues += HealthConnectVerificationIssue.SESSION_MISSING
        1 -> Unit
        else -> issues += HealthConnectVerificationIssue.SESSION_DUPLICATED
    }
    session?.let {
        if (it.exerciseType != ExerciseSessionRecord.EXERCISE_TYPE_BIKING) {
            issues += HealthConnectVerificationIssue.SESSION_TYPE_MISMATCH
        }
        if (it.startTime.toEpochMilli() != workout.startedAtMillis || it.endTime.toEpochMilli() != expectedEnd) {
            issues += HealthConnectVerificationIssue.SESSION_INTERVAL_MISMATCH
        }
        if (it.metadata.clientRecordVersion != HealthConnectRecordMapper.CLIENT_RECORD_VERSION) {
            issues += HealthConnectVerificationIssue.SESSION_VERSION_MISMATCH
        }
    }

    if (workout.distanceMeters > 0.0 && matchingDistances.isEmpty()) {
        issues += HealthConnectVerificationIssue.DISTANCE_MISSING
    } else if (workout.distanceMeters <= 0.0 && matchingDistances.isNotEmpty()) {
        issues += HealthConnectVerificationIssue.DISTANCE_UNEXPECTED
    }
    val toleranceMeters = maxOf(0.01, workout.distanceMeters * 0.000_001)
    if (abs(storedDistance - workout.distanceMeters) > toleranceMeters) {
        issues += HealthConnectVerificationIssue.DISTANCE_TOTAL_MISMATCH
    }
    if (matchingDistances.any { it.metadata.clientRecordVersion != HealthConnectRecordMapper.CLIENT_RECORD_VERSION }) {
        issues += HealthConnectVerificationIssue.DISTANCE_VERSION_MISMATCH
    }

    return HealthConnectVerification(
        verified = issues.isEmpty(),
        sessionCount = matchingSessions.size,
        exerciseType = session?.exerciseType,
        sessionVersion = session?.metadata?.clientRecordVersion,
        distanceRecordCount = matchingDistances.size,
        storedDistanceMeters = storedDistance,
        expectedDistanceMeters = workout.distanceMeters,
        issues = issues,
    )
}
