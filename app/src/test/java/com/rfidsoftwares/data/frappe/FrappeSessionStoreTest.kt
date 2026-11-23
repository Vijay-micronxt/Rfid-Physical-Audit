package com.rfidsoftwares.data.frappe

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Stands in for the Android Keystore, which Robolectric does not provide. */
private class FakeCipher(var readable: Boolean = true) : SecretCipher {
    override fun encrypt(plain: String): String = "enc:" + plain.reversed()
    override fun decrypt(token: String): String? = if (readable && token.startsWith("enc:")) token.removePrefix("enc:").reversed() else null
}

@RunWith(RobolectricTestRunner::class)
// A plain Application: the app's own one starts WorkManager and the database, which this test does not need.
@Config(sdk = [28], application = Application::class)
class FrappeSessionStoreTest {

    private lateinit var app: Application
    private val cipher = FakeCipher()
    private lateinit var store: FrappeSessionStore
    private val session = FrappeSession("http://192.168.0.103:8088", "karthik@example.com", "key-abc", "secret-xyz")

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("frappe_audit_session", 0).edit().clear().commit()
        File(app.filesDir, "audit_snapshot.json").delete()
        store = FrappeSessionStore(app, cipher)
    }

    private fun snapshot() = AuditSnapshot(
        "Stores - A", "2026-11-21 16:00:00",
        listOf(SnapshotTag("A1", "SHIRT")), listOf(SnapshotItem("SHIRT", "Shirt", tagQty = 1)),
    )

    @Test
    fun noOneIsLoggedInAtFirst() {
        assertNull(store.session())
    }

    @Test
    fun aSavedLoginComesBackWithItsSecret() {
        store.saveSession(session)
        assertEquals(session, FrappeSessionStore(app, cipher).session())
    }

    @Test
    fun theSecretIsNotWrittenToStorageAsPlainText() {
        store.saveSession(session)
        val stored = app.getSharedPreferences("frappe_audit_session", 0).all.values.joinToString("|")
        assertFalse(stored.contains("secret-xyz"))
        assertTrue(stored.contains("key-abc"))
    }

    @Test
    fun aSecretThatCannotBeReadMeansLoggedOut() {
        store.saveSession(session)
        cipher.readable = false // for example the Keystore key was lost after a restore
        assertNull(store.session())
        cipher.readable = true
        assertNull(store.session()) // and the unusable key was dropped, not kept
    }

    @Test
    fun clearingCredentialsKeepsTheAddressAndUserForTheNextLogin() {
        store.saveSession(session)
        store.clearCredentials()
        assertNull(store.session())
        assertEquals("http://192.168.0.103:8088", store.lastBaseUrl())
        assertEquals("karthik@example.com", store.lastUser())
    }

    @Test
    fun logoutAlsoDeletesTheDownloadedData() {
        store.saveSession(session)
        store.saveSnapshot(snapshot())
        assertNotNull(store.loadSnapshot())

        store.logout()

        assertNull(store.session())
        assertNull(store.loadSnapshot())
    }

    @Test
    fun theSnapshotSurvivesARestart() {
        store.saveSnapshot(snapshot())
        val loaded = FrappeSessionStore(app, cipher).loadSnapshot()
        assertEquals("A1", loaded?.tags?.single()?.epc)
        assertEquals("Shirt", loaded?.items?.single()?.itemName)
    }

    @Test
    fun theWarehouseListIsKeptInOrderAndClearedOnLogout() {
        val list = listOf(WarehouseOption("Stores - A", 2), WarehouseOption("Finished Goods - A", 0))
        assertTrue(store.savedWarehouses().isEmpty())

        store.saveWarehouses(list)
        assertEquals(list, FrappeSessionStore(app, cipher).savedWarehouses())

        store.logout()
        assertTrue(store.savedWarehouses().isEmpty())
    }

    @Test
    fun theServerAddressCanBeSetBeforeAnyLogin() {
        store.saveBaseUrl("http://10.0.0.5:8088")
        assertEquals("http://10.0.0.5:8088", store.lastBaseUrl())
        assertNull(store.session())
    }
}
