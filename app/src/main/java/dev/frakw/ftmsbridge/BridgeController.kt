package dev.frakw.ftmsbridge

import android.content.Context
import dev.frakw.ftmsbridge.data.target
import dev.frakw.ftmsbridge.ftms.FtmsClient
import dev.frakw.ftmsbridge.model.BridgeState
import dev.frakw.ftmsbridge.model.ConnectionState
import dev.frakw.ftmsbridge.model.IndoorBikeSample
import dev.frakw.ftmsbridge.model.WorkoutTarget
import dev.frakw.ftmsbridge.recording.WorkoutRecorder
import dev.frakw.ftmsbridge.recording.WorkoutStopResult
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
    private val inactivityMillis: Long = INACTIVITY_MILLIS,
    private val retryMillis: Long = RETRY_MILLIS,
    private val automaticStartCooldownMillis: Long = AUTOMATIC_START_COOLDOWN_MILLIS,
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
    private var lastMovementAt: Instant? = null
    private var lastMovementMetrics: MovementMetrics? = null
    private var inactivityJob: Job? = null
    private var retryJob: Job? = null
    private var automaticStartBlockedUntil: Instant? = null
    private var automaticStartNeedsBaseline = false

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
                    retryJob?.cancel()
                    retryJob = null
                }

                val sample = ftms.latest
                if (sample != null && sample.timestamp.toEpochMilli() != lastSampleMillis) handleSample(sample)

                if (ftms.connection in setOf(ConnectionState.DISCONNECTED, ConnectionState.ERROR)) {
                    if (previousConnection in setOf(ConnectionState.READY, ConnectionState.RECORDING)) {
                        if (automaticStartBlockedUntil != null) automaticStartNeedsBaseline = true
                        lastMovementMetrics = null
                        lastMovementAt = null
                    }
                    inactivityJob?.cancel()
                    inactivityJob = null
                    if (recorder.activeId() != null) {
                        workoutMutex.withLock { finishActiveWorkout(lastMeasurementAt ?: clock.instant()) }
                    }
                    if (mutableState.value.monitoringEnabled) startReconnectLoop()
                }
            }
        }
    }

    fun scan() = client.startScan()

    fun stopScan() = client.stopScan()

    fun connect(address: String) {
        environment.setLastBikeAddress(address)
        client.connect(address)
    }

    fun reconnectLastBike() {
        environment.lastBikeAddress()?.let {
            client.connect(it)
        } ?: client.startScan()
    }

    fun resumeMonitoring() {
        if (!environment.isMonitoringEnabled()) return
        mutableState.update { it.copy(monitoringEnabled = true) }
        startReconnectLoop(immediate = true)
    }

    fun setMonitoringEnabled(enabled: Boolean) {
        environment.setMonitoringEnabled(enabled)
        mutableState.update { it.copy(monitoringEnabled = enabled) }
        if (enabled) {
            environment.startRecordingService()
        } else {
            scope.launch {
                workoutMutex.withLock { finishActiveWorkout(lastMeasurementAt ?: clock.instant()) }
                inactivityJob?.cancel()
                retryJob?.cancel()
                inactivityJob = null
                retryJob = null
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
            val movementMetrics = MovementMetrics.from(sample)
            val automaticStartBlocked = automaticStartBlockedUntil?.let { clock.instant().isBefore(it) } == true
            if (
                recorder.activeId() == null &&
                mutableState.value.monitoringEnabled &&
                (automaticStartBlocked || automaticStartNeedsBaseline)
            ) {
                lastMovementMetrics = movementMetrics
                lastMovementAt = null
                automaticStartNeedsBaseline = false
                return
            }
            val previousMovementMetrics = lastMovementMetrics
            val movementChanged = movementMetrics != previousMovementMetrics
            if (movementChanged) {
                lastMovementMetrics = movementMetrics
                lastMovementAt = sample.timestamp
                inactivityJob?.cancel()
                inactivityJob = null
            }
            if (
                movementChanged &&
                mutableState.value.monitoringEnabled &&
                recorder.activeId() == null &&
                movementMetrics.isFreshActivityAfter(previousMovementMetrics)
            ) {
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
                if (movementChanged) scheduleInactivityFinalization()
            }
        }
    }

    private suspend fun startWorkoutLocked(at: Instant = clock.instant()) {
        automaticStartBlockedUntil = null
        automaticStartNeedsBaseline = false
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

    private fun scheduleInactivityFinalization() {
        val lastMovement = lastMovementAt ?: return
        inactivityJob?.cancel()
        inactivityJob =
            scope.launch {
                delay(inactivityMillis)
                inactivityJob = null
                workoutMutex.withLock {
                    finishActiveWorkout(lastMovement)
                }
            }
    }

    private fun startReconnectLoop(immediate: Boolean = false) {
        if (retryJob?.isActive == true) {
            if (!immediate) return
            retryJob?.cancel()
        }
        val address = environment.lastBikeAddress() ?: return
        retryJob =
            scope.launch {
                if (!immediate) delay(retryMillis)
                while (mutableState.value.monitoringEnabled && client.state.value.connection != ConnectionState.READY) {
                    client.connect(address)
                    delay(retryMillis)
                }
            }
    }

    private suspend fun finishActiveWorkout(at: Instant) {
        if (recorder.activeId() == null) return
        inactivityJob?.cancel()
        inactivityJob = null
        mutableState.update { it.copy(connection = ConnectionState.FINALIZING) }
        val result = recorder.stop(at)
        automaticStartBlockedUntil = clock.instant().plusMillis(automaticStartCooldownMillis)
        automaticStartNeedsBaseline = client.state.value.connection != ConnectionState.READY
        lastMovementAt = null
        lastMeasurementAt = null
        mutableState.update {
            it.copy(
                connection = displayConnection(client.state.value.connection, false),
                recordingId = null,
                startedAt = null,
                target = (result as? WorkoutStopResult.Discarded)?.workout?.target(),
            )
        }
        when (result) {
            is WorkoutStopResult.Completed -> environment.enqueueHealthSync()
            is WorkoutStopResult.Discarded -> environment.setPendingTarget(result.workout.target())
            null -> Unit
        }
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
        private const val INACTIVITY_MILLIS = 30_000L
        private const val RETRY_MILLIS = 10_000L
        private const val AUTOMATIC_START_COOLDOWN_MILLIS = 5_000L

        fun isMonitoringEnabled(context: Context): Boolean = AndroidBridgeEnvironment(context).isMonitoringEnabled()
    }

    private data class MovementMetrics(
        val speedKph: Double?,
        val cadenceRpm: Double?,
        val powerWatts: Int?,
        val totalDistanceMeters: Long?,
    ) {
        fun isFreshActivityAfter(previous: MovementMetrics?): Boolean {
            cadenceRpm?.let { cadence ->
                return cadence > 0.0 && cadence != previous?.cadenceRpm
            }
            return speedKph?.let { it > 0.0 && it != previous?.speedKph } == true ||
                powerWatts?.let { it > 0 && it != previous?.powerWatts } == true ||
                totalDistanceMeters?.let { distance ->
                    previous?.totalDistanceMeters?.let { distance > it } == true
                } == true
        }

        companion object {
            fun from(sample: IndoorBikeSample) = MovementMetrics(
                sample.speedKph,
                sample.cadenceRpm,
                sample.powerWatts,
                sample.totalDistanceMeters,
            )
        }
    }
}
