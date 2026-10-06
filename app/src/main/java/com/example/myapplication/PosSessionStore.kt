package com.example.myapplication

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Called on the controller's IO executor. Saves a refresh token, never a password. */
internal class PosSessionStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "pos_saved_session.json"))
    private val alias = context.packageName + ".pos_session.v1"
    val hasSession: Boolean get() = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "The saved sign-in key is unavailable." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    fun save(refreshToken: String) {
        var output: FileOutputStream? = null
        try {
            val secret = JSONObject().put("server", PosConfig.SUPABASE_URL.trimEnd('/'))
                .put("refresh_token", refreshToken).toString().toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(true)) }
            val envelope = JSONObject()
                .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .put("data", Base64.encodeToString(cipher.doFinal(secret), Base64.NO_WRAP))
            output = file.startWrite()
            output.write(envelope.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (e: Exception) {
            output?.let { file.failWrite(it) }
            throw IllegalStateException("Could not save sign-in on this phone. Try signing in with Remember me unchecked.", e)
        }
    }

    fun read(): String? {
        if (!hasSession) return null
        try {
            val envelope = file.openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            val iv = Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)
            val encrypted = Base64.decode(envelope.getString("data"), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, iv))
            }
            val secret = JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
            if (secret.getString("server") != PosConfig.SUPABASE_URL.trimEnd('/')) {
                clear()
                return null
            }
            return secret.getString("refresh_token").also { check(it.isNotBlank()) }
        } catch (e: Exception) {
            clear()
            throw IllegalStateException("Saved sign-in could not be opened. Enter your password again.", e)
        }
    }

    fun clear() {
        file.delete()
        check(!hasSession) { "Could not remove saved sign-in from this phone." }
    }
}
