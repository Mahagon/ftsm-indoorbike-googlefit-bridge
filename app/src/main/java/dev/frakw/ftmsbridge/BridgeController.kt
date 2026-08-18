package dev.frakw.ftmsbridge

import android.content.Context
import androidx.core.content.edit
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dev.frakw.ftmsbridge.ftms.FtmsClient
import dev.frakw.ftmsbridge.health.HealthSyncWorker
import dev.frakw.ftmsbridge.model.BridgeState
import dev.frakw.ftmsbridge.model.ConnectionState
import dev.frakw.ftmsbridge.model.IndoorBikeSample
import dev.frakw.ftmsbridge.recording.RecordingService
import dev.frakw.ftmsbridge.recording.WorkoutRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

class BridgeController(
    private val context: Context,
    private val client: FtmsClient,
    private val recorder: WorkoutRecorder,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(BridgeState())
    val state: StateFlow<BridgeState> = mutableState.asStateFlow()
    private val workoutMutex = Mutex()
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private var lastSampleMillis = 0L
    private var lastMeasurementAt: Instant? = null
    private var disconnectJob: Job? = null
    private var retryJob: Job? = null
    private var autoStartSuppressed = false

    init {
        scope.launch {
            val monitoring = isMonitoringEnabled(context)
            val restored = recorder.restore()
            restored?.let { workout ->
                lastMeasurementAt = recorder.lastSampleTime()
                lastSampleMillis = lastMeasurementAt?.toEpochMilli() ?: 0L
                mutableState.value =
                    mutableState.value.copy(
                        recordingId = workout.id,
                        startedAt = Instant.ofEpochMilli(workout.startedAtMillis),
                        distanceMeters = workout.distanceMeters,
                    )
            }
            mutableState.value = mutableState.value.copy(monitoringEnabled = monitoring)
            if (restored != null) scheduleDisconnectFinalization(Instant.now())

            client.state.collect { ftms ->
                val previousConnection = mutableState.value.connection
                val isRecording = recorder.activeId() != null
                mutableState.value =
                    mutableState.value.copy(
                        connection = displayConnection(ftms.connection, isRecording),
                        bike = ftms.selected,
                        devices = ftms.devices,
                        latest = ftms.latest,
                        rawPacket = ftms.rawPacket,
                        diagnostics = ftms.diagnostics,
                        error = ftms.error,
                    )

                if (ftms.connection == ConnectionState.READY) {
                    disconnectJob?.cancel()
                    disconnectJob = null
                    retryJob?.cancel()
                    retryJob = null
                    mutableState.value = mutableState.value.copy(reconnectDeadline = null)
                }

                val sample = ftms.latest
                if (sample != null && sample.timestamp.toEpochMilli() != lastSampleMillis) handleSample(sample)

                if (ftms.connection in setOf(ConnectionState.DISCONNECTED, ConnectionState.ERROR)) {
                    if (previousConnection in setOf(ConnectionState.READY, ConnectionState.RECORDING)) {
                        autoStartSuppressed = false
                    }
                    if (recorder.activeId() != null) scheduleDisconnectFinalization(Instant.now())
                    if (mutableState.value.monitoringEnabled && ftms.connection == ConnectionState.ERROR) {
                        scheduleConnectRetry()
                    }
                }
            }
        }
    }

    fun scan() = client.startScan()

    fun stopScan() = client.stopScan()

    fun connect(address: String) {
        preferences.edit { putString(KEY_ADDRESS, address) }
        client.connect(address, mutableState.value.monitoringEnabled)
    }

    fun reconnectLastBike() {
        preferences.getString(KEY_ADDRESS, null)?.let {
            client.connect(it, mutableState.value.monitoringEnabled)
        } ?: client.startScan()
    }

    fun resumeMonitoring() {
        if (!isMonitoringEnabled(context)) return
        mutableState.value = mutableState.value.copy(monitoringEnabled = true)
        preferences.getString(KEY_ADDRESS, null)?.let { client.connect(it, autoConnect = true) }
    }

    fun setMonitoringEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_MONITORING, enabled) }
        mutableState.value = mutableState.value.copy(monitoringEnabled = enabled)
        if (enabled) {
            RecordingService.start(context)
        } else {
            scope.launch {
                workoutMutex.withLock { finishActiveWorkout(lastMeasurementAt ?: Instant.now()) }
                disconnectJob?.cancel()
                retryJob?.cancel()
                client.disconnect()
                RecordingService.stop(context)
            }
        }
    }

    fun disconnect() = client.disconnect()

    fun startWorkout() {
        scope.launch {
            workoutMutex.withLock {
                if (recorder.activeId() == null) startWorkoutLocked()
            }
            RecordingService.start(context)
        }
    }

    fun stopWorkout() {
        scope.launch {
            workoutMutex.withLock {
                autoStartSuppressed = mutableState.value.monitoringEnabled
                finishActiveWorkout(lastMeasurementAt ?: Instant.now())
            }
            if (!mutableState.value.monitoringEnabled) RecordingService.stop(context)
        }
    }

    fun retryHealthSync() = enqueueHealthSync()

    private suspend fun handleSample(sample: IndoorBikeSample) {
        if (!hasMeasurement(sample)) return
        workoutMutex.withLock {
            lastSampleMillis = sample.timestamp.toEpochMilli()
            lastMeasurementAt = sample.timestamp
            if (mutableState.value.monitoringEnabled && recorder.activeId() == null && !autoStartSuppressed) {
                startWorkoutLocked(sample.timestamp)
            }
            if (recorder.activeId() != null) {
                val distance = recorder.accept(sample)
                mutableState.value =
                    mutableState.value.copy(
                        connection = ConnectionState.RECORDING,
                        distanceMeters = distance,
                    )
            }
        }
    }

    private suspend fun startWorkoutLocked(at: Instant = Instant.now()) {
        val workout = recorder.start(at)
        mutableState.value =
            mutableState.value.copy(
                connection = ConnectionState.RECORDING,
                recordingId = workout.id,
                startedAt = Instant.ofEpochMilli(workout.startedAtMillis),
                distanceMeters = 0.0,
                error = null,
            )
    }

    private fun scheduleDisconnectFinalization(disconnectedAt: Instant) {
        if (disconnectJob?.isActive == true) return
        val lastData = lastMeasurementAt ?: disconnectedAt
        val deadline = lastData.plusMillis(DISCONNECT_GRACE_MILLIS)
        mutableState.value = mutableState.value.copy(reconnectDeadline = deadline)
        disconnectJob =
            scope.launch {
                val remaining = deadline.toEpochMilli() - System.currentTimeMillis()
                if (remaining > 0) delay(remaining)
                workoutMutex.withLock { finishActiveWorkout(lastData) }
                mutableState.value = mutableState.value.copy(reconnectDeadline = null)
            }
    }

    private fun scheduleConnectRetry() {
        if (retryJob?.isActive == true) return
        val address = preferences.getString(KEY_ADDRESS, null) ?: return
        retryJob =
            scope.launch {
                delay(RETRY_MILLIS)
                if (mutableState.value.monitoringEnabled && client.state.value.connection != ConnectionState.READY) {
                    client.connect(address, autoConnect = true)
                }
            }
    }

    private suspend fun finishActiveWorkout(at: Instant) {
        if (recorder.activeId() == null) return
        mutableState.value = mutableState.value.copy(connection = ConnectionState.FINALIZING)
        val completed = recorder.stop(at)
        mutableState.value =
            mutableState.value.copy(
                connection = displayConnection(client.state.value.connection, false),
                recordingId = null,
                startedAt = null,
            )
        if (completed != null) enqueueHealthSync()
    }

    private fun enqueueHealthSync() {
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<HealthSyncWorker>().build())
    }

    private fun displayConnection(
        connection: ConnectionState,
        isRecording: Boolean,
    ) = if (isRecording && connection == ConnectionState.READY) ConnectionState.RECORDING else connection

    private fun hasMeasurement(sample: IndoorBikeSample) = sample.speedKph != null ||
        sample.cadenceRpm != null ||
        sample.powerWatts != null ||
        sample.totalDistanceMeters != null ||
        sample.elapsedTimeSeconds != null

    companion object {
        private const val PREFERENCES = "bike"
        private const val KEY_ADDRESS = "address"
        private const val KEY_MONITORING = "background_monitoring"
        private const val DISCONNECT_GRACE_MILLIS = 5 * 60 * 1000L
        private const val RETRY_MILLIS = 10_000L

        fun isMonitoringEnabled(context: Context): Boolean = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(KEY_MONITORING, false)
    }
}
