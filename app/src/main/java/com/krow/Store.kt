package com.krow

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Plain JSON persistence for chats/projects/settings. No secrets are stored here. */
class Store(ctx: Context) {
    private val f = File(ctx.filesDir, "krow_state.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): AppState = try {
        if (f.exists()) json.decodeFromString<AppState>(f.readText()) else AppState()
    } catch (e: Exception) {
        runCatching { f.copyTo(File(f.path + ".corrupt"), overwrite = true) }
        AppState()
    }

    fun save(s: AppState) {
        val tmp = File(f.path + ".tmp")
        tmp.writeText(json.encodeToString(s))
        tmp.renameTo(f)
    }
}

/** API keys and the GitHub token live ONLY here (AES-256 EncryptedSharedPreferences, Android Keystore backed). */
class Secrets(private val ctx: Context) {
    private val sp by lazy {
        val mk = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, "krow_secrets", mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun get(k: String): String? = try { sp.getString(k, null) } catch (e: Exception) { null }
    fun put(k: String, v: String) { sp.edit().putString(k, v).apply() }
    fun remove(k: String) { sp.edit().remove(k).apply() }

    companion object {
        fun apiKeyName(baseUrl: String) = "api|" + baseUrl.trim().trimEnd('/').lowercase()
        const val GITHUB = "github_pat"
    }
}
