package dev.frakw.ftmsbridge.ftms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FtmsPacketParserTest {
    private val parser = FtmsPacketParser()
    private val time = Instant.parse("2026-08-18T12:00:00Z")

    @Test
    fun parsesMandatorySpeed() {
        val result = parser.parse(byteArrayOf(0, 0, 0x3C, 0x0F), time)
        assertTrue(result is FtmsPacketParser.Result.Success)
        val sample = (result as FtmsPacketParser.Result.Success).sample
        assertEquals(39.0, sample.speedKph!!, 0.0)
        assertNull(sample.cadenceRpm)
    }

    @Test
    fun parsesCadenceDistancePowerAndElapsedTime() {
        // cadence + total distance + instantaneous power + elapsed time
        val flags = 0x0840 or 0x0010 or 0x0004
        val packet =
            byteArrayOf(
                flags.toByte(),
                (flags shr 8).toByte(),
                0x10,
                0x0E, // 36.00 km/h
                0xB4.toByte(),
                0x00, // 90 rpm (180 / 2)
                0x39,
                0x30,
                0x00, // 12345 metres
                0xFA.toByte(),
                0x00, // 250 watts
                0x58,
                0x02, // 600 seconds
            )
        val sample = (parser.parse(packet, time) as FtmsPacketParser.Result.Success).sample
        assertEquals(36.0, sample.speedKph!!, 0.0)
        assertEquals(90.0, sample.cadenceRpm!!, 0.0)
        assertEquals(12_345L, sample.totalDistanceMeters)
        assertEquals(250, sample.powerWatts)
        assertEquals(600, sample.elapsedTimeSeconds)
    }

    @Test
    fun moreDataOmitsInstantaneousSpeed() {
        val result = parser.parse(byteArrayOf(0x01, 0x00), time)
        val sample = (result as FtmsPacketParser.Result.Success).sample
        assertNull(sample.speedKph)
    }

    @Test
    fun parsesTotalEnergy() {
        val packet = byteArrayOf(
            0x00,
            0x01,
            0x10,
            0x0E,
            0x7B,
            0x00,
            0x34,
            0x12,
            0x05,
        )
        val sample = (parser.parse(packet, time) as FtmsPacketParser.Result.Success).sample
        assertEquals(123, sample.totalEnergyKcal)
    }

    @Test
    fun rejectsTruncatedOptionalField() {
        val result = parser.parse(byteArrayOf(0x04, 0x00, 0x10, 0x0E, 0x01), time)
        assertTrue(result is FtmsPacketParser.Result.Failure)
    }

    @Test
    fun parsesSignedNegativePower() {
        val flags = 0x0041 // more-data plus instantaneous power
        val packet = byteArrayOf(flags.toByte(), 0, 0xF6.toByte(), 0xFF.toByte())
        val sample = (parser.parse(packet, time) as FtmsPacketParser.Result.Success).sample
        assertEquals(-10, sample.powerWatts)
    }
}
