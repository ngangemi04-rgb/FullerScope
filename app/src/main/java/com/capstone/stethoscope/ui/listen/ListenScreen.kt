package com.capstone.stethoscope.ui.listen

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.capstone.stethoscope.R
import com.capstone.stethoscope.audio.usb.FirmwareManager
import com.capstone.stethoscope.audio.usb.UsbStethoscope
import com.capstone.stethoscope.ui.components.StopWhenBackgrounded
import com.capstone.stethoscope.ui.components.Waveform
import com.capstone.stethoscope.ui.theme.GreenOk

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ListenScreen(
    onRecordingSaved: (Long) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTesting: () -> Unit,
    vm: ListenViewModel = viewModel(factory = ListenViewModel.Factory),
) {
    val context = LocalContext.current
    val active by vm.listening.collectAsState()
    val waveform by vm.waveform.collectAsState()
    val stethoscope by vm.stethoscope.collectAsState()
    val patient by vm.patient.collectAsState()
    val monitorError by vm.error.collectAsState()
    val recordState by vm.recordState.collectAsState()
    val elapsedMs by vm.elapsedMs.collectAsState()
    val firmware by vm.firmware.collectAsState()
    val firmwareBusy by vm.firmwareBusy.collectAsState()
    val testingMode by vm.testingMode.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }

    val recording = recordState == RecordState.RECORDING
    val saving = recordState == RecordState.SAVING
    val busy = recording || saving

    // The firmware check claims the same single-use CDC interface capture needs, so the
    // capture buttons wait it out rather than failing with a lock error. Navigation stays
    // enabled throughout — there is no reason to trap the user on this screen for it.
    val captureBlocked = busy || firmwareBusy || firmwareBusy

    LaunchedEffect(Unit) { vm.refreshUsb() }

    // Prompt for USB access up front rather than letting listening fall back to the
    // phone's own microphone without saying so.
    LaunchedEffect(stethoscope) {
        if (stethoscope is UsbStethoscope.State.NeedsPermission) vm.requestUsbPermission()
    }

    LaunchedEffect(monitorError) {
        monitorError?.let {
            snackbarHost.showSnackbar(it)
            vm.consumeError()
        }
    }

    // Release the capture device when this screen goes away or the app backgrounds.
    DisposableEffect(Unit) { onDispose { vm.stopAll() } }
    StopWhenBackgrounded { vm.stopAll() }

    // The USB path never touches AudioRecord, so the mic permission only gates the
    // fallback. Remembering which action asked for it keeps one launcher serving both.
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) pendingAction?.invoke()
        pendingAction = null
    }

    fun withMic(action: () -> Unit) {
        if (vm.willUseStethoscope) {
            action()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            pendingAction = action
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun toggleListening() {
        if (active) vm.stopListening() else withMic { vm.startListening() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.listen_title)) },
                actions = {
                    if (testingMode) {
                        IconButton(onClick = onOpenTesting, enabled = !busy) {
                            Icon(
                                Icons.Filled.Science,
                                contentDescription = stringResource(R.string.testing_title),
                            )
                        }
                    }
                    IconButton(onClick = onOpenLibrary, enabled = !busy) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = stringResource(R.string.tab_library))
                    }
                    IconButton(onClick = onOpenSettings, enabled = !busy) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.action_settings))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Pinned above the scrolling content so the countdown stays visible however
            // far down the patient form the clinician has scrolled.
            AnimatedVisibility(visible = busy) {
                RecordingBar(
                    elapsedMs = elapsedMs,
                    totalMs = vm.maxDurationMs,
                    saving = saving,
                    onStop = vm::stopRecording,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
            Text(
                stringResource(R.string.listen_intro),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )

            DeviceStatus(
                stethoscope = stethoscope,
                firmware = firmware,
                onGrant = vm::requestUsbPermission,
            )

            PatientInfoCard(
                name = patient.name,
                id = patient.id,
                age = patient.ageYears?.toString() ?: "",
                sex = patient.sex,
                notes = patient.notes,
                onName = { v -> vm.updatePatient { it.copy(name = v) } },
                onId = { v -> vm.updatePatient { it.copy(id = v) } },
                onAge = { v -> vm.updatePatient { it.copy(ageYears = v.toIntOrNull()) } },
                onSex = { v -> vm.updatePatient { it.copy(sex = v) } },
                onNotes = { v -> vm.updatePatient { it.copy(notes = v) } },
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Waveform(
                        levels = waveform,
                        color = when {
                            recording -> MaterialTheme.colorScheme.error
                            active -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                    )
                    Text(
                        text = when {
                            saving -> stringResource(R.string.saving)
                            recording -> stringResource(R.string.recording_in_progress)
                            active -> stringResource(R.string.listening_active)
                            else -> stringResource(R.string.listen_idle_hint)
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            if (firmwareBusy) {
                Text(
                    stringResource(R.string.firmware_wait),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(
                onClick = { toggleListening() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !captureBlocked,
                colors = if (active) ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ) else ButtonDefaults.buttonColors(),
            ) {
                Icon(if (active) Icons.Filled.Stop else Icons.Filled.GraphicEq, contentDescription = null)
                Spacer(Modifier.height(0.dp))
                Text(
                    "  " + if (active) stringResource(R.string.btn_stop_listening)
                    else stringResource(R.string.btn_start_listening)
                )
            }

            // Recording happens right here rather than on another screen, so the device
            // banner and patient details stay in view throughout.
            if (recording) {
                Button(
                    onClick = vm::stopRecording,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Text("  " + stringResource(R.string.btn_stop))
                }
            } else {
                OutlinedButton(
                    onClick = { withMic { vm.startRecording(onRecordingSaved) } },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving && !firmwareBusy,
                ) {
                    Icon(Icons.Filled.FiberManualRecord, contentDescription = null)
                    Text("  " + stringResource(R.string.btn_record))
                }
            }
            }
        }
    }
}

/**
 * The pinned countdown shown while a recording is running.
 *
 * Replaces what used to be a whole separate screen. Recording is a 60-second wait during
 * which the clinician is holding a stethoscope against a patient, so the only thing that
 * needs to change on screen is a timer and a way to stop.
 */
@Composable
private fun RecordingBar(
    elapsedMs: Long,
    totalMs: Long,
    saving: Boolean,
    onStop: () -> Unit,
) {
    val fraction = if (totalMs > 0) (elapsedMs.toFloat() / totalMs).coerceIn(0f, 1f) else 0f
    val remainingSec = ((totalMs - elapsedMs).coerceAtLeast(0L) / 1000).toInt()

    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Filled.FiberManualRecord,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = if (saving) stringResource(R.string.saving)
                    else stringResource(R.string.recording_in_progress),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "%d:%02d".format(remainingSec / 60, remainingSec % 60),
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                if (!saving) {
                    IconButton(onClick = onStop) {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = stringResource(R.string.btn_stop),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun DeviceStatus(
    stethoscope: UsbStethoscope.State,
    firmware: FirmwareManager.State,
    onGrant: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val ready = stethoscope is UsbStethoscope.State.Ready
        StatusPill(
            ok = ready,
            okText = stringResource(R.string.usb_connected),
            notText = if (stethoscope is UsbStethoscope.State.NeedsPermission) {
                stringResource(R.string.usb_permission_needed)
            } else {
                stringResource(R.string.usb_not_connected)
            },
        )
        if (stethoscope is UsbStethoscope.State.NeedsPermission) {
            OutlinedButton(onClick = onGrant, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.usb_permission_grant))
            }
        }
        FirmwareStatus(firmware)
    }
}

/**
 * Firmware check/flash progress.
 *
 * Shown on the clinical screen rather than buried in diagnostics because it is the only
 * thing that explains why Record is briefly unavailable after plugging the stethoscope in,
 * and because a failed update is something the user needs to know *before* recording a
 * patient with firmware that may not match the app.
 */
@Composable
private fun FirmwareStatus(state: FirmwareManager.State) {
    when (state) {
        is FirmwareManager.State.Idle -> Unit

        is FirmwareManager.State.Checking ->
            InfoPill(stringResource(R.string.firmware_checking))

        is FirmwareManager.State.UpToDate ->
            StatusPill(
                ok = true,
                okText = stringResource(R.string.firmware_up_to_date, state.version),
                notText = "",
            )

        is FirmwareManager.State.Outdated ->
            InfoPill(stringResource(R.string.firmware_outdated, state.found ?: "none", state.bundled))

        is FirmwareManager.State.Installing -> {
            InfoPill(
                stringResource(R.string.firmware_installing, state.version, state.percent)
            )
            LinearProgressIndicator(
                progress = { state.percent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        is FirmwareManager.State.Installed ->
            StatusPill(
                ok = true,
                okText = stringResource(R.string.firmware_installed, state.version),
                notText = "",
            )

        // A recoverable failure is usually "the device was busy" and will be retried on
        // the next attach, so it gets a neutral note rather than a red alarm. Only an
        // unrecoverable one — a build with no firmware bundled — is worth alarming about.
        is FirmwareManager.State.Failed ->
            if (state.recoverable) {
                InfoPill(state.reason)
            } else {
                StatusPill(ok = false, okText = "", notText = state.reason)
            }
    }
}

@Composable
private fun InfoPill(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
        )
    }
}

@Composable
private fun StatusPill(ok: Boolean, okText: String, notText: String) {
    val color = if (ok) GreenOk else MaterialTheme.colorScheme.error
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = if (ok) okText else notText,
            color = color,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PatientInfoCard(
    name: String,
    id: String,
    age: String,
    sex: String?,
    notes: String,
    onName: (String) -> Unit,
    onId: (String) -> Unit,
    onAge: (String) -> Unit,
    onSex: (String?) -> Unit,
    onNotes: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.patient_info),
                    style = MaterialTheme.typography.titleMedium,
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = onName,
                        label = { Text(stringResource(R.string.patient_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = id,
                            onValueChange = onId,
                            label = { Text(stringResource(R.string.patient_id)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = age,
                            onValueChange = { v -> onAge(v.filter { it.isDigit() }.take(3)) },
                            label = { Text(stringResource(R.string.patient_age)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(stringResource(R.string.patient_sex), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "Male" to stringResource(R.string.sex_male),
                            "Female" to stringResource(R.string.sex_female),
                            "Other" to stringResource(R.string.sex_other),
                        ).forEach { (value, label) ->
                            FilterChip(
                                selected = sex == value,
                                onClick = { onSex(if (sex == value) null else value) },
                                label = { Text(label) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = notes,
                        onValueChange = onNotes,
                        label = { Text(stringResource(R.string.patient_notes)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
