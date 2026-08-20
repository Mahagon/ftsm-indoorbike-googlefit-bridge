package dev.frakw.ftmsbridge.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import dev.frakw.ftmsbridge.BridgeApplication
import dev.frakw.ftmsbridge.MainActivity
import dev.frakw.ftmsbridge.R
import dev.frakw.ftmsbridge.model.BridgeState
import dev.frakw.ftmsbridge.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationJob: Job? = null
    private val controller get() = (application as BridgeApplication).controller

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.monitoring_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        startForeground(NOTIFICATION_ID, notification(controller.state.value))
        if (intent?.action == ACTION_DISABLE) {
            controller.setMonitoringEnabled(false)
            return START_NOT_STICKY
        }
        controller.resumeMonitoring()
        if (notificationJob?.isActive != true) {
            notificationJob =
                scope.launch {
                    controller.state.collect { state ->
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state))
                    }
                }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(state: BridgeState): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent().setClass(this, MainActivity::class.java).setPackage(packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val disable =
            PendingIntent.getService(
                this,
                1,
                Intent().setClass(this, RecordingService::class.java).setPackage(packageName).setAction(ACTION_DISABLE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return Notification
            .Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(notificationText(state))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop_monitoring), disable).build())
            .build()
    }

    private fun notificationText(state: BridgeState): String = when {
        state.recordingId != null -> getString(R.string.recording_bike, state.bike?.name.orEmpty())
        state.connection == ConnectionState.READY -> getString(R.string.connected_waiting)
        state.connection == ConnectionState.CONNECTING -> getString(R.string.waiting_bike, state.bike?.name ?: getString(R.string.bike))
        else -> getString(R.string.monitoring_active)
    }

    companion object {
        private const val CHANNEL = "monitoring"
        private const val NOTIFICATION_ID = 303
        private const val ACTION_DISABLE = "dev.frakw.ftmsbridge.DISABLE_MONITORING"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingService::class.java))
        }
    }
}
