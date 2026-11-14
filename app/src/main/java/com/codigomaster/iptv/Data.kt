package com.codigomaster.iptv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

const val DEFAULT_PLAYLIST_URL =
    "https://raw.githubusercontent.com/walter2025hn/MiListaPremium/refs/heads/main/Completa.m3u"

data class Entry(
    val name: String,
    val url: String,
    val group: String,
    val logo: String,
    val tvgId: String,
    val attributes: String = ""
)

data class Series(val title: String, val episodes: List<Entry>, val poster: String, val group: String) {
    val key: String = "series:" + title.lowercase(Locale.ROOT)
}

data class Catalog(val live: List<Entry>, val movies: List<Entry>, val series: List<Series>)

enum class Kind { LIVE, MOVIE, SERIES, OTHER }

/** Persistencia local: lista en caché, favoritos, historial, posición de reproducción y ajustes. */
class Store(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("cm_playlist", Context.MODE_PRIVATE)
    private val cacheFile = File(app.filesDir, "playlist_cache.json")

    var playlistUrl: String
        get() = prefs.getString("custom_url", "").orEmpty()
        set(value) { prefs.edit().putString("custom_url", value.trim()).apply() }

    val effectiveUrl: String get() = playlistUrl.ifBlank { DEFAULT_PLAYLIST_URL }

    /** AUTO, EXO o VLC. */
    var engine: String
        get() = prefs.getString("engine", "AUTO").orEmpty().ifBlank { "AUTO" }
        set(value) { prefs.edit().putString("engine", value).apply() }

    fun favorites(): Set<String> = prefs.getStringSet("favs", emptySet()).orEmpty().toSet()
    fun saveFavorites(values: Set<String>) { prefs.edit().putStringSet("favs", HashSet(values)).apply() }

    fun recents(): List<String> = prefs.getString("recents", "").orEmpty().split('\n').filter { it.isNotBlank() }
    fun pushRecent(url: String) {
        val list = (listOf(url) + recents().filter { it != url }).take(24)
        prefs.edit().putString("recents", list.joinToString("\n")).apply()
    }

    fun position(url: String): Long = prefs.getLong("pos_" + url.hashCode(), 0L)
    fun savePosition(url: String, ms: Long) { prefs.edit().putLong("pos_" + url.hashCode(), ms).apply() }
    fun clearPosition(url: String) { prefs.edit().remove("pos_" + url.hashCode()).apply() }

    fun saveCache(entries: List<Entry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("name", e.name); put("url", e.url); put("group", e.group)
                put("logo", e.logo); put("tvgId", e.tvgId); put("attributes", e.attributes)
            })
        }
        cacheFile.writeText(arr.toString())
        prefs.edit().remove("playlist").apply() // formato antiguo
    }

    fun readCache(): List<Entry> = try {
        val raw = if (cacheFile.exists()) cacheFile.readText() else prefs.getString("playlist", "[]").orEmpty()
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(o.optString("name"), o.optString("url"), o.optString("group"), o.optString("logo"), o.optString("tvgId"), o.optString("attributes"))
        }
    } catch (_: Exception) { emptyList() }
}

fun groupCounts(items: List<Entry>, limit: Int = 60): List<Pair<String, Int>> =
    items.groupingBy { it.group }.eachCount().entries
        .sortedByDescending { it.value }.take(limit).map { it.key to it.value }

fun seriesGroupCounts(items: List<Series>, limit: Int = 60): List<Pair<String, Int>> =
    items.groupingBy { it.group }.eachCount().entries
        .sortedByDescending { it.value }.take(limit).map { it.key to it.value }

// ───────────────────────── Clasificación del catálogo ─────────────────────────

fun buildCatalog(entries: List<Entry>): Catalog {
    val live = ArrayList<Entry>()
    val movies = ArrayList<Entry>()
    val seriesEntries = ArrayList<Entry>()
    for (entry in entries) {
        when (classify(entry)) {
            Kind.LIVE -> live.add(entry)
            Kind.MOVIE -> movies.add(entry)
            Kind.SERIES -> seriesEntries.add(entry)
            else -> Unit
        }
    }
    return Catalog(live, movies, buildSeries(seriesEntries))
}

private val EPISODE_PATTERN = Regex("""\b(s\d{1,2}\s*e\d{1,3}|\d{1,2}x\d{1,3}|t\d{1,2}\s*e\d{1,3}|temporada\s*\d+|capitulo\s*\d+|episodio\s*\d+)\b""")
private val YEAR_PATTERN = Regex("""\b(19\d{2}|20\d{2})\b""")
private val SERIES_GROUP_WORDS = listOf("serie", "series", "tv shows", "temporadas")
private val MOVIE_GROUP_WORDS = listOf("pelicula", "peliculas", "movies", "movie", "vod", "cine", "films", "film", "estrenos")
private val MOVIE_ATTRS = listOf("type=movie", "tvg-type=movie", "content-type=movie", "media-type=movie", "vod=true")
private val QUALITY_WORDS = listOf("1080p", "720p", "2160p", "4k", "bluray", "blu-ray", "web-dl", "webrip", "hdrip", "hdtv", "hevc", "x264", "x265")
private val MOVIE_NAME_WORDS = listOf(
    "la comunidad del anillo", "las dos torres", "el retorno del rey", "la guerra de los rohirrim", "el hobbit",
    "avengers", "spider-man", "spiderman", "batman", "superman", "jurassic", "harry potter", "rapidos y furiosos",
    "mision imposible", "toy story", "shrek", "avatar", "matrix", "gladiador", "titanic", "oppenheimer",
    "barbie", "john wick", "el conjuro", "insidious", "sonic", "kung fu panda", "intensamente", "inside out",
    "moana", "encanto", "coco", "minions", "deadpool", "venom", "transformers", "piratas del caribe"
)
private val GENRE_GROUPS = listOf("fantasia", "accion", "aventura", "drama", "comedia", "terror", "animacion", "romance", "ciencia ficcion", "documental", "suspenso", "thriller", "anime", "familia")
private val EXPLICIT_LIVE_GROUPS = listOf("canales", "canal", "en vivo", "live", "deportes en vivo", "noticias en vivo", "radio", "iptv live")
private val CHANNEL_NAME_PREFIXES = listOf("espn", "fox sports", "discovery", "national geographic", "cnn", "bbc", "televisa", "telemundo", "univision", "hbo live", "cartoon network", "nickelodeon", "animal planet", "history channel", "tnt sports")
private val VOD_URL_PARTS = listOf("/vod/", "/peliculas/", "/film/", "/films/", "/movie/", "/movies/")
private val VOD_GROUP_WORDS = listOf("pelicula", "peliculas", "movie", "movies", "vod", "cine", "films", "estrenos")
private val CHANNEL_GROUP_WORDS = listOf("canales", "canal", "live", "en vivo", "deportes", "noticias", "radio", "musica", "musical", "infantil")
private val LIVE_PATHS = listOf("/live/", "/channel/", "/channels/")

fun classify(e: Entry): Kind {
    val name = normalize(e.name)
    val group = normalize(e.group)
    val attrs = normalize(e.attributes)
    val url = e.url.lowercase(Locale.ROOT)

    // Las rutas del proveedor son la señal más fiable de esta lista.
    if (url.contains("/series/")) return Kind.SERIES
    if (url.contains("/movie/") || url.contains("/movies/")) return Kind.MOVIE

    // Los marcadores de episodio tienen prioridad sobre nombres de grupo genéricos.
    if (EPISODE_PATTERN.containsMatchIn(name) || SERIES_GROUP_WORDS.any { group.contains(it) }) return Kind.SERIES

    if (MOVIE_GROUP_WORDS.any { group.contains(it) } || MOVIE_ATTRS.any { attrs.contains(it) }) return Kind.MOVIE

    val year = YEAR_PATTERN.containsMatchIn(name)
    val quality = QUALITY_WORDS.any { name.contains(it) || url.contains(it) }
    val hasGenre = GENRE_GROUPS.any { group.contains(it) }
    val explicitLiveGroup = EXPLICIT_LIVE_GROUPS.any { group.contains(it) }
    val channelName = CHANNEL_NAME_PREFIXES.any { name.startsWith(it) }

    val urlLooksVod = VOD_URL_PARTS.any { url.contains(it) }
    val groupLooksVod = VOD_GROUP_WORDS.any { group.contains(it) }
    if (urlLooksVod || groupLooksVod) return Kind.MOVIE
    if (!explicitLiveGroup && !channelName && (MOVIE_NAME_WORDS.any { name.contains(it) } || (year && (hasGenre || quality)))) return Kind.MOVIE

    val explicitChannelGroup = CHANNEL_GROUP_WORDS.any { group.contains(it) }
    val streamLooksLive = url.contains(".m3u8") || url.contains(".ts")
    val livePath = LIVE_PATHS.any { url.contains(it) }
    if (explicitLiveGroup || channelName || explicitChannelGroup || (livePath && streamLooksLive) || (e.tvgId.isNotBlank() && !year && streamLooksLive && !hasGenre)) return Kind.LIVE
    if (streamLooksLive && !year) return Kind.LIVE
    return Kind.OTHER
}

private val DIACRITICS = Regex("\\p{Mn}+")

private fun normalize(value: String): String =
    java.text.Normalizer.normalize(value.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
        .replace(DIACRITICS, "")

private val episodeRegex =
    Regex("""(?i)^(.*?)[ ._\-]*(?:S\d{1,2}[ ._\-]*E\d{1,3}|\d{1,2}x\d{1,3}|T\d{1,2}[ ._\-]*E\d{1,3})(.*)$""")

private val TRAILING_SEPARATORS = Regex("""[._\-]+$""")
private val SPACES = Regex("""\s+""")
private val SXE_REGEX = Regex("""(?i)S(\d{1,2})\s*E(\d{1,3})""")
private val X_REGEX = Regex("""(?i)(\d{1,2})x(\d{1,3})""")
private val TE_REGEX = Regex("""(?i)T(\d{1,2})\s*E(\d{1,3})""")

private fun buildSeries(seriesEntries: List<Entry>): List<Series> {
    val groups = linkedMapOf<String, MutableList<Entry>>()
    for (entry in seriesEntries) {
        val match = episodeRegex.find(entry.name)
        val title = if (match != null) match.groupValues[1].replace(TRAILING_SEPARATORS, "").trim().ifBlank { entry.name.trim() }
        else entry.name.trim()
        val key = title.lowercase(Locale.ROOT).replace(SPACES, " ")
        groups.getOrPut(key) { mutableListOf() }.add(entry)
    }
    return groups.map { (_, eps) ->
        val sorted = eps.sortedWith(compareBy<Entry>({ episodeNumber(it.name).first }, { episodeNumber(it.name).second }, { it.name }))
        val first = sorted.first()
        val title = episodeRegex.find(first.name)?.groupValues?.get(1)
            ?.replace(TRAILING_SEPARATORS, "")?.trim()?.ifBlank { first.name } ?: first.name
        Series(title, sorted, sorted.firstOrNull { it.logo.isNotBlank() }?.logo.orEmpty(), first.group)
    }.sortedBy { it.title.lowercase(Locale.ROOT) }.distinctBy { it.key }
}

/** Devuelve (temporada, episodio); (0, 0) si no hay marcador. */
fun episodeNumber(name: String): Pair<Int, Int> {
    val sxe = SXE_REGEX.find(name)
    if (sxe != null) return (sxe.groupValues[1].toIntOrNull() ?: 0) to (sxe.groupValues[2].toIntOrNull() ?: 0)
    val x = X_REGEX.find(name)
    if (x != null) return (x.groupValues[1].toIntOrNull() ?: 0) to (x.groupValues[2].toIntOrNull() ?: 0)
    val te = TE_REGEX.find(name)
    if (te != null) return (te.groupValues[1].toIntOrNull() ?: 0) to (te.groupValues[2].toIntOrNull() ?: 0)
    return 0 to 0
}

// ───────────────────────── Descarga y lectura M3U ─────────────────────────

fun download(url: String): String {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000
        readTimeout = 30000
        setRequestProperty("User-Agent", "CodigoMasterIPTV/3.0")
        setRequestProperty("Cache-Control", "no-cache")
    }
    return try { conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } }
    finally { conn.disconnect() }
}

private val groupRegex = Regex("""(?i)group-title="([^"]*)"""")
private val logoRegex = Regex("""(?i)tvg-logo="([^"]*)"""")
private val idRegex = Regex("""(?i)tvg-id="([^"]*)"""")

fun parseM3u(text: String): List<Entry> {
    val result = mutableListOf<Entry>()
    var name = ""
    var group = ""
    var logo = ""
    var tvgId = ""
    var attributes = ""
    for (raw in text.removePrefix("﻿").lineSequence()) {
        val line = raw.trim()
        if (line.startsWith("#EXTINF", true)) {
            name = line.substringAfterLast(',').trim().ifBlank { "Sin título" }
            group = groupRegex.find(line)?.groupValues?.get(1).orEmpty()
            logo = logoRegex.find(line)?.groupValues?.get(1).orEmpty()
            tvgId = idRegex.find(line)?.groupValues?.get(1).orEmpty()
            attributes = line.substringBeforeLast(",", line)
        } else if (line.startsWith("http://", true) || line.startsWith("https://", true) ||
            line.startsWith("rtsp://", true) || line.startsWith("rtmp://", true) || line.startsWith("udp://", true)
        ) {
            val placeholder = name.startsWith("Nombre de la Película", true) || line.contains("servidor.com", true)
            if (name.isNotBlank() && !placeholder) result.add(Entry(name, line, group.ifBlank { "Sin categoría" }, logo, tvgId, attributes))
            name = ""; group = ""; logo = ""; tvgId = ""; attributes = ""
        }
    }
    return result.distinctBy { it.url }
}
