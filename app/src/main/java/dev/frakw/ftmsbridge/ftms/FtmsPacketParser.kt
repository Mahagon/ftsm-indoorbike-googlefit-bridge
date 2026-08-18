package dev.frakw.ftmsbridge.ftms

import dev.frakw.ftmsbridge.model.IndoorBikeSample
import java.time.Instant

class FtmsPacketParser {
    sealed interface Result {
        data class Success(
            val sample: IndoorBikeSample,
        ) : Result

        data class Failure(
            val reason: String,
        ) : Result
    }

    fun parse(
        bytes: ByteArray,
        timestamp: Instant = Instant.now(),
    ): Result = try {
        val reader = LittleEndianReader(bytes)
        val flags = reader.u16()
        val moreData = flags and 0x0001 != 0

        val speed = if (!moreData) reader.u16() / 100.0 else null
        if (flags and 0x0002 != 0) reader.u16() // average speed
        val cadence = if (flags and 0x0004 != 0) reader.u16() / 2.0 else null
        if (flags and 0x0008 != 0) reader.u16() // average cadence
        val distance = if (flags and 0x0010 != 0) reader.u24().toLong() else null
        if (flags and 0x0020 != 0) reader.s16() // resistance level
        val power = if (flags and 0x0040 != 0) reader.s16() else null
        if (flags and 0x0080 != 0) reader.s16() // average power
        if (flags and 0x0100 != 0) {
            reader.u16() // total energy
            reader.u16() // energy per hour
            reader.u8() // energy per minute
        }
        if (flags and 0x0200 != 0) reader.u8() // heart rate
        if (flags and 0x0400 != 0) reader.u8() // MET
        val elapsed = if (flags and 0x0800 != 0) reader.u16() else null
        if (flags and 0x1000 != 0) reader.u16() // remaining time

        Result.Success(
            IndoorBikeSample(timestamp, speed, cadence, power, distance, elapsed),
        )
    } catch (error: IllegalArgumentException) {
        Result.Failure(error.message ?: "Malformed FTMS packet")
    }

    private class LittleEndianReader(
        private val bytes: ByteArray,
    ) {
        private var offset = 0

        fun u8(): Int {
            require(offset < bytes.size) { "Truncated FTMS packet at byte $offset" }
            return bytes[offset++].toInt() and 0xff
        }

        fun u16(): Int = u8() or (u8() shl 8)

        fun s16(): Int = u16().toShort().toInt()

        fun u24(): Int = u8() or (u8() shl 8) or (u8() shl 16)
    }
}

fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
