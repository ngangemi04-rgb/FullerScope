package com.capstone.stethoscope.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = Teal500,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = Teal200,
    secondary = Teal700,
    error = Coral,
)

private val DarkColors = darkColorScheme(
    primary = Teal200,
    secondary = Teal500,
    error = Coral,
)

@Composable
fun StethoScopeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
