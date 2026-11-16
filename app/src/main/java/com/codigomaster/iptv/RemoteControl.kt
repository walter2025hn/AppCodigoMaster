package com.codigomaster.iptv

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

private const val CM_SUPABASE_URL = "https://drfzysksothwyyyzgkqe.supabase.co"
private const val CM_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImRyZnp5c2tzb3Rod3l5eXpna3FlIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTQ3ODc4OTQsImV4cCI6MjExMDM2Mzg5NH0.jMkAgVVzlbDjWf4XF-2htsgxUYbf-sgK6JL635sYdvY".replace(" ", "")

internal data class ClientSession(val accessToken: String, val userId: String, val deviceKey: String)
internal data class RemoteAppControl(val isSuspended: Boolean, val maintenanceMessage: String)

private fun requestJson(url: String, method: String, token: String? = null, body: JSONObject? = null): JSONObject {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 12000
        readTimeout = 12000
        setRequestProperty("apikey", CM_ANON_KEY)
        setRequestProperty("Accept", "application/json")
        if (token != null) setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
    }
    try {
        if (body != null) connection.outputStream.bufferedWriter().use { it.write(body.toString()) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val result = if (text.isBlank()) JSONObject() else JSONObject(text)
        if (code !in 200..299) {
            val message = result.optString("msg", result.optString("message", result.optString("error", "HTTP $code")))
            throw IllegalStateException(message)
        }
        return result
    } finally {
        connection.disconnect()
    }
}

internal suspend fun loginClient(context: Context, username: String, password: String): ClientSession = withContext(Dispatchers.IO) {
    val cleanUser = username.trim().lowercase()
    require(cleanUser.matches(Regex("[a-z0-9._-]{3,32}"))) { "Escribe un usuario válido." }
    require(password.length >= 8) { "La contraseña debe tener al menos 8 caracteres." }
    val auth = requestJson(
        "$CM_SUPABASE_URL/auth/v1/token?grant_type=password",
        "POST",
        body = JSONObject().put("email", "$cleanUser@accounts.codigomaster.local").put("password", password)
    )
    val token = auth.optString("access_token")
    val userId = auth.optJSONObject("user")?.optString("id").orEmpty()
    if (token.isBlank() || userId.isBlank()) throw IllegalStateException("No se pudo iniciar sesión.")
    val prefs = context.getSharedPreferences("mastercode_device", Context.MODE_PRIVATE)
    val key = prefs.getString("device_key", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("device_key", it).apply()
    }
    val registered = requestJson(
        "$CM_SUPABASE_URL/functions/v1/master-control",
        "POST", token,
        JSONObject()
            .put("action", "client_register_device")
            .put("device_key", key)
            .put("device_name", (Build.MANUFACTURER + " " + Build.MODEL).trim())
            .put("model", Build.MODEL)
            .put("android_version", Build.VERSION.RELEASE ?: "")
            .put("app_version", "4.5.0")
    )
    if (!registered.optBoolean("ok", false)) {
        val reason = registered.optString("reason")
        val message = when (reason) {
            "device_limit" -> "Ya alcanzaste el límite de dispositivos de esta cuenta. Pide al administrador que libere un dispositivo."
            "device_blocked" -> "Este dispositivo está bloqueado por el administrador."
            "account_inactive" -> "La cuenta está suspendida o vencida."
            else -> registered.optString("error", "No se pudo registrar este dispositivo.")
        }
        throw IllegalStateException(message)
    }
    ClientSession(token, userId, key)
}

internal suspend fun loadClientStatus(accessToken: String, deviceKey: String): RemoteAppControl = withContext(Dispatchers.IO) {
    val status = requestJson(
        "$CM_SUPABASE_URL/functions/v1/master-control",
        "POST", accessToken,
        JSONObject().put("action", "client_status").put("device_key", deviceKey)
    )
    val message = when {
        !status.optBoolean("account_active", false) -> "Tu cuenta está suspendida o vencida. Contacta al administrador."
        status.optBoolean("global_suspended", false) -> status.optString("maintenance_message", "La aplicación está en mantenimiento.")
        !status.optBoolean("device_registered", false) -> "Este dispositivo no está registrado. Vuelve a iniciar sesión."
        status.optBoolean("device_blocked", false) -> "Este dispositivo ha sido bloqueado por el administrador."
        else -> ""
    }
    RemoteAppControl(message.isNotBlank(), message)
}
