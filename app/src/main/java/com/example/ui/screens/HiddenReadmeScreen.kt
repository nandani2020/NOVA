package com.example.ui.screens

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.BuildConfig
import com.example.data.SafetyTier
import com.example.data.SkillSpec
import com.example.engine.SkillCatalog
import com.example.processA.NovaAccessibilityService
import com.example.processA.NovaNotificationListenerService
import com.example.ui.NovaUiState
import com.example.ui.components.GlassCard
import com.example.ui.components.NovaGradientBackground
import com.example.ui.components.NovaStatusPill
import com.example.ui.theme.NovaAmber
import com.example.ui.theme.NovaCoralRed
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaEmerald
import com.example.ui.theme.NovaGlassBorder
import com.example.ui.theme.NovaGlassFill
import com.example.ui.theme.NovaLavender
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import com.example.ui.theme.NovaViolet

enum class ReadmeSectionTab(val label: String) {
    SPECS("App Specs"),
    SKILLS("Skills"),
    ACCESS("Access & Perms"),
    FEATURES("Capabilities")
}

data class SpecEntry(
    val label: String,
    val value: String,
    val detail: String = ""
)

data class AccessAuditItem(
    val name: String,
    val permissionOrService: String,
    val category: String,
    val purpose: String,
    val isGranted: Boolean,
    val settingsIntentAction: String? = null,
    val needsPackageUri: Boolean = false
)

data class FeatureTrackItem(
    val title: String,
    val status: String,
    val summary: String,
    val components: String
)

data class NovaDocumentationSnapshot(
    val appSpecs: List<SpecEntry>,
    val runtimeSpecs: List<SpecEntry>,
    val pipelineSpecs: List<SpecEntry>,
    val storageAndComponentsSpecs: List<SpecEntry>,
    val accessItems: List<AccessAuditItem>,
    val queriedIntents: List<String>,
    val queriedPackages: List<String>,
    val skills: List<SkillSpec>,
    val features: List<FeatureTrackItem>
)

/**
 * Live internal documentation & runtime capability auditor backing the hidden README screen.
 */
object NovaSystemDocumentation {

    val manifestQueriedIntents: List<String> = listOf(
        "MAIN / LAUNCHER",
        "MAIN / APP_MUSIC",
        "MAIN / APP_MAPS",
        "MAIN / APP_BROWSER",
        "MAIN / APP_MESSAGING",
        "MAIN / APP_EMAIL",
        "MAIN / APP_CALENDAR",
        "MAIN / APP_GALLERY",
        "ACTION_DIAL",
        "ACTION_SENDTO",
        "ACTION_SEND (text/plain)",
        "MEDIA_PLAY_FROM_SEARCH",
        "ACTION_SEARCH",
        "ACTION_WEB_SEARCH",
        "VIEW (geo: / https: / market:)",
        "android.speech.RecognitionService"
    )

    val manifestQueriedPackages: List<String> = listOf(
        "com.google.android.apps.youtube.music",
        "com.google.android.youtube",
        "com.spotify.music",
        "com.apple.android.music",
        "com.amazon.mp3",
        "com.soundcloud.android",
        "com.aspiro.tidal",
        "org.videolan.vlc",
        "com.netflix.mediaclient",
        "com.google.android.apps.maps",
        "com.waze",
        "com.android.chrome",
        "com.google.android.gm",
        "com.google.android.apps.messaging",
        "com.google.android.apps.photos",
        "com.google.android.apps.photosgo",
        "com.sec.android.gallery3d",
        "com.miui.gallery",
        "com.android.gallery3d",
        "com.oneplus.gallery",
        "com.coloros.gallery3d",
        "com.vivo.gallery",
        "com.google.android.apps.docs",
        "com.google.android.keep",
        "com.google.android.calendar",
        "com.whatsapp",
        "com.facebook.orca",
        "org.telegram.messenger",
        "com.viber.voip",
        "com.discord",
        "com.globe.gcash.android",
        "com.paymaya",
        "com.android.vending",
        "com.android.settings"
    )

    fun buildSnapshot(
        context: Context,
        uiState: NovaUiState
    ): NovaDocumentationSnapshot {
        val health = uiState.processHealth
        val pkgInfo = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val versionCode = if (pkgInfo != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            (pkgInfo?.versionCode?.toLong() ?: 1L)
        }
        val versionName = pkgInfo?.versionName ?: "1.0"

        val appSpecs = listOf(
            SpecEntry("Application ID", BuildConfig.APPLICATION_ID, "Namespace: com.example"),
            SpecEntry("Version", "$versionName (code $versionCode)", "Local-first build • LiteRT-LM embedded"),
            SpecEntry("Android SDK", "minSdk 24 • targetSdk 36 • compileSdk 36.1", "Device: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"),
            SpecEntry("Device Hardware", "${Build.MANUFACTURER} ${Build.MODEL}", "ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}"),
            SpecEntry("UI & Stack", "Kotlin • Jetpack Compose M3 • Coroutines • StateFlow", "Edge-to-Edge, DataStore Preferences, SQLite"),
            SpecEntry("Discovered App Profiles", "${uiState.installedAppsForRoutines.size} cached profiles", "Fuzzy alias + tag resolver (AppCapabilityRegistry)")
        )

        val runtimeSpecs = listOf(
            SpecEntry(
                "Process A (Main / Reflexes)",
                "PID ${health.processAPid} • ${health.processAHeapMb} MB heap",
                "Hosts Compose UI, <5ms Regex Fast-Path, SQLite Index, EmbeddingGemma 2, Accessibility & Notification services."
            ),
            SpecEntry(
                "Process B (:llm Isolated Runtime)",
                "PID ${health.processBPid} • ${health.processBStatus} (${health.activeLlmBackend})",
                "Respawns: ${health.respawnCount} • NPU Preferred: ${health.preferNpuBackend} • Deep Thinking: ${if (health.thinkingEnabled) "ON" else "OFF"}"
            ),
            SpecEntry(
                "Core LLM Slot",
                uiState.llmPreset?.displayName ?: "Gemma 4 E2B IT (.litertlm)",
                if (health.modelPresent) "Installed: ${health.activeModelName} (${health.activeModelSizeMb} MB)" else "Not installed (${uiState.llmPreset?.approxSizeLabel ?: "~2.58 GB"} on disk)"
            ),
            SpecEntry(
                "Embedding Engine Slot",
                uiState.embeddingPreset?.displayName ?: "EmbeddingGemma 2 270M (.litertlm)",
                if (health.embeddingPresent) "Installed: ${health.activeEmbeddingName} (${health.activeEmbeddingSizeMb} MB) • Ready=${health.embeddingReady}" else "Not installed (${uiState.embeddingPreset?.approxSizeLabel ?: "~165 MB"} on disk)"
            ),
            SpecEntry(
                "Wake Word & Voice Engine",
                health.wakeWordStatus.name,
                "Enabled=${health.wakeWordEnabled} • Always-On FGS microphone listener + 10s follow-up window"
            )
        )

        val pipelineSpecs = listOf(
            SpecEntry("Tier 0 • InputNormalizer", "Always Active", "Strips 'Hey Nova' wake phrases, removes self-corrections, splits compound requests."),
            SpecEntry("Tier 1 • RegexFastPathEngine", "<5 ms deterministic", "Instant hardware toggles, timers/alarms, media, calls, reminders, routines & UI reflexes."),
            SpecEntry("Tier 1.5 • Progressive Disclosure", "EmbeddingGemma 2 + Lexicon", "Cosine similarity + keyword router selects top relevant skills (<=3 args/tool; smart-home excluded)."),
            SpecEntry("Tier 2/3 • Isolated Gemma 4 Planner", if (health.isLlmReady) "Ready" else "Awaiting LLM install", "Multi-stage JSON action planning, multimodal UI screenshot fallback, Deep Thinking toggle."),
            SpecEntry("Safety Guardrails", "GREEN / YELLOW / RED + Vol-Down", "Tier Red confirmation bottom sheet blocks unapproved financial/destructive taps; hardware Vol-Down aborts automation.")
        )

        val storageAndComponentsSpecs = listOf(
            SpecEntry(
                "SQLite Local Knowledge & 14-Day Archive DB (v5)",
                "${health.indexedDocCount} indexed records • ${health.unreadMessagesCount} unread alerts • Mode: ${health.ftsMode}",
                "Stores apps, contacts, calendar events, memory notes, float32 EmbeddingGemma vectors, 14-day rolling notification_archive, plus agentic_memories, behavior_events, and learned_processes tables."
            ),
            SpecEntry(
                "Agentic Memory & Personality",
                "${health.assistantPersonality.displayName} • ${health.agenticMemoryCount} memories • ${health.learnedProcessCount} learned processes",
                "Learns app/channel preferences, user corrections, recent episodes, and recurring time-of-day workflows; personalizes briefings and Gemma 4 prompts."
            ),
            SpecEntry(
                "Recorded Routines Store",
                "${uiState.recordedRoutines.size} saved routines",
                "Global & Per-App custom routines with IF/ELSE conditional logic & custom per-step delays."
            ),
            SpecEntry(
                "DataStore Preferences",
                "nova_settings",
                "User greeting, Personality mode (${health.assistantPersonality.displayName}), Wake Word state, NPU backend preference, Proactive alerts toggle, Thinking toggle."
            ),
            SpecEntry(
                "Manifest Services & Receivers",
                "6 Android components registered",
                "NovaVoiceForegroundService (mic|specialUse), NovaVoiceInteractionService & Session, NovaAccessibilityService, NovaNotificationListenerService, NovaReminderReceiver, LlmEngineService (:llm)."
            )
        )

        val accConnected = NovaAccessibilityService.instance != null ||
            NovaAccessibilityService.isServiceEnabledInSystem(context)
        val notifConnected = health.notificationListenerConnected ||
            NovaNotificationListenerService.isPermissionGranted(context)
        val dndGranted = runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.isNotificationPolicyAccessGranted == true
        }.getOrDefault(false)
        val writeSettingsGranted = runCatching { Settings.System.canWrite(context) }.getOrDefault(false)

        fun hasPerm(permission: String): Boolean =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        val accessItems = listOf(
            // Special OS Services & Privileges
            AccessAuditItem(
                name = "Accessibility UI Navigator & Overlay",
                permissionOrService = "BIND_ACCESSIBILITY_SERVICE • TYPE_ACCESSIBILITY_OVERLAY",
                category = "Special Service",
                purpose = "Reads UI node trees, injects taps/swipes, hosts Floating Routine Bar, monitors Vol-Down kill switch.",
                isGranted = accConnected,
                settingsIntentAction = Settings.ACTION_ACCESSIBILITY_SETTINGS
            ),
            AccessAuditItem(
                name = "Notification Listener",
                permissionOrService = "BIND_NOTIFICATION_LISTENER_SERVICE",
                category = "Special Service",
                purpose = "Filters incoming notifications locally and prompts only Messages, Missed Calls, and Reminders.",
                isGranted = notifConnected,
                settingsIntentAction = Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            ),
            AccessAuditItem(
                name = "Do Not Disturb Policy Access",
                permissionOrService = "android.permission.ACCESS_NOTIFICATION_POLICY",
                category = "Special Privilege",
                purpose = "Toggles device interruption filter (DND ON/OFF) deterministically.",
                isGranted = dndGranted,
                settingsIntentAction = Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
            ),
            AccessAuditItem(
                name = "Modify System Brightness",
                permissionOrService = "android.permission.WRITE_SETTINGS",
                category = "Special Privilege",
                purpose = "Reads and writes screen brightness percentage via Settings.System.",
                isGranted = writeSettingsGranted,
                settingsIntentAction = Settings.ACTION_MANAGE_WRITE_SETTINGS,
                needsPackageUri = true
            ),
            // Dangerous Runtime Permissions
            AccessAuditItem(
                name = "Microphone Audio",
                permissionOrService = "android.permission.RECORD_AUDIO",
                category = "Runtime Permission",
                purpose = "Always-On 'Hey Nova' wake-word detection and Voice Overlay speech recognition.",
                isGranted = hasPerm(Manifest.permission.RECORD_AUDIO)
            ),
            AccessAuditItem(
                name = "Post Notifications",
                permissionOrService = "android.permission.POST_NOTIFICATIONS",
                category = "Runtime Permission",
                purpose = "Displays persistent always-on assistant notification and local reminder triggers.",
                isGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    hasPerm(Manifest.permission.POST_NOTIFICATIONS)
                } else true
            ),
            AccessAuditItem(
                name = "Read Contacts",
                permissionOrService = "android.permission.READ_CONTACTS",
                category = "Runtime Permission",
                purpose = "Resolves contact names to phone numbers for calls/SMS and indexes local contacts.",
                isGranted = hasPerm(Manifest.permission.READ_CONTACTS)
            ),
            AccessAuditItem(
                name = "Read & Write Calendar",
                permissionOrService = "android.permission.READ_CALENDAR / WRITE_CALENDAR",
                category = "Runtime Permission",
                purpose = "Fetches today's schedule and inserts/saves events & reminders directly.",
                isGranted = hasPerm(Manifest.permission.READ_CALENDAR) && hasPerm(Manifest.permission.WRITE_CALENDAR)
            ),
            AccessAuditItem(
                name = "Device Accounts Lookup",
                permissionOrService = "android.permission.GET_ACCOUNTS",
                category = "Runtime Permission",
                purpose = "Detects signed-in device email to resolve single-account app chooser prompts cleanly.",
                isGranted = hasPerm(Manifest.permission.GET_ACCOUNTS)
            ),
            AccessAuditItem(
                name = "Bluetooth Connect",
                permissionOrService = "android.permission.BLUETOOTH_CONNECT",
                category = "Runtime Permission",
                purpose = "Checks Bluetooth adapter status on Android 12+.",
                isGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    hasPerm(Manifest.permission.BLUETOOTH_CONNECT)
                } else true
            ),
            AccessAuditItem(
                name = "Answer Phone Calls",
                permissionOrService = "android.permission.ANSWER_PHONE_CALLS",
                category = "Runtime Permission",
                purpose = "Hands-free accepting or ending incoming phone calls via TelecomManager.",
                isGranted = hasPerm(Manifest.permission.ANSWER_PHONE_CALLS)
            ),
            // Install-Time / Normal Permissions
            AccessAuditItem(
                name = "Network & Model Downloads",
                permissionOrService = "INTERNET",
                category = "Install-Time Permission",
                purpose = "Downloads opt-in local Gemma 4 / EmbeddingGemma .litertlm weights.",
                isGranted = true
            ),
            AccessAuditItem(
                name = "Foreground Services (Mic + Special Use)",
                permissionOrService = "FOREGROUND_SERVICE • FOREGROUND_SERVICE_MICROPHONE • FOREGROUND_SERVICE_SPECIAL_USE",
                category = "Install-Time Permission",
                purpose = "Keeps Process A reflex & wake-word service resilient in background.",
                isGranted = true
            ),
            AccessAuditItem(
                name = "Wi-Fi State & Vibration",
                permissionOrService = "ACCESS_WIFI_STATE • CHANGE_WIFI_STATE • VIBRATE",
                category = "Install-Time Permission",
                purpose = "Reads/toggles connectivity state and provides haptic cues.",
                isGranted = true
            )
        )

        val features = listOf(
            FeatureTrackItem(
                title = "Multi-Stage Global App & UI Action Planner",
                status = "ACTIVE",
                summary = "Chains native app intents (launch_app) cleanly with Accessibility screen automation (screen_control_app) across third-party apps.",
                components = "NovaPipelineRouter • RegexFastPathEngine • AppCapabilityRegistry • NovaAccessibilityService"
            ),
            FeatureTrackItem(
                title = "Routine Studio & Floating Recorder Overlay",
                status = "ACTIVE",
                summary = "Full-bar draggable floating control over any app, focus/interaction capture suppression, continuous + Tap/+ Swipe exact (x,y) capture, IF/ELSE conditions, and customizable delays.",
                components = "FloatingRoutineOverlay • RoutineRecorderManager • RoutineExecutionEngine • RoutineStudioScreen"
            ),
            FeatureTrackItem(
                title = "Important Notification Filter & 14-Day Archive",
                status = "ACTIVE",
                summary = "Archives Messages, Missed Calls, and Reminders for 14 days with read/unread state, importance carry-over reminders, and 24h vs. weekly summaries.",
                components = "ImportantNotificationFilter • NovaNotificationListenerService • LocalFtsIndexHelper"
            ),
            FeatureTrackItem(
                title = "Agentic Memory, Adaptive Personality & Habit Learning",
                status = "ACTIVE",
                summary = "Extracts app preferences and corrections, tracks episodic history, learns recurring time-of-day workflows automatically, adapts 4 personality modes, and weaves learned processes into briefings.",
                components = "AgenticMemoryEngine • LocalFtsIndexHelper (DB v5) • DeviceActionExecutor • NovaPipelineRouter"
            ),
            FeatureTrackItem(
                title = "On-Device Gemma 4 + EmbeddingGemma 2 Runtime",
                status = "ACTIVE",
                summary = "Two-process isolation (:llm Process B) with NPU->GPU->CPU fallback, Thinking mode toggle, and official query/document prompt formatting.",
                components = "LlmEngineService • LlmProcessBridge • EmbeddingGemmaRuntime • SemanticLookupEngine"
            ),
            FeatureTrackItem(
                title = "Always-On 'Hey Nova' + 10s Follow-Up Listener",
                status = "ACTIVE",
                summary = "Continuous wake-word detection with TTS barge-in ducking and automatic 10-second listening window when Nova asks a question ('Let me know what you need.' timeout).",
                components = "NovaVoiceForegroundService • NovaViewModel • VoiceOverlayScreen"
            ),
            FeatureTrackItem(
                title = "Smart Home Controller",
                status = "DISABLED",
                summary = "Explicitly disabled in SkillCatalog and Hub UI while focusing on core phone capabilities.",
                components = "SkillCatalog (smart-home-controller enabled=false)"
            )
        )

        return NovaDocumentationSnapshot(
            appSpecs = appSpecs,
            runtimeSpecs = runtimeSpecs,
            pipelineSpecs = pipelineSpecs,
            storageAndComponentsSpecs = storageAndComponentsSpecs,
            accessItems = accessItems,
            queriedIntents = manifestQueriedIntents,
            queriedPackages = manifestQueriedPackages,
            skills = SkillCatalog.allSkills,
            features = features
        )
    }

    fun buildMarkdownExport(snapshot: NovaDocumentationSnapshot): String {
        val sb = StringBuilder()
        sb.appendLine("# Nova — Internal Specs, Access & Skills README")
        sb.appendLine()
        sb.appendLine("## App Specifications")
        snapshot.appSpecs.forEach { sb.appendLine("- **${it.label}**: ${it.value} (${it.detail})") }
        sb.appendLine()
        sb.appendLine("## Two-Process Runtime & Models")
        snapshot.runtimeSpecs.forEach { sb.appendLine("- **${it.label}**: ${it.value} — ${it.detail}") }
        sb.appendLine()
        sb.appendLine("## 4-Tier Routing Pipeline")
        snapshot.pipelineSpecs.forEach { sb.appendLine("- **${it.label}**: ${it.value} — ${it.detail}") }
        sb.appendLine()
        sb.appendLine("## Persistence & Registered Components")
        snapshot.storageAndComponentsSpecs.forEach { sb.appendLine("- **${it.label}**: ${it.value} — ${it.detail}") }
        sb.appendLine()
        sb.appendLine("## OS Access & Permissions Audit")
        snapshot.accessItems.forEach { item ->
            val state = if (item.isGranted) "GRANTED" else "NOT GRANTED"
            sb.appendLine("- [$state] **${item.name}** (`${item.permissionOrService}`) — ${item.purpose}")
        }
        sb.appendLine()
        sb.appendLine("### Scoped `<queries>` Package Visibility (${snapshot.queriedPackages.size} explicit packages)")
        sb.appendLine(snapshot.queriedPackages.joinToString(", ") { "`$it`" })
        sb.appendLine()
        sb.appendLine("## Skills Catalog (${snapshot.skills.count { it.enabled }} Active / ${snapshot.skills.size} Total)")
        snapshot.skills.forEach { s ->
            val status = if (s.enabled) "ACTIVE" else "DISABLED"
            sb.appendLine("### ${s.title} (`${s.id}`) [$status • ${s.safetyTier.name}]")
            sb.appendLine(s.description)
            if (s.tools.isNotEmpty()) {
                sb.appendLine("- **Tools**: " + s.tools.joinToString(", ") { t ->
                    "${t.name}(${t.parameters.joinToString { p -> p.name }})"
                })
            }
            if (s.companionTools.isNotEmpty()) {
                sb.appendLine("- **Companions**: ${s.companionTools.joinToString(", ")}")
            }
            sb.appendLine()
        }
        return sb.toString()
    }
}

@Composable
fun HiddenReadmeScreen(
    uiState: NovaUiState,
    onDismiss: () -> Unit,
    onNotifyToast: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    BackHandler { onDismiss() }

    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(ReadmeSectionTab.SPECS) }
    var searchQuery by remember { mutableStateOf("") }

    val snapshot = remember(
        uiState.processHealth,
        uiState.recordedRoutines.size,
        uiState.installedAppsForRoutines.size
    ) {
        NovaSystemDocumentation.buildSnapshot(context, uiState)
    }

    val q = searchQuery.trim().lowercase()

    NovaGradientBackground(
        modifier = modifier
            .fillMaxSize()
            .testTag("hidden_readme_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(NovaGlassFill)
                            .testTag("close_hidden_readme_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close Hidden README",
                            tint = NovaTextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "HIDDEN DEVELOPER README",
                            style = MaterialTheme.typography.labelSmall,
                            color = NovaCyan
                        )
                        Text(
                            text = "Specs, Access & Skills",
                            style = MaterialTheme.typography.titleLarge,
                            color = NovaTextPrimary
                        )
                    }
                }

                Button(
                    onClick = {
                        val md = NovaSystemDocumentation.buildMarkdownExport(snapshot)
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("Nova README Spec", md))
                        onNotifyToast("Copied complete Markdown README to clipboard.")
                    },
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NovaViolet,
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("copy_readme_markdown_btn")
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "Copy Markdown README",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy MD", style = MaterialTheme.typography.labelLarge)
                }
            }

            // Live Summary Header + Filter Search
            Column(
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                val activeSkillsCount = snapshot.skills.count { it.enabled }
                val grantedAccessCount = snapshot.accessItems.count { it.isGranted }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NovaStatusPill(
                        text = "$activeSkillsCount/${snapshot.skills.size} SKILLS",
                        dotColor = NovaEmerald
                    )
                    NovaStatusPill(
                        text = "$grantedAccessCount/${snapshot.accessItems.size} ACCESS",
                        dotColor = if (grantedAccessCount == snapshot.accessItems.size) NovaEmerald else NovaAmber
                    )
                    NovaStatusPill(
                        text = "${snapshot.queriedPackages.size} QUERIES",
                        dotColor = NovaCyan
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            "Filter skills, tools, permissions, or specs...",
                            color = NovaTextMuted
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = "Filter documentation",
                            tint = NovaLavender
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(999.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NovaViolet,
                        unfocusedBorderColor = NovaGlassBorder,
                        focusedTextColor = NovaTextPrimary,
                        unfocusedTextColor = NovaTextPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("readme_search_input")
                )

                Spacer(modifier = Modifier.height(10.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(ReadmeSectionTab.entries) { tab ->
                        val selected = selectedTab == tab
                        val tabTitle = when (tab) {
                            ReadmeSectionTab.SKILLS -> "${tab.label} (${snapshot.skills.size})"
                            ReadmeSectionTab.ACCESS -> "${tab.label} (${snapshot.accessItems.size})"
                            else -> tab.label
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (selected) NovaViolet else Color(0x16FFFFFF))
                                .border(
                                    1.dp,
                                    if (selected) NovaLavender else NovaGlassBorder,
                                    RoundedCornerShape(999.dp)
                                )
                                .clickable { selectedTab = tab }
                                .padding(horizontal = 14.dp, vertical = 9.dp)
                                .testTag("readme_tab_${tab.name.lowercase()}")
                        ) {
                            Text(
                                text = tabTitle,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) Color.White else NovaTextSecondary
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Content Area
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .widthIn(max = 760.dp)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (selectedTab) {
                    ReadmeSectionTab.SPECS -> {
                        val filteredBuild = snapshot.appSpecs.filterMatches(q)
                        if (filteredBuild.isNotEmpty()) {
                            item {
                                SpecGroupCard(
                                    title = "BUILD & DEVICE SPECIFICATIONS",
                                    icon = Icons.Outlined.Description,
                                    accentColor = NovaCyan,
                                    entries = filteredBuild
                                )
                            }
                        }
                        val filteredRuntime = snapshot.runtimeSpecs.filterMatches(q)
                        if (filteredRuntime.isNotEmpty()) {
                            item {
                                SpecGroupCard(
                                    title = "TWO-PROCESS RUNTIME & ON-DEVICE MODELS",
                                    icon = Icons.Outlined.Memory,
                                    accentColor = NovaLavender,
                                    entries = filteredRuntime
                                )
                            }
                        }
                        val filteredPipeline = snapshot.pipelineSpecs.filterMatches(q)
                        if (filteredPipeline.isNotEmpty()) {
                            item {
                                SpecGroupCard(
                                    title = "4-TIER ROUTING & SAFETY ARCHITECTURE",
                                    icon = Icons.Outlined.AutoAwesome,
                                    accentColor = NovaEmerald,
                                    entries = filteredPipeline
                                )
                            }
                        }
                        val filteredStorage = snapshot.storageAndComponentsSpecs.filterMatches(q)
                        if (filteredStorage.isNotEmpty()) {
                            item {
                                SpecGroupCard(
                                    title = "PERSISTENCE & REGISTERED SERVICES",
                                    icon = Icons.Outlined.Security,
                                    accentColor = NovaAmber,
                                    entries = filteredStorage
                                )
                            }
                        }
                    }

                    ReadmeSectionTab.SKILLS -> {
                        val filteredSkills = snapshot.skills.filter { s ->
                            q.isEmpty() ||
                                s.title.lowercase().contains(q) ||
                                s.id.lowercase().contains(q) ||
                                s.description.lowercase().contains(q) ||
                                s.tools.any { it.name.lowercase().contains(q) } ||
                                s.companionTools.any { it.lowercase().contains(q) }
                        }
                        items(filteredSkills, key = { it.id }) { skill ->
                            ReadmeSkillCard(skill = skill)
                        }
                    }

                    ReadmeSectionTab.ACCESS -> {
                        val filteredAccess = snapshot.accessItems.filter { a ->
                            q.isEmpty() ||
                                a.name.lowercase().contains(q) ||
                                a.permissionOrService.lowercase().contains(q) ||
                                a.category.lowercase().contains(q) ||
                                a.purpose.lowercase().contains(q)
                        }
                        items(filteredAccess, key = { it.permissionOrService }) { item ->
                            AccessAuditCard(
                                item = item,
                                onOpenSettings = { action, needsPkg ->
                                    runCatching {
                                        val intent = Intent(action).apply {
                                            if (needsPkg) data = Uri.parse("package:${context.packageName}")
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(intent)
                                    }
                                }
                            )
                        }

                        val matchingIntents = snapshot.queriedIntents.filter {
                            q.isEmpty() || it.lowercase().contains(q)
                        }
                        val matchingPkgs = snapshot.queriedPackages.filter {
                            q.isEmpty() || it.lowercase().contains(q)
                        }
                        if (matchingIntents.isNotEmpty() || matchingPkgs.isNotEmpty()) {
                            item {
                                QueriesScopeCard(
                                    intents = matchingIntents,
                                    packages = matchingPkgs
                                )
                            }
                        }
                    }

                    ReadmeSectionTab.FEATURES -> {
                        val filteredFeatures = snapshot.features.filter { f ->
                            q.isEmpty() ||
                                f.title.lowercase().contains(q) ||
                                f.summary.lowercase().contains(q) ||
                                f.components.lowercase().contains(q)
                        }
                        items(filteredFeatures, key = { it.title }) { feat ->
                            GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = feat.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = NovaTextPrimary,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        val badgeColor = if (feat.status == "ACTIVE") NovaEmerald else NovaTextMuted
                                        Text(
                                            text = feat.status,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = badgeColor,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(999.dp))
                                                .background(badgeColor.copy(alpha = 0.16f))
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = feat.summary,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = NovaTextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = feat.components,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NovaCyan
                                    )
                                }
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }
}

private fun List<SpecEntry>.filterMatches(q: String): List<SpecEntry> {
    if (q.isEmpty()) return this
    return filter {
        it.label.lowercase().contains(q) ||
            it.value.lowercase().contains(q) ||
            it.detail.lowercase().contains(q)
    }
}

@Composable
private fun SpecGroupCard(
    title: String,
    icon: ImageVector,
    accentColor: Color,
    entries: List<SpecEntry>
) {
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = accentColor
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            entries.forEachIndexed { idx, entry ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x0EFFFFFF))
                        .padding(12.dp)
                ) {
                    Text(
                        text = entry.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = NovaLavender
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = entry.value,
                        style = MaterialTheme.typography.titleSmall,
                        color = NovaTextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (entry.detail.isNotBlank()) {
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = entry.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = NovaTextSecondary
                        )
                    }
                }
                if (idx < entries.lastIndex) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun ReadmeSkillCard(skill: SkillSpec) {
    val tierColor = when {
        !skill.enabled -> NovaTextMuted
        skill.safetyTier == SafetyTier.RED -> NovaCoralRed
        skill.safetyTier == SafetyTier.YELLOW -> NovaAmber
        else -> NovaEmerald
    }
    val tierLabel = if (!skill.enabled) "DISABLED" else skill.safetyTier.name

    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = skill.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (skill.enabled) NovaTextPrimary else NovaTextMuted
                    )
                    Text(
                        text = "${skill.id} • ${skill.category.title}",
                        style = MaterialTheme.typography.labelSmall,
                        color = NovaLavender
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = tierLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = tierColor,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(tierColor.copy(alpha = 0.16f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                    if (skill.requiresThinking) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "THINKING",
                            style = MaterialTheme.typography.labelSmall,
                            color = NovaCyan
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = skill.description,
                style = MaterialTheme.typography.bodyMedium,
                color = NovaTextSecondary
            )

            if (skill.guidance.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Guidance: ${skill.guidance}",
                    style = MaterialTheme.typography.bodySmall,
                    color = NovaTextMuted
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            skill.tools.forEach { tool ->
                val paramsText = if (tool.parameters.isEmpty()) {
                    "no parameters"
                } else {
                    tool.parameters.joinToString(", ") { p ->
                        val opt = if (p.required) "" else "?"
                        "${p.name}$opt:${p.type}"
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x10FFFFFF))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${tool.name}($paramsText)",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (skill.enabled) NovaCyan else NovaTextMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = tool.safetyTier.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = NovaTextMuted
                    )
                }
            }

            if (skill.companionTools.isNotEmpty() || skill.fastTriggers.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                val metaParts = buildList {
                    if (skill.fastTriggers.isNotEmpty()) add("${skill.fastTriggers.size} Tier-1 triggers")
                    if (skill.companionTools.isNotEmpty()) add("Companions: ${skill.companionTools.joinToString(", ")}")
                    if (skill.fewShotExamples.isNotEmpty()) add("${skill.fewShotExamples.size} few-shots")
                }
                Text(
                    text = metaParts.joinToString(" • "),
                    style = MaterialTheme.typography.labelSmall,
                    color = NovaLavender
                )
            }
        }
    }
}

@Composable
private fun AccessAuditCard(
    item: AccessAuditItem,
    onOpenSettings: (String, Boolean) -> Unit
) {
    val statusColor = if (item.isGranted) NovaEmerald else NovaAmber
    val statusLabel = if (item.isGranted) "GRANTED" else "NOT GRANTED"

    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.category.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = NovaLavender
                    )
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = NovaTextPrimary
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                NovaStatusPill(
                    text = statusLabel,
                    dotColor = statusColor
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = item.permissionOrService,
                style = MaterialTheme.typography.labelSmall,
                color = NovaCyan
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = item.purpose,
                style = MaterialTheme.typography.bodyMedium,
                color = NovaTextSecondary
            )
            if (!item.isGranted && item.settingsIntentAction != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Open System Settings ->",
                    style = MaterialTheme.typography.labelLarge,
                    color = NovaAmber,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(NovaAmber.copy(alpha = 0.14f))
                        .clickable { onOpenSettings(item.settingsIntentAction, item.needsPackageUri) }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QueriesScopeCard(
    intents: List<String>,
    packages: List<String>
) {
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "SCOPED <QUERIES> VISIBILITY (${intents.size} INTENTS • ${packages.size} PACKAGES)",
                style = MaterialTheme.typography.labelSmall,
                color = NovaCyan
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (intents.isNotEmpty()) {
                Text(
                    text = "Intent Categories & Actions",
                    style = MaterialTheme.typography.labelMedium,
                    color = NovaLavender
                )
                Spacer(modifier = Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    intents.forEach { entry ->
                        Text(
                            text = entry,
                            style = MaterialTheme.typography.labelSmall,
                            color = NovaTextPrimary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0x16FFFFFF))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
            if (packages.isNotEmpty()) {
                Text(
                    text = "Explicit Package Queries",
                    style = MaterialTheme.typography.labelMedium,
                    color = NovaLavender
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = packages.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    color = NovaTextSecondary
                )
            }
        }
    }
}
