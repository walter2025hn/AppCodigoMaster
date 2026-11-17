package com.codigomaster.iptv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.random.Random

/**
 * Ficha de una película o serie (estilo captura 2): título y nota, país | fecha, géneros, director,
 * actores y sinopsis a la izquierda; vista previa con botón de reproducir a la derecha; botones
 * Pantalla completa / Idioma / Resolución / Favorito y fila «Quizás te guste».
 * Con una clave de TMDB (Ajustes) se rellenan sinopsis, reparto, director y fondo.
 */

private val Panel = Color(0xFF0D0D0D)
private val Orange = Color(0xFFFFA000)
private val LabelGrey = Color(0xFF9A9A9A)
private val ButtonGrey = Color(0xFF4F4F4F)
private val More = Color(0xFFD72631)

@Composable
fun DetailScreen(
    movie: Entry?,
    series: Series?,
    catalog: Catalog,
    store: Store,
    favorites: Set<String>,
    onToggleFavorite: (String) -> Unit,
    onPlay: (PlayRequest) -> Unit,
    onMovie: (Entry) -> Unit,
    onSeries: (Series) -> Unit,
    onBack: () -> Unit
) {
    if (movie == null && series == null) return
    BackHandler { onBack() }
    val cfg = LocalConfiguration.current
    val tv = LocalTv.current
    val playFocus = remember(movie?.url, series?.key) { FocusRequester() }
    LaunchedEffect(movie?.url, series?.key, tv) {
        if (tv) {
            kotlinx.coroutines.delay(180)
            try { playFocus.requestFocus() } catch (_: IllegalStateException) { }
        }
    }
    val s = (cfg.screenHeightDp / 400f).coerceIn(0.72f, 1.2f)

    val key = movie?.url ?: series!!.key
    val title = movie?.name ?: series!!.title
    val group = movie?.group ?: series!!.group
    val fallbackImage = movie?.logo ?: series!!.poster
    val kind = if (movie != null) RatingKind.MOVIE else RatingKind.SERIES
    val favorite = key in favorites

    val meta = rememberMeta(title, kind)
    val image = meta?.poster?.takeIf { it.startsWith("http") } ?: fallbackImage
    val displayTitle = meta?.displayTitle?.takeIf { it.isNotBlank() } ?: title
    val score = rememberScore(key, title, kind)
    val tmdbOn = LocalRatings.current?.enabled == true

    // ── Reproducción ──
    val seasons = remember(series) { series?.episodes?.map { episodeNumber(it.name).first }?.distinct()?.sorted().orEmpty() }
    var season by remember(series) { mutableIntStateOf(seasons.firstOrNull() ?: 0) }
    val shownEpisodes = remember(series, season) { series?.episodes?.filter { episodeNumber(it.name).first == season }.orEmpty() }
    val resumeEpisode = remember(series) { series?.episodes?.firstOrNull { store.position(it.url) > 10_000 } }

    fun play(openMenu: Boolean) {
        if (movie != null) onPlay(PlayRequest(listOf(movie), 0, false, openMenu = openMenu))
        else if (series != null) {
            val target = resumeEpisode ?: shownEpisodes.firstOrNull() ?: series.episodes.firstOrNull() ?: return
            onPlay(PlayRequest(series.episodes, series.episodes.indexOf(target).coerceAtLeast(0), false, series.key, series.title, openMenu))
        }
    }

    // ── Recomendaciones por similitud: películas recomiendan solo películas. ──
    val recs = remember(catalog, key, title, group, movie != null) {
        fun words(value: String): Set<String> =
            value.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.length > 2 && it !in setOf("the", "and", "con", "para", "una", "uno", "del", "los", "las", "por", "movie", "pelicula", "peliculas", "vod") }
                .toSet()
        val wantedGenres = words(group)
        val wantedTitle = words(title)
        if (movie != null) {
            catalog.movies.asSequence()
                .filter { it.url != key && it.logo.startsWith("http") }
                .map { candidate ->
                    val genreWords = words(candidate.group)
                    val titleWords = words(candidate.name)
                    val sharedGenres = wantedGenres.intersect(genreWords).size
                    val sharedTitle = wantedTitle.intersect(titleWords).size
                    val score = (if (candidate.group.equals(group, true)) 1000 else 0) +
                        sharedGenres * 120 + sharedTitle * 35
                    score to candidate
                }
                .sortedByDescending { it.first }
                .take(20)
                .map { it.second.toShow() }
                .toList()
        } else {
            catalog.series.asSequence()
                .filter { it.key != key && it.poster.startsWith("http") }
                .map { candidate ->
                    val sharedGenres = wantedGenres.intersect(words(candidate.group)).size
                    val sharedTitle = wantedTitle.intersect(words(candidate.title)).size
                    ((if (candidate.group.equals(group, true)) 1000 else 0) + sharedGenres * 120 + sharedTitle * 35) to candidate
                }
                .sortedByDescending { it.first }
                .take(20)
                .map { it.second.toShow() }
                .toList()
        }
    }

    var showMore by remember(key) { mutableStateOf(false) }
    val overview = meta?.overview.orEmpty()

    CompositionLocalProvider(LocalUiScale provides s) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Box(
                Modifier.fillMaxSize().padding(start = (16 * s).dp)
                    .clip(RoundedCornerShape(topStart = (24 * s).dp)).background(Panel)
            ) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = (22 * s).dp, end = (22 * s).dp, top = (16 * s).dp, bottom = (16 * s).dp)
                ) {
                    // ── Cabecera: datos a la izquierda, vista previa a la derecha ──
                    item(key = "head") {
                        Row(Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1.06f).padding(end = (22 * s).dp)) {
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(
                                        title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (22 * s).sp,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (score != null) Text(
                                        String.format(Locale.US, "%.1f", score), color = Orange, fontSize = (16 * s).sp,
                                        modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)
                                    )
                                }
                                Spacer(Modifier.height((8 * s).dp))
                                val line = listOfNotNull(
                                    meta?.country?.takeIf { it.isNotBlank() } ?: group,
                                    meta?.date?.take(4)?.takeIf { it.length == 4 },
                                    meta?.original?.takeIf { it.isNotBlank() } ?: title
                                ).joinToString(" | ")
                                Text(line, color = LabelGrey, fontWeight = FontWeight.SemiBold, fontSize = (13 * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height((10 * s).dp))

                                val genres = meta?.genres.orEmpty()
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Label("Género :")
                                    LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        items(if (genres.isNotEmpty()) genres else listOf(group)) { g -> GenreChip(g) }
                                    }
                                }
                                val director = meta?.director.orEmpty()
                                if (director.isNotBlank()) { Spacer(Modifier.height((6 * s).dp)); InfoLine("Director :", director) }
                                val cast = meta?.cast.orEmpty()
                                if (cast.isNotEmpty()) { Spacer(Modifier.height((6 * s).dp)); InfoLine("Actores :", cast.joinToString(",")) }
                                Spacer(Modifier.height((6 * s).dp))
                                Row(verticalAlignment = Alignment.Top) {
                                    Label("Sinopsis :")
                                    if (overview.isNotBlank()) {
                                        Text(
                                            overview, color = Color(0xFFD0D0D0), fontSize = (13 * s).sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                            lineHeight = (17 * s).sp, modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            "Más", color = More, fontSize = (13 * s).sp,
                                            modifier = Modifier.tvClickable(RoundedCornerShape(4.dp), 1.08f) { showMore = true }.padding(horizontal = 4.dp)
                                        )
                                    } else Text(
                                        if (tmdbOn) "Sin sinopsis disponible."
                                        else "Sin sinopsis. Añade tu clave de TMDB en Ajustes (campana) para ver sinopsis, director, reparto y fondos.",
                                        color = Color(0xFF8A8A8A), fontSize = (13 * s).sp, lineHeight = (17 * s).sp, modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            // Vista previa (fondo + botón de reproducir)
                            Box(
                                Modifier.weight(1f).aspectRatio(16f / 9f).border(1.dp, Color(0xFF2A2A2A))
                                    .background(Color.Black).then(if (tv) Modifier.focusRequester(playFocus) else Modifier).tvClickable(RoundedCornerShape(0.dp), 1.0f) { play(false) }
                            ) {
                                val back = meta?.backdrop.orEmpty()
                                val poster = meta?.poster.orEmpty()
                                Poster(
                                    back.ifBlank { poster.ifBlank { image } }, Modifier.fillMaxSize(), Icons.Filled.Movie, ContentScale.Crop, 780, 440,
                                    if (back.isBlank()) Alignment.TopCenter else Alignment.Center
                                )
                                Box(
                                    Modifier.align(Alignment.Center).size((58 * s).dp)
                                        .border(3.dp, Color.White, CircleShape).background(Color(0x44000000), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) { Icon(Icons.Filled.PlayArrow, "Reproducir", tint = Color.White, modifier = Modifier.size((34 * s).dp)) }
                            }
                        }
                    }

                    // ── Botones ──
                    item(key = "buttons") {
                        Row(Modifier.fillMaxWidth().padding(top = (18 * s).dp), horizontalArrangement = Arrangement.spacedBy((25 * s).dp)) {
                            ActionButton("Pantalla completa", Icons.Filled.Fullscreen, Modifier.weight(1f)) { play(false) }
                            ActionButton("Idioma", Icons.Filled.Subtitles, Modifier.weight(1f)) { play(true) }
                            ActionButton("Resolución", Icons.Filled.SwapHorizontalCircle, Modifier.weight(1f)) { play(true) }
                            ActionButton(
                                "Favorito", if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder, Modifier.weight(1f),
                                tint = if (favorite) Color(0xFFFFC84A) else Color.White
                            ) { onToggleFavorite(key) }
                            Spacer(Modifier.weight(0.6f))
                        }
                    }

                    // ── Temporadas y episodios (solo series) ──
                    if (series != null) {
                        if (seasons.size > 1) item(key = "seasons") {
                            LazyRow(Modifier.padding(top = (14 * s).dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(seasons, key = { it }) { n ->
                                    val active = n == season
                                    Text(
                                        if (n == 0) "Episodios" else "Temporada $n", color = if (active) Color.White else Color(0xFFC4C4C4),
                                        fontSize = (14 * s).sp, maxLines = 1,
                                        modifier = Modifier.tvClickable(RoundedCornerShape(50), 1.05f) { season = n }
                                            .background(if (active) CineRed else Color.Transparent, RoundedCornerShape(50))
                                            .padding(horizontal = (14 * s).dp, vertical = (5 * s).dp)
                                    )
                                }
                            }
                        }
                        item(key = "episodes") {
                            LazyRow(Modifier.padding(top = (12 * s).dp), horizontalArrangement = Arrangement.spacedBy((10 * s).dp)) {
                                items(shownEpisodes.size, key = { shownEpisodes[it].url }) { i ->
                                    val e = shownEpisodes[i]
                                    val (_, ep) = episodeNumber(e.name)
                                    val inProgress = store.position(e.url) > 10_000
                                    Column(
                                        Modifier.width((150 * s).dp)
                                            .tvClickable(RoundedCornerShape(8.dp), 1.04f) {
                                                onPlay(PlayRequest(series.episodes, series.episodes.indexOf(e).coerceAtLeast(0), false, series.key, series.title))
                                            }
                                            .background(Color(0xFF1A1A1A)).padding((10 * s).dp)
                                    ) {
                                        Text(
                                            "Ep. " + if (ep > 0) "$ep" else "${i + 1}", color = Color(0xFFFFC84A),
                                            fontWeight = FontWeight.Black, fontSize = (15 * s).sp
                                        )
                                        Text(e.name, color = Color(0xFFE6E6E6), fontSize = (12 * s).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        if (inProgress) Text("En progreso", color = CineRed, fontSize = (11 * s).sp)
                                    }
                                }
                            }
                        }
                    }

                    // ── Quizás te guste ──
                    if (recs.isNotEmpty()) {
                        item(key = "recs-title") {
                            Text(
                                "Quizás te guste", color = Color.White, fontWeight = FontWeight.Bold, fontSize = (17 * s).sp,
                                modifier = Modifier.padding(top = (18 * s).dp, bottom = (6 * s).dp)
                            )
                        }
                        item(key = "recs") {
                            LazyRow(contentPadding = PaddingValues(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy((14 * s).dp)) {
                                items(recs, key = { it.key }) { r ->
                                    PosterTile(r, Modifier.width((86 * s).dp), 0.85f) {
                                        val e = r.entry
                                        val sr = r.series
                                        if (e != null) onMovie(e) else if (sr != null) onSeries(sr)
                                    }
                                }
                            }
                        }
                    }
                }
                // Cerrar (esquina superior derecha)
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size((30 * s).dp)
                        .tvClickable(CircleShape, 1.1f) { onBack() }.background(Color(0xAA2B2B2B), CircleShape),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Close, "Volver", tint = Color.White, modifier = Modifier.size((16 * s).dp)) }
            }
        }
    }

    if (showMore) AlertDialog(
        onDismissRequest = { showMore = false },
        containerColor = Cm.SurfaceHi, titleContentColor = Cm.Text, textContentColor = Cm.Text,
        title = { Text(displayTitle, fontWeight = FontWeight.Bold) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(overview, fontSize = 14.sp, lineHeight = 20.sp) } },
        confirmButton = { TextButton(onClick = { showMore = false }) { Text("Cerrar", color = Cm.Gold, fontWeight = FontWeight.Bold) } }
    )
}

@Composable
private fun Label(text: String) {
    val s = LocalUiScale.current
    Text(
        text, color = LabelGrey, fontWeight = FontWeight.Bold, fontSize = (13 * s).sp, maxLines = 1,
        modifier = Modifier.width((72 * s).dp)
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    val s = LocalUiScale.current
    Row(verticalAlignment = Alignment.Top) {
        Label(label)
        Text(value, color = Color(0xFFE6E6E6), fontSize = (13 * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun GenreChip(text: String) {
    val s = LocalUiScale.current
    Text(
        text, color = Color.White, fontSize = (13 * s).sp, maxLines = 1,
        modifier = Modifier.border(1.dp, Color.White, RoundedCornerShape(50)).padding(horizontal = (9 * s).dp, vertical = (1 * s).dp)
    )
}

@Composable
private fun ActionButton(text: String, icon: ImageVector, modifier: Modifier, tint: Color = Color.White, onClick: () -> Unit) {
    val s = LocalUiScale.current
    Row(
        modifier.tvClickable(RoundedCornerShape(2.dp), 1.05f, onClick).background(ButtonGrey)
            .padding(horizontal = (10 * s).dp, vertical = (12 * s).dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size((26 * s).dp))
        Spacer(Modifier.width((8 * s).dp))
        Text(text, color = Color.White, fontSize = (14 * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
