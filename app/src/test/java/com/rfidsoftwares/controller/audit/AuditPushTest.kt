package com.rfidsoftwares.controller.audit

import com.rfidsoftwares.data.frappe.AuditPushTag
import com.rfidsoftwares.data.frappe.AuditSnapshot
import com.rfidsoftwares.data.frappe.SnapshotItem
import com.rfidsoftwares.data.frappe.SnapshotTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuditPushTest {

    private val snapshot = AuditSnapshot(
        "Stores - A", "2026-11-23 10:00:00.5",
        listOf(SnapshotTag("A1", "SHIRT"), SnapshotTag("A2", "SHIRT"), SnapshotTag("B1", "TROUSER")),
        listOf(
            SnapshotItem("SHIRT", "Shirt", "Nos", tagQty = 2, stockQty = 3.0),
            SnapshotItem("TROUSER", "Trouser", "Nos", tagQty = 1, stockQty = 1.0),
        ),
    )

    private fun push(scanned: Set<String>, id: String = "id-1") =
        AuditPushBuilder.build(snapshot, AuditCompare.compare(snapshot, scanned), id, "Seuic test")

    @Test
    fun theWholeWarehouseIsSentWithEachItemsNumbers() {
        val p = push(setOf("A1", "ZZ9"))

        assertEquals("Stores - A", p.warehouse)
        assertEquals("2026-11-23 10:00:00.5", p.downloadedAt)
        assertEquals("id-1", p.clientId)
        // An item with nothing scanned is still listed, so the difference shows.
        val rows = p.items.associateBy { it.itemCode }
        assertEquals(2, rows.getValue("SHIRT").systemQty)
        assertEquals(1, rows.getValue("SHIRT").scannedQty)
        assertEquals(3.0, rows.getValue("SHIRT").erpStockQty, 0.0)
        assertEquals(0, rows.getValue("TROUSER").scannedQty)
    }

    @Test
    fun everyTagIsMatchedMissingOrUnknown() {
        val tags = push(setOf("A1", "ZZ9")).tags

        assertEquals(
            setOf(
                AuditPushTag("A1", "SHIRT", "Matched"),
                AuditPushTag("A2", "SHIRT", "Missing"),
                AuditPushTag("B1", "TROUSER", "Missing"),
                AuditPushTag("ZZ9", null, "Unknown"),
            ),
            tags.toSet(),
        )
        assertEquals(4, tags.size)
    }

    @Test
    fun anUnknownTagHasNoItem() {
        assertNull(push(setOf("ZZ9")).tags.single { it.result == "Unknown" }.itemCode)
    }

    @Test
    fun theFingerprintIgnoresThePushIdButNotTheContent() {
        val a = AuditPushBuilder.fingerprint(push(setOf("A1"), "id-1"))
        assertEquals(a, AuditPushBuilder.fingerprint(push(setOf("A1"), "id-2")))
        assertNotEquals(a, AuditPushBuilder.fingerprint(push(setOf("A1", "A2"))))
    }

    @Test
    fun aRetryReusesTheIdAndANewCountGetsANewOne() {
        var n = 0
        val ids = PushIds { "id-${++n}" }

        val first = ids.idFor(111)
        assertEquals(first, ids.idFor(111)) // failed send, same content: same id
        assertNotEquals(first, ids.idFor(222)) // the count changed: a new count
        val changed = ids.idFor(222)
        ids.sent()
        assertNotEquals(changed, ids.idFor(222)) // after a successful send the next push is a new record
    }
}
