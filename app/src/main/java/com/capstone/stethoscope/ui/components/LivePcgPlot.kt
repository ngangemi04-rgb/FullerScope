package com.capstone.stethoscope.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.capstone.stethoscope.audio.LivePcg
import kotlin.math.abs

/**
 * Scrolling live phonocardiogram: the last few seconds of signal, newest at the right.
 *
 * Drawn as a single line: one point per column, taking whichever of the column's min
 * and max is further from zero (with its sign). Using the extreme rather than, say, the
 * column's first sample keeps S1/S2 peaks at their true height however many samples
 * fall in a column. The line goes out as one `drawLines` call of plain segments rather
 * than a stroked Path: a Path that changes every frame is re-tessellated on the CPU each
 * time, which on a moto g7 was what pushed frames past 16 ms.
 *
 * [pcg] is a lambda read inside the draw block: a new frame then only invalidates this
 * Canvas's drawing, instead of recomposing every caller up to where the state is read.
 */
@Composable
fun LivePcgPlot(
    pcg: () -> LivePcg,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF00838F),
) {
    // Reused across frames so a 30 fps redraw allocates nothing.
    val paint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            // Not ROUND: round caps on ~480 antialiased segments took frames from
            // 14 ms to 22 ms on the moto g7. At 1.5 dp the joins look the same.
            strokeCap = android.graphics.Paint.Cap.BUTT
        }
    }
    // A plain holder, not Compose state: writing state inside draw would schedule
    // another draw every frame.
    val buf = remember { SegmentBuffer() }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(140.dp),
    ) {
        val centerY = size.height / 2f
        drawLine(
            color = color.copy(alpha = 0.3f),
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = 1f,
        )
        val frame = pcg()
        val n = frame.columns
        if (n == 0) return@Canvas

        val half = centerY * 0.95f
        val scale = half / frame.fullScale
        val dx = size.width / n
        if (buf.xy.size != (n - 1) * 4) buf.xy = FloatArray(((n - 1) * 4).coerceAtLeast(0))
        val segments = buf.xy
        var prevX = 0f
        var prevY = 0f
        for (i in 0 until n) {
            val hi = frame.maxs[i]
            val lo = frame.mins[i]
            val v = if (abs(hi) >= abs(lo)) hi else lo
            val x = i * dx + dx / 2f
            val y = (centerY - v * scale).coerceIn(0f, size.height)
            if (i > 0) {
                val k = (i - 1) * 4
                segments[k] = prevX; segments[k + 1] = prevY
                segments[k + 2] = x; segments[k + 3] = y
            }
            prevX = x
            prevY = y
        }
        paint.color = color.toArgb()
        paint.strokeWidth = 1.5.dp.toPx()
        drawIntoCanvas { it.nativeCanvas.drawLines(segments, paint) }
    }
}

private class SegmentBuffer {
    var xy = FloatArray(0)
}
