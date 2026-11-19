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
        Regex(
            "^(turn on|turn off|switch on|switch off|enable|disable|toggle) (?:the )?(flashlight|torch|wifi|wi-fi|bluetooth|dnd|do not disturb|don't disturb|do not display|don't display|do not destroy|disturb mode)$",
            RegexOption.IGNORE_CASE
        )

    private val levelRegex =
        Regex("^set (?:the )?(volume|brightness) to (\\d{1,3})%?$", RegexOption.IGNORE_CASE)

    private val relativeLevelRegex =
        Regex("^(?:turn (up|down)|increase|decrease|raise|lower) (?:the )?(volume|brightness)$|^(volume|brightness) (up|down)$|^(brighter|dimmer|louder|quieter)$", RegexOption.IGNORE_CASE)

    private val muteRegex =
        Regex("^(mute|unmute)(?: the)? phone$", RegexOption.IGNORE_CASE)

    private val timerRegex =
        Regex("^set (?:a )?timer for (.+)$", RegexOption.IGNORE_CASE)

    private val alarmRegex =
        Regex("^set (?:an )?alarm for (.+)$", RegexOption.IGNORE_CASE)

    private val clockModifyRegex =
        Regex("^(stop|snooze|cancel) (?:the )?(alarm|timer)$", RegexOption.IGNORE_CASE)

    private val mediaTransportRegex =
        Regex("^(pause|resume|next|skip|previous|prev)(?: (?:the |this |a )?(?:track|song|music|video|media))?$|^play$", RegexOption.IGNORE_CASE)

    private val playRandomRegex =
        Regex("^play (?:me )?(?:(?:a |some |any )?(?:random )?(?:music|song|songs|track|tracks|tune|tunes|audio|playlist|something)|random)(?: please)?$", RegexOption.IGNORE_CASE)

    private val openAndPlayRegex =
        Regex("^open (?:the )?(.+?) (?:app )?(?:and |to )?play (?:the |a |some )?(.+)$", RegexOption.IGNORE_CASE)

    private val playInAppRegex =
        Regex("^play (.+?) (?:on|in|using) (?:the )?(.+?)$", RegexOption.IGNORE_CASE)

    private val playSpecificRegex =
        Regex("^play (?:me )?(?:the |a )?(?:song |track )?(.+)$", RegexOption.IGNORE_CASE)

    private val openAndSearchRegex =
        Regex("^open (?:the )?(.+?) (?:app )?(?:and |to )?(?:search|find|look up) (?:for )?(.+)$", RegexOption.IGNORE_CASE)

    private val searchInAppRegex =
        Regex("^search (?:for )?(.+?) (?:on|in|inside) (?:the )?(.+?)$", RegexOption.IGNORE_CASE)

    private val searchAppForRegex =
        Regex("^search (?:in |on |inside )?(?:the )?([a-zA-Z0-9 ._-]{2,48}) (?:app )?for (.+)$", RegexOption.IGNORE_CASE)

    private val findInAppRegex =
        Regex("^(?:find|look up) (.+?) (?:on|in|inside) (?:the )?([a-zA-Z0-9 ._-]{2,48})$", RegexOption.IGNORE_CASE)

    private val localFtsSourceNouns = setOf(
        "files", "my files", "notes", "my notes", "contacts", "my contacts",
        "messages", "my messages", "screenshots", "my screenshots",
        "calendar", "my calendar", "phone", "my phone", "device", "this device",
        "local", "documents", "my documents", "storage"
    )

    private val navigateToRegex =
        Regex("^(?:navigate|directions|drive|take me) to (.+)$", RegexOption.IGNORE_CASE)

    private val appSkillsRegex =
        Regex("^(?:what can|how do i use|how do you use|skills for) (.+?)(?: do)?$", RegexOption.IGNORE_CASE)

    private val callRegex =
        Regex("^call ([a-zA-Z0-9 ._-]+)$", RegexOption.IGNORE_CASE)

    private val incomingCallRegex =
        Regex("^(answer|accept|pick up|decline|reject|end|hang up) (?:the |incoming )?call$", RegexOption.IGNORE_CASE)

    private val calendarQueryRegex =
        Regex("^(?:what is|whats|what's|check|show|read) (?:on )?(?:my )?(?:calendar|schedule|agenda)(?: today| this week)?$|^(?:do i have any|any) (?:meetings|events|appointments)(?: today| upcoming)?$", RegexOption.IGNORE_CASE)

    private val saveMemoryKeyValueRegex =
        Regex("^remember (?:that )?my ([a-zA-Z0-9 _-]{2,32}) (?:is|are|=) (.+)$", RegexOption.IGNORE_CASE)

    private val saveMemoryFactRegex =
        Regex("^remember (?:that )?(.+)$", RegexOption.IGNORE_CASE)

    private val recallMemoryRegex =
        Regex("^(?:where did i|what was my|what is my saved|recall|do you remember) (.+)$", RegexOption.IGNORE_CASE)

    private val inspectAgenticMemoryRegex =
        Regex("^(?:what (?:have you|did you) learn(?:ed)?(?: about me| about my (?:habits|processes|routines))?|what do you know about (?:me|my habits|my routines|my processes)|(?:show|list|read) (?:my )?(?:agentic memory|learned processes|learned habits|learned preferences))$", RegexOption.IGNORE_CASE)

    private val teachProcessRegex =
        Regex("^(?:learn|teach)(?: a| my)? (?:new )?(?:process|workflow|habit)(?: called| named)? (.+?)\\s*[:,-]\\s*(.+)$", RegexOption.IGNORE_CASE)

    private val runLearnedProcessRegex =
        Regex("^(?:run|start|do|execute)\\s+(?:the\\s+|my\\s+|your\\s+)?(?:learned\\s+|usual\\s+)?(.+?)\\s+(?:process|workflow|habit)$|^(?:run|do|start)\\s+(?:my\\s+|the\\s+)?(?:learned|usual)\\s+(?!routine\\b)(.+?)$", RegexOption.IGNORE_CASE)

    private val setPreferenceRegex =
        Regex("^(?:no,?\\s+(?:use|open|play on)|instead use|from now on use|i prefer(?: using)?|always use)\\s+([A-Za-z0-9 .+'&-]{2,48})$", RegexOption.IGNORE_CASE)

    private val setPersonalityRegex =
        Regex("^(?:switch|change|set)(?: your)? personality(?: mode)? to (.+)$|^be more (warm|friendly|executive|professional|witty|playful|concise|minimalist|brief|calm)$", RegexOption.IGNORE_CASE)

    private val setUserNameRegex =
        Regex("^(?:call me|my name is) ([A-Za-z][A-Za-z0-9' -]{1,20})$", RegexOption.IGNORE_CASE)

    private val forgetAgenticRegex =
        Regex("^forget (?:my )?(?:learned )?(?:preference for |preference |process |habit |memory )?(.+)$", RegexOption.IGNORE_CASE)

    private val politeLeadInRegex =
        Regex("^(?:please\\s+|kindly\\s+|can\\s+you\\s+(?:please\\s+)?|could\\s+you\\s+(?:please\\s+)?|would\\s+you\\s+(?:please\\s+)?|will\\s+you\\s+(?:please\\s+)?|i\\s+want\\s+you\\s+to\\s+)+", RegexOption.IGNORE_CASE)

    private val morningBriefingRegex =
        Regex(
            "^(?:(?:give|provide|tell|read|show|get|bring|prepare|do|start)\\s+(?:me\\s+)?(?:with\\s+)?)?(?:the\\s+|a\\s+|my\\s+)?(?:morning|afternoon|evening|tonight'?s?|tonights|night'?s?|nightly|today'?s?|todays|daily|weekly|current|latest|quick|full)\\s+(?:briefing|summary|recap|rundown|update)$|" +
                "^(?:(?:give|provide|tell|read|show|get|bring|prepare|do|start)\\s+(?:me\\s+)?(?:with\\s+)?)?(?:the\\s+|a\\s+|my\\s+)?(?:briefing|daily\\s+recap|daily\\s+summary)(?:\\s+(?:for\\s+)?(?:today|tonight|this\\s+evening|this\\s+morning|this\\s+afternoon|this\\s+week))?$|" +
                "^(?:what\\s+is|what's|whats)\\s+(?:the\\s+|my\\s+)?(?:morning|afternoon|evening|tonight'?s?|tonights|today'?s?|todays|daily|weekly)?\\s*(?:briefing|recap|summary)(?:\\s+(?:for\\s+)?(?:today|tonight|this\\s+evening|this\\s+morning|this\\s+afternoon|this\\s+week))?$|" +
                "^(?:what\\s+happened\\s+(?:this\\s+week|in\\s+the\\s+last\\s+24\\s+hours|today|tonight|this\\s+evening)|catch\\s+me\\s+up)$|" +
                "^(?:check\\s+)?connectivity\\s+status$",
            RegexOption.IGNORE_CASE
        )

    private val routineTriggerRegex =
        Regex("^(?:(good morning)(?: nova)?|(?:start |activate |enable )?(focus mode|focus routine|do not disturb routine)|(good night|bedtime(?: routine)?|start bedtime)|sync (?:my )?(phone(?: info)?|contacts|apps|calendar))$", RegexOption.IGNORE_CASE)

    private val showFloatingRoutineRegex =
        Regex("^(?:show|open|launch|enable) (?:the )?floating routine(?: control| bar| overlay)?$|^(?:open|show) routine (?:control|bar|overlay)$", RegexOption.IGNORE_CASE)

    private val hideFloatingRoutineRegex =
        Regex("^(?:hide|close|dismiss|disable) (?:the )?floating routine(?: control| bar| overlay)?$|^(?:hide|close) routine (?:control|bar|overlay)$", RegexOption.IGNORE_CASE)

    private val stopRecordingRoutineRegex =
        Regex("^stop (?:recording )?(?:the |my )?routine(?: recording)?(?: (?:called|named|as) (.+))?$", RegexOption.IGNORE_CASE)

    private val listRoutinesRegex =
        Regex(
            "^(?:(?:if\\s+)?(?:what\\s+are|what's|whats|list|show|tell\\s+me|give\\s+me|provide\\s+me(?:\\s+with)?|check)\\s+(?:all\\s+)?(?:of\\s+)?(?:the\\s+|my\\s+|your\\s+)?(?:available\\s+|recorded\\s+|saved\\s+|built[-\\s]?in\\s+|existing\\s+|custom\\s+)*routines(?:\\s+(?:available|do\\s+you\\s+have|you\\s+have|are\\s+available|i\\s+have))?)$|" +
                "^(?:what\\s+routines\\s+(?:are\\s+available|do\\s+you\\s+have|do\\s+i\\s+have|exist|can\\s+you\\s+run))$",
            RegexOption.IGNORE_CASE
        )

    private val inspectRoutineStepsRegex =
        Regex(
            "^(?:(?:provide|give|show|list|tell|read|explain|describe)(?:\\s+me)?(?:\\s+with)?\\s+)?(?:a\\s+|the\\s+|all\\s+)?(?:list\\s+of\\s+|step[-\\s]by[-\\s]step\\s+)?steps\\s+(?:in|of|for)\\s+(?:all\\s+)?(?:the\\s+|my\\s+|your\\s+|each\\s+)?(?:available\\s+|recorded\\s+|saved\\s+|built[-\\s]?in\\s+)?(.+?)?\\s*routines?$|" +
                "^(?:what\\s+are\\s+(?:the\\s+)?steps\\s+(?:in|of|for)\\s+(?:all\\s+)?(?:the\\s+|my\\s+|your\\s+)?(?:available\\s+|recorded\\s+|saved\\s+|built[-\\s]?in\\s+)?(.+?)?\\s*routines?)$|" +
                "^(?:(?:provide|give|show|list|tell|read|explain|describe)(?:\\s+me)?(?:\\s+with)?\\s+)?(?:the\\s+|all\\s+)?(?:step[-\\s]by[-\\s]step\\s+(?:routines?|routine\\s+steps)|(?:available\\s+|my\\s+|all\\s+)?routines?\\s+(?:with\\s+)?(?:their\\s+)?(?:step[-\\s]by[-\\s]step|steps))$|" +
                "^(?:what\\s+does|explain|describe|show\\s+(?:me\\s+)?(?:the\\s+)?steps\\s+(?:in|of|for))\\s+(?:the\\s+|my\\s+)?(.+?)\\s+routine(?:\\s+do)?$",
            RegexOption.IGNORE_CASE
        )

    private val recordRoutineRegex =
        Regex("^(?:start )?record(?:ing)? (?:a )?(?:new )?(?:(global) )?routine(?: (?:called|named) (.+?))?(?: (?:for|in|on) (?:the )?(?:app )?(.+?))?$", RegexOption.IGNORE_CASE)

    private val runRecordedRoutineRegex =
        Regex("^(?:run|start|execute|play|trigger) (?:the |my )?(?:recorded |saved )?routine(?: (?:called|named))? (.+)$", RegexOption.IGNORE_CASE)

    private val batteryRegex =
        Regex("^(?:check |what is (?:my )?)?battery(?: level| status| percent)?$", RegexOption.IGNORE_CASE)

    private val listAddRegex =
        Regex("^add (.+?) to (?:my )?(shopping|todo|to-do) list$", RegexOption.IGNORE_CASE)

    private val listReadRegex =
        Regex("^(?:read|show|check|what is on) (?:my )?(shopping|todo|to-do) list$", RegexOption.IGNORE_CASE)

    private val listAppsRegex =
        Regex("^(?:what apps|list apps|which apps|show installed apps|list installed apps)(?: do i have| are installed)?$", RegexOption.IGNORE_CASE)

    private val openSystemSettingsRegex =
        Regex(
            "^(?:open|show|launch|go to) (?:the )?(?:(bluetooth|wifi|wi-fi|location|display|sound|battery|accessibility)(?: settings| panel| page)?|(notifications?) (?:settings|page))$",
            RegexOption.IGNORE_CASE
        )

    private val launchAppRegex =
        Regex("^open (?:the )?([a-zA-Z0-9 ._-]{2,48})(?: app)?$", RegexOption.IGNORE_CASE)

    private val osNavHomeRegex =
        Regex("^(?:go |navigate |return )?(?:to )?home(?: screen)?$", RegexOption.IGNORE_CASE)

    private val osNavBackRegex =
        Regex("^(?:go |navigate )?back$", RegexOption.IGNORE_CASE)

    private val osRecentsRegex =
        Regex("^(?:show |open )?(?:recent apps|recents|app switcher)$", RegexOption.IGNORE_CASE)

    private val osNotificationsRegex =
        Regex("^(?:open |show |pull down )?(?:my )?notifications(?: shade| panel)?$", RegexOption.IGNORE_CASE)

    private val readImportantNotificationsRegex =
        Regex(
            "^(?:read|check|summarize|tell|give|provide|list|show|get|bring)(?:\\s+me)?(?:\\s+about)?\\s+(?:all\\s+)?(?:of\\s+)?(?:my\\s+|the\\s+|any\\s+)?(?:most\\s+|top\\s+|highest\\s+priority\\s+|current\\s+|active\\s+|live\\s+|present\\s+|latest\\s+|undismissed\\s+|pending\\s+|remaining\\s+|today'?s\\s+|this\\s+week'?s\\s+|last\\s+week'?s\\s+|past\\s+2\\s+weeks'?\\s+|2\\s+weeks'?\\s+|older\\s+|old\\s+)*(?:important\\s+|urgent\\s+|unread\\s+|new\\s+|recent\\s+|current\\s+|active\\s+|live\\s+)*(?:notifications?|alerts?|missed\\s+calls|messages|texts)(?:\\s+(?:for\\s+me|to\\s+me|currently|right\\s+now|now|on\\s+my\\s+phone|on\\s+the\\s+phone|in\\s+the\\s+shade|that\\s+are\\s+not\\s+(?:yet\\s+)?dismissed|not\\s+(?:yet\\s+)?dismissed|still\\s+there|from\\s+today|from\\s+this\\s+week|(?:from|for|in)?\\s*(?:today|this\\s+week|last\\s+24\\s+hours|24h|past\\s+week|last\\s+2\\s+weeks|two\\s+weeks|history)))*$|" +
                "^(?:do\\s+i\\s+have|are\\s+there|what\\s+are|what\\s+is|what's|whats|did\\s+i\\s+miss)\\s+(?:any\\s+|my\\s+|the\\s+)?(?:most\\s+|top\\s+|current\\s+|active\\s+|live\\s+|latest\\s+|today'?s\\s+|this\\s+week'?s\\s+)*(?:important\\s+|urgent\\s+|unread\\s+|new\\s+|recent\\s+|current\\s+|active\\s+)*(?:notifications?|alerts?|missed\\s+calls|messages|texts|anything)(?:\\s+(?:for\\s+me|currently|right\\s+now|now|on\\s+my\\s+phone|on\\s+the\\s+phone|in\\s+the\\s+shade|today|this\\s+week|lately|in\\s+the\\s+last\\s+24\\s+hours))*$|" +
                "^(?:my\\s+|the\\s+)?(?:most\\s+important\\s+|current\\s+|active\\s+|unread\\s+|latest\\s+|today'?s\\s+)?notifications?(?:\\s+(?:for\\s+me|currently|right\\s+now|now))?$",
            RegexOption.IGNORE_CASE
        )

    private val markNotificationsReadRegex =
        Regex("^(?:mark|set|clear)\\s+(?:all\\s+)?(?:my\\s+)?(?:unread\\s+)?(?:notifications?|alerts?|messages)\\s*(?:as\\s+read|read)?$", RegexOption.IGNORE_CASE)

    private val createReminderRegex =
        Regex("^(?:create|add|make|set|schedule) (?:a |an |new )?(?:reminder|calendar event|event|appointment)(?: (?:to|for|called|named|titled|about))?(?: (.+))?$", RegexOption.IGNORE_CASE)

    private val remindMeRegex =
        Regex("^remind me (?:to )?(.+)$", RegexOption.IGNORE_CASE)

    private val osQuickSettingsRegex =
        Regex("^(?:open |show |pull down )?quick settings$", RegexOption.IGNORE_CASE)

    private val osScreenshotRegex =
        Regex("^take (?:a )?screenshot$", RegexOption.IGNORE_CASE)

    private val osLockScreenRegex =
        Regex("^lock (?:the |my )?(?:phone|screen)$", RegexOption.IGNORE_CASE)

    private val screenTapRegex =
        Regex("^(?:tap|click|press) (?:the |on )?(.+?)(?: button)?$", RegexOption.IGNORE_CASE)

    private val screenTypeRegex =
        Regex("^(?:type|enter|input) (.+?) (?:in|into) (?:the )?(.+?)$", RegexOption.IGNORE_CASE)

    private val screenScrollRegex =
        Regex("^(?:scroll|swipe) (down|up)$", RegexOption.IGNORE_CASE)

    private val screenAutomateAppRegex =
        Regex("^(?:automate|control) (.+?) (?:to|and) (.+)$", RegexOption.IGNORE_CASE)

    fun isAgenticOrMetaCommand(command: String): Boolean {
        val clean = command.trim().trimEnd('.', '!', '?')
        if (clean.isEmpty()) return false
        return inspectAgenticMemoryRegex.containsMatchIn(clean) ||
            teachProcessRegex.containsMatchIn(clean) ||
            runLearnedProcessRegex.containsMatchIn(clean) ||
            setPreferenceRegex.containsMatchIn(clean) ||
            setPersonalityRegex.containsMatchIn(clean) ||
            setUserNameRegex.containsMatchIn(clean) ||
            forgetAgenticRegex.containsMatchIn(clean) ||
            recordRoutineRegex.containsMatchIn(clean) ||
            stopRecordingRoutineRegex.containsMatchIn(clean) ||
            runRecordedRoutineRegex.containsMatchIn(clean)
    }

    fun match(
        command: String,
        installedApps: List<AppCapabilityRegistry.InstalledAppProfile> = emptyList()
    ): FastPathMatch? {
        val rawClean = command.trim().trimEnd('.', '!', '?')
        val clean = politeLeadInRegex.replace(rawClean, "")
            .replace(Regex("\\s+please$", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifEmpty { rawClean }

        hardwareToggleRegex.find(clean)?.let { m ->
            val actionRaw = m.groupValues[1].lowercase()
            val state = when {
                actionRaw.contains("on") || actionRaw == "enable" -> "on"
                actionRaw.contains("off") || actionRaw == "disable" -> "off"
                else -> "toggle"
            }
            val feature = when (m.groupValues[2].lowercase()) {
                "torch", "flashlight" -> "flashlight"
                "wi-fi", "wifi" -> "wifi"
                "do not disturb", "don't disturb", "do not display", "don't display", "do not destroy", "disturb mode", "dnd" -> "dnd"
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

        relativeLevelRegex.find(clean)?.let { m ->
            val lower = clean.lowercase()
            val target = when {
                lower.contains("brightness") || lower == "brighter" || lower == "dimmer" -> "brightness"
                else -> "volume"
            }
            val isUp = lower.contains("up") || lower.contains("increase") ||
                lower.contains("raise") || lower == "brighter" || lower == "louder"
            val step = if (target == "brightness") 20 else 15
            val delta = if (isUp) "+$step" else "-$step"
            return FastPathMatch(
                skillId = "device-hardware-control",
                toolCall = ToolCallRequest(
                    toolName = "set_level",
                    args = mapOf("target" to target, "percent" to delta),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "${if (isUp) "Turned up" else "Turned down"} $target"
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

        mediaTransportRegex.find(clean)?.let { m ->
            val raw = m.groupValues[1].lowercase().ifEmpty { "play" }
            val cmd = when (raw) {
                "resume" -> "play"
                "skip" -> "next"
                "prev" -> "previous"
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

        playRandomRegex.find(clean)?.let {
            return FastPathMatch(
                skillId = "app-media-streaming",
                toolCall = ToolCallRequest(
                    toolName = "play_in_app",
                    args = mapOf("app_name" to "", "query" to "random", "media_type" to "song"),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Playing a random song"
            )
        }

        incomingCallRegex.find(clean)?.let { m ->
            val rawAction = m.groupValues[1].lowercase()
            val action = if (rawAction in setOf("answer", "accept", "pick up")) "answer" else "decline"
            return FastPathMatch(
                skillId = "phone-calls-manager",
                toolCall = ToolCallRequest(
                    toolName = "handle_incoming_call",
                    args = mapOf("action" to action),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = if (action == "answer") "Answering call" else "Declining call"
            )
        }

        callRegex.find(clean)?.let { m ->
            val targetName = m.groupValues[1].trim()
            if (targetName.lowercase() !in setOf("back", "it", "the", "incoming")) {
                return FastPathMatch(
                    skillId = "phone-calls-manager",
                    toolCall = ToolCallRequest(
                        toolName = "place_call",
                        args = mapOf("contact_id" to targetName.take(32), "speakerphone" to "false"),
                        safetyTier = SafetyTier.GREEN
                    ),
                    summary = "Calling $targetName"
                )
            }
        }

        calendarQueryRegex.find(clean)?.let {
            return FastPathMatch(
                skillId = "calendar-schedule-manager",
                toolCall = ToolCallRequest(
                    toolName = "query_calendar_events",
                    args = mapOf("start_iso" to "today"),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Checking calendar schedule"
            )
        }

        saveMemoryKeyValueRegex.find(clean)?.let { m ->
            val rawKey = m.groupValues[1].trim()
            val rawVal = m.groupValues[2].trim()
            val key = rawKey.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "note" }
            val fullFact = "My $rawKey is $rawVal"
            return FastPathMatch(
                skillId = "personal-memory-vault",
                toolCall = ToolCallRequest(
                    toolName = "save_memory_note",
                    args = mapOf("key" to key.take(36), "value" to fullFact.take(160)),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Saved memory: $rawKey"
            )
        }

        saveMemoryFactRegex.find(clean)?.let { m ->
            val body = m.groupValues[1].trim()
            if (!body.startsWith("to ", ignoreCase = true) && body.isNotEmpty()) {
                val words = body.lowercase()
                    .replace(Regex("[^a-z0-9\\s]"), " ")
                    .trim()
                    .split(Regex("\\s+"))
                    .filter { it.length > 2 && it !in setOf("the", "that", "have", "with", "from") }
                    .take(3)
                val key = words.joinToString("_").ifEmpty { "note" }
                return FastPathMatch(
                    skillId = "personal-memory-vault",
                    toolCall = ToolCallRequest(
                        toolName = "save_memory_note",
                        args = mapOf("key" to key.take(36), "value" to body.take(160)),
                        safetyTier = SafetyTier.GREEN
                    ),
                    summary = "Saved memory note"
                )
            }
        }

        inspectAgenticMemoryRegex.find(clean)?.let {
            return FastPathMatch(
                skillId = "personal-memory-vault",
                toolCall = ToolCallRequest(
                    toolName = "manage_agentic_memory",
                    args = mapOf("action" to "inspect"),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Checking learned habits & memory"
            )
        }

        teachProcessRegex.find(clean)?.let { m ->
            val procName = m.groupValues[1].trim()
            val procSteps = m.groupValues[2].trim()
            if (procName.isNotEmpty() && procSteps.isNotEmpty()) {
                return FastPathMatch(
                    skillId = "personal-memory-vault",
                    toolCall = ToolCallRequest(
                        toolName = "manage_agentic_memory",
                        args = mapOf(
                            "action" to "teach_process",
                            "target" to procName.take(60),
                            "detail" to procSteps.take(240)
                        ),
                        safetyTier = SafetyTier.GREEN
                    ),
                    summary = "Learning process '$procName'"
                )
            }
        }

        runLearnedProcessRegex.find(clean)?.let { m ->
            val targetProc = m.groupValues[1].ifBlank { m.groupValues[2] }.trim()
            if (targetProc.isNotEmpty() && !targetProc.equals("routine", ignoreCase = true)) {
                return FastPathMatch(
                    skillId = "personal-memory-vault",
                    toolCall = ToolCallRequest(
                        toolName = "manage_agentic_memory",
                        args = mapOf(
                            "action" to "run_process",
                            "target" to targetProc.take(60)
                        ),
                        safetyTier = SafetyTier.GREEN
                    ),
                    summary = "Running learned process '$targetProc'"
                )
            }
        }

        setPreferenceRegex.find(clean)?.let { m ->
            val target = m.groupValues[1].trim()
            if (target.isNotEmpty()) {
                return FastPathMatch(
                    skillId = "personal-memory-vault",
                    toolCall = ToolCallRequest(
                        toolName = "manage_agentic_memory",
                        args = mapOf(
                            "action" to "set_preference",
                            "target" to target.take(60),
                            "detail" to clean.take(120)
                        ),
                        safetyTier = SafetyTier.GREEN
                    ),
                    summary = "Updating preference: $target"
                )
            }
        }

        setPersonalityRegex.find(clean)?.let { m ->
            val mode = m.groupValues[1].ifBlank { m.groupValues[2] }.trim()
            if (mode.isNotEmpty()) {
                return FastPathMatch(
                    skillId = "personal-memory-vault",
                    toolCall = ToolCallRequest(
                        toolName = "manage_agentic_memory",
                        args = mapOf("action" to "set_personality", "target" to mode.take(32)),
                        safetyTier = SafetyTier.GREEN
                    ),
                    summary = "Updating assistant personality"
                )
            }
        }

        setUserNameRegex.find(clean)?.let { m ->
            val userName = m.groupValues[1].trim()
            if (userName.isNotEmpty()) {
                return FastPathMatch(
                    skillId = "personal-memory-vault",
                    toolCall = ToolCallRequest(
                        toolName = "manage_agentic_memory",
                        args = mapOf("action" to "set_name", "target" to userName.take(24)),
                        safetyTier = SafetyTier.GREEN
                    ),
                    summary = "Saving your preferred name"
                )
            }
        }

        forgetAgenticRegex.find(clean)?.let { m ->
            val topic = m.groupValues[1].trim()
            if (topic.isNotEmpty()) {
                return FastPathMatch(
                    skillId = "personal-memory-vault",
                    toolCall = ToolCallRequest(
                        toolName = "manage_agentic_memory",
                        args = mapOf("action" to "forget", "target" to topic.take(60)),
                        safetyTier = SafetyTier.GREEN
                    ),
                    summary = "Forgetting '$topic'"
                )
            }
        }

        recallMemoryRegex.find(clean)?.let { m ->
            val rawTopic = m.groupValues[1].trim()
            if (!rawTopic.contains("time", ignoreCase = true) && !rawTopic.contains("battery", ignoreCase = true)) {
                return FastPathMatch(
                    skillId = "personal-memory-vault",
                    toolCall = ToolCallRequest(
                        toolName = "recall_memory_note",
                        args = mapOf("query" to rawTopic.take(80)),
                        safetyTier = SafetyTier.GREEN
                    ),
                    summary = "Checking saved memories"
                )
            }
        }

        morningBriefingRegex.find(clean)?.let {
            val lower = clean.lowercase()
            val topic = when {
                lower.contains("connectivity") -> "connectivity"
                lower.contains("week") -> "weekly"
                lower.contains("tonight") || lower.contains("evening") || lower.contains("night") -> "evening"
                lower.contains("afternoon") -> "afternoon"
                else -> "morning"
            }
            val summaryLabel = when (topic) {
                "weekly" -> "Prepared weekly briefing"
                "evening" -> "Prepared evening briefing"
                "afternoon" -> "Prepared afternoon briefing"
                "connectivity" -> "Checked connectivity status"
                else -> "Prepared daily briefing"
            }
            return FastPathMatch(
                skillId = "alexa-utility-briefings",
                toolCall = ToolCallRequest(
                    toolName = "get_device_briefing",
                    args = mapOf("topic" to topic),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = summaryLabel
            )
        }

        routineTriggerRegex.find(clean)?.let {
            val lower = clean.lowercase()
            val routineName = when {
                lower.contains("focus") || lower.contains("do not disturb") -> "focus"
                lower.contains("bedtime") || lower.contains("good night") -> "bedtime"
                lower.contains("sync") -> "sync"
                else -> "morning"
            }
            return FastPathMatch(
                skillId = "compound-routine-orchestrator",
                toolCall = ToolCallRequest(
                    toolName = "get_routine_steps",
                    args = mapOf("routine_name" to routineName),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Running $routineName routine"
            )
        }

        showFloatingRoutineRegex.find(clean)?.let {
            return FastPathMatch(
                skillId = "compound-routine-orchestrator",
                toolCall = ToolCallRequest(
                    toolName = "control_routine_recorder",
                    args = mapOf("action" to "show_floating"),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Showing floating routine control"
            )
        }

        hideFloatingRoutineRegex.find(clean)?.let {
            return FastPathMatch(
                skillId = "compound-routine-orchestrator",
                toolCall = ToolCallRequest(
                    toolName = "control_routine_recorder",
                    args = mapOf("action" to "hide_floating"),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Hiding floating routine control"
            )
        }

        stopRecordingRoutineRegex.find(clean)?.let { m ->
            val saveAs = m.groupValues[1].trim()
            val args = buildMap {
                put("action", "stop")
                if (saveAs.isNotEmpty()) put("routine_name", saveAs)
            }
            return FastPathMatch(
                skillId = "compound-routine-orchestrator",
                toolCall = ToolCallRequest(
                    toolName = "control_routine_recorder",
                    args = args,
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Stopping routine recording"
            )
        }

        inspectRoutineStepsRegex.find(clean)?.let { m ->
            val rawTarget = listOf(
                m.groupValues.getOrNull(1).orEmpty(),
                m.groupValues.getOrNull(2).orEmpty(),
                m.groupValues.getOrNull(3).orEmpty()
            ).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                .replace(Regex("^(?:the|my|your|all|each|available|recorded|saved|built[-\\s]?in)\\s+", RegexOption.IGNORE_CASE), "")
                .trim()
            val args = buildMap {
                put("action", "list_steps")
                if (rawTarget.isNotBlank() && rawTarget.lowercase() !in setOf("all", "every", "each", "available", "the")) {
                    put("routine_name", rawTarget)
                }
            }
            return FastPathMatch(
                skillId = "compound-routine-orchestrator",
                toolCall = ToolCallRequest(
                    toolName = "control_routine_recorder",
                    args = args,
                    safetyTier = SafetyTier.GREEN
                ),
                summary = if (rawTarget.isNotBlank()) "Listing steps for $rawTarget routine" else "Listing routine steps"
            )
        }

        listRoutinesRegex.find(clean)?.let {
            return FastPathMatch(
                skillId = "compound-routine-orchestrator",
                toolCall = ToolCallRequest(
                    toolName = "control_routine_recorder",
                    args = mapOf("action" to "list"),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Listing available routines"
            )
        }

        recordRoutineRegex.find(clean)?.let { m ->
            val explicitGlobal = m.groupValues[1].trim().isNotEmpty()
            val routineName = m.groupValues[2].trim()
            val targetScope = m.groupValues[3].trim()
            val resolvedScope = when {
                targetScope.isNotEmpty() -> targetScope
                explicitGlobal -> "global"
                else -> "global"
            }
            val args = buildMap {
                put("action", "start")
                put("target_scope", resolvedScope)
                if (routineName.isNotEmpty()) put("routine_name", routineName)
            }
            return FastPathMatch(
                skillId = "compound-routine-orchestrator",
                toolCall = ToolCallRequest(
                    toolName = "control_routine_recorder",
                    args = args,
                    safetyTier = SafetyTier.GREEN
                ),
                summary = if (resolvedScope == "global") "Recording global routine" else "Recording routine for $resolvedScope"
            )
        }

        runRecordedRoutineRegex.find(clean)?.let { m ->
            val rName = m.groupValues[1].trim()
            return FastPathMatch(
                skillId = "compound-routine-orchestrator",
                toolCall = ToolCallRequest(
                    toolName = "run_recorded_routine",
                    args = mapOf("routine_name" to rName),
                    safetyTier = SafetyTier.YELLOW
                ),
                summary = "Running routine '$rName'"
            )
        }

        AlexaUtilityEngine.matchFastUtility(clean)?.let { return it }

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

        markNotificationsReadRegex.find(clean)?.let {
            return FastPathMatch(
                skillId = "messaging-omni-send",
                toolCall = ToolCallRequest(
                    toolName = "read_recent_messages",
                    args = mapOf("contact_id" to "all", "action" to "mark_all_read"),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Marking notifications as read"
            )
        }

        readImportantNotificationsRegex.find(clean)?.let {
            val lower = clean.lowercase()
            val asksCurrent = lower.contains("current") || lower.contains("active") || lower.contains("live") ||
                lower.contains("right now") || lower.contains("in the shade") ||
                lower.contains("on my phone") || lower.contains("on the phone") ||
                lower.contains("not dismissed") || lower.contains("not yet dismissed") ||
                lower.contains("undismissed") || lower.contains("top notification")
            val asksMostImportant = lower.contains("most important") || lower.contains("most urgent") ||
                lower.contains("highest priority") || lower.contains("top important")
            val isSingularNotification = Regex("\\b(?:notification|alert)\\b").containsMatchIn(lower) &&
                !Regex("\\b(?:notifications|alerts)\\b").containsMatchIn(lower)
            val scope = when {
                asksCurrent && (asksMostImportant || lower.contains("important") || lower.contains("urgent")) ->
                    "current_important"
                asksCurrent -> "current"
                asksMostImportant && !lower.contains("week") && !lower.contains("history") && !lower.contains("old") ->
                    "current_important"
                lower.contains("unread") -> "unread"
                lower.contains("24") -> "24h"
                lower.contains("week") -> "week"
                lower.contains("old") || lower.contains("history") -> "archive"
                lower.contains("important") && !lower.contains("today") -> "important"
                lower.contains("today") -> "today"
                else -> "current_or_today"
            }
            val limit = if (isSingularNotification && asksMostImportant) "1" else "6"
            return FastPathMatch(
                skillId = "messaging-omni-send",
                toolCall = ToolCallRequest(
                    toolName = "read_recent_messages",
                    args = mapOf("contact_id" to "all", "scope" to scope, "limit" to limit),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Checking $scope notifications"
            )
        }

        createReminderRegex.find(clean)?.let { m ->
            val rawBody = m.groupValues[1].trim()
            val (title, whenExpr) = extractReminderTitleAndWhen(rawBody)
            return FastPathMatch(
                skillId = "calendar-schedule-manager",
                toolCall = ToolCallRequest(
                    toolName = "create_calendar_event",
                    args = mapOf(
                        "title" to title,
                        "start_iso" to whenExpr,
                        "duration_min" to "30"
                    ),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Creating reminder '$title'"
            )
        }

        remindMeRegex.find(clean)?.let { m ->
            val rawBody = m.groupValues[1].trim()
            val (title, whenExpr) = extractReminderTitleAndWhen(rawBody)
            return FastPathMatch(
                skillId = "calendar-schedule-manager",
                toolCall = ToolCallRequest(
                    toolName = "create_calendar_event",
                    args = mapOf(
                        "title" to title,
                        "start_iso" to whenExpr,
                        "duration_min" to "30"
                    ),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Creating reminder '$title'"
            )
        }

        openAndPlayRegex.find(clean)?.let { m ->
            val (appName, wantsBrowser) = parseAppAndBrowserHint(m.groupValues[1])
            val query = m.groupValues[2].trim()
            return FastPathMatch(
                skillId = "app-media-streaming",
                toolCall = ToolCallRequest(
                    toolName = "play_in_app",
                    args = buildMap {
                        put("app_name", appName)
                        put("query", query)
                        put("media_type", "song")
                        if (wantsBrowser) put("use_browser", "true")
                    },
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Playing in $appName"
            )
        }

        playInAppRegex.find(clean)?.let { m ->
            val query = m.groupValues[1].trim()
            val rawAppCandidate = m.groupValues[2].trim()
            val explicitAppSuffix = rawAppCandidate.lowercase().endsWith(" app")
            val (appName, wantsBrowser) = parseAppAndBrowserHint(rawAppCandidate)
            if (isPlausibleAppTarget(appName, explicitAppSuffix || wantsBrowser, installedApps)) {
                return FastPathMatch(
                    skillId = "app-media-streaming",
                    toolCall = ToolCallRequest(
                        toolName = "play_in_app",
                        args = buildMap {
                            put("app_name", appName)
                            put("query", query)
                            if (wantsBrowser) put("use_browser", "true")
                        },
                        safetyTier = SafetyTier.GREEN
                    ),
                    summary = "Playing in $appName"
                )
            }
        }

        playSpecificRegex.find(clean)?.let { m ->
            val query = m.groupValues[1].trim()
            if (query.isNotEmpty() && query.lowercase() !in setOf(
                    "it", "this", "that", "first", "the first", "resume", "pause", "next", "previous"
                )
            ) {
                return FastPathMatch(
                    skillId = "app-media-streaming",
                    toolCall = ToolCallRequest(
                        toolName = "play_in_app",
                        args = mapOf("app_name" to "", "query" to query, "media_type" to "song"),
                        safetyTier = SafetyTier.GREEN
                    ),
                    summary = "Playing $query"
                )
            }
        }

        openAndSearchRegex.find(clean)?.let { m ->
            val (appName, wantsBrowser) = parseAppAndBrowserHint(m.groupValues[1])
            val query = m.groupValues[2].trim()
            return FastPathMatch(
                skillId = "app-search-and-action",
                toolCall = ToolCallRequest(
                    toolName = "search_in_app",
                    args = buildMap {
                        put("app_name", appName)
                        put("query", query)
                        if (wantsBrowser) put("use_browser", "true")
                    },
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Searching $appName"
            )
        }

        searchInAppRegex.find(clean)?.let { m ->
            val query = m.groupValues[1].trim()
            val rawAppCandidate = m.groupValues[2].trim()
            val explicitAppSuffix = rawAppCandidate.lowercase().endsWith(" app")
            val (appName, wantsBrowser) = parseAppAndBrowserHint(rawAppCandidate)
            if (appName.lowercase() !in localFtsSourceNouns &&
                isPlausibleAppTarget(appName, explicitAppSuffix || wantsBrowser, installedApps)
            ) {
                return FastPathMatch(
                    skillId = "app-search-and-action",
                    toolCall = ToolCallRequest(
                        toolName = "search_in_app",
                        args = buildMap {
                            put("app_name", appName)
                            put("query", query)
                            if (wantsBrowser) put("use_browser", "true")
                        },
                        safetyTier = SafetyTier.GREEN
                    ),
                    summary = "Searching $appName"
                )
            }
        }

        WebKnowledgeResolver.extractExplicitWebLookupQuery(clean)?.let { webQuery ->
            return FastPathMatch(
                skillId = "general-knowledge-qa",
                toolCall = ToolCallRequest(
                    toolName = "answer_web_question",
                    args = mapOf("query" to webQuery.take(140)),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Answering web question"
            )
        }

        searchAppForRegex.find(clean)?.let { m ->
            val (appName, wantsBrowser) = parseAppAndBrowserHint(m.groupValues[1])
            val query = m.groupValues[2].trim()
            if (appName.lowercase() !in localFtsSourceNouns) {
                return FastPathMatch(
                    skillId = "app-search-and-action",
                    toolCall = ToolCallRequest(
                        toolName = "search_in_app",
                        args = buildMap {
                            put("app_name", appName)
                            put("query", query)
                            if (wantsBrowser) put("use_browser", "true")
                        },
                        safetyTier = SafetyTier.GREEN
                    ),
                    summary = "Searching $appName"
                )
            }
        }

        findInAppRegex.find(clean)?.let { m ->
            val query = m.groupValues[1].trim()
            val rawAppCandidate = m.groupValues[2].trim()
            val explicitAppSuffix = rawAppCandidate.lowercase().endsWith(" app")
            val (appName, wantsBrowser) = parseAppAndBrowserHint(rawAppCandidate)
            if (appName.lowercase() !in localFtsSourceNouns &&
                isPlausibleAppTarget(appName, explicitAppSuffix || wantsBrowser, installedApps)
            ) {
                return FastPathMatch(
                    skillId = "app-search-and-action",
                    toolCall = ToolCallRequest(
                        toolName = "search_in_app",
                        args = buildMap {
                            put("app_name", appName)
                            put("query", query)
                            if (wantsBrowser) put("use_browser", "true")
                        },
                        safetyTier = SafetyTier.GREEN
                    ),
                    summary = "Searching $appName"
                )
            }
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

        openSystemSettingsRegex.find(clean)?.let { m ->
            val rawFeature = (m.groupValues[1].ifBlank { m.groupValues[2] }).trim().lowercase()
            val feature = when {
                rawFeature == "wi-fi" || rawFeature == "wifi" -> "wifi"
                rawFeature.startsWith("notif") -> "notifications"
                else -> rawFeature
            }
            return FastPathMatch(
                skillId = "device-hardware-control",
                toolCall = ToolCallRequest(
                    toolName = "set_hardware_state",
                    args = mapOf("feature" to feature, "state" to "settings"),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Opening ${feature.replaceFirstChar { it.uppercase() }} settings"
            )
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

        screenScrollRegex.find(clean)?.let { m ->
            val dir = m.groupValues[1].lowercase()
            val action = if (dir == "up") "SCROLL_UP" else "SCROLL_DOWN"
            return FastPathMatch(
                skillId = "local-screen-control",
                toolCall = ToolCallRequest(
                    toolName = "ui_act",
                    args = mapOf("action" to action, "node_id" to ""),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Scrolling $dir"
            )
        }

        screenTypeRegex.find(clean)?.let { m ->
            val textToType = m.groupValues[1].trim()
            val targetField = m.groupValues[2].trim()
            return FastPathMatch(
                skillId = "local-screen-control",
                toolCall = ToolCallRequest(
                    toolName = "ui_find_and_tap",
                    args = mapOf(
                        "target_label" to targetField,
                        "action_type" to "type",
                        "input_text" to textToType
                    ),
                    safetyTier = SafetyTier.GREEN
                ),
                summary = "Typing into $targetField"
            )
        }

        screenTapRegex.find(clean)?.let { m ->
            val targetLabel = m.groupValues[1].trim()
            val tier = if (DeferredToolRegistry.isRedButtonLabel(targetLabel)) SafetyTier.RED else SafetyTier.GREEN
            return FastPathMatch(
                skillId = "local-screen-control",
                toolCall = ToolCallRequest(
                    toolName = "ui_find_and_tap",
                    args = mapOf("target_label" to targetLabel, "action_type" to "click"),
                    safetyTier = tier
                ),
                summary = "Tapping $targetLabel"
            )
        }

        screenAutomateAppRegex.find(clean)?.let { m ->
            val appName = m.groupValues[1].trim()
            val goal = m.groupValues[2].trim()
            val tier = if (DeferredToolRegistry.isRedButtonLabel(goal)) SafetyTier.RED else SafetyTier.GREEN
            return FastPathMatch(
                skillId = "local-screen-control",
                toolCall = ToolCallRequest(
                    toolName = "screen_control_app",
                    args = mapOf("app_name" to appName, "goal" to goal),
                    safetyTier = tier
                ),
                summary = "Automating $appName"
            )
        }

        return null
    }

    private fun parseAppAndBrowserHint(rawApp: String): Pair<String, Boolean> {
        val browserSuffix = Regex("\\s+(?:via|in|on|using)\\s+(?:the\\s+)?(?:web\\s+)?(?:browser|chrome|web)$", RegexOption.IGNORE_CASE)
        val wantsBrowser = browserSuffix.containsMatchIn(rawApp)
        val cleanApp = rawApp.replace(browserSuffix, "")
            .removeSuffix(" app")
            .removeSuffix(" App")
            .trim()
        return cleanApp to wantsBrowser
    }

    private fun isPlausibleAppTarget(
        appName: String,
        explicitAppSuffix: Boolean,
        installedApps: List<AppCapabilityRegistry.InstalledAppProfile>
    ): Boolean {
        val clean = appName.trim()
        if (clean.isEmpty()) return false
        if (explicitAppSuffix) return true
        if (installedApps.isNotEmpty() && AppCapabilityRegistry.findStrictMatchFromProfiles(installedApps, clean) != null) {
            return true
        }
        return AppCapabilityRegistry.isKnownAppNameOrAlias(clean)
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

    fun extractReminderTitleAndWhen(rawBody: String): Pair<String, String> {
        val trimmed = rawBody.trim()
        if (trimmed.isEmpty()) return "Nova Reminder" to "in 1 hour"

        val timeSuffixPatterns = listOf(
            Regex("\\b(tomorrow\\s+at\\s+\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?)$", RegexOption.IGNORE_CASE),
            Regex("\\b(today\\s+at\\s+\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?)$", RegexOption.IGNORE_CASE),
            Regex("\\b(in\\s+\\d+\\s+(?:minutes?|mins?|hours?|hrs?|days?))$", RegexOption.IGNORE_CASE),
            Regex("\\b(at\\s+\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?)$", RegexOption.IGNORE_CASE),
            Regex("\\b(tomorrow|tonight|this evening|this afternoon)$", RegexOption.IGNORE_CASE)
        )
        for (pattern in timeSuffixPatterns) {
            val match = pattern.find(trimmed)
            if (match != null) {
                val whenExpr = match.groupValues[1].trim()
                val title = trimmed.removeRange(match.range).trim()
                    .trimEnd(',', '-', ' ')
                    .ifEmpty { "Nova Reminder" }
                return title to whenExpr
            }
        }
        return trimmed to "in 1 hour"
    }
}
