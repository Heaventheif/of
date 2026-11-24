package org.openfilemanager.app.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Brand seed (emerald). Used only for the fallback palettes below; with dynamic color the system wallpaper wins. */
val BrandGreen = Color(0xFF00E676)

/**
 * Appearance preferences (theme mode + Material You). Small synchronous SharedPreferences, like
 * LanguageManager, because the theme is needed before the first frame. State is observable, so
 * changing it from Settings recomposes the whole app instantly with no activity recreation.
 */
object ThemeManager {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    private const val PREFS = "ofm_theme"
    private const val KEY_MODE = "mode"
    private const val KEY_DYNAMIC = "dynamic"

    var mode: String by mutableStateOf(SYSTEM)
        private set
    var dynamicColor: Boolean by mutableStateOf(false)
        private set

    /** Dynamic color needs Android 12 (API 31); the app's minSdk is 30. */
    val dynamicSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        mode = p.getString(KEY_MODE, SYSTEM)?.takeIf { it == LIGHT || it == DARK } ?: SYSTEM
        dynamicColor = p.getBoolean(KEY_DYNAMIC, false)
    }

    fun saveMode(context: Context, value: String) {
        mode = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODE, value).apply()
    }

    fun saveDynamic(context: Context, value: Boolean) {
        dynamicColor = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DYNAMIC, value).apply()
    }
}

// ---- Fallback Material 3 tonal palettes (used below Android 12 or when dynamic color is off) ----
private val OfmLightColors = lightColorScheme(
    primary = Color(0xFF00A152), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF94F7B5), onPrimaryContainer = Color(0xFF00210F),
    secondary = Color(0xFF4E6354), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD0E8D4), onSecondaryContainer = Color(0xFF0B1F13),
    tertiary = Color(0xFF3B6470), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBFE9F7), onTertiaryContainer = Color(0xFF001F27),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF6FBF3), onBackground = Color(0xFF181D19),
    surface = Color(0xFFF6FBF3), onSurface = Color(0xFF181D19),
    surfaceVariant = Color(0xFFDCE5DB), onSurfaceVariant = Color(0xFF404942),
    outline = Color(0xFF717972), outlineVariant = Color(0xFFC0C9C0),
    inverseSurface = Color(0xFF2D322D), inverseOnSurface = Color(0xFFEEF2EA), inversePrimary = Color(0xFF00E676),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF0F5ED),
    surfaceContainer = Color(0xFFEAEFE7), surfaceContainerHigh = Color(0xFFE4EAE2),
    surfaceContainerHighest = Color(0xFFDFE4DC)
)

private val OfmDarkColors = darkColorScheme(
    primary = Color(0xFF00E676), onPrimary = Color(0xFF00391D),
    primaryContainer = Color(0xFF0A4A2A), onPrimaryContainer = Color(0xFF00E676),
    secondary = Color(0xFFB4CCB9), onSecondary = Color(0xFF203526),
    secondaryContainer = Color(0xFF374B3C), onSecondaryContainer = Color(0xFFD0E8D4),
    tertiary = Color(0xFFA3CDDB), onTertiary = Color(0xFF053543),
    tertiaryContainer = Color(0xFF224C5A), onTertiaryContainer = Color(0xFFBFE9F7),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F1511), onBackground = Color(0xFFDFE4DC),
    surface = Color(0xFF0F1511), onSurface = Color(0xFFDFE4DC),
    surfaceVariant = Color(0xFF404942), onSurfaceVariant = Color(0xFFC0C9C0),
    outline = Color(0xFF8A938B), outlineVariant = Color(0xFF404942),
    inverseSurface = Color(0xFFDFE4DC), inverseOnSurface = Color(0xFF2D322D), inversePrimary = Color(0xFF00A152),
    surfaceContainerLowest = Color(0xFF0A0F0B), surfaceContainerLow = Color(0xFF171D19),
    surfaceContainer = Color(0xFF1B211D), surfaceContainerHigh = Color(0xFF262B27),
    surfaceContainerHighest = Color(0xFF303632)
)

/** Standard Material 3 type scale. Only change: nothing is smaller than 12sp (readability). */
private val OfmType = Typography().let { t ->
    // Slightly bolder text everywhere: Normal -> Medium, Medium -> SemiBold
    fun TextStyle.bolder() = copy(
        fontWeight = when (fontWeight) {
            null, FontWeight.Normal -> FontWeight.Medium
            FontWeight.Medium -> FontWeight.SemiBold
            else -> fontWeight
        }
    )
    t.copy(
        displayLarge = t.displayLarge.bolder(), displayMedium = t.displayMedium.bolder(), displaySmall = t.displaySmall.bolder(),
        headlineLarge = t.headlineLarge.bolder(), headlineMedium = t.headlineMedium.bolder(), headlineSmall = t.headlineSmall.bolder(),
        titleLarge = t.titleLarge.bolder(), titleMedium = t.titleMedium.bolder(), titleSmall = t.titleSmall.bolder(),
        bodyLarge = t.bodyLarge.bolder(), bodyMedium = t.bodyMedium.bolder(), bodySmall = t.bodySmall.bolder(),
        labelLarge = t.labelLarge.bolder(), labelMedium = t.labelMedium.bolder(),
        labelSmall = t.labelSmall.bolder().copy(fontSize = 12.sp, lineHeight = 16.sp)
    )
}

/** Standard Material 3 shape scale: 4 / 8 / 12 / 16 / 28 dp. */
private val OfmShapes = Shapes()

/** Fixed spacing/size scale. Every screen uses these, so everything lines up. */
object Dim {
    val Gutter = 16.dp
    val RowMinHeight = 64.dp
    val Tile = 48.dp
    val TouchTarget = 48.dp
    val GridCell = 108.dp
    val HomeCard = 72.dp
    val SheetItem = 56.dp
    val NavBar = 80.dp
    val BarIcon = 24.dp
    val IconBox = 40.dp
}

/** True when the app is currently drawn with a dark scheme (system or user choice). */
@Composable
fun isOfmDark(): Boolean = when (ThemeManager.mode) {
    ThemeManager.LIGHT -> false
    ThemeManager.DARK -> true
    else -> isSystemInDarkTheme()
}

@Composable
fun OfmTheme(content: @Composable () -> Unit) {
    val dark = isOfmDark()
    val ctx = LocalContext.current
    val scheme = when {
        ThemeManager.dynamicColor && ThemeManager.dynamicSupported ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> OfmDarkColors
        else -> OfmLightColors
    }
    MaterialTheme(colorScheme = scheme, typography = OfmType, shapes = OfmShapes, content = content)
}
