package com.rfidsoftwares.controller.audit

import com.rfidsoftwares.data.frappe.AuditSnapshot

/** One item's row on the audit screen. */
data class ItemResult(
    val itemCode: String,
    val itemName: String,
    /** Units the backend expects in the warehouse (tag_qty). */
    val siteQty: Int,
    /** ERPNext's own stock quantity, for reference. */
    val erpStockQty: Double,
    /** Units physically seen: every tag of the unit was read. */
    val scannedQty: Int,
    val scannedEpcs: List<String>,
    val missingEpcs: List<String>,
) {
    /** Expected minus seen: positive means some are not found yet. */
    val difference: Int get() = siteQty - scannedQty
}

data class AuditResult(
    val items: List<ItemResult>,
    /** Tags that were read but are not expected in this warehouse. */
    val unknownEpcs: List<String>,
) {
    val scannedUnits: Int get() = items.sumOf { it.scannedQty }
    val expectedUnits: Int get() = items.sumOf { it.siteQty }
}

/** Compares what the reader saw with the snapshot. Runs on the phone; nothing is sent anywhere. */
object AuditCompare {

    /** Tag codes are compared the way the server stores them: trimmed and upper case. */
    fun normalize(epc: String): String = epc.trim().uppercase()

    fun compare(snapshot: AuditSnapshot, scanned: Set<String>): AuditResult {
        val expected = snapshot.tags.map { normalize(it.epc) }.toHashSet()
        val tagsByItem = snapshot.tags.groupBy { it.itemCode }

        val rows = snapshot.items.map { item ->
            val tags = tagsByItem[item.itemCode].orEmpty()
            // A unit is the serial number (a set has several tags), or the tag itself.
            val units = tags.groupBy { it.serialNo ?: normalize(it.epc) }
            val seenUnits = units.values.count { unit -> unit.all { normalize(it.epc) in scanned } }
            ItemResult(
                itemCode = item.itemCode,
                itemName = item.itemName?.takeIf { it.isNotBlank() } ?: item.itemCode,
                siteQty = item.tagQty,
                erpStockQty = item.stockQty,
                scannedQty = seenUnits,
                scannedEpcs = tags.map { normalize(it.epc) }.filter { it in scanned }.sorted(),
                missingEpcs = tags.map { normalize(it.epc) }.filter { it !in scanned }.sorted(),
            )
        }

        return AuditResult(
            // Items with something still missing come first.
            items = rows.sortedWith(compareByDescending<ItemResult> { it.difference != 0 }.thenBy { it.itemName.lowercase() }),
            unknownEpcs = scanned.filter { it !in expected }.sorted(),
        )
    }
}
