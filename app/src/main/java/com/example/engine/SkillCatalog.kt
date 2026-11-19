package com.example.engine

import com.example.data.SafetyTier
import com.example.data.SkillCategory
import com.example.data.SkillSpec
import com.example.data.ToolDefinition
import com.example.data.ToolParameter

/**
 * Progressive Disclosure Skill Catalog across Categories A-E (16 active + 1 disabled smart home).
 * Each SkillSpec encapsulates trigger patterns, <=3-parameter tools, companion cross-skill tools,
 * SKILL behavioral guidance, and few-shot JSON examples injected only when that skill is disclosed.
 */
object SkillCatalog {

    val allSkills: List<SkillSpec> = listOf(
        // Category A: Instant Device & Hardware Reflexes
        SkillSpec(
            id = "device-hardware-control",
            title = "Device Hardware Control",
            category = SkillCategory.A,
            description = "Toggle Wi-Fi, Bluetooth, Flashlight, Do Not Disturb, mute, or set absolute/relative volume and brightness.",
            requiresThinking = false,
            guidance = "Use set_hardware_state for on/off/toggle switches (flashlight, wifi, bluetooth, dnd, mute). For set_level, pass 0-100 for exact percentages or relative deltas like '+15' / '-15' when the user asks to turn volume/brightness up or down.",
            fastTriggers = listOf(
                "^(turn on|turn off|toggle) (flashlight|torch|wifi|wi-fi|bluetooth|dnd|do not disturb)$",
                "^set (volume|brightness) to (\\d+)%?$",
                "^(turn up|turn down|increase|decrease) (volume|brightness)$",
                "^(mute|unmute) phone$"
            ),
            tools = listOf(
                ToolDefinition(
                    name = "set_hardware_state",
                    description = "Toggle a phone hardware feature.",
                    parameters = listOf(
                        ToolParameter("feature", "STRING", "flashlight, wifi, bluetooth, dnd, mute"),
                        ToolParameter("state", "STRING", "on, off, toggle")
                    ),
                    safetyTier = SafetyTier.GREEN
                ),
                ToolDefinition(
                    name = "set_level",
                    description = "Set device volume or brightness percentage (absolute 0-100 or relative delta like +15 / -15).",
                    parameters = listOf(
                        ToolParameter("target", "STRING", "volume or brightness"),
                        ToolParameter("percent", "STRING", "0-100 or relative delta +15 / -15")
                    ),
                    safetyTier = SafetyTier.GREEN
                )
            ),
            safetyTier = SafetyTier.GREEN,
            fewShotExamples = listOf(
                "turn on flashlight -> {\"calls\":[{\"tool\":\"set_hardware_state\",\"args\":{\"feature\":\"flashlight\",\"state\":\"on\"}}],\"reply\":\"\"}",
                "set volume to 65% -> {\"calls\":[{\"tool\":\"set_level\",\"args\":{\"target\":\"volume\",\"percent\":\"65\"}}],\"reply\":\"\"}",
                "volume up -> {\"calls\":[{\"tool\":\"set_level\",\"args\":{\"target\":\"volume\",\"percent\":\"+15\"}}],\"reply\":\"\"}",
                "mute phone -> {\"calls\":[{\"tool\":\"set_hardware_state\",\"args\":{\"feature\":\"mute\",\"state\":\"on\"}}],\"reply\":\"\"}"
            )
        ),
        SkillSpec(
            id = "clock-alarms-timers",
            title = "Clock, Alarms & Timers",
            category = SkillCategory.A,
            description = "Set, cancel, check, or snooze alarms, countdown timers, and stopwatches.",
            requiresThinking = false,
            guidance = "Convert spoken countdowns into integer seconds for create_timer (e.g. 25 minutes -> 1500) and convert 12-hour clock times into HH:mm 24-hour format for create_alarm.",
            fastTriggers = listOf(
                "^set (?:a )?timer for (.+)$",
                "^set (?:an )?alarm for (.+)$",
                "^(stop|snooze|cancel) (alarm|timer)$"
            ),
            tools = listOf(
                ToolDefinition(
                    name = "create_timer",
                    description = "Start a countdown timer.",
                    parameters = listOf(
                        ToolParameter("duration_sec", "INTEGER", "Seconds"),
                        ToolParameter("label", "STRING", "Optional timer label", required = false)
                    ),
                    safetyTier = SafetyTier.GREEN
                ),
                ToolDefinition(
                    name = "create_alarm",
                    description = "Create an alarm in 24h HH:mm format.",
                    parameters = listOf(
                        ToolParameter("time_24h", "STRING", "HH:mm"),
                        ToolParameter("label", "STRING", "Optional label", required = false)
                    ),
                    safetyTier = SafetyTier.GREEN
                ),
                ToolDefinition(
                    name = "modify_clock_item",
                    description = "Stop or snooze an active alarm or timer.",
                    parameters = listOf(
                        ToolParameter("item_id", "STRING", "Short ID like tm01"),
                        ToolParameter("action", "STRING", "stop or snooze")
                    ),
                    safetyTier = SafetyTier.GREEN
                )
            ),
            safetyTier = SafetyTier.GREEN,
            fewShotExamples = listOf(
                "set a timer for 25 minutes -> {\"calls\":[{\"tool\":\"create_timer\",\"args\":{\"duration_sec\":\"1500\",\"label\":\"Focus\"}}],\"reply\":\"\"}",
                "set an alarm for 7:30 am -> {\"calls\":[{\"tool\":\"create_alarm\",\"args\":{\"time_24h\":\"07:30\",\"label\":\"Wake up\"}}],\"reply\":\"\"}"
            )
        ),
        SkillSpec(
            id = "media-playback-control",
            title = "Media Playback Control",
            category = SkillCategory.A,
            description = "Play, pause, skip, rewind, or search music, podcasts, and videos.",
            requiresThinking = false,
            guidance = "Use media_transport for active playback transport commands (play, pause, next, previous). When the user wants a song or random track started, use play_media_query or companion tool play_in_app.",
            companionTools = listOf("play_in_app"),
            fastTriggers = listOf(
                "^(play|pause|resume|next|skip|previous)(?: track| song| music)?$"
            ),
            tools = listOf(
                ToolDefinition(
                    name = "media_transport",
                    description = "Control active media playback.",
                    parameters = listOf(
                        ToolParameter("command", "STRING", "play, pause, next, previous")
                    ),
                    safetyTier = SafetyTier.GREEN
                ),
                ToolDefinition(
                    name = "play_media_query",
                    description = "Play a specific song, playlist, or 'random' track.",
                    parameters = listOf(
                        ToolParameter("query", "STRING", "Track, playlist, or 'random'"),
                        ToolParameter("app_name", "STRING", "Target media app", required = false)
                    ),
                    safetyTier = SafetyTier.GREEN
                )
            ),
            safetyTier = SafetyTier.GREEN,
            fewShotExamples = listOf(
                "pause music -> {\"calls\":[{\"tool\":\"media_transport\",\"args\":{\"command\":\"pause\"}}],\"reply\":\"\"}",
                "play focus lo-fi -> {\"calls\":[{\"tool\":\"play_media_query\",\"args\":{\"query\":\"focus lo-fi\",\"app_name\":\"Spotify\"}}],\"reply\":\"\"}"
            )
        ),

        // Category B: Universal Local Search & Personal Memory
        SkillSpec(
            id = "local-device-search",
            title = "Local Device Search (FTS5)",
            category = SkillCategory.B,
            description = "Search local files, PDFs, screenshots, downloads, notes, contacts, and messages via SQLite FTS5 + EmbeddingGemma.",
            requiresThinking = false,
            guidance = "Use local_search with focused source filters (all, files, screenshots, contacts, messages) and concise search terms; summarize matching documents cleanly without exposing internal doc IDs. Pair with recall_memory_note when searching saved personal facts.",
            companionTools = listOf("recall_memory_note"),
            fastTriggers = emptyList(),
            tools = listOf(
                ToolDefinition(
                    name = "local_search",
                    description = "Search local device files, screenshots, contacts, messages, or notes.",
                    parameters = listOf(
                        ToolParameter("query", "STRING", "Search keywords"),
                        ToolParameter("source", "STRING", "all, files, screenshots, contacts, messages")
                    ),
                    safetyTier = SafetyTier.GREEN
                ),
                ToolDefinition(
                    name = "read_file_snippet",
                    description = "Read local file text or pass screenshot URI to vision.",
                    parameters = listOf(
                        ToolParameter("file_uri", "STRING", "Short doc ID or URI"),
                        ToolParameter("page_or_offset", "INTEGER", "Page index", required = false)
                    ),
                    safetyTier = SafetyTier.GREEN
                )
            ),
            safetyTier = SafetyTier.GREEN,
            fewShotExamples = listOf(
                "find that PDF receipt from Cebu Pacific -> {\"calls\":[{\"tool\":\"local_search\",\"args\":{\"query\":\"Cebu Pacific receipt\",\"source\":\"files\"}}],\"reply\":\"\"}",
                "what is the wifi password screenshot -> {\"calls\":[{\"tool\":\"local_search\",\"args\":{\"query\":\"wifi password\",\"source\":\"screenshots\"}}],\"reply\":\"\"}",
                "find my electricity bill -> {\"calls\":[{\"tool\":\"local_search\",\"args\":{\"query\":\"electricity bill\",\"source\":\"files\"}}],\"reply\":\"\"}"
            )
        ),
        SkillSpec(
            id = "personal-memory-vault",
            title = "Agentic Memory & Personal Vault",
            category = SkillCategory.B,
            description = "Agentic episodic/preference/process memory: remembers user facts, learns habits and workflows over time, adapts personality tone, and recalls personal notes.",
            requiresThinking = false,
            guidance = "Store durable user facts with save_memory_note and query recall_memory_note for saved notes. Use manage_agentic_memory to inspect what Nova has learned, teach or run multi-step user processes, switch personality (warm, executive, witty, minimalist), set user greeting name, save app/channel preferences (set_preference), or forget a learned preference.",
            fastTriggers = listOf(
                "^remember (?:that |i |my )(.+)$",
                "^(?:where did i|what was my|what is my saved|recall|do you remember) (.+)$",
                "^(?:what have you learned|show my learned processes|what do you know about me)$",
                "^(?:switch|change|set) (?:your )?personality to (warm|executive|witty|minimalist)$",
                "^learn process (.+?): (.+)$"
            ),
            tools = listOf(
                ToolDefinition(
                    name = "save_memory_note",
                    description = "Store a personal memory note locally in FTS5 + EmbeddingGemma.",
                    parameters = listOf(
                        ToolParameter("key", "STRING", "Topic key"),
                        ToolParameter("value", "STRING", "Fact content")
                    ),
                    safetyTier = SafetyTier.GREEN
                ),
                ToolDefinition(
                    name = "recall_memory_note",
                    description = "Recall stored personal notes matching a query.",
                    parameters = listOf(
                        ToolParameter("query", "STRING", "Topic keywords")
                    ),
                    safetyTier = SafetyTier.GREEN
                ),
                ToolDefinition(
                    name = "manage_agentic_memory",
                    description = "Inspect learned preferences/processes, teach or run a learned workflow, adapt personality, save an app preference, or forget a learned item.",
                    parameters = listOf(
                        ToolParameter("action", "STRING", "inspect, teach_process, run_process, set_personality, set_name, set_preference, forget"),
                        ToolParameter("target", "STRING", "Process name, personality ID, user name, preferred app, or topic", required = false),
                        ToolParameter("detail", "STRING", "Semicolon or then-separated workflow steps", required = false)
                    ),
                    safetyTier = SafetyTier.GREEN
                )
            ),
            safetyTier = SafetyTier.GREEN,
            fewShotExamples = listOf(
                "remember I parked on Basement 2 pillar B4 -> {\"calls\":[{\"tool\":\"save_memory_note\",\"args\":{\"key\":\"parking_spot\",\"value\":\"Basement 2 pillar B4\"}}],\"reply\":\"\"}",
                "what have you learned about me -> {\"calls\":[{\"tool\":\"manage_agentic_memory\",\"args\":{\"action\":\"inspect\"}}],\"reply\":\"\"}",
                "switch personality to witty -> {\"calls\":[{\"tool\":\"manage_agentic_memory\",\"args\":{\"action\":\"set_personality\",\"target\":\"witty\"}}],\"reply\":\"\"}"
            )
        ),

        // Category C: Communications & Scheduling
        SkillSpec(
            id = "messaging-omni-send",
            title = "Messaging Omni-Send",
            category = SkillCategory.C,
            description = "Summarize important unread notifications (messages, missed calls, reminders) and send SMS, WhatsApp, Messenger, or Viber messages.",
            requiresThinking = false,
            guidance = "Use read_recent_messages to inspect important notifications (messages, missed calls, reminders) from today's queue or the 14-day archive (scope: today, unread, 24h, week, archive, important, mark_all_read). When sending a message to a named contact, place the contact name or number in contact_id and specify channel (auto, sms, whatsapp, messenger, viber); search_contacts is also available if lookup confirmation is needed.",
            companionTools = listOf("search_contacts"),
            fastTriggers = listOf(
                "^(?:read|check|summarize) (?:my )?(?:important |unread |today'?s )?(?:notifications|messages|texts)$"
            ),
            tools = listOf(
                ToolDefinition(
                    name = "read_recent_messages",
                    description = "Read today's or archived important notifications/messages with read/unread tracking.",
                    parameters = listOf(
                        ToolParameter("contact_id", "STRING", "Short ID or 'all'"),
                        ToolParameter("scope", "STRING", "today, unread, 24h, week, archive, important, mark_all_read", required = false),
                        ToolParameter("limit", "INTEGER", "Max messages", required = false)
                    ),
                    safetyTier = SafetyTier.GREEN
                ),
                ToolDefinition(
                    name = "message_send",
                    description = "Send a message or SMS to a contact name or phone number.",
                    parameters = listOf(
                        ToolParameter("contact_id", "STRING", "Contact name, ID, or phone number"),
                        ToolParameter("text", "STRING", "Message content"),
                        ToolParameter("channel", "STRING", "auto, sms, whatsapp, messenger, viber", required = false)
                    ),
                    safetyTier = SafetyTier.YELLOW
                )
            ),
            safetyTier = SafetyTier.YELLOW,
            fewShotExamples = listOf(
                "summarize unread texts -> {\"calls\":[{\"tool\":\"read_recent_messages\",\"args\":{\"contact_id\":\"all\",\"limit\":\"5\"}}],\"reply\":\"\"}",
                "read my important notifications -> {\"calls\":[{\"tool\":\"read_recent_messages\",\"args\":{\"contact_id\":\"all\",\"limit\":\"6\"}}],\"reply\":\"\"}",
                "text Mom I am on my way -> {\"calls\":[{\"tool\":\"message_send\",\"args\":{\"contact_id\":\"Mom\",\"text\":\"I am on my way\",\"channel\":\"auto\"}}],\"reply\":\"\"}"
            )
        ),
        SkillSpec(
            id = "phone-calls-manager",
            title = "Phone Calls Manager",
            category = SkillCategory.C,
            description = "Look up contacts, place voice calls (resolving names to phone numbers automatically), and answer or decline incoming calls.",
            requiresThinking = false,
            guidance = "Pass either a contact name or phone number to place_call (names are resolved automatically against device contacts & local index), or run search_contacts first when disambiguating multiple people. Use handle_incoming_call for answer/decline commands.",
            fastTriggers = listOf(
                "^call (.+)$",
                "^(answer|accept|decline|reject|end) (?:the |incoming )?call$"
            ),
            tools = listOf(
                ToolDefinition(
                    name = "search_contacts",
                    description = "Search local Android contacts by name or phone number.",
                    parameters = listOf(
                        ToolParameter("query", "STRING", "Name or partial number")
                    ),
                    safetyTier = SafetyTier.GREEN
                ),
                ToolDefinition(
                    name = "place_call",
                    description = "Dial a contact by name, ID, or phone number.",
                    parameters = listOf(
                        ToolParameter("contact_id", "STRING", "Contact name or phone number"),
                        ToolParameter("speakerphone", "BOOLEAN", "Enable speaker", required = false)
                    ),
                    safetyTier = SafetyTier.GREEN
                ),
                ToolDefinition(
                    name = "handle_incoming_call",
                    description = "Answer or decline ringing call.",
                    parameters = listOf(
                        ToolParameter("action", "STRING", "answer or decline")
                    ),
                    safetyTier = SafetyTier.GREEN
                )
            ),
            safetyTier = SafetyTier.GREEN,
            fewShotExamples = listOf(
                "call Mom -> {\"calls\":[{\"tool\":\"place_call\",\"args\":{\"contact_id\":\"Mom\",\"speakerphone\":\"true\"}}],\"reply\":\"\"}",
                "answer incoming call -> {\"calls\":[{\"tool\":\"handle_incoming_call\",\"args\":{\"action\":\"answer\"}}],\"reply\":\"\"}"
            )
        ),
        SkillSpec(
            id = "calendar-schedule-manager",
            title = "Calendar & Reminders",
            category = SkillCategory.C,
            description = "Check daily schedule, find free slots, and create & automatically save reminders or calendar events.",
            requiresThinking = false,
            guidance = "Use create_calendar_event to create and automatically save reminders without waiting for manual confirmation in an external editor. Pass natural time phrases ('at 5pm', 'tomorrow at 9am', 'in 30 minutes') directly in start_iso.",
            fastTriggers = listOf(
                "^(create|add|set|schedule) (a )?(reminder|event)",
                "^remind me to .+$",
                "^(what is on|check|show) (?:my )?(calendar|schedule)(?: today)?$"
            ),
            tools = listOf(
                ToolDefinition(
                    name = "query_calendar_events",
                    description = "Fetch today's local calendar schedule.",
                    parameters = listOf(
                        ToolParameter("start_iso", "STRING", "Start window"),
                        ToolParameter("end_iso", "STRING", "End window", required = false)
                    ),
                    safetyTier = SafetyTier.GREEN
                ),
                ToolDefinition(
                    name = "create_calendar_event",
                    description = "Create and automatically save a reminder or calendar event.",
                    parameters = listOf(
                        ToolParameter("title", "STRING", "Reminder or event title"),
                        ToolParameter("start_iso", "STRING", "Start time or phrase like 'at 5pm' or 'in 30 minutes'"),
                        ToolParameter("duration_min", "INTEGER", "Minutes", required = false)
                    ),
                    safetyTier = SafetyTier.GREEN
                )
            ),
            safetyTier = SafetyTier.GREEN,
            fewShotExamples = listOf(
                "what is on my schedule today -> {\"calls\":[{\"tool\":\"query_calendar_events\",\"args\":{\"start_iso\":\"today\"}}],\"reply\":\"\"}",
                "create reminder buy milk at 5pm -> {\"calls\":[{\"tool\":\"create_calendar_event\",\"args\":{\"title\":\"Buy milk\",\"start_iso\":\"at 5pm\",\"duration_min\":\"30\"}}],\"reply\":\"\"}"
            )
        ),

        // Category D: Autonomous Cross-App UI Automation
        SkillSpec(
            id = "ui-autonomous-navigator",
            title = "Autonomous UI Navigator",
            category = SkillCategory.D,
            description = "Open any installed app and inspect, tap, scroll, or type on screen. Pauses on Tier Red actions.",
            requiresThinking = true,
            guidance = "Open an app with ui_open_and_scan (or launch_app), inspect the returned clickable/editable nodes, and execute step-by-step ui_act calls. Benign dialogs auto-resolve; pause strictly before real payments, transfers, purchases, or account deletions.",
            companionTools = listOf("launch_app", "ui_find_and_tap"),
            fastTriggers = listOf(
                "^(go home|go back|open notifications|open quick settings|recent apps|take a screenshot|lock screen)$"
            ),
            tools = listOf(
                ToolDefinition(
                    name = "ui_open_and_scan",
                    description = "Launch an app and return pruned Accessibility UI node tree.",
                    parameters = listOf(
                        ToolParameter("app_name", "STRING", "App name or package")
                    ),
                    safetyTier = SafetyTier.GREEN
                ),
                ToolDefinition(
                    name = "ui_act",
                    description = "Execute autonomous UI tap, type, scroll, or OS navigation action.",
                    parameters = listOf(
                        ToolParameter("action", "STRING", "CLICK, TYPE, SCROLL_DOWN, SCROLL_UP, BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, SCREENSHOT, LOCK_SCREEN"),
                        ToolParameter("node_id", "STRING", "Target UI node ID or empty for global action"),
                        ToolParameter("text", "STRING", "Input text for TYPE", required = false)
                    ),
                    safetyTier = SafetyTier.GREEN
                ),
                ToolDefinition(
                    name = "ui_vision_fallback",
                    description = "Capture screenshot for Gemma 4 E2B multimodal analysis when node labels are missing.",
                    parameters = listOf(
                        ToolParameter("question", "STRING", "Visual query")
                    ),
                    safetyTier = SafetyTier.GREEN
                )
            ),
            safetyTier = SafetyTier.YELLOW,
            fewShotExamples = listOf(
                "send 450 to Alex on GCash -> {\"calls\":[{\"tool\":\"ui_open_and_scan\",\"args\":{\"app_name\":\"GCash\"}}],\"reply\":\"\"}",
                "tap button n14 -> {\"calls\":[{\"tool\":\"ui_act\",\"args\":{\"action\":\"CLICK\",\"node_id\":\"n14\"}}],\"reply\":\"\"}"
            )
        ),
        SkillSpec(
            id = "screen-context-assistant",
            title = "Screen Context Assistant",
            category = SkillCategory.D,
            description = "Read, summarize, translate, or extract info from the currently active screen.",
            requiresThinking = false,
            guidance = "Capture active Accessibility text and optional screenshot via capture_current_screen (or ui_vision_fallback for image-heavy views) and reply clearly in natural English without reading raw node IDs.",
            companionTools = listOf("ui_vision_fallback"),
            fastTriggers = emptyList(),
            tools = listOf(
                ToolDefinition(
                    name = "capture_current_screen",
                    description = "Grab active Accessibility text tree and optional screenshot.",
                    parameters = listOf(
                        ToolParameter("include_screenshot", "BOOLEAN", "true or false", required = false)
                    ),
                    safetyTier = SafetyTier.GREEN
                )
            ),
            safetyTier = SafetyTier.GREEN,
            fewShotExamples = listOf(
                "summarize this screen -> {\"calls\":[{\"tool\":\"capture_current_screen\",\"args\":{\"include_screenshot\":\"true\"}}],\"reply\":\"\"}"
            )
        ),
        SkillSpec(
            id = "local-screen-control",
            title = "Local Screen Control Automation",
            category = SkillCategory.D,
            description = "Local on-device Accessibility screen control process: find and tap labeled buttons, type into fields, scroll screens, or automate an app end-to-end.",
            requiresThinking = false,
            guidance = "For multi-stage app tasks ('open <app> and find/search/play <query>'), always finalize BOTH launch_app first AND screen_control_app second in 'calls'. For already-open screens, use ui_find_and_tap or ui_act(SCROLL_DOWN/UP).",
            companionTools = listOf("launch_app", "ui_act"),
            fastTriggers = listOf(
                "^(?:tap|click|press) (?:the |on )?(.+?)(?: button)?$",
                "^(?:type|enter|input) (.+?) (?:in|into) (?:the )?(.+?)$",
                "^(?:scroll|swipe) (down|up)$",
                "^(?:automate|control) (.+?) (?:to|and) (.+)$"
            ),
            tools = listOf(
                ToolDefinition(
                    name = "screen_control_app",
                    description = "Open an installed app (or drive the already-opened foreground app) and run local Accessibility screen automation (auto-clear dialogs, search, play, tap, or scroll).",
                    parameters = listOf(
                        ToolParameter("app_name", "STRING", "Installed app name or alias like YT Music, YouTube, Gallery, Spotify"),
                        ToolParameter("goal", "STRING", "Automation goal: search_on_screen, search_and_play, play, search, scroll_down, scroll_up, or tap <label>"),
                        ToolParameter("query_text", "STRING", "Optional text or media query to type on screen", required = false)
                    ),
                    safetyTier = SafetyTier.GREEN
                ),
                ToolDefinition(
                    name = "ui_find_and_tap",
                    description = "Find a UI element on the active screen by visible label and click or type into it locally.",
                    parameters = listOf(
                        ToolParameter("target_label", "STRING", "Visible text or button name on screen"),
                        ToolParameter("action_type", "STRING", "click or type", required = false),
                        ToolParameter("input_text", "STRING", "Text to type when action_type is type", required = false)
                    ),
                    safetyTier = SafetyTier.GREEN
                )
            ),
            safetyTier = SafetyTier.YELLOW,
            fewShotExamples = listOf(
                "open youtube and find a christmas song -> {\"calls\":[{\"tool\":\"launch_app\",\"args\":{\"app_name\":\"YouTube\"}},{\"tool\":\"screen_control_app\",\"args\":{\"app_name\":\"YouTube\",\"goal\":\"search_on_screen\",\"query_text\":\"christmas song\"}}],\"reply\":\"\"}",
                "tap Play -> {\"calls\":[{\"tool\":\"ui_find_and_tap\",\"args\":{\"target_label\":\"Play\",\"action_type\":\"click\"}}],\"reply\":\"\"}",
                "type lo-fi beats into Search -> {\"calls\":[{\"tool\":\"ui_find_and_tap\",\"args\":{\"target_label\":\"Search\",\"action_type\":\"type\",\"input_text\":\"lo-fi beats\"}}],\"reply\":\"\"}",
                "automate YouTube Music to play jazz -> {\"calls\":[{\"tool\":\"screen_control_app\",\"args\":{\"app_name\":\"YouTube Music\",\"goal\":\"search_and_play\",\"query_text\":\"jazz\"}}],\"reply\":\"\"}"
            )
        ),

        // Category E: Smart Home (Disabled — focusing on core phone skills) & Multi-Step Workflows
        SkillSpec(
            id = "smart-home-controller",
            title = "Smart Home Controller",
            category = SkillCategory.E,
            description = "Disabled: Smart home controls are deferred while focusing on core device, search, and UI skills.",
            requiresThinking = false,
            guidance = "Disabled placeholder skill; inform the user smart home device control is disabled while focusing on core phone skills.",
            fastTriggers = emptyList(),
            tools = listOf(
                ToolDefinition(
                    name = "control_smart_device",
                    description = "Control a local smart device state or target value.",
                    parameters = listOf(
                        ToolParameter("device_name", "STRING", "ac, lights, tv, router"),
                        ToolParameter("command", "STRING", "on, off, set_temp, mode"),
                        ToolParameter("value", "STRING", "Optional value", required = false)
                    ),
                    safetyTier = SafetyTier.GREEN
                ),
                ToolDefinition(
                    name = "get_device_status",
                    description = "Read current device status.",
                    parameters = listOf(
                        ToolParameter("device_name", "STRING", "Device ID or name")
                    ),
                    safetyTier = SafetyTier.GREEN
                )
            ),
            safetyTier = SafetyTier.GREEN,
            fewShotExamples = emptyList(),
            enabled = false
        ),
        SkillSpec(
            id = "compound-routine-orchestrator",
            title = "Compound & Recorded Routine Orchestrator",
            category = SkillCategory.E,
            description = "Record, customize, and execute multi-step personal or recorded routines (per-app or global) with taps, swipes, custom step delays, and IF/ELSE conditional statements, plus floating routine controls.",
            requiresThinking = true,
            guidance = "Use run_recorded_routine to execute any user-recorded routine by name (either global or scoped to a specific app), control_routine_recorder to show/hide the floating routine bar or start/stop recording, and get_routine_steps for built-in routines ('morning', 'focus', 'bedtime', 'sync').",
            companionTools = listOf(
                "get_device_briefing",
                "set_hardware_state",
                "create_timer",
                "read_recent_messages",
                "query_calendar_events",
                "launch_app"
            ),
            fastTriggers = listOf(
                "^good morning(?: nova)?$",
                "^(?:start |activate )?(?:focus mode|focus routine)$",
                "^(?:good night|bedtime(?: routine)?|start bedtime)$",
                "^sync (?:my )?(?:phone(?: info)?|contacts|apps|calendar)$",
                "^(?:show|open|hide|close) (?:the )?floating routine(?: control| bar)?$",
                "^(?:start )?record(?:ing)? (?:a )?(?:new )?routine(?: .+)?$",
                "^stop (?:recording )?(?:the )?routine$",
                "^(?:run|start|execute|play) (?:the |my )?(?:recorded )?routine .+$",
                "^(?:list|show) (?:my )?(?:recorded )?routines$"
            ),
            tools = listOf(
                ToolDefinition(
                    name = "get_routine_steps",
                    description = "Run a named personal or built-in routine (morning, focus, bedtime, sync).",
                    parameters = listOf(
                        ToolParameter("routine_name", "STRING", "Routine name (e.g. morning, focus, bedtime, sync, or saved routine name)")
                    ),
                    safetyTier = SafetyTier.GREEN
                ),
                ToolDefinition(
                    name = "run_recorded_routine",
                    description = "Execute a user-recorded conditional routine with its configured taps, swipes, delays, and IF conditions.",
                    parameters = listOf(
                        ToolParameter("routine_name", "STRING", "Recorded routine name or ID"),
                        ToolParameter("scope_app", "STRING", "Optional app filter when matching per-app routines", required = false),
                        ToolParameter("override_delay_ms", "INTEGER", "Optional custom delay in ms between steps", required = false)
                    ),
                    safetyTier = SafetyTier.YELLOW
                ),
                ToolDefinition(
                    name = "control_routine_recorder",
                    description = "Show or hide the floating routine control bar, start/stop recording, or list saved recorded routines.",
                    parameters = listOf(
                        ToolParameter("action", "STRING", "show_floating, hide_floating, start, stop, list"),
                        ToolParameter("routine_name", "STRING", "Optional routine name", required = false),
                        ToolParameter("target_scope", "STRING", "'global' or app name for per-app recording", required = false)
                    ),
                    safetyTier = SafetyTier.GREEN
                ),
                ToolDefinition(
                    name = "store_turn_artifact",
                    description = "Save intermediate step output across multi-step turn.",
                    parameters = listOf(
                        ToolParameter("key", "STRING", "Artifact key"),
                        ToolParameter("value", "STRING", "Artifact value")
                    ),
                    safetyTier = SafetyTier.GREEN
                )
            ),
            safetyTier = SafetyTier.YELLOW,
            fewShotExamples = listOf(
                "run routine Morning Check -> {\"calls\":[{\"tool\":\"run_recorded_routine\",\"args\":{\"routine_name\":\"Morning Check\"}}],\"reply\":\"\"}",
                "record a routine for YouTube -> {\"calls\":[{\"tool\":\"control_routine_recorder\",\"args\":{\"action\":\"start\",\"target_scope\":\"YouTube\"}}],\"reply\":\"\"}",
                "show floating routine control -> {\"calls\":[{\"tool\":\"control_routine_recorder\",\"args\":{\"action\":\"show_floating\"}}],\"reply\":\"\"}",
                "good morning -> {\"calls\":[{\"tool\":\"get_routine_steps\",\"args\":{\"routine_name\":\"morning\"}}],\"reply\":\"\"}"
            )
        ),
        SkillSpec(
            id = "app-knowledge-controller",
            title = "Installed Apps & Control Guide",
            category = SkillCategory.D,
            description = "Discover installed apps on the phone, inspect what each app can do, and launch apps with fuzzy alias matching.",
            requiresThinking = false,
            guidance = "Use list_installed_apps to inspect apps by category or capability, get_app_skills to explain how Nova controls an app, and launch_app with tolerance for typos/aliases (e.g. 'yt music' -> 'YT Music').",
            fastTriggers = listOf(
                "^open (?:the )?(.+?)(?: app)?$",
                "^(what apps|list apps|which apps)(?: do i have| are installed)?$"
            ),
            tools = listOf(
                ToolDefinition(
                    name = "list_installed_apps",
                    description = "List installed apps, categories, and how Nova can control them.",
                    parameters = listOf(
                        ToolParameter("filter", "STRING", "Category, capability, or 'all'", required = false)
                    ),
                    safetyTier = SafetyTier.GREEN
                ),
                ToolDefinition(
                    name = "launch_app",
                    description = "Launch an installed app by name or category (supports fuzzy name match).",
                    parameters = listOf(
                        ToolParameter("app_name", "STRING", "App name or category like YouTube Music, Maps, Spotify")
                    ),
                    safetyTier = SafetyTier.GREEN
                ),
                ToolDefinition(
                    name = "get_app_skills",
                    description = "Inspect what actions and skills an installed app supports.",
                    parameters = listOf(
                        ToolParameter("app_name", "STRING", "Installed app name")
                    ),
                    safetyTier = SafetyTier.GREEN
                )
            ),
            safetyTier = SafetyTier.GREEN,
            fewShotExamples = listOf(
                "what music apps do i have -> {\"calls\":[{\"tool\":\"list_installed_apps\",\"args\":{\"filter\":\"audio\"}}],\"reply\":\"\"}",
                "open spotify -> {\"calls\":[{\"tool\":\"launch_app\",\"args\":{\"app_name\":\"Spotify\"}}],\"reply\":\"\"}",
                "what can spotify do -> {\"calls\":[{\"tool\":\"get_app_skills\",\"args\":{\"app_name\":\"Spotify\"}}],\"reply\":\"\"}"
            )
        ),
        SkillSpec(
            id = "app-media-streaming",
            title = "App Media & Streaming",
            category = SkillCategory.D,
            description = "Open music, video, or podcast apps (YouTube Music, Spotify, YouTube, VLC) and play random or specific songs, artists, or playlists.",
            requiresThinking = false,
            guidance = "Pass query='random' when the user asks generically to play music or a random song. When the user names a specific song with prepositions (e.g. 'play christmas in our hearts'), keep the entire title in query and leave app_name empty unless an installed app was explicitly specified.",
            companionTools = listOf("launch_app", "screen_control_app"),
            fastTriggers = listOf(
                "^open (.+?) and play (.+)$",
                "^play (.+?) (?:on|in) (.+)$",
                "^play (?:me )?(?:music|a random song|.+)$"
            ),
            tools = listOf(
                ToolDefinition(
                    name = "play_in_app",
                    description = "Open an installed music or video app and start playing a song, artist, playlist, or 'random' track inside it.",
                    parameters = listOf(
                        ToolParameter("app_name", "STRING", "Target app such as YouTube Music, Spotify, or YouTube (empty for default media app)"),
                        ToolParameter("query", "STRING", "Song title, artist, playlist, or 'random'", required = false),
                        ToolParameter("media_type", "STRING", "song, album, playlist, video", required = false)
                    ),
                    safetyTier = SafetyTier.GREEN
                )
            ),
            safetyTier = SafetyTier.GREEN,
            fewShotExamples = listOf(
                "play christmas in our hearts -> {\"calls\":[{\"tool\":\"play_in_app\",\"args\":{\"app_name\":\"\",\"query\":\"christmas in our hearts\",\"media_type\":\"song\"}}],\"reply\":\"\"}",
                "open youtube music and play a song -> {\"calls\":[{\"tool\":\"play_in_app\",\"args\":{\"app_name\":\"YouTube Music\",\"query\":\"random\",\"media_type\":\"song\"}}],\"reply\":\"\"}",
                "play Bohemian Rhapsody on Spotify -> {\"calls\":[{\"tool\":\"play_in_app\",\"args\":{\"app_name\":\"Spotify\",\"query\":\"Bohemian Rhapsody\",\"media_type\":\"song\"}}],\"reply\":\"\"}"
            )
        ),
        SkillSpec(
            id = "app-search-and-action",
            title = "In-App Search & Navigation",
            category = SkillCategory.D,
            description = "Search inside installed apps (YouTube, YT Music, Play Store, Gallery/Photos, Chrome, Shopping) via app intents or automatic local screen control, or start directions in Maps/Waze.",
            requiresThinking = false,
            guidance = "Use search_in_app to search third-party apps via native intents or automatic local UI automation fallback (for apps like Gallery/Photos without search intents). Use navigate_in_maps for directions.",
            companionTools = listOf("launch_app", "screen_control_app"),
            fastTriggers = listOf(
                "^search (?:for )?(.+?) (?:on|in|inside) (.+)$",
                "^search (?:in |on |inside )?(.+?) for (.+)$",
                "^open (.+?) and search (?:for )?(.+)$",
                "^(?:navigate|directions|drive) to (.+)$"
            ),
            tools = listOf(
                ToolDefinition(
                    name = "search_in_app",
                    description = "Search inside any installed app (uses app search/deep-link intent if supported, or falls back automatically to local screen control automation).",
                    parameters = listOf(
                        ToolParameter("app_name", "STRING", "Installed app name such as YouTube, Play Store, Gallery, Photos, Chrome, or YT Music"),
                        ToolParameter("query", "STRING", "Search query")
                    ),
                    safetyTier = SafetyTier.GREEN
                ),
                ToolDefinition(
                    name = "navigate_in_maps",
                    description = "Start navigation or place lookup in Google Maps, Waze, or another maps app.",
                    parameters = listOf(
                        ToolParameter("destination", "STRING", "Address or place name"),
                        ToolParameter("app_name", "STRING", "Maps app preference", required = false)
                    ),
                    safetyTier = SafetyTier.GREEN
                )
            ),
            safetyTier = SafetyTier.GREEN,
            fewShotExamples = listOf(
                "search lo-fi beats in youtube -> {\"calls\":[{\"tool\":\"search_in_app\",\"args\":{\"app_name\":\"YouTube\",\"query\":\"lo-fi beats\"}}],\"reply\":\"\"}",
                "search playstore for spotify -> {\"calls\":[{\"tool\":\"search_in_app\",\"args\":{\"app_name\":\"Play Store\",\"query\":\"spotify\"}}],\"reply\":\"\"}",
                "search gallery for sunset -> {\"calls\":[{\"tool\":\"search_in_app\",\"args\":{\"app_name\":\"Gallery\",\"query\":\"sunset\"}}],\"reply\":\"\"}",
                "navigate to nearest coffee shop -> {\"calls\":[{\"tool\":\"navigate_in_maps\",\"args\":{\"destination\":\"nearest coffee shop\"}}],\"reply\":\"\"}"
            )
        ),
        SkillSpec(
            id = "alexa-utility-briefings",
            title = "Daily Briefings & Lists",
            category = SkillCategory.B,
            description = "Agentic daily/weekly briefings personalized by tone, learned user processes, 14-day notifications, and calendar, plus Shopping & To-Do lists.",
            requiresThinking = false,
            guidance = "Use get_device_briefing for personalized morning/daily/weekly briefings weaving active personality, learned user processes for this time of day, recent interaction context, 24h vs past-week notifications, carry-over reminders, and calendar events. Use manage_list_item for adding, listing, removing, or clearing shopping/todo items.",
            companionTools = listOf("query_calendar_events", "read_recent_messages"),
            fastTriggers = listOf(
                "^what (?:time|day|date) is it$",
                "^(?:check |what is my )?battery(?: level)?$",
                "^(?:give me (?:a |my )?)?(?:morning|daily|weekly) briefing$",
                "^add (.+) to (?:my )?(shopping|todo|to-do) list$",
                "^(?:read|show|what is on) (?:my )?(shopping|todo|to-do) list$"
            ),
            tools = listOf(
                ToolDefinition(
                    name = "manage_list_item",
                    description = "Manage local Shopping or To-Do list items in local storage.",
                    parameters = listOf(
                        ToolParameter("list_name", "STRING", "shopping or todo"),
                        ToolParameter("action", "STRING", "add, list, remove, clear"),
                        ToolParameter("item", "STRING", "Item text", required = false)
                    ),
                    safetyTier = SafetyTier.GREEN
                ),
                ToolDefinition(
                    name = "get_device_briefing",
                    description = "Get live time/date, battery, connectivity, 24h/weekly briefing.",
                    parameters = listOf(
                        ToolParameter("topic", "STRING", "time_date, battery, connectivity, morning, weekly")
                    ),
                    safetyTier = SafetyTier.GREEN
                )
            ),
            safetyTier = SafetyTier.GREEN,
            fewShotExamples = listOf(
                "add coffee to my shopping list -> {\"calls\":[{\"tool\":\"manage_list_item\",\"args\":{\"list_name\":\"shopping\",\"action\":\"add\",\"item\":\"coffee\"}}],\"reply\":\"\"}",
                "give me a morning briefing -> {\"calls\":[{\"tool\":\"get_device_briefing\",\"args\":{\"topic\":\"morning\"}}],\"reply\":\"\"}"
            )
        ),
        SkillSpec(
            id = "general-knowledge-qa",
            title = "General Knowledge & Web Facts",
            category = SkillCategory.B,
            description = "Answer factual, trivia, history, geography, science, and general knowledge questions directly in plain speech or via live web fact lookup — without opening Chrome or reading links/button labels.",
            requiresThinking = false,
            guidance = "For general factual questions ('what is the highest mountain in the philippines', 'who is...', 'capital of...'), NEVER call launch_app or search_in_app on Google/Chrome. Answer concisely in 1-2 plain spoken sentences in 'reply' with calls=[], OR call answer_web_question(query) for live web facts. Never include URLs, domain breadcrumbs, button names, or technical labels.",
            fastTriggers = listOf(
                "^(?:google|search\\s+(?:google|the\\s+web|online)\\s+for|look\\s+up)\\s+(.+)$"
            ),
            tools = listOf(
                ToolDefinition(
                    name = "answer_web_question",
                    description = "Look up a concise 1-2 sentence factual answer over live web knowledge sources without reading raw browser links or buttons.",
                    parameters = listOf(
                        ToolParameter("query", "STRING", "The factual question or topic to answer cleanly")
                    ),
                    safetyTier = SafetyTier.GREEN
                )
            ),
            safetyTier = SafetyTier.GREEN,
            fewShotExamples = listOf(
                "what is the highest mountain in the philippines -> {\"calls\":[],\"reply\":\"The highest mountain in the Philippines is Mount Apo, standing at about 2,954 meters above sea level in Mindanao.\"}",
                "who invented the telephone -> {\"calls\":[],\"reply\":\"Alexander Graham Bell is credited with inventing and patenting the first practical telephone in 1876.\"}",
                "google population of Japan -> {\"calls\":[{\"tool\":\"answer_web_question\",\"args\":{\"query\":\"population of Japan\"}}],\"reply\":\"\"}"
            )
        )
    )

    val enabledSkills: List<SkillSpec>
        get() = allSkills.filter { it.enabled }

    fun findById(id: String): SkillSpec? = allSkills.firstOrNull { it.id == id }
}
