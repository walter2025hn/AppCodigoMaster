package com.codigomaster.iptv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.Outline
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Size as CoilSize

enum class Section(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    HOME("Inicio", Icons.Filled.Home),
    LIVE("TV en vivo", Icons.Filled.LiveTv),
    MOVIES("Películas", Icons.Filled.Movie),
    SERIES("Series", Icons.Filled.Tv),
    FAVS("Mi lista", Icons.Filled.Favorite)
}

/** True en Android TV / TV Box / Fire TV. Se provee desde MainActivity. */
val LocalTv = staticCompositionLocalOf { false }

/**
 * Clic con efecto de foco para mando a distancia / D-pad.
 * El anillo dorado se dibuja en la fase de dibujo (sin border/clip dinámicos) y la escala
 * se lee dentro de graphicsLayer: mover el foco solo repinta la tarjeta, no recompone su contenido.
 */
fun Modifier.tvClickable(
    shape: Shape = RoundedCornerShape(14.dp),
    focusScale: Float = 1.05f,
    onClick: () -> Unit
): Modifier = tvInteractive(shape, focusScale, Modifier.clickable(onClick = onClick))

/** Igual que [tvClickable] pero con pulsación larga (en TV: mantener OK pulsado). */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.tvClickableLong(
    shape: Shape = RoundedCornerShape(14.dp),
    focusScale: Float = 1.05f,
    onLongClick: () -> Unit,
    onClick: () -> Unit
): Modifier = tvInteractive(shape, focusScale, Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick))

private fun Modifier.tvInteractive(shape: Shape, focusScale: Float, click: Modifier): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val scale = animateFloatAsState(if (focused) focusScale else 1f, tween(110), label = "focusScale")
    this
        .zIndex(if (focused) 1f else 0f)
        .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        .onFocusChanged { focused = it.isFocused }
        .clip(shape)
        .then(click)
        .drawWithContent {
            drawContent()
            if (focused) {
                val w = 3.dp.toPx()
                when (val o = shape.createOutline(size, layoutDirection, this)) {
                    is Outline.Rounded -> drawRoundRect(
                        Cm.Gold, topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
                        size = GeoSize(size.width - w, size.height - w),
                        cornerRadius = o.roundRect.topLeftCornerRadius, style = Stroke(w)
                    )
                    else -> drawRoundRect(
                        Cm.Gold, topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
                        size = GeoSize(size.width - w, size.height - w),
                        cornerRadius = CornerRadius(size.minDimension / 2), style = Stroke(w)
                    )
                }
            }
        }
}

/** Botón de icono enfocable (la barra superior de Material no muestra foco claro con el mando). */
@Composable
fun FocusIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.padding(horizontal = 2.dp).size(44.dp).tvClickable(CircleShape, 1.12f) { if (enabled) onClick() },
        contentAlignment = Alignment.Center
    ) { Icon(icon, description, tint = if (enabled) Cm.Text else Cm.Muted, modifier = Modifier.size(24.dp)) }
}

@Composable
fun Poster(
    url: String,
    modifier: Modifier = Modifier,
    fallbackIcon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.Movie,
    contentScale: ContentScale = ContentScale.Crop,
    decodeWidth: Int = 320,
    decodeHeight: Int = 480
) {
    val context = LocalContext.current
    val tv = LocalTv.current
    val request = remember(url, tv, decodeWidth, decodeHeight) {
        ImageRequest.Builder(context)
            .data(url.takeIf { it.startsWith("http://") || it.startsWith("https://") })
            .size(CoilSize(decodeWidth, decodeHeight))
            .precision(Precision.INEXACT)
            .allowRgb565(true)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .crossfade(if (tv) 0 else 160)
            .build()
    }
    Box(modifier.background(Cm.PosterFallback), contentAlignment = Alignment.Center) {
        if (url.isBlank()) Icon(fallbackIcon, null, tint = Cm.Outline, modifier = Modifier.size(34.dp))
        else AsyncImage(request, null, Modifier.fillMaxSize(), contentScale = contentScale)
    }
}

@Composable
fun LiveBadge(modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(6.dp)).background(Cm.Live).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(Color.White))
        Spacer(Modifier.width(4.dp))
        Text("EN VIVO", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.6.sp)
    }
}

@Composable
fun GoldButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.tvClickable(shape, 1.06f, onClick).background(Cm.GoldBrush).padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) { Icon(icon, null, tint = Cm.OnGold, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, color = Cm.OnGold, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
    }
}

@Composable
fun GhostButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.tvClickable(shape, 1.06f, onClick).background(Color(0x33FFFFFF)).padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
fun SectionTitle(title: String, subtitle: String? = null, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(22.dp).clip(RoundedCornerShape(2.dp)).background(Cm.GoldBrush))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Cm.Text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            if (subtitle != null) Text(subtitle, color = Cm.Muted, fontSize = 11.sp)
        }
        if (action != null && onAction != null) {
            Text(
                action, color = Cm.Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.tvClickable(RoundedCornerShape(8.dp), 1.04f, onAction).padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

/** Tarjeta vertical tipo póster (películas y series). */
@Composable
fun PosterCard(
    title: String,
    subtitle: String,
    image: String,
    modifier: Modifier = Modifier,
    badge: String? = null,
    progress: Float? = null,
    ratingKey: String? = null,
    ratingKind: RatingKind = RatingKind.MOVIE,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    var showRate by remember { mutableStateOf(false) }
    val score = if (ratingKey != null) rememberScore(ratingKey, title, ratingKind) else null
    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .then(
                    if (ratingKey != null) Modifier.tvClickableLong(shape, 1.06f, { showRate = true }, onClick)
                    else Modifier.tvClickable(shape, 1.06f, onClick)
                )
        ) {
            Poster(image, Modifier.fillMaxSize())
            if (score != null) RatingBadge(score, Modifier.align(Alignment.TopEnd).padding(6.dp))
            Box(
                Modifier.fillMaxWidth().height(70.dp).align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
            )
            if (badge != null) {
                Text(
                    badge, color = Cm.OnGold, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(5.dp))
                        .background(Cm.Gold).padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Icon(
                Icons.Filled.PlayCircleFilled, null, tint = Color(0xE6FFFFFF),
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(26.dp)
            )
            if (progress != null && progress > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x66FFFFFF))) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(Cm.Gold))
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(title, color = Cm.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
        Text(subtitle, color = Cm.Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (showRate && ratingKey != null) RateDialog(title, ratingKey, ratingKind, { showRate = false })
}

/** Tarjeta horizontal de canal para carruseles. */
@Composable
fun ChannelCard(entry: Entry, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    var showRate by remember { mutableStateOf(false) }
    val score = rememberScore(entry.url, entry.name, RatingKind.CHANNEL)
    if (showRate) RateDialog(entry.name, entry.url, RatingKind.CHANNEL, { showRate = false })
    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 10f).tvClickableLong(shape, 1.06f, { showRate = true }, onClick)
                .background(Brush.linearGradient(listOf(Cm.SurfaceTop, Cm.SurfaceHi)))
        ) {
            Poster(entry.logo, Modifier.fillMaxSize().padding(18.dp), Icons.Filled.LiveTv, ContentScale.Fit, 240, 150)
            LiveBadge(Modifier.align(Alignment.TopStart).padding(7.dp))
            if (score != null) RatingBadge(score, Modifier.align(Alignment.TopEnd).padding(7.dp))
        }
        Spacer(Modifier.height(7.dp))
        Text(entry.name, color = Cm.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(entry.group, color = Cm.Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Fila de canal / episodio para listas. */
@Composable
fun ChannelRow(
    entry: Entry,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    val tv = LocalTv.current
    var showRate by remember { mutableStateOf(false) }
    val score = rememberScore(entry.url, entry.name, RatingKind.CHANNEL)
    if (showRate) RateDialog(entry.name, entry.url, RatingKind.CHANNEL, { showRate = false }, favorite, onToggleFavorite)
    Row(
        Modifier.fillMaxWidth()
            .tvClickableLong(shape, 1.02f, { showRate = true }, onClick)
            .background(Cm.SurfaceHi).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Poster(
            entry.logo,
            Modifier.size(58.dp).clip(RoundedCornerShape(10.dp)).background(Cm.SurfaceTop).padding(6.dp),
            Icons.Filled.LiveTv, ContentScale.Fit, 120, 120
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, color = Cm.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveBadge()
                if (score != null) { Spacer(Modifier.width(6.dp)); RatingBadge(score) }
                Spacer(Modifier.width(8.dp))
                Text(entry.group, color = Cm.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (tv) {
            if (favorite) Icon(Icons.Filled.Favorite, "En mi lista", tint = Cm.Gold, modifier = Modifier.padding(8.dp).size(22.dp))
        } else {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    if (favorite) "Quitar de mi lista" else "Añadir a mi lista",
                    tint = if (favorite) Cm.Gold else Cm.Muted
                )
            }
        }
    }
}

@Composable
fun GroupChips(groups: List<Pair<String, Int>>, selected: String?, onSelect: (String?) -> Unit) {
    if (groups.size < 2) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "__all") { Chip("Todo", selected == null) { onSelect(null) } }
        items(groups, key = { it.first }) { (name, count) ->
            Chip("$name · $count", selected == name) { onSelect(if (selected == name) null else name) }
        }
    }
}

@Composable
private fun Chip(text: String, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        text,
        color = if (active) Cm.OnGold else Cm.Text,
        fontSize = 12.sp,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier.tvClickable(shape, 1.06f, onClick)
            .background(if (active) SolidColor(Cm.Gold) else SolidColor(Cm.SurfaceHi), shape)
            .border(1.dp, if (active) Cm.Gold else Cm.Outline, shape)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

@Composable
fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, message: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = Cm.Outline, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(14.dp))
        Text(title, color = Cm.Text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Spacer(Modifier.height(4.dp))
        Text(message, color = Cm.Muted, fontSize = 13.sp)
    }
}

// ───────────────────────── Barra superior y navegación ─────────────────────────

@Composable
fun TopBar(
    section: Section,
    search: String,
    onSearch: (String) -> Unit,
    loading: Boolean,
    wide: Boolean,
    onRefresh: () -> Unit,
    onSettings: () -> Unit
) {
    var searchOpen by remember { mutableStateOf(false) }
    val showField = wide || searchOpen || search.isNotEmpty()
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!(showField && !wide)) {
            Image(
                painterResource(R.drawable.brand_logo), null,
                Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).border(1.dp, Cm.Gold.copy(alpha = 0.6f), RoundedCornerShape(11.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.width(10.dp))
            Column(if (wide) Modifier else Modifier.weight(1f)) {
                Text("CÓDIGO MASTER", color = Cm.Gold, fontWeight = FontWeight.Black, fontSize = 16.sp, letterSpacing = 1.sp, maxLines = 1)
                Text(section.label.uppercase(), color = Cm.Muted, fontSize = 9.sp, letterSpacing = 1.6.sp, maxLines = 1)
            }
        }
        if (wide) Spacer(Modifier.weight(1f))
        if (showField) {
            SearchField(search, onSearch, if (wide) Modifier.width(360.dp) else Modifier.weight(1f)) {
                onSearch(""); searchOpen = false
            }
        } else {
            FocusIconButton(Icons.Filled.Search, "Buscar") { searchOpen = true }
        }
        if (loading) Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(22.dp), color = Cm.Gold, strokeWidth = 2.dp)
        } else FocusIconButton(Icons.Filled.Refresh, "Actualizar lista") { onRefresh() }
        FocusIconButton(Icons.Filled.Settings, "Ajustes") { onSettings() }
    }
}

@Composable
internal fun SearchField(value: String, onValue: (String) -> Unit, modifier: Modifier, onClose: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(Cm.SurfaceHi)
            .border(if (focused) 2.dp else 1.dp, if (focused) Cm.Gold else Cm.Outline, RoundedCornerShape(14.dp))
            .onFocusChanged { focused = it.hasFocus }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Search, null, tint = Cm.Muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Buscar canales, películas o series…", color = Cm.Muted, fontSize = 14.sp, maxLines = 1)
            BasicTextField(
                value, onValue, singleLine = true,
                textStyle = TextStyle(color = Cm.Text, fontSize = 14.sp),
                cursorBrush = SolidColor(Cm.Gold),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {}),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Icon(Icons.Filled.Close, "Cerrar búsqueda", tint = Cm.Muted, modifier = Modifier.size(20.dp).clickable { onClose() })
    }
}

private val navColors
    @Composable get() = NavigationBarItemDefaults.colors(
        selectedIconColor = Cm.OnGold, selectedTextColor = Cm.Gold, indicatorColor = Cm.Gold,
        unselectedIconColor = Cm.Muted, unselectedTextColor = Cm.Muted
    )

@Composable
fun BottomBar(current: Section, onSelect: (Section) -> Unit) {
    NavigationBar(containerColor = Cm.Surface, tonalElevation = 0.dp) {
        Section.values().forEach { s ->
            NavigationBarItem(
                selected = current == s, onClick = { onSelect(s) }, colors = navColors,
                icon = { Icon(s.icon, s.label) },
                label = { Text(if (s == Section.LIVE) "TV" else s.label, fontSize = 10.sp, maxLines = 1) },
                alwaysShowLabel = true
            )
        }
    }
}

@Composable
fun SideRail(current: Section, onSelect: (Section) -> Unit) {
    Column(
        Modifier.fillMaxHeight().background(Cm.Surface).padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Section.values().forEach { s ->
            val active = current == s
            Column(
                Modifier.width(78.dp).tvClickable(RoundedCornerShape(16.dp), 1.06f) { onSelect(s) }
                    .background(if (active) Cm.Gold else Color.Transparent)
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(s.icon, s.label, tint = if (active) Cm.OnGold else Cm.Muted, modifier = Modifier.size(26.dp))
                Spacer(Modifier.height(3.dp))
                Text(
                    if (s == Section.LIVE) "TV" else s.label,
                    color = if (active) Cm.OnGold else Cm.Muted, fontSize = 11.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, maxLines = 1
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
