package dev.frakw.ftmsbridge.health

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.frakw.ftmsbridge.BridgeApplication

class HealthSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as BridgeApplication
        if (!app.healthWriter.hasPermissions()) return Result.failure()
        var retry = false
        app.database.workouts().pendingSync().forEach { workout ->
            val value = app.database.workouts().workout(workout.id) ?: return@forEach
            try {
                app.healthWriter.write(value)
                app.database.workouts().markSynced(workout.id)
            } catch (error: Exception) {
                app.database.workouts().markSyncFailed(workout.id, error.message ?: error.javaClass.simpleName)
                retry = true
            }
        }
        return if (retry) Result.retry() else Result.success()
    }
}
