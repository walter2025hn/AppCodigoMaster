package com.codigomaster.iptv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.HttpURLConnection

private const val PLAYLIST_URL =
    "https://raw.githubusercontent.com/walter2025hn/MiListaPremium/refs/heads/main/Completa.m3u"

data class Channel(
    val name: String,
    val url: String,
    val group: String = "Sin categoría",
    val logo: String = ""
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CodigoMasterApp() }
    }
}

@Composable
fun CodigoMasterApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("iptv_cache", 0) }
    var channels by remember { mutableStateOf(loadCached(prefs)) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(if (channels.isEmpty()) "Conectando con la lista..." else "Lista guardada disponible") }
    var query by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf("Todos") }
    var selected by remember { mutableStateOf<Channel?>(null) }

    fun refresh() {
        if (loading) return
        loading = true
        status = "Actualizando lista desde GitHub…"
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { downloadPlaylist(PLAYLIST_URL) }
                if (result.isNotEmpty()) {
                    channels = result
                    prefs.edit().putString("playlist_json", JSONArray().apply {
                        result.forEach { c ->
                            put(JSONObject().apply {
                                put("name", c.name); put("url", c.url)
                                put("group", c.group); put("logo", c.logo)
                            })
                        }
                    }.toString()).apply()
                    status = "Actualizada: ${result.size} elementos"
                } else {
                    status = "La lista no contiene enlaces reproducibles. Se conserva la copia anterior."
                }
            } catch (e: Exception) {
                status = if (channels.isNotEmpty()) "Sin conexión. Usando la última lista guardada."
                         else "No se pudo descargar la lista. Revisa tu conexión."
            } finally { loading = false }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val filtered = channels.filter { item ->
        val matchesQuery = item.name.contains(query, true) || item.group.contains(query, true)
        val category = when (selectedTab) {
            "TV" -> isLive(item)
            "Películas" -> item.group.contains("pel", true) || item.group.contains("movie", true)
            "Series" -> item.group.contains("serie", true) || item.group.contains("season", true) ||
                        item.name.contains(Regex("S\\d{1,2}E\\d{1,2}", RegexOption.IGNORE_CASE))
            else -> true
        }
        matchesQuery && category
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFFFFC247), secondary = Color(0xFF27B7FF),
        background = Color(0xFF070B18), surface = Color(0xFF111A2C)
    )) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF070B18)) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(R.drawable.brand_logo),
                        contentDescription = "Codigo Master",
                        modifier = Modifier.size(58.dp),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("CODIGO MASTER", color = Color(0xFFFFD36A), fontWeight = FontWeight.Black, fontSize = 20.sp)
                        Text("IPTV • TU ENTRETENIMIENTO", color = Color(0xFF91BDEB), fontSize = 10.sp, letterSpacing = 1.sp)
                    }
                    IconButton(onClick = { refresh() }, enabled = !loading) {
                        if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, contentDescription = "Actualizar", tint = Color(0xFFFFC247))
                    }
                }

                if (selected != null) {
                    PlayerScreen(channel = selected!!, onBack = { selected = null })
                } else {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        placeholder = { Text("Buscar canales, películas o series…", color = Color.Gray) },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFFFC247), unfocusedBorderColor = Color(0xFF34435E)
                        )
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Todos", "TV", "Películas", "Series").forEach { tab ->
                            FilterChip(
                                selected = selectedTab == tab,
                                onClick = { selectedTab = tab },
                                label = { Text(tab, fontSize = 12.sp) }
                            )
                        }
                    }
                    Text(status, color = Color(0xFF8EA3C3), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    if (channels.isEmpty() && loading) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else if (filtered.isEmpty()) {
                        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text("No hay elementos para mostrar. Comprueba tu archivo M3U en GitHub.", color = Color.LightGray)
                        }
                    } else {
                        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(filtered) { item ->
                                Row(
                                    Modifier.fillMaxWidth().background(Color(0xFF111A2C), RoundedCornerShape(12.dp))
                                        .clickable { selected = item }.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (item.logo.isNotBlank()) {
                                        AsyncImage(model = item.logo, contentDescription = null, modifier = Modifier.size(48.dp), contentScale = ContentScale.Fit)
                                    } else {
                                        Box(Modifier.size(48.dp).background(Color(0xFF1E3555), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                                            Text("CM", color = Color(0xFFFFC247), fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(item.name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                        Text(item.group, color = Color(0xFF91BDEB), fontSize = 11.sp)
                                    }
                                    Text("▶", color = Color(0xFFFFC247), fontSize = 18.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PlayerScreen(channel: Channel, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(channel.url) {
        ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(channel.url)); prepare(); playWhenReady = true }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("‹  Volver", color = Color(0xFFFFC247), modifier = Modifier.clickable { onBack() }.padding(8.dp))
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
        )
        Spacer(Modifier.height(12.dp))
        Text(channel.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(channel.group, color = Color(0xFF91BDEB))
    }
}

private fun isLive(c: Channel): Boolean {
    val g = c.group.lowercase()
    val channelGroups = listOf("tv", "canal", "live", "deporte", "noticia", "musica", "música", "infantil", "entretenimiento")
    return channelGroups.any { g.contains(it) } &&
           !g.contains("pel") && !g.contains("movie") && !g.contains("serie")
}

private fun downloadPlaylist(url: String): List<Channel> {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000; readTimeout = 20000
        setRequestProperty("User-Agent", "CodigoMasterIPTV/1.0")
    }
    try {
        val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return parseM3u(text)
    } finally { conn.disconnect() }
}

private fun parseM3u(text: String): List<Channel> {
    val result = mutableListOf<Channel>()
    var name = ""
    var group = "Sin categoría"
    var logo = ""
    for (raw in text.lines()) {
        val line = raw.trim()
        if (line.startsWith("#EXTINF", true)) {
            name = line.substringAfterLast(',').trim().ifBlank { "Sin nombre" }
            group = Regex("""group-title="([^"]*)"""", RegexOption.IGNORE_CASE)
                .find(line)?.groupValues?.getOrNull(1)?.ifBlank { "Sin categoría" } ?: "Sin categoría"
            logo = Regex("""tvg-logo="([^"]*)"""", RegexOption.IGNORE_CASE)
                .find(line)?.groupValues?.getOrNull(1).orEmpty()
        } else if (line.isNotBlank() && !line.startsWith("#") && name.isNotBlank()) {
            val valid = (line.startsWith("http://") || line.startsWith("https://")) &&
                        !line.contains("servidor.com", true) &&
                        !line.contains("URL_DEL_STREAMING", true)
            if (valid) result.add(Channel(name, line, group, logo))
            name = ""; group = "Sin categoría"; logo = ""
        }
    }
    return result.distinctBy { it.url }
}

private fun loadCached(prefs: android.content.SharedPreferences): List<Channel> {
    return try {
        val array = JSONArray(prefs.getString("playlist_json", "[]"))
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Channel(o.optString("name"), o.optString("url"), o.optString("group"), o.optString("logo"))
        }
    } catch (_: Exception) { emptyList() }
}
