package com.codigomaster.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class RatingKind { MOVIE, SERIES, CHANNEL }

val LocalStore = staticCompositionLocalOf<Store?> { null }
val LocalRatings = staticCompositionLocalOf<RatingsRepo?> { null }

private val TRAILING_YEAR = Regex("""[\s\-–:(\[]*\b(19\d{2}|20\d{2})\b[)\]]*\s*$""")
private val BRACKETS = Regex("""\[[^\]]*\]|\{[^}]*\}""")
private val MANY_SPACES = Regex("""\s+""")

/** Quita el año y etiquetas del título para buscarlo: «Dumbo (2019)» → («Dumbo», «2019»). */
fun cleanTitle(raw: String): Pair<String, String?> {
    var t = raw.replace(BRACKETS, " ")
    val m = TRAILING_YEAR.find(t)
    val year = m?.groupValues?.get(1)
    if (m != null) t = t.removeRange(m.range)
    return t.replace(MANY_SPACES, " ").trim(' ', '-', '–', ':', '.') to year
}

/**
 * Calificación del público (votos de usuarios de TMDB, escala 0-10) para películas y series.
 * Necesita una clave gratuita de TMDB (Ajustes). Los resultados se guardan en el equipo, así
 * cada título se consulta una sola vez. Los canales no tienen base pública: solo calificación propia.
 */
class RatingsRepo(private val store: Store) {
    private val memory = ConcurrentHashMap<String, String>()
    private val gate = Semaphore(3)

    val enabled: Boolean get() = store.tmdbKey.isNotBlank()

    suspend fun score(rawTitle: String, kind: RatingKind): Double? {
        if (kind == RatingKind.CHANNEL || !enabled) return null
        val (title, year) = cleanTitle(rawTitle)
        if (title.length < 2) return null
        val key = "${kind.name}|${title.lowercase(Locale.ROOT)}|${year.orEmpty()}"
        memory[key]?.let { return parse(it) }
        store.cachedPublic(key)?.let { memory[key] = it; return parse(it) }
        delay(350) // si el título sale de pantalla antes, la consulta se cancela
        val result = gate.withPermit { withContext(Dispatchers.IO) { fetch(title, year, kind) } }
        if (result != null) { memory[key] = result; store.cachePublic(key, result) }
        return result?.let { parse(it) }
    }

    /** Solo se muestra si hay un mínimo de votos de la gente. */
    private fun parse(v: String): Double? {
        val p = v.split('|')
        if (p.size != 2) return null
        val votes = p[1].toIntOrNull() ?: return null
        return if (votes >= 10) p[0].toDoubleOrNull() else null
    }

    /** «score|votos», «-» si no existe, null si falló la red o la clave (no se guarda). */
    private fun fetch(title: String, year: String?, kind: RatingKind): String? {
        val years: List<String?> = if (year != null) listOf(year, null) else listOf(null)
        for (y in years) {
            val json = request(title, y, kind) ?: return null
            val results = json.optJSONArray("results") ?: continue
            for (i in 0 until minOf(results.length(), 3)) {
                val r = results.getJSONObject(i)
                val votes = r.optInt("vote_count")
                if (votes > 0) return String.format(Locale.US, "%.1f|%d", r.optDouble("vote_average"), votes)
            }
        }
        return "-"
    }

    private fun request(title: String, year: String?, kind: RatingKind): JSONObject? = try {
        val key = store.tmdbKey.trim()
        val bearer = key.startsWith("eyJ")
        val path = if (kind == RatingKind.SERIES) "tv" else "movie"
        val sb = StringBuilder("https://api.themoviedb.org/3/search/$path?include_adult=false&language=es-MX&query=")
            .append(URLEncoder.encode(title, "UTF-8"))
        if (year != null) sb.append(if (kind == RatingKind.SERIES) "&first_air_date_year=$year" else "&year=$year")
        if (!bearer) sb.append("&api_key=").append(URLEncoder.encode(key, "UTF-8"))
        val conn = (URL(sb.toString()).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000
            setRequestProperty("Accept", "application/json")
            if (bearer) setRequestProperty("Authorization", "Bearer $key")
        }
        try {
            if (conn.responseCode == 200) JSONObject(conn.inputStream.bufferedReader().use { it.readText() }) else null
        } finally { conn.disconnect() }
    } catch (_: Exception) { null }
}

/**
 * Nota 0-10 que se muestra: tu calificación (estrellas ×2) si ya calificaste; si no, la del público.
 * Los canales solo muestran la tuya.
 */
@Composable
fun rememberScore(key: String, title: String, kind: RatingKind): Double? {
    val store = LocalStore.current
    val repo = LocalRatings.current
    val mine = store?.userRatings?.get(key)
    val pub by produceState<Double?>(null, key, kind) {
        value = if (repo != null && kind != RatingKind.CHANNEL) repo.score(title, kind) else null
    }
    return if (mine != null) mine * 2.0 else pub
}

@Composable
fun RatingBadge(score: Double, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(5.dp)).background(Color(0xCC000000)).padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Star, null, tint = Cm.Gold, modifier = Modifier.size(11.dp))
        Spacer(Modifier.width(2.dp))
        Text(String.format(Locale.US, "%.1f", score), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

/** Mantener pulsado OK (o pulsación larga): calificar con estrellas y, si aplica, añadir a Mi lista. */
@Composable
fun RateDialog(
    title: String,
    ratingKey: String,
    kind: RatingKind,
    onDismiss: () -> Unit,
    favorite: Boolean? = null,
    onToggleFavorite: (() -> Unit)? = null
) {
    val store = LocalStore.current ?: return
    val repo = LocalRatings.current
    val mine = store.userRatings[ratingKey] ?: 0
    val pub by produceState<Double?>(null, ratingKey, kind) { value = repo?.score(title, kind) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Cm.SurfaceHi,
        titleContentColor = Cm.Text,
        textContentColor = Cm.Text,
        title = { Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("TU CALIFICACIÓN", color = Cm.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..5).forEach { n ->
                        Box(
                            Modifier.size(48.dp).tvClickable(CircleShape, 1.12f) { store.rate(ratingKey, if (n == mine) 0 else n) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (n <= mine) Icons.Filled.Star else Icons.Filled.StarBorder, "$n estrellas",
                                tint = if (n <= mine) Cm.Gold else Cm.Muted, modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }
                Text(
                    when {
                        kind == RatingKind.CHANNEL -> "Los canales no tienen calificación pública: aquí solo cuenta la tuya."
                        repo?.enabled != true -> "Para ver lo que opina el público, agrega tu clave gratuita de TMDB en Ajustes."
                        pub != null -> "Público (TMDB): ★ ${String.format(Locale.US, "%.1f", pub)} / 10"
                        else -> "Sin calificación pública para este título."
                    },
                    color = Cm.Muted, fontSize = 12.sp
                )
                if (favorite != null && onToggleFavorite != null) {
                    GhostButton(
                        if (favorite) "Quitar de mi lista" else "Añadir a mi lista",
                        if (favorite) Icons.Filled.Check else Icons.Filled.Add, onToggleFavorite
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Listo", color = Cm.Gold, fontWeight = FontWeight.Bold) } }
    )
}
