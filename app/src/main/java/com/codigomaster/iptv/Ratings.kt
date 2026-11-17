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
import org.json.JSONArray
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

private fun titleSimilarity(a: String, b: String): Double {
    fun tokens(value: String): Set<String> = java.text.Normalizer.normalize(
        value.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD
    ).replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("""\b(19\d{2}|20\d{2})\b"""), " ")
        .split(Regex("[^a-z0-9]+")).filter { it.length > 1 }.toSet()
    val left = tokens(a)
    val right = tokens(b)
    if (left.isEmpty() || right.isEmpty()) return 0.0
    if (left == right) return 1.0
    val overlap = left.intersect(right).size.toDouble()
    val jaccard = overlap / left.union(right).size.toDouble()
    val containment = overlap / minOf(left.size, right.size).toDouble()
    return maxOf(jaccard, containment * 0.82)
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

    // ───────────── Metadatos para la ficha (sinopsis, director, reparto, fondo) ─────────────
    private val metaMemory = ConcurrentHashMap<String, String>()

    /** Ficha de TMDB (en español). Null si no hay clave, no existe el título o falló la red. */
    suspend fun meta(rawTitle: String, kind: RatingKind): Meta? {
        if (kind == RatingKind.CHANNEL || !enabled) return null
        val (title, year) = cleanTitle(rawTitle)
        if (title.length < 2) return null
        val key = "META|${kind.name}|${title.lowercase(Locale.ROOT)}|${year.orEmpty()}"
        metaMemory[key]?.let { return parseMeta(it) }
        store.cachedPublic(key)?.let { metaMemory[key] = it; return parseMeta(it) }
        delay(300) // si el título sale de pantalla antes, la consulta se cancela
        val result = gate.withPermit { withContext(Dispatchers.IO) { fetchMeta(title, year, kind) } }
        if (result != null) { metaMemory[key] = result; store.cachePublic(key, result) }
        return result?.let { parseMeta(it) }
    }

    private fun parseMeta(v: String): Meta? {
        if (v == "-") return null
        return try {
            val o = JSONObject(v)
            fun list(k: String): List<String> {
                val a = o.optJSONArray(k) ?: return emptyList()
                return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
            }
            Meta(o.optString("o"), list("g"), o.optString("d"), list("c"), o.optString("k"), o.optString("t"), o.optString("n"), o.optString("b"), o.optString("p"), o.optString("h"))
        } catch (_: Exception) { null }
    }

    /** JSON compacto de la ficha, «-» si no existe, null si falló la red (no se guarda). */
    private fun fetchMeta(title: String, year: String?, kind: RatingKind): String? {
        val tv = kind == RatingKind.SERIES
        val years: List<String?> = if (year != null) listOf(year, null) else listOf(null)
        var selected: JSONObject? = null
        for (y in years) {
            val json = request(title, y, kind) ?: return null
            val results = json.optJSONArray("results") ?: continue
            val candidates = (0 until minOf(results.length(), 8)).map { results.getJSONObject(it) }
            val ranked = candidates.map { candidate ->
                val candidateTitle = candidate.str(if (tv) "name" else "title").ifBlank {
                    candidate.str(if (tv) "original_name" else "original_title")
                }
                val similarity = titleSimilarity(title, candidateTitle)
                val candidateYear = candidate.str(if (tv) "first_air_date" else "release_date").take(4)
                val yearBonus = if (y != null && candidateYear == y) 0.20 else 0.0
                candidate to (similarity + yearBonus)
            }.filter { (candidate, score) ->
                val candidateTitle = candidate.str(if (tv) "name" else "title").ifBlank {
                    candidate.str(if (tv) "original_name" else "original_title")
                }
                titleSimilarity(title, candidateTitle) >= 0.88 &&
                    (y == null || candidate.str(if (tv) "first_air_date" else "release_date").take(4).isBlank() ||
                        candidate.str(if (tv) "first_air_date" else "release_date").take(4) == y ||
                        titleSimilarity(title, candidateTitle) >= 0.94)
            }.sortedByDescending { it.second }
            if (ranked.isNotEmpty()) { selected = ranked.first().first; break }
        }
        val id = selected?.optInt("id") ?: 0
        if (id == 0) return "-"
        val d = getJson("https://api.themoviedb.org/3/${if (tv) "tv" else "movie"}/$id?language=es-MX&append_to_response=credits") ?: return null
        val genres = JSONArray()
        d.optJSONArray("genres")?.let { g -> for (i in 0 until g.length()) genres.put(g.getJSONObject(i).str("name")) }
        val credits = d.optJSONObject("credits")
        var director = ""
        if (tv) {
            d.optJSONArray("created_by")?.let { c -> if (c.length() > 0) director = c.getJSONObject(0).str("name") }
        } else {
            credits?.optJSONArray("crew")?.let { c ->
                for (i in 0 until c.length()) {
                    val m = c.getJSONObject(i)
                    if (m.str("job") == "Director") { director = m.str("name"); break }
                }
            }
        }
        val cast = JSONArray()
        credits?.optJSONArray("cast")?.let { c -> for (i in 0 until minOf(c.length(), 3)) cast.put(c.getJSONObject(i).str("name")) }
        val code = if (tv) d.optJSONArray("origin_country")?.optString(0).orEmpty()
        else d.optJSONArray("production_countries")?.optJSONObject(0)?.str("iso_3166_1").orEmpty()
        val country = if (code.isNotBlank()) Locale("", code).getDisplayCountry(Locale("es")) else ""
        val back = d.str("backdrop_path")
        return JSONObject()
            .put("o", d.str("overview")).put("g", genres).put("d", director).put("c", cast).put("k", country)
            .put("t", d.str(if (tv) "first_air_date" else "release_date"))
            .put("n", d.str(if (tv) "original_name" else "original_title"))
            .put("b", if (back.isNotBlank()) "https://image.tmdb.org/t/p/w780$back" else "")
            .put("p", d.str("poster_path").let { path -> if (path.isNotBlank()) "https://image.tmdb.org/t/p/w500$path" else "" })
            .put("h", d.str(if (tv) "name" else "title").ifBlank { d.str(if (tv) "original_name" else "original_title") })
            .toString()
    }

    private fun getJson(url: String): JSONObject? = try {
        val key = store.tmdbKey.trim()
        val bearer = key.startsWith("eyJ")
        val full = if (bearer) url else url + (if (url.contains('?')) "&" else "?") + "api_key=" + URLEncoder.encode(key, "UTF-8")
        val conn = (URL(full).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000
            setRequestProperty("Accept", "application/json")
            if (bearer) setRequestProperty("Authorization", "Bearer $key")
        }
        try {
            if (conn.responseCode == 200) JSONObject(conn.inputStream.bufferedReader().use { it.readText() }) else null
        } finally { conn.disconnect() }
    } catch (_: Exception) { null }

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

/** Ficha ampliada de una película o serie (TMDB). */
data class Meta(
    val overview: String,
    val genres: List<String>,
    val director: String,
    val cast: List<String>,
    val country: String,
    val date: String,
    val original: String,
    val backdrop: String,
    val poster: String = "",
    val displayTitle: String = ""
)

private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k)

/** Ficha de TMDB para un título; null mientras carga o si no hay clave / no se encontró. */
@Composable
fun rememberMeta(title: String, kind: RatingKind): Meta? {
    val repo = LocalRatings.current
    val m by produceState<Meta?>(null, title, kind) {
        value = if (repo != null && kind != RatingKind.CHANNEL) repo.meta(title, kind) else null
    }
    return m
}
