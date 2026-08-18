package dev.frakw.ftmsbridge

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dev.frakw.ftmsbridge.ftms.FtmsClient
import dev.frakw.ftmsbridge.health.HealthSyncWorker
import dev.frakw.ftmsbridge.model.BridgeState
import dev.frakw.ftmsbridge.model.ConnectionState
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
import java.time.Instant

class BridgeController(
    private val context: Context,
    private val client: FtmsClient,
    private val recorder: WorkoutRecorder,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(BridgeState())
    val state: StateFlow<BridgeState> = mutableState.asStateFlow()
    private var lastSampleMillis = 0L
    private var reconnectJob: Job? = null
    private val preferences = context.getSharedPreferences("bike", Context.MODE_PRIVATE)

    init {
        scope.launch {
            recorder.restore()?.let { workout ->
                mutableState.value =
                    mutableState.value.copy(
                        recordingId = workout.id,
                        startedAt = Instant.ofEpochMilli(workout.startedAtMillis),
                        distanceMeters = workout.distanceMeters,
                    )
            }
            client.state.collect { ftms ->
                val isRecording = recorder.activeId() != null
                mutableState.value =
                    mutableState.value.copy(
                        connection =
                        if (isRecording && ftms.connection == ConnectionState.READY) {
                            ConnectionState.RECORDING
                        } else {
                            ftms.connection
                        },
                        bike = ftms.selected,
                        devices = ftms.devices,
                        latest = ftms.latest,
                        rawPacket = ftms.rawPacket,
                        diagnostics = ftms.diagnostics,
                        error = ftms.error,
                    )
                val sample = ftms.latest
                if (isRecording && sample != null && sample.timestamp.toEpochMilli() != lastSampleMillis) {
                    lastSampleMillis = sample.timestamp.toEpochMilli()
                    val distance = recorder.accept(sample)
                    mutableState.value = mutableState.value.copy(distanceMeters = distance)
                }
                if (isRecording && ftms.connection in setOf(ConnectionState.DISCONNECTED, ConnectionState.ERROR)) {
                    scheduleReconnect()
                }
            }
        }
    }

    fun scan() = client.startScan()

    fun stopScan() = client.stopScan()

    fun connect(address: String) {
        preferences.edit { putString("address", address) }
        client.connect(address)
    }

    fun reconnectLastBike() {
        preferences.getString("address", null)?.let(client::connect) ?: client.startScan()
    }

    fun disconnect() = client.disconnect()

    fun startWorkout() {
        scope.launch {
            val workout = recorder.start()
            mutableState.value =
                mutableState.value.copy(
                    connection = ConnectionState.RECORDING,
                    recordingId = workout.id,
                    startedAt = Instant.ofEpochMilli(workout.startedAtMillis),
                    distanceMeters = 0.0,
                    error = null,
                )
            ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))
        }
    }

    fun stopWorkout() {
        scope.launch {
            mutableState.value = mutableState.value.copy(connection = ConnectionState.FINALIZING)
            val completed = recorder.stop()
            reconnectJob?.cancel()
            context.stopService(Intent(context, RecordingService::class.java))
            mutableState.value =
                mutableState.value.copy(
                    connection = client.state.value.connection,
                    recordingId = null,
                    startedAt = null,
                )
            if (completed != null) {
                WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<HealthSyncWorker>().build())
            }
        }
    }

    fun retryHealthSync() {
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<HealthSyncWorker>().build())
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        val address = preferences.getString("address", null) ?: return
        reconnectJob =
            scope.launch {
                var waited = 0L
                var backoff = 1_000L
                while (recorder.activeId() != null && waited < 60_000L) {
                    delay(backoff)
                    waited += backoff
                    client.connect(address)
                    delay(2_000L)
                    waited += 2_000L
                    if (client.state.value.connection == ConnectionState.READY) return@launch
                    backoff = (backoff * 2).coerceAtMost(8_000L)
                }
            }
    }
}
