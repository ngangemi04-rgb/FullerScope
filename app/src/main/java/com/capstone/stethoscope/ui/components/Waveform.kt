package com.capstone.stethoscope.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

/**
 * A scrolling amplitude waveform. [levels] is a rolling window (oldest first) that
 * the caller updates continuously; each new value pushes the trace leftward.
 * Drawn as vertical bars mirrored around the vertical center.
 */
@Composable
fun Waveform(
    levels: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF00838F),
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp),
    ) {
        val count = levels.size
        if (count == 0) return@Canvas
        val centerY = size.height / 2f
        val slot = size.width / count
        val barWidth = (slot * 0.6f).coerceAtLeast(1.5f)

        levels.forEachIndexed { index, raw ->
            val level = raw.coerceIn(0f, 1f)
            // Keep a hair of height so silence still shows a baseline.
            val half = (level * centerY).coerceAtLeast(1f)
            val x = index * slot + slot / 2f
            drawLine(
                color = color,
                start = Offset(x, centerY - half),
                end = Offset(x, centerY + half),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}
