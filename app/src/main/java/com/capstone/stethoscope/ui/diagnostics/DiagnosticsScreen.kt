package com.capstone.stethoscope.ui.diagnostics

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.capstone.stethoscope.R
import com.capstone.stethoscope.StethoscopeApp
import com.capstone.stethoscope.analysis.segmenter.HeartSoundSegmenter
import com.capstone.stethoscope.audio.UsbAudioDiagnostics
import com.capstone.stethoscope.audio.usb.FirmwareManager
import com.capstone.stethoscope.audio.usb.UsbStethoscope

/**
 * Read-only dump of what the phone sees on the USB port, plus a plain-language
 * verdict. Intended to be opened with the stethoscope hardware plugged in and the
 * output shared with whoever is building it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as StethoscopeApp
    // Re-run on demand rather than continuously: the user plugs the hardware in,
    // then taps Refresh.
    var report by remember { mutableStateOf(UsbAudioDiagnostics.report(context)) }
    val signalStats by app.container.recorder.lastSignalStats.collectAsState()
    val transport by app.container.recorder.transportDiagnostics.collectAsState()
    val actualRate by app.container.recorder.actualSampleRate.collectAsState()
    val requestedRate = app.container.settings.sampleRate
    val usbState by app.container.usbStethoscope.state.collectAsState()
    val sourceLabel by app.container.recorder.sourceLabel.collectAsState()
    val firmwareState by app.container.firmware.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostics_title)) },
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
                stringResource(R.string.diagnostics_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // The stethoscope is a raw USB device, so it needs explicit per-device
            // consent before the app can open it. Surfaced here because a missing grant
            // otherwise looks identical to broken hardware.
            if (usbState is UsbStethoscope.State.NeedsPermission) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(R.string.usb_permission_needed),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = { app.container.usbStethoscope.requestPermission() }) {
                            Text(stringResource(R.string.usb_permission_grant))
                        }
                    }
                }
            }

            // Firmware is never touched automatically. Refresh reads the version (no
            // changes); the button below is the only thing that uploads.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Firmware", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = when (val f = firmwareState) {
                            is FirmwareManager.State.Idle ->
                                if (usbState is UsbStethoscope.State.Ready) {
                                    stringResource(R.string.firmware_idle_attached)
                                } else {
                                    "No stethoscope attached."
                                }
                            is FirmwareManager.State.Checking ->
                                stringResource(R.string.firmware_checking)
                            is FirmwareManager.State.UpToDate ->
                                stringResource(R.string.firmware_up_to_date, f.version) +
                                    (f.sampleRate?.let { "\nboard reports $it Hz" } ?: "")
                            is FirmwareManager.State.Outdated ->
                                stringResource(
                                    R.string.firmware_outdated,
                                    f.found ?: "none", f.bundled,
                                )
                            is FirmwareManager.State.Installing ->
                                stringResource(R.string.firmware_installing, f.version, f.percent)
                            is FirmwareManager.State.Installed ->
                                stringResource(R.string.firmware_installed, f.version)
                            is FirmwareManager.State.Failed -> f.reason
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (firmwareState is FirmwareManager.State.Failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    OutlinedButton(
                        onClick = { app.container.firmware.reinstall() },
                        enabled = usbState is UsbStethoscope.State.Ready &&
                            firmwareState !is FirmwareManager.State.Checking &&
                            firmwareState !is FirmwareManager.State.Installing,
                    ) {
                        Text(stringResource(R.string.firmware_reinstall))
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    app.container.usbStethoscope.refresh()
                    app.container.firmware.recheck()
                    report = UsbAudioDiagnostics.report(context)
                }) {
                    Text(stringResource(R.string.diagnostics_refresh))
                }
                OutlinedButton(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "StethoScope hardware diagnostics")
                            putExtra(Intent.EXTRA_TEXT, report)
                        }
                        context.startActivity(
                            Intent.createChooser(send, context.getString(R.string.diagnostics_share))
                        )
                    },
                ) { Text(stringResource(R.string.diagnostics_share)) }
            }

            // Only meaningful once something has been recorded this session, but it is
            // the clearest evidence of a 12-bit source: a gain of x16 means the samples
            // were arriving right-aligned in their 16-bit words.
            if (signalStats.sampleCount > 0) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("Last recording", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${sourceLabel.ifBlank { "unknown source" }} · $actualRate Hz\n" +
                                signalStats.describe() +
                                (transport?.let { "\n$it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        if (actualRate != requestedRate) {
                            Text(
                                "Requested $requestedRate Hz but the phone would not open it, " +
                                    "so $actualRate Hz was used and the audio is resampled. " +
                                    "Tell the algorithm team.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            // The most recent segmentation's numbers, for comparing a recording against
            // the algorithm team's MATLAB (segmentHeartSound.m).
            (app.container.classifier as? HeartSoundSegmenter)?.lastRun?.let { e ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("Last analysis", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = e.describe(),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            softWrap = false,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                // Monospace + horizontal scroll: the report is column-aligned and
                // wrapping it would make the tables unreadable.
                Text(
                    text = report,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    softWrap = false,
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp),
                )
            }
        }
    }
}
