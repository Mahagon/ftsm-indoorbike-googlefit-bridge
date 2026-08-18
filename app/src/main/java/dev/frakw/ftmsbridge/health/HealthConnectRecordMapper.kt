package dev.frakw.ftmsbridge.health

import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Power
import androidx.health.connect.client.units.Velocity
import dev.frakw.ftmsbridge.data.WorkoutWithSamples
import java.time.Instant
import java.time.ZoneId

class HealthConnectRecordMapper(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    fun map(value: WorkoutWithSamples): List<Record> {
        val workout = value.workout
        val endMillis = requireNotNull(workout.endedAtMillis) { "Only completed workouts can be exported" }
        require(endMillis >= workout.startedAtMillis) { "Workout end must not precede its start" }
        val start = Instant.ofEpochMilli(workout.startedAtMillis)
        val end = Instant.ofEpochMilli(endMillis)
        val startOffset = zoneId.rules.getOffset(start)
        val endOffset = zoneId.rules.getOffset(end)
        val samples =
            value.samples.sortedBy { it.timestampMillis }.filter {
                it.timestampMillis in workout.startedAtMillis..endMillis
            }
        val device = Device(type = Device.TYPE_UNKNOWN, manufacturer = "JC", model = "FTMS Indoor Bike")

        fun metadata(suffix: String) = Metadata.activelyRecorded(
            device = device,
            clientRecordId = "${workout.id}:$suffix",
            clientRecordVersion = 1,
        )

        val records = mutableListOf<Record>()
        records +=
            ExerciseSessionRecord(
                startTime = start,
                startZoneOffset = startOffset,
                endTime = end,
                endZoneOffset = endOffset,
                exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
                title = "Indoor bike",
                metadata = metadata("session"),
            )
        if (workout.distanceMeters > 0) {
            records +=
                DistanceRecord(
                    startTime = start,
                    startZoneOffset = startOffset,
                    endTime = end,
                    endZoneOffset = endOffset,
                    distance = Length.meters(workout.distanceMeters),
                    metadata = metadata("distance"),
                )
        }
        val speeds =
            samples.mapNotNull { row ->
                row.speedKph?.let {
                    SpeedRecord.Sample(Instant.ofEpochMilli(row.timestampMillis), Velocity.kilometersPerHour(it))
                }
            }
        if (speeds.isNotEmpty()) {
            records += SpeedRecord(start, startOffset, end, endOffset, speeds, metadata("speed"))
        }
        val cadence =
            samples.mapNotNull { row ->
                row.cadenceRpm?.let {
                    CyclingPedalingCadenceRecord.Sample(Instant.ofEpochMilli(row.timestampMillis), it)
                }
            }
        if (cadence.isNotEmpty()) {
            records +=
                CyclingPedalingCadenceRecord(
                    start,
                    startOffset,
                    end,
                    endOffset,
                    cadence,
                    metadata("cadence"),
                )
        }
        val powers =
            samples.mapNotNull { row ->
                row.powerWatts?.let {
                    PowerRecord.Sample(Instant.ofEpochMilli(row.timestampMillis), Power.watts(it.toDouble()))
                }
            }
        if (powers.isNotEmpty()) {
            records += PowerRecord(start, startOffset, end, endOffset, powers, metadata("power"))
        }
        return records
    }
}
