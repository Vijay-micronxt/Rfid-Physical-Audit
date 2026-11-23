package com.rfidsoftwares.ui.screens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.rfidsoftwares.R
import com.rfidsoftwares.data.frappe.FrappeClient
import com.rfidsoftwares.data.frappe.FrappeException
import com.rfidsoftwares.data.frappe.FrappeSessionStore
import com.rfidsoftwares.ui.base.BaseScreenFragment
import java.util.concurrent.Executors

/**
 * The app's front door: username and password for the ERP. The server address is set with the
 * gear icon at the top right. Nothing else in the app opens until this succeeds.
 */
class ErpLoginFragment : BaseScreenFragment() {

    override fun screenTitle(): String = "Log in"
    override fun isTopLevelScreen(): Boolean = true
    // A failed login already says "cannot reach the server"; the shared offline panel would cover the form.
    override fun allowOfflinePanel(): Boolean = false
    override fun onHeaderSettings(): (() -> Unit)? = { showServerDialog() }
    // No header bar here: only the theme and settings icons at the top right.
    override fun isHeaderIconsOnly(): Boolean = true

    private lateinit var store: FrappeSessionStore
    private val client = FrappeClient()
    private var io = Executors.newSingleThreadExecutor()

    private lateinit var serverText: TextView
    private lateinit var userInput: TextInputEditText
    private lateinit var passwordInput: TextInputEditText
    private lateinit var errorText: TextView
    private lateinit var loginButton: MaterialButton

    override fun createBody(inflater: LayoutInflater, container: FrameLayout): View =
        inflater.inflate(R.layout.body_erp_login, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        store = FrappeSessionStore(requireContext())
        if (io.isShutdown) io = Executors.newSingleThreadExecutor()

        serverText = view.findViewById(R.id.erpServerText)
        userInput = view.findViewById(R.id.erpUserInput)
        passwordInput = view.findViewById(R.id.erpPasswordInput)
        errorText = view.findViewById(R.id.erpLoginError)
        loginButton = view.findViewById(R.id.erpLoginButton)

        userInput.setText(store.lastUser())
        showServer()

        loginButton.setOnClickListener { login() }
        passwordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                login()
                true
            } else {
                false
            }
        }
    }

    private fun showServer() {
        val server = store.lastBaseUrl()
        if (server.isBlank()) {
            serverText.text = "Server not set. Tap the settings icon at the top right to enter the address."
            serverText.setTextColor(ContextCompat.getColor(requireContext(), R.color.rfid_error))
        } else {
            serverText.text = "Server: $server"
            serverText.setTextColor(ContextCompat.getColor(requireContext(), R.color.rfid_on_surface))
        }
    }

    private fun login() {
        val server = store.lastBaseUrl()
        val user = userInput.text?.toString().orEmpty().trim()
        val password = passwordInput.text?.toString().orEmpty()
        when {
            server.isBlank() -> return showError("Set the server address first: tap the settings icon at the top right.")
            user.isEmpty() || password.isEmpty() -> return showError("Enter your username and password.")
        }
        errorText.visibility = View.GONE
        loginButton.isEnabled = false
        loginButton.text = "Logging in..."
        io.execute {
            try {
                val session = client.login(server, user, password)
                store.saveSession(session)
                ui {
                    passwordInput.setText("")
                    findNavController().navigate(R.id.action_erpLogin_to_modeSelectorFragment)
                }
            } catch (e: FrappeException) {
                ui {
                    loginButton.isEnabled = true
                    loginButton.text = "Log in"
                    showError(e.message.orEmpty())
                }
            }
        }
    }

    private fun showError(message: String) {
        errorText.text = message
        errorText.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------- server address (gear icon)

    private fun showServerDialog() {
        val content = layoutInflater.inflate(R.layout.dialog_server_address, null)
        val input: TextInputEditText = content.findViewById(R.id.serverAddressInput)
        val result: TextView = content.findViewById(R.id.serverAddressResult)
        input.setText(store.lastBaseUrl())

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Server address")
            .setView(content)
            .setNegativeButton(R.string.action_cancel, null)
            .setNeutralButton("Test", null)
            .setPositiveButton("Save", null)
            .create()
        dialog.show()

        fun say(text: String, ok: Boolean) {
            result.text = text
            result.setTextColor(ContextCompat.getColor(requireContext(), if (ok) R.color.rfid_online else R.color.rfid_error))
            result.visibility = View.VISIBLE
        }

        // Buttons are re-wired after show() so a bad address keeps the dialog open.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                store.saveBaseUrl(FrappeClient.normalizeBaseUrl(input.text?.toString().orEmpty()))
                showServer()
                errorText.visibility = View.GONE
                dialog.dismiss()
            } catch (e: FrappeException) {
                say(e.message.orEmpty(), ok = false)
            }
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val address = input.text?.toString().orEmpty()
            say("Testing...", ok = true)
            io.execute {
                val outcome = try {
                    client.ping(address)
                    "Connected. This is an ERPNext server." to true
                } catch (e: FrappeException) {
                    e.message.orEmpty() to false
                }
                ui { if (dialog.isShowing) say(outcome.first, outcome.second) }
            }
        }
    }

    /** Runs on the main thread, and only while the screen is still there. */
    private fun ui(block: () -> Unit) {
        view?.post { if (isAdded && view != null) block() }
    }

    override fun onDestroyView() {
        io.shutdownNow()
        super.onDestroyView()
    }
}
