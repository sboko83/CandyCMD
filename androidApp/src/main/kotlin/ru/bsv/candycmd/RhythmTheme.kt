package ru.bsv.candycmd

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightRhythm = lightColorScheme(
    primary = Color(0xFF315FC8), onPrimary = Color.White,
    primaryContainer = Color(0xFFE9EFFE), onPrimaryContainer = Color(0xFF234DA8),
    secondary = Color(0xFF65738A), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9EFFE), onSecondaryContainer = Color(0xFF172A49),
    tertiary = Color(0xFF33694E), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEAF4EE), onTertiaryContainer = Color(0xFF33694E),
    background = Color(0xFFF7F9FD), onBackground = Color(0xFF172A49),
    surface = Color.White, onSurface = Color(0xFF172A49),
    surfaceVariant = Color(0xFFEDF1F8), onSurfaceVariant = Color(0xFF65738A),
    surfaceContainer = Color(0xFFEDF1F8), surfaceContainerLow = Color(0xFFF7F9FD),
    surfaceContainerHigh = Color(0xFFE9EFFE), surfaceContainerHighest = Color(0xFFE0E6EF),
    outline = Color(0xFF758298), outlineVariant = Color(0xFFE0E6EF),
    error = Color(0xFF9C3F31), onError = Color.White,
    errorContainer = Color(0xFFFFEDE7), onErrorContainer = Color(0xFF783629),
)

private val DarkRhythm = darkColorScheme(
    primary = Color(0xFFACC6FF), onPrimary = Color(0xFF12294E),
    primaryContainer = Color(0xFF273D60), onPrimaryContainer = Color(0xFFC5D7FF),
    secondary = Color(0xFFA6B5CB), onSecondary = Color(0xFF172A49),
    secondaryContainer = Color(0xFF273D60), onSecondaryContainer = Color(0xFFE7EEFB),
    tertiary = Color(0xFFA8D9BF), onTertiary = Color(0xFF153526),
    tertiaryContainer = Color(0xFF233E34), onTertiaryContainer = Color(0xFFA8D9BF),
    background = Color(0xFF131A26), onBackground = Color(0xFFE7EEFB),
    surface = Color(0xFF1E2735), onSurface = Color(0xFFE7EEFB),
    surfaceVariant = Color(0xFF253144), onSurfaceVariant = Color(0xFFA6B5CB),
    surfaceContainer = Color(0xFF1E2735), surfaceContainerLow = Color(0xFF18212F),
    surfaceContainerHigh = Color(0xFF273D60), surfaceContainerHighest = Color(0xFF354155),
    outline = Color(0xFF8A9BB5), outlineVariant = Color(0xFF354155),
    error = Color(0xFFFFB4A4), onError = Color(0xFF552217),
    errorContainer = Color(0xFF4B2E29), onErrorContainer = Color(0xFFFFC9BB),
)

private val RhythmTypography = Typography(
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 27.sp, lineHeight = 34.sp, letterSpacing = (-0.7).sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 23.sp, lineHeight = 30.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp, lineHeight = 28.sp, letterSpacing = (-0.4).sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 23.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp),
)

// Buttons and tiles use medium, cards and banners use large; Material3 buttons ignore the theme, see AppButton.
private val RhythmShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp), extraLarge = RoundedCornerShape(20.dp),
)

@Composable
internal fun CandyTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkRhythm else LightRhythm,
        typography = RhythmTypography, shapes = RhythmShapes, content = content)
}
