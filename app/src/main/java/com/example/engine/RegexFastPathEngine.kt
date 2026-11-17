package com.example.engine

import com.example.data.SafetyTier
import com.example.data.ToolCallRequest

/**
 * Tier 1: Deterministic Regex Fast-Path (< 5 ms, Process A).
 * Executes high-frequency hardware, clock, media, call, and smart-home triggers
 * immediately in Process A without waking Process B (:llm).
 */
object RegexFastPathEngine {

    data class FastPathMatch(
        val skillId: String,
        val toolCall: ToolCallRequest,
        val summary: String
    )

    private val hardwareToggleRegex =
        Regex("^(turn on|turn off|toggle) (?:the )?(flashlight|torch|wifi|wi-fi|bluetooth|dnd|do not disturb)$", RegexOption.IGNORE_CASE)

    private val levelRegex =
        Regex("^set (?:the )?(volume|brightness) to (\\d{1,3})%?$", RegexOption.IGNORE_CASE)

    private val muteRegex =
        Regex("^(mute|unmute)(?: the)? phone$", RegexOption.IGNORE_CASE)

    private val timerRegex =
        Regex("^set (?:a )?timer for (.+)$", RegexOption.IGNORE_CASE)

    private val alarmRegex =
        Regex("^set (?:an )?alarm for (.+)$", RegexOption.IGNORE_CASE)

    private val clockModifyRegex =
        Regex("^(stop|snooze|cancel) (?:the )?(alarm|timer)$", RegexOption.IGNORE_CASE)

    private val mediaRegex =
        Regex("^(play|pause|resume|next|skip|previous)(?: (?:a |some |the )?(?:track|song|music))?$", RegexOption.IGNORE_CASE)

    private val openAndPlayRegex =
        Regex("^open (?:the )?(.+?) (?:app )?(?:and |to )?play (?:the |a |some )?(.+)$", RegexOption.IGNORE_CASE)

    private val playInAppRegex =
        Regex("^play (.+?) (?:on|in|using) (?:the )?(.+?)(?: app)?$", RegexOption.IGNORE_CASE)

    private val searchInAppRegex =
        Regex("^search (?:for )?(.+?) (?:on|in) (?:the )?(.+?)(?: app)?$", RegexOption.IGNORE_CASE)

    private val navigateToRegex =
        Regex("^(?:navigate|directions|drive|take me) to (.+)$", RegexOption.IGNORE_CASE)

    private val appSkillsRegex =
        Regex("^(?:what can|how do i use|how do you use|skills for) (.+?)(?: do)?$", RegexOption.IGNORE_CASE)

    private val callRegex =
        Regex("^call ([a-zA-Z0-9 ._-]+)$", RegexOption.IGNORE_CASE)

    private val timeDateRegex =
        Regex("^(?:what (?:time|day|date) is it|tell me the (?:time|date)|current (?:time|date))$", RegexOption.IGNORE_CASE)

    private val batteryRegex =
        Regex("^(?:check |what is (?:my )?)?battery(?: level| status| percent)?$", RegexOption.IGNORE_CASE)

    private val listAddRegex =
        Regex("^add (.+?) to (?:my )?(shopping|todo|to-do) list$", RegexOption.IGNORE_CASE)

    private val listReadRegex =
        Regex("^(?:read|show|check|what is on) (?:my )?(shopping|todo|to-do) list$", RegexOption.IGNORE_CASE)

    private val listAppsRegex =
        Regex("^(?:what apps|list apps|which apps|show installed apps|list installed apps)(?: do i have| are installed)?$", RegexOption.IGNORE_CASE)

    private val launchAppRegex =
        Regex("^open (?:the )?([a-zA-Z0-9 ._-]{2,32})(?: app)?$", RegexOption.IGNORE_CASE)

    private val osNavHomeRegex =
        Regex("^(?:go |navigate |return )?(?:to )?home(?: screen)?$", RegexOption.IGNORE_CASE)

    private val osNavBackRegex =
        Regex("^(?:go |navigate )?back$", RegexOption.IGNORE_CASE)

    private val osRecentsRegex =
        Regex("^(?:show |open )?(?:recent apps|recents|app switcher)$", RegexOption.IGNORE_CASE)

    private val osNotificationsRegex =
        Regex("^(?:open |show |pull down )?(?:my )?notifications(?: shade| panel)?$", RegexOption.IGNORE_CASE)

    private val osQuickSettingsRegex =
        Regex("^(?:open |show |pull down )?quick settings$", RegexOption.IGNORE_CASE)

    private val osScreenshotRegex =
        Regex("^take (?:a )?screenshot$", RegexOption.IGNORE_CASE)

    private val osLockScreenRegex =
        Regex("^lock (?:the |my )?(?:phone|screen)$", RegexOption.IGNORE_CASE)

    fun match(command: String): FastPathMatch? {
        val clean = command.trim().trimEnd('.', '!', '?')

        hardwareToggleRegex.find(clean)?.let { m ->
            val actionRaw = m.groupValues[1].lowercase()
            val state = when {
                actionRaw.contains("on") -> "on"
                actionRaw.contains("off") -> "off"
                else -> "toggle"
            }
            val feature = when (m.groupValues[2].lowercase()) {
                "torch", "flashlight" -> "flashlight"
                "wi-fi", "wifi" -> "wifi"
                "do not disturb", "dnd" -> "dnd"
                else -> "bluetooth"
            }
            return FastPathMatch(
                skillId = "device-hardware-control",
                toolCall = ToolCallRequest(
                    toolName = "set_hardware_state",
                    args = mapOf("feature" to feature, "state" to state),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Hardware $feature -> $state"
            )
        }

        levelRegex.find(clean)?.let { m ->
            val target = m.groupValues[1].lowercase()
            val percent = m.groupValues[2].toIntOrNull()?.coerceIn(0, 100) ?: 50
            return FastPathMatch(
                skillId = "device-hardware-control",
                toolCall = ToolCallRequest(
                    toolName = "set_level",
                    args = mapOf("target" to target, "percent" to percent.toString()),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Set $target to $percent%"
            )
        }

        muteRegex.find(clean)?.let { m ->
            val state = if (m.groupValues[1].equals("mute", ignoreCase = true)) "on" else "off"
            return FastPathMatch(
                skillId = "device-hardware-control",
                toolCall = ToolCallRequest(
                    toolName = "set_hardware_state",
                    args = mapOf("feature" to "mute", "state" to state),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = if (state == "on") "Phone muted" else "Phone unmuted"
            )
        }

        timerRegex.find(clean)?.let { m ->
            val durationSeconds = parseDurationSeconds(m.groupValues[1])
            return FastPathMatch(
                skillId = "clock-alarms-timers",
                toolCall = ToolCallRequest(
                    toolName = "create_timer",
                    args = mapOf("duration_sec" to durationSeconds.toString(), "label" to "Nova Timer"),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Timer started (${durationSeconds}s)"
            )
        }

        alarmRegex.find(clean)?.let { m ->
            val time24 = parseTimeTo24h(m.groupValues[1])
            return FastPathMatch(
                skillId = "clock-alarms-timers",
                toolCall = ToolCallRequest(
                    toolName = "create_alarm",
                    args = mapOf("time_24h" to time24, "label" to "Nova Alarm"),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Alarm scheduled for $time24"
            )
        }

        clockModifyRegex.find(clean)?.let { m ->
            val action = m.groupValues[1].lowercase()
            val itemType = m.groupValues[2].lowercase()
            return FastPathMatch(
                skillId = "clock-alarms-timers",
                toolCall = ToolCallRequest(
                    toolName = "modify_clock_item",
                    args = mapOf("item_id" to "${itemType}_01", "action" to action),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "${action.replaceFirstChar { it.uppercase() }} $itemType"
            )
        }

        mediaRegex.find(clean)?.let { m ->
            val cmd = when (val raw = m.groupValues[1].lowercase()) {
                "resume" -> "play"
                "skip" -> "next"
                else -> raw
            }
            return FastPathMatch(
                skillId = "media-playback-control",
                toolCall = ToolCallRequest(
                    toolName = "media_transport",
                    args = mapOf("command" to cmd),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Media $cmd"
            )
        }

        callRegex.find(clean)?.let { m ->
            val targetName = m.groupValues[1].trim()
            if (targetName.lowercase() !in setOf("back", "it")) {
                return FastPathMatch(
                    skillId = "phone-calls-manager",
                    toolCall = ToolCallRequest(
                        toolName = "place_call",
                        args = mapOf("contact_id" to targetName.take(24), "speakerphone" to "false"),
                        safetyTier = SafetyTier.GREEN
                    ),
                    summary = "Calling $targetName"
                )
            }
        }

        timeDateRegex.find(clean)?.let {
            return FastPathMatch(
                skillId = "alexa-utility-briefings",
                toolCall = ToolCallRequest(
                    toolName = "get_device_briefing",
                    args = mapOf("topic" to "time_date"),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Checked live time & date"
            )
        }

        batteryRegex.find(clean)?.let {
            return FastPathMatch(
                skillId = "alexa-utility-briefings",
                toolCall = ToolCallRequest(
                    toolName = "get_device_briefing",
                    args = mapOf("topic" to "battery"),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Checked battery status"
            )
        }

        listAddRegex.find(clean)?.let { m ->
            val item = m.groupValues[1].trim()
            val listName = if (m.groupValues[2].lowercase().contains("shop")) "shopping" else "todo"
            return FastPathMatch(
                skillId = "alexa-utility-briefings",
                toolCall = ToolCallRequest(
                    toolName = "manage_list_item",
                    args = mapOf("list_name" to listName, "action" to "add", "item" to item.take(64)),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Added '$item' to $listName list"
            )
        }

        listReadRegex.find(clean)?.let { m ->
            val listName = if (m.groupValues[1].lowercase().contains("shop")) "shopping" else "todo"
            return FastPathMatch(
                skillId = "alexa-utility-briefings",
                toolCall = ToolCallRequest(
                    toolName = "manage_list_item",
                    args = mapOf("list_name" to listName, "action" to "list"),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Read $listName list"
            )
        }

        listAppsRegex.find(clean)?.let {
            return FastPathMatch(
                skillId = "app-knowledge-controller",
                toolCall = ToolCallRequest(
                    toolName = "list_installed_apps",
                    args = mapOf("filter" to "all"),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Listed installed apps"
            )
        }

        openAndPlayRegex.find(clean)?.let { m ->
            val appName = m.groupValues[1].trim()
            val query = m.groupValues[2].trim()
            return FastPathMatch(
                skillId = "app-media-streaming",
                toolCall = ToolCallRequest(
                    toolName = "play_in_app",
                    args = mapOf("app_name" to appName, "query" to query, "media_type" to "song"),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Playing in $appName"
            )
        }

        playInAppRegex.find(clean)?.let { m ->
            val query = m.groupValues[1].trim()
            val appName = m.groupValues[2].trim()
            return FastPathMatch(
                skillId = "app-media-streaming",
                toolCall = ToolCallRequest(
                    toolName = "play_in_app",
                    args = mapOf("app_name" to appName, "query" to query),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Playing in $appName"
            )
        }

        searchInAppRegex.find(clean)?.let { m ->
            val query = m.groupValues[1].trim()
            val appName = m.groupValues[2].trim()
            return FastPathMatch(
                skillId = "app-search-and-action",
                toolCall = ToolCallRequest(
                    toolName = "search_in_app",
                    args = mapOf("app_name" to appName, "query" to query),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Searching $appName"
            )
        }

        navigateToRegex.find(clean)?.let { m ->
            val dest = m.groupValues[1].trim()
            return FastPathMatch(
                skillId = "app-search-and-action",
                toolCall = ToolCallRequest(
                    toolName = "navigate_in_maps",
                    args = mapOf("destination" to dest),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Starting directions"
            )
        }

        appSkillsRegex.find(clean)?.let { m ->
            val appName = m.groupValues[1].trim()
            if (appName.lowercase() !in setOf("i", "you", "nova")) {
                return FastPathMatch(
                    skillId = "app-knowledge-controller",
                    toolCall = ToolCallRequest(
                        toolName = "get_app_skills",
                        args = mapOf("app_name" to appName),
                        safetyTier = SafetyTier.GREEN
                    ),
                    summary = "Checked $appName skills"
                )
            }
        }

        launchAppRegex.find(clean)?.let { m ->
            val target = m.groupValues[1].trim()
            if (target.lowercase() !in setOf("settings", "my messages", "the screen", "notifications", "quick settings", "recent apps", "recents")) {
                return FastPathMatch(
                    skillId = "app-knowledge-controller",
                    toolCall = ToolCallRequest(
                        toolName = "launch_app",
                        args = mapOf("app_name" to target),
                        safetyTier = SafetyTier.GREEN
                    ),
                    summary = "Opening $target"
                )
            }
        }

        osNavHomeRegex.find(clean)?.let {
            return FastPathMatch(
                skillId = "ui-autonomous-navigator",
                toolCall = ToolCallRequest(
                    toolName = "ui_act",
                    args = mapOf("action" to "HOME", "node_id" to ""),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Going to Home screen"
            )
        }

        osNavBackRegex.find(clean)?.let {
            return FastPathMatch(
                skillId = "ui-autonomous-navigator",
                toolCall = ToolCallRequest(
                    toolName = "ui_act",
                    args = mapOf("action" to "BACK", "node_id" to ""),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Going back"
            )
        }

        osRecentsRegex.find(clean)?.let {
            return FastPathMatch(
                skillId = "ui-autonomous-navigator",
                toolCall = ToolCallRequest(
                    toolName = "ui_act",
                    args = mapOf("action" to "RECENTS", "node_id" to ""),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Opening recent apps"
            )
        }

        osNotificationsRegex.find(clean)?.let {
            return FastPathMatch(
                skillId = "ui-autonomous-navigator",
                toolCall = ToolCallRequest(
                    toolName = "ui_act",
                    args = mapOf("action" to "NOTIFICATIONS", "node_id" to ""),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Opening notifications"
            )
        }

        osQuickSettingsRegex.find(clean)?.let {
            return FastPathMatch(
                skillId = "ui-autonomous-navigator",
                toolCall = ToolCallRequest(
                    toolName = "ui_act",
                    args = mapOf("action" to "QUICK_SETTINGS", "node_id" to ""),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Opening quick settings"
            )
        }

        osScreenshotRegex.find(clean)?.let {
            return FastPathMatch(
                skillId = "ui-autonomous-navigator",
                toolCall = ToolCallRequest(
                    toolName = "ui_act",
                    args = mapOf("action" to "SCREENSHOT", "node_id" to ""),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Taking screenshot"
            )
        }

        osLockScreenRegex.find(clean)?.let {
            return FastPathMatch(
                skillId = "ui-autonomous-navigator",
                toolCall = ToolCallRequest(
                    toolName = "ui_act",
                    args = mapOf("action" to "LOCK_SCREEN", "node_id" to ""),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Locking screen"
            )
        }

        return null
    }

    fun parseDurationSeconds(expr: String): Int {
        val lower = expr.lowercase()
        val num = Regex("(\\d+)").find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: 5
        return when {
            lower.contains("hour") || lower.contains("hr") -> num * 3600
            lower.contains("sec") -> num
            else -> num * 60
        }
    }

    fun parseTimeTo24h(expr: String): String {
        val lower = expr.lowercase().trim()
        val match = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?").find(lower)
        if (match != null) {
            var hour = match.groupValues[1].toIntOrNull() ?: 7
            val min = match.groupValues[2].toIntOrNull() ?: 0
            val ampm = match.groupValues[3]
            if (ampm == "pm" && hour < 12) hour += 12
            if (ampm == "am" && hour == 12) hour = 0
            return "%02d:%02d".format(hour.coerceIn(0, 23), min.coerceIn(0, 59))
        }
        return "07:30"
    }
}
