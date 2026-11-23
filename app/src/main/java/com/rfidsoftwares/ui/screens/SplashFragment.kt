/*
 * Copyright (c) 2026 RFID Software India Private Limited (https://rfidsoftwares.com)
 * OpenRFID Handheld Android - Universal Multi-Vendor Handheld RFID Middleware & Suite
 */

package com.rfidsoftwares.ui.screens

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.navigation.fragment.findNavController
import com.rfidsoftwares.R
import com.rfidsoftwares.data.frappe.FrappeSessionStore
import com.rfidsoftwares.ui.base.BaseScreenFragment

class SplashFragment : BaseScreenFragment() {

    private val handler = Handler(Looper.getMainLooper())

    override fun screenTitle(): String = "OpenRFID Handheld"
    override fun screenSubtitle(): String? = "Preparing your universal suite"
    override fun isTopLevelScreen(): Boolean = true

    override fun createBody(inflater: LayoutInflater, container: FrameLayout): View {
        return inflater.inflate(R.layout.body_splash, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val loggedIn = FrappeSessionStore(requireContext()).session() != null
        handler.postDelayed(
            {
                // The app opens only for a logged-in user; everyone else goes to the ERP login first.
                findNavController().navigate(
                    if (loggedIn) R.id.action_splash_to_modeSelectorFragment else R.id.action_splash_to_erpLoginFragment
                )
            },
            750
        )
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }
}
