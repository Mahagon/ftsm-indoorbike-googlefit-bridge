package dev.frakw.ftmsbridge.metrics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun MetricChart(series: MetricSeries, unit: String, startedAtMillis: Long, endedAtMillis: Long, chartHeight: Dp = 140.dp) {
    val locale = Locale.forLanguageTag(LocalLocale.current.toLanguageTag())
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val durationMillis = (endedAtMillis - startedAtMillis).coerceAtLeast(1L)
    val scaleMaximum = series.maximum.coerceAtLeast(1.0)
    Row(Modifier.fillMaxWidth().height(chartHeight)) {
        Column(Modifier.width(64.dp).fillMaxHeight().padding(end = 8.dp), Arrangement.SpaceBetween, Alignment.End) {
            Text(String.format(locale, "%.1f %s", series.maximum, unit), style = MaterialTheme.typography.labelSmall)
            Text(String.format(locale, "%.1f", 0.0), style = MaterialTheme.typography.labelSmall)
        }
        Canvas(Modifier.weight(1f).fillMaxHeight()) {
            drawLine(gridColor, Offset.Zero, Offset(size.width, 0f))
            drawLine(gridColor, Offset(0f, size.height), Offset(size.width, size.height))
            fun offset(point: MetricPoint) = Offset(
                ((point.timestampMillis - startedAtMillis).toDouble() / durationMillis).coerceIn(0.0, 1.0).toFloat() * size.width,
                size.height - (point.value / scaleMaximum).coerceIn(0.0, 1.0).toFloat() * size.height,
            )
            if (series.points.size == 1) {
                drawCircle(lineColor, 3.dp.toPx(), offset(series.points.single()))
            } else if (series.points.isNotEmpty()) {
                val path = Path()
                series.points.forEachIndexed { index, point ->
                    val position = offset(point)
                    if (index == 0) path.moveTo(position.x, position.y) else path.lineTo(position.x, position.y)
                }
                drawPath(path, lineColor, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.width(64.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatElapsed(0), style = MaterialTheme.typography.labelSmall)
            Text(formatElapsed(durationMillis), style = MaterialTheme.typography.labelSmall)
        }
    }
}
