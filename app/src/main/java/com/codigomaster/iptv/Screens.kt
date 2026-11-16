package com.codigomaster.iptv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    val liveRail = remember(catalog) { catalog.live.take(24) }
    val movieRail = remember(catalog) { catalog.movies.take(24) }
    val seriesRail = remember(catalog) { catalog.series.take(24) }
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
                val rail = liveRail
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexedRail(rail) { i, e -> ChannelCard(e, Modifier.width(if (wide) 210.dp else 160.dp)) { onPlay(PlayRequest(rail, i, true)) } }
                }
            }
        }
        if (catalog.movies.isNotEmpty()) item(key = "movies") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("Películas", "${catalog.movies.size} títulos", "Ver todas") { onSection(Section.MOVIES) }
                MovieRail(movieRail, cardWidth, onPlay)
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
                SeriesRail(seriesRail, cardWidth, onSeries)
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
            PosterCard(e.name, e.group, e.logo, Modifier.width(cardWidth), ratingKey = e.url) { onPlay(PlayRequest(listOf(e), 0, false)) }
        }
    }
}

@Composable
fun SeriesRail(list: List<Series>, cardWidth: androidx.compose.ui.unit.Dp, onSeries: (Series) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(list, key = { it.key }) { s ->
            PosterCard(s.title, "${s.episodes.size} episodios", s.poster, Modifier.width(cardWidth), badge = "SERIE", ratingKey = s.key, ratingKind = RatingKind.SERIES) { onSeries(s) }
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
    val tv = LocalTv.current
    var index by remember(items) { mutableStateOf(0) }
    var focusedInside by remember { mutableStateOf(false) }
    var interacted by remember { mutableStateOf(0) }
    var showRate by remember { mutableStateOf(false) }
    val last = items.lastIndex
    fun go(d: Int) { index = (index + d + items.size) % items.size; interacted++ }

    // Rota sola cada 9 s, pero se detiene mientras el mando está sobre las destacadas
    // (antes cambiaba de página bajo tus dedos y el foco se perdía: por eso "se trababa").
    LaunchedEffect(items.size, focusedInside, interacted) {
        if (items.size < 2 || focusedInside) return@LaunchedEffect
        while (true) { delay(9000); index = (index + 1) % items.size }
    }

    val current = items[index.coerceIn(0, last)]
    val kind = if (current.tag == "SERIE") RatingKind.SERIES else RatingKind.MOVIE

    BoxWithConstraints(
        Modifier.fillMaxWidth()
            .onFocusChanged { focusedInside = it.hasFocus }
            .pointerInput(items.size) {
                var drag = 0f
                detectHorizontalDragGestures(onDragStart = { drag = 0f }, onDragEnd = {
                    if (drag < -80f) go(1) else if (drag > 80f) go(-1)
                }) { _, dx -> drag += dx }
            }
    ) {
        // La altura sigue al ancho disponible: el contenido siempre cabe, también en TV con texto grande.
        val height = (maxWidth * 0.40f).coerceIn(260.dp, 340.dp)
        Box(Modifier.fillMaxWidth().height(height)) {
            Crossfade(index, Modifier.fillMaxSize(), animationSpec = tween(350), label = "heroBg") { i ->
                Box(Modifier.fillMaxSize()) {
                    Poster(items[i.coerceIn(0, last)].image, Modifier.fillMaxSize().alpha(0.55f), contentScale = ContentScale.Crop, decodeWidth = 480, decodeHeight = 720)
                    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xF005070D), Color(0xCC050810), Color(0x66050810)))))
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Cm.Bg)))
                }
            }
            Row(
                Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Crossfade(index, animationSpec = tween(350), label = "heroText") { i ->
                        val item = items[i.coerceIn(0, last)]
                        val score = rememberScore(item.favKey, item.title, if (item.tag == "SERIE") RatingKind.SERIES else RatingKind.MOVIE)
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    item.tag, color = Cm.OnGold, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp,
                                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Cm.Gold).padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                                if (score != null) { Spacer(Modifier.width(8.dp)); RatingBadge(score) }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                item.title, color = Color.White, fontSize = if (wide) 28.sp else 20.sp, fontWeight = FontWeight.Black,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = if (wide) 32.sp else 24.sp
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(item.subtitle, color = Color(0xFFC3CCDD), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    // Botones FUERA del cambio de página: el foco del mando no se pierde al avanzar.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (tv && items.size > 1) FocusIconButton(Icons.Filled.KeyboardArrowLeft, "Destacada anterior") { go(-1) }
                        GoldButton(if (current.tag == "SERIE") "Ver episodios" else "Reproducir", Icons.Filled.PlayArrow, current.play)
                        val fav = current.favKey in favorites
                        GhostButton(if (fav) "En mi lista" else "Mi lista", if (fav) Icons.Filled.Check else Icons.Filled.Add, { onToggleFavorite(current.favKey) })
                        GhostButton("Calificar", Icons.Filled.Star, { showRate = true })
                        if (tv && items.size > 1) FocusIconButton(Icons.Filled.KeyboardArrowRight, "Destacada siguiente") { go(1) }
                    }
                }
                Spacer(Modifier.width(16.dp))
                Crossfade(index, Modifier.fillMaxHeight().aspectRatio(2f / 3f), animationSpec = tween(350), label = "heroPoster") { i ->
                    Poster(
                        items[i.coerceIn(0, last)].image,
                        Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(14.dp)),
                        decodeWidth = 400, decodeHeight = 600
                    )
                }
            }
            if (items.size > 1) {
                Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items.indices.forEach { i ->
                        Box(
                            Modifier.height(5.dp).width(if (i == index) 20.dp else 6.dp).clip(CircleShape)
                                .background(if (i == index) Cm.Gold else Color(0x66FFFFFF))
                        )
                    }
                }
            }
        }
    }
    if (showRate) RateDialog(
        current.title, current.favKey, kind, { showRate = false },
        favorite = current.favKey in favorites, onToggleFavorite = { onToggleFavorite(current.favKey) }
    )
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
            gridItemsIndexed(shown, key = { _, e -> e.url }, contentType = { _, _ -> "channel" }) { i, e ->
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
            gridItems(shown, key = { it.url }, contentType = { "poster" }) { e ->
                PosterCard(e.name, e.group, e.logo, Modifier.fillMaxWidth(), badge = if (e.url in favorites) "★" else null, ratingKey = e.url) {
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
            gridItems(shown, key = { it.key }, contentType = { "poster" }) { s ->
                PosterCard(s.title, "${s.episodes.size} episodios", s.poster, Modifier.fillMaxWidth(), badge = if (s.key in favorites) "★" else null, ratingKey = s.key, ratingKind = RatingKind.SERIES) { onSeries(s) }
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
    var showRate by remember { mutableStateOf(false) }
    val score = rememberScore(series.key, series.title, RatingKind.SERIES)
    if (showRate) RateDialog(series.title, series.key, RatingKind.SERIES, { showRate = false }, favorite, onToggleFavorite)
    val seasons = remember(series) { series.episodes.map { episodeNumber(it.name).first }.distinct().sorted() }
    var season by remember(series) { mutableStateOf(seasons.firstOrNull() ?: 0) }
    val shown = remember(series, season) { series.episodes.filter { episodeNumber(it.name).first == season } }
    val resume = remember(series) {
        series.episodes.firstOrNull { store.position(it.url) > 10_000 }
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(if (wide) 330.dp else 290.dp)) {
                Poster(series.poster, Modifier.fillMaxSize().alpha(0.45f), decodeWidth = 480, decodeHeight = 720)
                Box(Modifier.fillMaxSize().background(Color(0xAA050810)))
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
                    Poster(series.poster, Modifier.width(if (wide) 150.dp else 104.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(12.dp)), decodeWidth = 360, decodeHeight = 540)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(series.title, color = Color.White, fontSize = if (wide) 30.sp else 21.sp, fontWeight = FontWeight.Black, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (score != null) { RatingBadge(score); Spacer(Modifier.width(8.dp)) }
                            Text("${series.episodes.size} episodios · ${series.group}", color = Color(0xFFC3CCDD), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val target = resume ?: shown.firstOrNull() ?: series.episodes.first()
                            GoldButton(if (resume != null) "Continuar" else "Reproducir", Icons.Filled.PlayArrow, {
                                val list = series.episodes
                                onPlay(PlayRequest(list, list.indexOf(target).coerceAtLeast(0), false, series.key, series.title))
                            })
                            GhostButton(if (favorite) "En mi lista" else "Mi lista", if (favorite) Icons.Filled.Check else Icons.Filled.Add, onToggleFavorite)
                            GhostButton("Calificar", Icons.Filled.Star, { showRate = true })
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


@Composable
private fun ColorSwatchRow(title: String, selected: String, colors: List<String>, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, color = Cm.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
            colors.forEach { hex ->
                val color = Cm.parse(hex, Color.White)
                val active = selected.equals(hex, ignoreCase = true)
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(color)
                        .then(if (active) Modifier.border(3.dp, Cm.Gold, CircleShape) else Modifier.border(1.dp, Cm.Outline, CircleShape))
                        .clickable { onSelect(hex) },
                    contentAlignment = Alignment.Center
                ) {
                    if (active) Icon(Icons.Filled.Check, null, tint = if (color == Color.White) Color.Black else Color.White, modifier = Modifier.size(17.dp))
                }
            }
        }
    }
}

// ───────────────────────────── Ajustes ─────────────────────────────

@Composable
fun SettingsDialog(
    store: Store,
    accessToken: String,
    deviceKey: String,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    var engine by remember { mutableStateOf(store.engine) }
    var url by remember { mutableStateOf(store.playlistUrl) }
    var tmdb by remember { mutableStateOf(store.tmdbKey) }
    var m3uName by remember { mutableStateOf("Mi lista M3U") }
    var xtreamName by remember { mutableStateOf("Mi conexión Xtream") }
    var xtreamServer by remember { mutableStateOf("") }
    var xtreamUser by remember { mutableStateOf("") }
    var xtreamPassword by remember { mutableStateOf("") }
    var playlists by remember { mutableStateOf(emptyList<ClientPlaylist>()) }
    var busy by remember { mutableStateOf(false) }
    var playlistMessage by remember { mutableStateOf("") }
    var parentalEnabled by remember { mutableStateOf(store.parentalProtectionEnabled) }
    var currentParentalPin by remember { mutableStateOf("") }
    var newParentalPin by remember { mutableStateOf("") }
    var confirmParentalPin by remember { mutableStateOf("") }
    var parentalMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    suspend fun reloadPlaylists() {
        playlists = listClientPlaylists(accessToken, deviceKey)
    }

    LaunchedEffect(accessToken, deviceKey) {
        try { reloadPlaylists() } catch (e: Exception) { playlistMessage = e.message ?: "No se pudieron cargar tus listas." }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Cm.SurfaceHi,
        titleContentColor = Cm.Text,
        textContentColor = Cm.Text,
        title = { Text("Ajustes", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("PERSONALIZACIÓN DE COLORES", color = Cm.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Text("Cambia la apariencia de la app. Los colores se aplican al instante y se guardan en este dispositivo.", color = Cm.Muted, fontSize = 10.sp)
                ColorSwatchRow("Fondo", store.backgroundColor, listOf("#FF05070D", "#FF101827", "#FF172033", "#FF20142E", "#FF10251F", "#FF2A1B12")) { store.backgroundColor = it; Cm.load(store) }
                ColorSwatchRow("Textos", store.textColor, listOf("#FFF3F6FB", "#FFFFFFFF", "#FFD7E7FF", "#FFFFE4B5", "#FFB9FFD8", "#FFFFC9D5")) { store.textColor = it; Cm.load(store) }
                ColorSwatchRow("Recuadros al seleccionar", store.selectionColor, listOf("#FF4DA3FF", "#FFFFC84A", "#FFFF4D6D", "#FF8B5CF6", "#FF22C55E", "#FF00BCD4")) { store.selectionColor = it; Cm.load(store) }
                TextButton(onClick = {
                    store.backgroundColor = "#FF05070D"
                    store.textColor = "#FFF3F6FB"
                    store.selectionColor = "#FF4DA3FF"
                    Cm.load(store)
                }) { Text("Restablecer colores originales", color = Cm.Gold, fontSize = 11.sp) }

                Divider(color = Cm.Outline)
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

                Divider(color = Cm.Outline)
                Text("CONTROL PARENTAL · CONTENIDO +18", color = Cm.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                Text(
                    if (parentalEnabled) "Protección activada: se ocultan títulos y categorías identificados como adultos."
                    else "Protección desactivada. Configura un PIN para ocultar el contenido adulto.",
                    color = Cm.Muted, fontSize = 11.sp
                )
                if (store.hasParentalPin()) {
                    OutlinedTextField(
                        value = currentParentalPin, onValueChange = { currentParentalPin = it.filter(Char::isDigit).take(6) },
                        label = { Text("PIN actual") }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = newParentalPin, onValueChange = { newParentalPin = it.filter(Char::isDigit).take(6) },
                    label = { Text(if (store.hasParentalPin()) "Nuevo PIN (4 a 6 dígitos)" else "Crear PIN (4 a 6 dígitos)") },
                    singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = confirmParentalPin, onValueChange = { confirmParentalPin = it.filter(Char::isDigit).take(6) },
                    label = { Text("Confirmar PIN") }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = newParentalPin.length in 4..6 && confirmParentalPin == newParentalPin &&
                            (!store.hasParentalPin() || currentParentalPin.length in 4..6),
                        onClick = {
                            if (store.hasParentalPin() && !store.verifyParentalPin(currentParentalPin)) {
                                parentalMessage = "El PIN actual no es correcto."
                            } else {
                                try {
                                    store.setParentalPin(newParentalPin)
                                    parentalEnabled = true
                                    currentParentalPin = ""; newParentalPin = ""; confirmParentalPin = ""
                                    parentalMessage = "PIN guardado. El contenido +18 está bloqueado."
                                } catch (e: Exception) { parentalMessage = e.message ?: "No se pudo guardar el PIN." }
                            }
                        }
                    ) { Text(if (store.hasParentalPin()) "Cambiar PIN" else "Crear PIN") }
                    if (parentalEnabled) {
                        OutlinedButton(
                            enabled = currentParentalPin.length in 4..6,
                            onClick = {
                                if (store.verifyParentalPin(currentParentalPin)) {
                                    store.parentalProtectionEnabled = false
                                    parentalEnabled = false
                                    currentParentalPin = ""
                                    parentalMessage = "Protección parental desactivada."
                                } else parentalMessage = "El PIN actual no es correcto."
                            }
                        ) { Text("Desactivar") }
                    }
                }
                if (parentalMessage.isNotBlank()) Text(parentalMessage, color = Cm.Gold, fontSize = 11.sp)
                Text("El PIN se guarda como hash con salt; no se almacena en texto legible. El filtro se basa en nombres y categorías de la lista, por lo que conviene revisar las etiquetas de tu proveedor.", color = Cm.Muted, fontSize = 10.sp)

                Divider(color = Cm.Outline)
                Text("MI LISTA M3U", color = Cm.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                OutlinedTextField(
                    value = m3uName, onValueChange = { m3uName = it },
                    label = { Text("Nombre de la lista") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    placeholder = { Text("https://servidor/lista.m3u", fontSize = 12.sp) },
                    label = { Text("URL M3U") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !busy && url.startsWith("http", true) && m3uName.isNotBlank(),
                        onClick = {
                            scope.launch {
                                busy = true; playlistMessage = "Guardando lista M3U…"
                                try {
                                    val id = saveM3uPlaylist(accessToken, deviceKey, m3uName, url.trim())
                                    reloadPlaylists()
                                    store.selectedPlaylistId = id
                                    store.playlistUrl = ""
                                    url = ""
                                    playlistMessage = "Lista M3U guardada en tu cuenta."
                                    onSave()
                                } catch (e: Exception) {
                                    playlistMessage = e.message ?: "No se pudo guardar la lista."
                                } finally { busy = false }
                            }
                        }
                    ) { Text("Guardar en mi cuenta") }
                    TextButton(onClick = {
                        store.selectedPlaylistId = ""
                        store.playlistUrl = url.trim()
                        onSave()
                    }) { Text("Usar solo aquí", color = Cm.Gold) }
                }

                Divider(color = Cm.Outline)
                Text("CONEXIÓN XTREAM CODES", color = Cm.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                OutlinedTextField(
                    value = xtreamName, onValueChange = { xtreamName = it },
                    label = { Text("Nombre de la conexión") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = xtreamServer, onValueChange = { xtreamServer = it },
                    label = { Text("Servidor (https://...)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = xtreamUser, onValueChange = { xtreamUser = it },
                    label = { Text("Usuario Xtream") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = xtreamPassword, onValueChange = { xtreamPassword = it },
                    label = { Text("Contraseña Xtream") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                )
                Button(
                    enabled = !busy && xtreamServer.startsWith("http", true) && xtreamUser.isNotBlank() && xtreamPassword.isNotBlank(),
                    onClick = {
                        scope.launch {
                            busy = true; playlistMessage = "Guardando conexión Xtream cifrada…"
                            try {
                                val id = saveXtreamPlaylist(accessToken, deviceKey, xtreamName, xtreamServer, xtreamUser, xtreamPassword)
                                reloadPlaylists()
                                val saved = playlists.firstOrNull { it.id == id }
                                    ?: listClientPlaylists(accessToken, deviceKey).first { it.id == id }
                                val streamUrl = loadClientPlaylistUrl(accessToken, deviceKey, saved)
                                store.selectedPlaylistId = id
                                store.playlistUrl = ""
                                url = ""
                                playlistMessage = "Conexión Xtream guardada y seleccionada."
                                xtreamPassword = ""
                                onSave()
                            } catch (e: Exception) {
                                playlistMessage = e.message ?: "No se pudo guardar Xtream."
                            } finally { busy = false }
                        }
                    }
                ) { Text(if (busy) "PROCESANDO…" else "Guardar conexión Xtream") }

                Divider(color = Cm.Outline)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("MIS LISTAS GUARDADAS", color = Cm.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    TextButton(enabled = !busy, onClick = { scope.launch { try { reloadPlaylists() } catch (e: Exception) { playlistMessage = e.message ?: "Error" } } }) {
                        Text("Actualizar", color = Cm.Gold)
                    }
                }
                if (playlists.isEmpty()) {
                    Text("Todavía no tienes listas guardadas en tu cuenta.", color = Cm.Muted, fontSize = 11.sp)
                } else {
                    playlists.forEach { item ->
                        val active = item.isEnabled && !item.isSuspended
                        Row(
                            Modifier.fillMaxWidth().background(Cm.SurfaceTop, RoundedCornerShape(10.dp)).padding(9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.name, color = Cm.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    (if (item.sourceType == "xtream") "Xtream · " + item.serverUrl else "M3U") +
                                        if (active) " · Activa" else " · Suspendida",
                                    color = if (active) Cm.Muted else Cm.Gold, fontSize = 10.sp, maxLines = 2
                                )
                            }
                            Button(
                                enabled = !busy && active,
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        try {
                                            val streamUrl = loadClientPlaylistUrl(accessToken, deviceKey, item)
                                            store.selectedPlaylistId = item.id
                                            store.playlistUrl = ""
                                            url = ""
                                            playlistMessage = "Lista seleccionada."
                                            onSave()
                                        } catch (e: Exception) {
                                            playlistMessage = e.message ?: "No se pudo activar la lista."
                                        } finally { busy = false }
                                    }
                                }
                            ) { Text("Usar", fontSize = 11.sp) }
                        }
                    }
                }
                if (playlistMessage.isNotBlank()) Text(playlistMessage, color = Cm.Gold, fontSize = 11.sp)
                Divider(color = Cm.Outline)
                Text("CALIFICACIONES DEL PÚBLICO (TMDB)", color = Cm.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                OutlinedTextField(
                    value = tmdb, onValueChange = { tmdb = it },
                    placeholder = { Text("Clave gratuita de themoviedb.org", fontSize = 12.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Cm.Gold, unfocusedBorderColor = Cm.Outline, cursorColor = Cm.Gold,
                        focusedTextColor = Cm.Text, unfocusedTextColor = Cm.Text
                    )
                )
                Text("Las contraseñas Xtream se guardan cifradas en el servidor. Usa únicamente listas y transmisiones para las que tengas autorización.", color = Cm.Muted, fontSize = 10.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { store.engine = engine; if (url.isNotBlank()) { store.selectedPlaylistId = ""; store.playlistUrl = url.trim() }; store.tmdbKey = tmdb; onSave() }) {
                Text("Guardar ajustes", color = Cm.Gold, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar", color = Cm.Muted) } }
    )
}


/** Catálogo mixto de anime o contenido infantil: conserva películas y series separadas. */
@Composable
fun MixedContentScreen(
    title: String,
    movies: List<Entry>,
    series: List<Series>,
    wide: Boolean,
    onPlay: (PlayRequest) -> Unit,
    onSeries: (Series) -> Unit
) {
    if (movies.isEmpty() && series.isEmpty()) {
        EmptyState(Icons.Filled.Collections, "Sin contenido en $title", "Prueba actualizar el catálogo o revisar los nombres y grupos de la lista.")
        return
    }
    val cardWidth = if (wide) 150.dp else 114.dp
    LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (movies.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("Películas de $title", "${movies.size} títulos")
                MovieRail(movies, cardWidth, onPlay)
            }
        }
        if (series.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("Series de $title", "${series.size} series")
                SeriesRail(series, cardWidth, onSeries)
            }
        }
    }
}
