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
        setContent { CodigoMasterTheme { CodigoMasterApp(store) } }
    }
}

@Composable
private fun CodigoMasterApp(store: Store) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tv = remember(context) { isTelevision(context) }
    val wide = tv || LocalConfiguration.current.screenWidthDp >= 840

    var entries by remember { mutableStateOf(emptyList<Entry>()) }
    var catalog by remember { mutableStateOf(Catalog(emptyList(), emptyList(), emptyList())) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var statusError by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var section by remember { mutableStateOf(Section.HOME) }
    var selectedSeries by remember { mutableStateOf<Series?>(null) }
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
        message = "Actualizando contenido…"
        scope.launch {
            try {
                val latest = withContext(Dispatchers.IO) { parseM3u(download(store.effectiveUrl)) }
                if (latest.isNotEmpty()) {
                    withContext(Dispatchers.IO) { store.saveCache(latest) }
                    entries = latest
                    message = ""
                } else {
                    message = "La lista llegó vacía; se conserva la versión anterior."
                    statusError = true
                }
            } catch (_: Exception) {
                message = if (entries.isNotEmpty()) "Sin conexión · mostrando la última lista guardada"
                else "No se pudo cargar la lista. Revisa tu conexión."
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

    val q = search.trim()
    val fLive = remember(catalog, q) { if (q.isBlank()) catalog.live else catalog.live.filter { it.name.contains(q, true) || it.group.contains(q, true) } }
    val fMovies = remember(catalog, q) { if (q.isBlank()) catalog.movies else catalog.movies.filter { it.name.contains(q, true) || it.group.contains(q, true) } }
    val fSeries = remember(catalog, q) { if (q.isBlank()) catalog.series else catalog.series.filter { it.title.contains(q, true) || it.group.contains(q, true) } }
    val continueWatching = remember(recents, entries) {
        val byUrl = entries.associateBy { it.url }
        recents.mapNotNull { byUrl[it] }.filter { store.position(it.url) > 10_000 }.take(12)
    }
    val catalogEmpty = catalog.live.isEmpty() && catalog.movies.isEmpty() && catalog.series.isEmpty()

    BackHandler(enabled = play == null && selectedSeries == null && (section != Section.HOME || search.isNotEmpty())) {
        if (search.isNotEmpty()) search = "" else section = Section.HOME
    }

    Surface(Modifier.fillMaxSize(), color = Cm.Bg) {
        val request = play
        if (request != null) {
            PlayerScreen(request, store, favorites, ::toggleFavorite) {
                play = null
                recents = store.recents()
            }
        } else {
            Row(Modifier.fillMaxSize()) {
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
private fun LoadingState() {
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
