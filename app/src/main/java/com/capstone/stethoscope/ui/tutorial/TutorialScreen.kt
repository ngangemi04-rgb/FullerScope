package com.capstone.stethoscope.ui.tutorial

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.capstone.stethoscope.R
import kotlinx.coroutines.launch

/** One step: what to do, in the order the clinician will do it. */
private class Step(val icon: ImageVector, @StringRes val title: Int, @StringRes val body: Int)

/**
 * Five swipeable cards covering one patient from plug-in to sending the report. Kept to
 * the path every user walks; Settings, diagnostics and testing mode are left out because
 * nobody needs them to record a patient.
 */
private val STEPS = listOf(
    Step(Icons.Filled.Usb, R.string.tutorial_connect_title, R.string.tutorial_connect_body),
    Step(Icons.Filled.GraphicEq, R.string.tutorial_listen_title, R.string.tutorial_listen_body),
    Step(Icons.Filled.FiberManualRecord, R.string.tutorial_record_title, R.string.tutorial_record_body),
    Step(Icons.Filled.TouchApp, R.string.tutorial_review_title, R.string.tutorial_review_body),
    Step(Icons.Filled.Insights, R.string.tutorial_result_title, R.string.tutorial_result_body),
)

@Composable
fun TutorialScreen(onFinished: () -> Unit) {
    val pager = rememberPagerState { STEPS.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == STEPS.lastIndex

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (!last) {
                    TextButton(onClick = onFinished) { Text(stringResource(R.string.tutorial_skip)) }
                }
            }

            HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
                StepPage(STEPS[page])
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                repeat(STEPS.size) { i ->
                    Box(
                        Modifier
                            .padding(4.dp)
                            .size(8.dp)
                            .background(
                                if (i == pager.currentPage) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                                CircleShape,
                            )
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (pager.currentPage > 0) {
                    TextButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }) {
                        Text(stringResource(R.string.action_back))
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        if (last) onFinished()
                        else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                    },
                ) {
                    Text(stringResource(if (last) R.string.tutorial_done else R.string.tutorial_next))
                }
            }
        }
    }
}

@Composable
private fun StepPage(step: Step) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
        Box(
            modifier = Modifier
                .size(112.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                step.icon,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Text(
            stringResource(step.title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(step.body),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}
