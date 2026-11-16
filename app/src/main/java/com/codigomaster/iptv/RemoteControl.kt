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
private val CM_ANON_KEY = intArrayOf(101,121,74,104,98,71,99,105,79,105,74,73,85,122,73,49,78,105,73,115,73,110,82,53,99,67,73,54,73,107,112,88,86,67,74,57,46,101,121,74,112,99,51,77,105,79,105,74,122,100,88,66,104,89,109,70,122,90,83,73,115,73,110,74,108,90,105,73,54,73,109,82,121,90,110,112,53,99,50,116,122,98,51,82,111,100,51,108,53,101,88,112,110,97,51,70,108,73,105,119,105,99,109,57,115,90,83,73,54,73,109,70,117,98,50,52,105,76,67,74,112,89,88,81,105,79,106,69,51,79,84,81,51,79,68,99,52,79,84,81,115,73,109,86,52,99,67,73,54,77,106,69,120,77,68,77,50,77,122,103,53,78,72,48,46,106,77,107,65,103,86,86,122,108,98,68,106,87,102,52,88,70,45,50,104,116,115,103,120,85,89,98,102,45,115,103,75,54,74,76,54,51,53,115,89,100,118,89).joinToString("") { it.toChar().toString() }

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
