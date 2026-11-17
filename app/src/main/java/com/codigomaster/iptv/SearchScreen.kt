package com.codigomaster.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale
import kotlin.random.Random

/**
 * Buscador: teclado en pantalla a la izquierda (A-Z con tildes, 123, borrar) y a la derecha
 * «Búsqueda popular» o los resultados en una cuadrícula de 5 pósters.
 */

private val PanelLeft = Color(0xFF141414)
private val PanelRight = Color(0xFF1B1B1B)
private val FieldBg = Color(0xFF262626)
private val KeyGrey = Color(0xFFB9B9B9)
private val Amber = Color(0xFFFFA000)

private class Indexed(val item: ShowItem, val titleFolded: String, val groupFolded: String)

private val DIACRITICS = Regex("\\p{InCombiningDiacriticalMarks}+")
private fun fold(s: String): String =
    Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(DIACRITICS, "")

private val LETTER_ROWS = listOf(
    listOf("A", "Á", "B", "C", "D", "E", "É"),
    listOf("F", "G", "H", "I", "Í", "J", "K"),
    listOf("L", "M", "N", "Ñ", "O", "Ó", "P"),
    listOf("Q", "R", "S", "T", "U", "Ú", "Ü")
)
private val LAST_ROW = listOf("V", "W", "X", "Y", "Z")
private val NUMBER_ROWS = listOf(
    listOf("1", "2", "3", "4", "5", "6", "7"),
    listOf("8", "9", "0", "-", ".", "'", "&")
)

@Composable
internal fun SearchScreen(catalog: Catalog, onOpen: (ShowItem) -> Unit, onClose: () -> Unit) {
    val cfg = LocalConfiguration.current
    val s = (cfg.screenHeightDp / 400f).coerceIn(0.72f, 1.2f)
    var text by remember { mutableStateOf("") }
    var numbers by remember { mutableStateOf(false) }
    var seed by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }

    // Índice sin tildes ni mayúsculas, construido una sola vez fuera del hilo principal.
    val index by produceState<List<Indexed>?>(null, catalog) {
        value = withContext(Dispatchers.Default) {
            val out = ArrayList<Indexed>(catalog.movies.size + catalog.series.size)
            catalog.movies.forEach { out += Indexed(it.toShow(), fold(it.name), fold(it.group)) }
            catalog.series.forEach { out += Indexed(it.toShow(), fold(it.title), fold(it.group)) }
            out
        }
    }
    LaunchedEffect(text) { if (text.isBlank()) query = "" else { delay(200); query = text } }

    val popular = remember(catalog, seed) {
        val m = catalog.movies.filter { it.logo.startsWith("http") }.take(120).map { it.toShow() }
        val sr = catalog.series.filter { it.poster.startsWith("http") }.take(120).map { it.toShow() }
        val pool = ArrayList<ShowItem>()
        for (i in 0 until maxOf(m.size, sr.size)) { if (i < sr.size) pool += sr[i]; if (i < m.size) pool += m[i] }
        pool.shuffled(Random(seed.toLong() + 7)).take(10)
    }
    val results by produceState<List<ShowItem>>(emptyList(), index, query) {
        val idx = index
        value = if (query.isBlank() || idx == null) emptyList() else withContext(Dispatchers.Default) {
            val tokens = fold(query).split(' ').filter { it.isNotBlank() }
            idx.mapNotNull { x ->
                if (!tokens.all { x.titleFolded.contains(it) || x.groupFolded.contains(it) }) return@mapNotNull null
                val score = when {
                    x.titleFolded == tokens.joinToString(" ") -> 1200
                    x.titleFolded.startsWith(tokens.joinToString(" ")) -> 1000
                    tokens.all { token -> x.titleFolded.split(Regex("[^a-z0-9]+")).any { it.startsWith(token) } } -> 850
                    tokens.all { x.titleFolded.contains(it) } -> 700
                    else -> 250
                } + tokens.sumOf { token ->
                    (if (x.titleFolded.contains(token)) 30 else 0) +
                    (if (x.groupFolded.contains(token)) 5 else 0)
                }
                score to x.item
            }.sortedByDescending { it.first }.take(300).map { it.second }
        }
    }
    val searching = query.isNotBlank()
    val shown = if (searching) results else popular

    CompositionLocalProvider(LocalUiScale provides s) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Row(Modifier.fillMaxSize().padding(start = (16 * s).dp)) {
                // ───────── Teclado ─────────
                Column(
                    Modifier.weight(0.31f).fillMaxHeight()
                        .clip(RoundedCornerShape(topStart = (24 * s).dp)).background(PanelLeft)
                        .padding(horizontal = (22 * s).dp, vertical = (14 * s).dp)
                ) {
                    Box(Modifier.fillMaxWidth().background(FieldBg).padding(horizontal = (10 * s).dp, vertical = (10 * s).dp)) {
                        if (text.isEmpty()) Text("Buscar películas y series", color = KeyGrey, fontSize = (14 * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        else Text(text.uppercase(Locale.ROOT), color = Color.White, fontSize = (15 * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height((4 * s).dp))
                    Column(Modifier.weight(1f).fillMaxWidth()) {
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            Key(1f, { numbers = !numbers }) { Text(if (numbers) "ABC" else "123", color = KeyGrey, fontSize = (19 * s).sp) }
                            Key(1f, { text = "" }) { Icon(Icons.Filled.Delete, "Borrar todo", tint = KeyGrey, modifier = Modifier.size((24 * s).dp)) }
                            Key(1f, { text = text.dropLast(1) }) { Icon(Icons.Filled.Backspace, "Borrar", tint = KeyGrey, modifier = Modifier.size((24 * s).dp)) }
                            Key(1.35f, { query = text.trim() }) {
                                Box(Modifier.fillMaxSize().padding(2.dp).background(FieldBg), contentAlignment = Alignment.Center) {
                                    Text("Busca", color = KeyGrey, fontSize = (17 * s).sp, maxLines = 1)
                                }
                            }
                        }
                        val rows = if (numbers) NUMBER_ROWS else LETTER_ROWS
                        rows.forEach { row ->
                            Row(Modifier.weight(1f).fillMaxWidth()) {
                                row.forEach { k -> Key(1f, { text += k.uppercase(Locale.ROOT) }) { KeyLabel(k) } }
                            }
                        }
                        if (!numbers) Row(Modifier.weight(1f).fillMaxWidth()) {
                            LAST_ROW.forEach { k -> Key(1f, { text += k.lowercase(Locale.ROOT) }) { KeyLabel(k) } }
                            Key(2f, { if (text.isNotEmpty() && !text.endsWith(" ")) text += " " }) {
                                Box(
                                    Modifier.fillMaxWidth().height((20 * s).dp).padding(horizontal = (10 * s).dp).drawBehind {
                                        val w = 2.dp.toPx()
                                        val y = size.height * 0.75f
                                        val h = 8.dp.toPx()
                                        drawLine(KeyGrey, Offset(0f, y - h), Offset(0f, y), w)
                                        drawLine(KeyGrey, Offset(0f, y), Offset(size.width, y), w)
                                        drawLine(KeyGrey, Offset(size.width, y - h), Offset(size.width, y), w)
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height((4 * s).dp))
                    Box(
                        Modifier.fillMaxWidth().tvClickable(RoundedCornerShape(2.dp), 1.03f) { onClose() }
                            .background(FieldBg).padding(vertical = (10 * s).dp),
                        contentAlignment = Alignment.Center
                    ) { Text("Cerrar", color = KeyGrey, fontSize = (17 * s).sp) }
                }

                // ───────── Resultados ─────────
                Column(
                    Modifier.weight(0.69f).fillMaxHeight().background(PanelRight)
                        .padding(horizontal = (22 * s).dp, vertical = (12 * s).dp)
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (searching) "Resultados" else "Búsqueda popular", color = Color.White, fontWeight = FontWeight.Bold, fontSize = (22 * s).sp, maxLines = 1)
                        if (!searching) {
                            Spacer(Modifier.width((10 * s).dp))
                            Text(
                                "Actualizar", color = Color.White, fontSize = (17 * s).sp, maxLines = 1,
                                modifier = Modifier.tvClickable(RoundedCornerShape(50), 1.05f) { seed++ }
                                    .background(Color(0xFF2E2E2E), RoundedCornerShape(50))
                                    .padding(horizontal = (10 * s).dp, vertical = (4 * s).dp)
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            buildAnnotatedString {
                                append("Total: ")
                                withStyle(SpanStyle(color = Amber)) { append("${shown.size}") }
                            },
                            color = Color(0xFFE0E0E0), fontSize = (17 * s).sp
                        )
                    }
                    Spacer(Modifier.height((10 * s).dp))
                    if (shown.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (searching && index == null) "Buscando…" else if (searching) "Sin resultados" else "Sin contenido",
                            color = KeyGrey, fontSize = (16 * s).sp
                        )
                    } else LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        contentPadding = PaddingValues(bottom = (12 * s).dp),
                        horizontalArrangement = Arrangement.spacedBy((12 * s).dp),
                        verticalArrangement = Arrangement.spacedBy((6 * s).dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(shown, key = { it.key }) { item ->
                            PosterTile(item, Modifier.fillMaxWidth(), 2f / 3f, true) { onOpen(item) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.Key(weight: Float, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.weight(weight).fillMaxHeight().tvClickable(RoundedCornerShape(6.dp), 1.12f, onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
private fun KeyLabel(text: String) {
    val s = LocalUiScale.current
    Text(text, color = KeyGrey, fontSize = (22 * s).sp, maxLines = 1)
}
