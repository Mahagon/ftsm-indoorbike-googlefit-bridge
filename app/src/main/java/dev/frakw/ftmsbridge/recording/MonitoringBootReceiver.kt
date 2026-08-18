package dev.frakw.ftmsbridge.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.frakw.ftmsbridge.BridgeController

class MonitoringBootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && BridgeController.isMonitoringEnabled(context)) {
            RecordingService.start(context)
        }
    }
}
