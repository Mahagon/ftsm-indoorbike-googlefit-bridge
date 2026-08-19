package dev.frakw.ftmsbridge.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.frakw.ftmsbridge.data.WorkoutDao
import dev.frakw.ftmsbridge.data.WorkoutEntity
import dev.frakw.ftmsbridge.data.WorkoutWithSamples
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class WorkoutPage(
    val workouts: List<WorkoutEntity> = emptyList(),
    val hasMore: Boolean = false,
    val isLoading: Boolean = true,
)

internal fun List<WorkoutEntity>.toPage(limit: Int) = WorkoutPage(take(limit), size > limit, isLoading = false)

data class MetricSummary(
    val average: Double,
    val maximum: Double,
)

data class WorkoutDetails(
    val workout: WorkoutEntity,
    val speedKph: MetricSummary?,
    val cadenceRpm: MetricSummary?,
    val powerWatts: MetricSummary?,
)

internal fun WorkoutWithSamples.toDetails(): WorkoutDetails = WorkoutDetails(
    workout = workout,
    speedKph = samples.mapNotNull { it.speedKph }.summary(),
    cadenceRpm = samples.mapNotNull { it.cadenceRpm }.summary(),
    powerWatts = samples.mapNotNull { it.powerWatts?.toDouble() }.summary(),
)

private fun List<Double>.summary(): MetricSummary? = if (isEmpty()) null else MetricSummary(average(), max())

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(
    private val dao: WorkoutDao,
) : ViewModel() {
    private val requested = MutableStateFlow(PAGE_SIZE)

    val page: StateFlow<WorkoutPage> = requested
        .flatMapLatest { limit ->
            dao.completedWorkouts(limit + 1).map { rows ->
                rows.toPage(limit)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkoutPage())

    fun loadMore() {
        if (page.value.hasMore) requested.value += PAGE_SIZE
    }

    fun details(id: String): Flow<WorkoutDetails?> = dao.observeCompletedWorkout(id).map { it?.toDetails() }

    companion object {
        internal const val PAGE_SIZE = 20
    }
}
