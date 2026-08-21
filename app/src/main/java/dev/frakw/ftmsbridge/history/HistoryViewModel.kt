package dev.frakw.ftmsbridge.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.frakw.ftmsbridge.data.SampleEntity
import dev.frakw.ftmsbridge.data.WorkoutDao
import dev.frakw.ftmsbridge.data.WorkoutEntity
import dev.frakw.ftmsbridge.data.WorkoutWithSamples
import dev.frakw.ftmsbridge.health.HealthConnectVerification
import dev.frakw.ftmsbridge.health.HealthConnectWorkoutVerifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class WorkoutPage(
    val workouts: List<WorkoutEntity> = emptyList(),
    val hasMore: Boolean = false,
    val isLoading: Boolean = true,
)

internal fun List<WorkoutEntity>.toPage(limit: Int) = WorkoutPage(take(limit), size > limit, isLoading = false)

data class MetricPoint(
    val timestampMillis: Long,
    val value: Double,
)

data class MetricSeries(
    val average: Double,
    val maximum: Double,
    val points: List<MetricPoint>,
)

data class WorkoutDetails(
    val workout: WorkoutEntity,
    val hasSamples: Boolean,
    val speedKph: MetricSeries?,
    val cadenceRpm: MetricSeries?,
    val powerWatts: MetricSeries?,
)

internal fun WorkoutWithSamples.toDetails(): WorkoutDetails {
    val ordered = samples.sortedBy { it.timestampMillis }
    return WorkoutDetails(
        workout = workout,
        hasSamples = samples.isNotEmpty(),
        speedKph = ordered.toMetricSeries { it.speedKph },
        cadenceRpm = ordered.toMetricSeries { it.cadenceRpm },
        powerWatts = ordered.toMetricSeries { it.powerWatts?.toDouble() },
    )
}

private fun List<SampleEntity>.toMetricSeries(
    value: (SampleEntity) -> Double?,
): MetricSeries? {
    val allPoints = mapNotNull { sample ->
        value(sample)?.let { MetricPoint(sample.timestampMillis, it) }
    }
    if (allPoints.isEmpty()) return null
    return MetricSeries(
        average = allPoints.map { it.value }.average(),
        maximum = allPoints.maxOf { it.value },
        points = allPoints.downsample(MAX_CHART_POINTS),
    )
}

internal fun List<MetricPoint>.downsample(maxPoints: Int): List<MetricPoint> {
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

internal const val MAX_CHART_POINTS = 600

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(
    private val dao: WorkoutDao,
    private val healthVerifier: HealthConnectWorkoutVerifier,
) : ViewModel() {
    private val requested = MutableStateFlow(PAGE_SIZE)

    val page: StateFlow<WorkoutPage> = requested
        .flatMapLatest { limit ->
            dao.completedWorkouts(limit + 1).map { rows ->
                rows.toPage(limit)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkoutPage())

    private val mutableHealthVerification = MutableStateFlow<HealthVerificationState>(HealthVerificationState.Idle)
    val healthVerification: StateFlow<HealthVerificationState> = mutableHealthVerification

    fun loadMore() {
        if (page.value.hasMore) requested.value += PAGE_SIZE
    }

    fun details(id: String): Flow<WorkoutDetails?> = dao.observeCompletedWorkout(id).map { it?.toDetails() }

    fun verifyHealthConnect(id: String) {
        mutableHealthVerification.value = HealthVerificationState.Loading(id)
        viewModelScope.launch {
            try {
                val workout = dao.workout(id) ?: error("Workout is no longer available")
                mutableHealthVerification.value = HealthVerificationState.Result(id, healthVerifier.verify(workout))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableHealthVerification.value =
                    HealthVerificationState.Error(id, error.message ?: error.javaClass.simpleName)
            }
        }
    }

    companion object {
        internal const val PAGE_SIZE = 20
    }
}

sealed interface HealthVerificationState {
    data object Idle : HealthVerificationState

    data class Loading(val workoutId: String) : HealthVerificationState

    data class Result(
        val workoutId: String,
        val verification: HealthConnectVerification,
    ) : HealthVerificationState

    data class Error(
        val workoutId: String,
        val message: String,
    ) : HealthVerificationState
}
