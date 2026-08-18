package dev.frakw.ftmsbridge.ftms

import dev.frakw.ftmsbridge.model.ConnectionState
import dev.frakw.ftmsbridge.model.DiscoveredBike
import dev.frakw.ftmsbridge.model.IndoorBikeSample
import kotlinx.coroutines.flow.StateFlow

data class FtmsClientState(
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val devices: List<DiscoveredBike> = emptyList(),
    val selected: DiscoveredBike? = null,
    val latest: IndoorBikeSample? = null,
    val rawPacket: String? = null,
    val diagnostics: List<String> = emptyList(),
    val error: String? = null,
)

interface FtmsClient {
    val state: StateFlow<FtmsClientState>

    fun startScan()

    fun stopScan()

    fun connect(
        address: String,
        autoConnect: Boolean = false,
    )

    fun disconnect()
}
