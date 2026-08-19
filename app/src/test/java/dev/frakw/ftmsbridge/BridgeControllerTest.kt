package dev.frakw.ftmsbridge

import dev.frakw.ftmsbridge.data.SampleEntity
import dev.frakw.ftmsbridge.data.WorkoutDao
import dev.frakw.ftmsbridge.data.WorkoutEntity
import dev.frakw.ftmsbridge.data.WorkoutWithSamples
import dev.frakw.ftmsbridge.ftms.FtmsClient
import dev.frakw.ftmsbridge.ftms.FtmsClientState
import dev.frakw.ftmsbridge.model.ConnectionState
import dev.frakw.ftmsbridge.model.IndoorBikeSample
import dev.frakw.ftmsbridge.recording.WorkoutRecorder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    fun `disconnect finalizes after grace period`() = runTest {
        val fixture = fixture(monitoringEnabled = true)
        runCurrent()
        fixture.client.emit(ready(sample(BASE_TIME)))
        runCurrent()

        fixture.client.emit(FtmsClientState(connection = ConnectionState.DISCONNECTED))
        runCurrent()
        assertEquals(BASE_TIME.plusSeconds(1), fixture.controller.state.value.reconnectDeadline)

        advanceTimeBy(999)
        runCurrent()
        assertNotNull(fixture.controller.state.value.recordingId)

        advanceTimeBy(1)
        runCurrent()
        assertNull(fixture.controller.state.value.recordingId)
        assertNull(fixture.controller.state.value.reconnectDeadline)
        assertEquals(1, fixture.environment.healthSyncRequests)
    }

    @Test
    fun `reconnect cancels pending finalization`() = runTest {
        val fixture = fixture(monitoringEnabled = true)
        runCurrent()
        fixture.client.emit(ready(sample(BASE_TIME)))
        runCurrent()
        fixture.client.emit(FtmsClientState(connection = ConnectionState.DISCONNECTED))
        runCurrent()

        advanceTimeBy(500)
        fixture.client.emit(ready(sample(BASE_TIME.plusMillis(500))))
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertNotNull(fixture.controller.state.value.recordingId)
        assertNull(fixture.controller.state.value.reconnectDeadline)
        assertEquals(0, fixture.environment.healthSyncRequests)
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
        assertEquals(listOf("bike-address" to true), fixture.client.connections)
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
    fun `active workout is restored on initialization`() = runTest {
        val dao = FakeDao()
        dao.upsertWorkout(WorkoutEntity("restored", BASE_TIME.toEpochMilli(), distanceMeters = 42.0))
        dao.upsertSample(SampleEntity("restored", BASE_TIME.toEpochMilli(), 20.0, null, null, null))
        val fixture = fixture(dao = dao)
        runCurrent()

        assertEquals("restored", fixture.controller.state.value.recordingId)
        assertEquals(BASE_TIME, fixture.controller.state.value.startedAt)
        assertEquals(42.0, fixture.controller.state.value.distanceMeters, 0.0)
        assertNotNull(fixture.controller.state.value.reconnectDeadline)
    }

    private fun kotlinx.coroutines.test.TestScope.fixture(
        monitoringEnabled: Boolean = false,
        lastBikeAddress: String? = null,
        dao: FakeDao = FakeDao(),
    ): Fixture {
        val client = FakeFtmsClient()
        val environment = FakeEnvironment(monitoringEnabled, lastBikeAddress)
        val controller =
            BridgeController(
                client = client,
                recorder = WorkoutRecorder(dao),
                environment = environment,
                scope = backgroundScope,
                clock = SchedulerClock(testScheduler, BASE_TIME),
                disconnectGraceMillis = 1_000,
                retryMillis = 1_000,
            )
        return Fixture(controller, client, environment, dao)
    }

    private fun ready(sample: IndoorBikeSample) = FtmsClientState(
        connection = ConnectionState.READY,
        latest = sample,
    )

    private fun sample(at: Instant) = IndoorBikeSample(at, 25.0, 80.0, 150, null, null)

    private data class Fixture(
        val controller: BridgeController,
        val client: FakeFtmsClient,
        val environment: FakeEnvironment,
        val dao: FakeDao,
    )

    private class FakeFtmsClient : FtmsClient {
        private val mutableState = MutableStateFlow(FtmsClientState())
        override val state: StateFlow<FtmsClientState> = mutableState
        val connections = mutableListOf<Pair<String, Boolean>>()

        fun emit(value: FtmsClientState) {
            mutableState.value = value
        }

        override fun startScan() = Unit

        override fun stopScan() = Unit

        override fun connect(
            address: String,
            autoConnect: Boolean,
        ) {
            connections += address to autoConnect
        }

        override fun disconnect() {
            mutableState.value = FtmsClientState(connection = ConnectionState.DISCONNECTED)
        }
    }

    private class FakeEnvironment(
        private var monitoringEnabled: Boolean,
        private var address: String?,
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
