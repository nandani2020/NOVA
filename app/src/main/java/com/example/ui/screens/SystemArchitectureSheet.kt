package com.example.ui.screens

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.data.AgenticMemoryType
import com.example.data.AsrEngineOption
import com.example.data.AssistantPersonality
import com.example.data.LearnedProcessRecord
import com.example.data.ModelDownloadState
import com.example.data.ModelSlot
import com.example.data.TtsEngineOption
import com.example.data.WakeWordStatus
import com.example.engine.SkillCatalog
import com.example.ui.NovaUiState
import com.example.ui.components.GlassCard
import com.example.ui.components.NovaStatusPill
import com.example.ui.theme.NovaAmber
import com.example.ui.theme.NovaBgTop
import com.example.ui.theme.NovaCoralRed
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaEmerald
import com.example.ui.theme.NovaGlassBorder
import com.example.ui.theme.NovaGlassFill
import com.example.ui.theme.NovaLavender
import com.example.ui.theme.NovaSurfaceDeep
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import com.example.ui.theme.NovaViolet

/**
 * Nova Settings & On-Device Engine Screen:
 * - Gemma 4 E2B IT (.litertlm) & EmbeddingGemma 2 270M (.litertlm) download / SAF import / delete
 * - Always-On "Hey Nova" wake-word toggle
 * - Two-Process PID / Heap telemetry
 * - Accessibility & Notification Listener shortcuts
 * - Local SQLite FTS5 + EmbeddingGemma hybrid indexer (Apps, Contacts, Calendar, SAF PDFs/Docs, Memory)
 * - Active Skills Catalog (Smart Home marked DISABLED)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NovaSettingsSheet(
    uiState: NovaUiState,
    onStartModelDownload: (slot: ModelSlot, url: String, fileName: String, token: String) -> Unit,
    onCancelModelDownload: () -> Unit,
    onImportLocalModel: (slot: ModelSlot, Uri, String?) -> Unit,
    onSelectInstalledModel: (slot: ModelSlot, fileName: String) -> Unit = { _, _ -> },
    onDeleteSpecificModel: (slot: ModelSlot, fileName: String) -> Unit = { _, _ -> },
    onDeleteModel: (slot: ModelSlot) -> Unit,
    onReloadModel: (slot: ModelSlot) -> Unit,
    onToggleWakeWord: (Boolean) -> Unit,
    onRecordWakeCalibrationSample: () -> Unit = {},
    onResetWakeVoiceCalibration: () -> Unit = {},
    onTogglePauseWakeDuringMedia: (Boolean) -> Unit = {},
    onSelectAsrEngine: (AsrEngineOption) -> Unit = {},
    onSelectTtsEngine: (TtsEngineOption) -> Unit = {},
    onSelectSystemTtsEngine: (String) -> Unit = {},
    onSelectSystemTtsVoice: (String) -> Unit = {},
    onSetTtsSpeechRate: (Float) -> Unit = {},
    onTestSpeakVoice: () -> Unit = {},
    onRefreshVoices: () -> Unit = {},
    onToggleProactiveNotifPrompts: (Boolean) -> Unit = {},
    onTogglePreferNpu: (Boolean) -> Unit,
    onToggleThinking: (Boolean) -> Unit = {},
    onUpdateUserName: (String) -> Unit,
    onSaveDeviceAccount: (String) -> Unit,
    onDetectDeviceAccount: () -> Unit,
    onSyncApps: () -> Unit,
    onSyncContacts: () -> Unit,
    onSyncCalendar: () -> Unit,
    onIndexDocument: (Uri, String?) -> Unit,
    onFtsQueryChange: (String) -> Unit,
    onAddMemoryNote: (String, String) -> Unit,
    onSelectPersonality: (AssistantPersonality) -> Unit = {},
    onDeleteAgenticMemory: (String) -> Unit = {},
    onDeleteLearnedProcess: (String) -> Unit = {},
    onDeleteLearnedProcessStep: (String, Int) -> Unit = { _, _ -> },
    onRunLearnedProcess: (LearnedProcessRecord) -> Unit = {},
    onClearAllAgenticLearning: () -> Unit = {},
    onOpenHiddenReadme: () -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var devTapCount by remember { mutableStateOf(0) }
    var activeModelTab by remember { mutableStateOf(ModelSlot.LLM) }
    var pendingImportSlot by remember { mutableStateOf(ModelSlot.LLM) }

    var editableName by remember(uiState.userName) { mutableStateOf(uiState.userName) }
    var editableAccount by remember(uiState.recordedAccount) { mutableStateOf(uiState.recordedAccount.orEmpty()) }
    var noteTitle by remember { mutableStateOf("") }
    var noteBody by remember { mutableStateOf("") }
    var showAddMemory by remember { mutableStateOf(false) }

    fun queryDisplayName(uri: Uri): String? {
        var name: String? = null
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
            }
        }
        if (name.isNullOrBlank()) {
            name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
        }
        return name
    }

    val modelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportLocalModel(pendingImportSlot, uri, queryDisplayName(uri))
        }
    }

    fun launchImportForSlot(slot: ModelSlot) {
        activeModelTab = slot
        pendingImportSlot = slot
        modelPickerLauncher.launch(arrayOf("*/*"))
    }

    val docPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onIndexDocument(uri, queryDisplayName(uri))
        }
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onToggleWakeWord(true)
    }

    val calibrateMicPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onRecordWakeCalibrationSample()
    }

    val contactsPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onSyncContacts()
    }

    val calendarPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.READ_CALENDAR] == true) onSyncCalendar()
    }

    val btPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    val accountsPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onDetectDeviceAccount()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xC8090A12))
            .statusBarsPadding(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
                .padding(top = 20.dp)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(
                    Brush.verticalGradient(listOf(Color(0xFF1F1D3A), NovaSurfaceDeep, NovaBgTop))
                )
                .border(1.dp, NovaGlassBorder, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .navigationBarsPadding()
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            val next = devTapCount + 1
                            if (next >= 5) {
                                devTapCount = 0
                                onOpenHiddenReadme()
                            } else {
                                devTapCount = next
                            }
                        }
                        .testTag("settings_header_secret_trigger")
                ) {
                    Text(
                        text = if (devTapCount in 2..4) {
                            "NOVA SETTINGS • README IN ${5 - devTapCount}"
                        } else {
                            "NOVA SETTINGS"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = NovaCyan
                    )
                    Text(
                        text = "Local AI Models, Voice & OS Access",
                        style = MaterialTheme.typography.titleLarge,
                        color = NovaTextPrimary
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(NovaGlassFill)
                        .testTag("settings_close_button")
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close settings", tint = Color.White)
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. Local Models Manager (Gemma 4, EmbeddingGemma, ASR & TTS Models)
                item {
                    LocalModelsManagerCard(
                        uiState = uiState,
                        activeModelTab = activeModelTab,
                        onSelectModelTab = { activeModelTab = it },
                        onStartModelDownload = onStartModelDownload,
                        onCancelModelDownload = onCancelModelDownload,
                        onLaunchModelImport = { slot -> launchImportForSlot(slot) },
                        onSelectInstalledModel = onSelectInstalledModel,
                        onDeleteSpecificModel = onDeleteSpecificModel,
                        onDeleteAllInSlot = onDeleteModel,
                        onReloadModel = onReloadModel
                    )
                }

                // 1b. Voice, Music Protection, ASR & TTS Engines
                item {
                    VoiceAudioEnginesCard(
                        uiState = uiState,
                        onTogglePauseWakeDuringMedia = onTogglePauseWakeDuringMedia,
                        onSelectAsrEngine = onSelectAsrEngine,
                        onSelectTtsEngine = onSelectTtsEngine,
                        onSelectSystemTtsEngine = onSelectSystemTtsEngine,
                        onSelectSystemTtsVoice = onSelectSystemTtsVoice,
                        onSetTtsSpeechRate = onSetTtsSpeechRate,
                        onTestSpeakVoice = onTestSpeakVoice,
                        onRefreshVoices = onRefreshVoices,
                        onQuickImportSlot = { slot -> launchImportForSlot(slot) }
                    )
                }

                // 2. Two-Process Runtime Telemetry & Always-On Wake Word
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "TWO-PROCESS RUNTIME & WAKE WORD",
                                style = MaterialTheme.typography.labelSmall,
                                color = NovaLavender
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Process A (Reflexes)", style = MaterialTheme.typography.titleMedium, color = NovaTextPrimary)
                                    Text(
                                        "PID ${uiState.processHealth.processAPid} • ${uiState.processHealth.processAHeapMb} MB heap",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = NovaEmerald
                                    )
                                    Text(
                                        if (uiState.processHealth.embeddingReady) "EmbeddingGemma READY" else "Regex + FTS5 + Accessibility",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = NovaTextMuted
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Process B (:llm)", style = MaterialTheme.typography.titleMedium, color = NovaTextPrimary)
                                    Text(
                                        "PID ${uiState.processHealth.processBPid} • ${uiState.processHealth.processBStatus} (${uiState.processHealth.activeLlmBackend})",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (uiState.processHealth.isLlmReady) NovaEmerald else NovaAmber
                                    )
                                    Text(
                                        "Respawns: ${uiState.processHealth.respawnCount}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = NovaTextMuted
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Prefer NPU (Hexagon QNN) Backend", style = MaterialTheme.typography.titleMedium, color = NovaTextPrimary)
                                    Text(
                                        "Tries Backend.NPU first when supported, then falls back to GPU (OpenCL) -> CPU",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = NovaTextMuted
                                    )
                                }
                                Switch(
                                    checked = uiState.processHealth.preferNpuBackend,
                                    onCheckedChange = onTogglePreferNpu,
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = NovaViolet),
                                    modifier = Modifier.testTag("prefer_npu_switch")
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Deep Thinking Mode (Gemma 4)", style = MaterialTheme.typography.titleMedium, color = NovaTextPrimary)
                                    Text(
                                        if (uiState.processHealth.thinkingEnabled) {
                                            "Enabled — uses reasoning tokens for complex planning and UI automation"
                                        } else {
                                            "Turned off — faster direct replies with 0 thinking tokens"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = NovaTextMuted
                                    )
                                }
                                Switch(
                                    checked = uiState.processHealth.thinkingEnabled,
                                    onCheckedChange = onToggleThinking,
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = NovaViolet),
                                    modifier = Modifier.testTag("thinking_mode_switch")
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Always-On \"Hey Nova\" Wake Word", style = MaterialTheme.typography.titleMedium, color = NovaTextPrimary)
                                    val wakeSubtitle = when (uiState.processHealth.wakeWordStatus) {
                                        WakeWordStatus.LISTENING -> "Active — listening hands-free for 'Hey Nova'"
                                        WakeWordStatus.PAUSED_FOR_OVERLAY -> "Paused while Voice Overlay microphone is open"
                                        WakeWordStatus.PAUSED_FOR_MEDIA -> "Paused automatically while music/media is playing (resumes when playback stops)"
                                        WakeWordStatus.MIC_PERMISSION_NEEDED -> "Microphone permission needed to listen for 'Hey Nova'"
                                        WakeWordStatus.UNAVAILABLE -> "System speech recognizer unavailable on this device — use Voice Overlay or keyboard"
                                        WakeWordStatus.OFF -> "Continuous foreground listener with automatic TTS barge-in ducking"
                                    }
                                    Text(
                                        text = wakeSubtitle,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (uiState.processHealth.wakeWordStatus == WakeWordStatus.LISTENING) NovaEmerald else NovaTextMuted
                                    )
                                }
                                Switch(
                                    checked = uiState.processHealth.wakeWordEnabled,
                                    onCheckedChange = { want ->
                                        if (want && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                                            != PackageManager.PERMISSION_GRANTED
                                        ) {
                                            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        } else {
                                            onToggleWakeWord(want)
                                        }
                                    },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = NovaViolet),
                                    modifier = Modifier.testTag("wake_word_switch")
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(NovaGlassBorder)
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            val calSamples = uiState.processHealth.wakeCalibrationSamples
                            val calAliases = uiState.processHealth.wakeCalibrationAliases
                            val isCalibrating = uiState.processHealth.isCalibratingWakeVoice

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Personalize \"Hey Nova\" Voice",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = NovaTextPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                                NovaStatusPill(
                                    text = when {
                                        isCalibrating -> "LISTENING..."
                                        calSamples >= 3 -> "3/3 CALIBRATED"
                                        calSamples > 0 -> "$calSamples/3 SAMPLES"
                                        else -> "0/3 OPTIONAL"
                                    },
                                    dotColor = when {
                                        isCalibrating -> NovaAmber
                                        calSamples >= 3 -> NovaEmerald
                                        calSamples > 0 -> NovaCyan
                                        else -> NovaTextMuted
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Record 3 quick samples of saying \"Hey Nova\" so Nova learns how your phone hears your voice & accent — zero extra battery or RAM.",
                                style = MaterialTheme.typography.bodySmall,
                                color = NovaTextMuted
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = {
                                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                                            != PackageManager.PERMISSION_GRANTED
                                        ) {
                                            calibrateMicPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        } else {
                                            onRecordWakeCalibrationSample()
                                        }
                                    },
                                    enabled = !isCalibrating,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isCalibrating) NovaAmber else NovaViolet,
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .testTag("wake_calibrate_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Mic,
                                        contentDescription = "Record Hey Nova voice sample",
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = when {
                                            isCalibrating -> "Say \"Hey Nova\" now..."
                                            calSamples >= 3 -> "Add Extra Sample"
                                            else -> "Record Sample (${calSamples}/3)"
                                        },
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                                if (calSamples > 0 || calAliases.isNotEmpty()) {
                                    OutlinedButton(
                                        onClick = onResetWakeVoiceCalibration,
                                        enabled = !isCalibrating,
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .testTag("wake_calibrate_reset_button")
                                    ) {
                                        Text(
                                            text = "Reset",
                                            color = NovaTextSecondary,
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                    }
                                }
                            }
                            if (calAliases.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Learned voice triggers:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NovaTextMuted
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    calAliases.take(6).forEach { alias ->
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(NovaGlassFill)
                                                .border(1.dp, NovaGlassBorder, RoundedCornerShape(10.dp))
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text(
                                                text = "\"$alias\"",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = NovaCyan
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. OS Services (Accessibility + Notification Listener)
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "OS ACCESSIBILITY & NOTIFICATIONS",
                                style = MaterialTheme.typography.labelSmall,
                                color = NovaLavender
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Accessibility UI Navigator", style = MaterialTheme.typography.titleMedium, color = NovaTextPrimary)
                                    NovaStatusPill(
                                        text = if (uiState.processHealth.accessibilityConnected) "CONNECTED" else "DISABLED IN OS SETTINGS",
                                        dotColor = if (uiState.processHealth.accessibilityConnected) NovaEmerald else NovaAmber,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            )
                                        }
                                    },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(NovaViolet)
                                ) {
                                    Icon(Icons.Filled.Settings, contentDescription = "Accessibility Settings", tint = Color.White)
                                }
                            }
                            if (!uiState.processHealth.accessibilityConnected) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Enable Nova in Android Accessibility Settings. If greyed out on Android 13+, open App Info -> ⋮ -> Allow restricted settings first.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NovaTextMuted
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                ActionChip("Open App Info (Restricted Settings)") {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                data = Uri.parse("package:${context.packageName}")
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Notification Listener", style = MaterialTheme.typography.titleMedium, color = NovaTextPrimary)
                                    NovaStatusPill(
                                        text = if (uiState.processHealth.notificationListenerConnected) "CONNECTED" else "DISABLED IN OS SETTINGS",
                                        dotColor = if (uiState.processHealth.notificationListenerConnected) NovaEmerald else NovaAmber,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            )
                                        }
                                    },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(NovaGlassFill)
                                ) {
                                    Icon(Icons.Filled.Settings, contentDescription = "Notification Access Settings", tint = Color.White)
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Proactive Important Alerts",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = NovaTextPrimary
                                    )
                                    Text(
                                        text = "Prompt aloud & on-screen only for important notifications (messages, missed calls, reminders).",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = NovaTextMuted
                                    )
                                }
                                Switch(
                                    checked = uiState.processHealth.proactiveNotificationPrompts,
                                    onCheckedChange = onToggleProactiveNotifPrompts,
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = NovaViolet),
                                    modifier = Modifier.testTag("proactive_notif_prompts_switch")
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    val btGranted = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.BLUETOOTH_CONNECT
                                    ) == PackageManager.PERMISSION_GRANTED
                                    ActionChip(if (btGranted) "Bluetooth Access ✓" else "Grant Bluetooth Access") {
                                        if (!btGranted) btPermLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                                    }
                                }
                                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                                val dndGranted = nm?.isNotificationPolicyAccessGranted == true
                                ActionChip(if (dndGranted) "DND Policy ✓" else "Grant DND Access") {
                                    if (!dndGranted) {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            )
                                        }
                                    }
                                }
                                val writeSettingsGranted = Settings.System.canWrite(context)
                                ActionChip(if (writeSettingsGranted) "Brightness Write ✓" else "Grant Brightness Access") {
                                    if (!writeSettingsGranted) {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                                                    data = Uri.parse("package:${context.packageName}")
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 4. Local SQLite FTS5 + Vector Indexer
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "LOCAL SQLITE ${uiState.processHealth.ftsMode} INDEX (${uiState.processHealth.indexedDocCount})",
                                style = MaterialTheme.typography.labelSmall,
                                color = NovaCyan
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                ActionChip("Sync Apps", onClick = onSyncApps)
                                ActionChip("Sync Contacts") {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
                                        == PackageManager.PERMISSION_GRANTED
                                    ) onSyncContacts()
                                    else contactsPermLauncher.launch(Manifest.permission.READ_CONTACTS)
                                }
                                ActionChip("Sync Calendar") {
                                    val hasReadCal = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
                                    val hasWriteCal = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
                                    if (hasReadCal && hasWriteCal) onSyncCalendar()
                                    else calendarPermLauncher.launch(
                                        arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
                                    )
                                }
                                ActionChip("+ PDF / Doc") {
                                    docPickerLauncher.launch(arrayOf("application/pdf", "text/*"))
                                }
                                ActionChip(if (showAddMemory) "Cancel Note" else "+ Memory") {
                                    showAddMemory = !showAddMemory
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            if (showAddMemory) {
                                OutlinedTextField(
                                    value = noteTitle,
                                    onValueChange = { noteTitle = it },
                                    label = { Text("Memory Key") },
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = NovaViolet,
                                        unfocusedBorderColor = NovaGlassBorder,
                                        focusedTextColor = NovaTextPrimary,
                                        unfocusedTextColor = NovaTextPrimary
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                OutlinedTextField(
                                    value = noteBody,
                                    onValueChange = { noteBody = it },
                                    label = { Text("Fact to remember locally") },
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = NovaViolet,
                                        unfocusedBorderColor = NovaGlassBorder,
                                        focusedTextColor = NovaTextPrimary,
                                        unfocusedTextColor = NovaTextPrimary
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        onAddMemoryNote(noteTitle, noteBody)
                                        noteTitle = ""
                                        noteBody = ""
                                        showAddMemory = false
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = NovaViolet),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Save to Local ${uiState.processHealth.ftsMode}")
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            OutlinedTextField(
                                value = uiState.ftsSearchQuery,
                                onValueChange = onFtsQueryChange,
                                placeholder = { Text("Search local index...", color = NovaTextMuted) },
                                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = NovaLavender) },
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
                                    .testTag("fts5_search_input")
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            if (uiState.ftsSearchResults.isEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0x0DFFFFFF))
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Filled.Sync, contentDescription = null, tint = NovaTextMuted, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "No indexed records yet. Sync Apps, Contacts, Calendar, or add a PDF/Memory.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = NovaTextMuted
                                    )
                                }
                            } else {
                                uiState.ftsSearchResults.take(5).forEach { doc ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0x10FFFFFF))
                                            .padding(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(doc.title, style = MaterialTheme.typography.titleMedium, color = NovaTextPrimary, modifier = Modifier.weight(1f))
                                            Text(doc.category.uppercase(), style = MaterialTheme.typography.labelSmall, color = NovaCyan)
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(doc.body, style = MaterialTheme.typography.bodyMedium, color = NovaTextSecondary)
                                    }
                                }
                            }
                        }
                    }
                }

                // 5. Profile & Active Device Account
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "PROFILE & ACTIVE DEVICE ACCOUNT",
                                style = MaterialTheme.typography.labelSmall,
                                color = NovaLavender
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Nova automatically records your device account so it can confirm single-account prompts without asking.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = NovaTextMuted
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = editableName,
                                    onValueChange = { editableName = it },
                                    label = { Text("Profile Greeting Name") },
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = NovaViolet,
                                        unfocusedBorderColor = NovaGlassBorder,
                                        focusedTextColor = NovaTextPrimary,
                                        unfocusedTextColor = NovaTextPrimary
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                                Button(
                                    onClick = { onUpdateUserName(editableName) },
                                    colors = ButtonDefaults.buttonColors(containerColor = NovaViolet)
                                ) {
                                    Text("Save")
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = editableAccount,
                                    onValueChange = { editableAccount = it },
                                    label = { Text("Recorded Device Account") },
                                    placeholder = { Text("Auto-captured or enter email", color = NovaTextMuted) },
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = NovaViolet,
                                        unfocusedBorderColor = NovaGlassBorder,
                                        focusedTextColor = NovaTextPrimary,
                                        unfocusedTextColor = NovaTextPrimary
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("device_account_input")
                                )
                                Button(
                                    onClick = { onSaveDeviceAccount(editableAccount) },
                                    colors = ButtonDefaults.buttonColors(containerColor = NovaViolet),
                                    modifier = Modifier.testTag("save_account_button")
                                ) {
                                    Text("Save")
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            ActionChip(
                                if (uiState.recordedAccount != null) "Re-Detect System Account" else "Detect System Account"
                            ) {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.GET_ACCOUNTS)
                                    == PackageManager.PERMISSION_GRANTED
                                ) {
                                    onDetectDeviceAccount()
                                } else {
                                    accountsPermLauncher.launch(Manifest.permission.GET_ACCOUNTS)
                                }
                            }
                        }
                    }
                }

                // 5.5 Agentic Personality, Learned Preferences & Processes
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "PERSONALITY & AGENTIC LEARNING",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NovaLavender
                                    )
                                    Text(
                                        text = "${uiState.processHealth.agenticMemoryCount} memories • ${uiState.processHealth.learnedProcessCount} learned processes",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = NovaTextMuted
                                    )
                                }
                                if (uiState.agenticMemories.isNotEmpty() || uiState.learnedProcesses.isNotEmpty()) {
                                    ActionChip("Reset Learning", onClick = onClearAllAgenticLearning)
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                AssistantPersonality.entries.forEach { persona ->
                                    val selected = uiState.assistantPersonality == persona
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(if (selected) NovaViolet else NovaGlassFill)
                                            .border(
                                                1.dp,
                                                if (selected) NovaLavender else NovaGlassBorder,
                                                RoundedCornerShape(999.dp)
                                            )
                                            .clickable { onSelectPersonality(persona) }
                                            .padding(horizontal = 12.dp, vertical = 8.dp)
                                            .testTag("personality_chip_${persona.name.lowercase()}")
                                    ) {
                                        Text(
                                            text = persona.displayName,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = if (selected) Color.White else NovaTextSecondary
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = uiState.assistantPersonality.tagline,
                                style = MaterialTheme.typography.bodySmall,
                                color = NovaCyan
                            )

                            if (uiState.learnedProcesses.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "LEARNED WORKFLOWS & HABITS",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NovaEmerald
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                uiState.learnedProcesses.take(5).forEach { proc ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0x12FFFFFF))
                                            .padding(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = proc.name,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    color = NovaTextPrimary
                                                )
                                                Text(
                                                    text = "${proc.timeBucket.label} • Seen ${proc.executionCount}x • ${(proc.confidence * 100).toInt()}%",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = NovaEmerald
                                                )
                                            }
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                ActionChip("Run") { onRunLearnedProcess(proc) }
                                                IconButton(
                                                    onClick = { onDeleteLearnedProcess(proc.processId) },
                                                    modifier = Modifier
                                                        .size(48.dp)
                                                        .testTag("delete_learned_process_${proc.processId}")
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Filled.DeleteOutline,
                                                        contentDescription = "Delete learned process ${proc.name}",
                                                        tint = NovaCoralRed
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        proc.steps.forEachIndexed { stepIdx, stepText ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 2.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = "${stepIdx + 1}. $stepText",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = NovaTextSecondary,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                IconButton(
                                                    onClick = { onDeleteLearnedProcessStep(proc.processId, stepIdx) },
                                                    modifier = Modifier
                                                        .size(40.dp)
                                                        .testTag("delete_learned_step_${proc.processId}_$stepIdx")
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Close,
                                                        contentDescription = "Delete step ${stepIdx + 1}",
                                                        tint = NovaCoralRed,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            if (uiState.agenticMemories.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "LEARNED PREFERENCES, CORRECTIONS & EPISODES",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NovaCyan
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                uiState.agenticMemories.take(6).forEach { mem ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 3.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0x0EFFFFFF))
                                            .padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            val tagColor = when (mem.memoryType) {
                                                AgenticMemoryType.CORRECTION -> NovaAmber
                                                AgenticMemoryType.PREFERENCE -> NovaEmerald
                                                AgenticMemoryType.EPISODE -> NovaLavender
                                                AgenticMemoryType.FACT -> NovaCyan
                                            }
                                            Text(
                                                text = "${mem.memoryType.label.uppercase()} • ${mem.topic}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = tagColor
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = mem.content,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = NovaTextPrimary
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        IconButton(
                                            onClick = { onDeleteAgenticMemory(mem.memoryId) },
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.DeleteOutline,
                                                contentDescription = "Forget learned memory",
                                                tint = NovaCoralRed
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 6. Skills Catalog (showing Smart Home DISABLED)
                item {
                    Text(
                        text = "SKILLS CATALOG (${SkillCatalog.enabledSkills.size} ACTIVE • 1 DISABLED)",
                        style = MaterialTheme.typography.labelSmall,
                        color = NovaTextMuted,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                items(SkillCatalog.allSkills, key = { it.id }) { skill ->
                    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = skill.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (skill.enabled) NovaTextPrimary else NovaTextMuted
                                    )
                                    Text(
                                        text = "${skill.id} • ${skill.category.title}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (skill.enabled) NovaLavender else NovaTextMuted
                                    )
                                }
                                val (tierLabel, tierColor) = if (!skill.enabled) {
                                    "DISABLED" to NovaTextMuted
                                } else when (skill.safetyTier.name) {
                                    "RED" -> "RED" to NovaCoralRed
                                    "YELLOW" -> "YELLOW" to NovaAmber
                                    else -> "GREEN" to NovaEmerald
                                }
                                Text(tierLabel, style = MaterialTheme.typography.labelSmall, color = tierColor)
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(skill.description, style = MaterialTheme.typography.bodyMedium, color = NovaTextSecondary)
                            if (skill.guidance.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = skill.guidance,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NovaTextMuted
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Tools: " + skill.tools.joinToString { "${it.name}(${it.parameters.size} args)" },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (skill.enabled) NovaCyan else NovaTextMuted
                            )
                            if (skill.companionTools.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Companions: ${skill.companionTools.joinToString(", ")}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NovaLavender
                                )
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun ActionChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(NovaGlassFill)
            .border(1.dp, NovaGlassBorder, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = NovaCyan
        )
    }
}
