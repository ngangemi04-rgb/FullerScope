package com.capstone.stethoscope.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.capstone.stethoscope.R
import com.capstone.stethoscope.data.model.AuscultationSite
import com.capstone.stethoscope.ui.components.ChestDiagram
import com.capstone.stethoscope.ui.components.detail
import com.capstone.stethoscope.ui.components.label

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    recordingId: Long,
    onRetake: () -> Unit,
    onAnalyze: () -> Unit,
    onBack: () -> Unit,
    vm: ReviewViewModel = viewModel(factory = ReviewViewModel.Factory),
) {
    val recording by vm.recording.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.review_title)) },
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
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = recording?.label ?: "",
                style = MaterialTheme.typography.headlineSmall,
            )
            recording?.let {
                Text(
                    text = stringResource(R.string.review_meta, it.durationMs / 1000, it.sampleRate),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(8.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.review_playback_hint))
                    Button(
                        onClick = vm::togglePlayback,
                        modifier = Modifier.size(72.dp),
                    ) {
                        Icon(
                            if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(
                                if (isPlaying) R.string.action_stop_playback else R.string.action_play
                            ),
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
            }

            // Where did you record from? Asked here rather than before the recording,
            // because now the clinician knows where they actually put the bell.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.review_site_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.review_site_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    val site = recording?.auscultationSite
                    ChestDiagram(
                        selected = site,
                        onSelect = vm::setSite,
                        patientSex = recording?.patientSex,
                        labelFor = { it.label() },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Text(
                        text = site?.let { stringResource(R.string.review_site_chosen, it.label()) }
                            ?: stringResource(R.string.review_site_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (site == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    site?.let {
                        Text(
                            it.detail(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // The apex sits beneath the left breast, so the landmark alone does not
                    // say how to reach it. Listening through breast tissue muffles S1.
                    if (site == AuscultationSite.MITRAL && recording?.patientSex == "Female") {
                        Text(
                            stringResource(R.string.site_mitral_female_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    OutlinedTextField(
                        value = recording?.siteNote.orEmpty(),
                        onValueChange = vm::setSiteNote,
                        label = { Text(stringResource(R.string.review_site_note_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedButton(
                    onClick = { vm.retake(onRetake) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.btn_retake))
                }
                Button(
                    onClick = onAnalyze,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(),
                ) {
                    Icon(Icons.Filled.Analytics, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.btn_analyze))
                }
            }
        }
    }
}
