package com.rfidsoftwares.ui.screens

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.widget.ArrayAdapter
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.rfidsoftwares.R
import com.rfidsoftwares.common.prefs.UhfConfigPrefs
import com.rfidsoftwares.common.sound.SoundBeeper
import com.rfidsoftwares.controller.audit.AuditCompare
import com.rfidsoftwares.controller.audit.AuditHistoryStore
import com.rfidsoftwares.controller.audit.AuditPushBuilder
import com.rfidsoftwares.controller.audit.AuditResult
import com.rfidsoftwares.controller.audit.HistoryEntry
import com.rfidsoftwares.controller.audit.PushIds
import com.rfidsoftwares.controller.audit.WarehouseChoice
import com.rfidsoftwares.data.frappe.AuditSnapshot
import com.rfidsoftwares.data.frappe.WarehouseOption
import com.rfidsoftwares.data.frappe.FrappeClient
import com.rfidsoftwares.data.frappe.FrappeException
import com.rfidsoftwares.data.frappe.FrappeSessionStore
import com.rfidsoftwares.rfid.driver.RfidDriverFactory
import com.rfidsoftwares.rfid.driver.RfidReaderDriver
import com.rfidsoftwares.ui.base.BaseScreenFragment
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Physical audit: pick a warehouse (its data downloads by itself), scan with the reader and compare on
 * the phone. The ERP login is the app's own login screen. Nothing is sent to the ERP until you press
 * Send to ERP, which creates a draft Physical Audit there (no stock changes).
 */
class PhysicalAuditFragment : BaseScreenFragment() {

    override fun screenTitle(): String = "Physical Audit"
    // Scanning and comparing work without a connection, so the shared "you are offline" panel stays off.
    override fun allowOfflinePanel(): Boolean = false

    private lateinit var store: FrappeSessionStore
    private val client = FrappeClient()
    private var io = Executors.newSingleThreadExecutor()
    private var scanExec = Executors.newSingleThreadExecutor()

    private var driver: RfidReaderDriver? = null
    private var driverReady = false
    private val scanning = AtomicBoolean(false)

    private var snapshot: AuditSnapshot? = null
    private val scanned = LinkedHashSet<String>()
    private var result: AuditResult? = null

    private lateinit var userText: TextView
    private lateinit var warehouseInput: MaterialAutoCompleteTextView
    private var warehouseOptions: List<WarehouseOption> = emptyList()
    private var selectedWarehouse: String? = null
    // Each pick gets a number, so a slow download for an earlier pick cannot overwrite a later one.
    @Volatile private var downloadToken = 0
    private val autoDownloaded = HashSet<String>()
    private lateinit var downloadStatus: TextView
    private lateinit var scanButton: MaterialButton
    private lateinit var sendButton: MaterialButton
    private val pushIds = PushIds()
    private lateinit var scanStatus: TextView
    private lateinit var summaryText: TextView
    private lateinit var unknownText: TextView
    private lateinit var adapter: AuditResultAdapter
    private lateinit var history: AuditHistoryStore

    override fun createBody(inflater: LayoutInflater, container: FrameLayout): View =
        inflater.inflate(R.layout.body_physical_audit, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        store = FrappeSessionStore(requireContext())
        if (io.isShutdown) io = Executors.newSingleThreadExecutor()
        if (scanExec.isShutdown) scanExec = Executors.newSingleThreadExecutor()

        userText = view.findViewById(R.id.auditUserText)
        warehouseInput = view.findViewById(R.id.auditWarehouseInput)
        downloadStatus = view.findViewById(R.id.auditDownloadStatus)
        scanButton = view.findViewById(R.id.auditScanButton)
        sendButton = view.findViewById(R.id.auditSendButton)
        scanStatus = view.findViewById(R.id.auditScanStatus)
        summaryText = view.findViewById(R.id.auditSummary)
        unknownText = view.findViewById(R.id.auditUnknownText)

        history = AuditHistoryStore(requireContext())
        adapter = AuditResultAdapter { AuditDialogs.showItem(requireContext(), it) }
        view.findViewById<RecyclerView>(R.id.auditResultList).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@PhysicalAuditFragment.adapter
        }

        val session = store.session()
        if (session == null) {
            // Not logged in (for example the session ended in the background): the login screen is the way in.
            findNavController().navigate(R.id.action_global_erpLoginFragment)
            return
        }
        userText.text = "Logged in as ${session.user} · ${session.baseUrl}"

        scanButton.setOnClickListener { if (scanning.get()) stopScan() else startScan() }
        sendButton.setOnClickListener { onSendClicked() }
        view.findViewById<MaterialButton>(R.id.auditClearButton).setOnClickListener { clearScans() }
        unknownText.setOnClickListener { showUnknown() }
        // Choosing a warehouse fetches it straight away; choosing the same one again refreshes it.
        warehouseInput.setOnItemClickListener { parent, _, position, _ ->
            val label = parent.getItemAtPosition(position) as? String
            warehouseOptions.firstOrNull { it.label == label }?.let { onWarehousePicked(it.name) }
        }

        snapshot = store.loadSnapshot()
        snapshot?.let { downloadStatus.text = downloadedText(it) }
        // The saved list fills the dropdown at once (works offline); the server's list replaces it a moment later.
        showWarehouses(store.savedWarehouses(), prefer = snapshot?.warehouse ?: store.lastWarehouse())
        loadWarehouses(session)
        refreshResult()
    }

    // ---------------------------------------------------------------- warehouse dropdown

    /** Fills the dropdown and selects `prefer` if it is on the list, else the last used, else the busiest. */
    private fun showWarehouses(options: List<WarehouseOption>, prefer: String?) {
        warehouseOptions = options
        warehouseInput.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, options.map { it.label }),
        )
        val choice = WarehouseChoice.pick(options, prefer ?: selectedWarehouse)
        selectedWarehouse = choice?.name
        // false = do not filter the list by the text we just set, so the whole list stays available.
        warehouseInput.setText(choice?.label.orEmpty(), false)
        // The preselected warehouse is fetched too, unless its data is already on the phone or was tried this visit.
        val name = selectedWarehouse
        if (name != null && snapshot?.warehouse != name && autoDownloaded.add(name)) download(name)
    }

    /** A warehouse was chosen from the dropdown. */
    private fun onWarehousePicked(name: String) {
        selectedWarehouse = name
        if (snapshot?.warehouse != name) {
            // A different warehouse is a different count: the old one's data and reads do not belong to it.
            stopScan()
            synchronized(scanned) { scanned.clear() }
            snapshot = null
            refreshResult()
        }
        download(name)
    }

    private fun loadWarehouses(session: com.rfidsoftwares.data.frappe.FrappeSession) {
        io.execute {
            try {
                val fresh = client.fetchWarehouses(session)
                store.saveWarehouses(fresh)
                ui { showWarehouses(fresh, prefer = selectedWarehouse ?: store.lastWarehouse()) }
            } catch (e: FrappeException) {
                ui {
                    when {
                        e.kind == FrappeException.Kind.SESSION_EXPIRED -> sessionEnded()
                        // Offline with a saved list is fine; only say something when there is nothing to choose from.
                        warehouseOptions.isEmpty() -> downloadStatus.text = "Could not load the warehouses: ${e.message}"
                    }
                }
            }
        }
    }

    /** The server no longer accepts our key (regenerated, user disabled): back to the login screen. */
    private fun sessionEnded() {
        stopScan()
        store.clearCredentials()
        findNavController().navigate(R.id.action_global_erpLoginFragment)
    }

    // ---------------------------------------------------------------- download

    private fun download(warehouse: String) {
        val session = store.session() ?: return sessionEnded()
        val token = ++downloadToken
        downloadStatus.text = "Downloading $warehouse..."
        io.execute {
            try {
                val fresh = client.fetchSnapshot(session, warehouse)
                if (token != downloadToken) return@execute // another warehouse was picked meanwhile
                store.saveSnapshot(fresh)
                store.saveWarehouse(warehouse)
                ui {
                    if (token != downloadToken) return@ui
                    snapshot = fresh
                    downloadStatus.text = downloadedText(fresh)
                    refreshResult()
                }
            } catch (e: FrappeException) {
                ui {
                    if (token != downloadToken) return@ui
                    if (e.kind == FrappeException.Kind.SESSION_EXPIRED) {
                        sessionEnded()
                    } else {
                        downloadStatus.text = e.message
                    }
                }
            }
        }
    }

    private fun downloadedText(s: AuditSnapshot): String =
        "${s.warehouse}: ${s.tags.size} tags, ${s.items.size} items. Downloaded ${s.generatedAt} (server time)."

    // ---------------------------------------------------------------- scanning

    private fun startScan() {
        if (snapshot == null) {
            scanStatus.text = "Status: Wait for the warehouse to finish loading first."
            return
        }
        if (!scanning.compareAndSet(false, true)) return
        scanButton.text = "Stop scan"
        scanStatus.text = "Status: Scanning..."
        val appContext = requireContext().applicationContext
        scanExec.execute {
            try {
                val d = driver ?: RfidDriverFactory.createDriver(UhfConfigPrefs.load(appContext).driverId).also { driver = it }
                if (!driverReady) driverReady = runCatching { d.init(appContext) }.getOrDefault(false)
                if (!driverReady) {
                    scanning.set(false)
                    ui {
                        scanButton.text = "Start scan"
                        scanStatus.text = "Status: Reader init failed (${d.displayName}). Check the reader is on."
                    }
                    return@execute
                }
                val soundOn = UhfConfigPrefs.load(appContext).soundEnabled
                d.startInventory()
                var dirty = false
                var lastRefresh = 0L
                while (scanning.get()) {
                    for (event in d.readBufferedTagEvents()) {
                        val added = synchronized(scanned) { scanned.add(AuditCompare.normalize(event.epc)) }
                        if (added) {
                            dirty = true
                            if (soundOn) SoundBeeper.playBeep()
                        }
                    }
                    val now = System.currentTimeMillis()
                    if (dirty && now - lastRefresh > REFRESH_MS) {
                        dirty = false
                        lastRefresh = now
                        ui { refreshResult() }
                    }
                    Thread.sleep(100)
                }
                d.stopInventory()
                ui { refreshResult() }
            } catch (e: Exception) {
                scanning.set(false)
                ui {
                    scanButton.text = "Start scan"
                    scanStatus.text = "Status: Scan error (${e.message ?: e.javaClass.simpleName})"
                }
            }
        }
    }

    private fun stopScan() {
        if (!scanning.getAndSet(false)) return
        scanButton.text = "Start scan"
        scanStatus.text = "Status: Stopped. ${synchronized(scanned) { scanned.size }} tags read."
    }

    private fun clearScans() {
        synchronized(scanned) { scanned.clear() }
        scanStatus.text = "Status: Scans cleared."
        refreshResult()
    }

    // ---------------------------------------------------------------- send to the ERP

    private fun onSendClicked() {
        val snap = snapshot
        if (snap == null) {
            scanStatus.text = "Status: Pick a warehouse first."
            return
        }
        val seen = synchronized(scanned) { scanned.toSet() }
        if (seen.isEmpty()) {
            scanStatus.text = "Status: Scan something first."
            return
        }
        stopScan()
        val r = AuditCompare.compare(snap, seen)
        val notExpected = if (r.unknownEpcs.isEmpty()) "" else " and ${r.unknownEpcs.size} tag(s) not expected here"
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Send to ERP?")
            .setMessage(
                "${snap.warehouse}: ${r.scannedUnits} of ${r.expectedUnits} units scanned$notExpected.\n\n" +
                    "This creates a draft Physical Audit in the ERP. It does not change any stock.",
            )
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton("Send") { _, _ -> send(snap, r) }
            .show()
    }

    private fun send(snap: AuditSnapshot, r: AuditResult) {
        val session = store.session() ?: return sessionEnded()
        // The same count sent again after a failure keeps its ID, so the server cannot make two records of it.
        val draft = AuditPushBuilder.build(snap, r, clientId = "", deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL}")
        val push = draft.copy(clientId = pushIds.idFor(AuditPushBuilder.fingerprint(draft)))
        sendButton.isEnabled = false
        sendButton.text = "Sending..."
        scanStatus.text = "Status: Sending to the ERP..."
        io.execute {
            try {
                val sent = client.pushAudit(session, push)
                pushIds.sent()
                // Kept on the phone so the count can be looked at again in History.
                history.add(
                    HistoryEntry(sent.name, session.user, snap.warehouse, snap.generatedAt, System.currentTimeMillis(), r.items, r.unknownEpcs),
                )
                // Only the tags that were sent are cleared; anything read while it was sending stays for the next count.
                val sentCodes = r.items.flatMap { it.scannedEpcs }.toSet() + r.unknownEpcs
                ui {
                    synchronized(scanned) { scanned.removeAll(sentCodes) }
                    refreshResult()
                    sendButton.isEnabled = true
                    sendButton.text = "Send to ERP"
                    scanStatus.text = if (sent.duplicate) {
                        "Status: Already sent as ${sent.name}. Scans cleared."
                    } else {
                        "Status: Sent as ${sent.name} (draft in the ERP). Scans cleared. See Audit History on the main menu."
                    }
                }
            } catch (e: FrappeException) {
                ui {
                    sendButton.isEnabled = true
                    sendButton.text = "Send to ERP"
                    if (e.kind == FrappeException.Kind.SESSION_EXPIRED) sessionEnded() else scanStatus.text = "Status: Not sent. ${e.message}"
                }
            }
        }
    }

    // ---------------------------------------------------------------- result

    private fun refreshResult() {
        val snap = snapshot
        if (snap == null) {
            result = null
            adapter.submit(emptyList())
            summaryText.text = "Scanned 0 of 0"
            unknownText.visibility = View.GONE
            return
        }
        val seen = synchronized(scanned) { scanned.toSet() }
        val r = AuditCompare.compare(snap, seen)
        result = r
        adapter.submit(r.items)
        summaryText.text = "Scanned ${r.scannedUnits} of ${r.expectedUnits}"
        if (r.unknownEpcs.isEmpty()) {
            unknownText.visibility = View.GONE
        } else {
            unknownText.visibility = View.VISIBLE
            unknownText.text = "${r.unknownEpcs.size} tag(s) read that are not in this warehouse. Tap to see."
        }
    }

    private fun showUnknown() {
        val unknown = result?.unknownEpcs ?: return
        AuditDialogs.showUnknown(requireContext(), unknown)
    }

    /** Runs on the main thread, and only while the screen is still there. */
    private fun ui(block: () -> Unit) {
        view?.post { if (isAdded && view != null) block() }
    }

    override fun onDestroyView() {
        scanning.set(false)
        runCatching {
            driver?.stopInventory()
            driver?.release()
        }
        driver = null
        driverReady = false
        io.shutdownNow()
        scanExec.shutdownNow()
        super.onDestroyView()
    }

    private companion object {
        const val REFRESH_MS = 400L
    }
}
