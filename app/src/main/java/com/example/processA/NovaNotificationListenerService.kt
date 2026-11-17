package com.example.processA

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.data.LocalFtsIndexHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process A Local Notification Listener.
 * Captures incoming SMS/WhatsApp/Messenger alerts locally into the SQLite FTS5 index
 * for zero-cloud "Summarize unread texts" awareness.
 */
class NovaNotificationListenerService : NotificationListenerService() {

    private val ftsHelper by lazy { LocalFtsIndexHelper(applicationContext) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        _isConnected.value = true
    }

    override fun onListenerDisconnected() {
        _isConnected.value = false
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return

        val docId = "notif_${sbn.key.hashCode() and 0x7FFFFFFF}"
        ftsHelper.upsertDocument(
            docId = docId,
            category = "messages",
            title = "$pkg • $title",
            body = text,
            uriOrRef = "notif://${sbn.packageName}/${sbn.id}"
        )
    }

    companion object {
        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    }
}
