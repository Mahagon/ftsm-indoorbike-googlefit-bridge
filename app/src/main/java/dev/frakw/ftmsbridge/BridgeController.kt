package dev.frakw.ftmsbridge

import android.content.Context
import dev.frakw.ftmsbridge.data.target
import dev.frakw.ftmsbridge.ftms.FtmsClient
import dev.frakw.ftmsbridge.model.BridgeState
import dev.frakw.ftmsbridge.model.ConnectionState
import dev.frakw.ftmsbridge.model.IndoorBikeSample
import dev.frakw.ftmsbridge.model.WorkoutTarget
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
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant

class BridgeController internal constructor(
    private val client: FtmsClient,
    private val recorder: WorkoutRecorder,
    private val environment: BridgeEnvironment,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: Clock = Clock.systemUTC(),
    private val disconnectGraceMillis: Long = DISCONNECT_GRACE_MILLIS,
    private val retryMillis: Long = RETRY_MILLIS,
) {
    private val mutableState = MutableStateFlow(
        BridgeState(
            monitoringEnabled = environment.isMonitoringEnabled(),
            target = environment.pendingTarget(),
        ),
    )
    val state: StateFlow<BridgeState> = mutableState.asStateFlow()
    private val workoutMutex = Mutex()
    private var lastSampleMillis = 0L
    private var lastMeasurementAt: Instant? = null
    private var disconnectJob: Job? = null
    private var retryJob: Job? = null
    private var autoStartSuppressed = false

    constructor(
        context: Context,
        client: FtmsClient,
        recorder: WorkoutRecorder,
    ) : this(client, recorder, AndroidBridgeEnvironment(context))

    init {
        scope.launch {
            val restored = recorder.restore()
            restored?.let { workout ->
                lastMeasurementAt = recorder.lastSampleTime()
                lastSampleMillis = lastMeasurementAt?.toEpochMilli() ?: 0L
                mutableState.update {
                    it.copy(
                        recordingId = workout.id,
                        startedAt = Instant.ofEpochMilli(workout.startedAtMillis),
                        distanceMeters = workout.distanceMeters,
                        target = workout.target(),
                    )
                }
            }
            if (restored != null) scheduleDisconnectFinalization(clock.instant())

            client.state.collect { ftms ->
                val isRecording = recorder.activeId() != null
                val previousConnection =
                    mutableState.getAndUpdate {
                        it.copy(
                            connection = displayConnection(ftms.connection, isRecording),
                            bike = ftms.selected,
                            devices = ftms.devices,
                            latest = ftms.latest,
                            rawPacket = ftms.rawPacket,
                            diagnostics = ftms.diagnostics,
                            error = ftms.error,
                        )
                    }.connection

                if (ftms.connection == ConnectionState.READY) {
                    disconnectJob?.cancel()
                    disconnectJob = null
                    retryJob?.cancel()
                    retryJob = null
                    mutableState.update { it.copy(reconnectDeadline = null) }
                }

                val sample = ftms.latest
                if (sample != null && sample.timestamp.toEpochMilli() != lastSampleMillis) handleSample(sample)

                if (ftms.connection in setOf(ConnectionState.DISCONNECTED, ConnectionState.ERROR)) {
                    if (previousConnection in setOf(ConnectionState.READY, ConnectionState.RECORDING)) {
                        autoStartSuppressed = false
                    }
                    if (recorder.activeId() != null) scheduleDisconnectFinalization(clock.instant())
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
        environment.setLastBikeAddress(address)
        client.connect(address, mutableState.value.monitoringEnabled)
    }

    fun reconnectLastBike() {
        environment.lastBikeAddress()?.let {
            client.connect(it, mutableState.value.monitoringEnabled)
        } ?: client.startScan()
    }

    fun resumeMonitoring() {
        if (!environment.isMonitoringEnabled()) return
        mutableState.update { it.copy(monitoringEnabled = true) }
        environment.lastBikeAddress()?.let { client.connect(it, autoConnect = true) }
    }

    fun setMonitoringEnabled(enabled: Boolean) {
        environment.setMonitoringEnabled(enabled)
        mutableState.update { it.copy(monitoringEnabled = enabled) }
        if (enabled) {
            environment.startRecordingService()
        } else {
            scope.launch {
                workoutMutex.withLock { finishActiveWorkout(lastMeasurementAt ?: clock.instant()) }
                disconnectJob?.cancel()
                retryJob?.cancel()
                client.disconnect()
                environment.stopRecordingService()
            }
        }
    }

    fun disconnect() = client.disconnect()

    fun startWorkout() {
        scope.launch {
            workoutMutex.withLock {
                if (recorder.activeId() == null) startWorkoutLocked()
            }
            environment.startRecordingService()
        }
    }

    fun stopWorkout() {
        scope.launch {
            workoutMutex.withLock {
                autoStartSuppressed = mutableState.value.monitoringEnabled
                finishActiveWorkout(lastMeasurementAt ?: clock.instant())
            }
            if (!mutableState.value.monitoringEnabled) environment.stopRecordingService()
        }
    }

    fun setNextWorkoutTarget(target: WorkoutTarget?) {
        if (recorder.activeId() != null) return
        environment.setPendingTarget(target)
        mutableState.update { it.copy(target = target) }
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
                mutableState.update {
                    it.copy(
                        connection = ConnectionState.RECORDING,
                        distanceMeters = distance,
                    )
                }
            }
        }
    }

    private suspend fun startWorkoutLocked(at: Instant = clock.instant()) {
        val target = environment.pendingTarget()
        val workout = recorder.start(at, target)
        environment.setPendingTarget(null)
        mutableState.update {
            it.copy(
                connection = ConnectionState.RECORDING,
                recordingId = workout.id,
                startedAt = Instant.ofEpochMilli(workout.startedAtMillis),
                distanceMeters = 0.0,
                error = null,
                target = target,
            )
        }
    }

    private fun scheduleDisconnectFinalization(disconnectedAt: Instant) {
        if (disconnectJob?.isActive == true) return
        val lastData = lastMeasurementAt ?: disconnectedAt
        val deadline = lastData.plusMillis(disconnectGraceMillis)
        mutableState.update { it.copy(reconnectDeadline = deadline) }
        disconnectJob =
            scope.launch {
                val remaining = deadline.toEpochMilli() - clock.instant().toEpochMilli()
                if (remaining > 0) delay(remaining)
                workoutMutex.withLock { finishActiveWorkout(lastData) }
                mutableState.update { it.copy(reconnectDeadline = null) }
            }
    }

    private fun scheduleConnectRetry() {
        if (retryJob?.isActive == true) return
        val address = environment.lastBikeAddress() ?: return
        retryJob =
            scope.launch {
                delay(retryMillis)
                if (mutableState.value.monitoringEnabled && client.state.value.connection != ConnectionState.READY) {
                    client.connect(address, autoConnect = true)
                }
            }
    }

    private suspend fun finishActiveWorkout(at: Instant) {
        if (recorder.activeId() == null) return
        mutableState.update { it.copy(connection = ConnectionState.FINALIZING) }
        val completed = recorder.stop(at)
        mutableState.update {
            it.copy(
                connection = displayConnection(client.state.value.connection, false),
                recordingId = null,
                startedAt = null,
                target = null,
            )
        }
        if (completed != null) environment.enqueueHealthSync()
    }

    private fun enqueueHealthSync() = environment.enqueueHealthSync()

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
        private const val DISCONNECT_GRACE_MILLIS = 5 * 60 * 1000L
        private const val RETRY_MILLIS = 10_000L

        fun isMonitoringEnabled(context: Context): Boolean = AndroidBridgeEnvironment(context).isMonitoringEnabled()
    }
}
