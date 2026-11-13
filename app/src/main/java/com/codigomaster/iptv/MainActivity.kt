package com.codigomaster.iptv

import android.os.Bundle
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

private const val PLAYLIST_URL =
    "https://raw.githubusercontent.com/walter2025hn/MiListaPremium/refs/heads/main/Completa.m3u"

private val Navy = Color(0xFF070B16)
private val Panel = Color(0xFF111A2B)
private val Panel2 = Color(0xFF19263B)
private val Gold = Color(0xFFFFC84A)
private val Blue = Color(0xFF42B9FF)
private val Muted = Color(0xFF9AAEC9)

data class Entry(val name: String, val url: String, val group: String, val logo: String, val tvgId: String)
data class Series(val title: String, val episodes: List<Entry>, val poster: String, val group: String)
enum class Kind { LIVE, MOVIE, SERIES, OTHER }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cm_playlist", MODE_PRIVATE)
        setContent { CodigoMasterApp(prefs) }
    }
}

@Composable
private fun CodigoMasterApp(prefs: SharedPreferences) {
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(readCache(prefs)) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf(if (entries.isEmpty()) "Conectando con tu lista…" else "Lista guardada disponible") }
    var search by remember { mutableStateOf("") }
    var section by remember { mutableStateOf("Inicio") }
    var selectedSeries by remember { mutableStateOf<Series?>(null) }
    var playing by remember { mutableStateOf<Entry?>(null) }

    fun refresh() {
        if (loading) return
        loading = true
        message = "Actualizando contenido desde GitHub…"
        scope.launch {
            try {
                val latest = withContext(Dispatchers.IO) { parseM3u(download(PLAYLIST_URL)) }
                if (latest.isNotEmpty()) {
                    entries = latest
                    saveCache(prefs, latest)
                    message = "Actualizada • ${latest.size} elementos"
                } else message = "La lista llegó vacía; conservamos la versión anterior."
            } catch (_: Exception) {
                message = if (entries.isNotEmpty()) "Sin conexión • mostrando última lista guardada"
                          else "No se pudo cargar la lista. Comprueba tu conexión."
            } finally { loading = false }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val movies = remember(entries) { entries.filter { classify(it) == Kind.MOVIE } }
    val live = remember(entries) { entries.filter { classify(it) == Kind.LIVE } }
    val series = remember(entries) { buildSeries(entries) }
    val q = search.trim()
    val filteredLive = live.filter { it.name.contains(q, true) || it.group.contains(q, true) }
    val filteredMovies = movies.filter { it.name.contains(q, true) || it.group.contains(q, true) }
    val filteredSeries = series.filter { it.title.contains(q, true) || it.episodes.any { ep -> ep.name.contains(q, true) } }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Gold, secondary = Blue, background = Navy, surface = Panel,
        onBackground = Color.White, onSurface = Color.White
    )) {
        Surface(Modifier.fillMaxSize(), color = Navy) {
            Column(Modifier.fillMaxSize()) {
                Header(loading, onRefresh = { refresh() })
                if (playing != null) {
                    PlayerViewScreen(playing!!, onBack = { playing = null })
                } else if (selectedSeries != null) {
                    SeriesDetail(selectedSeries!!, onBack = { selectedSeries = null }, onPlay = { playing = it })
                } else {
                    SearchBox(search, { search = it })
                    Spacer(Modifier.height(12.dp))
                    Navigation(section) { section = it }
                    Text(message, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
                    when (section) {
                        "TV" -> MediaList(filteredLive, "Canales en vivo", "No se encontraron canales", onPlay = { playing = it })
                        "Películas" -> PosterGrid(filteredMovies, "Películas", onPlay = { playing = it })
                        "Series" -> SeriesGrid(filteredSeries, onSelect = { selectedSeries = it })
                        else -> HomeContent(
                            live = live, movies = movies, series = series,
                            onSection = { section = it }, onPlay = { playing = it },
                            onSeries = { selectedSeries = it }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(loading: Boolean, onRefresh: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color(0xFF111B31), Navy)))
            .padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(painterResource(R.drawable.brand_logo), null, Modifier.size(58.dp).clip(RoundedCornerShape(13.dp)), contentScale = ContentScale.Crop)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("CODIGO MASTER", color = Gold, fontWeight = FontWeight.Black, fontSize = 19.sp, letterSpacing = 1.sp)
            Text("IPTV  •  TU ENTRETENIMIENTO", color = Muted, fontSize = 10.sp, letterSpacing = 1.3.sp)
        }
        IconButton(onClick = onRefresh, enabled = !loading) {
            if (loading) CircularProgressIndicator(Modifier.size(22.dp), color = Gold, strokeWidth = 2.dp)
            else Icon(Icons.Default.Refresh, "Actualizar lista", tint = Gold)
        }
    }
}

@Composable
private fun SearchBox(value: String, onValue: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(15.dp))
            .background(Panel2).padding(horizontal = 15.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, null, tint = Muted)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Buscar canales, películas o series…", color = Muted)
            BasicTextField(value, onValue, singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 15.sp), modifier = Modifier.fillMaxWidth())
        }
        if (value.isNotEmpty()) Icon(Icons.Default.Close, "Borrar", tint = Muted, modifier = Modifier.clickable { onValue("") })
    }
}

@Composable
private fun Navigation(current: String, onSelect: (String) -> Unit) {
    val tabs = listOf("Inicio" to Icons.Default.Home, "TV" to Icons.Default.LiveTv, "Películas" to Icons.Default.Movie, "Series" to Icons.Default.Tv)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tabs.forEach { (name, icon) ->
            val active = current == name
            Row(
                Modifier.clip(RoundedCornerShape(12.dp))
                    .background(if (active) Gold else Panel)
                    .clickable { onSelect(name) }.padding(horizontal = 15.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, null, tint = if (active) Navy else Muted, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
                Text(name, color = if (active) Navy else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun HomeContent(
    live: List<Entry>, movies: List<Entry>, series: List<Series>,
    onSection: (String) -> Unit, onPlay: (Entry) -> Unit, onSeries: (Series) -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(166.dp).clip(RoundedCornerShape(20.dp))) {
                Image(painterResource(R.drawable.brand_logo), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xDD050914), Color(0x22050914)))))
                Column(Modifier.align(Alignment.CenterStart).padding(18.dp)) {
                    Text("EL PODER DEL ENTRETENIMIENTO", color = Gold, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text("Todo tu contenido\n en un solo lugar", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black, lineHeight = 25.sp)
                    Text("Canales • Películas • Series", color = Color(0xFFE2EAF7), fontSize = 11.sp)
                }
            }
        }
        item { SectionHeader("Canales en vivo", live.size, "TV") { onSection("TV") } }
        item { EntryRail(live.take(12), onPlay) }
        item { SectionHeader("Películas", movies.size, "Ver todas") { onSection("Películas") } }
        item { EntryRail(movies.take(12), onPlay) }
        item { SectionHeader("Series", series.size, "Ver todas") { onSection("Series") } }
        item { SeriesRail(series.take(12), onSeries) }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("$count disponibles", color = Muted, fontSize = 11.sp)
        }
        Text(action, color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onClick() }.padding(8.dp))
    }
}

@Composable
private fun EntryRail(items: List<Entry>, onPlay: (Entry) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        items(items) { entry ->
            Column(Modifier.width(118.dp).clickable { onPlay(entry) }) {
                Poster(entry.logo, Modifier.fillMaxWidth().height(142.dp))
                Spacer(Modifier.height(7.dp))
                Text(entry.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(entry.group, color = Muted, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SeriesRail(items: List<Series>, onSelect: (Series) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        items(items) { s ->
            Column(Modifier.width(118.dp).clickable { onSelect(s) }) {
                Poster(s.poster, Modifier.fillMaxWidth().height(142.dp))
                Spacer(Modifier.height(7.dp))
                Text(s.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Text("${s.episodes.size} episodios", color = Muted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun Poster(url: String, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Panel2), contentAlignment = Alignment.Center) {
        if (url.isNotBlank()) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Default.ConfirmationNumber, null, tint = Gold, modifier = Modifier.size(35.dp))
    }
}

@Composable
private fun MediaList(items: List<Entry>, title: String, empty: String, onPlay: (Entry) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
        if (items.isEmpty()) EmptyState(empty)
        else LazyColumn(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items(items) { e -> MediaRow(e.name, e.group, e.logo, onClick = { onPlay(e) }) }
        }
    }
}

@Composable
private fun PosterGrid(items: List<Entry>, title: String, onPlay: (Entry) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text("$title  •  ${items.size}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
        if (items.isEmpty()) EmptyState("No se encontraron películas")
        else LazyColumn(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items(items) { e -> MediaRow(e.name, e.group, e.logo, onClick = { onPlay(e) }) }
        }
    }
}

@Composable
private fun SeriesGrid(items: List<Series>, onSelect: (Series) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text("Series  •  ${items.size}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
        if (items.isEmpty()) EmptyState("No se encontraron series")
        else LazyColumn(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items(items) { s -> MediaRow(s.title, "${s.episodes.size} episodios", s.poster, onClick = { onSelect(s) }) }
        }
    }
}

@Composable
private fun MediaRow(title: String, subtitle: String, logo: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(Panel)
            .clickable { onClick() }.padding(11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Poster(logo, Modifier.size(width = 62.dp, height = 78.dp))
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Muted, fontSize = 11.sp, maxLines = 1)
        }
        Icon(Icons.Default.PlayCircleFilled, null, tint = Gold, modifier = Modifier.size(27.dp))
    }
}

@Composable
private fun SeriesDetail(series: Series, onBack: () -> Unit, onPlay: (Entry) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().clickable { onBack() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ArrowBack, null, tint = Gold)
            Spacer(Modifier.width(8.dp))
            Text("Volver a series", color = Gold, fontWeight = FontWeight.SemiBold)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Poster(series.poster, Modifier.size(width = 105.dp, height = 145.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text(series.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
                Text("${series.episodes.size} episodios", color = Muted, fontSize = 12.sp)
                Text(series.group, color = Blue, fontSize = 11.sp)
            }
        }
        Text("EPISODIOS", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, modifier = Modifier.padding(18.dp))
        LazyColumn(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(series.episodes) { e -> MediaRow(e.name, e.group, e.logo.ifBlank { series.poster }, onClick = { onPlay(e) }) }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(Modifier.fillMaxSize().padding(25.dp), contentAlignment = Alignment.Center) {
        Text(message, color = Muted)
    }
}

@Composable
private fun PlayerViewScreen(entry: Entry, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(entry.url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(entry.url))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.clickable { onBack() }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ArrowBack, null, tint = Gold)
            Text("  Volver", color = Gold)
        }
        AndroidView(factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } }, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        Spacer(Modifier.height(12.dp))
        Text(entry.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(entry.group, color = Muted)
    }
}

private fun classify(e: Entry): Kind {
    val group = e.group.lowercase(Locale.ROOT)
    val name = e.name.lowercase(Locale.ROOT)
    val combined = "$group $name"
    val seriesPattern = Regex("""(?i)(s\d{1,2}\s*e\d{1,2}|temporada\s*\d+|cap[ií]tulo\s*\d+|episodio\s*\d+)""")
    if (seriesPattern.containsMatchIn(e.name) || group.contains("serie") || group.contains("series")) return Kind.SERIES
    if (listOf("pelicula", "película", "peliculas", "movies", "movie", "vod", "cine", "films").any { group.contains(it) }) return Kind.MOVIE
    if (listOf("live", "tv", "canal", "channels", "deportes", "noticias", "infantil", "musica", "música").any { group.contains(it) }) return Kind.LIVE
    if (e.tvgId.isNotBlank() || group.contains("general") || group.contains("nacional")) return Kind.LIVE
    if (Regex("""(?i)\b(19|20)\d{2}\b""").containsMatchIn(name) &&
        listOf("1080p", "720p", "4k", "bluray", "web-dl", "hdrip").any { name.contains(it) }) return Kind.MOVIE
    return Kind.OTHER
}

private fun buildSeries(entries: List<Entry>): List<Series> {
    val episodeRegex = Regex("""(?i)^(.*?)[ ._\-]*(S\d{1,2})[ ._\-]*(E\d{1,2})(.*)$""")
    val groups = linkedMapOf<String, MutableList<Entry>>()
    for (entry in entries) {
        if (classify(entry) != Kind.SERIES) continue
        val match = episodeRegex.find(entry.name)
        val title = if (match != null) match.groupValues[1]
            .replace(Regex("""[._\-]+$"""), "").trim()
            .ifBlank { entry.name.trim() }
            else entry.name.trim()
        val key = title.lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ")
        groups.getOrPut(key) { mutableListOf() }.add(entry)
    }
    // Group repeated non-episode series titles as one show, without splitting them into duplicate cards.
    return groups.map { (_, eps) ->
        val sorted = eps.sortedWith(compareBy<Entry>({ episodeNumber(it.name).first }, { episodeNumber(it.name).second }, { it.name }))
        val first = sorted.first()
        val title = episodeRegex.find(first.name)?.groupValues?.get(1)
            ?.replace(Regex("""[._\-]+$"""), "")?.trim()?.ifBlank { first.name } ?: first.name
        Series(title, sorted, sorted.firstOrNull { it.logo.isNotBlank() }?.logo.orEmpty(), first.group)
    }.sortedBy { it.title.lowercase(Locale.ROOT) }
}

private fun episodeNumber(name: String): Pair<Int, Int> {
    val m = Regex("""(?i)S(\d{1,2})\s*E(\d{1,2})""").find(name)
    return if (m != null) (m.groupValues[1].toIntOrNull() ?: 0) to (m.groupValues[2].toIntOrNull() ?: 0) else 0 to 0
}

private fun download(url: String): String {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000
        readTimeout = 25000
        setRequestProperty("User-Agent", "CodigoMasterIPTV/1.1")
        setRequestProperty("Cache-Control", "no-cache")
    }
    return try { conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } }
    finally { conn.disconnect() }
}

private fun parseM3u(text: String): List<Entry> {
    val result = mutableListOf<Entry>()
    var name = ""
    var group = ""
    var logo = ""
    var tvgId = ""
    for (raw in text.removePrefix("\uFEFF").lines()) {
        val line = raw.trim()
        if (line.startsWith("#EXTINF", true)) {
            name = line.substringAfterLast(',').trim().ifBlank { "Sin título" }
            group = Regex("""(?i)group-title="([^"]*)"""").find(line)?.groupValues?.get(1).orEmpty()
            logo = Regex("""(?i)tvg-logo="([^"]*)"""").find(line)?.groupValues?.get(1).orEmpty()
            tvgId = Regex("""(?i)tvg-id="([^"]*)"""").find(line)?.groupValues?.get(1).orEmpty()
        } else if (line.startsWith("http://") || line.startsWith("https://")) {
            if (name.isNotBlank()) result.add(Entry(name, line, group.ifBlank { "Sin categoría" }, logo, tvgId))
            name = ""; group = ""; logo = ""; tvgId = ""
        }
    }
    return result.distinctBy { it.url }
}

private fun saveCache(prefs: SharedPreferences, entries: List<Entry>) {
    val arr = JSONArray()
    entries.forEach { e ->
        arr.put(JSONObject().apply {
            put("name", e.name); put("url", e.url); put("group", e.group)
            put("logo", e.logo); put("tvgId", e.tvgId)
        })
    }
    prefs.edit().putString("playlist", arr.toString()).apply()
}

private fun readCache(prefs: SharedPreferences): List<Entry> = try {
    val arr = JSONArray(prefs.getString("playlist", "[]"))
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        Entry(o.optString("name"), o.optString("url"), o.optString("group"), o.optString("logo"), o.optString("tvgId"))
    }
} catch (_: Exception) { emptyList() }
