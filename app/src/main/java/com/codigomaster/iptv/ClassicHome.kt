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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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

enum class ClassicOverlay { FAVS, RECENTS, FILTER }

/** Escala de la interfaz según la altura de pantalla (móvil horizontal ≈ 0.8, TV ≈ 1.2). */
val LocalUiScale = compositionLocalOf { 1f }

/** Estado que sobrevive mientras se abre y cierra el reproductor a pantalla completa. */
class ClassicState {
    var tab by mutableStateOf(NavTab.TV)
    var overlay by mutableStateOf<ClassicOverlay?>(null)
    var group by mutableStateOf<String?>(null)
    var channelUrl by mutableStateOf<String?>(null)

    // Panel de filtros (películas / series / kids / anime)
    var fType by mutableIntStateOf(0)
    var fGenre by mutableStateOf<String?>(null)
    var fYear by mutableStateOf<String?>(null)
    var fCountry by mutableStateOf<String?>(null)
    var fAudio by mutableStateOf<String?>(null)
    internal var filterCache: List<FItem>? = null
    internal var filterCacheFor: Catalog? = null
}

private val Pure = Color(0xFF000000)
private val Circle = Color(0xFF2B2B2B)
private val MenuIdle = Color(0xFFB9B9B9)

private fun openShow(item: ShowItem, onMovie: (Entry) -> Unit, onSeries: (Series) -> Unit) {
    val e = item.entry
    val sr = item.series
    if (e != null) onMovie(e) else if (sr != null) onSeries(sr)
}

internal val KIDS_RE = Regex("kids|infantil|ni(ñ|n)os|cartoon|disney|nick|junior|dibujos|animaci", RegexOption.IGNORE_CASE)
internal val ANIME_RE = Regex("anime|crunchy|shonen|manga", RegexOption.IGNORE_CASE)

internal fun Catalog.byCategory(re: Regex, exclude: Regex? = null): Catalog {
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
    onMovie: (Entry) -> Unit,
    onToggleFavorite: (String) -> Unit
) {
    val tv = LocalTv.current
    val cfg = LocalConfiguration.current
    val s = (cfg.screenHeightDp / 400f).coerceIn(0.72f, 1.2f)
    val menuW = (cfg.screenWidthDp * 0.13f).coerceIn(104f, 190f)
    var searchOpen by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    val liveGroups = remember(catalog) { groupCounts(catalog.live, 200) }
    // Una película suelta abre su ficha (captura 2); canales y episodios siguen reproduciéndose directo.
    val openOrPlay: (PlayRequest) -> Unit = { r ->
        if (!r.live && r.seriesKey == null && r.queue.size == 1) onMovie(r.queue[0]) else onPlay(r)
    }

    // Pantalla inmersiva: sin barras del sistema para dar más espacio al vídeo y al contenido.
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        val ctl = window?.let { WindowCompat.getInsetsController(it, view) }
        ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { }
    }

    BackHandler(enabled = search.isNotEmpty() || state.overlay != null || state.tab != NavTab.TV) {
        when {
            search.isNotEmpty() -> { onSearch(""); searchOpen = false }
            state.overlay != null -> state.overlay = null
            else -> state.tab = NavTab.TV
        }
    }

    CompositionLocalProvider(LocalUiScale provides s) {
        if (state.overlay == ClassicOverlay.FILTER) {
            FilterScreen(state, catalog, openOrPlay, onSeries) { state.overlay = null }
        } else Row(
            Modifier.fillMaxSize().background(Pure)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .then(if (tv) Modifier.padding(horizontal = 16.dp, vertical = 4.dp) else Modifier)
        ) {
            SideMenu(state.tab, state.overlay == null, menuW) { tab ->
                state.tab = tab; state.overlay = null; onSearch(""); searchOpen = false
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                ClassicTopBar(
                    search = search, onSearch = onSearch, searchOpen = searchOpen, onSearchOpen = { searchOpen = it },
                    overlay = state.overlay,
                    loading = loading, statusError = statusError, message = message,
                    onFilter = { state.overlay = ClassicOverlay.FILTER },
                    onHistory = { state.overlay = if (state.overlay == ClassicOverlay.RECENTS) null else ClassicOverlay.RECENTS },
                    onProfile = { state.overlay = if (state.overlay == ClassicOverlay.FAVS) null else ClassicOverlay.FAVS },
                    onBell = onSettings,
                    onWifi = onRefresh
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        blocker == 1 -> LoadingState()
                        blocker == 2 -> EmptyState(Icons.Filled.CloudOff, "Sin contenido", "No pudimos cargar la lista. Toca el icono de wifi para reintentar.")
                        state.overlay == ClassicOverlay.FAVS -> FavoritesScreen(catalog, favorites, true, openOrPlay, onSeries)
                        state.overlay == ClassicOverlay.RECENTS ->
                            if (continueWatching.isEmpty()) EmptyState(Icons.Filled.History, "Sin historial", "Aquí aparecerán las películas y episodios que empieces a ver.")
                            else CatalogRails(Catalog(emptyList(), continueWatching, emptyList()), favorites, onPlay, onSeries, "Sin historial")
                        state.tab == NavTab.TV -> TvTab(state, store, fLive, favorites, cfg.screenWidthDp, onPlay, onToggleFavorite) { showPicker = true }
                        q.isNotBlank() -> SearchResults(fLive, fMovies, fSeries, true, openOrPlay, onSeries)
                        else -> when (state.tab) {
                            NavTab.FEATURED -> {
                                val (top, rows) = remember(catalog) { buildShow(catalog, ShowMode.FEATURED) }
                                Showcase(top, rows, continueWatching, emptyList(), { openShow(it, onMovie, onSeries) }, onPlay, "Sin contenido")
                            }
                            NavTab.MOVIES -> {
                                val (top, rows) = remember(catalog) { buildShow(catalog, ShowMode.MOVIES) }
                                Showcase(top, rows, emptyList(), emptyList(), { openShow(it, onMovie, onSeries) }, onPlay, "Sin películas")
                            }
                            NavTab.SERIES -> {
                                val (top, rows) = remember(catalog) { buildShow(catalog, ShowMode.SERIES) }
                                Showcase(top, rows, emptyList(), emptyList(), { openShow(it, onMovie, onSeries) }, onPlay, "Sin series")
                            }
                            NavTab.KIDS -> {
                                val c = remember(catalog) { catalog.byCategory(KIDS_RE, ANIME_RE) }
                                val (top, rows) = remember(c) { buildShow(c, ShowMode.MIXED) }
                                Showcase(top, rows, emptyList(), remember(c) { c.live.take(60) }, { openShow(it, onMovie, onSeries) }, onPlay, "Sin contenido infantil")
                            }
                            NavTab.ANIME -> {
                                val c = remember(catalog) { catalog.byCategory(ANIME_RE) }
                                val (top, rows) = remember(c) { buildShow(c, ShowMode.MIXED) }
                                Showcase(top, rows, emptyList(), remember(c) { c.live.take(60) }, { openShow(it, onMovie, onSeries) }, onPlay, "Sin anime")
                            }
                            else -> ExploreTab(catalog, favorites, openOrPlay, onSeries)
                        }
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
private fun SideMenu(current: NavTab, highlight: Boolean, widthDp: Float, onSelect: (NavTab) -> Unit) {
    val s = LocalUiScale.current
    Column(
        Modifier.fillMaxHeight().width(widthDp.dp).padding(start = (14 * s).dp, end = 2.dp),
        verticalArrangement = Arrangement.Center
    ) {
        NavTab.values().forEach { t ->
            val active = highlight && t == current
            Row(
                Modifier.fillMaxWidth().tvClickable(RoundedCornerShape(10.dp), 1.04f) { onSelect(t) }
                    .padding(vertical = (8 * s).dp, horizontal = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(t.icon, t.label, tint = if (active) Color(0xFF4D7CFF) else MenuIdle, modifier = Modifier.size((20 * s).dp))
                Spacer(Modifier.width((10 * s).dp))
                Text(
                    t.label, color = if (active) CineRed else MenuIdle,
                    fontSize = (16 * s).sp, fontWeight = FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Clip
                )
            }
        }
    }
}

// ───────────────────────────── Barra superior ─────────────────────────────

@Composable
private fun RoundIcon(icon: ImageVector, description: String, tint: Color = Color.White, onClick: () -> Unit) {
    val s = LocalUiScale.current
    Box(
        Modifier.padding(horizontal = (4 * s).dp).size((38 * s).dp).tvClickable(CircleShape, 1.1f) { onClick() }.background(Circle),
        contentAlignment = Alignment.Center
    ) { Icon(icon, description, tint = tint, modifier = Modifier.size((20 * s).dp)) }
}

@Composable
private fun ClassicTopBar(
    search: String, onSearch: (String) -> Unit, searchOpen: Boolean, onSearchOpen: (Boolean) -> Unit,
    overlay: ClassicOverlay?,
    loading: Boolean, statusError: Boolean, message: String,
    onFilter: () -> Unit, onHistory: () -> Unit, onProfile: () -> Unit, onBell: () -> Unit, onWifi: () -> Unit
) {
    val s = LocalUiScale.current
    Row(
        Modifier.fillMaxWidth().padding(top = (6 * s).dp, end = (14 * s).dp, start = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (message.isNotBlank()) Text(
            message, color = if (statusError) Cm.Danger else Cm.Muted, fontSize = 11.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 12.dp)
        ) else Spacer(Modifier.weight(1f))

        if (searchOpen || search.isNotEmpty()) {
            SearchField(search, onSearch, Modifier.width((280 * s).dp)) { onSearch(""); onSearchOpen(false) }
            Spacer(Modifier.width(4.dp))
        } else RoundIcon(Icons.Filled.Search, "Buscar") { onSearchOpen(true) }
        RoundIcon(Icons.Filled.FilterList, "Filtros", Color.White, onFilter)
        RoundIcon(Icons.Filled.History, "Historial", if (overlay == ClassicOverlay.RECENTS) Cm.Gold else Color.White, onHistory)
        RoundIcon(Icons.Filled.Person, "Mi lista", if (overlay == ClassicOverlay.FAVS) Cm.Gold else Color.White, onProfile)
        RoundIcon(Icons.Filled.Notifications, "Ajustes", Color.White, onBell)

        Spacer(Modifier.width((8 * s).dp))
        Box(Modifier.width(1.dp).height((16 * s).dp).background(Color(0xFF555555)))
        Spacer(Modifier.width((10 * s).dp))
        if (loading) Box(Modifier.size((30 * s).dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size((18 * s).dp), color = Cm.Gold, strokeWidth = 2.dp)
        } else Box(Modifier.size((30 * s).dp).tvClickable(CircleShape, 1.12f) { onWifi() }, contentAlignment = Alignment.Center) {
            Icon(
                if (statusError) Icons.Filled.WifiOff else Icons.Filled.Wifi, "Actualizar lista",
                tint = if (statusError) Cm.Danger else Color.White, modifier = Modifier.size((24 * s).dp)
            )
        }
        Spacer(Modifier.width((10 * s).dp))
        ClockText()
    }
}

@Composable
private fun ClockText() {
    val s = LocalUiScale.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (isActive) { now = System.currentTimeMillis(); delay(15_000) } }
    val minute = now / 60_000
    val time = remember(minute) { SimpleDateFormat("hh:mm", Locale.US).format(Date(now)) }
    val ampm = remember(minute) { SimpleDateFormat("a", Locale.US).format(Date(now)) }
    Row(verticalAlignment = Alignment.Bottom) {
        Text(time, color = Color.White, fontSize = (22 * s).sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(3.dp))
        Text(ampm, color = Color.White, fontSize = (12 * s).sp, modifier = Modifier.padding(bottom = 3.dp))
    }
}

// ───────────────────────────── Pestaña TV: vista previa + lista ─────────────────────────────

@Composable
private fun TvTab(
    state: ClassicState,
    store: Store,
    live: List<Entry>,
    favorites: Set<String>,
    screenWidthDp: Int,
    onPlay: (PlayRequest) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onPickGroup: () -> Unit
) {
    val s = LocalUiScale.current
    val shown = remember(live, state.group) { if (state.group == null) live else live.filter { it.group == state.group } }
    if (shown.isEmpty()) {
        EmptyState(Icons.Filled.LiveTv, "Sin canales", "No hay canales que coincidan con tu búsqueda o categoría.")
        return
    }
    val current = remember(shown, state.channelUrl) { shown.firstOrNull { it.url == state.channelUrl } ?: shown.first() }
    val currentIndex = shown.indexOf(current)
    val listW = (screenWidthDp * 0.21f).coerceIn(160f, 290f)

    Row(Modifier.fillMaxSize().padding(start = 2.dp, end = (10 * s).dp, top = (4 * s).dp, bottom = (8 * s).dp)) {
        // La vista previa ocupa todo el espacio disponible (16:9 como máximo).
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().padding(end = 4.dp), contentAlignment = Alignment.Center) {
            val w = minOf(maxWidth, maxHeight * (16f / 9f))
            LivePreview(current, store, Modifier.width(w).aspectRatio(16f / 9f), state.group ?: "Todos", onPickGroup) {
                onPlay(PlayRequest(shown, currentIndex.coerceAtLeast(0), true))
            }
        }
        Column(Modifier.width(listW.dp).fillMaxHeight().padding(start = (6 * s).dp)) {
            val ls = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 1).coerceAtLeast(0))
            Icon(
                Icons.Filled.ArrowDropUp, null, tint = Color(0xFFBBBBBB),
                modifier = Modifier.align(Alignment.CenterHorizontally).size((24 * s).dp).alpha(if (ls.canScrollBackward) 1f else 0f)
            )
            LazyColumn(Modifier.weight(1f), state = ls, verticalArrangement = Arrangement.spacedBy((8 * s).dp)) {
                itemsIndexed(shown, contentType = { _, _ -> "ch" }) { _, e ->
                    ChannelTile(e, e.url == current.url, e.url in favorites, { onToggleFavorite(e.url) }) { state.channelUrl = e.url }
                }
            }
            Icon(
                Icons.Filled.ArrowDropDown, null, tint = Color(0xFFBBBBBB),
                modifier = Modifier.align(Alignment.CenterHorizontally).size((24 * s).dp).alpha(if (ls.canScrollForward) 1f else 0f)
            )
        }
    }
}

@Composable
private fun ChannelTile(entry: Entry, selected: Boolean, favorite: Boolean, onToggleFavorite: () -> Unit, onClick: () -> Unit) {
    val s = LocalUiScale.current
    val shape = RoundedCornerShape(14.dp)
    var showRate by remember { mutableStateOf(false) }
    if (showRate) RateDialog(entry.name, entry.url, RatingKind.CHANNEL, { showRate = false }, favorite, onToggleFavorite)
    Row(
        Modifier.fillMaxWidth()
            .tvClickableLong(shape, 1.03f, { showRate = true }, onClick)
            .background(if (selected) Color(0x26FFFFFF) else Color.Transparent)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Poster(
            entry.logo,
            Modifier.size((56 * s).dp).clip(RoundedCornerShape(12.dp)).background(Circle).padding((6 * s).dp),
            Icons.Filled.LiveTv, ContentScale.Fit, 160, 160
        )
        Spacer(Modifier.width((10 * s).dp))
        Text(
            entry.name, color = Color.White, fontSize = (15 * s).sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            lineHeight = (18 * s).sp, modifier = Modifier.weight(1f)
        )
        if (favorite) Icon(Icons.Filled.Favorite, "En mi lista", tint = Cm.Gold, modifier = Modifier.padding(start = 3.dp, end = 4.dp).size(14.dp))
    }
}

@Composable
private fun LivePreview(entry: Entry, store: Store, modifier: Modifier, groupLabel: String, onPickGroup: () -> Unit, onFullscreen: () -> Unit) {
    val s = LocalUiScale.current
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

    val shape = RoundedCornerShape(8.dp)
    Box(modifier.clip(shape).background(Color.Black)) {
        controller?.Content(Modifier.fillMaxSize(), AspectMode.FIT)
        // Capa transparente: recibe el clic / OK del mando y abre la pantalla completa.
        Box(Modifier.matchParentSize().tvClickable(shape, 1.0f) { onFullscreen() })
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height((64 * s).dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
        )
        if (controller != null && !controller.state.ready && failure == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(32.dp), color = Cm.Gold, strokeWidth = 3.dp)
        }
        failure?.let {
            Column(Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.ErrorOutline, null, tint = Cm.Danger, modifier = Modifier.size(30.dp))
                Spacer(Modifier.height(4.dp))
                Text("Este canal no responde", color = Color.White, fontSize = 14.sp)
                Text("Elige otro de la lista", color = Cm.Muted, fontSize = 11.sp)
            }
        }
        // Categoría actual (toca para cambiarla)
        Row(
            Modifier.align(Alignment.TopStart).padding(8.dp)
                .tvClickable(RoundedCornerShape(50), 1.05f) { onPickGroup() }
                .background(Color(0x99000000), RoundedCornerShape(50))
                .padding(horizontal = (10 * s).dp, vertical = (4 * s).dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.FilterList, null, tint = Color.White, modifier = Modifier.size((14 * s).dp))
            Spacer(Modifier.width(4.dp))
            Text(groupLabel, color = Color.White, fontSize = (12 * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
        }
        Row(Modifier.align(Alignment.BottomEnd).padding((10 * s).dp), verticalAlignment = Alignment.CenterVertically) {
            Text(entry.name, color = Color.White, fontSize = (17 * s).sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 360.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size((24 * s).dp).clip(RoundedCornerShape(4.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Fullscreen, "Pantalla completa", tint = Color.White, modifier = Modifier.size((16 * s).dp))
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
