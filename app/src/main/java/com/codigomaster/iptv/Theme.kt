package com.codigomaster.iptv

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Paleta Código Master: negro profundo, azul eléctrico y dorado real. */
object Cm {
    val Bg = Color(0xFF05070D)
    val Surface = Color(0xFF0C111D)
    val SurfaceHi = Color(0xFF141B2C)
    val SurfaceTop = Color(0xFF1B2438)
    val Outline = Color(0xFF232D45)
    val Gold = Color(0xFFFFC84A)
    val GoldDeep = Color(0xFFE59B14)
    val OnGold = Color(0xFF1A1200)
    val Blue = Color(0xFF4DA3FF)
    val Text = Color(0xFFF3F6FB)
    val Muted = Color(0xFF8E9BB5)
    val Live = Color(0xFFFF3B4E)
    val Danger = Color(0xFFFF6B78)

    val GoldBrush = Brush.horizontalGradient(listOf(Gold, GoldDeep))
    val PosterFallback = Brush.verticalGradient(listOf(Color(0xFF1B2640), Color(0xFF0E1424)))
}

@Composable
fun CodigoMasterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Cm.Gold,
            onPrimary = Cm.OnGold,
            secondary = Cm.Blue,
            background = Cm.Bg,
            surface = Cm.Surface,
            surfaceVariant = Cm.SurfaceHi,
            onBackground = Cm.Text,
            onSurface = Cm.Text,
            onSurfaceVariant = Cm.Muted,
            outline = Cm.Outline,
            error = Cm.Danger
        ),
        content = content
    )
}
