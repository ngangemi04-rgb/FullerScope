package com.capstone.stethoscope.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Runs [onStop] when the screen goes to the background (ON_STOP). Used to stop
 * live audio (listening / recording) so it never keeps running — and pegging the
 * CPU — while the app isn't visible.
 */
@Composable
fun StopWhenBackgrounded(onStop: () -> Unit) {
    val currentOnStop by rememberUpdatedState(onStop)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) currentOnStop()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
