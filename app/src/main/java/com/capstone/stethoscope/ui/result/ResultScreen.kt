package com.capstone.stethoscope.ui.result

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.capstone.stethoscope.R
import com.capstone.stethoscope.analysis.MurmurOutcome
import com.capstone.stethoscope.data.model.Recording
import com.capstone.stethoscope.export.ExportNaming
import com.capstone.stethoscope.export.WhatsAppSharer
import com.capstone.stethoscope.ui.components.SegmentedPlot
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    recordingId: Long,
    onDone: () -> Unit,
    onBack: () -> Unit,
    vm: ResultViewModel = viewModel(factory = ResultViewModel.Factory),
) {
    val phase by vm.phase.collectAsState()
    val recording by vm.recording.collectAsState()
    val error by vm.error.collectAsState()
    val plot by vm.plot.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val exportedMsg = stringResource(R.string.export_success)
    val exportFailMsg = stringResource(R.string.export_failed)
    // Checked once per composition: drives the "install WhatsApp" hint below the button.
    val whatsAppInstalled = remember { WhatsAppSharer.installedPackage(context) != null }

    fun toast(ok: Boolean) {
        scope.launch { snackbar.showSnackbar(if (ok) exportedMsg else exportFailMsg) }
    }

    val savePdf = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri -> uri?.let { vm.exportReport(it, ::toast) } }

    val saveWav = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/x-wav")
    ) { uri -> uri?.let { vm.exportAudio(it, ::toast) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.result_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when (phase) {
                ResultViewModel.Phase.LOADING,
                ResultViewModel.Phase.ANALYZING -> {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.analyzing))
                }

                ResultViewModel.Phase.ERROR -> {
                    Text(error ?: stringResource(R.string.error_generic))
                    Button(onClick = vm::retry) { Text(stringResource(R.string.action_retry)) }
                }

                ResultViewModel.Phase.READY -> {
                    val rec = recording
                    if (rec != null) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(rec.label, style = MaterialTheme.typography.labelLarge)
                                rec.patientName?.let {
                                    Text(it, style = MaterialTheme.typography.titleMedium)
                                }
                                // Segmentation only, for now: the heart rate, rhythm and
                                // signal-quality numbers are still computed and stored on
                                // the recording, just not shown until that part of the
                                // analysis is ready to put in front of a clinician.
                                Text(
                                    stringResource(R.string.segmented_title),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                val p = plot
                                if (p != null) {
                                    SegmentedPlot(
                                        signal = p.signal,
                                        sampleRate = p.sampleRate,
                                        states = p.states,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Text(
                                        stringResource(R.string.segmented_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    // Testing readout: the raw numbers behind the plot, shown
                                    // even on a poor signal. English-only and plain on purpose.
                                    Text(
                                        testingReadout(rec),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                } else {
                                    Text(
                                        stringResource(R.string.segmented_unavailable),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                rec.murmurOutcome?.let { MurmurSection(it) }
                                Text(
                                    stringResource(R.string.segmented_disclaimer),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = { savePdf.launch("${ExportNaming.baseName(rec)}.pdf") },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.export_report)) }

                        OutlinedButton(
                            onClick = { saveWav.launch("${ExportNaming.baseName(rec)}.wav") },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.export_audio)) }

                        // Primary action: this is how a result actually reaches a
                        // doctor in the field. Both files go as WhatsApp documents so
                        // the WAV survives byte-exact (see WhatsAppSharer).
                        Button(
                            onClick = {
                                vm.prepareShare { uris ->
                                    if (uris.isEmpty()) { toast(false); return@prepareShare }
                                    val sent = WhatsAppSharer.share(
                                        context, uris, context.getString(R.string.send_whatsapp),
                                    )
                                    if (!sent) toast(false)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                            Text("  " + stringResource(R.string.send_whatsapp))
                        }
                        if (!whatsAppInstalled) {
                            Text(
                                stringResource(R.string.whatsapp_missing),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                vm.prepareShare { uris ->
                                    if (uris.isEmpty()) { toast(false); return@prepareShare }
                                    val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                        type = "*/*"
                                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(
                                        Intent.createChooser(share, context.getString(R.string.action_share))
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = null)
                            Text("  " + stringResource(R.string.action_share))
                        }

                        OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.action_done))
                        }
                    }
                }
            }
        }
    }
}

/**
 * The murmur classifier's answer, with its measured limits right underneath: a "no
 * murmur" from a model that misses four in ten must not read as an all-clear.
 */
@Composable
private fun MurmurSection(outcome: MurmurOutcome) {
    HorizontalDivider()
    Text(stringResource(R.string.murmur_title), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(
            when (outcome) {
                MurmurOutcome.DETECTED -> R.string.murmur_detected
                MurmurOutcome.NOT_DETECTED -> R.string.murmur_not_detected
                MurmurOutcome.UNABLE -> R.string.murmur_unable
            }
        ),
        style = MaterialTheme.typography.titleLarge,
        color = if (outcome == MurmurOutcome.DETECTED) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurface,
    )
    Text(
        stringResource(
            if (outcome == MurmurOutcome.UNABLE) R.string.murmur_unable_hint else R.string.murmur_caveat
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Plain-text dump of the stored analysis numbers, for testing. */
private fun testingReadout(rec: Recording): String = buildString {
    fun fmt(v: Float?, pattern: String) = v?.let { pattern.format(it) } ?: "—"
    appendLine("Heart rate:     ${fmt(rec.heartRateBpm, "%.1f bpm")}")
    appendLine("Rhythm:         ${rec.rhythm?.name?.lowercase() ?: "—"}")
    appendLine("Variability CV: ${fmt(rec.bpmVariability, "%.3f")}")
    appendLine("Beats (S1):     ${rec.beatCount ?: "—"}")
    append("Signal quality: ${fmt(rec.signalConfidence?.times(100), "%.0f%%")}")
    appendLine(" (${rec.signalQuality?.name?.lowercase() ?: "—"})")
    append("Murmur score:   ${fmt(rec.murmurScore, "%.3f")} (cut-off 0.428)")
}
