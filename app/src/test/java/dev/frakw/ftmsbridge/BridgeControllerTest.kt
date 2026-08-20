package dev.frakw.ftmsbridge

import dev.frakw.ftmsbridge.data.SampleEntity
import dev.frakw.ftmsbridge.data.WorkoutDao
import dev.frakw.ftmsbridge.data.WorkoutEntity
import dev.frakw.ftmsbridge.data.WorkoutWithSamples
import dev.frakw.ftmsbridge.ftms.FtmsClient
import dev.frakw.ftmsbridge.ftms.FtmsClientState
import dev.frakw.ftmsbridge.model.ConnectionState
import dev.frakw.ftmsbridge.model.IndoorBikeSample
import dev.frakw.ftmsbridge.model.WorkoutTarget
import dev.frakw.ftmsbridge.recording.WorkoutRecorder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class BridgeControllerTest {
    @Test
    fun `manual workout consumes duration target once`() = runTest {
        val target = WorkoutTarget.Duration(1_800)
        val fixture = fixture(pendingTarget = target)
        runCurrent()
        assertEquals(target, fixture.controller.state.value.target)

        fixture.controller.startWorkout()
        runCurrent()
        val first = fixture.dao.workouts.values.single()
        assertEquals(1_800L, first.targetDurationSeconds)
        assertNull(fixture.environment.pendingTarget())

        fixture.controller.stopWorkout()
        runCurrent()
        assertNull(fixture.controller.state.value.target)
        fixture.controller.startWorkout()
        runCurrent()
        assertNull(fixture.dao.workouts.values.last().targetDurationSeconds)
    }

    @Test
    fun `background workout consumes distance target`() = runTest {
        val target = WorkoutTarget.Distance(12_500.0)
        val fixture = fixture(monitoringEnabled = true, pendingTarget = target)
        runCurrent()

        fixture.client.emit(ready(sample(BASE_TIME)))
        runCurrent()

        assertEquals(target, fixture.controller.state.value.target)
        assertEquals(12_500.0, fixture.dao.workouts.values.single().targetDistanceMeters ?: 0.0, 0.0)
        assertNull(fixture.environment.pendingTarget())
    }

    @Test
    fun `target can change before recording but not during recording`() = runTest {
        val fixture = fixture()
        runCurrent()
        fixture.controller.setNextWorkoutTarget(WorkoutTarget.Duration(600))
        assertEquals(WorkoutTarget.Duration(600), fixture.controller.state.value.target)

        fixture.controller.startWorkout()
        runCurrent()
        fixture.controller.setNextWorkoutTarget(WorkoutTarget.Distance(5_000.0))

        assertEquals(WorkoutTarget.Duration(600), fixture.controller.state.value.target)
        assertNull(fixture.environment.pendingTarget())
    }

    @Test
    fun `monitoring automatically records data and suppresses restart after manual stop`() = runTest {
        val fixture = fixture(monitoringEnabled = true)
        runCurrent()

        fixture.client.emit(ready(sample(BASE_TIME)))
        runCurrent()
        assertNotNull(fixture.controller.state.value.recordingId)
        assertEquals(1, fixture.dao.workouts.size)

        fixture.client.emit(ready(sample(BASE_TIME)))
        runCurrent()
        assertEquals(1, fixture.dao.workouts.size)

        fixture.controller.stopWorkout()
        runCurrent()
        assertNull(fixture.controller.state.value.recordingId)
        assertEquals(1, fixture.environment.healthSyncRequests)

        fixture.client.emit(ready(sample(BASE_TIME.plusSeconds(1))))
        runCurrent()
        assertNull(fixture.controller.state.value.recordingId)

        fixture.client.emit(FtmsClientState(connection = ConnectionState.DISCONNECTED))
        runCurrent()
        fixture.client.emit(ready(sample(BASE_TIME.plusSeconds(2))))
        runCurrent()
        assertNotNull(fixture.controller.state.value.recordingId)
        assertEquals(2, fixture.dao.workouts.size)
    }

    @Test
    fun `disconnect finalizes and syncs immediately`() = runTest {
        val fixture = fixture(monitoringEnabled = true)
        runCurrent()
        fixture.client.emit(ready(sample(BASE_TIME)))
        runCurrent()

        fixture.client.emit(FtmsClientState(connection = ConnectionState.DISCONNECTED))
        runCurrent()
        assertNull(fixture.controller.state.value.recordingId)
        assertEquals(1, fixture.environment.healthSyncRequests)
    }

    @Test
    fun `normal disconnect retries saved bike after delay`() = runTest {
        val fixture = fixture(monitoringEnabled = true, lastBikeAddress = "bike-address")
        runCurrent()
        fixture.client.emit(ready(sample(BASE_TIME)))
        runCurrent()
        fixture.client.emit(FtmsClientState(connection = ConnectionState.DISCONNECTED))
        runCurrent()

        advanceTimeBy(999)
        runCurrent()
        assertTrue(fixture.client.connections.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("bike-address"), fixture.client.connections)
    }

    @Test
    fun `connection error retries saved bike once after delay`() = runTest {
        val fixture = fixture(monitoringEnabled = true, lastBikeAddress = "bike-address")
        runCurrent()

        fixture.client.emit(FtmsClientState(connection = ConnectionState.ERROR, error = "first"))
        fixture.client.emit(FtmsClientState(connection = ConnectionState.ERROR, error = "second"))
        runCurrent()
        advanceTimeBy(999)
        runCurrent()
        assertTrue(fixture.client.connections.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("bike-address"), fixture.client.connections)
    }

    @Test
    fun `ready connection cancels pending retry`() = runTest {
        val fixture = fixture(monitoringEnabled = true, lastBikeAddress = "bike-address")
        runCurrent()
        fixture.client.emit(FtmsClientState(connection = ConnectionState.ERROR, error = "failed"))
        runCurrent()

        advanceTimeBy(500)
        fixture.client.emit(FtmsClientState(connection = ConnectionState.READY))
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertTrue(fixture.client.connections.isEmpty())
    }

    @Test
    fun `manual workout controls service and schedules sync`() = runTest {
        val fixture = fixture()
        runCurrent()

        fixture.controller.startWorkout()
        runCurrent()
        assertNotNull(fixture.controller.state.value.recordingId)
        assertEquals(1, fixture.environment.serviceStarts)

        fixture.controller.stopWorkout()
        runCurrent()
        assertNull(fixture.controller.state.value.recordingId)
        assertEquals(1, fixture.environment.serviceStops)
        assertEquals(1, fixture.environment.healthSyncRequests)
    }

    @Test
    fun `unchanged movement finalizes after inactivity timeout`() = runTest {
        val fixture = fixture(monitoringEnabled = true)
        runCurrent()
        fixture.client.emit(ready(sample(BASE_TIME)))
        runCurrent()

        advanceTimeBy(999)
        runCurrent()
        assertNotNull(fixture.controller.state.value.recordingId)

        advanceTimeBy(1)
        runCurrent()
        assertNull(fixture.controller.state.value.recordingId)
        assertEquals(1, fixture.environment.healthSyncRequests)
        assertEquals(BASE_TIME.plusMillis(1).toEpochMilli(), fixture.dao.workouts.values.single().endedAtMillis)
    }

    @Test
    fun `movement changes reset inactivity timeout`() = runTest {
        val fixture = fixture(monitoringEnabled = true)
        runCurrent()
        fixture.client.emit(ready(sample(BASE_TIME)))
        runCurrent()

        advanceTimeBy(500)
        fixture.client.emit(ready(sample(BASE_TIME.plusMillis(500), powerWatts = 151)))
        runCurrent()
        advanceTimeBy(999)
        runCurrent()
        assertNotNull(fixture.controller.state.value.recordingId)

        advanceTimeBy(1)
        runCurrent()
        assertNull(fixture.controller.state.value.recordingId)
        assertEquals(BASE_TIME.plusMillis(500).toEpochMilli(), fixture.dao.workouts.values.single().endedAtMillis)
    }

    @Test
    fun `elapsed time alone does not reset inactivity timeout`() = runTest {
        val fixture = fixture(monitoringEnabled = true)
        runCurrent()
        fixture.client.emit(ready(sample(BASE_TIME, elapsedTimeSeconds = 1)))
        runCurrent()

        advanceTimeBy(500)
        fixture.client.emit(ready(sample(BASE_TIME.plusMillis(500), elapsedTimeSeconds = 2)))
        runCurrent()
        advanceTimeBy(500)
        runCurrent()

        assertNull(fixture.controller.state.value.recordingId)
        assertEquals(1, fixture.environment.healthSyncRequests)
    }

    @Test
    fun `stationary packets stay suppressed until movement changes`() = runTest {
        val fixture = fixture(monitoringEnabled = true)
        runCurrent()
        fixture.client.emit(ready(sample(BASE_TIME)))
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        fixture.client.emit(ready(sample(BASE_TIME.plusSeconds(1))))
        runCurrent()
        assertNull(fixture.controller.state.value.recordingId)
        assertEquals(1, fixture.dao.workouts.size)

        fixture.client.emit(ready(sample(BASE_TIME.plusSeconds(2), powerWatts = 151)))
        runCurrent()
        assertNotNull(fixture.controller.state.value.recordingId)
        assertEquals(2, fixture.dao.workouts.size)
    }

    @Test
    fun `disconnected restored workout is finalized on initialization`() = runTest {
        val dao = FakeDao()
        dao.upsertWorkout(
            WorkoutEntity(
                "restored",
                BASE_TIME.toEpochMilli(),
                distanceMeters = 42.0,
                targetDistanceMeters = 10_000.0,
            ),
        )
        dao.upsertSample(SampleEntity("restored", BASE_TIME.toEpochMilli(), 20.0, null, null, null))
        val fixture = fixture(dao = dao)
        runCurrent()

        assertNull(fixture.controller.state.value.recordingId)
        assertEquals(WorkoutEntity.STATE_COMPLETE, dao.workouts.getValue("restored").state)
        assertEquals(BASE_TIME.plusMillis(1).toEpochMilli(), dao.workouts.getValue("restored").endedAtMillis)
        assertEquals(1, fixture.environment.healthSyncRequests)
    }

    private fun kotlinx.coroutines.test.TestScope.fixture(
        monitoringEnabled: Boolean = false,
        lastBikeAddress: String? = null,
        pendingTarget: WorkoutTarget? = null,
        dao: FakeDao = FakeDao(),
    ): Fixture {
        val client = FakeFtmsClient()
        val environment = FakeEnvironment(monitoringEnabled, lastBikeAddress, pendingTarget)
        val controller =
            BridgeController(
                client = client,
                recorder = WorkoutRecorder(dao),
                environment = environment,
                scope = backgroundScope,
                clock = SchedulerClock(testScheduler, BASE_TIME),
                inactivityMillis = 1_000,
                retryMillis = 1_000,
            )
        return Fixture(controller, client, environment, dao)
    }

    private fun ready(sample: IndoorBikeSample) = FtmsClientState(
        connection = ConnectionState.READY,
        latest = sample,
    )

    private fun sample(
        at: Instant,
        powerWatts: Int = 150,
        elapsedTimeSeconds: Int? = null,
    ) = IndoorBikeSample(at, 25.0, 80.0, powerWatts, null, elapsedTimeSeconds)

    private data class Fixture(
        val controller: BridgeController,
        val client: FakeFtmsClient,
        val environment: FakeEnvironment,
        val dao: FakeDao,
    )

    private class FakeFtmsClient : FtmsClient {
        private val mutableState = MutableStateFlow(FtmsClientState())
        override val state: StateFlow<FtmsClientState> = mutableState
        val connections = mutableListOf<String>()

        fun emit(value: FtmsClientState) {
            mutableState.value = value
        }

        override fun startScan() = Unit

        override fun stopScan() = Unit

        override fun connect(address: String) {
            connections += address
        }

        override fun disconnect() {
            mutableState.value = FtmsClientState(connection = ConnectionState.DISCONNECTED)
        }
    }

    private class FakeEnvironment(
        private var monitoringEnabled: Boolean,
        private var address: String?,
        private var target: WorkoutTarget?,
    ) : BridgeEnvironment {
        var serviceStarts = 0
        var serviceStops = 0
        var healthSyncRequests = 0

        override fun isMonitoringEnabled() = monitoringEnabled

        override fun setMonitoringEnabled(enabled: Boolean) {
            monitoringEnabled = enabled
        }

        override fun lastBikeAddress() = address

        override fun setLastBikeAddress(address: String) {
            this.address = address
        }

        override fun pendingTarget() = target

        override fun setPendingTarget(target: WorkoutTarget?) {
            this.target = target
        }

        override fun startRecordingService() {
            serviceStarts++
        }

        override fun stopRecordingService() {
            serviceStops++
        }

        override fun enqueueHealthSync() {
            healthSyncRequests++
        }
    }

    private class SchedulerClock(
        private val scheduler: TestCoroutineScheduler,
        private val base: Instant,
        private val zone: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = SchedulerClock(scheduler, base, zone)

        override fun instant(): Instant = base.plusMillis(scheduler.currentTime)
    }

    private class FakeDao : WorkoutDao {
        val workouts = linkedMapOf<String, WorkoutEntity>()
        private val samples = linkedMapOf<Pair<String, Long>, SampleEntity>()

        override suspend fun upsertWorkout(workout: WorkoutEntity) {
            workouts[workout.id] = workout
        }

        override suspend fun upsertSample(sample: SampleEntity) {
            samples[sample.workoutId to sample.timestampMillis] = sample
        }

        override suspend fun activeWorkout() = workouts.values.lastOrNull { it.state == WorkoutEntity.STATE_ACTIVE }

        override suspend fun latestSampleTimestamp(workoutId: String) = samples.values.filter { it.workoutId == workoutId }.maxOfOrNull { it.timestampMillis }

        override suspend fun workout(id: String) = workouts[id]?.let { workout ->
            WorkoutWithSamples(workout, samples.values.filter { it.workoutId == id })
        }

        override fun completedWorkouts(limit: Int) = flowOf(
            workouts.values.filter { it.state == WorkoutEntity.STATE_COMPLETE }.sortedByDescending { it.startedAtMillis }.take(limit),
        )

        override fun observeCompletedWorkout(id: String) = flowOf(
            workouts[id]
                ?.takeIf { it.state == WorkoutEntity.STATE_COMPLETE }
                ?.let { WorkoutWithSamples(it, samples.values.filter { sample -> sample.workoutId == id }) },
        )

        override suspend fun pendingSync() = workouts.values.filter { it.state == WorkoutEntity.STATE_COMPLETE && !it.synced }

        override suspend fun markSynced(id: String) {
            workouts[id]?.let { workouts[id] = it.copy(synced = true) }
        }

        override suspend fun markSyncFailed(
            id: String,
            message: String,
        ) {
            workouts[id]?.let { workouts[id] = it.copy(syncError = message) }
        }
    }

    private companion object {
        val BASE_TIME: Instant = Instant.parse("2026-08-19T10:00:00Z")
    }
}
