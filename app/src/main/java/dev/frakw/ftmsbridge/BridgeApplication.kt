package dev.frakw.ftmsbridge

import android.app.Application
import dev.frakw.ftmsbridge.data.BridgeDatabase
import dev.frakw.ftmsbridge.ftms.AndroidFtmsClient
import dev.frakw.ftmsbridge.health.HealthConnectWorkoutWriter
import dev.frakw.ftmsbridge.recording.WorkoutRecorder

class BridgeApplication : Application() {
    val database by lazy { BridgeDatabase.create(this) }
    val ftmsClient by lazy { AndroidFtmsClient(this) }
    val healthWriter by lazy { HealthConnectWorkoutWriter(this) }
    val recorder by lazy { WorkoutRecorder(database.workouts()) }
    val controller by lazy { BridgeController(this, ftmsClient, recorder) }
}
