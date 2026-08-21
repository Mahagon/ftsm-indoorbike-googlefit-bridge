package dev.frakw.ftmsbridge.data

import android.content.Context
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.frakw.ftmsbridge.health.HealthConnectRecordMapper
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkoutDaoTest {
    private lateinit var database: BridgeDatabase
    private lateinit var dao: WorkoutDao

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            BridgeDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.workouts()
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun `updating and completing workout preserves samples`() = runTest {
        val workout = WorkoutEntity("ride", 1_000)
        dao.upsertWorkout(workout)
        dao.upsertSample(SampleEntity("ride", 2_000, 20.0, 80.0, 150, 10))
        dao.upsertWorkout(workout.copy(distanceMeters = 10.0))
        dao.upsertSample(SampleEntity("ride", 3_000, 25.0, 85.0, 175, 20))
        dao.upsertWorkout(
            workout.copy(
                endedAtMillis = 4_000,
                distanceMeters = 20.0,
                state = WorkoutEntity.STATE_COMPLETE,
            ),
        )

        val stored = requireNotNull(dao.workout("ride"))
        assertEquals(WorkoutEntity.STATE_COMPLETE, stored.workout.state)
        assertEquals(listOf(2_000L, 3_000L), stored.samples.sortedBy { it.timestampMillis }.map { it.timestampMillis })
        val records = HealthConnectRecordMapper().map(stored)
        assertEquals(2, records.filterIsInstance<SpeedRecord>().single().samples.size)
        assertEquals(2, records.filterIsInstance<CyclingPedalingCadenceRecord>().single().samples.size)
        assertEquals(2, records.filterIsInstance<PowerRecord>().single().samples.size)
    }

    @Test
    fun `deleting a completed workout cascades to its samples`() = runTest {
        dao.upsertWorkout(
            WorkoutEntity(
                id = "old-ride",
                startedAtMillis = 1_000,
                endedAtMillis = 2_000,
                state = WorkoutEntity.STATE_COMPLETE,
                synced = true,
            ),
        )
        dao.upsertSample(SampleEntity("old-ride", 1_500, 20.0, 80.0, 150, 10))

        assertEquals(1, dao.deleteCompletedWorkout("old-ride"))
        assertEquals(null, dao.workout("old-ride"))
    }

    @Test
    fun `deleting an active workout cascades to its samples`() = runTest {
        dao.upsertWorkout(WorkoutEntity(id = "short-ride", startedAtMillis = 1_000))
        dao.upsertSample(SampleEntity("short-ride", 1_500, 20.0, 80.0, 150, 10))

        assertEquals(1, dao.deleteActiveWorkout("short-ride"))
        assertEquals(null, dao.workout("short-ride"))
    }

    @Test
    fun `migrations requeue completed workouts and preserve active workouts`() = runTest {
        (1..4).forEach { version -> verifyMigrationFrom(version) }
    }

    private suspend fun verifyMigrationFrom(version: Int) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "workout-migration-$version-${System.nanoTime()}.db"
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { sqlite ->
            sqlite.execSQL(
                """
                CREATE TABLE workouts (
                    id TEXT NOT NULL PRIMARY KEY,
                    startedAtMillis INTEGER NOT NULL,
                    endedAtMillis INTEGER,
                    distanceMeters REAL NOT NULL,
                    state TEXT NOT NULL,
                    synced INTEGER NOT NULL,
                    syncError TEXT
                )
                """.trimIndent(),
            )
            sqlite.execSQL(
                """
                CREATE TABLE samples (
                    workoutId TEXT NOT NULL,
                    timestampMillis INTEGER NOT NULL,
                    speedKph REAL,
                    cadenceRpm REAL,
                    powerWatts INTEGER,
                    bikeDistanceMeters INTEGER,
                    PRIMARY KEY (workoutId, timestampMillis),
                    FOREIGN KEY (workoutId) REFERENCES workouts(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            sqlite.execSQL("CREATE INDEX index_samples_workoutId ON samples(workoutId)")
            if (version >= 2) {
                sqlite.execSQL("ALTER TABLE workouts ADD COLUMN targetDurationSeconds INTEGER")
                sqlite.execSQL("ALTER TABLE workouts ADD COLUMN targetDistanceMeters REAL")
            }
            if (version >= 3) {
                sqlite.execSQL("ALTER TABLE workouts ADD COLUMN caloriesKcal REAL")
                sqlite.execSQL("ALTER TABLE samples ADD COLUMN sessionDistanceMeters REAL")
                sqlite.execSQL("ALTER TABLE samples ADD COLUMN bikeEnergyKcal INTEGER")
                sqlite.execSQL("ALTER TABLE samples ADD COLUMN sessionEnergyKcal REAL")
            }
            val extraColumns = if (version == 1) {
                ""
            } else if (version == 2) {
                ", NULL, NULL"
            } else {
                ", NULL, NULL, NULL"
            }
            sqlite.execSQL("INSERT INTO workouts VALUES ('old-ride', 1000, 4000, 25.0, 'COMPLETE', 1, NULL$extraColumns)")
            sqlite.execSQL("INSERT INTO workouts VALUES ('active-ride', 5000, NULL, 5.0, 'ACTIVE', 1, 'keep'$extraColumns)")
            val sampleExtraColumns = if (version >= 3) ", NULL, NULL, NULL" else ""
            sqlite.execSQL("INSERT INTO samples VALUES ('old-ride', 2000, 20.0, 80.0, 150, 10$sampleExtraColumns)")
            sqlite.version = version
        }

        val migrated = Room.databaseBuilder(context, BridgeDatabase::class.java, name)
            .addMigrations(
                BridgeDatabase.MIGRATION_1_2,
                BridgeDatabase.MIGRATION_2_3,
                BridgeDatabase.MIGRATION_3_4,
                BridgeDatabase.MIGRATION_4_5,
            )
            .allowMainThreadQueries()
            .build()
        try {
            val stored = requireNotNull(migrated.workouts().workout("old-ride"))
            assertEquals(1, stored.samples.size)
            assertEquals(null, stored.workout.targetDurationSeconds)
            assertEquals(null, stored.workout.targetDistanceMeters)
            assertEquals(false, stored.workout.synced)
            assertEquals(null, stored.workout.syncError)
            val active = requireNotNull(migrated.workouts().workout("active-ride")).workout
            assertEquals(true, active.synced)
            assertEquals("keep", active.syncError)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }
}
