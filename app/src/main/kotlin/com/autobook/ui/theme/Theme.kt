package com.autobook.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import com.autobook.R

// Midnight Amber design system from Stitch
val Amber = Color(0xFFFFBF00)
val AmberDark = Color(0xFFFBBC00)
val AmberLight = Color(0xFFFFDFA0)
val Navy = Color(0xFF0A192F)
val NavyLight = Color(0xFF0D1C32)
val NavySurface = Color(0xFF233148)
val NavyMuted = Color(0xFF39475F)
val Teal = Color(0xFF00DCFF)
val SecondaryGold = Color(0xFF907335)
val TextPrimary = Color(0xFFD6E3FF)
val TextSecondary = Color(0xFFD6E3FF)
val TextMuted = Color(0xFF8391AD)

// Inter font from Google Fonts
val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs
)

val interFontName = GoogleFont("Inter")

val InterFontFamily = FontFamily(
    Font(googleFont = interFontName, fontProvider = provider, weight = FontWeight.Normal),
    Font(googleFont = interFontName, fontProvider = provider, weight = FontWeight.Medium),
    Font(googleFont = interFontName, fontProvider = provider, weight = FontWeight.SemiBold),
    Font(googleFont = interFontName, fontProvider = provider, weight = FontWeight.Bold)
)

// Typography using Inter font
val AIAnyBookTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 45.sp,
        lineHeight = 52.sp
    ),
    displaySmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp
    ),
    titleLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    labelLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)

private val DarkColorScheme = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF261A00),
    primaryContainer = Color(0xFF5C4300),
    onPrimaryContainer = AmberLight,
    secondary = SecondaryGold,
    onSecondary = Color(0xFF261A00),
    secondaryContainer = Color(0xFF5B4307),
    onSecondaryContainer = AmberLight,
    tertiary = Teal,
    onTertiary = Color(0xFF001F26),
    tertiaryContainer = Color(0xFF004E5C),
    onTertiaryContainer = Color(0xFFAAEDFF),
    background = Navy,
    onBackground = Color(0xFFD6E3FF),
    surface = NavyLight,
    onSurface = TextPrimary,
    surfaceVariant = NavySurface,
    onSurfaceVariant = TextSecondary,
    outline = NavyMuted,
    outlineVariant = Color(0xFF515F78),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF795900),
    onPrimary = Color.White,
    primaryContainer = AmberLight,
    onPrimaryContainer = Color(0xFF261A00),
    secondary = SecondaryGold,
    onSecondary = Color.White,
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
)

@Composable
fun AIAnyBookTheme(
    darkTheme: Boolean = true, // Default to dark theme
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AIAnyBookTypography,
        content = content
    )
}
