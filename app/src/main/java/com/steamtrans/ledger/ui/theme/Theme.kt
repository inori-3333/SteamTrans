package com.steamtrans.ledger.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Typography
import com.steamtrans.ledger.data.ItemType

val DeepInk = Color(0xFF07111E)
val PageBlue = Color(0xFF0A1727)
val SectionBlue = Color(0xFF102238)
val RaisedBlue = Color(0xFF172C45)
val InputBlue = Color(0xFF0C1D30)
val SteamBlue = Color(0xFF66C0F4)
val WalletBlue = Color(0xFF4FB6F4)
val FiatGold = Color(0xFFE6B85C)
val BuyCoral = Color(0xFFFF7C74)
val SellMint = Color(0xFF5ED6A7)
val ConvertPurple = Color(0xFFB79BFF)
val AdjustAmber = Color(0xFFFFC35B)
val Gain = Color(0xFF66D6A5)
val Loss = Color(0xFFFF7D88)
val Warning = Color(0xFFFFC35B)
val Stale = Color(0xFFDBA765)
val TextPrimary = Color(0xFFF1F6FC)
val TextSecondary = Color(0xFFA7B6CA)
val Divider = Color(0xFF29405B)

private val LedgerColors = darkColorScheme(
    primary = SteamBlue,
    onPrimary = DeepInk,
    primaryContainer = RaisedBlue,
    onPrimaryContainer = TextPrimary,
    secondary = FiatGold,
    onSecondary = DeepInk,
    secondaryContainer = SectionBlue,
    onSecondaryContainer = TextPrimary,
    tertiary = ConvertPurple,
    background = PageBlue,
    onBackground = TextPrimary,
    surface = SectionBlue,
    onSurface = TextPrimary,
    surfaceVariant = RaisedBlue,
    onSurfaceVariant = TextSecondary,
    outline = Divider,
    error = Loss,
    onError = DeepInk
)

private val LedgerTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 27.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp)
)

@Composable
fun SteamLedgerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LedgerColors, typography = LedgerTypography, content = content)
}

fun categoryColor(type: ItemType): Color = when (type) {
    ItemType.SKIN -> Color(0xFF7FA9D8)
    ItemType.CASE_CONTAINER -> Color(0xFFD1A96D)
    ItemType.CAPSULE -> Color(0xFF8DB9A4)
    ItemType.SOUVENIR_PACKAGE -> Color(0xFFD09775)
    ItemType.GEM_SACK, ItemType.GEM -> Color(0xFF9D89D1)
    ItemType.CARD, ItemType.FOIL_CARD -> Color(0xFF7CB5B3)
    ItemType.BOOSTER -> Color(0xFFB795C8)
    ItemType.BACKGROUND -> Color(0xFF758EBD)
    ItemType.EMOTICON -> Color(0xFFD38CA2)
    ItemType.OTHER -> Color(0xFF8E9BAD)
}
