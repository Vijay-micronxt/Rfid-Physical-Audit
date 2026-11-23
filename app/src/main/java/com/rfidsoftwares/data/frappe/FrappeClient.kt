package com.rfidsoftwares.data.frappe

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Why a call to the ERP failed, so the screen can react (log in again, show the server's reason, retry). */
class FrappeException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind {
        /** No session, or it expired: the user has to log in again. */
        SESSION_EXPIRED,

        /** Logged in, but not allowed (for example no Stock Audit station role). */
        FORBIDDEN,

        /** Wrong username or password. */
        BAD_CREDENTIALS,

        /** Server could not be reached. */
        NETWORK,

        /** Anything else the server rejected. */
        SERVER,
    }
}

/**
 * Talks to an ERPNext/Frappe site. Blocking calls: run them off the main thread.
 * Reads only: after the login it sends nothing but GET requests, so no CSRF token is needed.
 */
class FrappeClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson(),
) {

    /** Checks that `baseUrl` is an ERPNext/Frappe server that answers. Needs no login. */
    fun ping(baseUrl: String) {
        val base = normalizeBaseUrl(baseUrl)
        execute(Request.Builder().url("$base/api/method/ping").get().build()).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful || !text.contains("pong")) {
                throw FrappeException(
                    FrappeException.Kind.SERVER,
                    "That address answered, but it does not look like an ERPNext server (error ${response.code}).",
                )
            }
        }
    }

    /**
     * Cancels this login's secret on the server, so it is dead from now on. A 401/403 means it already was
     * (a later login replaced it), which is the same outcome. Throws only when the server cannot be reached
     * or fails; the app clears its own copy regardless.
     */
    fun logout(session: FrappeSession) {
        val request = Request.Builder()
            .url("${session.baseUrl}/api/method/$LOGOUT_METHOD")
            .post("{}".toRequestBody(JSON))
            .header("Authorization", "token ${session.apiKey}:${session.apiSecret}")
            .build()
        execute(request).use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 401 || response.code == 403) return
            if (!response.isSuccessful) throw FrappeException(FrappeException.Kind.SERVER, serverMessage(text, response.code))
        }
    }

    /**
     * Checks the password on the server and returns the user's API key and a new secret, which every later
     * call sends as a token until the next logout. The password is not kept.
     */
    fun login(baseUrl: String, user: String, password: String): FrappeSession {
        val base = normalizeBaseUrl(baseUrl)
        val body = JsonObject().apply {
            addProperty("usr", user.trim())
            addProperty("pwd", password)
        }.toString().toRequestBody(JSON)
        val request = Request.Builder().url("$base/api/method/$LOGIN_METHOD").post(body).build()
        execute(request).use { response ->
            val text = response.body?.string().orEmpty()
            when {
                response.code == 401 || response.code == 403 ->
                    throw FrappeException(FrappeException.Kind.BAD_CREDENTIALS, loginFailure(text))
                response.code == 429 ->
                    throw FrappeException(FrappeException.Kind.SERVER, "Too many attempts. Wait a minute and try again.")
                response.code == 417 && text.contains("Failed to get method") ->
                    throw FrappeException(
                        FrappeException.Kind.SERVER,
                        "This server does not have the app login yet. Ask your admin to update the RFID Retail app.",
                    )
                !response.isSuccessful ->
                    throw FrappeException(FrappeException.Kind.SERVER, serverMessage(text, response.code))
            }
            val reply = runCatching { JsonParser.parseString(text).asJsonObject.getAsJsonObject("message") }.getOrNull()
            val apiKey = reply?.get("api_key")?.takeUnless { it.isJsonNull }?.asString
            val apiSecret = reply?.get("api_secret")?.takeUnless { it.isJsonNull }?.asString
            if (apiKey.isNullOrBlank() || apiSecret.isNullOrBlank()) {
                throw FrappeException(FrappeException.Kind.SERVER, "The server sent an unexpected reply.")
            }
            val name = reply?.get("user")?.takeUnless { it.isJsonNull }?.asString ?: user.trim()
            return FrappeSession(base, name, apiKey, apiSecret)
        }
    }

    /** A wrong password is one plain sentence; any other refusal (locked, two-factor, ...) keeps the server's reason. */
    private fun loginFailure(body: String): String {
        val reason = serverMessage(body, 401)
        return if (reason.contains("Invalid login credentials", ignoreCase = true) || reason.startsWith("The server answered")) {
            "Wrong username or password."
        } else {
            reason
        }
    }

    /** Downloads every page of the snapshot and joins them. Creates nothing on the server. */
    fun fetchSnapshot(session: FrappeSession, warehouse: String, pageLength: Int = PAGE_LENGTH): AuditSnapshot {
        val tags = ArrayList<SnapshotTag>()
        var items: List<SnapshotItem> = emptyList()
        var generatedAt = ""
        var start = 0
        while (true) {
            val page = fetchPage(session, warehouse, start, pageLength)
            if (start == 0) {
                items = page.items
                generatedAt = page.generatedAt
            }
            tags += page.tags
            start += pageLength
            if (page.tags.isEmpty() || start >= page.totalTags) break
        }
        return AuditSnapshot(warehouse, generatedAt, tags, items)
    }

    private class Page(
        val totalTags: Int,
        val generatedAt: String,
        val tags: List<SnapshotTag>,
        val items: List<SnapshotItem>,
    )

    private fun fetchPage(session: FrappeSession, warehouse: String, start: Int, pageLength: Int): Page {
        val url = "${session.baseUrl}/api/method/$SNAPSHOT_METHOD".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("warehouse", warehouse)
            ?.addQueryParameter("start", start.toString())
            ?.addQueryParameter("page_length", pageLength.toString())
            ?.build()
            ?: throw FrappeException(FrappeException.Kind.SERVER, "The server address is not valid.")
        val message = getMessage(session, url).takeIf { it.isJsonObject }?.asJsonObject
            ?: throw FrappeException(FrappeException.Kind.SERVER, "The server sent an unexpected reply.")
        val snapshot = gson.fromJson(message, AuditSnapshot::class.java)
        return Page(
            totalTags = message.get("total_tags")?.asInt ?: snapshot.tags.size,
            generatedAt = snapshot.generatedAt,
            tags = snapshot.tags,
            items = snapshot.items,
        )
    }

    /** The warehouses the user can count, for the dropdown. Reads only. */
    fun fetchWarehouses(session: FrappeSession): List<WarehouseOption> {
        val url = "${session.baseUrl}/api/method/$WAREHOUSES_METHOD".toHttpUrlOrNull()
            ?: throw FrappeException(FrappeException.Kind.SERVER, "The server address is not valid.")
        val message = getMessage(session, url).takeIf { it.isJsonArray }?.asJsonArray
            ?: throw FrappeException(FrappeException.Kind.SERVER, "The server sent an unexpected reply.")
        return message.mapNotNull { row ->
            val o = row.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val name = o.get("name")?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            WarehouseOption(name, o.get("tags")?.takeUnless { it.isJsonNull }?.asInt ?: 0)
        }
    }

    /**
     * Sends a count to the ERP as a new draft Physical Audit. Changes no stock and no tag on the server.
     * Sending the same `clientId` again returns the record already made (`duplicate = true`).
     */
    fun pushAudit(session: FrappeSession, push: AuditPush): PushedAudit {
        val request = Request.Builder()
            .url("${session.baseUrl}/api/method/$PUSH_METHOD")
            .post(gson.toJson(push).toRequestBody(JSON))
            .header("Authorization", "token ${session.apiKey}:${session.apiSecret}")
            .build()
        val reply = message(request).takeIf { it.isJsonObject }?.asJsonObject
        val name = reply?.get("name")?.takeUnless { it.isJsonNull }?.asString
        if (name.isNullOrBlank()) {
            throw FrappeException(FrappeException.Kind.SERVER, "The server sent an unexpected reply.")
        }
        return PushedAudit(name, reply.get("duplicate")?.takeUnless { it.isJsonNull }?.asBoolean ?: false)
    }

    /** GET with the token; the `message` part of a Frappe reply, or the matching failure. */
    private fun getMessage(session: FrappeSession, url: HttpUrl): com.google.gson.JsonElement =
        message(authorized(url, session))

    /** Runs a request; the `message` part of a Frappe reply, or the matching failure. */
    private fun message(request: Request): com.google.gson.JsonElement {
        execute(request).use { response ->
            val text = response.body?.string().orEmpty()
            when {
                response.code == 401 -> throw FrappeException(
                    FrappeException.Kind.SESSION_EXPIRED, "Your login is no longer valid. Please log in again.",
                )
                response.code == 403 -> throw FrappeException(
                    FrappeException.Kind.FORBIDDEN, serverMessage(text, 403),
                )
                !response.isSuccessful -> throw FrappeException(
                    FrappeException.Kind.SERVER, serverMessage(text, response.code),
                )
            }
            return runCatching { JsonParser.parseString(text).asJsonObject.get("message") }.getOrNull()
                ?.takeUnless { it.isJsonNull }
                ?: throw FrappeException(FrappeException.Kind.SERVER, "The server sent an unexpected reply.")
        }
    }

    private fun authorized(url: HttpUrl, session: FrappeSession): Request =
        Request.Builder().url(url).get()
            .header("Authorization", "token ${session.apiKey}:${session.apiSecret}")
            .build()

    private fun execute(request: Request): Response = try {
        http.newCall(request).execute()
    } catch (e: IOException) {
        throw FrappeException(FrappeException.Kind.NETWORK, "Cannot reach the server (${e.message ?: "no connection"}).")
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        const val LOGIN_METHOD = "rfid_retail.api.app_auth.login"
        const val LOGOUT_METHOD = "rfid_retail.api.app_auth.logout"
        const val SNAPSHOT_METHOD = "rfid_retail.api.catalog.audit_snapshot"
        const val WAREHOUSES_METHOD = "rfid_retail.api.catalog.audit_warehouses"
        const val PUSH_METHOD = "rfid_retail.api.physical_audit.push"
        const val PAGE_LENGTH = 2000

        /** "192.168.1.5:8088" or "http://host/" becomes "http://192.168.1.5:8088" / "http://host". */
        fun normalizeBaseUrl(input: String): String {
            val trimmed = input.trim().trimEnd('/')
            if (trimmed.isEmpty()) {
                throw FrappeException(FrappeException.Kind.SERVER, "Enter the server address.")
            }
            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
            if (withScheme.toHttpUrlOrNull() == null) {
                throw FrappeException(FrappeException.Kind.SERVER, "The server address is not valid.")
            }
            return withScheme
        }

        /** The plain reason from a Frappe error body, e.g. "You are not allowed to use the Stock Audit station." */
        fun serverMessage(body: String, code: Int): String {
            val parsed = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            val fromMessages = parsed?.get("_server_messages")?.asString?.let {
                runCatching {
                    JsonParser.parseString(it).asJsonArray.firstOrNull()?.asString
                        ?.let { m -> JsonParser.parseString(m).asJsonObject.get("message")?.asString }
                }.getOrNull()
            }
            val fromMessage = parsed?.get("message")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            val fromException = parsed?.get("exception")?.asString?.substringAfter(": ", "")?.takeIf { it.isNotBlank() }
            return (fromMessages ?: fromMessage ?: fromException ?: "The server answered with error $code.")
                .replace(Regex("<[^>]*>"), "")
        }
    }
}
