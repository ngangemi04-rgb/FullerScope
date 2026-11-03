package com.capstone.stethoscope.ui.settings

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.capstone.stethoscope.R
import com.capstone.stethoscope.data.AppSettings
import com.capstone.stethoscope.locale.LocaleHelper

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenTesting: () -> Unit,
    onOpenTutorial: () -> Unit,
    vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val context = LocalContext.current
    val sampleRate by vm.sampleRate.collectAsState()
    val inputChannel by vm.inputChannel.collectAsState()
    val testingMode by vm.testingMode.collectAsState()
    val currentLang = LocaleHelper.getLanguageTag(context)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // --- Language ---
            Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LocaleHelper.SUPPORTED.forEach { lang ->
                    FilterChip(
                        selected = currentLang == lang.tag,
                        onClick = {
                            if (currentLang != lang.tag) {
                                LocaleHelper.setLanguageTag(context, lang.tag)
                                (context as? Activity)?.recreate()
                            }
                        },
                        label = { Text(lang.displayName) },
                    )
                }
            }

            HorizontalDivider()

            // --- Sample rate ---
            Text(stringResource(R.string.settings_sample_rate), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_sample_rate_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppSettings.SAMPLE_RATE_OPTIONS.forEach { rate ->
                    FilterChip(
                        selected = sampleRate == rate,
                        onClick = { vm.setSampleRate(rate) },
                        label = { Text("$rate Hz") },
                    )
                }
            }

            HorizontalDivider()

            // --- Input channel (only matters for two-channel hardware) ---
            Text(stringResource(R.string.settings_input_channel), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_input_channel_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppSettings.InputChannel.entries.forEach { channel ->
                    FilterChip(
                        selected = inputChannel == channel,
                        onClick = { vm.setInputChannel(channel) },
                        label = {
                            Text(
                                stringResource(
                                    when (channel) {
                                        AppSettings.InputChannel.LEFT -> R.string.channel_left
                                        AppSettings.InputChannel.RIGHT -> R.string.channel_right
                                        AppSettings.InputChannel.MIX -> R.string.channel_mix
                                    }
                                )
                            )
                        },
                    )
                }
            }

            HorizontalDivider()

            // --- Tutorial ---
            Text(stringResource(R.string.settings_tutorial), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onOpenTutorial) {
                Text(stringResource(R.string.settings_tutorial_open))
            }

            HorizontalDivider()

            // --- Hardware diagnostics ---
            Text(stringResource(R.string.settings_diagnostics), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_diagnostics_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onOpenDiagnostics) {
                Text(stringResource(R.string.settings_diagnostics))
            }

            HorizontalDivider()

            // --- Testing mode ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.settings_testing_mode),
                    style = MaterialTheme.typography.titleMedium,
                )
                Switch(checked = testingMode, onCheckedChange = vm::setTestingMode)
            }
            Text(
                stringResource(R.string.settings_testing_mode_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Reachable from here even with the switch off, so the scope can be opened
            // once without leaving a shortcut on the clinical screen.
            OutlinedButton(onClick = onOpenTesting) {
                Text(stringResource(R.string.settings_testing_open))
            }
        }
    }
}
