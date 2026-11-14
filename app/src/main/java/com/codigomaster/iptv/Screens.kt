package com.codigomaster.iptv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay

// ───────────────────────────── Inicio ─────────────────────────────

private data class HeroItem(val title: String, val tag: String, val subtitle: String, val image: String, val play: () -> Unit, val favKey: String)

@Composable
fun HomeScreen(
    catalog: Catalog,
    continueWatching: List<Entry>,
    store: Store,
    favorites: Set<String>,
    wide: Boolean,
    onSection: (Section) -> Unit,
    onPlay: (PlayRequest) -> Unit,
    onSeries: (Series) -> Unit,
    onToggleFavorite: (String) -> Unit
) {
    val cardWidth = if (wide) 150.dp else 114.dp
    val hero = remember(catalog) {
        val movies = catalog.movies.filter { it.logo.startsWith("http") }.take(4)
        val series = catalog.series.filter { it.poster.startsWith("http") }.take(3)
        val out = mutableListOf<HeroItem>()
        val m = movies.iterator(); val s = series.iterator()
        while (m.hasNext() || s.hasNext()) {
            if (m.hasNext()) m.next().let { e ->
                out += HeroItem(e.name, "PELÍCULA", e.group, e.logo, { onPlay(PlayRequest(listOf(e), 0, false)) }, e.url)
            }
            if (s.hasNext()) s.next().let { se ->
                out += HeroItem(se.title, "SERIE", "${se.episodes.size} episodios · ${se.group}", se.poster, { onSeries(se) }, se.key)
            }
        }
        out
    }
    val genreRails = remember(catalog.movies) {
        catalog.movies.groupBy { it.group }.entries.filter { it.value.size >= 6 && it.key != "Sin categoría" }
            .sortedByDescending { it.value.size }.take(4).map { it.key to it.value.take(20) }
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        if (hero.isNotEmpty()) item(key = "hero") { Hero(hero, wide, favorites, onToggleFavorite) }
        else item(key = "welcome") { WelcomeBanner() }

        if (continueWatching.isNotEmpty()) {
            item(key = "continue") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle("Continuar viendo")
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(continueWatching, key = { it.url }) { e ->
                            PosterCard(e.name, e.group, e.logo, Modifier.width(cardWidth), badge = "SEGUIR") {
                                onPlay(PlayRequest(listOf(e), 0, false))
                            }
                        }
                    }
                }
            }
        }
        if (catalog.live.isNotEmpty()) item(key = "live") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("TV en vivo", "${catalog.live.size} canales", "Ver todos") { onSection(Section.LIVE) }
                val rail = catalog.live.take(24)
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexedRail(rail) { i, e -> ChannelCard(e, Modifier.width(if (wide) 210.dp else 160.dp)) { onPlay(PlayRequest(rail, i, true)) } }
                }
            }
        }
        if (catalog.movies.isNotEmpty()) item(key = "movies") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("Películas", "${catalog.movies.size} títulos", "Ver todas") { onSection(Section.MOVIES) }
                MovieRail(catalog.movies.take(24), cardWidth, onPlay)
            }
        }
        genreRails.forEach { (genre, list) ->
            item(key = "genre-$genre") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle(genre)
                    MovieRail(list, cardWidth, onPlay)
                }
            }
        }
        if (catalog.series.isNotEmpty()) item(key = "series") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("Series", "${catalog.series.size} series", "Ver todas") { onSection(Section.SERIES) }
                SeriesRail(catalog.series.take(24), cardWidth, onSeries)
            }
        }
    }
}

private fun <T> LazyListScope.itemsIndexedRail(list: List<T>, content: @Composable (Int, T) -> Unit) {
    items(list.size) { i -> content(i, list[i]) }
}

@Composable
private fun MovieRail(list: List<Entry>, cardWidth: androidx.compose.ui.unit.Dp, onPlay: (PlayRequest) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(list, key = { it.url }) { e ->
            PosterCard(e.name, e.group, e.logo, Modifier.width(cardWidth)) { onPlay(PlayRequest(listOf(e), 0, false)) }
        }
    }
}

@Composable
fun SeriesRail(list: List<Series>, cardWidth: androidx.compose.ui.unit.Dp, onSeries: (Series) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(list, key = { it.key }) { s ->
            PosterCard(s.title, "${s.episodes.size} episodios", s.poster, Modifier.width(cardWidth), badge = "SERIE") { onSeries(s) }
        }
    }
}

@Composable
private fun WelcomeBanner() {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(150.dp).clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF16254A), Color(0xFF0A1020))))
    ) {
        Column(Modifier.align(Alignment.CenterStart).padding(22.dp)) {
            Text("TU CINE · TU TV · TU MUNDO", color = Cm.Gold, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            Spacer(Modifier.height(6.dp))
            Text("Entretenimiento\nsin límites", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black, lineHeight = 29.sp)
        }
    }
}

@Composable
private fun Hero(items: List<HeroItem>, wide: Boolean, favorites: Set<String>, onToggleFavorite: (String) -> Unit) {
    val pager = rememberPagerState { items.size }
    LaunchedEffect(items.size) {
        while (items.size > 1) {
            delay(7000)
            pager.animateScrollToPage((pager.currentPage + 1) % items.size)
        }
    }
    Box {
        HorizontalPager(pager) { page ->
            val item = items[page]
            val height = if (wide) 360.dp else 270.dp
            Box(Modifier.fillMaxWidth().height(height)) {
                Poster(item.image, Modifier.fillMaxSize().blur(22.dp), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xF005070D), Color(0x99050810), Color(0x44050810)))))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Cm.Bg)))
                Row(
                    Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 30.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.tag, color = Cm.OnGold, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Cm.Gold).padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(item.title, color = Color.White, fontSize = if (wide) 34.sp else 22.sp, fontWeight = FontWeight.Black, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = if (wide) 38.sp else 26.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(item.subtitle, color = Color(0xFFC3CCDD), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GoldButton(if (item.tag == "SERIE") "Ver episodios" else "Reproducir", Icons.Filled.PlayArrow, item.play)
                            val fav = item.favKey in favorites
                            GhostButton(if (fav) "En mi lista" else "Mi lista", if (fav) Icons.Filled.Check else Icons.Filled.Add, { onToggleFavorite(item.favKey) })
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Poster(
                        item.image,
                        Modifier.width(if (wide) 190.dp else 108.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp))
                            .border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(14.dp))
                    )
                }
            }
        }
        if (items.size > 1) {
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items.indices.forEach { i ->
                    Box(
                        Modifier.height(5.dp).width(if (i == pager.currentPage) 20.dp else 6.dp).clip(CircleShape)
                            .background(if (i == pager.currentPage) Cm.Gold else Color(0x66FFFFFF))
                    )
                }
            }
        }
    }
}

// ───────────────────────────── TV en vivo ─────────────────────────────

@Composable
fun LiveScreen(
    items: List<Entry>,
    favorites: Set<String>,
    wide: Boolean,
    onPlay: (PlayRequest) -> Unit,
    onToggleFavorite: (String) -> Unit
) {
    var group by remember { mutableStateOf<String?>(null) }
    val groups = remember(items) { groupCounts(items) }
    val shown = remember(items, group) { if (group == null) items else items.filter { it.group == group } }
    Column(Modifier.fillMaxSize()) {
        GroupChips(groups, group) { group = it }
        if (shown.isEmpty()) EmptyState(Icons.Filled.LiveTv, "Sin canales", "No hay canales que coincidan con tu búsqueda.")
        else LazyVerticalGrid(
            columns = GridCells.Adaptive(if (wide) 330.dp else 300.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            gridItemsIndexed(shown, key = { _, e -> e.url }) { i, e ->
                ChannelRow(e, e.url in favorites, { onToggleFavorite(e.url) }) { onPlay(PlayRequest(shown, i, true)) }
            }
        }
    }
}

// ───────────────────────────── Películas y series ─────────────────────────────

@Composable
fun MoviesScreen(items: List<Entry>, favorites: Set<String>, wide: Boolean, onPlay: (PlayRequest) -> Unit) {
    var group by remember { mutableStateOf<String?>(null) }
    val groups = remember(items) { groupCounts(items) }
    val shown = remember(items, group) { if (group == null) items else items.filter { it.group == group } }
    Column(Modifier.fillMaxSize()) {
        GroupChips(groups, group) { group = it }
        if (shown.isEmpty()) EmptyState(Icons.Filled.Movie, "Sin películas", "No hay películas que coincidan con tu búsqueda.")
        else LazyVerticalGrid(
            columns = GridCells.Adaptive(if (wide) 160.dp else 104.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            gridItems(shown, key = { it.url }) { e ->
                PosterCard(e.name, e.group, e.logo, Modifier.fillMaxWidth(), badge = if (e.url in favorites) "★" else null) {
                    onPlay(PlayRequest(listOf(e), 0, false))
                }
            }
        }
    }
}

@Composable
fun SeriesScreen(items: List<Series>, favorites: Set<String>, wide: Boolean, onSeries: (Series) -> Unit) {
    var group by remember { mutableStateOf<String?>(null) }
    val groups = remember(items) { seriesGroupCounts(items) }
    val shown = remember(items, group) { if (group == null) items else items.filter { it.group == group } }
    Column(Modifier.fillMaxSize()) {
        GroupChips(groups, group) { group = it }
        if (shown.isEmpty()) EmptyState(Icons.Filled.Tv, "Sin series", "No hay series que coincidan con tu búsqueda.")
        else LazyVerticalGrid(
            columns = GridCells.Adaptive(if (wide) 160.dp else 104.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            gridItems(shown, key = { it.key }) { s ->
                PosterCard(s.title, "${s.episodes.size} episodios", s.poster, Modifier.fillMaxWidth(), badge = if (s.key in favorites) "★" else null) { onSeries(s) }
            }
        }
    }
}

// ───────────────────────────── Mi lista ─────────────────────────────

@Composable
fun FavoritesScreen(
    catalog: Catalog,
    favorites: Set<String>,
    wide: Boolean,
    onPlay: (PlayRequest) -> Unit,
    onSeries: (Series) -> Unit
) {
    val live = remember(catalog, favorites) { catalog.live.filter { it.url in favorites } }
    val movies = remember(catalog, favorites) { catalog.movies.filter { it.url in favorites } }
    val series = remember(catalog, favorites) { catalog.series.filter { it.key in favorites } }
    if (live.isEmpty() && movies.isEmpty() && series.isEmpty()) {
        EmptyState(Icons.Filled.FavoriteBorder, "Tu lista está vacía", "Toca el corazón en un canal, película o serie para guardarlo aquí.")
        return
    }
    val cardWidth = if (wide) 150.dp else 114.dp
    LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        if (live.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("Canales", "${live.size} guardados")
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexedRail(live) { i, e -> ChannelCard(e, Modifier.width(if (wide) 210.dp else 160.dp)) { onPlay(PlayRequest(live, i, true)) } }
                }
            }
        }
        if (movies.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("Películas", "${movies.size} guardadas")
                MovieRail(movies, cardWidth, onPlay)
            }
        }
        if (series.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("Series", "${series.size} guardadas")
                SeriesRail(series, cardWidth, onSeries)
            }
        }
    }
}

// ───────────────────────────── Resultados de búsqueda ─────────────────────────────

@Composable
fun SearchResults(
    live: List<Entry>, movies: List<Entry>, series: List<Series>,
    wide: Boolean, onPlay: (PlayRequest) -> Unit, onSeries: (Series) -> Unit
) {
    if (live.isEmpty() && movies.isEmpty() && series.isEmpty()) {
        EmptyState(Icons.Filled.Search, "Sin resultados", "Prueba con otro nombre o categoría.")
        return
    }
    val cardWidth = if (wide) 150.dp else 114.dp
    LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        if (live.isNotEmpty()) item {
            val rail = live.take(40)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("Canales", "${live.size} resultados")
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexedRail(rail) { i, e -> ChannelCard(e, Modifier.width(if (wide) 210.dp else 160.dp)) { onPlay(PlayRequest(rail, i, true)) } }
                }
            }
        }
        if (movies.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("Películas", "${movies.size} resultados")
                MovieRail(movies.take(40), cardWidth, onPlay)
            }
        }
        if (series.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("Series", "${series.size} resultados")
                SeriesRail(series.take(40), cardWidth, onSeries)
            }
        }
    }
}

// ───────────────────────────── Detalle de serie ─────────────────────────────

@Composable
fun SeriesDetail(
    series: Series,
    favorite: Boolean,
    wide: Boolean,
    store: Store,
    onToggleFavorite: () -> Unit,
    onPlay: (PlayRequest) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val seasons = remember(series) { series.episodes.map { episodeNumber(it.name).first }.distinct().sorted() }
    var season by remember(series) { mutableStateOf(seasons.firstOrNull() ?: 0) }
    val shown = remember(series, season) { series.episodes.filter { episodeNumber(it.name).first == season } }
    val resume = remember(series) {
        series.episodes.firstOrNull { store.position(it.url) > 10_000 }
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(if (wide) 330.dp else 290.dp)) {
                Poster(series.poster, Modifier.fillMaxSize().blur(24.dp))
                Box(Modifier.fillMaxSize().background(Color(0x99050810)))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Cm.Bg)))
                Row(
                    Modifier.fillMaxSize().statusBarsPadding().padding(start = 12.dp, end = 12.dp, top = 6.dp),
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Volver", tint = Color.White) }
                }
                Row(
                    Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Poster(series.poster, Modifier.width(if (wide) 150.dp else 104.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(12.dp)))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(series.title, color = Color.White, fontSize = if (wide) 30.sp else 21.sp, fontWeight = FontWeight.Black, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text("${series.episodes.size} episodios · ${series.group}", color = Color(0xFFC3CCDD), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val target = resume ?: shown.firstOrNull() ?: series.episodes.first()
                            GoldButton(if (resume != null) "Continuar" else "Reproducir", Icons.Filled.PlayArrow, {
                                val list = series.episodes
                                onPlay(PlayRequest(list, list.indexOf(target).coerceAtLeast(0), false, series.key, series.title))
                            })
                            GhostButton(if (favorite) "En mi lista" else "Mi lista", if (favorite) Icons.Filled.Check else Icons.Filled.Add, onToggleFavorite)
                        }
                    }
                }
            }
        }
        if (seasons.size > 1) item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(seasons, key = { it }) { s ->
                    val active = s == season
                    Text(
                        if (s == 0) "Episodios" else "Temporada $s",
                        color = if (active) Cm.OnGold else Cm.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.tvClickable(RoundedCornerShape(50), 1.06f) { season = s }
                            .background(if (active) Cm.Gold else Cm.SurfaceHi)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
        item { Spacer(Modifier.height(6.dp)) }
        items(shown.size, key = { shown[it].url }) { i ->
            val e = shown[i]
            val (_, ep) = episodeNumber(e.name)
            val pos = store.position(e.url)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    .tvClickable(RoundedCornerShape(14.dp), 1.02f) {
                        val list = series.episodes
                        onPlay(PlayRequest(list, list.indexOf(e).coerceAtLeast(0), false, series.key, series.title))
                    }
                    .background(Cm.SurfaceHi).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Cm.SurfaceTop),
                    contentAlignment = Alignment.Center
                ) { Text(if (ep > 0) "$ep" else "${i + 1}", color = Cm.Gold, fontWeight = FontWeight.Black, fontSize = 16.sp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.name, color = Cm.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (pos > 10_000) Text("En progreso", color = Cm.Gold, fontSize = 11.sp)
                }
                Icon(Icons.Filled.PlayCircleFilled, null, tint = Cm.Gold, modifier = Modifier.size(30.dp))
            }
        }
    }
}

// ───────────────────────────── Ajustes ─────────────────────────────

@Composable
fun SettingsDialog(store: Store, onSave: () -> Unit, onDismiss: () -> Unit) {
    var engine by remember { mutableStateOf(store.engine) }
    var url by remember { mutableStateOf(store.playlistUrl) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Cm.SurfaceHi,
        titleContentColor = Cm.Text,
        textContentColor = Cm.Text,
        title = { Text("Ajustes", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("MOTOR DE REPRODUCCIÓN", color = Cm.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                listOf(
                    Triple("AUTO", "Automático (recomendado)", "ExoPlayer primero; si falla, cambia solo a VLC"),
                    Triple("EXO", "ExoPlayer", "Empieza con Media3 (HLS, DASH, TS, MP4, MKV)"),
                    Triple("VLC", "VLC", "Empieza con libVLC (RTMP, UDP, códecs raros)")
                ).forEach { (id, title, desc) ->
                    Row(
                        Modifier.fillMaxWidth().tvClickable(RoundedCornerShape(10.dp), 1.02f) { engine = id }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = engine == id, onClick = { engine = id }, colors = RadioButtonDefaults.colors(selectedColor = Cm.Gold, unselectedColor = Cm.Muted))
                        Column {
                            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(desc, fontSize = 11.sp, color = Cm.Muted)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("LISTA M3U PERSONALIZADA", color = Cm.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    placeholder = { Text("Vacío = lista de Código Master", fontSize = 12.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Cm.Gold, unfocusedBorderColor = Cm.Outline, cursorColor = Cm.Gold,
                        focusedTextColor = Cm.Text, unfocusedTextColor = Cm.Text
                    )
                )
                Text("Usa únicamente listas y transmisiones para las que tengas autorización.", color = Cm.Muted, fontSize = 10.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { store.engine = engine; store.playlistUrl = url; onSave() }) {
                Text("Guardar", color = Cm.Gold, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar", color = Cm.Muted) } }
    )
}
