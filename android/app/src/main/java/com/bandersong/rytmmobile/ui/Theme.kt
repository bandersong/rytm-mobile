package com.bandersong.rytmmobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.bandersong.rytmmobile.R

/**
 * "Patch bay" palette, lifted from the Synth Jam Bot reference site (dark scheme):
 * audio = blue cables, cv = amber cables, signal = coral/red rail.
 */
object P {
    val Bg = Color(0xFF12181A)
    val Panel = Color(0xFF1C2528)
    val Well = Color(0xFF222C2F)       // ink 7% over panel
    val Line = Color(0xFF3E494B)       // ink 24% over panel
    val Ink = Color(0xFFE4EAE8)
    val Muted = Color(0xFFA2ABAA)      // ink 68% over panel
    val Cv = Color(0xFFF0B429)         // amber
    val Signal = Color(0xFFFF7467)     // coral
    val Audio = Color(0xFF7AB8F5)      // blue
    val Shade = Color(0xFF0B0F10)
}

val Display = FontFamily(
    Font(R.font.barlow_sc_bold, FontWeight.Bold),
    Font(R.font.barlow_sc_semibold, FontWeight.SemiBold),
)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val Body = FontFamily(
    Font(R.font.plex_sans, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.plex_sans, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.plex_sans, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)

val Mono = FontFamily(
    Font(R.font.plex_mono, FontWeight.Normal),
    Font(R.font.plex_mono_medium, FontWeight.Medium),
)

object T {
    val Hero = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 40.sp, letterSpacing = 0.01.em, color = P.Ink)
    val Title = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 26.sp, letterSpacing = 0.02.em, color = P.Ink)
    val Button = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 19.sp, letterSpacing = 0.08.em, color = P.Ink)
    val Kicker = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.14.em, color = P.Signal)
    val Label = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.1.em, color = P.Muted)
    val Data = TextStyle(fontFamily = Mono, fontSize = 13.sp, color = P.Ink)
    val Body = TextStyle(fontFamily = com.bandersong.rytmmobile.ui.Body, fontSize = 15.sp, lineHeight = 22.sp, color = P.Ink)
    val Small = TextStyle(fontFamily = com.bandersong.rytmmobile.ui.Body, fontSize = 13.sp, lineHeight = 18.sp, color = P.Muted)
}

@Composable
fun PatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = P.Bg, surface = P.Panel, primary = P.Cv, onPrimary = P.Bg,
            secondary = P.Signal, onSurface = P.Ink, onBackground = P.Ink, outline = P.Line,
        ),
        content = content,
    )
}
