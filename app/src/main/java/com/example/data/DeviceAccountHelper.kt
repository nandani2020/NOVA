package com.example.data

import android.Manifest
import android.accounts.AccountManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Stores and retrieves the active device account observed via AccountManager
 * (when GET_ACCOUNTS is granted) or captured during account-selection dialogs.
 * Never fabricates or pre-populates fake account credentials.
 */
class DeviceAccountHelper(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getRecordedAccount(): String? =
        prefs.getString(KEY_RECORDED_ACCOUNT, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun recordAccount(rawValue: String): String? {
        val cleaned = extractCleanAccount(rawValue) ?: return null
        prefs.edit().putString(KEY_RECORDED_ACCOUNT, cleaned).apply()
        return cleaned
    }

    fun clearRecordedAccount() {
        prefs.edit().remove(KEY_RECORDED_ACCOUNT).apply()
    }

    /**
     * Returns the recorded account, or detects a real account from Android AccountManager
     * when GET_ACCOUNTS is granted and automatically records it for future confirmations.
     */
    fun getOrDetectSystemAccount(): String? {
        getRecordedAccount()?.let { return it }

        val hasAccountsPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.GET_ACCOUNTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasAccountsPerm) return null

        val accounts = runCatching { AccountManager.get(context).accounts }.getOrNull().orEmpty()
        if (accounts.isEmpty()) return null

        val preferred = accounts.firstOrNull { it.type == "com.google" && it.name.contains("@") }
            ?: accounts.firstOrNull { it.name.contains("@") }
            ?: accounts.firstOrNull()

        val name = preferred?.name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return recordAccount(name)
    }

    companion object {
        private const val PREFS_NAME = "nova_device_account"
        private const val KEY_RECORDED_ACCOUNT = "recorded_account"
        private val EMAIL_REGEX = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")

        fun extractCleanAccount(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            EMAIL_REGEX.find(trimmed)?.let { return it.value }
            return trimmed.take(64)
        }
    }
}
