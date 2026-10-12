package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme =
  darkColorScheme(
    primary = SilverBlue,
    onPrimary = SlateNight,
    secondary = Moonlight,
    onSecondary = SlateNight,
    tertiary = SilverBlue,
    background = SlateNight,
    onBackground = Moonlight,
    surface = SlateNightSurface,
    onSurface = Moonlight,
  )

private val LightColorScheme =
  lightColorScheme(
    primary = MidnightSlate,
    onPrimary = Moonlight,
    secondary = SilverBlue,
    onSecondary = SlateDeep,
    tertiary = SilverBlue,
    background = Moonlight,
    onBackground = SlateDeep,
    surface = MoonlightLight,
    onSurface = SlateDeep,
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Dynamic (wallpaper) colour is off so the app always uses the Moonlight / Silver Blue / Midnight Slate palette.
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
