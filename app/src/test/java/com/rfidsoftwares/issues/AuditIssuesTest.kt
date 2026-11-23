package com.rfidsoftwares.issues

import com.rfidsoftwares.data.frappe.AuditSnapshot
import com.rfidsoftwares.data.frappe.SnapshotItem
import com.rfidsoftwares.data.frappe.SnapshotTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditIssuesTest {

    private fun snapshot(warehouse: String = "Stores - A") = AuditSnapshot(
        warehouse, "2026-11-24 10:00:00.5",
        listOf(SnapshotTag("A1", "SHIRT"), SnapshotTag("A2", "SHIRT"), SnapshotTag("B1", "TROUSER")),
        listOf(SnapshotItem("SHIRT", "Shirt", tagQty = 2), SnapshotItem("TROUSER", "Trouser", tagQty = 1)),
    )

    @Test
    fun aSavedWarehouseIsReportedWithItsItemAndTagCounts() {
        val note = AuditIssues.cached(snapshot(), now = 1000L)

        assertEquals("info", note.severity)
        assertEquals(IssueCategories.SYNC, note.category)
        assertTrue(note.message.contains("Stores - A"))
        assertTrue(note.message.contains("2 item(s)") && note.message.contains("3 tag(s)"))
        assertEquals("Downloaded 2026-11-24 10:00:00.5 (server time)", note.detail)
        assertEquals(1000L, note.createdAt)
        assertTrue(note.active)
    }

    @Test
    fun eachWarehouseHasOneNoteThatANewerDownloadReplaces() {
        // Same ID for the same warehouse: the page keeps the latest state, not a growing list.
        assertEquals(AuditIssues.cached(snapshot(), 1).issueId, AuditIssues.cached(snapshot(), 2).issueId)
        assertNotEquals(AuditIssues.cached(snapshot("Stores - A"), 1).issueId, AuditIssues.cached(snapshot("Goods - A"), 1).issueId)
    }

    @Test
    fun aFailureAndTheSavedNoteOfTheSameWarehouseAreDifferentNotes() {
        assertNotEquals(AuditIssues.cachedId("Stores - A"), AuditIssues.failedId("Stores - A"))
    }

    @Test
    fun aProblemIsAnErrorWithTheGivenReason() {
        val note = AuditIssues.problem(AuditIssues.failedId("Stores - A"), IssueCategories.SYNC, "Could not load Stores - A: no connection", null, 5L)

        assertEquals("error", note.severity)
        assertEquals(AuditIssues.failedId("Stores - A"), note.issueId)
        assertEquals("Could not load Stores - A: no connection", note.message)
        assertNull(note.detail)
        assertTrue(note.active)
    }

    @Test
    fun theFixedNotesHaveDifferentIds() {
        val ids = listOf(AuditIssues.WAREHOUSES_ID, AuditIssues.SEND_ID, AuditIssues.READER_ID)
        assertEquals(ids.size, ids.toSet().size)
    }
}
