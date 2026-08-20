package dev.frakw.ftmsbridge.retention

import android.content.Context
import androidx.core.content.edit
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.frakw.ftmsbridge.BridgeApplication
import dev.frakw.ftmsbridge.data.BridgeDatabase
import dev.frakw.ftmsbridge.data.WorkoutEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

data class RetentionStatus(
    val configuredHours: Int = TrainingRetentionPreferences.DEFAULT_HOURS,
    val retainedSeconds: Long = 0,
    val databaseBytes: Long = 0,
    val cleanupBlocked: Boolean = false,
    val loading: Boolean = true,
) {
    val retainedHours: Double get() = retainedSeconds / 3_600.0
    val exceedsBackupTarget: Boolean get() = databaseBytes > BACKUP_TARGET_BYTES
    val warning: RetentionWarning?
        get() = when {
            exceedsBackupTarget && cleanupBlocked -> RetentionWarning.BACKUP_BLOCKED
            exceedsBackupTarget -> RetentionWarning.BACKUP_TARGET
            cleanupBlocked -> RetentionWarning.PROTECTED_LIMIT
            else -> null
        }

    companion object {
        const val BACKUP_TARGET_BYTES = 20L * 1024L * 1024L
    }
}

enum class RetentionWarning { BACKUP_BLOCKED, BACKUP_TARGET, PROTECTED_LIMIT }

internal class TrainingRetentionPreferences(context: Context) {
    private val values = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun hours(): Int = values.getInt(KEY_HOURS, DEFAULT_HOURS).coerceIn(MIN_HOURS, MAX_HOURS)

    fun setHours(hours: Int) {
        require(hours in MIN_HOURS..MAX_HOURS)
        values.edit { putInt(KEY_HOURS, hours) }
    }

    companion object {
        const val DEFAULT_HOURS = 36
        const val MIN_HOURS = 1
        const val MAX_HOURS = 10_000
        private const val PREFERENCES = "training_retention"
        private const val KEY_HOURS = "hours"
    }
}

internal data class RetentionSelection(
    val workoutIds: List<String>,
    val retainedSeconds: Long,
    val blocked: Boolean,
)

internal fun selectWorkoutsForDeletion(
    workouts: List<WorkoutEntity>,
    limitSeconds: Long,
): RetentionSelection {
    val ordered = workouts.sortedBy { it.startedAtMillis }
    val durations = ordered.associate { workout ->
        workout.id to ((workout.endedAtMillis ?: workout.startedAtMillis) - workout.startedAtMillis)
            .coerceAtLeast(0) / 1_000
    }
    var retained = durations.values.sum()
    val newestId = ordered.lastOrNull()?.id
    val deleted = mutableListOf<String>()
    ordered.forEach { workout ->
        if (retained <= limitSeconds) return@forEach
        if (workout.synced && workout.id != newestId) {
            deleted += workout.id
            retained -= durations.getValue(workout.id)
        }
    }
    return RetentionSelection(deleted, retained, retained > limitSeconds)
}

class TrainingRetentionManager internal constructor(
    private val context: Context,
    private val database: BridgeDatabase,
) {
    private val preferences = TrainingRetentionPreferences(context)
    private val mutex = Mutex()
    private val mutableStatus = MutableStateFlow(RetentionStatus(configuredHours = preferences.hours()))
    val status: StateFlow<RetentionStatus> = mutableStatus.asStateFlow()

    fun setHours(hours: Int) {
        preferences.setHours(hours)
        mutableStatus.value = mutableStatus.value.copy(configuredHours = hours)
        schedule(context)
    }

    suspend fun enforce(): EnforcementResult = mutex.withLock {
        val dao = database.workouts()
        val hours = preferences.hours()
        if (dao.activeWorkout() != null) {
            updateStatus(hours, dao.completedWorkoutsForRetention(), cleanupBlocked = false)
            return@withLock EnforcementResult.DEFERRED_ACTIVE_WORKOUT
        }

        val selection = database.withTransaction {
            val workouts = dao.completedWorkoutsForRetention()
            selectWorkoutsForDeletion(workouts, hours * 3_600L).also { result ->
                result.workoutIds.forEach { dao.deleteCompletedWorkout(it) }
            }
        }
        if (selection.workoutIds.isNotEmpty()) compactDatabase()
        updateStatus(hours, dao.completedWorkoutsForRetention(), selection.blocked)
        EnforcementResult.COMPLETE
    }

    private fun updateStatus(
        hours: Int,
        workouts: List<WorkoutEntity>,
        cleanupBlocked: Boolean,
    ) {
        mutableStatus.value = RetentionStatus(
            configuredHours = hours,
            retainedSeconds = workouts.sumOf { workout ->
                ((workout.endedAtMillis ?: workout.startedAtMillis) - workout.startedAtMillis)
                    .coerceAtLeast(0) / 1_000
            },
            databaseBytes = databaseSize(),
            cleanupBlocked = cleanupBlocked,
            loading = false,
        )
    }

    private fun compactDatabase() {
        val sqlite = database.openHelper.writableDatabase
        sqlite.query("PRAGMA wal_checkpoint(FULL)").close()
        sqlite.execSQL("VACUUM")
    }

    private fun databaseSize(): Long {
        val databaseFile = context.getDatabasePath(DATABASE_NAME)
        return listOf(databaseFile, File("${databaseFile.path}-wal"), File("${databaseFile.path}-shm"))
            .sumOf { file -> if (file.exists()) file.length() else 0L }
    }

    enum class EnforcementResult {
        COMPLETE,
        DEFERRED_ACTIVE_WORKOUT,
    }

    companion object {
        const val DATABASE_NAME = "ftms-bridge.db"
        private const val UNIQUE_WORK = "training-history-retention"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<TrainingRetentionWorker>().build(),
            )
        }
    }
}

class TrainingRetentionWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as BridgeApplication
        return when (app.retention.enforce()) {
            TrainingRetentionManager.EnforcementResult.COMPLETE -> Result.success()
            TrainingRetentionManager.EnforcementResult.DEFERRED_ACTIVE_WORKOUT -> Result.retry()
        }
    }
}
