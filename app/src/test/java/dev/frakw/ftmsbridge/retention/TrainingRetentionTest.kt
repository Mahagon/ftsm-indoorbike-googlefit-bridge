package dev.frakw.ftmsbridge.retention

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.frakw.ftmsbridge.data.BridgeDatabase
import dev.frakw.ftmsbridge.data.SampleEntity
import dev.frakw.ftmsbridge.data.WorkoutEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrainingRetentionTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearPreferences() {
        context.getSharedPreferences("training_retention", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `preferences default to about twenty MiB of training`() {
        val preferences = TrainingRetentionPreferences(context)
        assertEquals(36, preferences.hours())
        preferences.setHours(72)
        assertEquals(72, preferences.hours())
    }

    @Test
    fun `oldest synced workouts are removed by cumulative duration`() {
        val workouts = (1..4).map { workout("ride-$it", it.toLong(), hours = 10, synced = true) }

        val result = selectWorkoutsForDeletion(workouts, 20 * 3_600L)

        assertEquals(listOf("ride-1", "ride-2"), result.workoutIds)
        assertEquals(20 * 3_600L, result.retainedSeconds)
        assertFalse(result.blocked)
    }

    @Test
    fun `manager deletes old Room rows and compacts the file database`() = runTest {
        context.deleteDatabase(TrainingRetentionManager.DATABASE_NAME)
        val database = Room.databaseBuilder(
            context,
            BridgeDatabase::class.java,
            TrainingRetentionManager.DATABASE_NAME,
        ).build()
        try {
            val dao = database.workouts()
            (1..3).forEach { index ->
                val workout = workout("ride-$index", index.toLong(), hours = 20, synced = true)
                dao.upsertWorkout(workout)
                dao.upsertSample(SampleEntity(workout.id, workout.startedAtMillis + 1_000, 25.0, 80.0, 150, 10))
            }

            val result = TrainingRetentionManager(context, database).enforce()

            assertEquals(TrainingRetentionManager.EnforcementResult.COMPLETE, result)
            assertEquals(listOf("ride-3"), dao.completedWorkoutsForRetention().map { it.id })
            assertNull(dao.workout("ride-1"))
        } finally {
            database.close()
            context.deleteDatabase(TrainingRetentionManager.DATABASE_NAME)
        }
    }

    @Test
    fun `unsynced and newest workouts remain protected`() {
        val workouts = listOf(
            workout("unsynced", 1, hours = 10, synced = false),
            workout("synced", 2, hours = 10, synced = true),
            workout("newest", 3, hours = 10, synced = true),
        )

        val result = selectWorkoutsForDeletion(workouts, 10 * 3_600L)

        assertEquals(listOf("synced"), result.workoutIds)
        assertEquals(20 * 3_600L, result.retainedSeconds)
        assertTrue(result.blocked)
    }

    @Test
    fun `warning reflects actual storage and protected history`() {
        assertNull(RetentionStatus(databaseBytes = RetentionStatus.BACKUP_TARGET_BYTES, loading = false).warning)
        assertEquals(
            RetentionWarning.BACKUP_BLOCKED,
            RetentionStatus(
                databaseBytes = RetentionStatus.BACKUP_TARGET_BYTES + 1,
                cleanupBlocked = true,
                loading = false,
            ).warning,
        )
    }

    @Test
    fun `retention input accepts only supported whole hours`() {
        assertEquals(36, parseRetentionHours(" 36 "))
        assertNull(parseRetentionHours("0"))
        assertNull(parseRetentionHours("1.5"))
        assertNull(parseRetentionHours("10001"))
    }

    private fun workout(
        id: String,
        order: Long,
        hours: Int,
        synced: Boolean,
    ): WorkoutEntity {
        val start = order * 100_000_000L
        return WorkoutEntity(
            id = id,
            startedAtMillis = start,
            endedAtMillis = start + hours * 3_600_000L,
            state = WorkoutEntity.STATE_COMPLETE,
            synced = synced,
        )
    }
}
