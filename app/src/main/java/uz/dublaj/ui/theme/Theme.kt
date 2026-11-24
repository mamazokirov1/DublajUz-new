package uz.dublaj.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/** Foydalanuvchi tanlovi: 0 = tizim, 1 = yorug', 2 = qorong'i. */
object ThemeSettings {
    var mode by mutableIntStateOf(0)
    var dynamic by mutableStateOf(false)
    /** Tanlangan rang mavzusi (0 = standart). Faqat Pro da amal qiladi. */
    var accent by mutableIntStateOf(0)
    var pro by mutableStateOf(false)
}

/**
 * Rang mavzusi. Birinchi 5 maydon — asosiy (primary) ranglar; qolganlari butun sxemani (ikkilamchi rang,
 * fon, sirt, gradient) uyg'unlashtiradi. 0-mavzu — standart (hammaga bepul), qolganlari — Pro.
 */
data class AccentPalette(
    val name: String,
    val light: Color,
    val lightContainer: Color,
    val dark: Color,
    val darkContainer: Color,
    val sec: Color = Color(0xFF00898A),
    val bgLight: Color = Color(0xFFF7F5FF),
    val bgDark: Color = Color(0xFF110F18),
    val surfDark: Color = Color(0xFF1B1824),
    val gradient: List<Color> = listOf(Color(0xFF4A2FD0), Color(0xFF7B4DFF), Color(0xFF00A6A6))
)

val AccentPalettes: List<AccentPalette> = listOf(
    AccentPalette("Binafsha (standart)", Color(0xFF5B3FD8), Color(0xFFE6DFFF), Color(0xFFB9AAFF), Color(0xFF4328B8)),
    AccentPalette(
        "Okean", Color(0xFF0B63D6), Color(0xFFD6E6FF), Color(0xFF9CC4FF), Color(0xFF0A3F8C),
        sec = Color(0xFF0097A7), bgLight = Color(0xFFF2F7FF), bgDark = Color(0xFF09111B), surfDark = Color(0xFF111C2A),
        gradient = listOf(Color(0xFF0A3F8C), Color(0xFF0B63D6), Color(0xFF14B8C6))
    ),
    AccentPalette(
        "Zumrad", Color(0xFF0B8A5D), Color(0xFFCFF5E4), Color(0xFF6FE0B0), Color(0xFF06583B),
        sec = Color(0xFFB7892B), bgLight = Color(0xFFF1FAF5), bgDark = Color(0xFF09130F), surfDark = Color(0xFF111F19),
        gradient = listOf(Color(0xFF06583B), Color(0xFF0B8A5D), Color(0xFF3FD0A4))
    ),
    AccentPalette(
        "Quyosh botishi", Color(0xFFD9480F), Color(0xFFFFE0CC), Color(0xFFFFB38A), Color(0xFF8A2E06),
        sec = Color(0xFFC2255C), bgLight = Color(0xFFFFF6F0), bgDark = Color(0xFF17100C), surfDark = Color(0xFF221813),
        gradient = listOf(Color(0xFFB0235A), Color(0xFFD9480F), Color(0xFFF5A524))
    ),
    AccentPalette(
        "Atirgul", Color(0xFFC2255C), Color(0xFFFFDDE8), Color(0xFFFFA8C5), Color(0xFF7D1238),
        sec = Color(0xFFB8860B), bgLight = Color(0xFFFFF5F8), bgDark = Color(0xFF160D11), surfDark = Color(0xFF211419),
        gradient = listOf(Color(0xFF7D1238), Color(0xFFC2255C), Color(0xFFE8A87C))
    ),
    AccentPalette(
        "Oltin tun", Color(0xFF8A6100), Color(0xFFFFEFC2), Color(0xFFF2C14E), Color(0xFF5E4100),
        sec = Color(0xFF6B7A99), bgLight = Color(0xFFFBF8EF), bgDark = Color(0xFF0B0B0E), surfDark = Color(0xFF16161B),
        gradient = listOf(Color(0xFF1C1608), Color(0xFF7A5400), Color(0xFFF2C14E))
    ),
    AccentPalette(
        "Aurora", Color(0xFF6D28D9), Color(0xFFEDE4FF), Color(0xFFC4B5FD), Color(0xFF4C1D95),
        sec = Color(0xFF0D9488), bgLight = Color(0xFFF8F4FF), bgDark = Color(0xFF0E0A18), surfDark = Color(0xFF18132A),
        gradient = listOf(Color(0xFF4C1D95), Color(0xFF7C3AED), Color(0xFF14B8A6))
    ),
    AccentPalette(
        "Yarim tun", Color(0xFF1E40AF), Color(0xFFDCE6FF), Color(0xFF93B4FF), Color(0xFF1B2D6B),
        sec = Color(0xFF0EA5E9), bgLight = Color(0xFFF3F6FF), bgDark = Color(0xFF070B16), surfDark = Color(0xFF0F1628),
        gradient = listOf(Color(0xFF0B1437), Color(0xFF1E40AF), Color(0xFF38BDF8))
    ),
    AccentPalette(
        "Yoqut", Color(0xFFB3122B), Color(0xFFFFDAD9), Color(0xFFFF8A94), Color(0xFF7A0A1E),
        sec = Color(0xFF5F6B7A), bgLight = Color(0xFFFFF5F5), bgDark = Color(0xFF0E0D0F), surfDark = Color(0xFF19171A),
        gradient = listOf(Color(0xFF3A0A14), Color(0xFFB3122B), Color(0xFFFF6B6B))
    )
)

/** Hozirgi (Pro tanlovi hisobga olingan) mavzuning gradienti: hero, jarayon kartasi va h.k. uchun. */
fun currentGradient(): List<Color> =
    AccentPalettes[if (ThemeSettings.pro) ThemeSettings.accent.coerceIn(0, AccentPalettes.size - 1) else 0].gradient

private fun onColor(c: Color): Color = if (c.luminance() > 0.5f) Color(0xFF14121A) else Color.White

object Brand {
    val Indigo = Color(0xFF4A2FD0)
    val Violet = Color(0xFF7B4DFF)
    val Teal = Color(0xFF00A6A6)
    val Coral = Color(0xFFFF6B8B)
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B3FD8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6DFFF),
    onPrimaryContainer = Color(0xFF1B0A5C),
    secondary = Color(0xFF00898A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDF4F2),
    onSecondaryContainer = Color(0xFF00302F),
    tertiary = Color(0xFFD6406A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDE5),
    onTertiaryContainer = Color(0xFF3F0016),
    background = Color(0xFFF7F5FF),
    onBackground = Color(0xFF1C1B22),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1B22),
    surfaceVariant = Color(0xFFECE8F8),
    onSurfaceVariant = Color(0xFF494657),
    outline = Color(0xFF7A7689),
    outlineVariant = Color(0xFFCBC6DA),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9AAFF),
    onPrimary = Color(0xFF2A0F8F),
    primaryContainer = Color(0xFF4328B8),
    onPrimaryContainer = Color(0xFFE6DFFF),
    secondary = Color(0xFF5CE0DC),
    onSecondary = Color(0xFF003736),
    secondaryContainer = Color(0xFF00504F),
    onSecondaryContainer = Color(0xFFCDF4F2),
    tertiary = Color(0xFFFFB0C2),
    onTertiary = Color(0xFF5E1030),
    tertiaryContainer = Color(0xFF7B2A47),
    onTertiaryContainer = Color(0xFFFFDDE5),
    background = Color(0xFF110F18),
    onBackground = Color(0xFFE6E1EC),
    surface = Color(0xFF1B1824),
    onSurface = Color(0xFFE6E1EC),
    surfaceVariant = Color(0xFF2B2838),
    onSurfaceVariant = Color(0xFFC9C4D8),
    outline = Color(0xFF938EA2),
    outlineVariant = Color(0xFF494657),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
)

fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun DublajTheme(content: @Composable () -> Unit) {
    val dark = when (ThemeSettings.mode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val accentIdx = if (ThemeSettings.pro) ThemeSettings.accent.coerceIn(0, AccentPalettes.size - 1) else 0
    val pal = AccentPalettes[accentIdx]
    val scheme = when {
        ThemeSettings.dynamic && Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        accentIdx == 0 -> if (dark) DarkColors else LightColors
        dark -> {
            val sc = lerp(pal.sec, Color.Black, 0.55f)
            DarkColors.copy(
                primary = pal.dark, onPrimary = onColor(pal.dark),
                primaryContainer = pal.darkContainer, onPrimaryContainer = onColor(pal.darkContainer),
                secondary = lerp(pal.sec, Color.White, 0.35f), onSecondary = Color(0xFF101010),
                secondaryContainer = sc, onSecondaryContainer = onColor(sc),
                tertiary = pal.gradient.last(), tertiaryContainer = lerp(pal.gradient.last(), Color.Black, 0.6f),
                onTertiaryContainer = Color.White,
                background = pal.bgDark, surface = pal.surfDark,
                surfaceVariant = lerp(pal.surfDark, Color.White, 0.08f)
            )
        }
        else -> {
            val sc = lerp(pal.sec, Color.White, 0.82f)
            LightColors.copy(
                primary = pal.light, onPrimary = onColor(pal.light),
                primaryContainer = pal.lightContainer, onPrimaryContainer = onColor(pal.lightContainer),
                secondary = pal.sec, onSecondary = Color.White,
                secondaryContainer = sc, onSecondaryContainer = Color(0xFF14121A),
                tertiary = pal.gradient.last(), tertiaryContainer = lerp(pal.gradient.last(), Color.White, 0.8f),
                onTertiaryContainer = Color(0xFF14121A),
                background = pal.bgLight, surface = lerp(pal.bgLight, Color.White, 0.7f),
                surfaceVariant = lerp(pal.bgLight, pal.light, 0.1f)
            )
        }
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = context.findActivity()?.window ?: return@SideEffect
            val c = WindowCompat.getInsetsController(window, view)
            c.isAppearanceLightStatusBars = !dark
            c.isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
}
