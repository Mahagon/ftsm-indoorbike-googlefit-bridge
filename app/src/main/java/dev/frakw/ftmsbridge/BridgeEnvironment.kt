package dev.frakw.ftmsbridge

import android.content.Context
import androidx.core.content.edit
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dev.frakw.ftmsbridge.health.HealthSyncWorker
import dev.frakw.ftmsbridge.recording.RecordingService

internal interface BridgeEnvironment {
    fun isMonitoringEnabled(): Boolean

    fun setMonitoringEnabled(enabled: Boolean)

    fun lastBikeAddress(): String?

    fun setLastBikeAddress(address: String)

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

    override fun startRecordingService() = RecordingService.start(context)

    override fun stopRecordingService() = RecordingService.stop(context)

    override fun enqueueHealthSync() {
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<HealthSyncWorker>().build())
    }

    companion object {
        private const val PREFERENCES = "bike"
        private const val KEY_ADDRESS = "address"
        private const val KEY_MONITORING = "background_monitoring"
    }
}
