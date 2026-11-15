package com.codigomaster.iptv

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Cola de reproducción: canales de una lista, episodios de una serie o una sola película. */
data class PlayRequest(
    val queue: List<Entry>,
    val index: Int,
    val live: Boolean,
    val seriesKey: String? = null,
    val seriesTitle: String? = null,
    /** Abre el panel de audio / subtítulos / pantalla nada más empezar (botones Idioma y Resolución de la ficha). */
    val openMenu: Boolean = false
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

fun isTelevision(context: Context): Boolean =
    (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION ||
        context.packageManager.hasSystemFeature("android.software.leanback")

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

@Composable
fun PlayerScreen(
    request: PlayRequest,
    store: Store,
    favorites: Set<String>,
    onToggleFavorite: (String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(context) { context.findActivity() }
    val tv = remember(context) { isTelevision(context) }

    var index by remember { mutableIntStateOf(request.index.coerceIn(0, request.queue.lastIndex)) }
    val entry = request.queue[index]
    val favKey = request.seriesKey ?: entry.url

    val startEngine = remember(entry.url) { if (store.engine == "VLC") EngineType.VLC else EngineType.EXO }
    var engine by remember(entry.url) { mutableStateOf(startEngine) }
    var tried by remember(entry.url) { mutableStateOf(setOf<EngineType>()) }
    var failure by remember(entry.url) { mutableStateOf<String?>(null) }
    var notice by remember(entry.url) { mutableStateOf<String?>(null) }
    var reload by remember(entry.url) { mutableIntStateOf(0) }

    val fatal = rememberUpdatedState<(String) -> Unit> { msg ->
        val current = engine
        val newTried = tried + current
        tried = newTried
        val other = if (current == EngineType.EXO) EngineType.VLC else EngineType.EXO
        if (other !in newTried) {
            notice = "Cambiando al motor ${other.label}…"
            engine = other
        } else failure = msg
    }

    val controller: Controller? = remember(entry.url, engine, reload) {
        createController(engine, context, entry, request.live, if (request.live) 0L else store.position(entry.url)) { fatal.value(it) }
    }

    DisposableEffect(controller) {
        onDispose {
            if (controller != null) {
                val st = controller.state
                if (!request.live && st.ready && st.duration > 0) {
                    if (st.position > st.duration - 30_000) store.clearPosition(entry.url)
                    else if (st.position > 10_000) store.savePosition(entry.url, st.position)
                }
                controller.release()
            }
        }
    }

    LaunchedEffect(controller) { if (controller == null) fatal.value("No se pudo iniciar el motor de vídeo") }
    LaunchedEffect(controller) {
        if (controller != null) {
            delay(25_000)
            if (!controller.state.ready) fatal.value("Tiempo de espera agotado")
        }
    }
    LaunchedEffect(entry.url) { if (!request.live) store.pushRecent(entry.url) }
    LaunchedEffect(controller) {
        var tick = 0
        while (isActive) {
            controller?.poll()
            tick++
            if (controller != null && tick % 12 == 0 && !request.live && controller.state.ready && controller.state.duration > 0 && controller.state.position > 10_000) {
                store.savePosition(entry.url, controller.state.position)
            }
            delay(500)
        }
    }
    LaunchedEffect(notice) { if (notice != null) { delay(3500); notice = null } }

    // Velocidad de descarga de la app (esquina superior derecha en películas y series).
    var speed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(request.live) {
        if (request.live) return@LaunchedEffect
        val uid = android.os.Process.myUid()
        var last = android.net.TrafficStats.getUidRxBytes(uid)
        var lastT = System.nanoTime()
        while (isActive) {
            delay(1000)
            val now = android.net.TrafficStats.getUidRxBytes(uid)
            val t = System.nanoTime()
            speed = if (last >= 0 && now >= last && t > lastT) {
                val kb = ((now - last) * 1_000_000_000.0 / (t - lastT) / 1024.0).toLong()
                if (kb >= 1024) String.format(java.util.Locale.US, "%.1fMB/S", kb / 1024.0) else "${kb}KB/S"
            } else null
            last = now; lastT = t
        }
    }

    // Pantalla completa, orientación horizontal en móviles y pantalla siempre encendida.
    DisposableEffect(Unit) {
        val window = activity?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, view) }
        val previous = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        if (!tv) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            insets?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = previous
        }
    }

    // Siguiente episodio automático.
    val ended = controller?.state?.ended ?: false
    LaunchedEffect(ended) {
        if (ended && !request.live) {
            store.clearPosition(entry.url)
            if (index < request.queue.lastIndex) index++ else onClose()
        }
    }

    var visible by remember { mutableStateOf(true) }
    var poke by remember { mutableIntStateOf(0) }
    var showMenu by remember { mutableStateOf(request.openMenu) }
    var aspect by remember { mutableStateOf(AspectMode.FIT) }
    val playing = controller?.state?.playing == true

    LaunchedEffect(visible, poke, playing, showMenu, failure) {
        if (visible && !showMenu && playing && failure == null) { delay(4500); visible = false }
    }

    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(visible, failure) {
        delay(60)
        try { if (visible && failure == null && request.live) playFocus.requestFocus() else rootFocus.requestFocus() } catch (_: Exception) { }
    }

    val queueSize = request.queue.size
    fun step(delta: Int) {
        if (queueSize < 2) return
        val target = index + delta
        index = if (request.live) ((target % queueSize) + queueSize) % queueSize else target.coerceIn(0, queueSize - 1)
        poke++
    }
    val stepRef = rememberUpdatedState<(Int) -> Unit> { step(it) }
    val ctrlRef = rememberUpdatedState(controller)

    BackHandler(enabled = !showMenu) { onClose() }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(rootFocus).focusable()
            .onPreviewKeyEvent { ev ->
                val c = ctrlRef.value
                if (ev.type != KeyEventType.KeyDown || c == null) return@onPreviewKeyEvent false
                val isLive = c.state.live
                when (ev.key) {
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { c.toggle(); poke++; visible = true; true }
                    Key.MediaFastForward -> { if (!isLive) c.seekBy(30_000); poke++; visible = true; true }
                    Key.MediaRewind -> { if (!isLive) c.seekBy(-30_000); poke++; visible = true; true }
                    Key.MediaNext, Key.ChannelUp -> { stepRef.value(1); true }
                    Key.MediaPrevious, Key.ChannelDown -> { stepRef.value(-1); true }
                    else -> if (!request.live && failure == null) when (ev.key) {
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { c.toggle(); visible = true; poke++; true }
                        Key.DirectionLeft -> { c.seekBy(-10_000); visible = true; poke++; true }
                        Key.DirectionRight -> { c.seekBy(10_000); visible = true; poke++; true }
                        Key.DirectionUp -> { showMenu = true; poke++; true }
                        Key.DirectionDown -> { if (visible) visible = false else { visible = true; poke++ }; true }
                        else -> false
                    } else if (visible) false else when (ev.key) {
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { visible = true; poke++; true }
                        Key.DirectionLeft -> { if (!isLive) c.seekBy(-10_000); visible = true; poke++; true }
                        Key.DirectionRight -> { if (!isLive) c.seekBy(10_000); visible = true; poke++; true }
                        Key.DirectionUp -> { if (queueSize > 1) stepRef.value(-1) else { visible = true; poke++ }; true }
                        Key.DirectionDown -> { if (queueSize > 1) stepRef.value(1) else { visible = true; poke++ }; true }
                        else -> false
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (visible) visible = false else { visible = true; poke++ } },
                    onDoubleTap = { offset ->
                        val c = ctrlRef.value
                        if (c != null && !c.state.live) {
                            c.seekBy(if (offset.x < size.width / 2) -10_000L else 10_000L)
                            visible = true; poke++
                        }
                    }
                )
            }
    ) {
        if (controller != null) {
            key(controller) { controller.Content(Modifier.fillMaxSize(), aspect) }
        }
        val st = controller?.state

        if (st != null && st.buffering && failure == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(56.dp), color = Cm.Gold, strokeWidth = 4.dp)
        }

        notice?.let {
            Text(
                it, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 70.dp)
                    .clip(RoundedCornerShape(20.dp)).background(Color(0xCC000000)).padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        AnimatedVisibility(visible && failure == null && controller != null, enter = fadeIn(), exit = fadeOut()) {
            if (controller != null) {
                if (request.live) PlayerControls(
                    controller = controller,
                    title = request.seriesTitle ?: entry.name,
                    subtitle = if (request.seriesTitle != null) entry.name else entry.group,
                    positionLabel = if (queueSize > 1) "${index + 1} / $queueSize" else null,
                    favorite = favKey in favorites,
                    hasPrev = queueSize > 1 && (request.live || index > 0),
                    hasNext = queueSize > 1 && (request.live || index < queueSize - 1),
                    playFocus = playFocus,
                    onBack = onClose,
                    onFavorite = { onToggleFavorite(favKey) },
                    onMenu = { showMenu = true; poke++ },
                    onPrev = { step(-1) },
                    onNext = { step(1) },
                    onInteract = { poke++ }
                ) else VodControls(
                    controller = controller,
                    title = request.seriesTitle ?: entry.name,
                    episode = if (request.seriesTitle != null) entry.name else null,
                    poster = entry.logo,
                    speed = speed,
                    hasNext = queueSize > 1 && index < queueSize - 1,
                    onBack = onClose,
                    onMenu = { showMenu = true; poke++ },
                    onNext = { step(1) },
                    onInteract = { poke++ },
                    onHide = { visible = false }
                )
            }
        }

        failure?.let { msg ->
            Column(
                Modifier.align(Alignment.Center).padding(24.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xE6101626)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Filled.ErrorOutline, null, tint = Cm.Danger, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(10.dp))
                Text("No se pudo reproducir", color = Cm.Text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(4.dp))
                Text("Se probaron ExoPlayer y VLC. El enlace puede estar caído o requerir otra conexión.", color = Cm.Muted, fontSize = 12.sp)
                Text(msg, color = Cm.Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GoldButton("Reintentar", Icons.Filled.Refresh, {
                        failure = null; tried = emptySet(); engine = startEngine; reload++
                    })
                    GhostButton("Volver", Icons.Filled.ArrowBack, onClose)
                }
            }
        }
    }

    if (showMenu && controller != null) {
        PlayerMenu(
            controller = controller,
            engine = engine,
            aspect = aspect,
            onEngine = {
                if (it != engine) { tried = emptySet(); failure = null; engine = it; notice = "Motor: ${it.label}" }
                showMenu = false
            },
            onAspect = { aspect = it },
            onDismiss = { showMenu = false }
        )
    }
}

@Composable
private fun PlayerControls(
    controller: Controller,
    title: String,
    subtitle: String,
    positionLabel: String?,
    favorite: Boolean,
    hasPrev: Boolean,
    hasNext: Boolean,
    playFocus: FocusRequester,
    onBack: () -> Unit,
    onFavorite: () -> Unit,
    onMenu: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onInteract: () -> Unit
) {
    val st = controller.state
    Box(Modifier.fillMaxSize()) {
        // Barra superior
        Row(
            Modifier.fillMaxWidth().align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                .statusBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundButton(Icons.Filled.ArrowBack, "Volver", 44.dp, onBack)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (st.live) { LiveBadge(); Spacer(Modifier.width(8.dp)) }
                    Text(subtitle, color = Color(0xFFC3CCDD), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (positionLabel != null) Text("  ·  $positionLabel", color = Cm.Muted, fontSize = 12.sp)
                }
            }
            Text(
                controller.engine.label, color = Cm.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x55000000)).padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Spacer(Modifier.width(8.dp))
            RoundButton(if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Mi lista", 44.dp, onFavorite, tint = if (favorite) Cm.Gold else Color.White)
            Spacer(Modifier.width(6.dp))
            RoundButton(Icons.Filled.Tune, "Ajustes de reproducción", 44.dp, onMenu)
        }

        // Controles centrales
        Row(
            Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            if (hasPrev) RoundButton(Icons.Filled.SkipPrevious, "Anterior", 52.dp, onPrev)
            if (!st.live) RoundButton(Icons.Filled.Replay10, "Retroceder 10 s", 52.dp, { controller.seekBy(-10_000); onInteract() })
            RoundButton(
                if (st.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                if (st.playing) "Pausa" else "Reproducir", 72.dp,
                { controller.toggle(); onInteract() },
                modifier = Modifier.focusRequester(playFocus), filled = true
            )
            if (!st.live) RoundButton(Icons.Filled.Forward10, "Adelantar 10 s", 52.dp, { controller.seekBy(10_000); onInteract() })
            if (hasNext) RoundButton(Icons.Filled.SkipNext, "Siguiente", 52.dp, onNext)
        }

        // Barra de progreso
        if (!st.live && st.duration > 0) {
            var dragging by remember { mutableStateOf(false) }
            var dragValue by remember { mutableFloatStateOf(0f) }
            Column(
                Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                    .navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Slider(
                    value = if (dragging) dragValue else st.position.toFloat().coerceIn(0f, st.duration.toFloat()),
                    onValueChange = { dragging = true; dragValue = it; onInteract() },
                    onValueChangeFinished = { controller.seekTo(dragValue.toLong()); dragging = false },
                    valueRange = 0f..st.duration.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = Cm.Gold, activeTrackColor = Cm.Gold, inactiveTrackColor = Color(0x44FFFFFF)
                    )
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
                    Text(formatTime(if (dragging) dragValue.toLong() else st.position), color = Color.White, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    Text(formatTime(st.duration), color = Color(0xFFC3CCDD), fontSize = 12.sp)
                }
            }
        }
    }
}

// ───────────────────── Controles de películas, series, kids y anime ─────────────────────

@Composable
private fun VodControls(
    controller: Controller,
    title: String,
    episode: String?,
    poster: String,
    speed: String?,
    hasNext: Boolean,
    onBack: () -> Unit,
    onMenu: () -> Unit,
    onNext: () -> Unit,
    onInteract: () -> Unit,
    onHide: () -> Unit
) {
    val st = controller.state
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxWidth().fillMaxHeight(0.42f).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
        )
        if (speed != null) Text(
            speed, color = Color.White, fontSize = 15.sp,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 14.dp, end = 22.dp)
        )
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().navigationBarsPadding()
                .padding(start = 8.dp, end = 14.dp, bottom = 26.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Poster(
                poster, Modifier.width(62.dp).aspectRatio(0.85f).clip(RoundedCornerShape(2.dp)),
                Icons.Filled.Movie, ContentScale.Crop, 240, 280
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatTime(st.position), color = Color.White, fontSize = 14.sp)
                    SeekBar(
                        st.position, st.duration, { controller.seekTo(it) }, onInteract,
                        Modifier.weight(1f).padding(horizontal = 10.dp)
                    )
                    Text(if (st.duration > 0) formatTime(st.duration) else "--:--", color = Color(0xFFB0B0B0), fontSize = 14.sp)
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(title, color = Color.White, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (episode != null) Text(episode, color = Color(0xFFB0B0B0), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                        KeyHint("EXIT", null, "Vuelta", onBack)
                        KeyHint("OK", null, "Reproducir/Pausar") { controller.toggle(); onInteract() }
                        KeyHint(null, Icons.Filled.ArrowLeft, "Detrás") { controller.seekBy(-10_000); onInteract() }
                        KeyHint(null, Icons.Filled.ArrowRight, "Adelante") { controller.seekBy(10_000); onInteract() }
                        KeyHint(null, Icons.Filled.ArrowDropUp, "Info", onMenu)
                        if (hasNext) KeyHint(null, Icons.Filled.SkipNext, "Siguiente", onNext)
                    }
                }
            }
        }
        Icon(
            Icons.Filled.KeyboardArrowDown, "Ocultar controles", tint = Color(0xFFCCCCCC),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 2.dp).size(30.dp)
                .tvClickable(CircleShape, 1.1f) { onHide() }
        )
    }
}

/** Barra de progreso fina con punto rojo; admite toque y arrastre. */
@Composable
private fun SeekBar(position: Long, duration: Long, onSeek: (Long) -> Unit, onInteract: () -> Unit, modifier: Modifier) {
    var dragFrac by remember { mutableStateOf<Float?>(null) }
    val frac = dragFrac ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    BoxWithConstraints(
        modifier.height(24.dp)
            .pointerInput(duration) {
                detectTapGestures(onTap = { o ->
                    if (duration > 0) { onSeek((o.x / size.width * duration).toLong().coerceIn(0L, duration)); onInteract() }
                })
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragFrac = (o.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { dragFrac?.let { if (duration > 0) onSeek((it * duration).toLong()) }; dragFrac = null },
                    onDragCancel = { dragFrac = null }
                ) { change, _ -> dragFrac = (change.position.x / size.width).coerceIn(0f, 1f); onInteract() }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0x66FFFFFF)))
        Box(Modifier.fillMaxWidth(frac).height(4.dp).background(Color(0xFFB8B8B8)))
        val dot = 14.dp
        Box(
            Modifier.offset(x = (maxWidth * frac - dot / 2).coerceAtLeast(0.dp)).size(dot + 8.dp)
                .background(Color(0x55D72631), CircleShape),
            contentAlignment = Alignment.Center
        ) { Box(Modifier.size(dot).background(Color(0xFFD72631), CircleShape)) }
    }
}

@Composable
private fun KeyHint(badge: String?, icon: ImageVector?, label: String, onClick: () -> Unit) {
    Row(
        Modifier.tvClickable(RoundedCornerShape(4.dp), 1.05f, onClick).padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.clip(RoundedCornerShape(2.dp)).background(Color(0xFFD9D9D9)).padding(horizontal = 5.dp, vertical = 1.dp),
            contentAlignment = Alignment.Center
        ) {
            if (badge != null) Text(badge, color = Color(0xFF222222), fontSize = 8.sp, fontWeight = FontWeight.Bold)
            else if (icon != null) Icon(icon, null, tint = Color(0xFF222222), modifier = Modifier.size(11.dp))
        }
        Spacer(Modifier.width(5.dp))
        Text(label, color = Color.White, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun RoundButton(
    icon: ImageVector,
    description: String,
    size: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    filled: Boolean = false
) {
    Box(
        modifier.size(size).tvClickable(CircleShape, 1.12f, onClick)
            .background(if (filled) Cm.Gold else Color(0x66000000)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, description, tint = if (filled) Cm.OnGold else tint, modifier = Modifier.size(size * 0.55f))
    }
}

@Composable
private fun PlayerMenu(
    controller: Controller,
    engine: EngineType,
    aspect: AspectMode,
    onEngine: (EngineType) -> Unit,
    onAspect: (AspectMode) -> Unit,
    onDismiss: () -> Unit
) {
    val version = controller.state.tracksVersion
    val audio = remember(version) { controller.audioOptions() }
    val subs = remember(version) { controller.subtitleOptions() }
    val video = remember(version) { controller.videoOptions() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Cm.SurfaceHi,
        titleContentColor = Cm.Text,
        textContentColor = Cm.Text,
        title = { Text("Reproducción", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                MenuHeader("Motor de reproducción")
                EngineType.values().forEach { e ->
                    MenuRow(
                        if (e == EngineType.EXO) "ExoPlayer · HLS, DASH, TS, MP4, MKV" else "VLC · RTMP, UDP, códecs raros",
                        e == engine
                    ) { onEngine(e) }
                }
                MenuHeader("Pantalla")
                AspectMode.values().forEach { a -> MenuRow(a.label, a == aspect) { onAspect(a) } }
                MenuHeader("Idioma / Audio")
                if (audio.isNotEmpty()) audio.forEach { o ->
                    MenuRow(o.label, o.selected) { controller.selectAudio(o.id) }
                } else MenuHint("Esta transmisión no ofrece pistas de audio alternativas.")

                MenuHeader("Subtítulos")
                if (subs.isNotEmpty()) subs.forEach { o ->
                    MenuRow(o.label, o.selected) { controller.selectSubtitle(o.id) }
                } else MenuHint("No hay subtítulos disponibles para esta transmisión.")

                MenuHeader("Calidad de video")
                if (video.isNotEmpty()) video.forEach { o ->
                    MenuRow(o.label, o.selected) { controller.selectVideo(o.id) }
                } else MenuHint("La fuente no ofrece varias calidades seleccionables.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar", color = Cm.Gold, fontWeight = FontWeight.Bold) } }
    )
}

@Composable
private fun MenuHeader(text: String) {
    Text(text.uppercase(), color = Cm.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
}

@Composable
private fun MenuHint(text: String) {
    Text(text, color = Cm.Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
}

@Composable
private fun MenuRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().tvClickable(RoundedCornerShape(10.dp), 1.02f, onClick).padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = if (selected) Cm.Gold else Cm.Text, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Filled.Check, null, tint = Cm.Gold, modifier = Modifier.size(18.dp))
    }
}
