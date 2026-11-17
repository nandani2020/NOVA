package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val NovaDarkColorScheme = darkColorScheme(
    primary = NovaViolet,
    onPrimary = NovaTextPrimary,
    primaryContainer = NovaBgBottom,
    onPrimaryContainer = NovaLavender,
    secondary = NovaMagenta,
    onSecondary = NovaTextPrimary,
    tertiary = NovaCyan,
    onTertiary = NovaBgTop,
    background = NovaBgTop,
    onBackground = NovaTextPrimary,
    surface = NovaSurfaceDeep,
    onSurface = NovaTextPrimary,
    surfaceVariant = NovaGlassFillElevated,
    onSurfaceVariant = NovaTextSecondary,
    outline = NovaGlassBorder,
    error = NovaCoralRed,
    onError = NovaTextPrimary
)

val NovaShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(999.dp)
)

@Composable
fun NovaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NovaDarkColorScheme,
        typography = NovaTypography,
        shapes = NovaShapes,
        content = content
    )
}
