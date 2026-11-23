package com.rfidsoftwares.data.frappe

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import java.io.File

/**
 * Keeps the ERP login on the phone: the server address, the user name, the API key, and the API
 * secret (encrypted; the password is never stored). Also keeps the last downloaded snapshot so the
 * audit screen opens straight away and can compare without a connection.
 */
class FrappeSessionStore(
    context: Context,
    private val cipher: SecretCipher = KeystoreSecretCipher(),
) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val snapshotFile: File get() = File(appContext.filesDir, SNAPSHOT_FILE)

    /** The logged-in user, or null when nobody is logged in or the stored secret can no longer be read. */
    fun session(): FrappeSession? {
        val base = prefs.getString(KEY_BASE_URL, null)
        val user = prefs.getString(KEY_USER, null)
        val apiKey = prefs.getString(KEY_API_KEY, null)
        val encrypted = prefs.getString(KEY_API_SECRET, null)
        if (base.isNullOrBlank() || user.isNullOrBlank() || apiKey.isNullOrBlank() || encrypted.isNullOrBlank()) return null
        val secret = cipher.decrypt(encrypted)
        if (secret.isNullOrEmpty()) {
            clearCredentials()
            return null
        }
        return FrappeSession(base, user, apiKey, secret)
    }

    fun saveSession(session: FrappeSession) {
        prefs.edit()
            .putString(KEY_BASE_URL, session.baseUrl)
            .putString(KEY_USER, session.user)
            .putString(KEY_API_KEY, session.apiKey)
            .putString(KEY_API_SECRET, cipher.encrypt(session.apiSecret))
            .apply()
    }

    /** The server address set in the login screen's settings (kept across logins and logouts). */
    fun lastBaseUrl(): String = prefs.getString(KEY_BASE_URL, "").orEmpty()

    fun saveBaseUrl(baseUrl: String) {
        prefs.edit().putString(KEY_BASE_URL, baseUrl).apply()
    }

    fun lastUser(): String = prefs.getString(KEY_USER, "").orEmpty()

    fun lastWarehouse(): String = prefs.getString(KEY_WAREHOUSE, "").orEmpty()

    fun saveWarehouse(warehouse: String) {
        prefs.edit().putString(KEY_WAREHOUSE, warehouse).apply()
    }

    /** The last warehouse list from the server, so the dropdown is filled even without a connection. */
    fun savedWarehouses(): List<WarehouseOption> = runCatching {
        gson.fromJson(prefs.getString(KEY_WAREHOUSE_LIST, null), Array<WarehouseOption>::class.java)?.toList()
    }.getOrNull().orEmpty()

    fun saveWarehouses(options: List<WarehouseOption>) {
        prefs.edit().putString(KEY_WAREHOUSE_LIST, gson.toJson(options)).apply()
    }

    /** The server no longer accepts the key: forget it but keep the address and user name for the next login. */
    fun clearCredentials() {
        prefs.edit().remove(KEY_API_KEY).remove(KEY_API_SECRET).apply()
    }

    /** Log out: forget the key and secret and delete the downloaded business data. */
    fun logout() {
        clearCredentials()
        snapshotFile.delete()
        prefs.edit().remove(KEY_WAREHOUSE_LIST).apply()
    }

    fun saveSnapshot(snapshot: AuditSnapshot) {
        // Written to a temp file first so a crash mid-write cannot leave a half-written snapshot.
        val temp = File(snapshotFile.parentFile, "$SNAPSHOT_FILE.tmp")
        temp.writeText(gson.toJson(snapshot))
        if (!temp.renameTo(snapshotFile)) {
            snapshotFile.writeText(temp.readText())
            temp.delete()
        }
    }

    fun loadSnapshot(): AuditSnapshot? = runCatching {
        if (!snapshotFile.exists()) null else gson.fromJson(snapshotFile.readText(), AuditSnapshot::class.java)
    }.getOrNull()

    private companion object {
        const val PREFS_NAME = "frappe_audit_session"
        const val SNAPSHOT_FILE = "audit_snapshot.json"
        const val KEY_BASE_URL = "base_url"
        const val KEY_USER = "user"
        const val KEY_API_KEY = "api_key"
        const val KEY_API_SECRET = "api_secret_enc"
        const val KEY_WAREHOUSE = "warehouse"
        const val KEY_WAREHOUSE_LIST = "warehouse_list"
    }
}
