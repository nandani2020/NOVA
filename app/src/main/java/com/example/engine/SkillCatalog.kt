package com.example.engine

import com.example.data.SafetyTier
import com.example.data.SkillCategory
import com.example.data.SkillSpec
import com.example.data.ToolDefinition
import com.example.data.ToolParameter

/**
 * Progressive Disclosure Skill Catalog implementing all 12 SKILL.md specs
 * across Categories A-E. Every tool strictly adheres to <= 3 parameters.
 */
object SkillCatalog {

    val allSkills: List<SkillSpec> = listOf(
        // Category A: Instant Device & Hardware Reflexes
        SkillSpec(
            id = "device-hardware-control",
            title = "Device Hardware Control",
            category = SkillCategory.A,
            description = "Toggle Wi-Fi, Bluetooth, Flashlight, Airplane Mode, Do Not Disturb, brightness, or volume.",
            requiresThinking = false,
            fastTriggers = listOf(
                "^(turn on|turn off|toggle) (flashlight|torch|wifi|wi-fi|bluetooth|dnd|do not disturb)$",
                "^set (volume|brightness) to (\\d+)%?$",
                "^(mute|unmute) phone$",
                "^(go home|go back|open notifications|open quick settings|recent apps|take a screenshot|lock screen)$"
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
                    description = "Set device volume or brightness percentage.",
                    parameters = listOf(
                        ToolParameter("target", "STRING", "volume or brightness"),
                        ToolParameter("percent", "INTEGER", "0 to 100")
                    ),
                    safetyTier = SafetyTier.GREEN
                )
            ),
            safetyTier = SafetyTier.GREEN,
            fewShotExamples = listOf(
                "turn on flashlight -> {\"calls\":[{\"tool\":\"set_hardware_state\",\"args\":{\"feature\":\"flashlight\",\"state\":\"on\"}}],\"reply\":\"\"}",
                "set volume to 65% -> {\"calls\":[{\"tool\":\"set_level\",\"args\":{\"target\":\"volume\",\"percent\":\"65\"}}],\"reply\":\"\"}",
                "mute phone -> {\"calls\":[{\"tool\":\"set_hardware_state\",\"args\":{\"feature\":\"mute\",\"state\":\"on\"}}],\"reply\":\"\"}"
            )
        ),
        SkillSpec(
            id = "clock-alarms-timers",
            title = "Clock, Alarms & Timers",
            category = SkillCategory.A,
            description = "Set, cancel, check, or snooze alarms, countdown timers, and stopwatches.",
            requiresThinking = false,
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
                    description = "Play a specific song or playlist.",
                    parameters = listOf(
                        ToolParameter("query", "STRING", "Track or playlist"),
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
            description = "Search local files, PDFs, screenshots, downloads, notes, contacts, and messages via SQLite FTS5.",
            requiresThinking = false,
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
            title = "Personal Memory Vault",
            category = SkillCategory.B,
            description = "Save and recall personal facts, parking spots, locker codes, and reminders locally.",
            requiresThinking = false,
            fastTriggers = emptyList(),
            tools = listOf(
                ToolDefinition(
                    name = "save_memory_note",
                    description = "Store a personal memory note locally in FTS5.",
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
                )
            ),
            safetyTier = SafetyTier.GREEN,
            fewShotExamples = listOf(
                "remember I parked on Basement 2 pillar B4 -> {\"calls\":[{\"tool\":\"save_memory_note\",\"args\":{\"key\":\"parking_spot\",\"value\":\"Basement 2 pillar B4\"}}],\"reply\":\"\"}",
                "where did I park -> {\"calls\":[{\"tool\":\"recall_memory_note\",\"args\":{\"query\":\"parking\"}}],\"reply\":\"\"}"
            )
        ),

        // Category C: Communications & Scheduling
        SkillSpec(
            id = "messaging-omni-send",
            title = "Messaging Omni-Send",
            category = SkillCategory.C,
            description = "Summarize unread notifications and send SMS, WhatsApp, Messenger, or Viber messages.",
            requiresThinking = false,
            fastTriggers = emptyList(),
            tools = listOf(
                ToolDefinition(
                    name = "read_recent_messages",
                    description = "Read recent unread incoming notifications/messages.",
                    parameters = listOf(
                        ToolParameter("contact_id", "STRING", "Short ID or 'all'"),
                        ToolParameter("limit", "INTEGER", "Max messages", required = false)
                    ),
                    safetyTier = SafetyTier.GREEN
                ),
                ToolDefinition(
                    name = "message_send",
                    description = "Send a message or SMS to a contact via short ID.",
                    parameters = listOf(
                        ToolParameter("contact_id", "STRING", "Short contact ID like c01"),
                        ToolParameter("text", "STRING", "Message content"),
                        ToolParameter("channel", "STRING", "auto, sms, whatsapp, messenger", required = false)
                    ),
                    safetyTier = SafetyTier.YELLOW
                )
            ),
            safetyTier = SafetyTier.YELLOW,
            fewShotExamples = listOf(
                "summarize unread texts -> {\"calls\":[{\"tool\":\"read_recent_messages\",\"args\":{\"contact_id\":\"all\",\"limit\":\"5\"}}],\"reply\":\"\"}",
                "text Mom I am on my way -> {\"calls\":[{\"tool\":\"message_send\",\"args\":{\"contact_id\":\"c01\",\"text\":\"I am on my way\",\"channel\":\"whatsapp\"}}],\"reply\":\"\"}"
            )
        ),
        SkillSpec(
            id = "phone-calls-manager",
            title = "Phone Calls Manager",
            category = SkillCategory.C,
            description = "Place voice calls, answer or decline incoming calls on speakerphone.",
            requiresThinking = false,
            fastTriggers = listOf(
                "^call (.+)$",
                "^(answer|decline|reject) call$"
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
                    description = "Dial a contact using short ID.",
                    parameters = listOf(
                        ToolParameter("contact_id", "STRING", "Short contact ID or number"),
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
                "call Mom -> {\"calls\":[{\"tool\":\"place_call\",\"args\":{\"contact_id\":\"c01\",\"speakerphone\":\"true\"}}],\"reply\":\"\"}"
            )
        ),
        SkillSpec(
            id = "calendar-schedule-manager",
            title = "Calendar & Schedule",
            category = SkillCategory.C,
            description = "Check daily schedule, find free slots, and create local calendar events.",
            requiresThinking = false,
            fastTriggers = emptyList(),
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
                    description = "Create a new calendar event.",
                    parameters = listOf(
                        ToolParameter("title", "STRING", "Event title"),
                        ToolParameter("start_iso", "STRING", "Start time"),
                        ToolParameter("duration_min", "INTEGER", "Minutes", required = false)
                    ),
                    safetyTier = SafetyTier.YELLOW
                )
            ),
            safetyTier = SafetyTier.YELLOW,
            fewShotExamples = listOf(
                "what is on my schedule today -> {\"calls\":[{\"tool\":\"query_calendar_events\",\"args\":{\"start_iso\":\"today\"}}],\"reply\":\"\"}"
            )
        ),

        // Category D: Autonomous Cross-App UI Automation
        SkillSpec(
            id = "ui-autonomous-navigator",
            title = "Autonomous UI Navigator",
            category = SkillCategory.D,
            description = "Open any installed app and inspect, tap, scroll, or type on screen. Pauses on Tier Red actions.",
            requiresThinking = true,
            fastTriggers = emptyList(),
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
            description = "Read, summarize, or extract info from the currently active screen.",
            requiresThinking = false,
            fastTriggers = listOf(
                "^(what is on my screen|whats on my screen|summarize this screen|read this page|translate this screen)$"
            ),
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
                "summarize this screen -> {\"calls\":[{\"tool\":\"capture_current_screen\",\"args\":{\"include_screenshot\":\"false\"}}],\"reply\":\"\"}"
            )
        ),

        // Category E: Smart Home (Disabled — focusing on core phone skills) & Multi-Step Workflows
        SkillSpec(
            id = "smart-home-controller",
            title = "Smart Home Controller",
            category = SkillCategory.E,
            description = "Disabled: Smart home controls are deferred while focusing on core device, search, and UI skills.",
            requiresThinking = false,
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
            title = "Compound Routine Orchestrator",
            category = SkillCategory.E,
            description = "Execute multi-step personal routines combining search, messaging, UI navigation, and device settings.",
            requiresThinking = true,
            fastTriggers = emptyList(),
            tools = listOf(
                ToolDefinition(
                    name = "get_routine_steps",
                    description = "Load steps for a named personal routine.",
                    parameters = listOf(
                        ToolParameter("routine_name", "STRING", "morning, focus, sync")
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
                "sync my phone info -> {\"calls\":[{\"tool\":\"get_routine_steps\",\"args\":{\"routine_name\":\"sync\"}}],\"reply\":\"\"}"
            )
        ),
        SkillSpec(
            id = "app-knowledge-controller",
            title = "Installed Apps & Control Guide",
            category = SkillCategory.D,
            description = "Discover installed apps on the phone, inspect what each app can do, and launch or hand off to screen automation.",
            requiresThinking = false,
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
            description = "Open music, video, or podcast apps (YouTube Music, Spotify, YouTube, VLC) and play songs, artists, or playlists inside them.",
            requiresThinking = false,
            fastTriggers = listOf(
                "^open (.+?) and play (.+)$",
                "^play (.+?) (?:on|in) (.+)$"
            ),
            tools = listOf(
                ToolDefinition(
                    name = "play_in_app",
                    description = "Open an installed music or video app and start playing a song, artist, or playlist inside it.",
                    parameters = listOf(
                        ToolParameter("app_name", "STRING", "Target app such as YouTube Music, Spotify, or YouTube"),
                        ToolParameter("query", "STRING", "Song title, artist, playlist, or 'music'", required = false),
                        ToolParameter("media_type", "STRING", "song, album, playlist, video", required = false)
                    ),
                    safetyTier = SafetyTier.GREEN
                )
            ),
            safetyTier = SafetyTier.GREEN,
            fewShotExamples = listOf(
                "open youtube music and play a song -> {\"calls\":[{\"tool\":\"play_in_app\",\"args\":{\"app_name\":\"YouTube Music\",\"query\":\"song\",\"media_type\":\"song\"}}],\"reply\":\"\"}",
                "play Bohemian Rhapsody on Spotify -> {\"calls\":[{\"tool\":\"play_in_app\",\"args\":{\"app_name\":\"Spotify\",\"query\":\"Bohemian Rhapsody\",\"media_type\":\"song\"}}],\"reply\":\"\"}"
            )
        ),
        SkillSpec(
            id = "app-search-and-action",
            title = "In-App Search & Navigation",
            category = SkillCategory.D,
            description = "Search inside installed apps (Browser, YouTube, Shopping, Notes) or start directions inside Maps/Waze.",
            requiresThinking = false,
            fastTriggers = listOf(
                "^search (.+?) (?:on|in) (.+)$",
                "^(?:navigate|directions|drive) to (.+)$"
            ),
            tools = listOf(
                ToolDefinition(
                    name = "search_in_app",
                    description = "Open an installed app and search for a topic inside that app.",
                    parameters = listOf(
                        ToolParameter("app_name", "STRING", "Installed app name such as YouTube, Chrome, or Gmail"),
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
                "navigate to nearest coffee shop -> {\"calls\":[{\"tool\":\"navigate_in_maps\",\"args\":{\"destination\":\"nearest coffee shop\"}}],\"reply\":\"\"}"
            )
        ),
        SkillSpec(
            id = "alexa-utility-briefings",
            title = "Daily Briefings & Lists",
            category = SkillCategory.B,
            description = "Alexa-style time/date/battery status, morning briefings, and persistent local Shopping & To-Do lists.",
            requiresThinking = false,
            fastTriggers = listOf(
                "^what (?:time|day|date) is it$",
                "^(?:check |what is my )?battery(?: level)?$",
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
                    description = "Get live time/date, battery, connectivity, or morning briefing.",
                    parameters = listOf(
                        ToolParameter("topic", "STRING", "time_date, battery, connectivity, morning")
                    ),
                    safetyTier = SafetyTier.GREEN
                )
            ),
            safetyTier = SafetyTier.GREEN,
            fewShotExamples = listOf(
                "add coffee to my shopping list -> {\"calls\":[{\"tool\":\"manage_list_item\",\"args\":{\"list_name\":\"shopping\",\"action\":\"add\",\"item\":\"coffee\"}}],\"reply\":\"\"}",
                "give me a morning briefing -> {\"calls\":[{\"tool\":\"get_device_briefing\",\"args\":{\"topic\":\"morning\"}}],\"reply\":\"\"}"
            )
        )
    )

    val enabledSkills: List<SkillSpec>
        get() = allSkills.filter { it.enabled }

    fun findById(id: String): SkillSpec? = allSkills.firstOrNull { it.id == id }
}
