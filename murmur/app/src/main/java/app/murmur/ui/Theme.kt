package app.murmur.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Twilight: deep violet night, ember accents.
val Violet = Color(0xFF7C4DFF)
val Lilac = Color(0xFFB388FF)
val Ember = Color(0xFFFF8A65)
val Flame = Color(0xFFFF5252)

private val Dark = darkColorScheme(
    primary = Lilac,
    onPrimary = Color(0xFF1B0F33),
    primaryContainer = Color(0xFF3A2670),
    onPrimaryContainer = Color(0xFFEDE4FF),
    secondary = Ember,
    onSecondary = Color(0xFF3A1206),
    secondaryContainer = Color(0xFF4A2A22),
    onSecondaryContainer = Color(0xFFFFDBD0),
    background = Color(0xFF14121C),
    onBackground = Color(0xFFE8E2F2),
    surface = Color(0xFF14121C),
    onSurface = Color(0xFFE8E2F2),
    surfaceVariant = Color(0xFF2A2638),
    onSurfaceVariant = Color(0xFFB9B1CC),
    surfaceContainer = Color(0xFF1E1B29),
    surfaceContainerHigh = Color(0xFF26222F),
    surfaceContainerHighest = Color(0xFF302B3B),
    outline = Color(0xFF6E6680),
    error = Flame,
)

private val Light = lightColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9DDFF),
    onPrimaryContainer = Color(0xFF22005D),
    secondary = Color(0xFFC4502E),
    secondaryContainer = Color(0xFFFFDBD0),
    onSecondaryContainer = Color(0xFF3A0B00),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1C1A22),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1C1A22),
    surfaceVariant = Color(0xFFECE6F4),
    onSurfaceVariant = Color(0xFF4A4458),
    surfaceContainer = Color(0xFFF2EDFA),
    surfaceContainerHigh = Color(0xFFECE6F5),
    surfaceContainerHighest = Color(0xFFE5DFEF),
    outline = Color(0xFF7B7489),
    error = Color(0xFFBA1A1A),
)

@Composable
fun MurmurTheme(content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = base.copy(
            headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
            titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
        ),
        content = content,
    )
}
