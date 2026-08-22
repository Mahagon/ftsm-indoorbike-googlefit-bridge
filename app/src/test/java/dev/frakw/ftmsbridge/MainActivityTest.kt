package dev.frakw.ftmsbridge

import dev.frakw.ftmsbridge.model.BridgeState
import dev.frakw.ftmsbridge.model.ConnectionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityTest {
    @Test
    fun `screen stays awake while bike is ready or recording`() {
        assertTrue(shouldKeepScreenAwake(ConnectionState.READY))
        assertTrue(shouldKeepScreenAwake(ConnectionState.RECORDING))
    }

    @Test
    fun `screen timeout remains enabled outside a connected session`() {
        ConnectionState.entries
            .filterNot { it == ConnectionState.READY || it == ConnectionState.RECORDING }
            .forEach { connection -> assertFalse(connection.name, shouldKeepScreenAwake(connection)) }
    }

    @Test
    fun `fullscreen is available only while recording`() {
        assertFalse(canShowFullscreen(ConnectionState.READY))
        assertTrue(canShowFullscreen(ConnectionState.RECORDING))
    }

    @Test
    fun `fullscreen is unavailable without live bike data`() {
        ConnectionState.entries
            .filterNot { it == ConnectionState.RECORDING }
            .forEach { connection -> assertFalse(connection.name, canShowFullscreen(connection)) }
    }

    @Test
    fun `start workout remains available while ready with background monitoring`() {
        assertTrue(shouldShowStartWorkout(BridgeState(connection = ConnectionState.READY, monitoringEnabled = true)))
        assertTrue(shouldShowStartWorkout(BridgeState(connection = ConnectionState.READY, monitoringEnabled = false)))
    }

    @Test
    fun `start workout is hidden during recording and finalization`() {
        assertFalse(shouldShowStartWorkout(BridgeState(connection = ConnectionState.RECORDING, recordingId = "ride")))
        assertFalse(shouldShowStartWorkout(BridgeState(connection = ConnectionState.FINALIZING)))
    }
}
