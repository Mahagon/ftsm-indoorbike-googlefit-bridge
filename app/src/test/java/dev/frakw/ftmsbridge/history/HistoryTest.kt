package dev.frakw.ftmsbridge.history

import dev.frakw.ftmsbridge.data.SampleEntity
import dev.frakw.ftmsbridge.data.WorkoutEntity
import dev.frakw.ftmsbridge.data.WorkoutWithSamples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {
    @Test
    fun `page keeps requested rows and detects another page`() {
        val rows = (1..21).map { workout(it.toString(), it.toLong()) }
        val page = rows.toPage(20)
        assertEquals(20, page.workouts.size)
        assertTrue(page.hasMore)
        assertFalse(rows.take(10).toPage(20).hasMore)
    }

    @Test
    fun `details summarize available samples and ignore missing values`() {
        val value = WorkoutWithSamples(
            workout("ride", 1),
            listOf(
                SampleEntity("ride", 2, 20.0, null, 100, null),
                SampleEntity("ride", 3, 30.0, 80.0, 200, null),
            ),
        ).toDetails()

        assertEquals(25.0, value.speedKph?.average ?: 0.0, 0.001)
        assertEquals(30.0, value.speedKph?.maximum ?: 0.0, 0.001)
        assertEquals(80.0, value.cadenceRpm?.average ?: 0.0, 0.001)
        assertEquals(150.0, value.powerWatts?.average ?: 0.0, 0.001)
        assertTrue(value.hasSamples)
    }

    @Test
    fun `details leave absent metrics unavailable`() {
        val value = WorkoutWithSamples(workout("ride", 1), emptyList()).toDetails()
        assertNull(value.speedKph)
        assertNull(value.cadenceRpm)
        assertNull(value.powerWatts)
        assertFalse(value.hasSamples)
    }

    @Test
    fun `sync label reflects persisted state`() {
        assertEquals(dev.frakw.ftmsbridge.R.string.save_pending, syncLabelResource(workout("pending", 1)))
        assertEquals(dev.frakw.ftmsbridge.R.string.save_failed, syncLabelResource(workout("failed", 1).copy(syncError = "denied")))
        assertEquals(
            dev.frakw.ftmsbridge.R.string.saved_health_connect,
            syncLabelResource(workout("synced", 1).copy(synced = true, syncError = "old")),
        )
    }

    private fun workout(id: String, started: Long) = WorkoutEntity(
        id = id,
        startedAtMillis = started,
        endedAtMillis = started + 1_000,
        state = WorkoutEntity.STATE_COMPLETE,
    )
}
