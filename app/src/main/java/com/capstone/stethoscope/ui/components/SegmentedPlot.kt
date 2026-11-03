package com.capstone.stethoscope.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.capstone.stethoscope.R
import com.capstone.stethoscope.analysis.HeartPhase
import com.capstone.stethoscope.analysis.HeartStates
import kotlin.math.abs

/**
 * Phase colours, shared with the PDF so the report and the screen read the same way.
 * ARGB, opaque; bands are drawn translucent over the plot background.
 *
 * S1 and S2 — the sounds themselves — get the two strong hues; the silent intervals
 * between them get muted ones, so the eye lands on the sounds first.
 */
val PHASE_ARGB: Map<HeartPhase, Int> = mapOf(
    HeartPhase.S1 to 0xFFD32F2F.toInt(),       // red
    HeartPhase.SYSTOLE to 0xFFFFB300.toInt(),  // amber
    HeartPhase.S2 to 0xFF1E88E5.toInt(),       // blue
    HeartPhase.DIASTOLE to 0xFF9E9E9E.toInt(), // grey
)

/** Band opacity behind the trace: strong enough to read, weak enough not to hide it. */
const val PHASE_BAND_ALPHA = 0.28f

private fun HeartPhase.color() = Color(PHASE_ARGB.getValue(this))

@Composable
fun HeartPhase.label(): String = stringResource(
    when (this) {
        HeartPhase.S1 -> R.string.phase_s1
        HeartPhase.SYSTOLE -> R.string.phase_systole
        HeartPhase.S2 -> R.string.phase_s2
        HeartPhase.DIASTOLE -> R.string.phase_diastole
    },
)

/**
 * The recording, with each stretch shaded by the cardiac phase the segmenter assigned it.
 *
 * [signal] is what the segmenter saw (2 kHz, 25–400 Hz, percentile-normalised), not the
 * raw capture: that is the trace the phases were decided on, and with the bandpass the
 * heart sounds stand out from hiss on a mic recording. Opens on the first
 * [initialWindowSec] seconds — a whole minute in 900 px makes 100 ms phases sub-pixel —
 * and pinch, pan and tap work as in testing mode; a tap reports the phase under it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SegmentedPlot(
    signal: DoubleArray,
    sampleRate: Int,
    states: HeartStates,
    modifier: Modifier = Modifier,
    initialWindowSec: Double = 5.0,
) {
    val duration = signal.size / sampleRate.toDouble()
    val zoom = rememberScopeZoom(signal, states)
    LaunchedEffect(zoom) {
        if (duration > initialWindowSec) zoom.zoomAbout((duration / initialWindowSec).toFloat(), 0f)
    }
    var cursor by remember(signal) { mutableStateOf<Double?>(null) }
    val runs = remember(states) { states.runs() }
    // Symmetric y-range from the trace's own extent; ±8 is the normaliser's clip.
    val yMax = remember(signal) { (signal.maxOfOrNull { abs(it) } ?: 1.0).coerceIn(0.5, 8.0) * 1.05 }

    val phaseLabels = HeartPhase.entries.associateWith { it.label() }
    val traceColor = MaterialTheme.colorScheme.onSurface
    val readout = cursor?.let { t ->
        val phase = states.phaseAt(t)?.let { phaseLabels.getValue(it) } ?: "—"
        "%.3f s · %s".format(t, phase)
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScopePlot(
            xMin = 0.0,
            xMax = duration,
            yMin = -yMax,
            yMax = yMax,
            xAxisLabel = "s",
            yAxisLabel = stringResource(R.string.segmented_y_axis),
            formatX = { "%.1f".format(it) },
            formatY = { "%.0f".format(it) },
            zoom = zoom,
            cursorX = cursor,
            onCursorChange = { cursor = it },
            height = 220.dp,
            readout = readout,
        ) { t ->
            // Bands first, so the trace is drawn over them.
            for ((phase, start, end) in runs) {
                if (end < t.xMin || start > t.xMax) continue
                val x0 = t.px(start.coerceAtLeast(t.xMin))
                val x1 = t.px(end.coerceAtMost(t.xMax))
                drawRect(
                    color = phase.color().copy(alpha = PHASE_BAND_ALPHA),
                    topLeft = Offset(x0, t.plot.top),
                    size = Size((x1 - x0).coerceAtLeast(1f), t.plot.height),
                )
            }
            drawWaveform(t, signal, sampleRate, traceColor)
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            HeartPhase.entries.forEach { phase ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(12.dp)
                            .background(phase.color(), RoundedCornerShape(2.dp)),
                    )
                    Text(
                        "  " + phaseLabels.getValue(phase),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}
