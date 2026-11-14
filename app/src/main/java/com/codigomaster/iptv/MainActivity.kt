package com.codigomaster.iptv

import android.os.Bundle
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import androidx.compose.ui.focus.onFocusChanged

private const val PLAYLIST_URL =
    "https://raw.githubusercontent.com/walter2025hn/MiListaPremium/refs/heads/main/Completa.m3u"

private val Navy = Color(0xFF070B16)
private val Panel = Color(0xFF111A2B)
private val Panel2 = Color(0xFF19263B)
private val Gold = Color(0xFFFFC84A)
private val Blue = Color(0xFF42B9FF)
private val Muted = Color(0xFF9AAEC9)

data class Entry(val name: String, val url: String, val group: String, val logo: String, val tvgId: String, val attributes: String = "")
data class Series(val title: String, val episodes: List<Entry>, val poster: String, val group: String)
data class Catalog(val live: List<Entry>, val movies: List<Entry>, val series: List<Series>)
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
    var entries by remember { mutableStateOf(emptyList<Entry>()) }
    var catalog by remember { mutableStateOf(Catalog(emptyList(), emptyList(), emptyList())) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Preparando tu contenido…") }
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
                    withContext(Dispatchers.IO) { saveCache(prefs, latest) }
                    entries = latest
                    message = "Actualizada • ${latest.size} elementos"
                } else message = "La lista llegó vacía; conservamos la versión anterior."
            } catch (_: Exception) {
                message = if (entries.isNotEmpty()) "Sin conexión • mostrando última lista guardada"
                          else "No se pudo cargar la lista. Comprueba tu conexión."
            } finally { loading = false }
        }
    }

    LaunchedEffect(Unit) {
        val cached = withContext(Dispatchers.IO) { readCache(prefs) }
        if (cached.isNotEmpty()) {
            entries = cached
            message = "Contenido guardado • ${cached.size} elementos"
        }
        refresh()
    }

    LaunchedEffect(entries) {
        catalog = withContext(Dispatchers.Default) { buildCatalog(entries) }
    }
    val movies = catalog.movies
    val live = catalog.live
    val series = catalog.series
    val q = search.trim()
    val filteredLive = remember(live, q) { if (q.isBlank()) live else live.filter { it.name.contains(q, true) || it.group.contains(q, true) } }
    val filteredMovies = remember(movies, q) { if (q.isBlank()) movies else movies.filter { it.name.contains(q, true) || it.group.contains(q, true) } }
    val filteredSeries = remember(series, q) { if (q.isBlank()) series else series.filter { it.title.contains(q, true) || it.episodes.any { ep -> ep.name.contains(q, true) } } }

    BackHandler(enabled = playing != null || selectedSeries != null) {
        if (playing != null) playing = null else selectedSeries = null
    }

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
                    Spacer(Modifier.height(7.dp))
                    Navigation(section) { section = it }
                    Text(message, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
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
    val wideScreen = LocalConfiguration.current.screenWidthDp >= 700
    Row(
        Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color(0xFF111B31), Navy)))
            .padding(start = 14.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(painterResource(R.drawable.brand_logo), null, Modifier.size(if (wideScreen) 48.dp else 38.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("CODIGO MASTER", color = Gold, fontWeight = FontWeight.Black, fontSize = if (wideScreen) 20.sp else 15.sp, letterSpacing = 0.7.sp)
            Text("IPTV", color = Muted, fontSize = 8.sp, letterSpacing = 1.1.sp)
        }
        IconButton(onClick = onRefresh, enabled = !loading) {
            if (loading) CircularProgressIndicator(Modifier.size(22.dp), color = Gold, strokeWidth = 2.dp)
            else Icon(Icons.Default.Refresh, "Actualizar lista", tint = Gold)
        }
    }
}

@Composable
private fun SearchBox(value: String, onValue: (String) -> Unit) {
    val wideScreen = LocalConfiguration.current.screenWidthDp >= 700
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(15.dp))
            .background(Panel2).padding(horizontal = 13.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, null, tint = Muted)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Buscar canales, películas o series…", color = Muted, fontSize = if (wideScreen) 19.sp else 15.sp)
            BasicTextField(value, onValue, singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = if (wideScreen) 19.sp else 15.sp), modifier = Modifier.fillMaxWidth())
        }
        if (value.isNotEmpty()) Icon(Icons.Default.Close, "Borrar", tint = Muted, modifier = Modifier.clickable { onValue("") })
    }
}

@Composable
private fun Navigation(current: String, onSelect: (String) -> Unit) {
    val wideScreen = LocalConfiguration.current.screenWidthDp >= 700
    val tabs = listOf("Inicio" to Icons.Default.Home, "TV" to Icons.Default.LiveTv, "Películas" to Icons.Default.Movie, "Series" to Icons.Default.Tv)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tabs.forEach { (name, icon) ->
            val active = current == name
            var focused by remember(name) { mutableStateOf(false) }
            Row(
                Modifier.widthIn(min = 82.dp).clip(RoundedCornerShape(15.dp))
                    .background(if (active) Gold else Panel)
                    .border(if (focused) 3.dp else 1.dp, if (focused) Color.White else if (active) Gold else Color(0xFF25344C), RoundedCornerShape(15.dp))
                    .onFocusChanged { focused = it.isFocused }
                    .clickable { onSelect(name) }.focusable()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(icon, null, tint = if (active) Navy else Muted, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(name, color = if (active) Navy else Color.White, fontWeight = FontWeight.Bold, fontSize = if (wideScreen) 16.sp else 12.sp, maxLines = 1)
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
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 118.dp, max = 166.dp).clip(RoundedCornerShape(20.dp))) {
                Image(painterResource(R.drawable.brand_logo), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xDD050914), Color(0x22050914)))))
                Column(Modifier.align(Alignment.CenterStart).padding(18.dp)) {
                    Text("TU CINE · TU TV · TU MUNDO", color = Gold, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text("Entretenimiento\nsin límites", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Black, lineHeight = 27.sp)
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
        items(items, key = { it.url }) { entry ->
            var focused by remember(entry.url) { mutableStateOf(false) }
            Column(Modifier.width(124.dp).graphicsLayer { scaleX = if (focused) 1.035f else 1f; scaleY = if (focused) 1.035f else 1f }.border(if (focused) 2.dp else 0.dp, if (focused) Gold else Color.Transparent, RoundedCornerShape(13.dp)).onFocusChanged { focused = it.isFocused }.clickable { onPlay(entry) }.focusable().padding(3.dp)) {
                Poster(entry.logo, Modifier.fillMaxWidth().height(148.dp))
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
        items(items, key = { it.title }) { s ->
            var focused by remember(s.title) { mutableStateOf(false) }
            Column(Modifier.width(124.dp).graphicsLayer { scaleX = if (focused) 1.035f else 1f; scaleY = if (focused) 1.035f else 1f }.border(if (focused) 2.dp else 0.dp, if (focused) Gold else Color.Transparent, RoundedCornerShape(13.dp)).onFocusChanged { focused = it.isFocused }.clickable { onSelect(s) }.focusable().padding(3.dp)) {
                Poster(s.poster, Modifier.fillMaxWidth().height(148.dp))
                Spacer(Modifier.height(7.dp))
                Text(s.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Text("${s.episodes.size} episodios", color = Muted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun Poster(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val request = remember(url) {
        ImageRequest.Builder(context)
            .data(url.takeIf { it.startsWith("http://") || it.startsWith("https://") })
            .crossfade(160)
            .allowHardware(true)
            .build()
    }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Panel2), contentAlignment = Alignment.Center) {
        if (url.isNotBlank()) AsyncImage(model = request, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Default.ConfirmationNumber, null, tint = Gold, modifier = Modifier.size(35.dp))
    }
}

@Composable
private fun MediaList(items: List<Entry>, title: String, empty: String, onPlay: (Entry) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
        if (items.isEmpty()) EmptyState(empty)
        else LazyColumn(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items(items, key = { it.url }) { e -> MediaRow(e.name, e.group, e.logo, onClick = { onPlay(e) }) }
        }
    }
}

@Composable
private fun PosterGrid(items: List<Entry>, title: String, onPlay: (Entry) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text("$title  •  ${items.size}", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
        if (items.isEmpty()) EmptyState("No se encontraron películas")
        else LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = if (LocalConfiguration.current.screenWidthDp >= 600) 190.dp else 145.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            gridItems(items, key = { it.url }) { e ->
                var focused by remember(e.url) { mutableStateOf(false) }
                Column(Modifier.fillMaxWidth().border(if (focused) 3.dp else 1.dp, if (focused) Gold else Color(0xFF1B2940), RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(Panel).onFocusChanged { focused = it.isFocused }.clickable { onPlay(e) }.focusable().padding(9.dp)) {
                    Poster(e.logo, Modifier.fillMaxWidth().aspectRatio(0.70f))
                    Spacer(Modifier.height(8.dp))
                    Text(e.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, minLines = 2)
                    Text(e.group, color = Muted, fontSize = 10.sp, maxLines = 1)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Icon(Icons.Default.PlayCircleFilled, null, tint = Gold, modifier = Modifier.size(25.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesGrid(items: List<Series>, onSelect: (Series) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text("Series  •  ${items.size}", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
        if (items.isEmpty()) EmptyState("No se encontraron series")
        else LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = if (LocalConfiguration.current.screenWidthDp >= 600) 190.dp else 145.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            gridItems(items, key = { it.title }) { series ->
                var focused by remember(series.title) { mutableStateOf(false) }
                Column(Modifier.fillMaxWidth().border(if (focused) 3.dp else 1.dp, if (focused) Gold else Color(0xFF1B2940), RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(Panel).onFocusChanged { focused = it.isFocused }.clickable { onSelect(series) }.focusable().padding(9.dp)) {
                    Poster(series.poster, Modifier.fillMaxWidth().aspectRatio(0.70f))
                    Spacer(Modifier.height(8.dp))
                    Text(series.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, minLines = 2)
                    Text("${series.episodes.size} episodios", color = Muted, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun MediaRow(title: String, subtitle: String, logo: String, onClick: () -> Unit) {
    var focused by remember(title, logo) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().border(if (focused) 3.dp else 1.dp, if (focused) Gold else Color(0xFF1B2940), RoundedCornerShape(15.dp)).clip(RoundedCornerShape(15.dp)).background(Panel)
            .onFocusChanged { focused = it.isFocused }.clickable { onClick() }.focusable().padding(11.dp),
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
            items(series.episodes, key = { it.url }) { e -> MediaRow(e.name, e.group, e.logo.ifBlank { series.poster }, onClick = { onPlay(e) }) }
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

private fun buildCatalog(entries: List<Entry>): Catalog {
    val live = ArrayList<Entry>()
    val movies = ArrayList<Entry>()
    for (entry in entries) {
        when (classify(entry)) {
            Kind.LIVE -> live.add(entry)
            Kind.MOVIE -> movies.add(entry)
            else -> Unit
        }
    }
    return Catalog(live, movies, buildSeries(entries))
}

private fun classify(e: Entry): Kind {
    val name = normalize(e.name)
    val group = normalize(e.group)
    val attrs = normalize(e.attributes)
    val url = e.url.lowercase(Locale.ROOT)

    // The provider's URL paths are the most reliable signal in this playlist.
    if (url.contains("/series/")) return Kind.SERIES
    if (url.contains("/movie/") || url.contains("/movies/")) return Kind.MOVIE

    // Episodic markers are the strongest signal and take priority over generic group names.
    val episodePattern = Regex("""\b(s\d{1,2}\s*e\d{1,3}|\d{1,2}x\d{1,3}|t\d{1,2}\s*e\d{1,3}|temporada\s*\d+|capitulo\s*\d+|episodio\s*\d+)\b""")
    if (episodePattern.containsMatchIn(name) || listOf("serie", "series", "tv shows", "temporadas").any { group.contains(it) }) return Kind.SERIES

    // Explicit VOD/movie metadata and movie-specific categories.
    val movieGroups = listOf("pelicula", "peliculas", "movies", "movie", "vod", "cine", "films", "film", "estrenos")
    val movieAttrs = listOf("type=movie", "tvg-type=movie", "content-type=movie", "media-type=movie", "vod=true")
    if (movieGroups.any { group.contains(it) } || movieAttrs.any { attrs.contains(it) }) return Kind.MOVIE

    val year = Regex("""\b(19\d{2}|20\d{2})\b""").containsMatchIn(name)
    val quality = listOf("1080p", "720p", "2160p", "4k", "bluray", "blu-ray", "web-dl", "webrip", "hdrip", "hdtv", "hevc", "x264", "x265").any { name.contains(it) || url.contains(it) }
    val movieWords = listOf(
        "la comunidad del anillo", "las dos torres", "el retorno del rey", "la guerra de los rohirrim", "el hobbit",
        "avengers", "spider-man", "spiderman", "batman", "superman", "jurassic", "harry potter", "rapidos y furiosos",
        "mision imposible", "toy story", "shrek", "avatar", "matrix", "gladiador", "titanic", "oppenheimer",
        "barbie", "john wick", "el conjuro", "insidious", "sonic", "kung fu panda", "intensamente", "inside out",
        "moana", "encanto", "coco", "minions", "deadpool", "venom", "transformers", "piratas del caribe"
    )
    val genreGroups = listOf("fantasia", "accion", "aventura", "drama", "comedia", "terror", "animacion", "romance", "ciencia ficcion", "documental", "suspenso", "thriller", "anime", "familia")
    val hasGenre = genreGroups.any { group.contains(it) }
    val explicitLiveGroup = listOf("canales", "canal", "en vivo", "live", "deportes en vivo", "noticias en vivo", "radio", "iptv live").any { group.contains(it) }
    val channelName = listOf("espn", "fox sports", "discovery", "national geographic", "cnn", "bbc", "televisa", "telemundo", "univision", "hbo live", "cartoon network", "nickelodeon", "animal planet", "history channel", "tnt sports").any { name.startsWith(it) }

    val urlLooksVod = listOf("/vod/", "/peliculas/", "/film/", "/films/", "/movie/", "/movies/").any { url.contains(it) }
    val groupLooksVod = listOf("pelicula", "peliculas", "movie", "movies", "vod", "cine", "films", "estrenos").any { group.contains(it) }
    // Los metadatos y la ruta VOD tienen prioridad sobre un grupo genérico llamado TV.
    if (urlLooksVod || groupLooksVod) return Kind.MOVIE
    if (!explicitLiveGroup && !channelName && (movieWords.any { name.contains(it) } || (year && (hasGenre || quality)))) return Kind.MOVIE

    // A generic TV/General group is not enough to label a title as a live channel.
    val explicitChannelGroup = listOf("canales", "canal", "live", "en vivo", "deportes", "noticias", "radio", "musica", "musical", "infantil").any { group.contains(it) }
    val streamLooksLive = url.contains(".m3u8") || url.contains(".ts")
    val livePath = listOf("/live/", "/channel/", "/channels/").any { url.contains(it) }
    if (explicitLiveGroup || channelName || explicitChannelGroup || (livePath && streamLooksLive) || (e.tvgId.isNotBlank() && !year && streamLooksLive && !hasGenre)) return Kind.LIVE
    if (streamLooksLive && !year) return Kind.LIVE
    return Kind.OTHER
}

private fun normalize(value: String): String = java.text.Normalizer.normalize(value.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
    .replace(Regex("\\p{Mn}+"), "")

private fun buildSeries(entries: List<Entry>): List<Series> {
    val episodeRegex = Regex("""(?i)^(.*?)[ ._\-]*(?:S\d{1,2}[ ._\-]*E\d{1,3}|\d{1,2}x\d{1,3}|T\d{1,2}[ ._\-]*E\d{1,3})(.*)$""")
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
    val sxe = Regex("""(?i)S(\d{1,2})\s*E(\d{1,3})""").find(name)
    if (sxe != null) return (sxe.groupValues[1].toIntOrNull() ?: 0) to (sxe.groupValues[2].toIntOrNull() ?: 0)
    val x = Regex("""(?i)(\d{1,2})x(\d{1,3})""").find(name)
    if (x != null) return (x.groupValues[1].toIntOrNull() ?: 0) to (x.groupValues[2].toIntOrNull() ?: 0)
    val te = Regex("""(?i)T(\d{1,2})\s*E(\d{1,3})""").find(name)
    if (te != null) return (te.groupValues[1].toIntOrNull() ?: 0) to (te.groupValues[2].toIntOrNull() ?: 0)
    return 0 to 0
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
    var attributes = ""
    for (raw in text.removePrefix("\uFEFF").lines()) {
        val line = raw.trim()
        if (line.startsWith("#EXTINF", true)) {
            name = line.substringAfterLast(',').trim().ifBlank { "Sin título" }
            group = Regex("""(?i)group-title="([^"]*)"""").find(line)?.groupValues?.get(1).orEmpty()
            logo = Regex("""(?i)tvg-logo="([^"]*)"""").find(line)?.groupValues?.get(1).orEmpty()
            tvgId = Regex("""(?i)tvg-id="([^"]*)"""").find(line)?.groupValues?.get(1).orEmpty()
            attributes = line.substringBeforeLast(",", line)
        } else if (line.startsWith("http://") || line.startsWith("https://")) {
            val placeholder = name.startsWith("Nombre de la Película", true) || line.contains("servidor.com", true)
            if (name.isNotBlank() && !placeholder) result.add(Entry(name, line, group.ifBlank { "Sin categoría" }, logo, tvgId, attributes))
            name = ""; group = ""; logo = ""; tvgId = ""; attributes = ""
        }
    }
    return result.distinctBy { it.url }
}

private fun saveCache(prefs: SharedPreferences, entries: List<Entry>) {
    val arr = JSONArray()
    entries.forEach { e ->
        arr.put(JSONObject().apply {
            put("name", e.name); put("url", e.url); put("group", e.group)
            put("logo", e.logo); put("tvgId", e.tvgId); put("attributes", e.attributes)
        })
    }
    prefs.edit().putString("playlist", arr.toString()).apply()
}

private fun readCache(prefs: SharedPreferences): List<Entry> = try {
    val arr = JSONArray(prefs.getString("playlist", "[]"))
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        Entry(o.optString("name"), o.optString("url"), o.optString("group"), o.optString("logo"), o.optString("tvgId"), o.optString("attributes"))
    }
} catch (_: Exception) { emptyList() }
