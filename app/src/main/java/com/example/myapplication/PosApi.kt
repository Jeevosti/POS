package com.example.myapplication

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object PosConfig {
    const val COMPANY_NAME = "Damany Solar"
    // Replace ONLY these two values with your own project settings.
    // This is the PUBLIC publishable key, never a secret/service_role key.
    const val SUPABASE_URL = "https://dgxuaibxpujbugjabreo.supabase.co"
    const val PUBLISHABLE_KEY = "sb_publishable_lxrwsJTmlfftvqme3WvavQ_T-5B2_TA"
    val configured: Boolean get() = !SUPABASE_URL.contains("YOUR_PROJECT") && !PUBLISHABLE_KEY.startsWith("YOUR_")
}

class ApiError(val status: Int, message: String, val endpoint: String) : Exception(message)

/** Blocking HTTPS client. PosController calls it exclusively on its IO executor. */
class PosApi(context: Context) {
    private val sessionStore = PosSessionStore(context)
    private var rememberSession = false
    private var accessToken = ""
    private var refreshToken = ""
    private var expiresAt = 0L
    var userId: String = ""
        private set

    val hasSavedSession: Boolean get() = sessionStore.hasSession

    fun login(email: String, password: String, remember: Boolean = false) {
        require(PosConfig.configured) { "Complete the Supabase setup in SETUP.md first." }
        clear()
        rememberSession = remember
        saveSession(request("/auth/v1/token?grant_type=password", JSONObject().put("email", email.trim()).put("password", password), ""))
    }

    fun restoreSession(): Boolean {
        val saved = sessionStore.read() ?: return false
        refreshToken = saved
        rememberSession = true
        try { refresh() }
        catch (e: ApiError) {
            if (e.status == 400 || e.status == 401) {
                clear()
                throw IllegalStateException("Saved sign-in has expired. Enter your password again.", e)
            }
            throw e
        }
        return true
    }

    private fun saveSession(response: JSONObject) {
        accessToken = response.getString("access_token")
        refreshToken = response.getString("refresh_token")
        expiresAt = System.currentTimeMillis() + response.optLong("expires_in", 3600) * 1000
        userId = response.getJSONObject("user").getString("id")
        // Save every rotated refresh token before the next API request.
        if (rememberSession) sessionStore.save(refreshToken)
    }

    private fun refresh() {
        require(refreshToken.isNotEmpty()) { "Sign in again." }
        saveSession(request("/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", refreshToken), ""))
    }

    fun rpc(name: String, payload: JSONObject = JSONObject()): JSONObject {
        if (System.currentTimeMillis() > expiresAt - 60000) refresh()
        return try {
            request("/rest/v1/rpc/$name", payload, accessToken)
        } catch (error: ApiError) {
            if (error.status != 401) throw error
            refresh()
            request("/rest/v1/rpc/$name", payload, accessToken)
        }
    }

    fun clear() {
        sessionStore.clear()
        rememberSession = false
        accessToken = ""; refreshToken = ""; expiresAt = 0; userId = ""
    }

    fun signOut() {
        // Local removal still completes when the phone has lost its connection.
        if (accessToken.isNotBlank()) runCatching { request("/auth/v1/logout?scope=local", JSONObject(), accessToken) }
        clear()
    }

    private fun request(path: String, body: JSONObject, bearer: String): JSONObject {
        val connection = URL(PosConfig.SUPABASE_URL.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 25000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("apikey", PosConfig.PUBLISHABLE_KEY)
            if (bearer.isNotEmpty()) connection.setRequestProperty("Authorization", "Bearer $bearer")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "{}"
            val response = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (status !in 200..299) {
                val message = response.optString("message", response.optString("msg", response.optString("error_description", "Server returned HTTP $status")))
                throw ApiError(status, message, path)
            }
            return response
        } finally { connection.disconnect() }
    }
}
