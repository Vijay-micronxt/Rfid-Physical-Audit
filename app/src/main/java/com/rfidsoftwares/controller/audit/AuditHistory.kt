package com.rfidsoftwares.controller.audit

import android.content.Context
import com.google.gson.Gson
import java.io.File

/** One count that was sent to the ERP, kept on the phone so it can be looked at again. */
data class HistoryEntry(
    /** The ERP record it became, for example PA-2026-00001. */
    val name: String,
    val user: String,
    val warehouse: String,
    /** When the phone downloaded the warehouse (server time), as sent with the count. */
    val downloadedAt: String,
    /** When it was sent, phone clock (milliseconds). */
    val sentAt: Long,
    val items: List<ItemResult>,
    val unknownEpcs: List<String>,
) {
    val scannedUnits: Int get() = items.sumOf { it.scannedQty }
    val expectedUnits: Int get() = items.sumOf { it.siteQty }

    /** The same figures the scanning screen shows, so a past count reads exactly like a live one. */
    fun toResult(): AuditResult = AuditResult(items, unknownEpcs)
}

/**
 * The counts this user has sent, newest first. Kept in a file in the app's private storage. Logging out deletes
 * it, and a user only ever sees their own entries.
 */
class AuditHistoryStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val gson = Gson()

    @Synchronized
    fun list(user: String): List<HistoryEntry> = load().filter { it.user == user }.sortedByDescending { it.sentAt }

    @Synchronized
    fun get(name: String, user: String): HistoryEntry? = load().firstOrNull { it.name == name && it.user == user }

    /** Adds an entry. A count already kept under the same ERP record is replaced, never listed twice. */
    @Synchronized
    fun add(entry: HistoryEntry) {
        val kept = (load().filterNot { it.name == entry.name && it.user == entry.user } + entry)
            .sortedByDescending { it.sentAt }
            .take(MAX_ENTRIES)
        save(kept)
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun load(): List<HistoryEntry> = runCatching {
        if (!file.exists()) emptyList() else gson.fromJson(file.readText(), Array<HistoryEntry>::class.java).toList()
    }.getOrDefault(emptyList())

    private fun save(entries: List<HistoryEntry>) {
        // Written to a temp file first so a crash mid-write cannot leave a half-written history.
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        temp.writeText(gson.toJson(entries))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }

    companion object {
        const val FILE_NAME = "audit_history.json"

        /** Older counts drop off, so the file cannot grow without end (each can hold thousands of tags). */
        const val MAX_ENTRIES = 50
    }
}
