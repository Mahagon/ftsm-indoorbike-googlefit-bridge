package dev.frakw.ftmsbridge.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dev.frakw.ftmsbridge.R
import dev.frakw.ftmsbridge.data.WorkoutWithSamples
import java.time.Instant

class HealthConnectWorkoutWriter(
    context: Context,
    private val mapper: HealthConnectRecordMapper = HealthConnectRecordMapper(),
) : HealthConnectWorkoutVerifier {
    private val appContext = context.applicationContext
    private val client = HealthConnectClient.getOrCreate(appContext)

    val permissions: Set<String> =
        setOf(
            HealthPermission.getWritePermission(ExerciseSessionRecord::class),
            HealthPermission.getWritePermission(SpeedRecord::class),
            HealthPermission.getWritePermission(DistanceRecord::class),
            HealthPermission.getWritePermission(PowerRecord::class),
            HealthPermission.getWritePermission(CyclingPedalingCadenceRecord::class),
            HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class),
        )

    suspend fun hasPermissions(): Boolean = client.permissionController.getGrantedPermissions().containsAll(permissions)

    suspend fun write(value: WorkoutWithSamples) {
        mapper.map(value, appContext.getString(R.string.indoor_bike)).chunked(1000).forEach { client.insertRecords(it) }
    }

    override suspend fun verify(value: WorkoutWithSamples): HealthConnectVerification {
        val workout = value.workout
        val endMillis = requireNotNull(workout.endedAtMillis)
        val filter = TimeRangeFilter.between(
            Instant.ofEpochMilli(workout.startedAtMillis).minusSeconds(1),
            Instant.ofEpochMilli(endMillis).plusSeconds(1),
        )
        val origins = setOf(DataOrigin(appContext.packageName))
        val sessions = client.readRecords(
            ReadRecordsRequest(
                recordType = ExerciseSessionRecord::class,
                timeRangeFilter = filter,
                dataOriginFilter = origins,
            ),
        ).records
        val distances = client.readRecords(
            ReadRecordsRequest(
                recordType = DistanceRecord::class,
                timeRangeFilter = filter,
                dataOriginFilter = origins,
            ),
        ).records
        return verifyHealthConnectRecords(value, sessions, distances)
    }
}
