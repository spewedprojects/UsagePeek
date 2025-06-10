package com.gratus.usagepeek.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview

private val LightColors = lightColorScheme(
    primary = Color(0xFF00629F),
    onPrimary = Color.White,
    secondary = Color(0xFF00629F),
)

@Composable
fun UsagePeekTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = Typography(),
        content = content
    )
}
