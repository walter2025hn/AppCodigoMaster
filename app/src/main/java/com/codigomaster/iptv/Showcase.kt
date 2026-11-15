package com.codigomaster.iptv

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Portada estilo «cartelera»: banner doble con puntos, fila de 4 miniaturas apaisadas
 * y filas de pósters con nota (azul) y etiqueta CC (naranja).
 * La usan Destacados, Películas, Series, Kids y Anime.
 */

internal val CineRed = Color(0xFFD72631)
private val CineBlue = Color(0xFF3D9BFF)
private val CineOrange = Color(0xFFF29A2E)
private val TileBg = Color(0xFF1A1A1A)
private val SUBS_HINT = Regex("subtitul|\\bsub\\b|\\bvose\\b|\\bcc\\b", RegexOption.IGNORE_CASE)

/** Película o serie lista para mostrarse en una tarjeta. */
internal class ShowItem(
    val key: String,
    val title: String,
    val image: String,
    val kind: RatingKind,
    val entry: Entry?,
    val series: Series?,
    val subs: Boolean
)

internal class ShowRow(val title: String, val items: List<ShowItem>)

internal fun Entry.toShow() = ShowItem(url, name, logo, RatingKind.MOVIE, this, null, SUBS_HINT.containsMatchIn("$name $group"))
internal fun Series.toShow() = ShowItem(key, title, poster, RatingKind.SERIES, null, this, SUBS_HINT.containsMatchIn("$title $group"))

/** «T4» o «T10 - E89»: última temporada (y episodio si la serie tiene muchos). */
internal fun seriesTag(s: Series): String? {
    var season = 0
    var ep = 0
    for (e in s.episodes) {
        val (a, b) = episodeNumber(e.name)
        if (a > season || (a == season && b > ep)) { season = a; ep = b }
    }
    if (season <= 0) return null
    return if (ep >= 40) "T$season - E$ep" else "T$season"
}

internal enum class ShowMode { FEATURED, MOVIES, SERIES, MIXED }

private fun <T> interleave(a: List<T>, b: List<T>): List<T> {
    val out = ArrayList<T>(a.size + b.size)
    var i = 0
    while (i < a.size || i < b.size) {
        if (i < a.size) out += a[i]
        if (i < b.size) out += b[i]
        i++
    }
    return out
}

/** Reparte el catálogo en: elementos destacados (banner + miniaturas) y filas por categoría. */
internal fun buildShow(c: Catalog, mode: ShowMode): Pair<List<ShowItem>, List<ShowRow>> {
    val movies = c.movies
    val series = c.series
    val mv = movies.filter { it.logo.startsWith("http") }.take(12).map { it.toShow() }
    val sr = series.filter { it.poster.startsWith("http") }.take(12).map { it.toShow() }
    val top = when (mode) {
        ShowMode.MOVIES -> mv
        ShowMode.SERIES -> sr
        else -> interleave(mv, sr)
    }.take(12)

    val rows = ArrayList<ShowRow>()
    fun movieGroups(limit: Int, min: Int) {
        movies.groupBy { it.group }.entries
            .filter { it.value.size >= min && it.key != "Sin categoría" }
            .sortedByDescending { it.value.size }.take(limit)
            .forEach { (g, list) -> rows += ShowRow(g, list.take(24).map { it.toShow() }) }
    }
    fun seriesGroups(limit: Int, min: Int) {
        series.groupBy { it.group }.entries
            .filter { it.value.size >= min && it.key != "Sin categoría" }
            .sortedByDescending { it.value.size }.take(limit)
            .forEach { (g, list) -> rows += ShowRow(g, list.take(24).map { it.toShow() }) }
    }
    when (mode) {
        ShowMode.FEATURED -> {
            if (movies.isNotEmpty()) rows += ShowRow("Películas", movies.take(24).map { it.toShow() })
            movieGroups(3, 6)
            if (series.isNotEmpty()) rows += ShowRow("Series", series.take(24).map { it.toShow() })
        }
        ShowMode.MOVIES -> {
            movieGroups(14, 4)
            if (rows.isEmpty() && movies.isNotEmpty()) rows += ShowRow("Películas", movies.take(40).map { it.toShow() })
        }
        ShowMode.SERIES -> {
            seriesGroups(14, 4)
            if (rows.isEmpty() && series.isNotEmpty()) rows += ShowRow("Series", series.take(40).map { it.toShow() })
        }
        ShowMode.MIXED -> {
            if (movies.isNotEmpty()) rows += ShowRow("Películas", movies.take(30).map { it.toShow() })
            if (series.isNotEmpty()) rows += ShowRow("Series", series.take(30).map { it.toShow() })
        }
    }
    return top to rows
}

@Composable
internal fun Showcase(
    top: List<ShowItem>,
    rows: List<ShowRow>,
    continueRow: List<Entry>,
    liveRow: List<Entry>,
    onOpen: (ShowItem) -> Unit,
    onPlay: (PlayRequest) -> Unit,
    emptyTitle: String
) {
    if (top.isEmpty() && rows.isEmpty() && liveRow.isEmpty() && continueRow.isEmpty()) {
        EmptyState(Icons.Filled.SearchOff, emptyTitle, "No encontramos contenido en esta categoría.")
        return
    }
    val s = LocalUiScale.current
    val hero = remember(top) { top.take(8) }
    val tiles = remember(top) { top.drop(8).take(4) }
    val pages = remember(hero) { hero.chunked(2) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = (4 * s).dp, end = (4 * s).dp, top = (2 * s).dp, bottom = (16 * s).dp),
        verticalArrangement = Arrangement.spacedBy((3 * s).dp)
    ) {
        if (pages.isNotEmpty()) item(key = "hero") { HeroPairs(pages, onOpen) }
        if (tiles.isNotEmpty()) item(key = "tiles") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                tiles.forEach { t -> WideTile(t, Modifier.weight(1f).aspectRatio(1.72f), 17f) { onOpen(t) } }
                repeat(4 - tiles.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (continueRow.isNotEmpty()) item(key = "continue") {
            RailBlock("Continuar viendo") {
                items(continueRow, key = { it.url }) { e ->
                    PosterTile(e.toShow(), Modifier.width((108 * s).dp)) { onPlay(PlayRequest(listOf(e), 0, false)) }
                }
            }
        }
        items(rows.size, key = { "row-" + rows[it].title + it }) { i ->
            val row = rows[i]
            RailBlock(row.title) {
                items(row.items, key = { it.key }) { it0 -> PosterTile(it0, Modifier.width((108 * s).dp)) { onOpen(it0) } }
            }
        }
        if (liveRow.isNotEmpty()) item(key = "live") {
            RailBlock("Canales") {
                items(liveRow.size) { i ->
                    ChannelCard(liveRow[i], Modifier.width((170 * s).dp)) { onPlay(PlayRequest(liveRow, i, true)) }
                }
            }
        }
    }
}

@Composable
private fun RailBlock(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val s = LocalUiScale.current
    Column(Modifier.fillMaxWidth().padding(top = (12 * s).dp)) {
        Text(
            title, color = Color(0xFFE0E0E0), fontWeight = FontWeight.Bold, fontSize = (20 * s).sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp, bottom = (6 * s).dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy((12 * s).dp),
            content = content
        )
    }
}

// ───────────────────────────── Banner doble con puntos ─────────────────────────────

@Composable
private fun HeroPairs(pages: List<List<ShowItem>>, onOpen: (ShowItem) -> Unit) {
    var page by remember(pages) { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    val last = pages.lastIndex

    // Rota sola cada 7 s; se detiene mientras el mando / el dedo están sobre el banner.
    LaunchedEffect(pages.size, focused, page) {
        if (pages.size < 2 || focused) return@LaunchedEffect
        delay(7000)
        page = (page + 1) % pages.size
    }

    Box(
        Modifier.fillMaxWidth()
            .onFocusChanged { focused = it.hasFocus }
            .pointerInput(pages.size) {
                var drag = 0f
                detectHorizontalDragGestures(onDragStart = { drag = 0f }, onDragEnd = {
                    if (drag < -80f) page = (page + 1) % pages.size
                    else if (drag > 80f) page = (page - 1 + pages.size) % pages.size
                }) { _, dx -> drag += dx }
            }
    ) {
        Crossfade(page.coerceIn(0, last), Modifier.fillMaxWidth(), animationSpec = tween(350), label = "showHero") { p ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                pages[p].forEach { item -> WideTile(item, Modifier.weight(1f).aspectRatio(2.15f), 24f) { onOpen(item) } }
                if (pages[p].size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (pages.size > 1) Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
                .background(Color(0x99000000), CircleShape).padding(horizontal = 9.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            pages.indices.forEach { i ->
                Box(
                    Modifier.height(5.dp).width(if (i == page) 22.dp else 5.dp).clip(CircleShape)
                        .background(if (i == page) CineRed else Color(0x88FFFFFF))
                )
            }
        }
    }
}

/**
 * Miniatura apaisada. Con fondo de TMDB: la imagen apaisada y el título abajo.
 * Sin fondo (solo hay póster vertical): póster completo a la izquierda sobre una versión tenue
 * del mismo póster y el título a la derecha, para que no se vea recortado ni deformado.
 */
@Composable
private fun WideTile(item: ShowItem, modifier: Modifier, titleSp: Float, onClick: () -> Unit) {
    val s = LocalUiScale.current
    val meta = rememberMeta(item.title, item.kind)
    val back = meta?.backdrop.orEmpty()
    Box(modifier.tvClickable(RoundedCornerShape(0.dp), 1.03f, onClick).background(TileBg)) {
        if (back.isNotBlank()) {
            Poster(back, Modifier.fillMaxSize(), Icons.Filled.Movie, ContentScale.Crop, 780, 440)
            Box(
                Modifier.fillMaxWidth().fillMaxHeight(0.55f).align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
            )
            Text(
                item.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (titleSp * s * 0.62f).sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = (titleSp * s * 0.72f).sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = (10 * s).dp, vertical = (8 * s).dp)
            )
        } else {
            Poster(item.image, Modifier.fillMaxSize().alpha(0.22f), Icons.Filled.Movie, ContentScale.Crop, 240, 360)
            Row(Modifier.fillMaxSize().padding((8 * s).dp), verticalAlignment = Alignment.CenterVertically) {
                Poster(
                    item.image, Modifier.fillMaxHeight().aspectRatio(2f / 3f).clip(RoundedCornerShape(4.dp)),
                    Icons.Filled.Movie, ContentScale.Crop, 240, 360
                )
                Spacer(Modifier.width((12 * s).dp))
                Text(
                    item.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (titleSp * s * 0.62f).sp,
                    maxLines = 4, overflow = TextOverflow.Ellipsis, lineHeight = (titleSp * s * 0.74f).sp,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ───────────────────────────── Póster con nota y CC ─────────────────────────────

@Composable
internal fun PosterTile(item: ShowItem, modifier: Modifier, aspect: Float = 2f / 3f, centerTitle: Boolean = false, onClick: () -> Unit) {
    val s = LocalUiScale.current
    val tag = remember(item.key) { item.series?.let { seriesTag(it) } }
    val score = rememberScore(item.key, item.title, item.kind)
    Column(modifier.tvClickable(RoundedCornerShape(6.dp), 1.04f, onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(4.dp)).background(TileBg)) {
            Poster(item.image, Modifier.fillMaxSize(), Icons.Filled.Movie, ContentScale.Crop, 240, 360)
            if (score != null) Text(
                String.format(Locale.US, "%.1f", score), color = CineBlue, fontWeight = FontWeight.Bold,
                fontSize = (12 * s).sp,
                modifier = Modifier.align(Alignment.BottomStart).background(Color(0xAA000000)).padding(horizontal = 3.dp)
            )
            if (tag != null) Text(
                tag, color = Color.White, fontSize = (12 * s).sp, maxLines = 1,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp)
            )
            if (item.subs) Text(
                "CC", color = Color.White, fontWeight = FontWeight.Bold, fontSize = (9 * s).sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp)
                    .background(CineOrange, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
        Text(
            item.title, color = Color(0xFFE6E6E6), fontSize = (13 * s).sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = if (centerTitle) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
    }
}
