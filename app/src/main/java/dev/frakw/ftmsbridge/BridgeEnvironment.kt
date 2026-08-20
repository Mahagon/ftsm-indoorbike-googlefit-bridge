package dev.frakw.ftmsbridge

import android.content.Context
import androidx.core.content.edit
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dev.frakw.ftmsbridge.health.HealthSyncWorker
import dev.frakw.ftmsbridge.model.WorkoutTarget
import dev.frakw.ftmsbridge.recording.RecordingService
import dev.frakw.ftmsbridge.retention.TrainingRetentionManager

internal interface BridgeEnvironment {
    fun isMonitoringEnabled(): Boolean

    fun setMonitoringEnabled(enabled: Boolean)

    fun lastBikeAddress(): String?

    fun setLastBikeAddress(address: String)

    fun pendingTarget(): WorkoutTarget?

    fun setPendingTarget(target: WorkoutTarget?)

    fun startRecordingService()

    fun stopRecordingService()

    fun enqueueHealthSync()
}

internal class AndroidBridgeEnvironment(
    private val context: Context,
) : BridgeEnvironment {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun isMonitoringEnabled(): Boolean = preferences.getBoolean(KEY_MONITORING, false)

    override fun setMonitoringEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_MONITORING, enabled) }
    }

    override fun lastBikeAddress(): String? = preferences.getString(KEY_ADDRESS, null)

    override fun setLastBikeAddress(address: String) {
        preferences.edit { putString(KEY_ADDRESS, address) }
    }

    override fun pendingTarget(): WorkoutTarget? = when (preferences.getString(KEY_TARGET_TYPE, null)) {
        TARGET_DURATION -> preferences.getLong(KEY_TARGET_VALUE, 0).takeIf { it > 0 }?.let(WorkoutTarget::Duration)

        TARGET_DISTANCE -> Double.fromBits(preferences.getLong(KEY_TARGET_VALUE, 0))
            .takeIf { it.isFinite() && it > 0 }
            ?.let(WorkoutTarget::Distance)

        else -> null
    }

    override fun setPendingTarget(target: WorkoutTarget?) {
        preferences.edit {
            when (target) {
                is WorkoutTarget.Duration -> {
                    putString(KEY_TARGET_TYPE, TARGET_DURATION)
                    putLong(KEY_TARGET_VALUE, target.seconds)
                }

                is WorkoutTarget.Distance -> {
                    putString(KEY_TARGET_TYPE, TARGET_DISTANCE)
                    putLong(KEY_TARGET_VALUE, target.meters.toBits())
                }

                null -> {
                    remove(KEY_TARGET_TYPE)
                    remove(KEY_TARGET_VALUE)
                }
            }
        }
    }

    override fun startRecordingService() = RecordingService.start(context)

    override fun stopRecordingService() = RecordingService.stop(context)

    override fun enqueueHealthSync() {
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<HealthSyncWorker>().build())
        TrainingRetentionManager.schedule(context)
    }

    companion object {
        private const val PREFERENCES = "bike"
        private const val KEY_ADDRESS = "address"
        private const val KEY_MONITORING = "background_monitoring"
        private const val KEY_TARGET_TYPE = "pending_target_type"
        private const val KEY_TARGET_VALUE = "pending_target_value"
        private const val TARGET_DURATION = "duration"
        private const val TARGET_DISTANCE = "distance"
    }
}
