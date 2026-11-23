package com.rfidsoftwares.ui.screens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rfidsoftwares.R
import com.rfidsoftwares.controller.audit.AuditHistoryStore
import com.rfidsoftwares.data.frappe.FrappeSessionStore
import com.rfidsoftwares.ui.base.BaseScreenFragment

/**
 * One sent count, shown the way it looked while scanning: the same item table (tap an item for its scanned
 * and missing tags) and the same "not in this warehouse" line. It is a record; nothing here can be changed.
 */
class AuditHistoryDetailFragment : BaseScreenFragment() {

    override fun screenTitle(): String = "Audit Details"
    override fun allowOfflinePanel(): Boolean = false

    override fun createBody(inflater: LayoutInflater, container: FrameLayout): View =
        inflater.inflate(R.layout.body_audit_history_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val user = FrappeSessionStore(requireContext()).session()?.user
        if (user == null) {
            findNavController().navigate(R.id.action_global_erpLoginFragment)
            return
        }
        val entry = arguments?.getString(ARG_NAME)?.let { AuditHistoryStore(requireContext()).get(it, user) }
        val title = view.findViewById<TextView>(R.id.historyDetailTitle)
        if (entry == null) {
            title.text = "This count is no longer on the phone."
            return
        }

        title.text = entry.name
        view.findViewById<TextView>(R.id.historyDetailMeta).text =
            "${entry.warehouse} · sent ${AuditHistoryFragment.formatTime(entry.sentAt)} by ${entry.user}\n" +
                "Downloaded ${entry.downloadedAt} (server time)"
        view.findViewById<TextView>(R.id.historyDetailSummary).text = "Scanned ${entry.scannedUnits} of ${entry.expectedUnits}"

        val result = entry.toResult()
        val adapter = AuditResultAdapter { AuditDialogs.showItem(requireContext(), it) }
        view.findViewById<RecyclerView>(R.id.historyDetailList).apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = adapter
        }
        adapter.submit(result.items)

        val unknown = view.findViewById<TextView>(R.id.historyDetailUnknown)
        if (result.unknownEpcs.isNotEmpty()) {
            unknown.visibility = View.VISIBLE
            unknown.text = "${result.unknownEpcs.size} tag(s) read that were not in this warehouse. Tap to see."
            unknown.setOnClickListener { AuditDialogs.showUnknown(requireContext(), result.unknownEpcs) }
        }
    }

    companion object {
        const val ARG_NAME = "name"
    }
}
