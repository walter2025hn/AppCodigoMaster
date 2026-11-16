package com.codigomaster.iptv

import android.os.Bundle
import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.delay
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
        if (!isTelevision(this)) AdsConsentManager.gatherConsent(this)
        val store = Store(applicationContext)
        Cm.load(store)
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
    var accessToken by remember { mutableStateOf("") }
    var deviceKey by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var loginError by remember { mutableStateOf("") }

    if (accessToken.isNotBlank() && deviceKey.isNotBlank()) {
        CodigoMasterHome(store, accessToken, deviceKey)
    } else {
        Surface(Modifier.fillMaxSize(), color = Cm.Bg) {
            var username by remember { mutableStateOf("") }
            var password by remember { mutableStateOf("") }
            Column(
                Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Image(
                    painterResource(R.drawable.brand_logo), null,
                    Modifier.size(112.dp).clip(RoundedCornerShape(24.dp))
                        .border(1.5.dp, Cm.Gold, RoundedCornerShape(24.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.height(18.dp))
                Text("MASTERCode TV", color = Cm.Text, fontSize = 23.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(8.dp))
                Text("Inicia sesión con tu cuenta", color = Cm.Muted, fontSize = 14.sp)
                Spacer(Modifier.height(18.dp))
                OutlinedTextField(
                    value = username, onValueChange = { username = it },
                    label = { Text("Usuario") }, singleLine = true,
                    modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("Contraseña") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (!busy) {
                            busy = true
                            loginError = ""
                            scope.launch {
                                try {
                                    val session = loginClient(context, username, password)
                                    accessToken = session.accessToken
                                    deviceKey = session.deviceKey
                                } catch (e: Exception) {
                                    loginError = e.message ?: "No se pudo iniciar sesión."
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    },
                    enabled = !busy && username.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().heightIn(min = 52.dp)
                ) {
                    Text(if (busy) "VERIFICANDO CUENTA…" else "INICIAR SESIÓN")
                }
                if (loginError.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(loginError, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun CodigoMasterHome(store: Store, accessToken: String, deviceKey: String) {
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
    var controlChecked by remember { mutableStateOf(false) }
    var remoteBlocked by remember { mutableStateOf(true) }
    var remoteError by remember { mutableStateOf(false) }
    var remoteMessage by remember { mutableStateOf("Verificando autorización…") }
    var pendingAnnouncement by remember { mutableStateOf<ClientAnnouncement?>(null) }
    var availableRelease by remember { mutableStateOf<AppRelease?>(null) }
    var dismissedReleaseCode by remember { mutableIntStateOf(0) }
    var contentEnabled by remember { mutableStateOf(true) }
    var parentalEnabled by remember { mutableStateOf(store.parentalProtectionEnabled) }
    var favorites by remember { mutableStateOf(store.favorites()) }
    var recents by remember { mutableStateOf(store.recents()) }
    var lastAdSection by remember { mutableStateOf(Section.HOME) }
    LaunchedEffect(section, play, controlChecked, remoteBlocked) {
        if (lastAdSection != section) {
            lastAdSection = section
            if (play == null && controlChecked && !remoteBlocked && !tv) {
                delay(500)
                (context as? Activity)?.let { AdsManager.showInterstitial(it) }
            }
        }
    }

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
                val selectedId = store.selectedPlaylistId
                val personalUrl = if (selectedId.isNotBlank()) {
                    val saved = withContext(Dispatchers.IO) {
                        listClientPlaylists(accessToken, deviceKey).firstOrNull { it.id == selectedId }
                            ?: throw IllegalStateException("La lista guardada ya no existe en tu cuenta.")
                    }
                    if (!saved.isEnabled || saved.isSuspended) {
                        throw IllegalStateException("La lista está desactivada o suspendida por el administrador.")
                    }
                    withContext(Dispatchers.IO) { loadClientPlaylistUrl(accessToken, deviceKey, saved) }
                } else store.playlistUrl.trim()
                if (personalUrl.isNotEmpty()) {
                    message = "Cargando tu lista personal…"
                    val personalEntries = withContext(Dispatchers.IO) { downloadEntries(personalUrl) }
                    require(personalEntries.isNotEmpty()) { "La lista personal no contiene canales o videos válidos." }
                    withContext(Dispatchers.IO) { store.saveCache(personalEntries) }
                    entries = personalEntries
                    loadedEntriesCount = personalEntries.size
                    message = "Lista personal cargada · ${personalEntries.size} elementos"
                    initialLoadReady = true
                    return@launch
                }
                if (!contentEnabled) {
                    entries = emptyList()
                    loadedEntriesCount = 0
                    message = "El catálogo general está desactivado. Abre Ajustes para agregar tu lista M3U personal."
                    statusError = true
                    initialLoadReady = true
                    return@launch
                }
                val source = PagedCatalogSource()
                val manifest = withContext(Dispatchers.IO) { source.loadManifest() }
                val cached = withContext(Dispatchers.IO) { store.readCache() }
                val storedHashes = withContext(Dispatchers.IO) { store.readPageHashes(source.cacheKey) }
                val cachedPages = cached.chunked(manifest.pageSize)
                // Si esta instalación ya tenía el catálogo completo de la versión anterior,
                // calculamos sus huellas localmente para evitar volver a bajar los 256 mil elementos.
                val previousHashes = if (storedHashes.isNotEmpty()) storedHashes else if (cached.isNotEmpty()) {
                    // También sirve para cachés antiguas o parciales: las páginas presentes se
                    // comparan y solo se descargan las que faltan o tienen cambios.
                    cachedPages.map { pageFingerprint(it) }
                } else emptyList()

                // Cada página local se compara individualmente; las páginas ausentes se descargan.
                val canCompare = cached.isNotEmpty() &&
                    previousHashes.isNotEmpty() &&
                    cachedPages.size == previousHashes.size &&
                    manifest.pageSha256.size == manifest.pageCount

                val changedPages = if (canCompare) {
                    (1..manifest.pageCount).filter { page ->
                        val hashChanged = previousHashes.getOrNull(page - 1) != manifest.pageSha256.getOrNull(page - 1)
                        val shortenedCatalogTail = manifest.pageCount < previousHashes.size && page == manifest.pageCount
                        hashChanged || shortenedCatalogTail
                    }
                } else {
                    (1..manifest.pageCount).toList()
                }

                totalPages = manifest.pageCount
                totalEntries = manifest.total

                if (canCompare && changedPages.isEmpty()) {
                    if (storedHashes.isEmpty()) {
                        withContext(Dispatchers.IO) { store.savePageHashes(source.cacheKey, manifest.pageSha256) }
                    }
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
        var wasBlocked = true
        var previousContentEnabled: Boolean? = null
        while (true) {
            try {
                val control = loadClientStatus(accessToken, deviceKey, store.selectedPlaylistId)
                controlChecked = true
                remoteError = false
                remoteBlocked = control.isSuspended
                remoteMessage = control.maintenanceMessage
                if (pendingAnnouncement == null) pendingAnnouncement = control.announcements.firstOrNull()
                val candidateRelease = control.latestRelease
                availableRelease = candidateRelease?.takeIf { it.versionCode > BuildConfig.VERSION_CODE && it.versionCode != dismissedReleaseCode }
                val catalogPermissionChanged = previousContentEnabled != null &&
                    previousContentEnabled != control.contentEnabled
                contentEnabled = control.contentEnabled
                previousContentEnabled = control.contentEnabled

                if (control.isSuspended) {
                    wasBlocked = true
                    play = null
                    selectedSeries = null
                    selectedMovie = null
                    showSettings = false
                    initialLoadReady = false
                } else if (catalogPermissionChanged) {
                    entries = emptyList()
                    loadedEntriesCount = 0
                    initialLoadReady = false
                    delay(100)
                    refresh()
                    wasBlocked = false
                } else if (wasBlocked) {
                    val cached = withContext(Dispatchers.IO) { store.readCache() }
                    if (cached.isNotEmpty()) {
                        entries = cached
                        loadedEntriesCount = cached.size
                        initialLoadReady = true
                    }
                    refresh()
                    wasBlocked = false
                }
            } catch (e: Exception) {
                controlChecked = true
                remoteError = true
                remoteBlocked = true
                remoteMessage = "No se pudo verificar el permiso de uso. Conéctate a Internet e inténtalo de nuevo."
                wasBlocked = true
                play = null
                selectedSeries = null
                selectedMovie = null
                showSettings = false
            }
            // Revisa el control remoto mientras la app está abierta; al suspender,
            // la pantalla bloqueante aparece sin tener que salir y volver a entrar.
            delay(10_000)
        }
    }

    // Sincroniza las modificaciones globales del panel sin descargar de nuevo las 256 mil entradas.
    // La APK consulta únicamente las diferencias guardadas en Supabase cada 60 segundos.
    LaunchedEffect(initialLoadReady, accessToken, deviceKey, contentEnabled, loading) {
        if (!initialLoadReady || loading || !contentEnabled ||
            store.selectedPlaylistId.isNotBlank() || store.playlistUrl.isNotBlank()) return@LaunchedEffect
        while (true) {
            try {
                val base = withContext(Dispatchers.IO) { store.readCache() }
                if (base.isNotEmpty()) {
                    val changes = loadClientCatalogOverrides(accessToken, deviceKey)
                    entries = withContext(Dispatchers.Default) { applyCatalogOverrides(base, changes) }
                }
            } catch (_: Exception) {
                // Si no hay conexión, se conserva el catálogo que ya está en pantalla.
            }
            delay(60_000)
        }
    }

    LaunchedEffect(entries) { catalog = withContext(Dispatchers.Default) { buildCatalog(entries) } }
    LaunchedEffect(initialLoadReady, controlChecked, remoteBlocked) {
        if (initialLoadReady && controlChecked && !remoteBlocked) {
            delay(3600)
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
    val displayCatalog = remember(catalog, parentalEnabled) {
        if (parentalEnabled) catalog.withoutAdultContent() else catalog
    }
    val filtered by produceState(Filtered(displayCatalog.live, displayCatalog.movies, displayCatalog.series), displayCatalog, q) {
        value = if (q.isBlank()) Filtered(displayCatalog.live, displayCatalog.movies, displayCatalog.series)
        else withContext(Dispatchers.Default) {
            Filtered(
                displayCatalog.live.filter { it.name.contains(q, true) || it.group.contains(q, true) },
                displayCatalog.movies.filter { it.name.contains(q, true) || it.group.contains(q, true) },
                displayCatalog.series.filter { it.title.contains(q, true) || it.group.contains(q, true) }
            )
        }
    }
    val fLive = filtered.live
    val fMovies = filtered.movies
    val fSeries = filtered.series
    val continueWatching = remember(recents, entries, parentalEnabled) {
        val byUrl = entries.filterNot { parentalEnabled && isAdultContent(it.name, it.group, it.attributes) }.associateBy { it.url }
        recents.mapNotNull { byUrl[it] }.filter { store.position(it.url) > 10_000 }.take(12)
    }
    val catalogEmpty = displayCatalog.live.isEmpty() && displayCatalog.movies.isEmpty() && displayCatalog.series.isEmpty()

    BackHandler(enabled = play == null && selectedSeries == null && selectedMovie == null && !showSettings) {
        when {
            search.isNotEmpty() -> search = ""
            section != Section.HOME -> section = Section.HOME
            else -> showExitDialog = true
        }
    }

    Surface(Modifier.fillMaxSize(), color = Cm.Bg) {
        val request = play
        if (!controlChecked) {
            Box(Modifier.fillMaxSize().background(Cm.Bg), contentAlignment = Alignment.Center) {
                Text("Verificando autorización…", color = Cm.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        } else if (remoteBlocked) {
            Column(
                Modifier.fillMaxSize().background(Cm.Bg).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Image(
                    painterResource(R.drawable.brand_logo), null,
                    Modifier.size(112.dp).clip(RoundedCornerShape(24.dp)).border(1.5.dp, Cm.Gold, RoundedCornerShape(24.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.height(22.dp))
                Text(
                    if (remoteError) "No se pudo verificar el acceso" else "MasterCode TV está suspendida",
                    color = Cm.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(10.dp))
                Text(remoteMessage, color = Cm.Muted, fontSize = 14.sp)
                Spacer(Modifier.height(14.dp))
                Text("El acceso se comprobará automáticamente.", color = Cm.Muted, fontSize = 12.sp)
            }
        } else if (request != null) {
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
                        movie = detailMovie, series = detailSeries, catalog = displayCatalog, store = store,
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
                    catalogEmpty -> if (parentalEnabled) 2 else 1
                    else -> 0
                }
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        ClassicHome(
                    state = classicState, store = store, catalog = displayCatalog,
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
                    if (!tv) BannerAd()
                }
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
                        if (!tv && section == Section.HOME && selectedSeries == null) NativeAdCard()
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when {
                                entries.isEmpty() && loading -> LoadingState()
                                entries.isEmpty() -> EmptyState(Icons.Filled.CloudOff, "Sin contenido", "No pudimos cargar la lista. Toca actualizar para reintentar.")
                                catalogEmpty -> if (parentalEnabled) EmptyState(Icons.Filled.Lock, "Contenido protegido", "El control parental está ocultando el contenido +18. Puedes cambiarlo en Ajustes.") else LoadingState()
                                q.isNotBlank() && (section == Section.HOME || section == Section.FAVS) ->
                                    SearchResults(fLive, fMovies, fSeries, wide, { play = it }, { selectedSeries = it })
                                else -> when (section) {
                                    Section.HOME -> HomeScreen(displayCatalog, continueWatching, store, favorites, wide, { section = it }, { play = it }, { selectedSeries = it }, ::toggleFavorite)
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
                                    Section.FAVS -> FavoritesScreen(displayCatalog, favorites, wide, { play = it }, { selectedSeries = it })
                                }
                            }
                        }
                    if (!wide && detail == null) BottomBar(section) { section = it; search = "" }
                    if (!tv) BannerAd()
                }
            }
        }
    }

    if (showIntro && controlChecked && !remoteBlocked) {
        val reveal by animateFloatAsState(1f, animationSpec = tween(1050, easing = FastOutSlowInEasing), label = "masterIntroReveal")
        val motion = rememberInfiniteTransition(label = "masterIntroMotion")
        val pulse by motion.animateFloat(0.88f, 1.08f, infiniteRepeatable(tween(1250), RepeatMode.Reverse), label = "masterIntroPulse")
        val orbit by motion.animateFloat(0f, 360f, infiniteRepeatable(tween(9000)), label = "masterIntroOrbit")
        Box(
            Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF02050D), Color(0xFF071A32), Color(0xFF030711)))),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier.size(340.dp).graphicsLayer { scaleX = pulse; scaleY = pulse; alpha = 0.55f }
                    .background(Brush.radialGradient(listOf(Cm.Gold.copy(alpha = 0.24f), Color(0xFF1478B8).copy(alpha = 0.10f), Color.Transparent)), CircleShape)
            )
            Box(
                Modifier.size(286.dp).graphicsLayer { rotationZ = orbit; scaleX = 0.78f + reveal * 0.22f; scaleY = 0.78f + reveal * 0.22f; alpha = reveal }
                    .border(1.5.dp, Cm.Gold.copy(alpha = 0.75f), CircleShape)
            )
            Box(
                Modifier.size(244.dp).graphicsLayer { rotationZ = -orbit * 0.72f; scaleX = 0.82f + reveal * 0.18f; scaleY = 0.82f + reveal * 0.18f; alpha = 0.55f + reveal * 0.35f }
                    .border(1.dp, Color(0xFF4AB8FF).copy(alpha = 0.65f), CircleShape)
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Image(
                    painterResource(R.drawable.brand_logo), null,
                    Modifier.size(156.dp).graphicsLayer {
                        val entrance = 0.52f + reveal * 0.48f
                        scaleX = entrance * pulse; scaleY = entrance * pulse
                        rotationZ = (1f - reveal) * -16f; alpha = reveal
                    }.clip(RoundedCornerShape(30.dp)).border(2.dp, Cm.Gold, RoundedCornerShape(30.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.height(28.dp))
                Text("CÓDIGO MASTER TV", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = 2.4.sp,
                    modifier = Modifier.graphicsLayer { translationY = (1f - reveal) * 24f; alpha = reveal })
                Spacer(Modifier.height(9.dp))
                Text("EL ENTRETENIMIENTO COBRA VIDA", color = Cm.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.1.sp,
                    modifier = Modifier.graphicsLayer { translationY = (1f - reveal) * 18f; alpha = reveal })
                Spacer(Modifier.height(30.dp))
                LinearProgressIndicator(Modifier.width(142.dp).height(2.dp).graphicsLayer { alpha = reveal }, color = Cm.Gold, trackColor = Color.White.copy(alpha = 0.14f))
            }
        }
    }

    pendingAnnouncement?.let { notice ->
        AlertDialog(
            onDismissRequest = { if (!notice.forceAck) scope.launch { try { acknowledgeClientAnnouncement(accessToken, deviceKey, notice.id); pendingAnnouncement = null } catch (_: Exception) { } } },
            containerColor = Cm.SurfaceHi,
            titleContentColor = Cm.Text,
            textContentColor = Cm.Text,
            title = { Text(notice.title, fontWeight = FontWeight.Bold) },
            text = { Text(notice.message) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try { acknowledgeClientAnnouncement(accessToken, deviceKey, notice.id); pendingAnnouncement = null } catch (_: Exception) { }
                    }
                }) { Text("ENTENDIDO", color = Cm.Gold, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                if (!notice.forceAck) TextButton(onClick = { scope.launch { try { acknowledgeClientAnnouncement(accessToken, deviceKey, notice.id); pendingAnnouncement = null } catch (_: Exception) { } } }) { Text("CERRAR", color = Cm.Muted) }
            }
        )
    }

    availableRelease?.let { release ->
        AlertDialog(
            onDismissRequest = { if (!release.forceUpdate) dismissedReleaseCode = release.versionCode },
            containerColor = Cm.SurfaceHi,
            titleContentColor = Cm.Text,
            textContentColor = Cm.Text,
            title = { Text("Actualización disponible · ${release.versionName}", fontWeight = FontWeight.Bold) },
            text = { Text(release.releaseNotes.ifBlank { "Hay una nueva versión de MasterCode TV disponible." }) },
            confirmButton = {
                TextButton(onClick = { try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.downloadUrl))) } catch (_: Exception) { } }) { Text("ACTUALIZAR", color = Cm.Gold, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                if (!release.forceUpdate) TextButton(onClick = { dismissedReleaseCode = release.versionCode }) { Text("MÁS TARDE", color = Cm.Muted) }
            }
        )
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
        SettingsDialog(store, accessToken, deviceKey, onSave = { parentalEnabled = store.parentalProtectionEnabled; showSettings = false; refresh() }, onDismiss = { parentalEnabled = store.parentalProtectionEnabled; showSettings = false })
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
