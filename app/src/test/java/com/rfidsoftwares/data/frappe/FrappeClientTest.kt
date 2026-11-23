package com.rfidsoftwares.data.frappe

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class FrappeClientTest {

    private lateinit var server: MockWebServer
    private val client = FrappeClient()
    private val base get() = server.url("/").toString().trimEnd('/')
    private val session get() = FrappeSession(base, "u", "key1", "secret1")

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun page(totalTags: Int, tags: List<String>, items: String = """[{"item_code":"SHIRT","item_name":"Shirt","uom":"Nos","tag_qty":$totalTags,"stock_qty":3.0}]""") =
        MockResponse().setBody(
            """{"message":{"warehouse":"Stores - A","generated_at":"2026-11-21 16:00:00","total_tags":$totalTags,"tags":[${
                tags.joinToString(",") { """{"epc":"$it","item_code":"SHIRT","serial_no":null,"component":null}""" }
            }],"items":$items}}""",
        )

    private fun loginReply(user: String = "karthik@example.com") = MockResponse().setBody(
        """{"message":{"user":"$user","full_name":"Karthik","api_key":"key-abc","api_secret":"secret-xyz","stations":["Stock Audit"]}}""",
    )

    private fun failure(kind: FrappeException.Kind, block: () -> Unit): FrappeException {
        try {
            block()
        } catch (e: FrappeException) {
            assertEquals(kind, e.kind)
            return e
        }
        fail("expected a $kind failure")
        throw IllegalStateException()
    }

    // ---------------------------------------------------------------- login

    @Test
    fun loginReturnsTheApiKeyAndSecretFromTheServer() {
        server.enqueue(loginReply())

        val session = client.login(base, " karthik@example.com ", "secret")

        assertEquals("key-abc", session.apiKey)
        assertEquals("secret-xyz", session.apiSecret)
        assertEquals("karthik@example.com", session.user)
        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/api/method/rfid_retail.api.app_auth.login", sent.path)
        val body = sent.body.readUtf8()
        assertTrue(body.contains("\"usr\":\"karthik@example.com\"") && body.contains("\"pwd\":\"secret\""))
    }

    @Test
    fun theSecretNeverAppearsWhenASessionIsPrinted() {
        assertFalse(FrappeSession("http://h", "u", "key1", "secret1").toString().contains("secret1"))
    }

    @Test
    fun wrongPasswordIsOneSimpleSentence() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Invalid login credentials","exc_type":"AuthenticationError"}"""))
        assertEquals("Wrong username or password.", failure(FrappeException.Kind.BAD_CREDENTIALS) { client.login(base, "u", "p") }.message)
    }

    @Test
    fun otherRefusalsKeepTheServersReason() {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody(
                """{"exception":"frappe.exceptions.AuthenticationError: Log in with a personal account, not Administrator."}""",
            ),
        )
        assertEquals(
            "Log in with a personal account, not Administrator.",
            failure(FrappeException.Kind.BAD_CREDENTIALS) { client.login(base, "Administrator", "p") }.message,
        )
    }

    @Test
    fun aLockedAccountShowsHowLongToWait() {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody(
                """{"exception":"frappe.exceptions.SecurityException: Your account has been locked and will resume after 60 seconds"}""",
            ),
        )
        val e = failure(FrappeException.Kind.BAD_CREDENTIALS) { client.login(base, "u", "p") }
        assertTrue(e.message!!.contains("locked"))
    }

    @Test
    fun aServerWithoutTheAppLoginSaysSo() {
        server.enqueue(MockResponse().setResponseCode(417).setBody("""{"exception":"ValidationError: Failed to get method for command rfid_retail.api.app_auth.login"}"""))
        val e = failure(FrappeException.Kind.SERVER) { client.login(base, "u", "p") }
        assertTrue(e.message!!.contains("update the RFID Retail app"))
    }

    @Test
    fun tooManyAttemptsAsksToWait() {
        server.enqueue(MockResponse().setResponseCode(429))
        assertTrue(failure(FrappeException.Kind.SERVER) { client.login(base, "u", "p") }.message!!.contains("Wait a minute"))
    }

    @Test
    fun aReplyWithoutKeysIsRejected() {
        server.enqueue(MockResponse().setBody("""{"message":"Logged In"}"""))
        failure(FrappeException.Kind.SERVER) { client.login(base, "u", "p") }
    }

    // ---------------------------------------------------------------- logout

    @Test
    fun logoutCancelsTheSecretOnTheServerWithTheCurrentToken() {
        server.enqueue(MockResponse().setBody("""{"message":{"ok":true}}"""))

        client.logout(session)

        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/api/method/rfid_retail.api.app_auth.logout", sent.path)
        assertEquals("token key1:secret1", sent.getHeader("Authorization"))
    }

    @Test
    fun aSecretThatIsAlreadyDeadCountsAsLoggedOut() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        client.logout(session) // no exception: a later login already replaced it
    }

    @Test
    fun logoutReportsAServerThatIsDown() {
        val dead = FrappeSession(base, "u", "k", "s")
        server.shutdown()
        failure(FrappeException.Kind.NETWORK) { client.logout(dead) }
    }

    // ---------------------------------------------------------------- snapshot

    @Test
    fun snapshotPagesAreJoinedAndSendTheToken() {
        server.enqueue(page(3, listOf("A1", "A2")))
        server.enqueue(page(3, listOf("A3")))

        val snapshot = client.fetchSnapshot(session, "Stores - A", pageLength = 2)

        assertEquals(listOf("A1", "A2", "A3"), snapshot.tags.map { it.epc })
        assertEquals("Shirt", snapshot.items.single().itemName)
        assertEquals("2026-11-21 16:00:00", snapshot.generatedAt)
        val first = server.takeRequest()
        assertEquals("token key1:secret1", first.getHeader("Authorization"))
        assertNull(first.getHeader("Cookie"))
        assertEquals("GET", first.method)
        assertTrue(first.path!!.contains("start=0") && first.path!!.contains("page_length=2"))
        assertTrue(server.takeRequest().path!!.contains("start=2"))
    }

    @Test
    fun aKeyTheServerNoLongerAcceptsAsksForANewLogin() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        failure(FrappeException.Kind.SESSION_EXPIRED) { client.fetchSnapshot(session, "W") }
    }

    @Test
    fun aMissingStationRoleShowsTheServersReason() {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody(
                """{"exc_type":"PermissionError","exception":"frappe.exceptions.PermissionError: You are not allowed to use the Stock Audit station."}""",
            ),
        )
        val e = failure(FrappeException.Kind.FORBIDDEN) { client.fetchSnapshot(session, "W") }
        assertEquals("You are not allowed to use the Stock Audit station.", e.message)
    }

    @Test
    fun anEmptyWarehouseGivesAnEmptySnapshot() {
        server.enqueue(page(0, emptyList(), items = "[]"))
        val snapshot = client.fetchSnapshot(session, "W")
        assertTrue(snapshot.tags.isEmpty() && snapshot.items.isEmpty())
    }

    @Test
    fun anUnreachableServerIsANetworkFailure() {
        val dead = FrappeSession(base, "u", "k", "s")
        server.shutdown()
        failure(FrappeException.Kind.NETWORK) { client.fetchSnapshot(dead, "W") }
    }

    // ---------------------------------------------------------------- sending a count

    private fun push(id: String = "id-1") = AuditPush(
        clientId = id, warehouse = "Stores - A", downloadedAt = "2026-11-23 10:00:00.1", deviceInfo = "Seuic test",
        items = listOf(AuditPushItem("SHIRT", 3, 1, 2.0)),
        tags = listOf(AuditPushTag("AA1", "SHIRT", "Matched"), AuditPushTag("ZZ9", null, "Unknown")),
    )

    @Test
    fun aCountIsSentToTheErpWithTheTokenAndTheRightFields() {
        server.enqueue(MockResponse().setBody("""{"message":{"name":"PA-2026-00001","duplicate":false}}"""))

        val sent = client.pushAudit(session, push())

        assertEquals(PushedAudit("PA-2026-00001", false), sent)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/method/rfid_retail.api.physical_audit.push", request.path)
        assertEquals("token key1:secret1", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"client_id\":\"id-1\"") && body.contains("\"warehouse\":\"Stores - A\""))
        assertTrue(body.contains("\"system_qty\":3") && body.contains("\"scanned_qty\":1") && body.contains("\"erp_stock_qty\":2.0"))
        assertTrue(body.contains("\"result\":\"Matched\"") && body.contains("\"result\":\"Unknown\""))
        // An unknown tag has no item, and the field is left out rather than sent as null.
        assertFalse(body.contains("null"))
    }

    @Test
    fun aRepeatedSendIsReportedAsADuplicate() {
        server.enqueue(MockResponse().setBody("""{"message":{"name":"PA-2026-00001","duplicate":true}}"""))
        assertTrue(client.pushAudit(session, push()).duplicate)
    }

    @Test
    fun aRefusedCountShowsTheServersReason() {
        server.enqueue(MockResponse().setResponseCode(417).setBody("""{"exception":"frappe.exceptions.ValidationError: Item not found: NO-SUCH-ITEM"}"""))
        assertEquals("Item not found: NO-SUCH-ITEM", failure(FrappeException.Kind.SERVER) { client.pushAudit(session, push()) }.message)

        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"exception":"frappe.exceptions.PermissionError: You are not allowed to use the Stock Audit station."}"""))
        failure(FrappeException.Kind.FORBIDDEN) { client.pushAudit(session, push()) }

        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        failure(FrappeException.Kind.SESSION_EXPIRED) { client.pushAudit(session, push()) }
    }

    @Test
    fun aReplyWithoutARecordNumberIsRejected() {
        server.enqueue(MockResponse().setBody("""{"message":{"ok":true}}"""))
        failure(FrappeException.Kind.SERVER) { client.pushAudit(session, push()) }
    }

    @Test
    fun sendingToAnUnreachableServerIsANetworkFailure() {
        val dead = FrappeSession(base, "u", "k", "s")
        server.shutdown()
        failure(FrappeException.Kind.NETWORK) { client.pushAudit(dead, push()) }
    }

    // ---------------------------------------------------------------- warehouse list

    @Test
    fun warehousesComeBackWithTheirTagCounts() {
        server.enqueue(MockResponse().setBody("""{"message":[{"name":"Stores - A","tags":2},{"name":"Finished Goods - A","tags":0}]}"""))

        val list = client.fetchWarehouses(session)

        assertEquals(listOf(WarehouseOption("Stores - A", 2), WarehouseOption("Finished Goods - A", 0)), list)
        val sent = server.takeRequest()
        assertEquals("/api/method/rfid_retail.api.catalog.audit_warehouses", sent.path)
        assertEquals("token key1:secret1", sent.getHeader("Authorization"))
    }

    @Test
    fun warehouseRowsWithoutANameAreSkipped() {
        server.enqueue(MockResponse().setBody("""{"message":[{"name":"","tags":1},{"tags":3},{"name":"Stores - A"}]}"""))
        assertEquals(listOf(WarehouseOption("Stores - A", 0)), client.fetchWarehouses(session))
    }

    @Test
    fun anEmptyWarehouseListIsEmpty() {
        server.enqueue(MockResponse().setBody("""{"message":[]}"""))
        assertTrue(client.fetchWarehouses(session).isEmpty())
    }

    @Test
    fun warehousesFollowTheSameAccessRulesAsTheSnapshot() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        failure(FrappeException.Kind.SESSION_EXPIRED) { client.fetchWarehouses(session) }

        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"exception":"frappe.exceptions.PermissionError: You are not allowed to use the Stock Audit station."}"""))
        assertTrue(failure(FrappeException.Kind.FORBIDDEN) { client.fetchWarehouses(session) }.message!!.contains("Stock Audit"))

        server.enqueue(MockResponse().setBody("""{"message":{"not":"a list"}}"""))
        failure(FrappeException.Kind.SERVER) { client.fetchWarehouses(session) }
    }

    // ---------------------------------------------------------------- address and ping

    @Test
    fun pingAcceptsAnErpServer() {
        server.enqueue(MockResponse().setBody("""{"message":"pong"}"""))
        client.ping(base)
        assertEquals("/api/method/ping", server.takeRequest().path)
    }

    @Test
    fun pingRejectsAServerThatIsNotErpNext() {
        server.enqueue(MockResponse().setBody("<html>hello</html>"))
        failure(FrappeException.Kind.SERVER) { client.ping(base) }
    }

    @Test
    fun pingReportsAnUnreachableServer() {
        val dead = base
        server.shutdown()
        failure(FrappeException.Kind.NETWORK) { client.ping(dead) }
    }

    @Test
    fun serverAddressesAreTidied() {
        assertEquals("http://192.168.1.5:8088", FrappeClient.normalizeBaseUrl("192.168.1.5:8088/"))
        assertEquals("https://erp.example.com", FrappeClient.normalizeBaseUrl(" https://erp.example.com/ "))
        failure(FrappeException.Kind.SERVER) { FrappeClient.normalizeBaseUrl("  ") }
    }
}
