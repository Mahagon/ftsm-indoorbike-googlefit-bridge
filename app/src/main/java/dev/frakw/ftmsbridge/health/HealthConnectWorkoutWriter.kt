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
import dev.frakw.ftmsbridge.R
import dev.frakw.ftmsbridge.data.WorkoutWithSamples

class HealthConnectWorkoutWriter(
    context: Context,
    private val mapper: HealthConnectRecordMapper = HealthConnectRecordMapper(),
) {
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
}
