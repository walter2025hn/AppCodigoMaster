package com.codigomaster.iptv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.delay
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        val store = Store(applicationContext)
        val tv = isTelevision(this)
        val ratings = RatingsRepo(store)
        setContent {
            // En TV se lee a 3 m: texto ~15 % mayor. LocalTv activa los ajustes de mando a distancia.
            val base = LocalDensity.current
            val density = if (tv) Density(base.density, base.fontScale * 1.15f) else base
            CompositionLocalProvider(LocalTv provides tv, LocalDensity provides density, LocalStore provides store, LocalRatings provides ratings) {
                CodigoMasterTheme { CodigoMasterApp(store) }
            }
        }
    }
}

private data class Filtered(val live: List<Entry>, val movies: List<Entry>, val series: List<Series>)

@Composable
private fun CodigoMasterApp(store: Store) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tv = remember(context) { isTelevision(context) }
    val wide = tv || LocalConfiguration.current.screenWidthDp >= 840
    val classic = LocalConfiguration.current.screenWidthDp >= 560
    val classicState = remember { ClassicState() }

    var entries by remember { mutableStateOf(emptyList<Entry>()) }
    var catalog by remember { mutableStateOf(Catalog(emptyList(), emptyList(), emptyList())) }
    var loading by remember { mutableStateOf(false) }
    var nextPage by remember { mutableIntStateOf(1) }
    var totalPages by remember { mutableIntStateOf(0) }
    var totalEntries by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    var statusError by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var section by remember { mutableStateOf(Section.HOME) }
    var selectedSeries by remember { mutableStateOf<Series?>(null) }
    var selectedMovie by remember { mutableStateOf<Entry?>(null) }
    var play by remember { mutableStateOf<PlayRequest?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var favorites by remember { mutableStateOf(store.favorites()) }
    var recents by remember { mutableStateOf(store.recents()) }

    fun toggleFavorite(key: String) {
        favorites = if (key in favorites) favorites - key else favorites + key
        store.saveFavorites(favorites)
    }

    fun refresh() {
        if (loading) return
        loading = true
        statusError = false
        message = "Cargando los primeros 50 elementos…"
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val source = PagedCatalogSource()
                    val manifest = source.loadManifest()
                    val firstPage = if (manifest.pageCount > 0) source.loadPage(1) else emptyList()
                    manifest to firstPage
                }
                val latest = result.second
                if (latest.isNotEmpty()) {
                    withContext(Dispatchers.IO) { store.saveCache(latest) }
                    entries = latest
                    totalPages = result.first.pageCount
                    totalEntries = result.first.total
                    nextPage = 2
                    message = "Mostrando ${latest.size} de ${result.first.total} elementos"
                } else {
                    message = "El catálogo no contiene elementos; se conserva la versión anterior."
                    statusError = true
                }
            } catch (e: Exception) {
                message = if (entries.isNotEmpty()) "Sin conexión · mostrando la última lista guardada"
                else "No se pudo cargar la primera página: ${e.message ?: "revisa tu conexión"}"
                statusError = true
            } finally { loading = false }
        }
    }

    fun loadMore() {
        if (loading || nextPage > totalPages) return
        loading = true
        statusError = false
        message = "Cargando los siguientes 50 elementos…"
        scope.launch {
            try {
                val page = nextPage
                val latest = withContext(Dispatchers.IO) { PagedCatalogSource().loadPage(page) }
                if (latest.isNotEmpty()) {
                    val merged = (entries + latest).distinctBy { it.url }
                    withContext(Dispatchers.IO) { store.saveCache(merged) }
                    entries = merged
                    nextPage = page + 1
                    message = "Mostrando ${merged.size} de ${totalEntries} elementos"
                } else {
                    nextPage = totalPages + 1
                    message = "No hay más elementos disponibles."
                }
            } catch (e: Exception) {
                message = "No se pudo cargar la página ${nextPage}: ${e.message ?: "inténtalo de nuevo"}"
                statusError = true
            } finally { loading = false }
        }
    }

    LaunchedEffect(Unit) {
        val cached = withContext(Dispatchers.IO) { store.readCache() }
        if (cached.isNotEmpty()) entries = cached
        refresh()
    }
    LaunchedEffect(entries) { catalog = withContext(Dispatchers.Default) { buildCatalog(entries) } }

    // Búsqueda: espera a que dejes de escribir y filtra fuera del hilo principal (antes cada tecla
    // recorría miles de títulos en el hilo de la interfaz y la app se quedaba pegada).
    var q by remember { mutableStateOf("") }
    LaunchedEffect(search) {
        val next = search.trim()
        if (next.isEmpty()) q = "" else { delay(300); q = next }
    }
    val filtered by produceState(Filtered(catalog.live, catalog.movies, catalog.series), catalog, q) {
        value = if (q.isBlank()) Filtered(catalog.live, catalog.movies, catalog.series)
        else withContext(Dispatchers.Default) {
            Filtered(
                catalog.live.filter { it.name.contains(q, true) || it.group.contains(q, true) },
                catalog.movies.filter { it.name.contains(q, true) || it.group.contains(q, true) },
                catalog.series.filter { it.title.contains(q, true) || it.group.contains(q, true) }
            )
        }
    }
    val fLive = filtered.live
    val fMovies = filtered.movies
    val fSeries = filtered.series
    val continueWatching = remember(recents, entries) {
        val byUrl = entries.associateBy { it.url }
        recents.mapNotNull { byUrl[it] }.filter { store.position(it.url) > 10_000 }.take(12)
    }
    val catalogEmpty = catalog.live.isEmpty() && catalog.movies.isEmpty() && catalog.series.isEmpty()

    BackHandler(enabled = !classic && play == null && selectedSeries == null && (section != Section.HOME || search.isNotEmpty())) {
        if (search.isNotEmpty()) search = "" else section = Section.HOME
    }

    Surface(Modifier.fillMaxSize(), color = Cm.Bg) {
        val request = play
        if (request != null) {
            PlayerScreen(request, store, favorites, ::toggleFavorite) {
                play = null
                recents = store.recents()
            }
        } else if (classic) {
            val detailSeries = selectedSeries
            val detailMovie = selectedMovie
            if (detailSeries != null || detailMovie != null) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    DetailScreen(
                        movie = detailMovie, series = detailSeries, catalog = catalog, store = store,
                        favorites = favorites, onToggleFavorite = ::toggleFavorite,
                        onPlay = { play = it },
                        onMovie = { selectedMovie = it; selectedSeries = null },
                        onSeries = { selectedSeries = it; selectedMovie = null },
                        onBack = { selectedSeries = null; selectedMovie = null }
                    )
                }
            } else {
                val blocker = when {
                    entries.isEmpty() && loading -> 1
                    entries.isEmpty() -> 2
                    catalogEmpty -> 1
                    else -> 0
                }
                ClassicHome(
                    state = classicState, store = store, catalog = catalog,
                    fLive = fLive, fMovies = fMovies, fSeries = fSeries,
                    q = q, search = search, onSearch = { search = it },
                    continueWatching = continueWatching, favorites = favorites,
                    loading = loading, message = message, statusError = statusError,
                    blocker = blocker,
                    onRefresh = ::refresh, onSettings = { showSettings = true },
                    onPlay = { play = it }, onSeries = { selectedSeries = it; selectedMovie = null },
                    onMovie = { selectedMovie = it; selectedSeries = null },
                    onToggleFavorite = ::toggleFavorite
                )
            }
        } else {
            Row(Modifier.fillMaxSize().then(if (tv) Modifier.padding(end = 18.dp, top = 6.dp, bottom = 6.dp) else Modifier)) {
                if (wide) SideRail(section) { section = it; selectedSeries = null; search = "" }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    val detail = selectedSeries
                    if (detail != null) {
                        Box(Modifier.weight(1f)) {
                            SeriesDetail(
                                detail, detail.key in favorites, wide, store,
                                onToggleFavorite = { toggleFavorite(detail.key) },
                                onPlay = { play = it },
                                onBack = { selectedSeries = null }
                            )
                        }
                    } else {
                        TopBar(section, search, { search = it }, loading, wide, ::refresh, { showSettings = true })
                        if (message.isNotBlank()) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (statusError) Icons.Filled.WifiOff else Icons.Filled.Sync, null,
                                    tint = if (statusError) Cm.Danger else Cm.Muted, modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(message, color = if (statusError) Cm.Danger else Cm.Muted, fontSize = 11.sp, maxLines = 1)
                            }
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when {
                                entries.isEmpty() && loading -> LoadingState()
                                entries.isEmpty() -> EmptyState(Icons.Filled.CloudOff, "Sin contenido", "No pudimos cargar la lista. Toca actualizar para reintentar.")
                                catalogEmpty -> LoadingState()
                                q.isNotBlank() && (section == Section.HOME || section == Section.FAVS) ->
                                    SearchResults(fLive, fMovies, fSeries, wide, { play = it }, { selectedSeries = it })
                                else -> when (section) {
                                    Section.HOME -> HomeScreen(catalog, continueWatching, store, favorites, wide, { section = it }, { play = it }, { selectedSeries = it }, ::toggleFavorite)
                                    Section.LIVE -> LiveScreen(fLive, favorites, wide, { play = it }, ::toggleFavorite)
                                    Section.MOVIES -> MoviesScreen(fMovies, favorites, wide, { play = it })
                                    Section.SERIES -> SeriesScreen(fSeries, favorites, wide) { selectedSeries = it }
                                    Section.FAVS -> FavoritesScreen(catalog, favorites, wide, { play = it }, { selectedSeries = it })
                                }
                            }
                        }
                        if (totalPages > 0 && nextPage <= totalPages && entries.isNotEmpty()) {
                            Button(
                                onClick = ::loadMore,
                                enabled = !loading,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                Text(if (loading) "Cargando…" else "Cargar 50 más · ${entries.size} de ${totalEntries}")
                            }
                        }
                    }
                    if (!wide && detail == null) BottomBar(section) { section = it; search = "" }
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(store, onSave = { showSettings = false; refresh() }, onDismiss = { showSettings = false })
    }
}

@Composable
internal fun LoadingState() {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.55f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha"
    )
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Image(
            painterResource(R.drawable.brand_logo), null,
            Modifier.size(110.dp).alpha(pulse).clip(RoundedCornerShape(26.dp)).border(1.5.dp, Cm.Gold, RoundedCornerShape(26.dp)),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.height(22.dp))
        Text("Preparando tu contenido…", color = Cm.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(Modifier.width(160.dp), color = Cm.Gold, trackColor = Cm.SurfaceTop)
    }
}
