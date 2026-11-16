package com.codigomaster.iptv

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable\nimport androidx.compose.runtime.getValue\nimport androidx.compose.runtime.setValue\nimport androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Paleta Código Master: negro profundo, azul eléctrico y dorado real. */
object Cm {
    var Bg by mutableStateOf(Color(0xFF05070D))\n        private set
    var Surface by mutableStateOf(Color(0xFF0C111D))\n        private set
    var SurfaceHi by mutableStateOf(Color(0xFF141B2C))\n        private set
    var SurfaceTop by mutableStateOf(Color(0xFF1B2438))\n        private set
    val Outline = Color(0xFF232D45)
    val Gold = Color(0xFFFFC84A)
    val GoldDeep = Color(0xFFE59B14)
    val OnGold = Color(0xFF1A1200)
    val Blue = Color(0xFF4DA3FF)
    var Text by mutableStateOf(Color(0xFFF3F6FB))\n        private set\n    var Selection by mutableStateOf(Color(0xFF4DA3FF))\n        private set
    val Muted = Color(0xFF8E9BB5)
    val Live = Color(0xFFFF3B4E)
    val Danger = Color(0xFFFF6B78)

    fun load(store: Store) {
        Bg = parse(store.backgroundColor, Color(0xFF05070D))
        Surface = Bg.copy(red = (Bg.red * 0.78f).coerceIn(0f, 1f), green = (Bg.green * 0.82f).coerceIn(0f, 1f), blue = (Bg.blue * 0.90f).coerceIn(0f, 1f))
        SurfaceHi = Surface.copy(red = (Surface.red * 1.35f).coerceIn(0f, 1f), green = (Surface.green * 1.35f).coerceIn(0f, 1f), blue = (Surface.blue * 1.35f).coerceIn(0f, 1f))
        SurfaceTop = SurfaceHi.copy(red = (SurfaceHi.red * 1.25f).coerceIn(0f, 1f), green = (SurfaceHi.green * 1.25f).coerceIn(0f, 1f), blue = (SurfaceHi.blue * 1.25f).coerceIn(0f, 1f))
        Text = parse(store.textColor, Color(0xFFF3F6FB))
        Selection = parse(store.selectionColor, Color(0xFF4DA3FF))
    }

    fun parse(hex: String, fallback: Color): Color = try {
        val clean = hex.removePrefix("#")
        Color(android.graphics.Color.parseColor(if (clean.length == 6) "#FF$clean" else "#$clean"))
    } catch (_: Exception) { fallback }

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
