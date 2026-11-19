package com.example.processA

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.data.LocalFtsIndexHelper
import com.example.engine.ImportantNotificationFilter
import com.example.engine.ImportantNotificationFilter.ImportantNotificationAlert
import com.example.engine.ImportantNotificationFilter.ImportantNotificationType
import com.example.engine.ImportantNotificationFilter.RawNotificationSignals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Process A Local Notification Listener.
 * Reads incoming Android notifications, filters out promos/downloads/media/noise,
 * indexes important notifications (Messages, Missed Calls, Reminders) locally in SQLite FTS,
 * and emits real-time prompts for Nova's voice and UI layers.
 */
class NovaNotificationListenerService : NotificationListenerService() {

    private val ftsHelper by lazy { LocalFtsIndexHelper(applicationContext) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        _isConnected.value = true
        serviceScope.launch {
            runCatching {
                val active = getImportantActiveNotifications()
                val now = System.currentTimeMillis()
                active.forEach { alert ->
                    ftsHelper.upsertNotificationArchive(alert, markRead = false, nowMs = now, indexFts = true)
                    recordRecentAlert(alert)
                }
                val audit = ftsHelper.runBackgroundNotificationAudit(now)
                _unreadNotificationCount.value = audit.unreadTotal
                _latestAudit.value = audit
            }
        }
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        _isConnected.value = false
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        if (instance === this) instance = null
        _isConnected.value = false
        super.onDestroy()
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: RankingMap?,
        reason: Int
    ) {
        super.onNotificationRemoved(sbn, rankingMap, reason)
        if (sbn == null) return
        val handledOrDismissed = reason == REASON_CLICK ||
            reason == REASON_APP_CANCEL ||
            reason == REASON_APP_CANCEL_ALL ||
            reason == REASON_CANCEL ||
            reason == REASON_CANCEL_ALL
        if (handledOrDismissed) {
            val alert = extractActiveAlert(sbn, includeGeneralActive = true, allowStandaloneSummary = true)
            val notifKey = sbn.key
            serviceScope.launch {
                runCatching {
                    val now = System.currentTimeMillis()
                    if (alert != null) {
                        ftsHelper.markNotificationsRead(listOf(alert.id), nowMs = now)
                    }
                    if (!notifKey.isNullOrBlank()) {
                        ftsHelper.markNotificationsReadByKey(notifKey, nowMs = now)
                    }
                    val audit = ftsHelper.runBackgroundNotificationAudit(now)
                    _unreadNotificationCount.value = audit.unreadTotal
                    _latestAudit.value = audit
                }
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val alert = extractImportantAlert(sbn) ?: return

        val fingerprint = "${sbn.key}|${alert.title}|${alert.body}"
        val now = System.currentTimeMillis()
        val lastSeen = recentFingerprints[fingerprint]
        if (lastSeen != null && (now - lastSeen) < DEDUP_WINDOW_MS) {
            return
        }
        recentFingerprints[fingerprint] = now
        if (recentFingerprints.size > 120) {
            val cutoff = now - DEDUP_WINDOW_MS
            recentFingerprints.entries.removeIf { it.value < cutoff }
        }

        sbn.notification?.contentIntent?.let { pi ->
            contentIntents[alert.id] = pi
        }

        val typeTag = "[${alert.type.label}]"
        val cleanShort = alert.shortLabel.removePrefix("${alert.type.label} • ").trim()
        val indexedTitle = "$typeTag $cleanShort"
        val indexedBody = alert.body.ifEmpty { alert.title }
        ftsHelper.upsertNotificationArchive(
            alert = alert,
            markRead = false,
            nowMs = now,
            indexFts = true
        )
        val audit = ftsHelper.runBackgroundNotificationAudit(now)
        _unreadNotificationCount.value = audit.unreadTotal
        _latestAudit.value = audit

        val embRuntime = EmbeddingGemmaRuntime.activeProcessInstance
        if (embRuntime != null && embRuntime.isReady.value) {
            serviceScope.launch {
                val vec = embRuntime.embedDocument(indexedTitle, indexedBody)
                if (vec != null) {
                    ftsHelper.updateDocumentEmbedding(alert.id, vec)
                }
            }
        }

        recordRecentAlert(alert)
        _latestImportantAlert.value = alert
        _incomingImportantAlerts.tryEmit(alert)
    }

    fun getImportantActiveNotifications(): List<ImportantNotificationAlert> =
        getActiveNotificationsForReading(includeGeneralActive = false)

    fun getActiveNotificationsForReading(includeGeneralActive: Boolean = true): List<ImportantNotificationAlert> {
        val active = runCatching { activeNotifications?.toList().orEmpty() }.getOrDefault(emptyList())
        // Identify groups that already have at least one non-summary child notification
        val groupsWithChildren = active
            .filter { sbn -> ((sbn.notification?.flags ?: 0) and Notification.FLAG_GROUP_SUMMARY) == 0 }
            .map { sbn -> "${sbn.packageName}|${sbn.groupKey.orEmpty()}" }
            .toHashSet()
        return active
            .mapNotNull { sbn ->
                val isGroupSummary = ((sbn.notification?.flags ?: 0) and Notification.FLAG_GROUP_SUMMARY) != 0
                val groupSig = "${sbn.packageName}|${sbn.groupKey.orEmpty()}"
                val allowStandaloneSummary = includeGeneralActive && isGroupSummary && groupSig !in groupsWithChildren
                val alert = extractActiveAlert(
                    sbn = sbn,
                    includeGeneralActive = includeGeneralActive,
                    allowStandaloneSummary = allowStandaloneSummary
                ) ?: return@mapNotNull null
                sbn.notification?.contentIntent?.let { pi ->
                    contentIntents[alert.id] = pi
                }
                alert
            }
            .sortedByDescending { it.timestampMs }
    }

    private fun extractImportantAlert(sbn: StatusBarNotification): ImportantNotificationAlert? =
        extractActiveAlert(sbn, includeGeneralActive = false, allowStandaloneSummary = false)

    private fun extractActiveAlert(
        sbn: StatusBarNotification,
        includeGeneralActive: Boolean,
        allowStandaloneSummary: Boolean = false
    ): ImportantNotificationAlert? {
        val pkg = sbn.packageName ?: return null
        val notif = sbn.notification ?: return null
        val extras = notif.extras ?: return null

        var title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            .ifEmpty { extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()?.trim().orEmpty() }
        var text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
        val summaryText = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()?.trim().orEmpty()
        var bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString()?.trim()?.takeIf { line -> line.isNotEmpty() } }
            .orEmpty()
        val template = extras.getString(Notification.EXTRA_TEMPLATE).orEmpty()
        val hasMessages = extras.containsKey(Notification.EXTRA_MESSAGES)
        val hasMediaSession = extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
        val progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val progressInd = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) notif.channelId.orEmpty() else ""

        // Extract latest MessagingStyle sender & message text when present
        if (hasMessages) {
            @Suppress("DEPRECATION")
            val msgBundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                ?.mapNotNull { it as? Bundle }
                .orEmpty()
            val lastMsg = msgBundles.lastOrNull()
            if (lastMsg != null) {
                val msgText = lastMsg.getCharSequence("text")?.toString()?.trim().orEmpty()
                val senderName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    @Suppress("DEPRECATION")
                    val person = lastMsg.getParcelable("sender_person") as? Person
                    person?.name?.toString()?.trim().orEmpty()
                        .ifEmpty { lastMsg.getCharSequence("sender")?.toString()?.trim().orEmpty() }
                } else {
                    lastMsg.getCharSequence("sender")?.toString()?.trim().orEmpty()
                }
                if (msgText.isNotEmpty() && bigText.isEmpty()) {
                    bigText = msgText
                    if (text.isEmpty()) text = msgText
                }
                if (senderName.isNotEmpty() &&
                    (title.isEmpty() || ImportantNotificationFilter.looksLikePackageName(title))
                ) {
                    title = senderName
                }
            }
        }

        // InboxStyle (Gmail, Outlook, Messenger, WhatsApp, banking/delivery digests):
        // surface the actual latest text lines when EXTRA_TEXT is blank or a generic count
        if (textLines.isNotEmpty()) {
            val isGenericCount = text.isEmpty() ||
                Regex("^\\d+\\s+(?:new\\s+)?(?:messages?|notifications?|emails?|unread)$", RegexOption.IGNORE_CASE)
                    .matches(text)
            if (bigText.isEmpty() || isGenericCount) {
                bigText = textLines.takeLast(2).joinToString(" | ")
                if (isGenericCount) text = textLines.last()
            }
        }
        if (text.isEmpty() && bigText.isEmpty() && summaryText.isNotEmpty()) {
            text = summaryText
        }

        val pmLabel = runCatching {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info)?.toString()?.trim().orEmpty()
        }.getOrDefault("")
        val substName = extras.getCharSequence("android.substName")?.toString()?.trim().orEmpty()
        val rawAppLabel = pmLabel.takeUnless {
            it.isEmpty() || ImportantNotificationFilter.looksLikePackageName(it) || it.equals(pkg, ignoreCase = true)
        } ?: substName
        val appLabel = ImportantNotificationFilter.resolveFriendlyAppName(rawAppLabel, pkg)

        val signals = RawNotificationSignals(
            key = sbn.key ?: "$pkg:${sbn.id}",
            packageName = pkg,
            ownPackageName = packageName,
            appLabel = appLabel,
            category = notif.category,
            title = title,
            text = text,
            subText = subText,
            bigText = bigText,
            template = template,
            hasMessagingMessages = hasMessages,
            hasMediaSession = hasMediaSession,
            flags = notif.flags,
            hasProgress = progressMax > 0 || progressInd,
            channelId = channelId,
            postTimeMs = sbn.postTime.takeIf { it > 0L } ?: System.currentTimeMillis()
        )
        return if (includeGeneralActive) {
            ImportantNotificationFilter.classifyActiveForUserQuery(
                signals = signals,
                allowStandaloneSummary = allowStandaloneSummary
            )
        } else {
            ImportantNotificationFilter.classify(signals)
        }
    }

    companion object {
        private const val DEDUP_WINDOW_MS = 180_000L

        @Volatile
        var instance: NovaNotificationListenerService? = null
            private set

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _incomingImportantAlerts = MutableSharedFlow<ImportantNotificationAlert>(
            replay = 0,
            extraBufferCapacity = 24
        )
        val incomingImportantAlerts: SharedFlow<ImportantNotificationAlert> = _incomingImportantAlerts.asSharedFlow()

        private val _latestImportantAlert = MutableStateFlow<ImportantNotificationAlert?>(null)
        val latestImportantAlert: StateFlow<ImportantNotificationAlert?> = _latestImportantAlert.asStateFlow()

        private val _unreadNotificationCount = MutableStateFlow(0)
        val unreadNotificationCount: StateFlow<Int> = _unreadNotificationCount.asStateFlow()

        private val _latestAudit = MutableStateFlow<LocalFtsIndexHelper.NotificationAuditSnapshot?>(null)
        val latestAudit: StateFlow<LocalFtsIndexHelper.NotificationAuditSnapshot?> = _latestAudit.asStateFlow()

        private val recentAlertsLock = Any()
        private val recentImportantAlertsList = ArrayDeque<ImportantNotificationAlert>(24)
        private val recentFingerprints = ConcurrentHashMap<String, Long>()
        private val contentIntents = ConcurrentHashMap<String, PendingIntent>()

        fun isPermissionGranted(context: Context): Boolean {
            if (_isConnected.value || instance != null) return true
            val enabled = runCatching {
                Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            }.getOrNull().orEmpty()
            val flat = ComponentName(context, NovaNotificationListenerService::class.java).flattenToString()
            val shortFlat = ComponentName(context, NovaNotificationListenerService::class.java).flattenToShortString()
            return enabled.split(':').any { entry ->
                entry.equals(flat, ignoreCase = true) || entry.equals(shortFlat, ignoreCase = true)
            }
        }

        @Volatile
        private var lastRebindRequestMs: Long = 0L

        fun requestRebindIfNeeded(context: Context): Boolean {
            if (instance != null || testActiveNotificationsProvider != null) return false
            if (!isPermissionGranted(context)) return false
            val now = System.currentTimeMillis()
            if (now - lastRebindRequestMs >= 10_000L) {
                lastRebindRequestMs = now
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    runCatching {
                        requestRebind(ComponentName(context, NovaNotificationListenerService::class.java))
                    }
                }
            }
            return true
        }

        data class LiveNotificationReconciliation(
            val liveChecked: Boolean,
            val serviceRebinding: Boolean = false,
            val liveActiveRecords: List<LocalFtsIndexHelper.ArchivedNotificationRecord>,
            val newlySavedCount: Int,
            val alreadySavedUnreadCount: Int,
            val alreadyReadCount: Int,
            val previouslySavedUnreadNotInShadeCount: Int,
            val audit: LocalFtsIndexHelper.NotificationAuditSnapshot
        )

        @Volatile
        internal var testActiveNotificationsProvider: ((Boolean) -> List<ImportantNotificationAlert>)? = null

        fun getImportantNotificationsSnapshot(): List<ImportantNotificationAlert> {
            val active = testActiveNotificationsProvider?.invoke(false)
                ?: instance?.getImportantActiveNotifications().orEmpty()
            val recent = synchronized(recentAlertsLock) { recentImportantAlertsList.toList() }
            return (active + recent)
                .distinctBy { it.id }
                .sortedByDescending { it.timestampMs }
                .take(20)
        }

        fun getCurrentActiveNotificationsSnapshot(
            includeGeneralActive: Boolean = true
        ): List<ImportantNotificationAlert> {
            testActiveNotificationsProvider?.let { provider ->
                return provider.invoke(includeGeneralActive)
                    .distinctBy { it.id }
                    .sortedByDescending { it.timestampMs }
                    .take(20)
            }
            val liveSvc = instance ?: return emptyList()
            return liveSvc.getActiveNotificationsForReading(includeGeneralActive = includeGeneralActive)
                .distinctBy { it.id }
                .sortedByDescending { it.timestampMs }
                .take(20)
        }

        /**
         * Actively re-queries Android's live status-bar notifications right now and reconciles them
         * against the saved 14-day SQLite archive:
         * - Saves any newly discovered live notifications into the archive (deduplicated by notif_key).
         * - Preserves read/unread state of already-saved live notifications (unless content updated).
         * - Compares live active IDs against previously saved unread IDs so Nova knows what is
         *   brand new right now vs already saved/read vs cleared from the shade.
         * - Automatically requests Android to rebind the listener if permission is granted after an update.
         */
        fun reconcileActiveNotificationsWithArchive(
            ftsHelper: LocalFtsIndexHelper,
            nowMs: Long = System.currentTimeMillis(),
            includeGeneralActive: Boolean = false,
            context: Context? = null
        ): LiveNotificationReconciliation {
            val serviceRebinding = context != null && requestRebindIfNeeded(context)
            val savedBefore = ftsHelper.getArchivedNotificationStateMap()
            val liveChecked = testActiveNotificationsProvider != null || instance != null
            val liveAlerts = if (liveChecked) {
                getCurrentActiveNotificationsSnapshot(includeGeneralActive = includeGeneralActive)
            } else {
                synchronized(recentAlertsLock) { recentImportantAlertsList.toList() }
            }

            var newlySaved = 0
            var alreadySavedUnread = 0
            var alreadyRead = 0
            val liveIds = LinkedHashSet<String>()

            liveAlerts.forEach { alert ->
                liveIds.add(alert.id)
                val existingReadState = savedBefore[alert.id]
                when (existingReadState) {
                    null -> newlySaved++
                    true -> alreadyRead++
                    false -> alreadySavedUnread++
                }
                ftsHelper.upsertNotificationArchive(
                    alert = alert,
                    markRead = false,
                    nowMs = nowMs,
                    indexFts = true
                )
            }

            val savedUnreadNotInShade = if (liveChecked) {
                savedBefore.entries.count { (id, isRead) -> !isRead && id !in liveIds }
            } else {
                0
            }

            val audit = ftsHelper.runBackgroundNotificationAudit(nowMs).also { snapshot ->
                _unreadNotificationCount.value = snapshot.unreadTotal
                _latestAudit.value = snapshot
            }

            val liveActiveRecords = if (liveIds.isNotEmpty()) {
                ftsHelper.getArchivedNotificationsByIds(liveIds)
            } else {
                emptyList()
            }

            return LiveNotificationReconciliation(
                liveChecked = liveChecked,
                serviceRebinding = serviceRebinding,
                liveActiveRecords = liveActiveRecords,
                newlySavedCount = newlySaved,
                alreadySavedUnreadCount = alreadySavedUnread,
                alreadyReadCount = alreadyRead,
                previouslySavedUnreadNotInShadeCount = savedUnreadNotInShade,
                audit = audit
            )
        }

        fun syncActiveNotificationsToArchive(
            ftsHelper: LocalFtsIndexHelper,
            nowMs: Long = System.currentTimeMillis(),
            context: Context? = null
        ): LocalFtsIndexHelper.NotificationAuditSnapshot {
            return reconcileActiveNotificationsWithArchive(
                ftsHelper = ftsHelper,
                nowMs = nowMs,
                includeGeneralActive = false,
                context = context
            ).audit
        }

        fun updatePublishedAudit(audit: LocalFtsIndexHelper.NotificationAuditSnapshot) {
            _unreadNotificationCount.value = audit.unreadTotal
            _latestAudit.value = audit
        }

        @Volatile
        internal var testOpenNotificationOverride: ((alertId: String, packageName: String?) -> Boolean)? = null

        fun openNotificationAlert(context: Context, alert: ImportantNotificationAlert): Boolean =
            openNotificationByIdOrPackage(context, alert.id, alert.packageName)

        fun openNotificationByIdOrPackage(
            context: Context,
            alertId: String,
            packageName: String?
        ): Boolean {
            testOpenNotificationOverride?.let { override ->
                return override(alertId, packageName)
            }
            val pi = contentIntents[alertId]
            if (pi != null) {
                val sent = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        val opts = ActivityOptions.makeBasic()
                            .setPendingIntentBackgroundActivityStartMode(
                                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                            )
                            .toBundle()
                        pi.send(context, 0, null, null, null, null, opts)
                    } else {
                        pi.send()
                    }
                    true
                }.getOrElse {
                    runCatching {
                        pi.send()
                        true
                    }.getOrDefault(false)
                }
                if (sent) return true
            }
            if (!packageName.isNullOrBlank()) {
                val launch = context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (launch != null && runCatching { context.startActivity(launch) }.isSuccess) {
                    return true
                }
            }
            return false
        }

        private fun recordRecentAlert(alert: ImportantNotificationAlert) {
            synchronized(recentAlertsLock) {
                recentImportantAlertsList.removeAll { it.id == alert.id }
                recentImportantAlertsList.addFirst(alert)
                while (recentImportantAlertsList.size > 24) {
                    recentImportantAlertsList.removeLast()
                }
            }
        }
    }
}
