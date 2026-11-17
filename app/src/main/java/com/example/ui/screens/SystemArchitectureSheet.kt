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
import com.example.data.ModelDownloadState
import com.example.data.ModelSlot
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
    onDeleteModel: (slot: ModelSlot) -> Unit,
    onReloadModel: (slot: ModelSlot) -> Unit,
    onToggleWakeWord: (Boolean) -> Unit,
    onTogglePreferNpu: (Boolean) -> Unit,
    onUpdateUserName: (String) -> Unit,
    onSaveDeviceAccount: (String) -> Unit,
    onDetectDeviceAccount: () -> Unit,
    onSyncApps: () -> Unit,
    onSyncContacts: () -> Unit,
    onSyncCalendar: () -> Unit,
    onIndexDocument: (Uri, String?) -> Unit,
    onFtsQueryChange: (String) -> Unit,
    onAddMemoryNote: (String, String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var activeModelTab by remember { mutableStateOf(ModelSlot.LLM) }
    var llmUrl by remember { mutableStateOf(uiState.llmPreset?.downloadUrl.orEmpty()) }
    var embUrl by remember { mutableStateOf(uiState.embeddingPreset?.downloadUrl.orEmpty()) }
    var hfToken by remember { mutableStateOf("") }

    var editableName by remember(uiState.userName) { mutableStateOf(uiState.userName) }
    var editableAccount by remember(uiState.recordedAccount) { mutableStateOf(uiState.recordedAccount.orEmpty()) }
    var noteTitle by remember { mutableStateOf("") }
    var noteBody by remember { mutableStateOf("") }
    var showAddMemory by remember { mutableStateOf(false) }

    fun queryDisplayName(uri: Uri): String? {
        var name: String? = null
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
            }
        }
        return name
    }

    val modelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportLocalModel(activeModelTab, uri, queryDisplayName(uri))
        }
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

    val contactsPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onSyncContacts()
    }

    val calendarPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onSyncCalendar()
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
                Column {
                    Text(
                        text = "NOVA SETTINGS",
                        style = MaterialTheme.typography.labelSmall,
                        color = NovaCyan
                    )
                    Text(
                        text = "Gemma 4, EmbeddingGemma & OS Access",
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
                // 1. Local Models Manager (Gemma 4 E2B + EmbeddingGemma 2)
                item {
                    val isLlmTab = activeModelTab == ModelSlot.LLM
                    val isPresent = if (isLlmTab) uiState.processHealth.modelPresent else uiState.processHealth.embeddingPresent
                    val activeName = if (isLlmTab) uiState.processHealth.activeModelName else uiState.processHealth.activeEmbeddingName
                    val activeMb = if (isLlmTab) uiState.processHealth.activeModelSizeMb else uiState.processHealth.activeEmbeddingSizeMb
                    val currentPreset = if (isLlmTab) uiState.llmPreset else uiState.embeddingPreset
                    val currentUrl = if (isLlmTab) llmUrl else embUrl

                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 20.dp,
                        isHighlighted = !uiState.processHealth.modelPresent
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ModelSlot.entries.forEach { slot ->
                                    val selected = activeModelTab == slot
                                    val installed = if (slot == ModelSlot.LLM) {
                                        uiState.processHealth.modelPresent
                                    } else {
                                        uiState.processHealth.embeddingPresent
                                    }
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (selected) NovaViolet.copy(alpha = 0.28f) else Color(0x10FFFFFF))
                                            .border(
                                                1.dp,
                                                if (selected) NovaViolet else NovaGlassBorder,
                                                RoundedCornerShape(12.dp)
                                            )
                                            .clickable { activeModelTab = slot }
                                            .padding(10.dp)
                                            .testTag("model_tab_${slot.name.lowercase()}")
                                    ) {
                                        Column {
                                            Text(
                                                text = if (slot == ModelSlot.LLM) "Gemma 4 E2B IT" else "EmbeddingGemma 2",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = NovaTextPrimary
                                            )
                                            Text(
                                                text = if (installed) "Installed" else if (slot == ModelSlot.LLM) "Required (~2.58 GB)" else "Tier 1.5 (~165 MB)",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (installed) NovaEmerald else NovaAmber
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isLlmTab) "CORE LLM (:llm)" else "EMBEDDING ENGINE (Proc A)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NovaLavender
                                )
                                NovaStatusPill(
                                    text = if (isPresent) "INSTALLED ($activeMb MB)" else "NOT INSTALLED",
                                    dotColor = if (isPresent) NovaEmerald else NovaCoralRed
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = when {
                                    isPresent && isLlmTab ->
                                        "Active: $activeName • Backend: ${uiState.processHealth.activeLlmBackend} • :llm ${uiState.processHealth.processBStatus}"
                                    isPresent && !isLlmTab ->
                                        "Active: $activeName • Engine: ${if (uiState.processHealth.embeddingReady) "READY (CPU)" else "IDLE"}"
                                    isLlmTab ->
                                        "Tier 2/3 AI inference is disabled until Gemma 4 E2B (.litertlm) is downloaded or imported."
                                    else ->
                                        "Optional for neural Tier 1.5 lookup & hybrid vector search. Download or import EmbeddingGemma 2."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = NovaTextSecondary
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedTextField(
                                value = currentUrl,
                                onValueChange = { if (isLlmTab) llmUrl = it else embUrl = it },
                                label = { Text("${currentPreset?.displayName ?: "Model"} URL") },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = NovaViolet,
                                    unfocusedBorderColor = NovaGlassBorder,
                                    focusedTextColor = NovaTextPrimary,
                                    unfocusedTextColor = NovaTextPrimary
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("model_url_input")
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = hfToken,
                                onValueChange = { hfToken = it },
                                label = { Text("Hugging Face Token (optional)") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = NovaViolet,
                                    unfocusedBorderColor = NovaGlassBorder,
                                    focusedTextColor = NovaTextPrimary,
                                    unfocusedTextColor = NovaTextPrimary
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            when (val dl = uiState.downloadState) {
                                is ModelDownloadState.Downloading -> {
                                    val dlMb = dl.bytesDownloaded / (1024L * 1024L)
                                    val totalMb = if (dl.totalBytes > 0) "${dl.totalBytes / (1024L * 1024L)} MB" else "..."
                                    Text(
                                        text = "Downloading ${dl.fileName}: $dlMb MB / $totalMb",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = NovaCyan
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    if (dl.totalBytes > 0) {
                                        LinearProgressIndicator(
                                            progress = { dl.progress },
                                            color = NovaViolet,
                                            trackColor = Color(0x28FFFFFF),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    } else {
                                        LinearProgressIndicator(
                                            color = NovaViolet,
                                            trackColor = Color(0x28FFFFFF),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedButton(
                                        onClick = onCancelModelDownload,
                                        shape = RoundedCornerShape(999.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, NovaCoralRed),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Cancel Download", color = NovaCoralRed)
                                    }
                                }
                                else -> {
                                    if (dl is ModelDownloadState.Failed) {
                                        Text(
                                            text = dl.message,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = NovaCoralRed,
                                            modifier = Modifier.padding(bottom = 8.dp)
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                val fileName = currentPreset?.fileName ?: "model.litertlm"
                                                onStartModelDownload(activeModelTab, currentUrl, fileName, hfToken)
                                            },
                                            shape = RoundedCornerShape(999.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = NovaViolet),
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("download_llm_button")
                                        ) {
                                            Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Download")
                                        }
                                        OutlinedButton(
                                            onClick = { modelPickerLauncher.launch(arrayOf("*/*")) },
                                            shape = RoundedCornerShape(999.dp),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, NovaLavender.copy(alpha = 0.5f)),
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("import_llm_button")
                                        ) {
                                            Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp), tint = NovaLavender)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Import File", color = NovaLavender)
                                        }
                                    }
                                }
                            }

                            if (isPresent) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { onReloadModel(activeModelTab) },
                                        shape = RoundedCornerShape(999.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, NovaCyan.copy(alpha = 0.5f)),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp), tint = NovaCyan)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(if (isLlmTab) "Reload :llm" else "Reload Emb", color = NovaCyan)
                                    }
                                    OutlinedButton(
                                        onClick = { onDeleteModel(activeModelTab) },
                                        shape = RoundedCornerShape(999.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, NovaCoralRed.copy(alpha = 0.6f)),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("delete_llm_button")
                                    ) {
                                        Icon(Icons.Filled.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp), tint = NovaCoralRed)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Delete", color = NovaCoralRed)
                                    }
                                }
                            }
                        }
                    }
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
                                    Text("Always-On \"Hey Nova\" Wake Word", style = MaterialTheme.typography.titleMedium, color = NovaTextPrimary)
                                    Text(
                                        "Continuous foreground listener with automatic TTS barge-in ducking",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = NovaTextMuted
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
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
                                        == PackageManager.PERMISSION_GRANTED
                                    ) onSyncCalendar()
                                    else calendarPermLauncher.launch(Manifest.permission.READ_CALENDAR)
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
                                Text(
                                    text = skill.id,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (skill.enabled) NovaLavender else NovaTextMuted
                                )
                                val (tierLabel, tierColor) = if (!skill.enabled) {
                                    "DISABLED" to NovaTextMuted
                                } else when (skill.safetyTier.name) {
                                    "RED" -> "RED" to NovaCoralRed
                                    "YELLOW" -> "YELLOW" to NovaAmber
                                    else -> "GREEN" to NovaEmerald
                                }
                                Text(tierLabel, style = MaterialTheme.typography.labelSmall, color = tierColor)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(skill.description, style = MaterialTheme.typography.bodyMedium, color = NovaTextSecondary)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Tools: " + skill.tools.joinToString { "${it.name}(${it.parameters.size} args)" },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (skill.enabled) NovaCyan else NovaTextMuted
                            )
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
