package dev.frakw.ftmsbridge.model

import java.time.Instant

data class IndoorBikeSample(
    val timestamp: Instant,
    val speedKph: Double?,
    val cadenceRpm: Double?,
    val powerWatts: Int?,
    val totalDistanceMeters: Long?,
    val elapsedTimeSeconds: Int?,
)

sealed interface WorkoutTarget {
    data class Duration(val seconds: Long) : WorkoutTarget {
        init {
            require(seconds > 0)
        }
    }

    data class Distance(val meters: Double) : WorkoutTarget {
        init {
            require(meters.isFinite() && meters > 0)
        }
    }
}

data class DiscoveredBike(
    val name: String,
    val address: String,
    val signalDbm: Int,
)

enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, READY, RECORDING, FINALIZING, ERROR }

data class BridgeState(
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val bike: DiscoveredBike? = null,
    val devices: List<DiscoveredBike> = emptyList(),
    val latest: IndoorBikeSample? = null,
    val recordingId: String? = null,
    val startedAt: Instant? = null,
    val distanceMeters: Double = 0.0,
    val error: String? = null,
    val rawPacket: String? = null,
    val diagnostics: List<String> = emptyList(),
    val monitoringEnabled: Boolean = false,
    val target: WorkoutTarget? = null,
)
