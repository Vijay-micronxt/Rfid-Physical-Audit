package com.rfidsoftwares.ui.screens

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rfidsoftwares.R
import com.rfidsoftwares.controller.audit.ItemResult

/** The item table of a count: item, scanned, site and difference. Used while scanning and in History. */
class AuditResultAdapter(private val onClick: (ItemResult) -> Unit) :
    RecyclerView.Adapter<AuditResultAdapter.Holder>() {

    private var rows: List<ItemResult> = emptyList()

    fun submit(newRows: List<ItemResult>) {
        rows = newRows
        notifyDataSetChanged()
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.auditRowName)
        val meta: TextView = v.findViewById(R.id.auditRowMeta)
        val scanned: TextView = v.findViewById(R.id.auditRowScanned)
        val site: TextView = v.findViewById(R.id.auditRowSite)
        val diff: TextView = v.findViewById(R.id.auditRowDiff)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_audit_result_row, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        holder.name.text = row.itemName
        holder.meta.text = "${row.itemCode} · ERP stock ${formatQty(row.erpStockQty)}"
        holder.scanned.text = row.scannedQty.toString()
        holder.site.text = row.siteQty.toString()
        holder.diff.text = if (row.difference > 0) "-${row.difference}" else if (row.difference < 0) "+${-row.difference}" else "0"
        holder.diff.setTextColor(
            ContextCompat.getColor(
                holder.itemView.context,
                if (row.difference == 0) R.color.rfid_online else R.color.rfid_error,
            ),
        )
        holder.itemView.setOnClickListener { onClick(row) }
    }

    override fun getItemCount(): Int = rows.size

    private fun formatQty(q: Double): String = if (q % 1.0 == 0.0) q.toLong().toString() else q.toString()
}

/** The two detail pop-ups of a count, shared by the scanning screen and History. */
object AuditDialogs {
    private const val MAX_LISTED = 200

    fun showItem(context: Context, item: ItemResult) {
        MaterialAlertDialogBuilder(context)
            .setTitle(item.itemName)
            .setMessage(
                "Scanned (${item.scannedEpcs.size}):\n${listOrNone(item.scannedEpcs)}\n\n" +
                    "Not found (${item.missingEpcs.size}):\n${listOrNone(item.missingEpcs)}",
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    fun showUnknown(context: Context, unknown: List<String>) {
        MaterialAlertDialogBuilder(context)
            .setTitle("Not in this warehouse (${unknown.size})")
            .setMessage(listOrNone(unknown))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun listOrNone(epcs: List<String>): String = when {
        epcs.isEmpty() -> "none"
        epcs.size <= MAX_LISTED -> epcs.joinToString("\n")
        else -> epcs.take(MAX_LISTED).joinToString("\n") + "\n... and ${epcs.size - MAX_LISTED} more"
    }
}
