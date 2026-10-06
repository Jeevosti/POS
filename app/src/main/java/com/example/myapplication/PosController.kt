package com.example.myapplication

import android.app.Application
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

class PosController(app: Application) : AndroidViewModel(app) {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val api = PosApi(app)
    private val settings = app.getSharedPreferences("pos_settings", 0)
    // Pending requests survive restart. Remember me saves only an encrypted refresh token.
    private val prefs = app.getSharedPreferences("pos_pending", 0)
    var data by mutableStateOf<PosData?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
        private set
    var error by mutableStateOf("")
        private set
    var hasPending by mutableStateOf(prefs.contains("request"))
        private set
    var signedIn by mutableStateOf(false)
        private set
    var hasSavedSignIn by mutableStateOf(api.hasSavedSession)
        private set
    var darkMode by mutableStateOf(settings.getBoolean("dark_mode",
        app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES))
        private set
    var updatedAt by mutableStateOf(0L)
        private set
    var cart by mutableStateOf<List<CartLine>>(emptyList())
    var csvContent: String = ""

    init { if (hasSavedSignIn && PosConfig.configured) restoreLogin() }

    fun toggleDarkMode() {
        darkMode = !darkMode
        settings.edit().putBoolean("dark_mode", darkMode).apply()
    }

    fun localError(text: String) { error = text; message = "" }
    private fun run(work: () -> Unit, done: () -> Unit = {}, silent: Boolean = false) {
        if (busy) return
        busy = true
        if (!silent) { error = ""; message = "" }
        io.execute {
            try { work(); main.post { busy = false; hasSavedSignIn = api.hasSavedSession; done() } }
            catch (e: Exception) { main.post { busy = false; error = e.message ?: "Operation failed. Check your connection."; hasPending = prefs.contains("request"); hasSavedSignIn = api.hasSavedSession } }
        }
    }

    fun login(email: String, password: String, remember: Boolean = false) = run({
        api.login(email,password,remember)
        val snapshot = PosData(api.rpc("pos_snapshot"))
        main.post { data = snapshot; signedIn = true; updatedAt = System.currentTimeMillis() }
    })

    fun restoreLogin() = run({
        check(api.restoreSession()) { "No saved sign-in. Enter your email and password." }
        val snapshot = PosData(api.rpc("pos_snapshot"))
        main.post { data = snapshot; signedIn = true; updatedAt = System.currentTimeMillis() }
    })

    fun forgetSavedSignIn() = run({ api.clear() })

    fun refresh(silent: Boolean = false) = run({
        val snapshot = PosData(api.rpc("pos_snapshot"))
        main.post { data = snapshot; updatedAt = System.currentTimeMillis() }
    }, silent = silent)

    fun logout() = run({ api.signOut() }, {
        data = null; signedIn = false; message = ""; error = ""; cart = emptyList()
    })

    fun command(action: String, payload: JSONObject, done: () -> Unit = {}) {
        if (busy) return
        if (prefs.contains("request")) { localError("Retry the pending operation before starting another one."); return }
        val request = JSONObject().put("p_request",UUID.randomUUID().toString())
            .put("p_action",action).put("p_payload",payload)
        // Synchronous persist before sending: an interrupted app can retry the same ID.
        val saved = prefs.edit().putString("request",request.toString()).putString("user",api.userId).commit()
        if (!saved) { localError("Could not save the request on this device. Nothing was sent."); return }
        hasPending = true
        executeSaved(done)
    }

    fun retry() { if (!busy) executeSaved() }

    private fun executeSaved(done: () -> Unit = {}) = run({
        require(prefs.getString("user","") == api.userId) { "Sign in with the staff account that started this pending operation." }
        val body = JSONObject(prefs.getString("request",null) ?: throw IllegalStateException("No pending operation."))
        val result = try { api.rpc("pos_execute",body) }
        catch (e: ApiError) {
            // 4xx operation responses are definite rejections. Network/5xx errors
            // keep the UUID: the server might already have committed the transaction.
            if (e.endpoint == "/rest/v1/rpc/pos_execute" && e.status in 400..499 &&
                e.status != 401 && e.status != 403 && !e.message.orEmpty().contains("not an active"))
                prefs.edit().clear().commit()
            throw e
        }
        val cleared = prefs.edit().clear().commit()
        main.post { hasPending = !cleared; message = result.optString("message","Saved."); if (body.optString("p_action") == "create_invoice") cart = emptyList(); done() }
        try {
            val snapshot = PosData(api.rpc("pos_snapshot"))
            main.post { data = snapshot; updatedAt = System.currentTimeMillis() }
        } catch (e: Exception) {
            main.post { error = "Saved on server. Refresh to load the latest records. ${e.message ?: ""}" }
        }
    })

    fun exportFile(work: () -> Unit) = run(work, { message = "File saved." })

    override fun onCleared() { io.shutdown(); super.onCleared() }
}
