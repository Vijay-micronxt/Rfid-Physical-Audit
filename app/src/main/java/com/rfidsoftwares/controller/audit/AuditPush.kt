package com.rfidsoftwares.controller.audit

import com.rfidsoftwares.data.frappe.AuditPush
import com.rfidsoftwares.data.frappe.AuditPushItem
import com.rfidsoftwares.data.frappe.AuditPushTag
import com.rfidsoftwares.data.frappe.AuditSnapshot
import java.util.UUID

object AuditPushBuilder {

    /** Builds what the phone saw: every item of the downloaded warehouse with its numbers, and every tag. */
    fun build(snapshot: AuditSnapshot, result: AuditResult, clientId: String, deviceInfo: String): AuditPush {
        val items = result.items.map {
            AuditPushItem(it.itemCode, it.siteQty, it.scannedQty, it.erpStockQty)
        }
        val tags = buildList {
            result.items.forEach { item ->
                item.scannedEpcs.forEach { add(AuditPushTag(it, item.itemCode, "Matched")) }
                item.missingEpcs.forEach { add(AuditPushTag(it, item.itemCode, "Missing")) }
            }
            result.unknownEpcs.forEach { add(AuditPushTag(it, null, "Unknown")) }
        }
        return AuditPush(clientId, snapshot.warehouse, snapshot.generatedAt, deviceInfo, items, tags)
    }

    /** Same content gives the same number, whatever the push ID. Used to tell "retry" from "a new count". */
    fun fingerprint(push: AuditPush): Int =
        listOf(push.warehouse, push.downloadedAt, push.items, push.tags).hashCode()
}

/**
 * Gives each push an ID. If a send fails (the reply may have been lost after the server saved it), pressing Send
 * again with the same content reuses the ID, so the server answers "duplicate" instead of making a second record.
 * After a successful send the next push is a new count and gets a new ID.
 */
class PushIds(private val newId: () -> String = { UUID.randomUUID().toString() }) {
    private var pending: Pair<Int, String>? = null

    @Synchronized
    fun idFor(fingerprint: Int): String {
        pending?.takeIf { it.first == fingerprint }?.let { return it.second }
        return newId().also { pending = fingerprint to it }
    }

    @Synchronized
    fun sent() {
        pending = null
    }
}
