package com.example.engine

import android.app.Notification
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Classifies incoming Android notifications so Nova ONLY prompts and reads
 * high-signal important alerts: messages, missed calls, and reminders/events.
 * Filters out ongoing media/progress bars, promos, social likes, downloads, and system noise.
 */
object ImportantNotificationFilter {

    enum class ImportantNotificationType(val label: String) {
        MESSAGE("Message"),
        MISSED_CALL("Missed Call"),
        REMINDER("Reminder"),
        GENERAL("Notification")
    }

    enum class NotificationImportanceTier(val label: String) {
        CRITICAL("Critical"),
        HIGH("High"),
        NORMAL("Normal")
    }

    data class NotificationImportanceAssessment(
        val score: Int,
        val tier: NotificationImportanceTier,
        val flagReasons: List<String>
    )

    data class RawNotificationSignals(
        val key: String,
        val packageName: String,
        val ownPackageName: String,
        val appLabel: String,
        val category: String?,
        val title: String,
        val text: String,
        val subText: String = "",
        val bigText: String = "",
        val template: String = "",
        val hasMessagingMessages: Boolean = false,
        val hasMediaSession: Boolean = false,
        val flags: Int = 0,
        val hasProgress: Boolean = false,
        val channelId: String = "",
        val postTimeMs: Long = System.currentTimeMillis()
    )

    data class ImportantNotificationAlert(
        val id: String,
        val key: String,
        val packageName: String,
        val appLabel: String,
        val type: ImportantNotificationType,
        val title: String,
        val body: String,
        val timestampMs: Long,
        val spokenPrompt: String,
        val shortLabel: String,
        val importanceScore: Int = 60,
        val importanceTier: NotificationImportanceTier = NotificationImportanceTier.NORMAL,
        val flagReasons: List<String> = emptyList()
    )

    const val NOVA_REMINDER_CHANNEL_ID = "nova_reminder_channel"

    private val noisyCategories = setOf(
        Notification.CATEGORY_TRANSPORT,
        Notification.CATEGORY_PROGRESS,
        Notification.CATEGORY_SERVICE,
        Notification.CATEGORY_SYSTEM,
        Notification.CATEGORY_STATUS,
        Notification.CATEGORY_NAVIGATION,
        Notification.CATEGORY_PROMO,
        Notification.CATEGORY_RECOMMENDATION
    )

    private val noisyPackages = setOf(
        "com.android.systemui",
        "com.android.vending",
        "com.android.providers.downloads",
        "com.android.providers.downloads.ui",
        "com.google.android.providers.media.module",
        "com.google.android.gms",
        "com.android.settings",
        "com.android.bluetooth",
        "com.google.android.apps.wellbeing",
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "com.spotify.music"
    )

    private val messagingPackages = setOf(
        "com.google.android.apps.messaging",
        "com.android.mms",
        "com.samsung.android.messaging",
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.facebook.orca",
        "org.telegram.messenger",
        "org.telegram.plus",
        "com.viber.voip",
        "org.thoughtcrime.securesms",
        "com.discord",
        "com.Slack",
        "com.microsoft.teams",
        "com.google.android.apps.dynamite",
        "jp.naver.line.android",
        "com.skype.raider"
    )

    private val dialerPackages = setOf(
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.android.server.telecom",
        "com.android.incallui"
    )

    private val reminderPackages = setOf(
        "com.google.android.calendar",
        "com.samsung.android.calendar",
        "com.android.calendar",
        "com.google.android.apps.tasks",
        "com.google.android.keep",
        "com.google.android.deskclock",
        "com.android.deskclock",
        "com.todoist",
        "com.microsoft.todos",
        "com.ticktick.task",
        "com.anydo"
    )

    private val promoOrNoisePhrases = listOf(
        "% off",
        "flash sale",
        "promo code",
        "special offer",
        "deal of the day",
        "limited time offer",
        "subscribe now",
        "recommended for you",
        "trending now",
        "watch now",
        "is live now",
        "liked your photo",
        "liked your post",
        "started following you",
        "usb debugging",
        "charging this device",
        "tap for more options",
        "checking for new messages",
        "whatsapp web is currently active",
        "syncing messages",
        "finish setting up",
        "battery is ",
        "software update"
    )

    fun classify(signals: RawNotificationSignals): ImportantNotificationAlert? {
        val pkg = signals.packageName.trim()
        if (pkg.isEmpty()) return null

        val isOwnPackage = pkg == signals.ownPackageName
        val isOwnReminder = isOwnPackage && signals.channelId == NOVA_REMINDER_CHANNEL_ID
        if (isOwnPackage && !isOwnReminder) return null

        // Never prompt on group summary banners, ongoing background notifications, media controls, or progress bars
        if ((signals.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return null
        if ((signals.flags and Notification.FLAG_ONGOING_EVENT) != 0) return null
        if ((signals.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0) return null
        if (signals.hasMediaSession || signals.hasProgress) return null

        val cat = signals.category
        if (cat != null && cat in noisyCategories) return null

        val title = signals.title.trim()
        val body = signals.bigText.trim().ifEmpty { signals.text.trim() }
        if (title.isEmpty() && body.isEmpty()) return null

        val combinedLower = "$title $body ${signals.subText}".lowercase()

        if (promoOrNoisePhrases.any { combinedLower.contains(it) }) {
            return null
        }

        val isMissedCall = cat == Notification.CATEGORY_MISSED_CALL ||
            ((pkg in dialerPackages || cat == Notification.CATEGORY_CALL) && (
                combinedLower.contains("missed call") ||
                    combinedLower.contains("missed a call") ||
                    combinedLower.contains("missed audio call") ||
                    combinedLower.contains("missed video call") ||
                    combinedLower.contains("unanswered call")
                )) ||
            (combinedLower.contains("missed call") && !combinedLower.contains("game"))

        val isReminder = isOwnReminder ||
            cat == Notification.CATEGORY_REMINDER ||
            cat == Notification.CATEGORY_EVENT ||
            cat == Notification.CATEGORY_ALARM ||
            (pkg in reminderPackages) ||
            (
                cat != Notification.CATEGORY_SOCIAL &&
                    pkg !in noisyPackages &&
                    (
                        title.startsWith("reminder", ignoreCase = true) ||
                            combinedLower.contains("reminder:") ||
                            combinedLower.contains("upcoming event") ||
                            combinedLower.contains("starting in ") ||
                            combinedLower.contains("due now")
                        )
                )

        if (pkg in noisyPackages && !isMissedCall && !isReminder) {
            return null
        }

        val isMessage = cat == Notification.CATEGORY_MESSAGE ||
            signals.hasMessagingMessages ||
            signals.template.contains("MessagingStyle", ignoreCase = true) ||
            (pkg in messagingPackages && cat != Notification.CATEGORY_CALL)

        val resolvedType = when {
            isMissedCall -> ImportantNotificationType.MISSED_CALL
            isReminder -> ImportantNotificationType.REMINDER
            isMessage -> ImportantNotificationType.MESSAGE
            else -> return null
        }

        val appDisplay = resolveFriendlyAppName(signals.appLabel, pkg)
        val cleanTitle = sanitizeNotificationText(title, appDisplay, pkg).ifEmpty { appDisplay }
        val cleanBody = sanitizeNotificationText(body, appDisplay, pkg).take(180)
        val alertId = "imp_${(signals.key + "|" + cleanTitle + "|" + cleanBody).hashCode() and 0x7FFFFFFF}"

        val spokenPrompt = when (resolvedType) {
            ImportantNotificationType.MISSED_CALL -> {
                val caller = cleanTitle
                    .replace(Regex("missed call(?: from)?", RegexOption.IGNORE_CASE), "")
                    .trim()
                    .ifEmpty { cleanBody }
                    .ifEmpty { "an unknown caller" }
                "Important notification: You missed a call from $caller. Would you like me to call back?"
            }
            ImportantNotificationType.REMINDER -> {
                val detail = if (cleanBody.isNotEmpty() && !cleanBody.equals(cleanTitle, ignoreCase = true)) {
                    "$cleanTitle, $cleanBody"
                } else {
                    cleanTitle
                }
                "Reminder alert: $detail."
            }
            ImportantNotificationType.MESSAGE -> {
                val excerpt = if (cleanBody.isNotEmpty()) ": ${cleanBody.take(110)}" else ""
                "New message from $cleanTitle on $appDisplay$excerpt."
            }
            ImportantNotificationType.GENERAL -> {
                val excerpt = if (cleanBody.isNotEmpty() && !cleanBody.equals(cleanTitle, ignoreCase = true)) {
                    ": ${cleanBody.take(110)}"
                } else ""
                "Notification from $appDisplay: $cleanTitle$excerpt."
            }
        }

        val shortLabel = when (resolvedType) {
            ImportantNotificationType.MISSED_CALL -> "Missed Call • $cleanTitle"
            ImportantNotificationType.REMINDER -> "Reminder • $cleanTitle"
            ImportantNotificationType.MESSAGE -> "$appDisplay • $cleanTitle"
            ImportantNotificationType.GENERAL -> "$appDisplay • $cleanTitle"
        }

        val importance = assessImportance(
            type = resolvedType,
            title = cleanTitle,
            body = cleanBody,
            appLabel = appDisplay,
            hasMessagingMessages = signals.hasMessagingMessages
        )

        return ImportantNotificationAlert(
            id = alertId,
            key = signals.key,
            packageName = pkg,
            appLabel = appDisplay,
            type = resolvedType,
            title = cleanTitle,
            body = cleanBody,
            timestampMs = signals.postTimeMs,
            spokenPrompt = spokenPrompt,
            shortLabel = shortLabel,
            importanceScore = importance.score,
            importanceTier = importance.tier,
            flagReasons = importance.flagReasons
        )
    }

    /**
     * Classifies ANY real user-facing notification currently sitting in the notification shade
     * when the user explicitly asks to "read the current notification(s)".
     * Important notifications (messages, missed calls, reminders) keep their full priority;
     * other real app alerts (email, deliveries, banking, calendar, etc.) are returned as GENERAL,
     * while ongoing background services, media players, progress bars, and group headers stay filtered.
     */
    fun classifyActiveForUserQuery(
        signals: RawNotificationSignals,
        allowStandaloneSummary: Boolean = false
    ): ImportantNotificationAlert? {
        classify(signals)?.let { return it }

        val pkg = signals.packageName.trim()
        if (pkg.isEmpty()) return null
        val isOwnPackage = pkg == signals.ownPackageName
        val isOwnReminder = isOwnPackage && signals.channelId == NOVA_REMINDER_CHANNEL_ID
        if (isOwnPackage && !isOwnReminder) return null

        if (!allowStandaloneSummary && (signals.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return null
        if ((signals.flags and Notification.FLAG_ONGOING_EVENT) != 0) return null
        if ((signals.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0) return null
        if (signals.hasMediaSession || signals.hasProgress) return null

        val nonReadableSystemCategories = setOf(
            Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_SYSTEM,
            Notification.CATEGORY_STATUS
        )
        if (signals.category != null && signals.category in nonReadableSystemCategories) return null

        val title = signals.title.trim()
        val body = signals.bigText.trim().ifEmpty { signals.text.trim() }
        if (title.isEmpty() && body.isEmpty()) return null

        val combinedLower = "$title $body ${signals.subText}".lowercase()
        val systemNoisePhrases = listOf(
            "usb debugging",
            "charging this device",
            "tap for more options",
            "checking for new messages",
            "syncing messages",
            "whatsapp web is currently active"
        )
        if (systemNoisePhrases.any { combinedLower.contains(it) }) return null

        val appDisplay = resolveFriendlyAppName(signals.appLabel, pkg)
        val cleanTitle = sanitizeNotificationText(title, appDisplay, pkg).ifEmpty { appDisplay }
        val cleanBody = sanitizeNotificationText(body, appDisplay, pkg).take(180)
        val alertId = "cur_${(signals.key + "|" + cleanTitle + "|" + cleanBody).hashCode() and 0x7FFFFFFF}"
        val excerpt = if (cleanBody.isNotEmpty() && !cleanBody.equals(cleanTitle, ignoreCase = true)) {
            ": ${cleanBody.take(110)}"
        } else ""
        val importance = assessImportance(
            type = ImportantNotificationType.GENERAL,
            title = cleanTitle,
            body = cleanBody,
            appLabel = appDisplay,
            hasMessagingMessages = false
        )

        return ImportantNotificationAlert(
            id = alertId,
            key = signals.key,
            packageName = pkg,
            appLabel = appDisplay,
            type = ImportantNotificationType.GENERAL,
            title = cleanTitle,
            body = cleanBody,
            timestampMs = signals.postTimeMs,
            spokenPrompt = "Notification from $appDisplay: $cleanTitle$excerpt.",
            shortLabel = "$appDisplay • $cleanTitle",
            importanceScore = importance.score,
            importanceTier = importance.tier,
            flagReasons = importance.flagReasons
        )
    }

    private val criticalUrgencyKeywords = listOf(
        "urgent", "emergency", "asap", "call me", "911", "verification code",
        "otp", "security code", "2fa", "fraud", "overdue", "flight", "boarding",
        "gate", "deadline", "hospital", "doctor"
    )

    private val highActionKeywords = listOf(
        "today", "tonight", "tomorrow", "meeting", "appointment", "schedule",
        "deliver", "delivered", "arrived", "package", "pickup", "invoice",
        "payment", "bill", "due", "confirm", "reply", "waiting", "where are"
    )

    private val priorityCallerKeywords = listOf(
        "urgent", "emergency", "mom", "dad", "mother", "father", "wife",
        "husband", "boss", "doctor", "hospital", "clinic", "school"
    )

    fun assessImportance(
        type: ImportantNotificationType,
        title: String,
        body: String,
        appLabel: String = "",
        hasMessagingMessages: Boolean = false
    ): NotificationImportanceAssessment {
        val combined = "$title $body $appLabel".lowercase()
        val reasons = mutableListOf<String>()
        var score: Int

        when (type) {
            ImportantNotificationType.MISSED_CALL -> {
                score = 88
                reasons.add("Missed call")
                if (priorityCallerKeywords.any { combined.contains(it) }) {
                    score += 8
                    reasons.add("Priority contact")
                }
            }
            ImportantNotificationType.REMINDER -> {
                score = 80
                reasons.add("Reminder / Event")
                if (criticalUrgencyKeywords.any { combined.contains(it) } ||
                    highActionKeywords.any { combined.contains(it) }
                ) {
                    score += 10
                    reasons.add("Time-sensitive")
                }
            }
            ImportantNotificationType.MESSAGE -> {
                score = 55
                if (hasMessagingMessages) {
                    score += 5
                    reasons.add("Direct message")
                }
                if (criticalUrgencyKeywords.any { combined.contains(it) }) {
                    score += 33
                    reasons.add("Urgent / time-sensitive")
                } else if (highActionKeywords.any { combined.contains(it) } || body.contains("?")) {
                    score += 18
                    reasons.add("Action requested")
                } else if (reasons.isEmpty()) {
                    reasons.add("Direct message")
                }
            }
            ImportantNotificationType.GENERAL -> {
                score = 45
                if (criticalUrgencyKeywords.any { combined.contains(it) }) {
                    score += 35
                    reasons.add("Urgent alert")
                } else if (highActionKeywords.any { combined.contains(it) }) {
                    score += 18
                    reasons.add("Actionable alert")
                } else {
                    reasons.add("Active notification")
                }
            }
        }

        val clamped = score.coerceIn(0, 100)
        val tier = when {
            clamped >= 85 -> NotificationImportanceTier.CRITICAL
            clamped >= 68 -> NotificationImportanceTier.HIGH
            else -> NotificationImportanceTier.NORMAL
        }
        return NotificationImportanceAssessment(
            score = clamped,
            tier = tier,
            flagReasons = reasons.distinct()
        )
    }

    fun startOfDayMillis(nowMs: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    fun formatRelativeTimestamp(
        timestampMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): String {
        if (timestampMs <= 0L) return "Recent"
        val todayStart = startOfDayMillis(nowMs)
        val yesterdayStart = todayStart - 86_400_000L
        val weekStart = nowMs - 7L * 86_400_000L
        return when {
            timestampMs >= todayStart -> {
                val t = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestampMs))
                "Today at $t"
            }
            timestampMs >= yesterdayStart -> {
                val t = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestampMs))
                "Yesterday at $t"
            }
            timestampMs >= weekStart -> {
                SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(Date(timestampMs))
            }
            else -> {
                SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestampMs))
            }
        }
    }

    fun inferTypeFromTitleOrUri(
        title: String,
        body: String,
        uriOrRef: String = ""
    ): ImportantNotificationType {
        val lowerTitle = title.lowercase()
        val lowerBody = body.lowercase()
        val lowerUri = uriOrRef.lowercase()
        return when {
            lowerTitle.contains("[missed call]") || lowerTitle.contains("missed call") || lowerBody.contains("missed call") ->
                ImportantNotificationType.MISSED_CALL
            lowerTitle.contains("[reminder]") || lowerTitle.startsWith("reminder") || lowerUri.startsWith("nova://reminder") ->
                ImportantNotificationType.REMINDER
            else -> ImportantNotificationType.MESSAGE
        }
    }

    private val packageTokenRegex = Regex(
        "\\b(?:com|org|net|io|android)\\.[a-z0-9_]+(?:\\.[a-z0-9_]+)+\\b",
        RegexOption.IGNORE_CASE
    )

    private val exactPackageFormatRegex = Regex(
        "^(?:com|org|net|io|android|[a-z][a-z0-9_]*\\.[a-z0-9_]+)\\.[a-z0-9_.]+$",
        RegexOption.IGNORE_CASE
    )

    private val knownPackageAppNames = mapOf(
        "com.google.android.apps.messaging" to "Messages",
        "com.google.android.gm" to "Gmail",
        "com.google.android.calendar" to "Google Calendar",
        "com.google.android.apps.tasks" to "Google Tasks",
        "com.google.android.keep" to "Google Keep",
        "com.google.android.deskclock" to "Clock",
        "com.google.android.dialer" to "Phone",
        "com.google.android.apps.dynamite" to "Google Chat",
        "com.google.android.apps.tachyon" to "Google Meet",
        "com.google.android.apps.meetings" to "Google Meet",
        "com.google.android.googlequicksearchbox" to "Google",
        "com.google.android.gms" to "Google",
        "com.google.android.apps.wellbeing" to "Digital Wellbeing",
        "com.google.android.apps.docs" to "Google Docs",
        "com.google.android.apps.maps" to "Google Maps",
        "com.google.android.youtube" to "YouTube",
        "com.google.android.apps.youtube.music" to "YouTube Music",
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "com.facebook.orca" to "Messenger",
        "org.telegram.messenger" to "Telegram",
        "org.telegram.plus" to "Telegram",
        "com.viber.voip" to "Viber",
        "org.thoughtcrime.securesms" to "Signal",
        "com.discord" to "Discord",
        "com.slack" to "Slack",
        "com.microsoft.teams" to "Microsoft Teams",
        "jp.naver.line.android" to "LINE",
        "com.skype.raider" to "Skype",
        "com.android.mms" to "Messages",
        "com.samsung.android.messaging" to "Messages",
        "com.android.dialer" to "Phone",
        "com.samsung.android.dialer" to "Phone",
        "com.android.server.telecom" to "Phone",
        "com.android.incallui" to "Phone",
        "com.android.calendar" to "Calendar",
        "com.samsung.android.calendar" to "Calendar",
        "com.android.deskclock" to "Clock",
        "com.todoist" to "Todoist",
        "com.microsoft.todos" to "Microsoft To Do",
        "com.ticktick.task" to "TickTick",
        "com.anydo" to "Any.do"
    )

    private val ignoredPkgSegments = setOf(
        "com", "org", "net", "io", "android", "google", "apps", "app",
        "sec", "samsung", "miui", "xiaomi", "coloros", "oppo", "vivo",
        "oneplus", "user", "client", "mobile", "prod", "release"
    )

    fun looksLikePackageName(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return false
        return exactPackageFormatRegex.matches(trimmed) && trimmed.count { it == '.' } >= 2
    }

    fun resolveFriendlyAppName(rawAppLabel: String, pkg: String): String {
        val cleanedRaw = rawAppLabel.trim()
        if (cleanedRaw.isNotEmpty() &&
            !cleanedRaw.equals(pkg, ignoreCase = true) &&
            !looksLikePackageName(cleanedRaw)
        ) {
            return cleanedRaw
        }
        val normalizedPkg = pkg.trim().lowercase()
        knownPackageAppNames[normalizedPkg]?.let { return it }

        val segments = normalizedPkg.split('.').filter { it.isNotBlank() }
        val meaningful = segments.filterNot { it in ignoredPkgSegments }
        val chosen = meaningful.ifEmpty { segments.takeLast(1) }
        return chosen.joinToString(" ") { seg ->
            seg.replace('_', ' ').split(' ').filter { it.isNotBlank() }.joinToString(" ") { part ->
                part.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() }
            }
        }.ifBlank { "App" }
    }

    fun sanitizeNotificationText(
        rawText: String,
        fallbackAppLabel: String = "",
        pkg: String = ""
    ): String {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return ""
        return packageTokenRegex.replace(trimmed) { match ->
            val token = match.value
            if (pkg.isNotEmpty() && token.equals(pkg, ignoreCase = true) && fallbackAppLabel.isNotBlank()) {
                fallbackAppLabel
            } else {
                resolveFriendlyAppName("", token)
            }
        }.trim()
    }
}
