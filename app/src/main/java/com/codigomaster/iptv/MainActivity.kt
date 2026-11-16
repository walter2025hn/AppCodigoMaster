package com.codigomaster.iptv

import android.os.Bundle
import android.app.Activity
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
    var showExitDialog by remember { mutableStateOf(false) }
    var showIntro by remember { mutableStateOf(true) }
    var initialLoadReady by remember { mutableStateOf(false) }
    var loadedEntriesCount by remember { mutableIntStateOf(0) }
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
        message = "Comprobando actualizaciones…"
        scope.launch {
            try {
                val source = PagedCatalogSource()
                val manifest = withContext(Dispatchers.IO) { source.loadManifest() }
                val cached = withContext(Dispatchers.IO) { store.readCache() }
                val previousHashes = withContext(Dispatchers.IO) { store.readPageHashes(source.cacheKey) }
                val cachedPages = cached.chunked(manifest.pageSize)

                // Solo se puede sincronizar por diferencias cuando tenemos una copia completa
                // y las huellas de todas sus páginas. Si no, se realiza una descarga inicial segura.
                val canCompare = cached.isNotEmpty() &&
                    previousHashes.isNotEmpty() &&
                    cachedPages.size == previousHashes.size &&
                    manifest.pageSha256.size == manifest.pageCount

                val changedPages = if (canCompare) {
                    (1..manifest.pageCount).filter { page ->
                        previousHashes.getOrNull(page - 1) != manifest.pageSha256.getOrNull(page - 1)
                    }
                } else {
                    (1..manifest.pageCount).toList()
                }

                totalPages = manifest.pageCount
                totalEntries = manifest.total

                if (canCompare && changedPages.isEmpty()) {
                    entries = cached
                    loadedEntriesCount = cached.size
                    message = "Catálogo al día · no hay contenido nuevo"
                    initialLoadReady = true
                    return@launch
                }

                val pages = MutableList<List<Entry>?>(manifest.pageCount) { index ->
                    if (canCompare && previousHashes.getOrNull(index) == manifest.pageSha256.getOrNull(index)) {
                        cachedPages.getOrNull(index)
                    } else null
                }

                val initialDownload = !canCompare
                message = if (initialDownload) {
                    "Descargando catálogo por primera vez…"
                } else {
                    "Descargando solo las páginas modificadas…"
                }

                // Las páginas no modificadas se conservan desde la caché local.
                // Las modificadas se solicitan de 50 elementos y en bloques de 20 páginas.
                for (batchPages in changedPages.chunked(20)) {
                    while (play != null) delay(500)
                    for (pageNumber in batchPages) {
                        while (play != null) delay(500)
                        val pageEntries = withContext(Dispatchers.IO) { source.loadPage(pageNumber) }
                        require(pageEntries.isNotEmpty()) { "La página $pageNumber llegó vacía; se conserva la caché anterior." }
                        pages[pageNumber - 1] = pageEntries
                    }

                    // En una primera descarga se conserva el prefijo ya recibido por si la app
                    // se cierra. En una actualización, la caché anterior no se reemplaza hasta
                    // tener todas las páginas nuevas, evitando dejar una mezcla incompleta.
                    if (initialDownload) {
                        val prefix = pages.takeWhile { it != null }.filterNotNull().flatten()
                        if (prefix.isNotEmpty()) {
                            withContext(Dispatchers.IO) { store.saveCache(prefix) }
                            entries = prefix
                            loadedEntriesCount = prefix.size
                        }
                    }
                    message = if (initialDownload) {
                        "Preparando catálogo…"
                    } else {
                        "Aplicando actualizaciones…"
                    }
                    initialLoadReady = true
                }

                require(pages.all { it != null }) { "La sincronización quedó incompleta; se conserva la caché anterior." }
                val complete = pages.filterNotNull().flatten()
                require(complete.isNotEmpty()) { "El catálogo recibido está vacío." }

                withContext(Dispatchers.IO) {
                    store.saveCache(complete)
                    if (manifest.pageSha256.isNotEmpty()) {
                        store.savePageHashes(source.cacheKey, manifest.pageSha256)
                    }
                }
                entries = complete
                loadedEntriesCount = complete.size
                message = if (canCompare) {
                    "Actualización completada · ${complete.size} elementos"
                } else {
                    "Catálogo completo · ${complete.size} elementos"
                }
            } catch (e: Exception) {
                message = if (entries.isNotEmpty()) {
                    "Catálogo guardado disponible · no se pudo completar la actualización"
                } else {
                    "No se pudo cargar el catálogo: ${e.message ?: "revisa tu conexión"}"
                }
                statusError = true
            } finally {
                loading = false
                initialLoadReady = true
            }
        }
    }

    LaunchedEffect(Unit) {
        val cached = withContext(Dispatchers.IO) { store.readCache() }
        if (cached.isNotEmpty()) {
            entries = cached
            loadedEntriesCount = cached.size
            initialLoadReady = true
        }
        refresh()
    }
    LaunchedEffect(entries) { catalog = withContext(Dispatchers.Default) { buildCatalog(entries) } }
    LaunchedEffect(initialLoadReady) {
        if (initialLoadReady) {
            delay(1400)
            showIntro = false
        }
    }

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

    BackHandler(enabled = play == null && selectedSeries == null && selectedMovie == null && !showSettings) {
        when {
            search.isNotEmpty() -> search = ""
            section != Section.HOME -> section = Section.HOME
            else -> showExitDialog = true
        }
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
                    onRefresh = ::refresh,
                    onSettings = { showSettings = true },
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
                                    Section.ANIME -> MixedContentScreen(
                                        "Anime",
                                        fMovies.filter { isAnimeContent(it.name, it.group) },
                                        fSeries.filter { isAnimeContent(it.title, it.group) },
                                        wide, { play = it }, { selectedSeries = it }
                                    )
                                    Section.KIDS -> MixedContentScreen(
                                        "Kids",
                                        fMovies.filter { isKidsContent(it.name, it.group) },
                                        fSeries.filter { isKidsContent(it.title, it.group) },
                                        wide, { play = it }, { selectedSeries = it }
                                    )
                                    Section.FAVS -> FavoritesScreen(catalog, favorites, wide, { play = it }, { selectedSeries = it })
                                }
                            }
                        }
                    if (!wide && detail == null) BottomBar(section) { section = it; search = "" }
                }
            }
        }
    }

    if (showIntro) {
        val pulse by rememberInfiniteTransition(label = "intro").animateFloat(
            initialValue = 0.72f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "introAlpha"
        )
        Box(
            Modifier.fillMaxSize().background(Cm.Bg),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Image(
                    painterResource(R.drawable.brand_logo), null,
                    Modifier.size(132.dp).alpha(pulse).clip(RoundedCornerShape(28.dp))
                        .border(2.dp, Cm.Gold, RoundedCornerShape(28.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.height(20.dp))
                Text("CÓDIGO MASTER TV", color = Cm.Text, fontSize = 21.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tu entretenimiento está listo",
                    color = Cm.Muted, fontSize = 13.sp
                )
            }
        }
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            containerColor = Cm.SurfaceHi,
            titleContentColor = Cm.Text,
            textContentColor = Cm.Text,
            title = { Text("Salir de MasterCode TV", fontWeight = FontWeight.Bold) },
            text = { Text("¿Estás seguro de que deseas salir de la aplicación?") },
            confirmButton = {
                TextButton(onClick = {
                    showExitDialog = false
                    (context as? Activity)?.finish()
                }) { Text("Salir", color = Cm.Gold, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) { Text("Cancelar", color = Cm.Muted) }
            }
        )
    }

    if (showSettings) {
        SettingsDialog(store, onSave = { showSettings = false; refresh() }, onDismiss = { showSettings = false })
    }
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
        // Sin barra ni indicador giratorio: el catálogo se carga en segundo plano.
    }
}
