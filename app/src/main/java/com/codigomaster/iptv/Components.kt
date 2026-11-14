package com.codigomaster.iptv

import androidx.compose.animation.core.animateFloatAsState
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
import coil.request.ImageRequest

enum class Section(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    HOME("Inicio", Icons.Filled.Home),
    LIVE("TV en vivo", Icons.Filled.LiveTv),
    MOVIES("Películas", Icons.Filled.Movie),
    SERIES("Series", Icons.Filled.Tv),
    FAVS("Mi lista", Icons.Filled.Favorite)
}

/** Clic con efecto de foco (mando a distancia / D-pad): escala + borde dorado. */
fun Modifier.tvClickable(
    shape: Shape = RoundedCornerShape(14.dp),
    focusScale: Float = 1.05f,
    onClick: () -> Unit
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusScale else 1f, label = "focusScale")
    this
        .zIndex(if (focused) 1f else 0f)
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .border(if (focused) 2.5.dp else 0.dp, if (focused) Cm.Gold else Color.Transparent, shape)
        .clip(shape)
        .onFocusChanged { focused = it.isFocused }
        .clickable(onClick = onClick)
}

@Composable
fun Poster(
    url: String,
    modifier: Modifier = Modifier,
    fallbackIcon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.Movie,
    contentScale: ContentScale = ContentScale.Crop
) {
    val context = LocalContext.current
    val request = remember(url) {
        ImageRequest.Builder(context)
            .data(url.takeIf { it.startsWith("http://") || it.startsWith("https://") })
            .crossfade(180)
            .build()
    }
    Box(modifier.background(Cm.PosterFallback), contentAlignment = Alignment.Center) {
        Icon(fallbackIcon, null, tint = Cm.Outline, modifier = Modifier.size(34.dp))
        if (url.isNotBlank()) AsyncImage(request, null, Modifier.fillMaxSize(), contentScale = contentScale)
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
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    Column(modifier) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).tvClickable(shape, 1.06f, onClick)) {
            Poster(image, Modifier.fillMaxSize())
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
}

/** Tarjeta horizontal de canal para carruseles. */
@Composable
fun ChannelCard(entry: Entry, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 10f).tvClickable(shape, 1.06f, onClick)
                .background(Brush.linearGradient(listOf(Cm.SurfaceTop, Cm.SurfaceHi)))
        ) {
            Poster(entry.logo, Modifier.fillMaxSize().padding(18.dp), Icons.Filled.LiveTv, ContentScale.Fit)
            LiveBadge(Modifier.align(Alignment.TopStart).padding(7.dp))
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
    Row(
        Modifier.fillMaxWidth().tvClickable(shape, 1.02f, onClick).background(Cm.SurfaceHi).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Poster(
            entry.logo,
            Modifier.size(58.dp).clip(RoundedCornerShape(10.dp)).background(Cm.SurfaceTop).padding(6.dp),
            Icons.Filled.LiveTv, ContentScale.Fit
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, color = Cm.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveBadge()
                Spacer(Modifier.width(8.dp))
                Text(entry.group, color = Cm.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onToggleFavorite) {
            Icon(
                if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                if (favorite) "Quitar de mi lista" else "Añadir a mi lista",
                tint = if (favorite) Cm.Gold else Cm.Muted
            )
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
            IconButton(onClick = { searchOpen = true }) { Icon(Icons.Filled.Search, "Buscar", tint = Cm.Text) }
        }
        IconButton(onClick = onRefresh, enabled = !loading) {
            if (loading) CircularProgressIndicator(Modifier.size(22.dp), color = Cm.Gold, strokeWidth = 2.dp)
            else Icon(Icons.Filled.Refresh, "Actualizar lista", tint = Cm.Text)
        }
        IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Ajustes", tint = Cm.Text) }
    }
}

@Composable
private fun SearchField(value: String, onValue: (String) -> Unit, modifier: Modifier, onClose: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(Cm.SurfaceHi)
            .border(1.dp, Cm.Outline, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 9.dp),
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
    NavigationRail(containerColor = Cm.Surface, modifier = Modifier.fillMaxHeight()) {
        Spacer(Modifier.weight(1f))
        Section.values().forEach { s ->
            NavigationRailItem(
                selected = current == s, onClick = { onSelect(s) },
                icon = { Icon(s.icon, s.label) },
                label = { Text(if (s == Section.LIVE) "TV" else s.label, fontSize = 11.sp, maxLines = 1) },
                alwaysShowLabel = true,
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = Cm.OnGold, selectedTextColor = Cm.Gold, indicatorColor = Cm.Gold,
                    unselectedIconColor = Cm.Muted, unselectedTextColor = Cm.Muted
                )
            )
            Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.weight(1f))
    }
}

