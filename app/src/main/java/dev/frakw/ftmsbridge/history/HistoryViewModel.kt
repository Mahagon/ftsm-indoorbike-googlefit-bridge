package dev.frakw.ftmsbridge.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

data class MetricSummary(
    val average: Double,
    val maximum: Double,
)

data class WorkoutDetails(
    val workout: WorkoutEntity,
    val hasSamples: Boolean,
    val speedKph: MetricSummary?,
    val cadenceRpm: MetricSummary?,
    val powerWatts: MetricSummary?,
)

internal fun WorkoutWithSamples.toDetails(): WorkoutDetails = WorkoutDetails(
    workout = workout,
    hasSamples = samples.isNotEmpty(),
    speedKph = samples.mapNotNull { it.speedKph }.summary(),
    cadenceRpm = samples.mapNotNull { it.cadenceRpm }.summary(),
    powerWatts = samples.mapNotNull { it.powerWatts?.toDouble() }.summary(),
)

private fun List<Double>.summary(): MetricSummary? = if (isEmpty()) null else MetricSummary(average(), max())

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
