package com.rfidsoftwares.ui.screens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rfidsoftwares.R
import com.rfidsoftwares.controller.audit.AuditHistoryStore
import com.rfidsoftwares.controller.audit.HistoryEntry
import com.rfidsoftwares.data.frappe.FrappeSessionStore
import com.rfidsoftwares.ui.base.BaseScreenFragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The counts this user has sent to the ERP, newest first. Tap one to see its items, as when it was scanned. */
class AuditHistoryFragment : BaseScreenFragment() {

    override fun screenTitle(): String = "Audit History"
    override fun allowOfflinePanel(): Boolean = false

    override fun createBody(inflater: LayoutInflater, container: FrameLayout): View =
        inflater.inflate(R.layout.body_audit_history, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val user = FrappeSessionStore(requireContext()).session()?.user
        if (user == null) {
            findNavController().navigate(R.id.action_global_erpLoginFragment)
            return
        }
        val entries = AuditHistoryStore(requireContext()).list(user)
        view.findViewById<TextView>(R.id.auditHistoryEmpty).visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        view.findViewById<RecyclerView>(R.id.auditHistoryList).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = HistoryAdapter(entries) {
                findNavController().navigate(
                    R.id.action_auditHistory_to_auditHistoryDetail,
                    bundleOf(AuditHistoryDetailFragment.ARG_NAME to it.name),
                )
            }
        }
    }

    private class HistoryAdapter(
        private val entries: List<HistoryEntry>,
        private val onClick: (HistoryEntry) -> Unit,
    ) : RecyclerView.Adapter<HistoryAdapter.Holder>() {

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.historyRowName)
            val meta: TextView = v.findViewById(R.id.historyRowMeta)
            val count: TextView = v.findViewById(R.id.historyRowCount)
            val flags: TextView = v.findViewById(R.id.historyRowFlags)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_audit_history_row, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val e = entries[position]
            holder.name.text = e.name
            holder.meta.text = "${e.warehouse} · ${formatTime(e.sentAt)}"
            holder.count.text = "Scanned ${e.scannedUnits} of ${e.expectedUnits}"
            val missing = e.expectedUnits - e.scannedUnits
            holder.flags.text = listOfNotNull(
                if (missing > 0) "$missing missing" else null,
                if (e.unknownEpcs.isNotEmpty()) "${e.unknownEpcs.size} not expected" else null,
            ).joinToString(" · ")
            holder.itemView.setOnClickListener { onClick(e) }
        }

        override fun getItemCount(): Int = entries.size
    }

    companion object {
        fun formatTime(millis: Long): String = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(millis))
    }
}
