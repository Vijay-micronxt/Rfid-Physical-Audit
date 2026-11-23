package com.rfidsoftwares.data.frappe

import com.google.gson.annotations.SerializedName

/** One count, as it is sent to the ERP (`rfid_retail.api.physical_audit.push`). */
data class AuditPush(
    @SerializedName("client_id") val clientId: String,
    val warehouse: String,
    @SerializedName("downloaded_at") val downloadedAt: String,
    @SerializedName("device_info") val deviceInfo: String,
    val items: List<AuditPushItem>,
    val tags: List<AuditPushTag>,
)

data class AuditPushItem(
    @SerializedName("item_code") val itemCode: String,
    @SerializedName("system_qty") val systemQty: Int,
    @SerializedName("scanned_qty") val scannedQty: Int,
    @SerializedName("erp_stock_qty") val erpStockQty: Double,
)

/** `result` is Matched (expected and read), Missing (expected, not read) or Unknown (read, not expected here). */
data class AuditPushTag(
    val epc: String,
    @SerializedName("item_code") val itemCode: String?,
    val result: String,
)

/** What the server answers: the new record's number, and whether it was already there from an earlier try. */
data class PushedAudit(val name: String, val duplicate: Boolean)
