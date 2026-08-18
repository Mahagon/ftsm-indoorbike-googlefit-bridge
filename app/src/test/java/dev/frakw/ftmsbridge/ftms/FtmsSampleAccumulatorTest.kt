package dev.frakw.ftmsbridge.ftms

import dev.frakw.ftmsbridge.model.IndoorBikeSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class FtmsSampleAccumulatorTest {
    private val start = Instant.parse("2026-08-18T12:00:00Z")

    @Test
    fun retainsFieldsOmittedFromLaterPackets() {
        val accumulator = FtmsSampleAccumulator()
        accumulator.merge(IndoorBikeSample(start, null, 90.0, 250, null, null))

        val merged = accumulator.merge(IndoorBikeSample(start.plusMillis(100), 32.0, null, null, 1234, null))

        assertEquals(32.0, merged.speedKph!!, 0.0)
        assertEquals(90.0, merged.cadenceRpm!!, 0.0)
        assertEquals(250, merged.powerWatts)
        assertEquals(1234L, merged.totalDistanceMeters)
        assertEquals(start.plusMillis(100), merged.timestamp)
    }

    @Test
    fun explicitZeroReplacesPreviousValues() {
        val accumulator = FtmsSampleAccumulator()
        accumulator.merge(IndoorBikeSample(start, null, 90.0, 250, null, null))

        val merged = accumulator.merge(IndoorBikeSample(start.plusSeconds(1), null, 0.0, 0, null, null))

        assertEquals(0.0, merged.cadenceRpm!!, 0.0)
        assertEquals(0, merged.powerWatts)
    }

    @Test
    fun resetDoesNotLeakValuesIntoNewConnection() {
        val accumulator = FtmsSampleAccumulator()
        accumulator.merge(IndoorBikeSample(start, null, 90.0, 250, null, null))
        accumulator.reset()

        val merged = accumulator.merge(IndoorBikeSample(start.plusSeconds(1), 20.0, null, null, null, null))

        assertNull(merged.cadenceRpm)
        assertNull(merged.powerWatts)
    }
}
