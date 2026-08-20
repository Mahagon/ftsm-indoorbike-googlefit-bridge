package dev.frakw.ftmsbridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.frakw.ftmsbridge.data.WorkoutEntity
import dev.frakw.ftmsbridge.data.target
import dev.frakw.ftmsbridge.model.WorkoutTarget
import java.util.Locale

internal enum class TargetKind { DURATION, DISTANCE }

internal fun parseTarget(kind: TargetKind, input: String): WorkoutTarget? = when (kind) {
    TargetKind.DURATION -> input.trim().toLongOrNull()
        ?.takeIf { it in 1..Long.MAX_VALUE / 60 }
        ?.let { WorkoutTarget.Duration(it * 60) }

    TargetKind.DISTANCE -> input.trim().replace(',', '.').toDoubleOrNull()
        ?.takeIf { it.isFinite() && it > 0 && it <= Double.MAX_VALUE / 1_000 }
        ?.let { WorkoutTarget.Distance(it * 1_000) }
}

internal fun targetProgress(target: WorkoutTarget?, durationSeconds: Long, distanceMeters: Double): Float? = when (target) {
    is WorkoutTarget.Duration -> (durationSeconds.toDouble() / target.seconds).toFloat().coerceIn(0f, 1f)
    is WorkoutTarget.Distance -> (distanceMeters / target.meters).toFloat().coerceIn(0f, 1f)
    null -> null
}

internal fun targetReached(workout: WorkoutEntity): Boolean? = when (val target = workout.target()) {
    is WorkoutTarget.Duration -> workout.endedAtMillis?.let { end ->
        (end - workout.startedAtMillis).coerceAtLeast(0) / 1_000 >= target.seconds
    }

    is WorkoutTarget.Distance -> workout.distanceMeters >= target.meters

    null -> null
}

internal fun formatTarget(target: WorkoutTarget): String = when (target) {
    is WorkoutTarget.Duration -> "${target.seconds / 60} min"
    is WorkoutTarget.Distance -> String.format(Locale.getDefault(), "%.2f km", target.meters / 1_000)
}

@Composable
internal fun TargetEditor(current: WorkoutTarget?, onTargetChanged: (WorkoutTarget?) -> Unit) {
    var kind by rememberSaveable { mutableStateOf(current.kind()) }
    var input by rememberSaveable { mutableStateOf(current.inputValue()) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(current) {
        if (current != null) {
            kind = current.kind()
            input = current.inputValue()
            invalid = false
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Session target", style = MaterialTheme.typography.titleMedium)
            Text("Applies to the next workout only", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = kind == TargetKind.DURATION,
                    onClick = {
                        kind = TargetKind.DURATION
                        input = ""
                        invalid = false
                    },
                    label = { Text("Duration") },
                )
                FilterChip(
                    selected = kind == TargetKind.DISTANCE,
                    onClick = {
                        kind = TargetKind.DISTANCE
                        input = ""
                        invalid = false
                    },
                    label = { Text("Distance") },
                )
            }
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    invalid = false
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (kind == TargetKind.DURATION) "Minutes" else "Kilometers") },
                suffix = { Text(if (kind == TargetKind.DURATION) "min" else "km") },
                singleLine = true,
                isError = invalid,
                supportingText = if (invalid) {
                    { Text(if (kind == TargetKind.DURATION) "Enter positive whole minutes" else "Enter a positive distance") }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (kind == TargetKind.DURATION) KeyboardType.Number else KeyboardType.Decimal,
                ),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val target = parseTarget(kind, input)
                        invalid = target == null
                        if (target != null) onTargetChanged(target)
                    },
                ) { Text(if (current == null) "Set target" else "Update target") }
                if (current != null) {
                    TextButton(
                        onClick = {
                            input = ""
                            invalid = false
                            onTargetChanged(null)
                        },
                    ) { Text("Clear") }
                }
            }
        }
    }
}

@Composable
internal fun ProgressMetric(label: String, value: String, target: String, progress: Float) {
    val fill = MaterialTheme.colorScheme.primaryContainer
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(progress, 0f..1f) }
                .drawProgress(fill, progress)
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(label)
                Text(
                    if (progress >= 1f) "$target · Target reached" else "Target $target",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

private fun Modifier.drawProgress(color: Color, progress: Float) = drawBehind {
    drawProgressFill(color, progress)
}

private fun DrawScope.drawProgressFill(color: Color, progress: Float) {
    drawRect(color = color, size = size.copy(width = size.width * progress.coerceIn(0f, 1f)))
}

private fun WorkoutTarget?.kind() = if (this is WorkoutTarget.Distance) TargetKind.DISTANCE else TargetKind.DURATION

private fun WorkoutTarget?.inputValue(): String = when (this) {
    is WorkoutTarget.Duration -> (seconds / 60).toString()
    is WorkoutTarget.Distance -> String.format(Locale.US, "%.2f", meters / 1_000).trimEnd('0').trimEnd('.')
    null -> ""
}
