package dev.frakw.ftmsbridge.metrics

import dev.frakw.ftmsbridge.data.SampleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutMetricsTest {
    @Test
    fun `accumulates exact statistics and missing metrics`() {
        val accumulator = LiveWorkoutMetricsAccumulator()
        accumulator.add(sample(1, speed = 10.0, cadence = null, power = 100))
        accumulator.add(sample(2, speed = 20.0, cadence = null, power = 300))

        val snapshot = accumulator.snapshot()
        assertEquals(15.0, snapshot.speedKph!!.average, 0.0)
        assertEquals(20.0, snapshot.speedKph.maximum, 0.0)
        assertNull(snapshot.cadenceRpm)
        assertEquals(200.0, snapshot.powerWatts!!.average, 0.0)
    }

    @Test
    fun `orders timestamps and bounds plot while preserving endpoints and extrema`() {
        val accumulator = LiveWorkoutMetricsAccumulator()
        (700 downTo 0).forEach { timestamp ->
            val speed = when (timestamp) {
                350 -> 10_000.0
                351 -> -100.0
                else -> timestamp.toDouble()
            }
            accumulator.add(sample(timestamp.toLong(), speed = speed))
        }

        val series = accumulator.snapshot().speedKph!!
        assertTrue(series.points.size <= MAX_CHART_POINTS)
        assertEquals(series.points.sortedBy { it.timestampMillis }, series.points)
        assertEquals(0L, series.points.first().timestampMillis)
        assertEquals(700L, series.points.last().timestampMillis)
        assertTrue(series.points.any { it.value == 10_000.0 })
        assertTrue(series.points.any { it.value == -100.0 })
        assertEquals(
            (0..700).sumOf {
                if (it == 350) {
                    10_000.0
                } else if (it == 351) {
                    -100.0
                } else {
                    it.toDouble()
                }
            } / 701,
            series.average,
            0.0001,
        )
    }

    @Test
    fun `clear removes all live data`() {
        val accumulator = LiveWorkoutMetricsAccumulator()
        accumulator.add(sample(1, speed = 20.0))
        accumulator.clear()
        assertNull(accumulator.snapshot().speedKph)
    }

    private fun sample(timestamp: Long, speed: Double? = null, cadence: Double? = null, power: Int? = null) = SampleEntity("ride", timestamp, speed, cadence, power, null)
}
