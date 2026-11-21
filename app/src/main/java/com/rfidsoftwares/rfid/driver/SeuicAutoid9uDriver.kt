/*
 * Copyright (c) 2026 RFID Software India Private Limited (https://rfidsoftwares.com)
 * OpenRFID Handheld Android - Universal Multi-Vendor Handheld RFID Middleware & Suite
 */

package com.rfidsoftwares.rfid.driver

import android.content.Context
import com.rfidsoftwares.rfid.ReaderDiagnosticsSummary
import com.rfidsoftwares.rfid.TagEvent
import com.rfidsoftwares.rfid.TagSource
import com.seuic.uhf.EPC
import com.seuic.uhf.UHFService
import java.util.Locale

/**
 * Driver implementation for Seuic AUTOID9U handheld RFID readers.
 *
 * Uses reflective invocations on `com.seuic.uhf.UHFService` so the app compiles portably
 * without forcing Seuic SDK JARs into every build target.
 */
class SeuicAutoid9uDriver : RfidReaderDriver {

    override val driverId: String = "seuic_autoid9u"
    override val displayName: String = "Seuic AUTOID9U"

    private var uhfService: Any? = null
    private var isScanning = false
    private val lastSeenCounts = HashMap<String, Int>()

    override fun init(context: Context): Boolean {
        return try {
            val serviceClass = Class.forName("com.seuic.uhf.UHFService")
            val getInstanceMethod = serviceClass.getMethod("getInstance")
            uhfService = getInstanceMethod.invoke(null)
            
            val openMethod = serviceClass.methods.firstOrNull { it.name == "open" }
            val ok = if (openMethod != null) {
                if (openMethod.parameterCount == 1) openMethod.invoke(uhfService, context) as? Boolean ?: true
                else openMethod.invoke(uhfService) as? Boolean ?: true
            } else true
            if (ok) applyAccessFriendlyInventoryMode()
            ok
        } catch (_: Exception) {
            uhfService = null
            false
        }
    }

    override fun release(): Boolean {
        val s = uhfService ?: return false
        return try {
            restoreDefaultInventoryMode()
            val closeMethod = s.javaClass.getMethod("close")
            closeMethod.invoke(s)
            uhfService = null
            isScanning = false
            true
        } catch (_: Exception) {
            uhfService = null
            false
        }
    }

    /**
     * The reader ships configured for inventory session S2 / target A (seen in the Seuic service
     * log on every open). In that mode a tag that has just been read flips its inventoried flag and
     * ignores further target-A requests for a couple of seconds, even after the radio stops. Two
     * visible effects: a write issued right after a scan fails (the select-then-access command
     * finds no tag), and in continuous mode a tag is counted once and then goes quiet.
     * Session S0 clears its flag as soon as the radio drops, and target A/B answers whichever flag
     * state the tag is in, so neither effect applies. Values: session 0..3 = S0..S3, target
     * 0/1/2 = A/B/A+B (the order of Seuic's own UHF tool dropdowns). Best-effort: a failure here
     * must not stop the reader from opening.
     */
    private fun applyAccessFriendlyInventoryMode() {
        val s = uhfService as? UHFService ?: return
        runCatching {
            s.setParameters(UHFService.PARAMETER_INVENTORY_SESSION, SESSION_S0)
            s.setParameters(UHFService.PARAMETER_INVENTORY_SESSION_TARGET, TARGET_A_OR_B)
        }
    }

    /** Put back what the reader had when we found it, so Seuic's own apps are unaffected. */
    private fun restoreDefaultInventoryMode() {
        val s = uhfService as? UHFService ?: return
        runCatching {
            s.setParameters(UHFService.PARAMETER_INVENTORY_SESSION, SESSION_S2)
            s.setParameters(UHFService.PARAMETER_INVENTORY_SESSION_TARGET, TARGET_A)
        }
    }

    /**
     * The Seuic SDK names are `inventoryStart` / `inventoryStop` / `getTagIDs`. An earlier version
     * looked up guessed names (`startScan`, `getTag`, `getEpc`); the lookup silently returned null,
     * the driver reported success, and Read Tag always showed "No tag found" on real hardware even
     * though Seuic's own UHF app read the same tag. Calls are therefore typed, so a wrong name is a
     * compile error rather than a silent no-op.
     */
    override fun startInventory(): Boolean {
        val s = uhfService as? UHFService ?: return false
        return try {
            lastSeenCounts.clear()
            val started = s.inventoryStart()
            isScanning = started
            started
        } catch (_: Exception) {
            false
        }
    }

    override fun stopInventory(): Boolean {
        val s = uhfService as? UHFService ?: return false
        return try {
            val stopped = s.inventoryStop()
            isScanning = false
            stopped
        } catch (_: Exception) {
            false
        }
    }

    /**
     * `getTagIDs()` returns the SDK's whole accumulated list on every call, with a running
     * per-tag read count. Emitting all of it each poll would inflate the screen's "Count" column
     * (continuous mode polls every 100 ms), so only tags whose count grew since the last poll are
     * emitted.
     */
    override fun readBufferedTagEvents(): List<TagEvent> {
        val s = uhfService as? UHFService ?: return emptyList()
        return try {
            val tags = s.tagIDs ?: return emptyList()
            val now = System.currentTimeMillis()
            val events = mutableListOf<TagEvent>()
            for (tag in tags) {
                val epc = tag.getId()?.trim()?.uppercase(Locale.US).orEmpty()
                if (epc.isEmpty()) continue
                val previous = lastSeenCounts[epc] ?: 0
                // `!=` rather than `>`: if the SDK restarts its list, counts drop below what we
                // remembered and `>` would hide that tag until it caught up.
                if (tag.count != previous || !lastSeenCounts.containsKey(epc)) {
                    lastSeenCounts[epc] = tag.count
                    events += TagEvent(epc = epc, rssi = tag.rssi, source = TagSource.EPC, seenAt = now)
                }
            }
            events
        } catch (_: Exception) {
            emptyList()
        }
    }

    /*
     * Tag access (read / write / lock).
     *
     * Facts below were taken from the SDK installed on the device (/system/framework/uhf.jar and
     * Seuic's own UHF tool), not from documentation:
     *  - signature is (epc, password, bank, offset, len, data): EPC first, password second. The EPC
     *    is the *select* target, so it cannot be omitted (the firmware rejects an empty one).
     *  - offset and len are in BYTES and must be even; this interface speaks Gen2 WORDS (like the
     *    Chainway driver), so we convert at this boundary.
     *  - the firmware refuses all three operations while an inventory is running, hence
     *    withInventoryPaused. Without it every call just returns false.
     */

    override fun readMemoryBank(
        epc: String?,
        bank: MemoryBank,
        ptr: Int,
        len: Int,
        pwdHex: String?
    ): String? {
        val s = uhfService as? UHFService ?: return null
        val pwd = passwordBytes(pwdHex) ?: return null
        if (ptr < 0 || len <= 0) return null
        return try {
            withInventoryPaused(s) {
                val epcBytes = resolveEpc(s, epc) ?: return@withInventoryPaused null
                readBytes(s, epcBytes, pwd, bank, ptr * BYTES_PER_WORD, len * BYTES_PER_WORD)
                    ?.let { bytesToHex(it) }
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun writeMemoryBank(
        epc: String?,
        bank: MemoryBank,
        ptr: Int,
        dataHex: String,
        pwdHex: String?
    ): Boolean {
        val s = uhfService as? UHFService ?: return false
        val pwd = passwordBytes(pwdHex) ?: return false
        val data = hexToBytes(dataHex) ?: return false
        // Gen2 writes whole 16-bit words.
        if (ptr < 0 || data.size % BYTES_PER_WORD != 0) return false
        return try {
            withInventoryPaused(s) {
                val epcBytes = resolveEpc(s, epc) ?: return@withInventoryPaused false
                if (bank == MemoryBank.EPC && ptr == EPC_DATA_START_WORD) {
                    writeEpc(s, epcBytes, pwd, data)
                } else {
                    s.writeTagData(epcBytes, pwd, bank.bankId, ptr * BYTES_PER_WORD, data.size, data)
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Writing only the EPC words is enough when the length is unchanged, but a tag keeps its EPC
     * length in the PC word (EPC bank word 1, top 5 bits). Rewriting a 96-bit EPC as 128-bit
     * without updating the PC would leave the tag still reporting 96 bits, i.e. a truncated EPC.
     * The Update EPC screen offers both formats, so the length change is handled here.
     */
    private fun writeEpc(s: UHFService, currentEpc: ByteArray, pwd: ByteArray, newEpc: ByteArray): Boolean {
        val newWords = newEpc.size / BYTES_PER_WORD
        if (newWords !in 1..MAX_PC_EPC_WORDS) return false

        // The tag's current EPC length *is* its PC length field, so a same-length rewrite needs no
        // PC read. Skipping it matters: each extra RF round-trip is another chance to miss the tag.
        if (newEpc.size == currentEpc.size) {
            return s.writeTagData(
                currentEpc, pwd, MemoryBank.EPC.bankId,
                EPC_DATA_START_WORD * BYTES_PER_WORD, newEpc.size, newEpc
            )
        }

        // Different length: the PC's other bits (UMI/XPC/numbering system) must be preserved, so it
        // has to be read. Without it we cannot write a correct PC, so refuse rather than leave a
        // tag whose length field disagrees with its EPC.
        val pc = readBytes(s, currentEpc, pwd, MemoryBank.EPC, PC_OFFSET_BYTES, BYTES_PER_WORD)
            ?: return false
        val oldPc = ((pc[0].toInt() and 0xFF) shl 8) or (pc[1].toInt() and 0xFF)

        val newPc = (oldPc and 0x07FF) or (newWords shl 11)
        val payload = ByteArray(BYTES_PER_WORD + newEpc.size)
        payload[0] = (newPc ushr 8).toByte()
        payload[1] = newPc.toByte()
        newEpc.copyInto(payload, BYTES_PER_WORD)
        return s.writeTagData(currentEpc, pwd, MemoryBank.EPC.bankId, PC_OFFSET_BYTES, payload.size, payload)
    }

    /**
     * Seuic's lock flag is `mode * 10 + memoryArea` (mode: 0 unlock, 1 lock, 2 permanent lock;
     * area: 0 access password, 1 EPC, 2 TID, 3 USER), read from Seuic's own UHF tool. This
     * interface only has lock/unlock, so it write-protects the EPC and USER banks behind the access
     * password. Permanent lock (mode 2) is deliberately never used: it cannot be undone and the
     * interface has no way to ask the operator to confirm it. The EPC bank decides success, since
     * a tag may have no USER memory.
     */
    override fun setLockState(
        epc: String?,
        accessPasswordHex: String?,
        lock: Boolean
    ): Boolean {
        val s = uhfService as? UHFService ?: return false
        val pwd = passwordBytes(accessPasswordHex) ?: return false
        val mode = if (lock) LOCK_MODE else UNLOCK_MODE
        return try {
            withInventoryPaused(s) {
                val epcBytes = resolveEpc(s, epc) ?: return@withInventoryPaused false
                val epcOk = s.lockTag(epcBytes, pwd, mode * 10 + MemoryBank.EPC.bankId)
                s.lockTag(epcBytes, pwd, mode * 10 + MemoryBank.USER.bankId)
                epcOk
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * The Seuic firmware needs an EPC to select the tag, but the Write and Lock screens mark the
     * EPC field "optional (nearest tag)". A blank field therefore means "the tag in front of the
     * reader", resolved with the same single-tag call Seuic's own tool uses. It deliberately avoids
     * `getTagIDs()`, whose list can still hold tags from an earlier scan, which could make a write
     * or lock land on the wrong tag.
     */
    private fun resolveEpc(s: UHFService, epc: String?): ByteArray? {
        if (!epc.isNullOrBlank()) return hexToBytes(epc)
        repeat(NEAREST_TAG_ATTEMPTS) {
            val tag = EPC()
            if (s.inventoryOnce(tag, NEAREST_TAG_TIMEOUT)) {
                hexToBytes(tag.getId())?.let { return it }
            }
        }
        return null
    }

    private fun readBytes(
        s: UHFService,
        epc: ByteArray,
        pwd: ByteArray,
        bank: MemoryBank,
        offsetBytes: Int,
        lenBytes: Int
    ): ByteArray? {
        val buffer = ByteArray(lenBytes)
        return if (s.readTagData(epc, pwd, bank.bankId, offsetBytes, lenBytes, buffer)) buffer else null
    }

    /**
     * Runs [block] with inventory stopped, then restarts it if it was running. Batch Update EPC
     * writes while its inventory loop is live, so the scan has to resume afterwards. The SDK may
     * clear its tag list on restart, so the delta tracking is reset too.
     */
    private fun <T> withInventoryPaused(s: UHFService, block: () -> T): T {
        val wasScanning = isScanning
        if (wasScanning) {
            s.inventoryStop()
            isScanning = false
            // Seuic's own tool never issues an access command straight after stop (a person
            // taps in between). The module may still be draining inventory frames.
            Thread.sleep(INVENTORY_STOP_SETTLE_MS)
        }
        try {
            return block()
        } finally {
            if (wasScanning) {
                lastSeenCounts.clear()
                isScanning = s.inventoryStart()
            }
        }
    }

    private fun hexToBytes(hex: String?): ByteArray? {
        val h = hex?.filterNot { it.isWhitespace() } ?: return null
        if (h.isEmpty() || h.length % 2 != 0) return null
        if (!h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { String.format(Locale.US, "%02X", it) }

    /** Gen2 access password is 32 bits; blank means the default all-zero password. */
    private fun passwordBytes(pwdHex: String?): ByteArray? {
        if (pwdHex.isNullOrBlank()) return ByteArray(4)
        return hexToBytes(pwdHex)?.takeIf { it.size == 4 }
    }

    private companion object {
        const val BYTES_PER_WORD = 2
        const val EPC_DATA_START_WORD = 2
        const val PC_OFFSET_BYTES = 2
        const val MAX_PC_EPC_WORDS = 31
        const val UNLOCK_MODE = 0
        const val LOCK_MODE = 1
        const val INVENTORY_STOP_SETTLE_MS = 150L
        const val SESSION_S0 = 0
        const val SESSION_S2 = 2
        const val TARGET_A = 0
        const val TARGET_A_OR_B = 2
        const val NEAREST_TAG_ATTEMPTS = 5
        const val NEAREST_TAG_TIMEOUT = 60 // same value Seuic's own UHF tool passes
    }

    override fun setPower(powerDbm: Int): Boolean {
        val s = uhfService ?: return false
        return try {
            val setPowerMethod = s.javaClass.methods.firstOrNull { it.name == "setPower" && it.parameterCount == 1 }
            setPowerMethod?.invoke(s, powerDbm)
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun getPower(): Int? {
        val s = uhfService ?: return null
        return try {
            val getPowerMethod = s.javaClass.methods.firstOrNull { it.name == "getPower" && it.parameterCount == 0 }
            (getPowerMethod?.invoke(s) as? Number)?.toInt()
        } catch (_: Exception) {
            null
        }
    }

    override fun readDiagnosticsSummary(): ReaderDiagnosticsSummary {
        val power = getPower()
        return ReaderDiagnosticsSummary(
            sdkReady = uhfService != null,
            readerOpen = uhfService != null,
            powerDbm = power,
            regionOrFrequency = "Seuic UHF",
            batteryNote = "Seuic Reader Ready",
            detailLine = "Seuic AUTOID9U Driver"
        )
    }
}
