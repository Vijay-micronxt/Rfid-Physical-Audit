package com.rfidsoftwares.controller.audit

import com.rfidsoftwares.data.frappe.AuditSnapshot
import com.rfidsoftwares.data.frappe.SnapshotItem
import com.rfidsoftwares.data.frappe.SnapshotTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditCompareTest {

    private fun snapshot(tags: List<SnapshotTag>, items: List<SnapshotItem>) =
        AuditSnapshot("Stores - A", "2026-11-21 16:00:00", tags, items)

    private val shirt = SnapshotItem("SHIRT", "Shirt", "Nos", tagQty = 3, stockQty = 4.0)
    private val shirtTags = listOf("AA01", "AA02", "AA03").map { SnapshotTag(it, "SHIRT") }

    @Test
    fun countsEveryExpectedTagThatWasRead() {
        val result = AuditCompare.compare(snapshot(shirtTags, listOf(shirt)), setOf("AA01", "AA02"))

        val row = result.items.single()
        assertEquals(2, row.scannedQty)
        assertEquals(3, row.siteQty)
        assertEquals(4.0, row.erpStockQty, 0.0)
        assertEquals(1, row.difference)
        assertEquals(listOf("AA01", "AA02"), row.scannedEpcs)
        assertEquals(listOf("AA03"), row.missingEpcs)
        assertTrue(result.unknownEpcs.isEmpty())
    }

    @Test
    fun everythingReadMeansNoDifference() {
        val row = AuditCompare.compare(snapshot(shirtTags, listOf(shirt)), setOf("AA01", "AA02", "AA03")).items.single()
        assertEquals(0, row.difference)
        assertTrue(row.missingEpcs.isEmpty())
    }

    @Test
    fun tagsNotInTheSnapshotAreUnknown() {
        val result = AuditCompare.compare(snapshot(shirtTags, listOf(shirt)), setOf("AA01", "ZZ99", "ZZ01"))
        assertEquals(listOf("ZZ01", "ZZ99"), result.unknownEpcs)
        assertEquals(1, result.items.single().scannedQty)
    }

    @Test
    fun aSetCountsOnlyWhenEveryPieceWasRead() {
        val suit = SnapshotItem("SUIT", "Suit", "Nos", tagQty = 2, stockQty = 2.0)
        val tags = listOf(
            SnapshotTag("J1", "SUIT", "SUIT-1", "SUIT-1-JKT"),
            SnapshotTag("T1", "SUIT", "SUIT-1", "SUIT-1-TRS"),
            SnapshotTag("J2", "SUIT", "SUIT-2", "SUIT-2-JKT"),
            SnapshotTag("T2", "SUIT", "SUIT-2", "SUIT-2-TRS"),
        )
        // Suit 1 complete, suit 2 has only its jacket.
        val row = AuditCompare.compare(snapshot(tags, listOf(suit)), setOf("J1", "T1", "J2")).items.single()

        assertEquals(1, row.scannedQty)
        assertEquals(1, row.difference)
        assertEquals(listOf("T2"), row.missingEpcs)
    }

    @Test
    fun codesAreComparedTrimmedAndUpperCase() {
        assertEquals("E200AB", AuditCompare.normalize("  e200ab "))
        val row = AuditCompare.compare(snapshot(listOf(SnapshotTag("E200AB", "SHIRT")), listOf(shirt.copy(tagQty = 1))), setOf("E200AB")).items.single()
        assertEquals(1, row.scannedQty)
    }

    @Test
    fun itemsWithSomethingMissingComeFirst() {
        val a = SnapshotItem("A", "Alpha", tagQty = 1)
        val b = SnapshotItem("B", "Bravo", tagQty = 1)
        val tags = listOf(SnapshotTag("A1", "A"), SnapshotTag("B1", "B"))
        val names = AuditCompare.compare(snapshot(tags, listOf(a, b)), setOf("A1")).items.map { it.itemName }
        assertEquals(listOf("Bravo", "Alpha"), names)
    }

    @Test
    fun anItemWithoutANameShowsItsCode() {
        val row = AuditCompare.compare(snapshot(emptyList(), listOf(SnapshotItem("X1", null, tagQty = 0))), emptySet()).items.single()
        assertEquals("X1", row.itemName)
    }
}
