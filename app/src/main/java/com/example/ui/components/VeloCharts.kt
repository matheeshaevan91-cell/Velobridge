package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.domain.model.LatencySample
import com.example.domain.model.TelemetrySample
import com.example.ui.theme.LocalVeloColors
import com.example.ui.theme.MonoDataStyle

@Composable
fun DualTrafficChart(
    samples: List<TelemetrySample>,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val velo = LocalVeloColors.current
    val downloadColor = if (isActive) velo.success else MaterialTheme.colorScheme.outline
    val uploadColor = if (isActive) velo.info else MaterialTheme.colorScheme.outlineVariant
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)

    Column(modifier = modifier.fillMaxWidth()) {
        val peakSample = samples.maxOfOrNull { maxOf(it.downBps, it.upBps) } ?: 0L
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ChartLegendDot(color = downloadColor, label = "Download")
                ChartLegendDot(color = uploadColor, label = "Upload")
            }
            Text(
                text = if (peakSample > 0L) "Peak ${formatSpeed(peakSample)}" else "Idle",
                style = MonoDataStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(92.dp)
        ) {
            listOf(0.25f, 0.5f, 0.75f, 1f).forEach { ratio ->
                val y = size.height * ratio - 1.dp.toPx()
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y.coerceAtLeast(0f)),
                    end = Offset(size.width, y.coerceAtLeast(0f)),
                    strokeWidth = 1.dp.toPx()
                )
            }

            val points = if (samples.size >= 2) {
                samples
            } else {
                val single = samples.firstOrNull() ?: TelemetrySample(0L, 0L, 0L)
                listOf(single, single)
            }

            val maxVal = points.maxOf { maxOf(it.downBps, it.upBps) }.coerceAtLeast(1024L).toFloat()
            val stepX = size.width / (points.size - 1).coerceAtLeast(1)

            fun drawSeries(values: List<Long>, strokeColor: Color, withFill: Boolean) {
                val linePath = Path()
                val fillPath = Path()
                values.forEachIndexed { idx, v ->
                    val x = idx * stepX
                    val norm = (v.toFloat() / maxVal).coerceIn(0f, 1f)
                    val y = size.height - (norm * (size.height * 0.84f)) - 4.dp.toPx()
                    if (idx == 0) {
                        linePath.moveTo(x, y)
                        fillPath.moveTo(x, size.height)
                        fillPath.lineTo(x, y)
                    } else {
                        val prevX = (idx - 1) * stepX
                        val prevNorm = (values[idx - 1].toFloat() / maxVal).coerceIn(0f, 1f)
                        val prevY = size.height - (prevNorm * (size.height * 0.84f)) - 4.dp.toPx()
                        val midX = (prevX + x) / 2f
                        linePath.cubicTo(midX, prevY, midX, y, x, y)
                        fillPath.cubicTo(midX, prevY, midX, y, x, y)
                    }
                    if (idx == values.lastIndex) {
                        fillPath.lineTo(x, size.height)
                        fillPath.close()
                    }
                }
                if (withFill) {
                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(strokeColor.copy(alpha = 0.22f), Color.Transparent)
                        )
                    )
                }
                drawPath(
                    path = linePath,
                    color = strokeColor,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            drawSeries(points.map { it.downBps }, downloadColor, withFill = true)
            drawSeries(points.map { it.upBps }, uploadColor, withFill = false)
        }
    }
}

@Composable
fun LatencyHistoryChart(
    samples: List<LatencySample>,
    modifier: Modifier = Modifier
) {
    val velo = LocalVeloColors.current
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)

    if (samples.isEmpty()) {
        Text(
            text = "Run a latency ping, SNI probe, or connect to capture live RTT measurements.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
        )
        return
    }

    Column(modifier = modifier.fillMaxWidth()) {
        val avg = samples.map { it.rttMs }.average().toLong()
        val min = samples.minOf { it.rttMs }
        val max = samples.maxOf { it.rttMs }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Min ${min}ms • Avg ${avg}ms • Max ${max}ms",
                style = MonoDataStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "${samples.size} samples",
                style = MonoDataStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
        ) {
            drawLine(
                color = gridColor,
                start = Offset(0f, size.height - 1.dp.toPx()),
                end = Offset(size.width, size.height - 1.dp.toPx()),
                strokeWidth = 1.dp.toPx()
            )
            val maxRtt = samples.maxOf { it.rttMs }.coerceAtLeast(100L).toFloat()
            val slotWidth = size.width / samples.size.coerceAtLeast(1)
            val barWidth = (slotWidth * 0.62f).coerceIn(6.dp.toPx(), 18.dp.toPx())

            samples.forEachIndexed { idx, sample ->
                val norm = (sample.rttMs.toFloat() / maxRtt).coerceIn(0.08f, 1f)
                val barHeight = (size.height * 0.85f) * norm
                val x = idx * slotWidth + (slotWidth - barWidth) / 2f
                val y = size.height - barHeight
                val barColor = when {
                    sample.rttMs < 100 -> velo.success
                    sample.rttMs < 240 -> velo.warning
                    else -> velo.danger
                }
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(x, y),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )
            }
        }
    }
}

@Composable
private fun ChartLegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = CircleShape,
            color = color,
            modifier = Modifier.size(8.dp)
        ) {}
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
