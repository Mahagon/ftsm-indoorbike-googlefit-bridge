package dev.frakw.ftmsbridge.health

import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Power
import androidx.health.connect.client.units.Velocity
import dev.frakw.ftmsbridge.data.WorkoutWithSamples
import java.time.Instant
import java.time.ZoneId

class HealthConnectRecordMapper(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    fun map(value: WorkoutWithSamples, title: String = "Exercise bike"): List<Record> {
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
                title = title,
                metadata = metadata("session"),
            )
        records += distanceRecords(workout.distanceMeters, samples, start, end, ::metadata)
        workout.caloriesKcal?.takeIf { it > 0.0 }?.let { calories ->
            records += TotalCaloriesBurnedRecord(
                startTime = start,
                startZoneOffset = startOffset,
                endTime = end,
                endZoneOffset = endOffset,
                energy = Energy.kilocalories(calories),
                metadata = metadata("calories"),
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

    private fun distanceRecords(
        totalMeters: Double,
        samples: List<dev.frakw.ftmsbridge.data.SampleEntity>,
        start: Instant,
        end: Instant,
        metadata: (String) -> Metadata,
    ): List<DistanceRecord> {
        if (totalMeters <= 0.0) return emptyList()
        val durationMillis = (end.toEpochMilli() - start.toEpochMilli()).coerceAtLeast(1L)
        val buckets = sortedMapOf<Long, Double>()
        var previous = 0.0
        samples.forEach { sample ->
            val cumulative = sample.sessionDistanceMeters ?: return@forEach
            val normalized = cumulative.coerceIn(previous, totalMeters)
            val delta = normalized - previous
            if (delta > 0.0) {
                val bucket = ((sample.timestampMillis - start.toEpochMilli()).coerceAtLeast(0L) / 60_000L)
                    .coerceAtMost((durationMillis - 1L) / 60_000L)
                buckets[bucket] = buckets.getOrDefault(bucket, 0.0) + delta
            }
            previous = normalized
        }
        val remainder = totalMeters - buckets.values.sum()
        if (remainder > 0.000_001) {
            val lastBucket = (durationMillis - 1L) / 60_000L
            buckets[lastBucket] = buckets.getOrDefault(lastBucket, 0.0) + remainder
        }
        return buckets.mapNotNull { (bucket, meters) ->
            if (meters <= 0.0) return@mapNotNull null
            val intervalStart = start.plusMillis(bucket * 60_000L)
            val intervalEnd = minOf(end, start.plusMillis((bucket + 1L) * 60_000L))
            DistanceRecord(
                startTime = intervalStart,
                startZoneOffset = zoneId.rules.getOffset(intervalStart),
                endTime = intervalEnd,
                endZoneOffset = zoneId.rules.getOffset(intervalEnd),
                distance = Length.meters(meters),
                metadata = metadata("distance-$bucket"),
            )
        }
    }
}
