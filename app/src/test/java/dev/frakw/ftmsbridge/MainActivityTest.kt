package dev.frakw.ftmsbridge

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
}
