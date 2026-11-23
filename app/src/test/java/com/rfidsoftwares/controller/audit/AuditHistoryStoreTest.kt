package com.rfidsoftwares.controller.audit

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

// A plain Application: the app's own one starts WorkManager and the database, which this test does not need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AuditHistoryStoreTest {

    private lateinit var app: Application
    private lateinit var store: AuditHistoryStore

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        File(app.filesDir, AuditHistoryStore.FILE_NAME).delete()
        store = AuditHistoryStore(app)
    }

    private fun entry(name: String, at: Long, user: String = "u1") = HistoryEntry(
        name = name, user = user, warehouse = "Stores - A", downloadedAt = "2026-11-24 10:00:00", sentAt = at,
        items = listOf(
            ItemResult("SHIRT", "Shirt", siteQty = 3, erpStockQty = 4.0, scannedQty = 2,
                scannedEpcs = listOf("A1", "A2"), missingEpcs = listOf("A3")),
        ),
        unknownEpcs = listOf("ZZ9"),
    )

    @Test
    fun nothingIsKeptAtFirst() {
        assertTrue(store.list("u1").isEmpty())
        assertNull(store.get("PA-1", "u1"))
    }

    @Test
    fun countsComeBackNewestFirst() {
        store.add(entry("PA-1", 100))
        store.add(entry("PA-3", 300))
        store.add(entry("PA-2", 200))
        assertEquals(listOf("PA-3", "PA-2", "PA-1"), AuditHistoryStore(app).list("u1").map { it.name })
    }

    @Test
    fun aCountKeepsEverythingTheScanningScreenShowed() {
        store.add(entry("PA-1", 100))

        val back = AuditHistoryStore(app).get("PA-1", "u1")!!

        assertEquals("Stores - A", back.warehouse)
        assertEquals(2, back.scannedUnits)
        assertEquals(3, back.expectedUnits)
        val row = back.toResult().items.single()
        assertEquals(listOf("A1", "A2"), row.scannedEpcs)
        assertEquals(listOf("A3"), row.missingEpcs)
        assertEquals(4.0, row.erpStockQty, 0.0)
        assertEquals(1, row.difference)
        assertEquals(listOf("ZZ9"), back.toResult().unknownEpcs)
    }

    @Test
    fun theSameRecordIsNeverListedTwice() {
        store.add(entry("PA-1", 100))
        store.add(entry("PA-1", 500))
        val all = store.list("u1")
        assertEquals(1, all.size)
        assertEquals(500L, all.single().sentAt)
    }

    @Test
    fun aUserOnlySeesTheirOwnCounts() {
        store.add(entry("PA-1", 100, user = "u1"))
        store.add(entry("PA-2", 200, user = "u2"))
        assertEquals(listOf("PA-1"), store.list("u1").map { it.name })
        assertEquals(listOf("PA-2"), store.list("u2").map { it.name })
        assertNull(store.get("PA-2", "u1"))
    }

    @Test
    fun oldCountsDropOffSoTheFileCannotGrowForever() {
        for (i in 1..(AuditHistoryStore.MAX_ENTRIES + 5)) store.add(entry("PA-$i", i.toLong()))
        val kept = store.list("u1")
        assertEquals(AuditHistoryStore.MAX_ENTRIES, kept.size)
        assertEquals("PA-${AuditHistoryStore.MAX_ENTRIES + 5}", kept.first().name)
        assertNull(store.get("PA-1", "u1"))
    }

    @Test
    fun clearingDeletesEverything() {
        store.add(entry("PA-1", 100))
        store.clear()
        assertTrue(store.list("u1").isEmpty())
    }

    @Test
    fun aDamagedFileIsTreatedAsEmptyNotACrash() {
        File(app.filesDir, AuditHistoryStore.FILE_NAME).writeText("{ not json")
        assertTrue(store.list("u1").isEmpty())
        store.add(entry("PA-1", 100)) // and it recovers
        assertEquals(1, store.list("u1").size)
    }
}
