package com.codigomaster.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Panel de filtros: Tipo · Género · Año · País · Audio, con cuadrícula de pósters debajo.
 * Los filtros se deducen del nombre y la categoría (group-title) de cada elemento de la lista M3U.
 */

internal class FItem(
    val key: String,
    val title: String,
    val image: String,
    val entry: Entry?,
    val series: Series?,
    val kids: Boolean,
    val anime: Boolean,
    val year: Int?,
    val genres: Set<String>,
    val countries: Set<String>,
    val audios: Set<String>,
    val subs: Boolean
)

private val GENRES = listOf(
    "Acción" to Regex("acci[oó]n|action"),
    "Drama" to Regex("drama"),
    "Aventuras" to Regex("aventura|adventure"),
    "Crimen" to Regex("crimen|crime|polic[ií]"),
    "Ciencia ficción" to Regex("ciencia ficci|sci-?fi|science fiction"),
    "Comedia" to Regex("comedia|comedy"),
    "Romántico" to Regex("rom[aá]nti|romance"),
    "Terror" to Regex("terror|horror"),
    "Suspenso" to Regex("suspenso|suspense|thriller"),
    "Animación" to Regex("animaci|animation|animad"),
    "Documental" to Regex("documental|documentary"),
    "Familia" to Regex("familia|family"),
    "Fantasía" to Regex("fantas"),
    "Bélico" to Regex("b[eé]lic|guerra|\\bwar\\b"),
    "Misterio" to Regex("misterio|mystery"),
    "Western" to Regex("western|oeste")
)

private val COUNTRIES = listOf(
    "Brasil" to Regex("brasil|brazil"),
    "Portugal" to Regex("portugal"),
    "Estados Unidos" to Regex("estados unidos|\\busa\\b|eeuu|\\bus\\b"),
    "México" to Regex("m[eé]xic"),
    "Reino Unido" to Regex("reino unido|\\buk\\b|britain"),
    "Japón" to Regex("jap[oó]n|japan"),
    "Corea del Sur" to Regex("corea|korea"),
    "España" to Regex("espa[nñ]a|spain"),
    "Argentina" to Regex("argentin"),
    "Colombia" to Regex("colombia"),
    "Francia" to Regex("franc[ei]"),
    "Italia" to Regex("ital"),
    "India" to Regex("\\bindia\\b"),
    "China" to Regex("\\bchin[ao]\\b")
)

private val AUDIOS = listOf(
    "Portugués" to Regex("portugu|\\bpt\\b|dublado"),
    "Español" to Regex("espa[nñ]ol|latino|castellano|\\blat\\b|\\besp\\b|spanish|\\bspa\\b"),
    "Inglés" to Regex("ingl[eé]s|english|\\beng\\b")
)

private val SUBS_RE = Regex("subtitul|\\bsub\\b|\\bvose\\b|\\bcc\\b")
private val YEAR_PAREN = Regex("\\((19[5-9]\\d|20[0-3]\\d)\\)")
private val YEAR_ANY = Regex("(?<!\\d)(19[5-9]\\d|20[0-3]\\d)(?!\\d)")
private val TYPES = listOf("Pelicula", "Series", "Kids", "Anime")
private val Red = Color(0xFFD72631)

private fun yearOf(title: String, group: String): Int? =
    YEAR_PAREN.findAll(title).lastOrNull()?.groupValues?.get(1)?.toInt()
        ?: YEAR_ANY.findAll(title).lastOrNull()?.groupValues?.get(1)?.toInt()
        ?: YEAR_ANY.findAll(group).lastOrNull()?.groupValues?.get(1)?.toInt()

private fun makeItem(key: String, title: String, image: String, group: String, entry: Entry?, series: Series?): FItem {
    val text = "$title $group".lowercase(Locale.ROOT)
    val anime = ANIME_RE.containsMatchIn(text)
    return FItem(
        key, title, image, entry, series,
        kids = !anime && KIDS_RE.containsMatchIn(text),
        anime = anime,
        year = yearOf(title, group),
        genres = GENRES.filter { it.second.containsMatchIn(text) }.map { it.first }.toSet(),
        countries = COUNTRIES.filter { it.second.containsMatchIn(text) }.map { it.first }.toSet(),
        audios = AUDIOS.filter { it.second.containsMatchIn(text) }.map { it.first }.toSet(),
        subs = SUBS_RE.containsMatchIn(text)
    )
}

private fun buildItems(c: Catalog): List<FItem> {
    val out = ArrayList<FItem>(c.movies.size + c.series.size)
    c.movies.forEach { out += makeItem(it.url, it.name, it.logo, it.group, it, null) }
    c.series.forEach { out += makeItem(it.key, it.title, it.poster, it.group, null, it) }
    return out
}

@Composable
internal fun FilterScreen(
    state: ClassicState,
    catalog: Catalog,
    onPlay: (PlayRequest) -> Unit,
    onSeries: (Series) -> Unit,
    onClose: () -> Unit
) {
    val s = LocalUiScale.current
    val items by produceState<List<FItem>?>(
        if (state.filterCacheFor === catalog) state.filterCache else null, catalog
    ) {
        if (value == null) {
            val built = withContext(Dispatchers.Default) { buildItems(catalog) }
            state.filterCache = built
            state.filterCacheFor = catalog
            value = built
        }
    }

    val type = state.fType
    val base = remember(items, type) {
        items?.filter {
            when (type) { 0 -> it.entry != null; 1 -> it.series != null; 2 -> it.kids; else -> it.anime }
        } ?: emptyList()
    }
    val genres = remember(base) { GENRES.map { it.first }.filter { g -> base.any { g in it.genres } } }
    val years = remember(base) { base.mapNotNull { it.year }.distinct().sortedDescending().take(30).map { it.toString() } }
    val countries = remember(base) { COUNTRIES.map { it.first }.filter { c -> base.any { c in it.countries } } }
    val audios = remember(base) { AUDIOS.map { it.first }.filter { a -> base.any { a in it.audios } } }

    val shown = remember(base, state.fGenre, state.fYear, state.fCountry, state.fAudio) {
        val g = state.fGenre; val y = state.fYear; val c = state.fCountry; val a = state.fAudio
        base.filter {
            (g == null || g in it.genres) && (y == null || it.year?.toString() == y) &&
                (c == null || c in it.countries) && (a == null || a in it.audios)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Box(
            Modifier.fillMaxSize().padding(start = (16 * s).dp)
                .clip(RoundedCornerShape(topStart = (24 * s).dp))
                .background(Color(0xFF0D0D0D))
        ) {
            if (items == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(34.dp), color = Red)
            } else LazyVerticalGrid(
                columns = GridCells.Adaptive((112 * s).dp),
                contentPadding = PaddingValues(start = (16 * s).dp, end = (16 * s).dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy((12 * s).dp),
                verticalArrangement = Arrangement.spacedBy((8 * s).dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().padding(top = (10 * s).dp)) {
                        FilterRow("Tipo", TYPES, TYPES[type], pill = true) { v ->
                            val idx = TYPES.indexOf(v).coerceAtLeast(0)
                            if (idx != state.fType) {
                                state.fType = idx
                                state.fGenre = null; state.fYear = null; state.fCountry = null; state.fAudio = null
                            }
                        }
                        if (genres.isNotEmpty()) FilterRow("Género", genres, state.fGenre) { state.fGenre = it }
                        if (years.isNotEmpty()) FilterRow("Año", years, state.fYear) { state.fYear = it }
                        if (countries.isNotEmpty()) FilterRow("País", countries, state.fCountry) { state.fCountry = it }
                        if (audios.isNotEmpty()) FilterRow("Audio", audios, state.fAudio) { state.fAudio = it }
                        Spacer(Modifier.height((6 * s).dp))
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF242424)))
                        Spacer(Modifier.height((8 * s).dp))
                    }
                }
                if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                        Text("Sin resultados con estos filtros", color = Cm.Muted, fontSize = 14.sp)
                    }
                }
                items(shown.size, contentType = { "poster" }) { i ->
                    val fi = shown[i]
                    FilterPoster(fi) {
                        val e = fi.entry
                        val sr = fi.series
                        if (e != null) onPlay(PlayRequest(listOf(e), 0, false)) else if (sr != null) onSeries(sr)
                    }
                }
            }
            Box(
                Modifier.align(Alignment.TopEnd).padding(8.dp).size((34 * s).dp)
                    .tvClickable(CircleShape, 1.1f) { onClose() }.background(Color(0xFF2B2B2B), CircleShape),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Close, "Cerrar filtros", tint = Color.White, modifier = Modifier.size((18 * s).dp)) }
        }
    }
}

@Composable
private fun FilterRow(label: String, options: List<String>, selected: String?, pill: Boolean = false, onSelect: (String?) -> Unit) {
    val s = LocalUiScale.current
    Row(Modifier.fillMaxWidth().padding(vertical = (1 * s).dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$label:", color = Color.White, fontWeight = FontWeight.Bold, fontSize = (14 * s).sp,
            textAlign = TextAlign.End, maxLines = 1,
            modifier = Modifier.width((66 * s).dp).padding(end = (8 * s).dp)
        )
        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (!pill) item { FilterChip("Todos", selected == null, false) { onSelect(null) } }
            items(options.size) { i -> FilterChip(options[i], selected == options[i], pill) { onSelect(options[i]) } }
        }
    }
}

@Composable
private fun FilterChip(text: String, active: Boolean, pill: Boolean, onClick: () -> Unit) {
    val s = LocalUiScale.current
    val shape = RoundedCornerShape(50)
    Text(
        text,
        color = when { active && pill -> Color.White; active -> Red; else -> Color(0xFFC4C4C4) },
        fontSize = (14 * s).sp, maxLines = 1,
        modifier = Modifier
            .tvClickable(shape, 1.05f) { onClick() }
            .background(if (active && pill) Red else Color.Transparent, shape)
            .padding(horizontal = (12 * s).dp, vertical = (5 * s).dp)
    )
}

@Composable
private fun FilterPoster(item: FItem, onClick: () -> Unit) {
    val s = LocalUiScale.current
    val score = rememberScore(item.key, item.title, if (item.series != null) RatingKind.SERIES else RatingKind.MOVIE)
    Column(
        Modifier.fillMaxWidth().tvClickable(RoundedCornerShape(6.dp), 1.04f) { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(4.dp)).background(Color(0xFF1A1A1A))) {
            Poster(item.image, Modifier.fillMaxSize(), Icons.Filled.Movie, ContentScale.Crop, 240, 360)
            if (score != null) Text(
                String.format(Locale.US, "%.1f", score), color = Color(0xFF3D9BFF), fontWeight = FontWeight.Bold,
                fontSize = (12 * s).sp,
                modifier = Modifier.align(Alignment.BottomStart).background(Color(0xAA000000)).padding(horizontal = 3.dp)
            )
            if (item.subs) Text(
                "CC", color = Color.White, fontWeight = FontWeight.Bold, fontSize = (9 * s).sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp)
                    .background(Color(0xFFF29A2E), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
        Text(
            item.title, color = Color(0xFFE6E6E6), fontSize = (14 * s).sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)
        )
    }
}
