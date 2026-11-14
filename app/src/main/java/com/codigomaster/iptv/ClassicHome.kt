package com.codigomaster.iptv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Interfaz horizontal: menú vertical a la izquierda, vista previa del canal en el centro
 * y lista de canales a la derecha, con iconos de acceso rápido y reloj arriba.
 */

enum class NavTab(val label: String, val icon: ImageVector) {
    TV("TV", Icons.Filled.Tv),
    FEATURED("DESTACAD.", Icons.Filled.ThumbUp),
    MOVIES("PELICULA", Icons.Filled.Theaters),
    SERIES("SERIES", Icons.Filled.Movie),
    KIDS("KIDS", Icons.Filled.ChildCare),
    ANIME("ANIME", Icons.Filled.Pets),
    EXPLORE("EXPLORAR", Icons.Filled.Explore)
}

enum class ClassicOverlay { FAVS, RECENTS }

/** Estado que sobrevive mientras se abre y cierra el reproductor a pantalla completa. */
class ClassicState {
    var tab by mutableStateOf(NavTab.TV)
    var overlay by mutableStateOf<ClassicOverlay?>(null)
    var group by mutableStateOf<String?>(null)
    var channelUrl by mutableStateOf<String?>(null)
}

private val Pure = Color(0xFF000000)
private val Circle = Color(0xFF2B2B2B)
private val MenuIdle = Color(0xFFB9B9B9)

private val KIDS_RE = Regex("kids|infantil|ni(ñ|n)os|cartoon|disney|nick|junior|dibujos|animaci", RegexOption.IGNORE_CASE)
private val ANIME_RE = Regex("anime|crunchy|shonen|manga", RegexOption.IGNORE_CASE)

private fun Catalog.byCategory(re: Regex, exclude: Regex? = null): Catalog {
    fun ok(vararg s: String) = s.any { re.containsMatchIn(it) } && (exclude == null || s.none { x -> exclude.containsMatchIn(x) })
    return Catalog(
        live.filter { ok(it.group, it.name) },
        movies.filter { ok(it.group) },
        series.filter { ok(it.group, it.title) }
    )
}

@Composable
fun ClassicHome(
    state: ClassicState,
    store: Store,
    catalog: Catalog,
    fLive: List<Entry>,
    fMovies: List<Entry>,
    fSeries: List<Series>,
    q: String,
    search: String,
    onSearch: (String) -> Unit,
    continueWatching: List<Entry>,
    favorites: Set<String>,
    loading: Boolean,
    message: String,
    statusError: Boolean,
    /** 0 = contenido listo, 1 = cargando, 2 = sin contenido. */
    blocker: Int,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onPlay: (PlayRequest) -> Unit,
    onSeries: (Series) -> Unit,
    onToggleFavorite: (String) -> Unit
) {
    val tv = LocalTv.current
    var searchOpen by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    val liveGroups = remember(catalog) { groupCounts(catalog.live, 200) }

    BackHandler(enabled = search.isNotEmpty() || state.overlay != null || state.tab != NavTab.TV) {
        when {
            search.isNotEmpty() -> { onSearch(""); searchOpen = false }
            state.overlay != null -> state.overlay = null
            else -> state.tab = NavTab.TV
        }
    }

    Row(
        Modifier.fillMaxSize().background(Pure)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .then(if (tv) Modifier.padding(horizontal = 20.dp, vertical = 6.dp) else Modifier)
    ) {
        SideMenu(state.tab, state.overlay == null) { tab ->
            state.tab = tab; state.overlay = null; onSearch(""); searchOpen = false
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            ClassicTopBar(
                search = search, onSearch = onSearch, searchOpen = searchOpen, onSearchOpen = { searchOpen = it },
                filterActive = state.group != null,
                overlay = state.overlay,
                loading = loading, statusError = statusError, message = message,
                onFilter = { showPicker = true },
                onHistory = { state.overlay = if (state.overlay == ClassicOverlay.RECENTS) null else ClassicOverlay.RECENTS },
                onProfile = { state.overlay = if (state.overlay == ClassicOverlay.FAVS) null else ClassicOverlay.FAVS },
                onBell = onSettings,
                onWifi = onRefresh
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    blocker == 1 -> LoadingState()
                    blocker == 2 -> EmptyState(Icons.Filled.CloudOff, "Sin contenido", "No pudimos cargar la lista. Toca el icono de wifi para reintentar.")
                    state.overlay == ClassicOverlay.FAVS -> FavoritesScreen(catalog, favorites, true, onPlay, onSeries)
                    state.overlay == ClassicOverlay.RECENTS ->
                        if (continueWatching.isEmpty()) EmptyState(Icons.Filled.History, "Sin historial", "Aquí aparecerán las películas y episodios que empieces a ver.")
                        else CatalogRails(Catalog(emptyList(), continueWatching, emptyList()), favorites, onPlay, onSeries, "Sin historial")
                    state.tab == NavTab.TV -> TvTab(state, store, fLive, favorites, onPlay, onToggleFavorite)
                    q.isNotBlank() -> SearchResults(fLive, fMovies, fSeries, true, onPlay, onSeries)
                    else -> when (state.tab) {
                        NavTab.FEATURED -> HomeScreen(
                            catalog, continueWatching, store, favorites, true,
                            { s ->
                                when (s) {
                                    Section.LIVE -> state.tab = NavTab.TV
                                    Section.MOVIES -> state.tab = NavTab.MOVIES
                                    Section.SERIES -> state.tab = NavTab.SERIES
                                    Section.FAVS -> state.overlay = ClassicOverlay.FAVS
                                    Section.HOME -> state.tab = NavTab.FEATURED
                                }
                            },
                            onPlay, onSeries, onToggleFavorite
                        )
                        NavTab.MOVIES -> MoviesScreen(fMovies, favorites, true, onPlay)
                        NavTab.SERIES -> SeriesScreen(fSeries, favorites, true) { onSeries(it) }
                        NavTab.KIDS -> {
                            val c = remember(catalog) { catalog.byCategory(KIDS_RE, ANIME_RE) }
                            CatalogRails(c, favorites, onPlay, onSeries, "Sin contenido infantil")
                        }
                        NavTab.ANIME -> {
                            val c = remember(catalog) { catalog.byCategory(ANIME_RE) }
                            CatalogRails(c, favorites, onPlay, onSeries, "Sin anime")
                        }
                        else -> ExploreTab(catalog, favorites, onPlay, onSeries)
                    }
                }
            }
        }
    }

    if (showPicker) GroupPicker(
        liveGroups, state.group,
        onSelect = { state.group = it; state.tab = NavTab.TV; state.overlay = null; showPicker = false },
        onDismiss = { showPicker = false }
    )
}

// ───────────────────────────── Menú lateral ─────────────────────────────

@Composable
private fun SideMenu(current: NavTab, highlight: Boolean, onSelect: (NavTab) -> Unit) {
    Column(
        Modifier.fillMaxHeight().width(188.dp).padding(start = 20.dp, end = 4.dp),
        verticalArrangement = Arrangement.Center
    ) {
        NavTab.values().forEach { t ->
            val active = highlight && t == current
            Row(
                Modifier.fillMaxWidth().tvClickable(RoundedCornerShape(10.dp), 1.04f) { onSelect(t) }.padding(vertical = 11.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(t.icon, t.label, tint = if (active) Cm.Gold else MenuIdle, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                Text(
                    t.label, color = if (active) Color.White else MenuIdle,
                    fontSize = 19.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Clip
                )
            }
        }
    }
}

// ───────────────────────────── Barra superior ─────────────────────────────

@Composable
private fun RoundIcon(icon: ImageVector, description: String, tint: Color = Color.White, onClick: () -> Unit) {
    Box(
        Modifier.padding(horizontal = 6.dp).size(46.dp).tvClickable(CircleShape, 1.1f) { onClick() }.background(Circle),
        contentAlignment = Alignment.Center
    ) { Icon(icon, description, tint = tint, modifier = Modifier.size(24.dp)) }
}

@Composable
private fun ClassicTopBar(
    search: String, onSearch: (String) -> Unit, searchOpen: Boolean, onSearchOpen: (Boolean) -> Unit,
    filterActive: Boolean, overlay: ClassicOverlay?,
    loading: Boolean, statusError: Boolean, message: String,
    onFilter: () -> Unit, onHistory: () -> Unit, onProfile: () -> Unit, onBell: () -> Unit, onWifi: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, end = 18.dp, start = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (message.isNotBlank()) Text(
            message, color = if (statusError) Cm.Danger else Cm.Muted, fontSize = 11.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 12.dp)
        ) else Spacer(Modifier.weight(1f))

        if (searchOpen || search.isNotEmpty()) {
            SearchField(search, onSearch, Modifier.width(300.dp)) { onSearch(""); onSearchOpen(false) }
            Spacer(Modifier.width(6.dp))
        } else RoundIcon(Icons.Filled.Search, "Buscar") { onSearchOpen(true) }
        RoundIcon(Icons.Filled.FilterList, "Categorías", if (filterActive) Cm.Gold else Color.White, onFilter)
        RoundIcon(Icons.Filled.History, "Historial", if (overlay == ClassicOverlay.RECENTS) Cm.Gold else Color.White, onHistory)
        RoundIcon(Icons.Filled.Person, "Mi lista", if (overlay == ClassicOverlay.FAVS) Cm.Gold else Color.White, onProfile)
        RoundIcon(Icons.Filled.Notifications, "Ajustes", Color.White, onBell)

        Spacer(Modifier.width(10.dp))
        Box(Modifier.width(1.dp).height(20.dp).background(Color(0xFF555555)))
        Spacer(Modifier.width(14.dp))
        if (loading) Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(22.dp), color = Cm.Gold, strokeWidth = 2.dp)
        } else Box(Modifier.size(34.dp).tvClickable(CircleShape, 1.12f) { onWifi() }, contentAlignment = Alignment.Center) {
            Icon(
                if (statusError) Icons.Filled.WifiOff else Icons.Filled.Wifi, "Actualizar lista",
                tint = if (statusError) Cm.Danger else Color.White, modifier = Modifier.size(28.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        ClockText()
    }
}

@Composable
private fun ClockText() {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (isActive) { now = System.currentTimeMillis(); delay(15_000) } }
    val minute = now / 60_000
    val time = remember(minute) { SimpleDateFormat("hh:mm", Locale.US).format(Date(now)) }
    val ampm = remember(minute) { SimpleDateFormat("a", Locale.US).format(Date(now)) }
    Row(verticalAlignment = Alignment.Bottom) {
        Text(time, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(4.dp))
        Text(ampm, color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp))
    }
}

// ───────────────────────────── Pestaña TV: vista previa + lista ─────────────────────────────

@Composable
private fun TvTab(
    state: ClassicState,
    store: Store,
    live: List<Entry>,
    favorites: Set<String>,
    onPlay: (PlayRequest) -> Unit,
    onToggleFavorite: (String) -> Unit
) {
    val shown = remember(live, state.group) { if (state.group == null) live else live.filter { it.group == state.group } }
    if (shown.isEmpty()) {
        EmptyState(Icons.Filled.LiveTv, "Sin canales", "No hay canales que coincidan con tu búsqueda o categoría.")
        return
    }
    val current = remember(shown, state.channelUrl) { shown.firstOrNull { it.url == state.channelUrl } ?: shown.first() }
    val currentIndex = shown.indexOf(current)

    Row(Modifier.fillMaxSize().padding(start = 4.dp, end = 18.dp, top = 6.dp, bottom = 14.dp)) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().padding(end = 8.dp), contentAlignment = Alignment.Center) {
            val w = minOf(maxWidth, maxHeight * (16f / 9f))
            LivePreview(current, store, Modifier.width(w).aspectRatio(16f / 9f)) {
                onPlay(PlayRequest(shown, currentIndex.coerceAtLeast(0), true))
            }
        }
        Column(Modifier.width(286.dp).fillMaxHeight().padding(start = 10.dp)) {
            val ls = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 1).coerceAtLeast(0))
            Icon(
                Icons.Filled.ArrowDropUp, null, tint = Color(0xFFBBBBBB),
                modifier = Modifier.align(Alignment.CenterHorizontally).size(30.dp).alpha(if (ls.canScrollBackward) 1f else 0f)
            )
            LazyColumn(Modifier.weight(1f), state = ls, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                itemsIndexed(shown, contentType = { _, _ -> "ch" }) { _, e ->
                    ChannelTile(e, e.url == current.url, e.url in favorites, { onToggleFavorite(e.url) }) { state.channelUrl = e.url }
                }
            }
            Icon(
                Icons.Filled.ArrowDropDown, null, tint = Color(0xFFBBBBBB),
                modifier = Modifier.align(Alignment.CenterHorizontally).size(30.dp).alpha(if (ls.canScrollForward) 1f else 0f)
            )
        }
    }
}

@Composable
private fun ChannelTile(entry: Entry, selected: Boolean, favorite: Boolean, onToggleFavorite: () -> Unit, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    var showRate by remember { mutableStateOf(false) }
    if (showRate) RateDialog(entry.name, entry.url, RatingKind.CHANNEL, { showRate = false }, favorite, onToggleFavorite)
    Row(
        Modifier.fillMaxWidth()
            .tvClickableLong(shape, 1.03f, { showRate = true }, onClick)
            .background(if (selected) Color(0x26FFFFFF) else Color.Transparent)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Poster(
            entry.logo,
            Modifier.size(78.dp).clip(RoundedCornerShape(14.dp)).background(Circle).padding(8.dp),
            Icons.Filled.LiveTv, ContentScale.Fit, 160, 160
        )
        Spacer(Modifier.width(14.dp))
        Text(
            entry.name, color = Color.White, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (favorite) Icon(Icons.Filled.Favorite, "En mi lista", tint = Cm.Gold, modifier = Modifier.padding(start = 4.dp, end = 6.dp).size(16.dp))
    }
}

@Composable
private fun LivePreview(entry: Entry, store: Store, modifier: Modifier, onFullscreen: () -> Unit) {
    val context = LocalContext.current
    var engine by remember(entry.url) { mutableStateOf(if (store.engine == "VLC") EngineType.VLC else EngineType.EXO) }
    var tried by remember(entry.url) { mutableStateOf(setOf<EngineType>()) }
    var failure by remember(entry.url) { mutableStateOf<String?>(null) }
    val fatal = rememberUpdatedState<(String) -> Unit> { msg ->
        val cur = engine
        val nt = tried + cur
        tried = nt
        val other = if (cur == EngineType.EXO) EngineType.VLC else EngineType.EXO
        if (other !in nt) engine = other else failure = msg
    }
    val controller: Controller? = remember(entry.url, engine) {
        createController(engine, context, entry, true, 0L) { fatal.value(it) }
    }
    DisposableEffect(controller) { onDispose { controller?.release() } }
    LaunchedEffect(controller) { if (controller == null) fatal.value("No se pudo iniciar el motor de vídeo") }
    LaunchedEffect(controller) {
        if (controller != null) { delay(25_000); if (!controller.state.ready) fatal.value("Tiempo de espera agotado") }
    }
    LaunchedEffect(controller) { while (isActive) { controller?.poll(); delay(500) } }

    val shape = RoundedCornerShape(6.dp)
    Box(modifier.clip(shape).background(Color.Black)) {
        controller?.Content(Modifier.fillMaxSize(), AspectMode.FIT)
        // Capa transparente: recibe el clic / OK del mando y abre la pantalla completa.
        Box(Modifier.matchParentSize().tvClickable(shape, 1.0f) { onFullscreen() })
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(72.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
        )
        if (controller != null && !controller.state.ready && failure == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(36.dp), color = Cm.Gold, strokeWidth = 3.dp)
        }
        failure?.let {
            Column(Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.ErrorOutline, null, tint = Cm.Danger, modifier = Modifier.size(34.dp))
                Spacer(Modifier.height(6.dp))
                Text("Este canal no responde", color = Color.White, fontSize = 15.sp)
                Text("Elige otro de la lista", color = Cm.Muted, fontSize = 12.sp)
            }
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(entry.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 360.dp))
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(26.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Fullscreen, "Pantalla completa", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ───────────────────────────── Selector de categoría ─────────────────────────────

@Composable
private fun GroupPicker(groups: List<Pair<String, Int>>, selected: String?, onSelect: (String?) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(18.dp), color = Cm.Surface) {
            Column(Modifier.widthIn(max = 420.dp).heightIn(max = 380.dp).padding(vertical = 14.dp)) {
                Text("Categoría de canales", color = Cm.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                LazyColumn {
                    item { PickerRow("Todos los canales", null, selected == null) { onSelect(null) } }
                    items(groups.size) { i ->
                        val (name, count) = groups[i]
                        PickerRow(name, count, selected == name) { onSelect(name) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(text: String, count: Int?, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp)
            .tvClickable(RoundedCornerShape(10.dp), 1.02f) { onClick() }
            .background(if (active) Cm.Gold.copy(alpha = 0.18f) else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = if (active) Cm.Gold else Cm.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (count != null) Text("$count", color = Cm.Muted, fontSize = 12.sp)
    }
}

// ───────────────────────────── Kids / Anime / Explorar ─────────────────────────────

@Composable
private fun CatalogRails(
    c: Catalog,
    favorites: Set<String>,
    onPlay: (PlayRequest) -> Unit,
    onSeries: (Series) -> Unit,
    emptyTitle: String
) {
    if (c.live.isEmpty() && c.movies.isEmpty() && c.series.isEmpty()) {
        EmptyState(Icons.Filled.SearchOff, emptyTitle, "No encontramos contenido en esta categoría.")
        return
    }
    val live = remember(c) { c.live.take(80) }
    val movies = remember(c) { c.movies.take(80) }
    val series = remember(c) { c.series.take(80) }
    LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        if (live.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("Canales", "${c.live.size} disponibles")
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(live) { i, e -> ChannelCard(e, Modifier.width(210.dp)) { onPlay(PlayRequest(live, i, true)) } }
                }
            }
        }
        if (movies.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("Películas", "${c.movies.size} disponibles")
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(movies) { e ->
                        PosterCard(e.name, e.group, e.logo, Modifier.width(150.dp), badge = if (e.url in favorites) "★" else null, ratingKey = e.url) {
                            onPlay(PlayRequest(listOf(e), 0, false))
                        }
                    }
                }
            }
        }
        if (series.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("Series", "${c.series.size} disponibles")
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(series) { s ->
                        PosterCard(s.title, "${s.episodes.size} episodios", s.poster, Modifier.width(150.dp), badge = if (s.key in favorites) "★" else null, ratingKey = s.key, ratingKind = RatingKind.SERIES) { onSeries(s) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExploreTab(catalog: Catalog, favorites: Set<String>, onPlay: (PlayRequest) -> Unit, onSeries: (Series) -> Unit) {
    var group by remember { mutableStateOf<String?>(null) }
    val groups = remember(catalog) {
        (groupCounts(catalog.live, 300) + groupCounts(catalog.movies, 300) + seriesGroupCounts(catalog.series, 300))
            .groupBy { it.first }.map { (k, v) -> k to v.sumOf { it.second } }
            .sortedByDescending { it.second }.take(80)
    }
    val shown = remember(catalog, group) {
        val g = group
        if (g == null) Catalog(catalog.live.take(40), catalog.movies.take(40), catalog.series.take(40))
        else Catalog(catalog.live.filter { it.group == g }, catalog.movies.filter { it.group == g }, catalog.series.filter { it.group == g })
    }
    Column(Modifier.fillMaxSize()) {
        GroupChips(groups, group) { group = it }
        Box(Modifier.weight(1f)) { CatalogRails(shown, favorites, onPlay, onSeries, "Sin resultados") }
    }
}
