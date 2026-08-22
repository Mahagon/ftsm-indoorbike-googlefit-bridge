package dev.frakw.ftmsbridge.metrics

import dev.frakw.ftmsbridge.data.SampleEntity

data class MetricPoint(val timestampMillis: Long, val value: Double)

data class MetricSeries(
    val average: Double,
    val maximum: Double,
    val points: List<MetricPoint>,
)

data class WorkoutMetricSnapshot(
    val speedKph: MetricSeries? = null,
    val cadenceRpm: MetricSeries? = null,
    val powerWatts: MetricSeries? = null,
)

class LiveWorkoutMetricsAccumulator {
    private val speed = MetricAccumulator()
    private val cadence = MetricAccumulator()
    private val power = MetricAccumulator()

    fun add(sample: SampleEntity) {
        speed.add(sample.timestampMillis, sample.speedKph)
        cadence.add(sample.timestampMillis, sample.cadenceRpm)
        power.add(sample.timestampMillis, sample.powerWatts?.toDouble())
    }

    fun addAll(samples: Iterable<SampleEntity>) = samples.sortedBy { it.timestampMillis }.forEach(::add)

    fun snapshot() = WorkoutMetricSnapshot(speed.series(), cadence.series(), power.series())

    fun clear() {
        speed.clear()
        cadence.clear()
        power.clear()
    }
}

private class MetricAccumulator {
    private var sum = 0.0
    private var count = 0L
    private var maximum = Double.NEGATIVE_INFINITY
    private val points = mutableListOf<MetricPoint>()

    fun add(timestampMillis: Long, value: Double?) {
        if (value == null) return
        sum += value
        count++
        maximum = maxOf(maximum, value)
        val insertion = points.binarySearchBy(timestampMillis) { it.timestampMillis }
        if (insertion < 0) {
            points.add(-insertion - 1, MetricPoint(timestampMillis, value))
        } else {
            points[insertion] = MetricPoint(timestampMillis, value)
        }
        if (points.size > MAX_CHART_POINTS) {
            val reduced = points.downsample(MAX_CHART_POINTS)
            points.clear()
            points.addAll(reduced)
        }
    }

    fun series() = if (count == 0L) null else MetricSeries(sum / count, maximum, points.toList())

    fun clear() {
        sum = 0.0
        count = 0
        maximum = Double.NEGATIVE_INFINITY
        points.clear()
    }
}

fun List<SampleEntity>.metricSnapshot(): WorkoutMetricSnapshot {
    val accumulator = LiveWorkoutMetricsAccumulator()
    accumulator.addAll(this)
    return accumulator.snapshot()
}

fun List<MetricPoint>.downsample(maxPoints: Int): List<MetricPoint> {
    require(maxPoints >= 4)
    if (size <= maxPoints) return this
    val interior = subList(1, lastIndex)
    val bucketCount = (maxPoints - 2) / 2
    val bucketSize = (interior.size + bucketCount - 1) / bucketCount
    val reduced = interior.chunked(bucketSize).flatMap { bucket ->
        val minimum = bucket.minBy { it.value }
        val maximum = bucket.maxBy { it.value }
        if (minimum === maximum) listOf(minimum) else listOf(minimum, maximum).sortedBy { it.timestampMillis }
    }
    return buildList {
        add(this@downsample.first())
        addAll(reduced)
        add(this@downsample.last())
    }
}

fun formatElapsed(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1_000
    val hours = seconds / 3_600
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, seconds / 60 % 60, seconds % 60)
    } else {
        "%02d:%02d".format(seconds / 60, seconds % 60)
    }
}

const val MAX_CHART_POINTS = 600
