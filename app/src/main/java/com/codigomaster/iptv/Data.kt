package com.codigomaster.iptv

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
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
    val attributes: String = "",
    /** Sección declarada en la lista con marcas «---- CANALES ----»: LIVE, SERIES, MOVIE o vacío. */
    val section: String = ""
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
    private val cacheFile = File(app.filesDir, "playlist_cache.jsonl.gz")
    private val legacyCacheFile = File(app.filesDir, "playlist_cache.json")

    var playlistUrl: String
        get() = prefs.getString("custom_url", "").orEmpty()
        set(value) { prefs.edit().putString("custom_url", value.trim()).apply() }

    val effectiveUrl: String get() = playlistUrl.ifBlank { DEFAULT_PLAYLIST_URL }

    /** AUTO, EXO o VLC. */
    var engine: String
        get() = prefs.getString("engine", "AUTO").orEmpty().ifBlank { "AUTO" }
        set(value) { prefs.edit().putString("engine", value).apply() }

    /** Clave gratuita de TMDB (opcional) para mostrar la calificación del público. */
    var tmdbKey: String
        get() = prefs.getString("tmdb_key", "").orEmpty()
        set(value) { prefs.edit().putString("tmdb_key", value.trim()).apply() }

    /** Tus estrellas (1-5) por título/serie/canal. Observable: la interfaz se actualiza sola. */
    val userRatings = mutableStateMapOf<String, Int>().apply {
        try {
            val o = JSONObject(prefs.getString("user_ratings", "{}").orEmpty())
            o.keys().forEach { k -> put(k, o.getInt(k)) }
        } catch (_: Exception) { }
    }

    fun rate(key: String, stars: Int) {
        if (stars in 1..5) userRatings[key] = stars else userRatings.remove(key)
        prefs.edit().putString("user_ratings", JSONObject(userRatings.toMap()).toString()).apply()
    }

    fun cachedPublic(key: String): String? = prefs.getString("pr_" + key.hashCode(), null)
    fun cachePublic(key: String, value: String) { prefs.edit().putString("pr_" + key.hashCode(), value).apply() }

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

    /**
     * Caché comprimida y en JSON Lines: no construye un JSONArray/JSON gigante en RAM.
     * El archivo antiguo (JSONArray sin comprimir) se sigue leyendo para migrar sin perder datos.
     */
    fun saveCache(entries: List<Entry>) {
        val temp = File(app.filesDir, "playlist_cache.jsonl.gz.tmp")
        GZIPOutputStream(temp.outputStream().buffered()).bufferedWriter(Charsets.UTF_8).use { out ->
            entries.forEach { e ->
                out.write(JSONObject().apply {
                    put("name", e.name); put("url", e.url); put("group", e.group)
                    put("logo", e.logo); put("tvgId", e.tvgId); put("attributes", e.attributes); put("section", e.section)
                }.toString())
                out.newLine()
            }
        }
        if (cacheFile.exists()) cacheFile.delete()
        if (!temp.renameTo(cacheFile)) {
            temp.copyTo(cacheFile, overwrite = true)
            temp.delete()
        }
        // readCache detecta automáticamente el formato comprimido nuevo.
        prefs.edit().remove("playlist").apply()
    }

    fun savePageHashes(sourceUrl: String, hashes: List<String>) {
        prefs.edit()
            .putString("page_hashes_source", sourceUrl)
            .putString("page_hashes", JSONArray(hashes).toString())
            .apply()
    }

    fun readPageHashes(sourceUrl: String): List<String> {
        if (prefs.getString("page_hashes_source", null) != sourceUrl) return emptyList()
        return try {
            val array = JSONArray(prefs.getString("page_hashes", "[]").orEmpty())
            List(array.length()) { index -> array.getString(index) }
        } catch (_: Exception) { emptyList() }
    }

    fun readCache(): List<Entry> {
        return try {
        if (!cacheFile.exists()) {
            if (legacyCacheFile.exists()) return readLegacyArray(legacyCacheFile.readText(Charsets.UTF_8))
            val legacy = prefs.getString("playlist", "[]").orEmpty()
            return readLegacyArray(legacy)
        }
        cacheFile.inputStream().buffered().use { input ->
            input.mark(2)
            val first = input.read()
            val second = input.read()
            input.reset()
            val reader = if (first == 0x1f && second == 0x8b) {
                GZIPInputStream(input).bufferedReader(Charsets.UTF_8)
            } else input.bufferedReader(Charsets.UTF_8)
            reader.use { source ->
                source.mark(1)
                var firstLine = source.readLine()
                while (firstLine != null && firstLine.isBlank()) firstLine = source.readLine()
                if (firstLine == null) emptyList()
                else if (firstLine.trimStart().startsWith("[")) {
                    readLegacyArray(firstLine + "\n" + source.readText())
                } else {
                    buildList {
                        var line: String? = firstLine
                        // Recuperar todo el contenido persistido, no solo la primera página.
                        while (line != null) {
                            if (line.isNotBlank()) {
                                val o = JSONObject(line)
                                add(Entry(o.optString("name"), o.optString("url"), o.optString("group"),
                                    o.optString("logo"), o.optString("tvgId"), o.optString("attributes"), o.optString("section")))
                            }
                            line = source.readLine()
                        }
                    }
                }
            }
        }
        } catch (_: Exception) { emptyList() }
    }

    private fun readLegacyArray(raw: String): List<Entry> {
        val arr = JSONArray(raw)
        return buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(Entry(o.optString("name"), o.optString("url"), o.optString("group"),
                    o.optString("logo"), o.optString("tvgId"), o.optString("attributes"), o.optString("section")))
            }
        }
    }
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

private val EPISODE_PATTERN = Regex("""\b(s\d{1,2}\s*e\d{1,3}|\d{1,2}x\d{1,3}|t\d{1,2}\s*e\d{1,3}|(?:temporada|season)\s*\d+\s*(?:cap[ií]tulo|episodio|episode|ep)\s*\d+|(?:cap[ií]tulo|episodio|episode|ep)\s*\d+)\b""", RegexOption.IGNORE_CASE)
private val SEASON_ONLY_PATTERN = Regex("""\b(?:temporada|season)\s*\d+\b""", RegexOption.IGNORE_CASE)
private val YEAR_PATTERN = Regex("""\b(19\d{2}|20\d{2})\b""")
private val TOKEN_SPLIT = Regex("[^a-z0-9]+")
private val BARE_ID_PATH = Regex("""/\d+/?$""")

// Palabras COMPLETAS (tokens) del grupo. Se comparan por token para que "cinemax" o "cinecanal"
// (canales) no se confundan con "cine" (películas).
private val LIVE_GROUP_TOKENS = setOf(
    "canales", "canal", "vivo", "live", "radio", "deportes", "deporte", "sports", "noticias", "news",
    "musica", "infantil", "iptv", "abierta"
)
private val SERIES_GROUP_TOKENS = setOf("serie", "series", "temporadas", "temporada", "novelas", "telenovelas", "doramas", "shows")
private val MOVIE_GROUP_TOKENS = setOf("pelicula", "peliculas", "movie", "movies", "vod", "cine", "film", "films", "estrenos", "cinema")
private val GENRE_GROUP_TOKENS = setOf("fantasia", "accion", "aventura", "drama", "comedia", "terror", "animacion", "romance", "ciencia", "documental", "documentales", "suspenso", "thriller", "anime", "familia")
private val MOVIE_ATTRS = listOf("type=movie", "tvg-type=movie", "content-type=movie", "media-type=movie", "vod=true")
private val SERIES_ATTRS = listOf("type=series", "tvg-type=series", "content-type=series", "media-type=series")
private val LIVE_ATTRS = listOf("type=live", "tvg-type=live", "content-type=live", "media-type=live")
private val QUALITY_WORDS = listOf("1080p", "720p", "2160p", "bluray", "blu-ray", "web-dl", "webrip", "hdrip", "x264", "x265")
private val VOD_EXTENSIONS = listOf(".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".m4v", ".mpg", ".mpeg", ".webm")
private val LIVE_EXTENSIONS = listOf(".m3u8", ".ts", ".m3u", ".php")
private val LIVE_PATHS = listOf("/live/", "/channel/", "/channels/", "/stream/", "/iptv/", "/tv/")
private val VOD_URL_PARTS = listOf("/vod/", "/peliculas/", "/film/", "/films/")
// Nombres de canales conocidos: el nombre EMPIEZA por ellos (o los contiene como palabra completa).
private val CHANNEL_NAMES = listOf(
    "espn", "fox", "tnt", "hbo", "cinemax", "cinecanal", "axn", "tlc", "tudn", "dazn", "directv", "win sports",
    "discovery", "national geographic", "nat geo", "cnn", "bbc", "televisa", "telemundo", "univision", "unimas",
    "cartoon network", "cartoonito", "nickelodeon", "nick jr", "animal planet", "history channel", "a&e", "disney channel",
    "disney junior", "paramount network", "comedy central", "studio universal", "star channel", "de pelicula",
    "las estrellas", "azteca", "canal", "caracol", "rcn", "telefe", "tve", "antena 3", "la sexta", "mtv", "vh1",
    "gala tv", "foro tv", "adn 40", "tvn", "chilevision", "americatv", "willax", "euronews", "france 24",
    "al jazeera", "bein", "tyc"
)

private fun tokens(value: String): Set<String> = value.split(TOKEN_SPLIT).filter { it.isNotEmpty() }.toSet()

private fun looksLikeChannelName(name: String): Boolean = CHANNEL_NAMES.any { n ->
    name.startsWith(n) && (name.length == n.length || !name[n.length].isLetterOrDigit())
}

fun classify(e: Entry): Kind {
    // La lista del usuario ya viene ordenada por secciones («---- CANALES ----», «---- SERIES ----»,
    // «---- PELICULAS ----»): es la señal más fiable, por encima de cualquier heurística.
    when (e.section) {
        "LIVE" -> return Kind.LIVE
        "SERIES" -> return Kind.SERIES
        "MOVIE" -> return Kind.MOVIE
    }
    val name = normalize(e.name)
    val group = normalize(e.group)
    val attrs = normalize(e.attributes)
    val url = e.url.lowercase(Locale.ROOT)
    val path = url.substringBefore('?').substringBefore('#')
    val groupTokens = tokens(group)

    // 1) Rutas del proveedor (Xtream): la señal más fiable.
    if (path.contains("/series/")) return Kind.SERIES
    if (path.contains("/movie/") || path.contains("/movies/")) return Kind.MOVIE
    if (path.contains("/live/")) return Kind.LIVE

    // 2) Etiquetas explícitas del M3U.
    if (SERIES_ATTRS.any { attrs.contains(it) }) return Kind.SERIES
    if (MOVIE_ATTRS.any { attrs.contains(it) }) return Kind.MOVIE
    if (LIVE_ATTRS.any { attrs.contains(it) }) return Kind.LIVE

    // 3) Marcadores de episodio en el nombre.
    if (EPISODE_PATTERN.containsMatchIn(name) || SEASON_ONLY_PATTERN.containsMatchIn(name)) return Kind.SERIES

    // 4) Archivo de vídeo suelto (mp4/mkv/avi…) = contenido a la carta; no es un canal.
    val isVodFile = VOD_EXTENSIONS.any { path.endsWith(it) }
    val streamLooksLive = !isVodFile && (LIVE_EXTENSIONS.any { path.endsWith(it) } || BARE_ID_PATH.containsMatchIn(path) ||
        LIVE_PATHS.any { path.contains(it) })

    // 5) Grupo explícito de canales: gana a «pelicula/cine» (p. ej. «Canales de Cine», «Cinemax», «TV · Películas»).
    val liveGroup = groupTokens.any { it in LIVE_GROUP_TOKENS } || group.contains("en vivo") || group.contains("24/7")
    val seriesGroup = groupTokens.any { it in SERIES_GROUP_TOKENS }
    val movieGroup = groupTokens.any { it in MOVIE_GROUP_TOKENS } || VOD_URL_PARTS.any { path.contains(it) }
    if (liveGroup && !isVodFile) return Kind.LIVE

    // 6) Nombre de canal conocido o tvg-id (las películas casi nunca traen tvg-id) con stream en vivo.
    if (!isVodFile && looksLikeChannelName(name) && !movieGroup && !seriesGroup) return Kind.LIVE
    if (streamLooksLive && e.tvgId.isNotBlank() && !movieGroup && !seriesGroup) return Kind.LIVE

    // 7) Grupos de series / películas.
    if (seriesGroup) return Kind.SERIES
    if (movieGroup || isVodFile) return Kind.MOVIE

    // 8) Sin pistas claras: un stream en vivo sin año es un canal; con año/calidad/género es película.
    val year = YEAR_PATTERN.containsMatchIn(name)
    val quality = QUALITY_WORDS.any { name.contains(it) || path.contains(it) }
    val hasGenre = groupTokens.any { it in GENRE_GROUP_TOKENS }
    if (year && (hasGenre || quality)) return Kind.MOVIE
    if (streamLooksLive) return Kind.LIVE
    if (hasGenre) return Kind.MOVIE
    return Kind.OTHER
}

private val ANIME_WORDS = setOf("anime", "animacion japonesa", "animacion japonesa", "manga", "otaku", "donghua")
private val KIDS_WORDS = setOf("kids", "kid", "infantil", "infantiles", "ninos", "ninas", "preescolar", "cartoons", "cartoon", "disney junior", "nick jr")

fun isAnimeContent(name: String, group: String): Boolean {
    val value = normalize("$name $group")
    return ANIME_WORDS.any { normalize(it) in value } ||
        listOf("naruto", "dragon ball", "one piece", "pokemon", " pokémon ", "demon slayer", "jujutsu kaisen", "my hero academia").any { value.contains(normalize(it)) }
}

fun isKidsContent(name: String, group: String): Boolean {
    val value = normalize("$name $group")
    return KIDS_WORDS.any { normalize(it) in value } ||
        listOf("paw patrol", "peppa pig", "mickey mouse", "baby shark", "cocomelon", "dora la exploradora").any { value.contains(normalize(it)) }
}

private val DIACRITICS = Regex("\\p{Mn}+")

private fun normalize(value: String): String =
    java.text.Normalizer.normalize(value.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
        .replace(DIACRITICS, "")

private val episodeRegex =
    Regex("""(?i)^(.*?)[ ._\-]*(?:S\d{1,2}[ ._\-]*E\d{1,3}|\d{1,2}x\d{1,3}|T\d{1,2}[ ._\-]*E\d{1,3}|(?:temporada|season)\s*\d+\s*(?:cap[ií]tulo|episodio|episode|ep)\s*\d+)(.*)$""")
private val seasonOnlyRegex = Regex("""(?i)^(.*?)[ ._\-]*(?:temporada|season)\s*\d+.*$""")
private val SEASON_EP_REGEX = Regex("""(?i)(?:temporada|season)\s*(\d+)\s*(?:cap[ií]tulo|episodio|episode|ep)\s*(\d+)""")
private val SEASON_ONLY_NUMBER_REGEX = Regex("""(?i)(?:temporada|season)\s*(\d+)""")
private val EP_ONLY_NUMBER_REGEX = Regex("""(?i)(?:cap[ií]tulo|episodio|episode|ep)\s*(\d+)""")

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
        else seasonOnlyRegex.find(entry.name)?.groupValues?.get(1)?.replace(TRAILING_SEPARATORS, "")?.trim()?.ifBlank { entry.name.trim() }
            ?: entry.name.trim()
        val key = title.lowercase(Locale.ROOT).replace(SPACES, " ")
        groups.getOrPut(key) { mutableListOf() }.add(entry)
    }
    return groups.map { (_, eps) ->
        val sorted = eps.sortedWith(compareBy<Entry>({ episodeNumber(it.name).first }, { episodeNumber(it.name).second }, { it.name }))
        val first = sorted.first()
        val title = episodeRegex.find(first.name)?.groupValues?.get(1)
            ?.replace(TRAILING_SEPARATORS, "")?.trim()?.ifBlank { first.name }
            ?: seasonOnlyRegex.find(first.name)?.groupValues?.get(1)?.replace(TRAILING_SEPARATORS, "")?.trim()?.ifBlank { first.name }
            ?: first.name
        Series(title, sorted, sorted.firstOrNull { it.logo.isNotBlank() }?.logo.orEmpty(), first.group)
    }.sortedBy { it.title.lowercase(Locale.ROOT) }.distinctBy { it.key }
}

/** Devuelve (temporada, episodio); (0, 0) si no hay marcador. */
fun episodeNumber(name: String): Pair<Int, Int> {
    val seasonEpisode = SEASON_EP_REGEX.find(name)
    if (seasonEpisode != null) return (seasonEpisode.groupValues[1].toIntOrNull() ?: 0) to (seasonEpisode.groupValues[2].toIntOrNull() ?: 0)
    val seasonOnly = SEASON_ONLY_NUMBER_REGEX.find(name)?.groupValues?.get(1)?.toIntOrNull()
    val episodeOnly = EP_ONLY_NUMBER_REGEX.find(name)?.groupValues?.get(1)?.toIntOrNull()
    if (seasonOnly != null || episodeOnly != null) return (seasonOnly ?: 0) to (episodeOnly ?: 0)
    val sxe = SXE_REGEX.find(name)
    if (sxe != null) return (sxe.groupValues[1].toIntOrNull() ?: 0) to (sxe.groupValues[2].toIntOrNull() ?: 0)
    val x = X_REGEX.find(name)
    if (x != null) return (x.groupValues[1].toIntOrNull() ?: 0) to (x.groupValues[2].toIntOrNull() ?: 0)
    val te = TE_REGEX.find(name)
    if (te != null) return (te.groupValues[1].toIntOrNull() ?: 0) to (te.groupValues[2].toIntOrNull() ?: 0)
    return 0 to 0
}

// ───────────────────────── Descarga y lectura M3U ─────────────────────────

/** Descarga y analiza línea por línea: evita mantener la M3U completa como String en memoria. */
fun downloadEntries(url: String): List<Entry> {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000
        readTimeout = 60000
        setRequestProperty("User-Agent", "CodigoMasterIPTV/4.4")
        setRequestProperty("Cache-Control", "no-cache")
        instanceFollowRedirects = true
    }
    return try {
        conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader -> parseM3u(reader) }
    } finally { conn.disconnect() }
}

private val groupRegex = Regex("""(?i)group-title="([^"]*)"""")
private val logoRegex = Regex("""(?i)tvg-logo="([^"]*)"""")
private val idRegex = Regex("""(?i)tvg-id="([^"]*)"""")

private val SECTION_MARKER = Regex("""^[-=_*#~\s]{2,}([^-=_*#~].*?)[-=_*#~\s]{2,}$""")

private fun sectionOf(marker: String): String {
    val t = java.text.Normalizer.normalize(marker.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD).replace(DIACRITICS, "")
    return when {
        t.contains("canal") || t.contains("en vivo") || t.contains("live") -> "LIVE"
        t.contains("serie") || t.contains("novela") -> "SERIES"
        t.contains("pelicula") || t.contains("peli") || t.contains("cine") || t.contains("movie") || t.contains("vod") -> "MOVIE"
        t.contains("tv") -> "LIVE"
        else -> ""
    }
}

fun parseM3u(text: String): List<Entry> = parseM3uLines(text.removePrefix("\uFEFF").lineSequence())

fun parseM3u(reader: BufferedReader): List<Entry> = parseM3uLines(reader.lineSequence())

private fun parseM3uLines(lines: Sequence<String>): List<Entry> {
    val result = ArrayList<Entry>()
    var name = ""
    var group = ""
    var logo = ""
    var tvgId = ""
    var attributes = ""
    var section = ""
    for (raw in lines) {
        val line = raw.trim()
        if (line.startsWith("#EXTINF", true)) {
            // Las comas dentro del título («El león, la bruja y el ropero») no deben cortarlo:
            // el título empieza tras el último «",» de los atributos.
            val cut = line.lastIndexOf("\",")
            val titleStart = if (cut >= 0) cut + 2 else line.indexOf(',') + 1
            name = (if (titleStart > 0) line.substring(titleStart) else "").trim().ifBlank { "Sin título" }
            group = groupRegex.find(line)?.groupValues?.get(1).orEmpty()
            logo = logoRegex.find(line)?.groupValues?.get(1).orEmpty()
            tvgId = idRegex.find(line)?.groupValues?.get(1).orEmpty()
            attributes = if (titleStart > 0) line.substring(0, titleStart - 1) else line
        } else if (line.startsWith("http://", true) || line.startsWith("https://", true) ||
            line.startsWith("rtsp://", true) || line.startsWith("rtmp://", true) || line.startsWith("udp://", true)
        ) {
            val placeholder = name.startsWith("Nombre de la Película", true) || line.contains("servidor.com", true)
            if (name.isNotBlank() && !placeholder) result.add(Entry(name, line, group.ifBlank { "Sin categoría" }, logo, tvgId, attributes, section))
            name = ""; group = ""; logo = ""; tvgId = ""; attributes = ""
        } else if (line.isNotEmpty() && !line.startsWith("#")) {
            // Marca de sección tipo «---- CANALES ----».
            SECTION_MARKER.find(line)?.let { m ->
                val found = sectionOf(m.groupValues[1])
                if (found.isNotEmpty()) section = found
            }
        }
    }
    return result.distinctBy { it.url }
}
