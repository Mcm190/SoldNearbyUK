package com.soldnearby.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = BrandGreen,
    secondary = BrandGreenLight,
    tertiary = Amber
)

private val DarkColors = darkColorScheme(
    primary = BrandGreenLight,
    secondary = BrandGreen,
    tertiary = Amber
)

// Driven by the app's own "Dark mode" setting (AppSettings.darkModeEnabled), not the system
// theme — this app deliberately doesn't follow the device default, and defaults to light.
@Composable
fun SoldNearbyTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
