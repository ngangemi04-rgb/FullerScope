package com.capstone.stethoscope.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.capstone.stethoscope.R
import com.capstone.stethoscope.data.model.Recording

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenResult: (Long) -> Unit,
    onBack: () -> Unit,
    vm: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory),
) {
    val recordings by vm.recordings.collectAsState()
    val export by vm.export.collectAsState()
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.exportAll(uri)
    }

    // A test session's export runs for minutes; the screen going to sleep would pause it
    // in the user's mind even if it does not in fact.
    val view = LocalView.current
    DisposableEffect(export is LibraryViewModel.ExportState.Running) {
        view.keepScreenOn = export is LibraryViewModel.ExportState.Running
        onDispose { view.keepScreenOn = false }
    }
    export?.let { ExportDialog(it, onCancel = vm::cancelExport, onDismiss = vm::dismissExport) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    TextButton(onClick = { pickFolder.launch(null) }, enabled = recordings.isNotEmpty()) {
                        Text(stringResource(R.string.export_all))
                    }
                },
            )
        },
    ) { padding ->
        if (recordings.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.library_empty))
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(recordings, key = { it.id }) { recording ->
                    RecordingItem(
                        recording = recording,
                        onClick = { onOpenResult(recording.id) },
                        onDelete = { vm.delete(recording) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ExportDialog(
    state: LibraryViewModel.ExportState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        is LibraryViewModel.ExportState.Running -> AlertDialog(
            // Not dismissable by tapping outside or Back: only Cancel stops it.
            onDismissRequest = {},
            title = { Text(stringResource(R.string.export_all_running_title)) },
            text = {
                val p = state.progress
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(
                            if (p.analyzing) R.string.export_all_analyzing else R.string.export_all_writing,
                            (p.done + 1).coerceAtMost(p.total), p.total,
                        )
                    )
                    LinearProgressIndicator(
                        progress = { if (p.total > 0) p.done.toFloat() / p.total else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.export_all_keep_open), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
        )
        is LibraryViewModel.ExportState.Done -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.export_all_done_title)) },
            text = {
                val s = state.summary
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.export_all_done_body, s.exported, s.folderName))
                    if (s.failures.isNotEmpty()) {
                        Text(
                            stringResource(R.string.export_all_failures, s.failures.size) + "\n" +
                                s.failures.joinToString("\n"),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
        )
        is LibraryViewModel.ExportState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.export_all_failed_title)) },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
        )
    }
}

@Composable
private fun RecordingItem(
    recording: Recording,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    recording.patientName?.takeIf { it.isNotBlank() } ?: recording.label,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    // Segmentation-only mode: no rate or rhythm in the list either.
                    text = stringResource(
                        if (recording.isAnalyzed && recording.segmentation != null) {
                            R.string.library_segmented
                        } else {
                            R.string.not_analyzed
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
            }
        }
    }
}
