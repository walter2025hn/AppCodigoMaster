package com.codigomaster.iptv

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Fuente HTTP paginada del catálogo Código Master.
 * Cada llamada loadPage descarga como máximo 50 registros JSON.
 */
/** Huella estable por página: permite reconocer la caché anterior aunque aún no guardara hashes. */
fun pageFingerprint(entries: List<Entry>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    entries.forEach { entry ->
        listOf(entry.name, entry.url, entry.group, entry.logo, entry.tvgId, entry.attributes, entry.section).forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            val size = bytes.size
            digest.update(byteArrayOf(
                (size ushr 24).toByte(),
                (size ushr 16).toByte(),
                (size ushr 8).toByte(),
                size.toByte()
            ))
            digest.update(bytes)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

data class CatalogManifest(
    val total: Int,
    val pageSize: Int,
    val pageCount: Int,
    val sourceSha256: String,
    val pageSha256: List<String>
)

class PagedCatalogSource(
    private val baseUrl: String = "https://raw.githubusercontent.com/walter2025hn/MiListaPremium/main/catalog_pages"
 ) {
    val cacheKey: String get() = baseUrl

    fun loadManifest(): CatalogManifest {
        val json = JSONObject(get("$baseUrl/manifest.json"))
        require(json.optInt("schema") == 1) { "Versión de catálogo no compatible" }
        return CatalogManifest(
            total = json.optInt("total"),
            pageSize = json.optInt("page_size", 50),
            pageCount = json.optInt("page_count"),
            sourceSha256 = json.optString("source_sha256"),
            pageSha256 = json.optJSONArray("page_sha256")?.let { hashes ->
                List(hashes.length()) { index -> hashes.getString(index) }
            }.orEmpty()
        ).also {
            require(it.pageSize == 50 && it.pageCount >= 0) { "Manifiesto de catálogo inválido" }
            require(it.pageSha256.isEmpty() || it.pageSha256.size == it.pageCount) { "Huellas del catálogo incompletas" }
        }
    }

    fun loadPage(page: Int): List<Entry> {
        require(page >= 1) { "La página comienza en 1" }
        val json = JSONArray(get("$baseUrl/page-%06d.json".format(page)))
        return buildList(json.length()) {
            for (i in 0 until json.length()) {
                val item = json.getJSONObject(i)
                add(Entry(
                    name = item.optString("name"),
                    url = item.optString("url"),
                    group = item.optString("group", "Sin categoría"),
                    logo = item.optString("logo"),
                    tvgId = item.optString("tvgId"),
                    attributes = item.optString("attributes"),
                    section = item.optString("section")
                ))
            }
        }
    }

    private fun get(address: String): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 20000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "CodigoMasterTV/4.4")
            setRequestProperty("Cache-Control", "no-cache")
            instanceFollowRedirects = true
        }
        return try {
            require(connection.responseCode in 200..299) { "Error HTTP ${connection.responseCode}" }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
