package com.codigomaster.iptv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

internal data class RemoteAppControl(
    val isSuspended: Boolean,
    val maintenanceMessage: String
)

internal suspend fun loadRemoteAppControl(): RemoteAppControl = withContext(Dispatchers.IO) {
    val endpoint = URL(
        "https://drfzysksothwyyyzgkqe.supabase.co/rest/v1/app_control?select=is_suspended,maintenance_message&id=eq.true"
    )
    val connection = (endpoint.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 10_000
        readTimeout = 10_000
        setRequestProperty("apikey", "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImRyZnp5c2tzb3Rod3l5eXpna3FlIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTQ3ODc4OTQsImV4cCI6MjExMDM2Mzg5NH0.jMkAgVVzlbDjWf4XF-2htsgxUYbf-sgK6JL635sYdvY")
        setRequestProperty("Authorization", "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImRyZnp5c2tzb3Rod3l5eXpna3FlIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTQ3ODc4OTQsImV4cCI6MjExMDM2Mzg5NH0.jMkAgVVzlbDjWf4XF-2htsgxUYbf-sgK6JL635sYdvY")
        setRequestProperty("Accept", "application/json")
    }

    try {
        val code = connection.responseCode
        if (code !in 200..299) {
            throw IllegalStateException("No se pudo consultar el control remoto (HTTP $code)")
        }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val rows = JSONArray(body)
        if (rows.length() == 0) throw IllegalStateException("No existe el estado remoto de la aplicación")
        val row = rows.getJSONObject(0)
        RemoteAppControl(
            isSuspended = row.optBoolean("is_suspended", true),
            maintenanceMessage = row.optString(
                "maintenance_message",
                "Estamos realizando mantenimiento. Intenta más tarde."
            )
        )
    } finally {
        connection.disconnect()
    }
}
