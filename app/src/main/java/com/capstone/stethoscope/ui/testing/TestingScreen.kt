package com.capstone.stethoscope.ui.testing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.capstone.stethoscope.R
import com.capstone.stethoscope.analysis.pcg.PcgSpec
import com.capstone.stethoscope.ui.components.ScopeGuide
import com.capstone.stethoscope.ui.components.ScopePlot
import com.capstone.stethoscope.ui.components.drawSpectrum
import com.capstone.stethoscope.ui.components.drawWaveform
import com.capstone.stethoscope.ui.components.rememberScopeZoom
import kotlin.math.roundToInt

/**
 * The engineering scope — deliberately not a clinical screen.
 *
 * ## What this screen is for, and what it is not
 *
 * It verifies the **digital** signal chain: does the decimation alias, and does the signal
 * the algorithm receives match the signal that went in. Those are questions the app can
 * answer about itself, because it holds both ends of every stage.
 *
 * Since the app stopped filtering (2026-09-23) the second question is the whole point.
 * The algorithm team is conditioning the signal on their side on the understanding that
 * nobody touched it first; this screen is where that claim is checked.
 *
 * It is **not** a hardware instrument. The gain of the analog front end, the sensitivity
 * of the transducer, how many millivolts a given chest produces — none of that is
 * recoverable from a recording, because a capture shows an output whose input nobody
 * measured. Characterising that needs a known stimulus and a signal generator, and any
 * number this screen printed about it would be a guess wearing units. The one exception
 * is the response trace, which divides two stages the app owns and so is a real gain.
 *
 * ## Traces
 *
 * Two signal stages, each with a time plot and a spectrum:
 *  - **Raw capture** at whatever rate was negotiated,
 *  - **PCG (2 kHz)**, byte-for-byte what the classifier is handed and what the exported
 *    report draws.
 *
 * Plus two views that answer different questions about the same samples:
 *  - **Voltage**: the raw capture as absolute volts against the 0–3.3 V rails, for
 *    checking that the analog front end is biased at mid-rail and using the range. The
 *    only view here about the hardware rather than the maths, and only available for a
 *    USB capture, whose count-to-volt conversion is exact.
 *  - **Chain response**: the measured transfer function from raw to PCG, against the flat
 *    0 dB line the app is now specified to have. Anything other than a straight line
 *    below the 1 kHz fold means the app is altering the recording.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TestingScreen(
    onBack: () -> Unit,
    vm: TestingViewModel = viewModel(factory = TestingViewModel.Factory),
) {
    val phase by vm.phase.collectAsState()
    val capture by vm.capture.collectAsState()
    val stage by vm.stage.collectAsState()
    val error by vm.error.collectAsState()
    val elapsedMs by vm.elapsedMs.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            vm.consumeError()
        }
    }
    // Leaving mid-capture must release the device, or the next Listen fails on the lock.
    DisposableEffect(Unit) { onDispose { vm.stopCapture() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.testing_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.testing_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SourceControls(
                phase = phase,
                elapsedMs = elapsedMs,
                requestedRate = vm.requestedRate,
                onCapture = vm::captureNow,
                onStop = vm::stopCapture,
                onLoadLatest = vm::loadLatestRecording,
            )

            when (phase) {
                TestingViewModel.Phase.EMPTY ->
                    Text(
                        stringResource(R.string.testing_empty),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                TestingViewModel.Phase.CAPTURING, TestingViewModel.Phase.ANALYZING ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            if (phase == TestingViewModel.Phase.CAPTURING) {
                                stringResource(R.string.testing_capturing)
                            } else {
                                stringResource(R.string.testing_analyzing)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                else -> Unit
            }

            capture?.let { c ->
                HorizontalDivider()
                CaptureHeader(c)

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TestingViewModel.Stage.entries.forEach { s ->
                        FilterChip(
                            selected = stage == s,
                            onClick = { vm.selectStage(s) },
                            label = { Text(s.label) },
                        )
                    }
                }

                when (stage) {
                    TestingViewModel.Stage.RESPONSE ->
                        c.response?.let { ResponsePlot(it); ResponseFacts(it) }
                            ?: Text(
                                stringResource(R.string.testing_response_unavailable),
                                style = MaterialTheme.typography.bodyMedium,
                            )

                    // Voltage reads the raw capture, not a stage of its own: it is the
                    // same samples asked a different question, and decimating first
                    // would hide exactly the brief excursions that reveal clipping.
                    TestingViewModel.Stage.VOLTAGE ->
                        c.traces[TestingViewModel.Stage.RAW]?.let { trace ->
                            VoltagePlot(trace)
                            VoltageFacts(trace)
                        }

                    else -> c.traces[stage]?.let { trace ->
                        TimePlot(trace)
                        SpectrumPlot(trace)
                        TraceFacts(trace)
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceControls(
    phase: TestingViewModel.Phase,
    elapsedMs: Long,
    requestedRate: Int,
    onCapture: (Int) -> Unit,
    onStop: () -> Unit,
    onLoadLatest: () -> Unit,
) {
    val busy = phase == TestingViewModel.Phase.CAPTURING ||
        phase == TestingViewModel.Phase.ANALYZING

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (phase == TestingViewModel.Phase.CAPTURING) {
                Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(
                            R.string.testing_stop_at,
                            (elapsedMs / 1000).toInt(),
                        )
                    )
                }
            } else {
                Button(onClick = { onCapture(SHORT_CAPTURE_SECONDS) }, enabled = !busy) {
                    Text(stringResource(R.string.testing_capture, SHORT_CAPTURE_SECONDS))
                }
                OutlinedButton(onClick = onLoadLatest, enabled = !busy) {
                    Text(stringResource(R.string.testing_load_last))
                }
            }
        }
        Text(
            stringResource(R.string.testing_requested_rate, requestedRate),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CaptureHeader(capture: TestingViewModel.Capture) {
    Column {
        Text(
            "${capture.sourceLabel} · captured at ${capture.capturedRate} Hz",
            style = MaterialTheme.typography.titleSmall,
        )
        if (capture.capturedRate != PcgSpec.CAPTURE_SAMPLE_RATE) {
            Text(
                stringResource(
                    R.string.testing_rate_mismatch,
                    capture.capturedRate,
                    PcgSpec.CAPTURE_SAMPLE_RATE,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        capture.truncatedFrom?.let {
            Text(
                stringResource(R.string.testing_truncated, it.roundToInt()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TimePlot(trace: TestingViewModel.Trace) {
    // Keyed on the trace so switching stages starts at full scale rather than inheriting
    // a zoom window measured in the previous stage's units.
    val zoom = rememberScopeZoom(trace)
    var cursor by remember(trace) { mutableStateOf<Double?>(null) }
    val color = MaterialTheme.colorScheme.primary

    val bound = trace.amplitudeBound
    val sampleAt = cursor?.let { t ->
        val i = (t * trace.sampleRate).roundToInt()
        if (i in trace.samples.indices) trace.samples[i] else null
    }

    // Millivolts wherever the samples are traceable to the ADC, counts where they are
    // not — so the axis never claims a voltage the signal does not have.
    val cal = trace.calibration
    ScopePlot(
        xMin = 0.0,
        xMax = trace.durationSeconds,
        yMin = -bound,
        yMax = bound,
        xAxisLabel = "s",
        yAxisLabel = if (cal != null) "mV" else "counts",
        formatX = ::formatSeconds,
        formatY = { v ->
            if (cal != null) "%.1f".format(cal.millivolts(v)) else v.roundToInt().toString()
        },
        zoom = zoom,
        cursorX = cursor,
        onCursorChange = { cursor = it },
        height = 200.dp,
        readout = cursor?.let { t ->
            val value = when {
                sampleAt == null -> "—"
                cal != null -> "%.2f mV".format(cal.millivolts(sampleAt))
                else -> "${sampleAt.roundToInt()} counts"
            }
            "t = ${formatSeconds(t)} s · sample ${(t * trace.sampleRate).roundToInt()} · $value"
        },
    ) { t ->
        drawWaveform(t, trace.samples, trace.sampleRate, color)
    }
}

@Composable
private fun SpectrumPlot(trace: TestingViewModel.Trace) {
    val zoom = rememberScopeZoom(trace)
    var cursor by remember(trace) { mutableStateOf<Double?>(null) }
    val color = MaterialTheme.colorScheme.tertiary
    val cornerColor = MaterialTheme.colorScheme.error
    val nyquistColor = MaterialTheme.colorScheme.secondary

    val spectrum = trace.spectrum
    val guides = buildList {
        // The fold frequency of the 2 kHz output. With no lowpass of our own left, this
        // is the only frequency the app is allowed to care about: content above it cannot
        // survive decimation, and content below it must arrive untouched.
        val outputNyquist = PcgSpec.SAMPLE_RATE / 2.0
        if (outputNyquist <= spectrum.nyquistHz && trace.sampleRate != PcgSpec.SAMPLE_RATE) {
            add(ScopeGuide(outputNyquist, "1 kHz fold", nyquistColor))
        }
        // Top of the heart-sound band, for orientation only — nothing filters here.
        if (HEART_BAND_TOP_HZ <= spectrum.nyquistHz) {
            add(ScopeGuide(HEART_BAND_TOP_HZ, "400 Hz", cornerColor))
        }
    }

    ScopePlot(
        xMin = 0.0,
        xMax = spectrum.nyquistHz,
        yMin = SPECTRUM_FLOOR_DB,
        yMax = 5.0,
        xAxisLabel = "Hz",
        yAxisLabel = "dB",
        formatX = { hz -> if (hz >= 1000) "%.1fk".format(hz / 1000) else hz.roundToInt().toString() },
        formatY = { db -> db.roundToInt().toString() },
        zoom = zoom,
        cursorX = cursor,
        onCursorChange = { cursor = it },
        guides = guides,
        height = 200.dp,
        readout = cursor?.let { hz -> "%.1f Hz · %.1f dB".format(hz, spectrum.dbAt(hz)) },
    ) { t ->
        drawSpectrum(t, spectrum::dbAt, color)
    }
}

/**
 * The capture as absolute volts at the ADC pin, drawn against the supply rails.
 *
 * Every other plot on this screen is about the *shape* of the signal. This one is about
 * whether the analog front end is usable at all, which is a different question and the
 * one that goes wrong first on new hardware: a bias that has drifted off mid-rail loses
 * half the swing to clipping, and a signal that never leaves the middle of the range is
 * being digitised with a fraction of the ADC's bits.
 *
 * The axis is the full 0–3.3 V rail, fixed, never auto-ranged. Auto-ranging would make
 * a 40 mV signal fill the plot and look healthy, which is the exact misreading this view
 * exists to prevent. There is deliberately no pass/fail band: nobody has yet measured
 * what amplitude a real chest produces through this front end, and drawing a threshold
 * we made up would be worse than drawing none.
 */
@Composable
private fun VoltagePlot(trace: TestingViewModel.Trace) {
    val cal = trace.calibration
    if (cal == null) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.testing_voltage_uncalibrated),
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val zoom = rememberScopeZoom(trace)
    var cursor by remember(trace) { mutableStateOf<Double?>(null) }
    val color = MaterialTheme.colorScheme.primary
    val railColor = MaterialTheme.colorScheme.error
    val midColor = MaterialTheme.colorScheme.secondary

    // Volts once, not per frame: a 20-second 16 kHz capture is 320 000 samples and
    // remapping them on every recomposition would make the plot stutter under a pinch.
    val volts = remember(trace) {
        DoubleArray(trace.samples.size) { cal.volts(trace.samples[it]) }
    }

    ScopePlot(
        xMin = 0.0,
        xMax = trace.durationSeconds.coerceAtLeast(0.001),
        yMin = 0.0,
        yMax = cal.supplyVolts,
        xAxisLabel = "s",
        yAxisLabel = "V",
        formatX = { t -> formatSeconds(t) },
        formatY = { v -> "%.2f".format(v) },
        zoom = zoom,
        cursorX = cursor,
        onCursorChange = { cursor = it },
        guides = emptyList(),
        height = 220.dp,
        readout = cursor?.let { t ->
            val i = (t * trace.sampleRate).roundToInt().coerceIn(0, volts.size - 1)
            "t = ${formatSeconds(t)} s · %.3f V".format(volts[i])
        },
    ) { t ->
        // Rails and mid-rail first, so the trace draws over them.
        for ((v, c) in listOf(
            0.0 to railColor,
            cal.midRailVolts to midColor,
            cal.supplyVolts to railColor,
        )) {
            drawLine(
                color = c.copy(alpha = 0.5f),
                start = Offset(t.plot.left, t.py(v)),
                end = Offset(t.plot.right, t.py(v)),
                strokeWidth = 1f,
            )
        }
        drawWaveform(t, volts, trace.sampleRate, color)
    }
}

/** Bias, swing and headroom — the numbers that say whether the front end is set up. */
@Composable
private fun VoltageFacts(trace: TestingViewModel.Trace) {
    val cal = trace.calibration ?: return
    val samples = trace.samples
    if (samples.isEmpty()) return

    val mean = samples.average()
    var lo = Double.MAX_VALUE
    var hi = -Double.MAX_VALUE
    var clipped = 0
    for (v in samples) {
        if (v < lo) lo = v
        if (v > hi) hi = v
        // One LSB of the 12-bit ADC is 16 counts after the left shift, so "on the rail"
        // means within one ADC step of it rather than exactly at the extreme.
        if (v >= 32_767.0 - 16 || v <= -32_768.0 + 16) clipped++
    }

    val biasV = cal.volts(mean)
    val loV = cal.volts(lo)
    val hiV = cal.volts(hi)
    val swingV = hiV - loV
    val headroom = minOf(cal.supplyVolts - hiV, loV)
    val usedPercent = 100.0 * swingV / cal.supplyVolts
    val biasOffsetMv = (biasV - cal.midRailVolts) * 1000.0

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Voltage at the ADC pin", style = MaterialTheme.typography.titleSmall)
            Text(
                buildString {
                    appendLine("supply     %.2f V  (mid-rail %.3f V)".format(cal.supplyVolts, cal.midRailVolts))
                    appendLine("bias       %.3f V  (%+.0f mV off mid-rail)".format(biasV, biasOffsetMv))
                    appendLine("min / max  %.3f V / %.3f V".format(loV, hiV))
                    appendLine("swing      %.3f V  (%.1f%% of the rail)".format(swingV, usedPercent))
                    appendLine("headroom   %.3f V to the nearer rail".format(headroom))
                    append("clipped    $clipped samples")
                }.trimEnd(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            val warning = when {
                clipped > 0 ->
                    stringResource(R.string.testing_voltage_clipping, clipped)
                kotlin.math.abs(biasOffsetMv) > 250 ->
                    stringResource(R.string.testing_voltage_bias, biasOffsetMv.roundToInt())
                usedPercent < 2.0 ->
                    stringResource(R.string.testing_voltage_quiet, usedPercent)
                else -> null
            }
            if (warning != null) {
                Text(
                    warning,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                stringResource(R.string.testing_voltage_scope),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * What the digital chain did to the signal, against the flat line it is supposed to be.
 *
 * Unlike every other plot on this screen, this one is a genuine gain: both ends of the
 * chain are in hand, so the capture's own spectrum divides out. Nothing about the
 * hardware can be read off it — that is the point, and it is why this is the plot to
 * trust about whether the app is leaving the recording alone.
 *
 * A flat trace on 0 dB is a pass. A trace that sags near 1 kHz is the decimation's
 * anti-alias filter, which is expected and unavoidable. A trace that departs from 0 dB
 * anywhere in the heart-sound band is a bug.
 */
@Composable
private fun ResponsePlot(response: TestingViewModel.Response) {
    val zoom = rememberScopeZoom(response)
    var cursor by remember(response) { mutableStateOf<Double?>(null) }
    val measuredColor = MaterialTheme.colorScheme.primary
    val idealColor = MaterialTheme.colorScheme.onSurfaceVariant
    val cornerColor = MaterialTheme.colorScheme.error

    val guides = buildList {
        if (HEART_BAND_TOP_HZ <= response.nyquistHz) {
            add(ScopeGuide(HEART_BAND_TOP_HZ, "400 Hz", cornerColor))
        }
    }

    ScopePlot(
        xMin = 0.0,
        xMax = response.nyquistHz,
        yMin = RESPONSE_FLOOR_DB,
        yMax = 6.0,
        xAxisLabel = "Hz",
        yAxisLabel = "dB",
        formatX = { hz -> if (hz >= 1000) "%.1fk".format(hz / 1000) else hz.roundToInt().toString() },
        formatY = { db -> db.roundToInt().toString() },
        zoom = zoom,
        cursorX = cursor,
        onCursorChange = { cursor = it },
        guides = guides,
        height = 220.dp,
        readout = cursor?.let { hz ->
            "%.1f Hz · measured %.1f dB · spec %.1f dB".format(
                hz,
                response.measuredDbAt(hz),
                response.idealDbAt(hz),
            )
        },
    ) { t ->
        drawSpectrum(t, response::idealDbAt, idealColor, dashed = true)
        drawSpectrum(t, response::measuredDbAt, measuredColor)
    }
}

/** Measured against spec at frequencies spread across the band that has to survive. */
@Composable
private fun ResponseFacts(response: TestingViewModel.Response) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Chain response", style = MaterialTheme.typography.titleSmall)
            Text(
                buildString {
                    appendLine("Hz        measured     spec    error")
                    for (hz in listOf(50.0, 100.0, 200.0, 400.0, 600.0, 800.0)) {
                        if (hz > response.nyquistHz) continue
                        appendLine(
                            "%-9s %7.1f  %7.1f  %+6.1f".format(
                                hz.roundToInt().toString(),
                                response.measuredDbAt(hz),
                                response.idealDbAt(hz),
                                response.errorDbAt(hz),
                            )
                        )
                    }
                }.trimEnd(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                stringResource(R.string.testing_response_intro),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.testing_response_scope),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The numbers worth reading off rather than eyeballing: rate, length, and how the
 * spectrum sits relative to the middle of the heart-sound band.
 */
@Composable
private fun TraceFacts(trace: TestingViewModel.Trace) {
    val s = trace.spectrum
    val cal = trace.calibration
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("Measured response", style = MaterialTheme.typography.titleSmall)
            Text(
                buildString {
                    appendLine("rate      ${trace.sampleRate} Hz")
                    appendLine("samples   ${trace.samples.size}")
                    appendLine("bin       %.2f Hz".format(s.binHz))
                    if (cal != null) {
                        appendLine("peak      %.2f mV".format(cal.millivolts(trace.peakCounts)))
                    }
                    for (hz in listOf(100.0, 300.0, 600.0, 900.0, 1_200.0)) {
                        if (hz > s.nyquistHz) continue
                        // Relative to 100 Hz: what matters is the shape of the capture
                        // against the band S1 and S2 live in, since nothing in the app
                        // imposes a shape of its own any more.
                        appendLine(
                            "%-9s %+6.1f dB".format(
                                "${hz.roundToInt()} Hz",
                                s.dbAt(hz) - s.dbAt(100.0),
                            )
                        )
                    }
                }.trimEnd(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                stringResource(R.string.testing_relative_db),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Bottom of the spectrum's y-axis. Well above [Spectrum.FLOOR_DB]: showing 120 dB of
 * range compresses the 0 to −60 dB region everything interesting lives in into a
 * fraction of the plot's height.
 */
private const val SPECTRUM_FLOOR_DB = -90.0

/**
 * Floor for the response plot. Deeper than the spectra's, because a transfer function
 * keeps being meaningful far below where a single spectrum has run out of signal: the
 * filter attenuates the noise floor along with everything else, so the ratio stays valid
 * long after both traces have gone quiet.
 */
private const val RESPONSE_FLOOR_DB = -120.0

/**
 * Top of the heart-sound band, drawn as a guide for orientation only.
 *
 * Nothing in the app filters here any more — this is where the *classifier's* own causal
 * 25–400 Hz Butterworth rolls off, inside `HeartSoundFeatures`. It is on the plot so the
 * band that matters is visible, not because the app does anything at it.
 */
private const val HEART_BAND_TOP_HZ = 400.0

/** Seconds with enough decimals to stay readable when zoomed into a few milliseconds. */
private fun formatSeconds(t: Double): String = when {
    t >= 10 -> "%.1f".format(t)
    t >= 1 -> "%.2f".format(t)
    else -> "%.3f".format(t)
}

/** Long enough for several cardiac cycles, short enough to stay responsive. */
private const val SHORT_CAPTURE_SECONDS = 5
