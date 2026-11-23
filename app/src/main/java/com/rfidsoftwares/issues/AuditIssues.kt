package com.rfidsoftwares.issues

import android.content.Context
import com.rfidsoftwares.data.frappe.AuditSnapshot
import com.rfidsoftwares.data.local.RfidSessionDbProvider
import com.rfidsoftwares.data.local.entities.IssueEntity

/**
 * What the Physical Audit screen tells the warning page (Issue Center) instead of showing it on the screen itself:
 * which warehouse is saved on the phone and how much of it, and anything that went wrong.
 *
 * Every note has a fixed ID, so a newer note about the same thing replaces the older one: the page shows the
 * latest state per warehouse, not a growing list.
 */
object AuditIssues {
    const val WAREHOUSES_ID = "audit-warehouses"
    const val SEND_ID = "audit-send"
    const val READER_ID = "audit-reader"

    fun cachedId(warehouse: String) = "audit-cached-$warehouse"
    fun failedId(warehouse: String) = "audit-failed-$warehouse"

    /** "This warehouse is saved on the phone": how many items and tags, and when it was downloaded. */
    fun cached(snapshot: AuditSnapshot, now: Long): IssueEntity = IssueEntity(
        issueId = cachedId(snapshot.warehouse),
        severity = "info",
        category = IssueCategories.SYNC,
        message = "${snapshot.warehouse} is saved on this phone: ${snapshot.items.size} item(s), ${snapshot.tags.size} tag(s)",
        correlationId = null,
        detail = "Downloaded ${snapshot.generatedAt} (server time)",
        createdAt = now,
        active = true,
        suggestedAction = IssueActions.NONE,
    )

    fun problem(id: String, category: String, message: String, detail: String?, now: Long): IssueEntity = IssueEntity(
        issueId = id,
        severity = "error",
        category = category,
        message = message,
        correlationId = null,
        detail = detail,
        createdAt = now,
        active = true,
        suggestedAction = IssueActions.NONE,
    )

    // The writes below touch the database: call them from a background thread. They never throw, so a problem
    // with the warning page cannot break the audit screen.

    fun recordCached(context: Context, snapshot: AuditSnapshot) = runCatching {
        val db = RfidSessionDbProvider.getInstance(context)
        IssueLogSupport.insertIssue(db, cached(snapshot, System.currentTimeMillis()))
        db.issueDao().dismiss(failedId(snapshot.warehouse)) // it loaded this time
    }

    fun recordProblem(context: Context, id: String, category: String, message: String, detail: String? = null) = runCatching {
        IssueLogSupport.insertIssue(
            RfidSessionDbProvider.getInstance(context),
            problem(id, category, message, detail, System.currentTimeMillis()),
        )
    }

    /** The thing works again: take its earlier problem off the warning page. */
    fun resolve(context: Context, id: String) = runCatching {
        RfidSessionDbProvider.getInstance(context).issueDao().dismiss(id)
    }
}
