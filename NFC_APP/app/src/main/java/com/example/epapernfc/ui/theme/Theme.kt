package com.example.epapernfc.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xff236b5b),
    onPrimary = Color.White,
    primaryContainer = Color(0xffc5ebdb),
    secondaryContainer = Color(0xffe4ede6),
    onSecondaryContainer = Color(0xff233b31),
    background = Color(0xfff8f7f1),
    surface = Color(0xfff8f7f1),
    surfaceContainerHighest = Color(0xffefeee7)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xff9ed4bf),
    primaryContainer = Color(0xff174f40),
    secondaryContainer = Color(0xff283e34),
    background = Color(0xff141b17),
    surface = Color(0xff141b17)
)

@Composable
fun EpaperNFCTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, typography = Typography, content = content)
}
