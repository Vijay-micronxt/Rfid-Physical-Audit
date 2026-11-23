package com.rfidsoftwares.data.frappe

import com.google.gson.annotations.SerializedName

/** What a warehouse is expected to hold, as returned by rfid_retail.api.catalog.audit_snapshot. */
data class AuditSnapshot(
    val warehouse: String,
    @SerializedName("generated_at") val generatedAt: String,
    val tags: List<SnapshotTag>,
    val items: List<SnapshotItem>,
)

data class SnapshotTag(
    val epc: String,
    @SerializedName("item_code") val itemCode: String,
    @SerializedName("serial_no") val serialNo: String? = null,
    val component: String? = null,
)

data class SnapshotItem(
    @SerializedName("item_code") val itemCode: String,
    @SerializedName("item_name") val itemName: String? = null,
    val uom: String? = null,
    /** Units the RFID tags stand for (a set is one unit). */
    @SerializedName("tag_qty") val tagQty: Int = 0,
    /** ERPNext's own stock quantity for the item in this warehouse. */
    @SerializedName("stock_qty") val stockQty: Double = 0.0,
)

/** One entry of the warehouse dropdown. `tags` is how many tags stand for stock in it (0 = none tagged yet). */
data class WarehouseOption(val name: String, val tags: Int = 0) {
    /** What the dropdown shows. */
    val label: String get() = if (tags > 0) "$name ($tags ${if (tags == 1) "tag" else "tags"})" else name
}

/**
 * A logged-in ERP user: the server and the API key and secret the login returned. Every later call
 * sends them as `Authorization: token <key>:<secret>`.
 */
data class FrappeSession(
    val baseUrl: String,
    val user: String,
    val apiKey: String,
    val apiSecret: String,
) {
    // Keeps the secret out of logs and crash reports.
    override fun toString(): String = "FrappeSession(baseUrl=$baseUrl, user=$user)"
}
