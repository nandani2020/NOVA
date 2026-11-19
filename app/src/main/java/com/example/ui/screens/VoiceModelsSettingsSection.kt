package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.AsrEngineOption
import com.example.data.LlmModelPreset
import com.example.data.ModelDownloadState
import com.example.data.ModelSlot
import com.example.data.TtsEngineOption
import com.example.ui.NovaUiState
import com.example.ui.components.GlassCard
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalModelsManagerCard(
    uiState: NovaUiState,
    activeModelTab: ModelSlot,
    onSelectModelTab: (ModelSlot) -> Unit,
    onStartModelDownload: (slot: ModelSlot, url: String, fileName: String, token: String) -> Unit,
    onCancelModelDownload: () -> Unit,
    onLaunchModelImport: (slot: ModelSlot) -> Unit,
    onSelectInstalledModel: (slot: ModelSlot, fileName: String) -> Unit,
    onDeleteSpecificModel: (slot: ModelSlot, fileName: String) -> Unit,
    onDeleteAllInSlot: (slot: ModelSlot) -> Unit,
    onReloadModel: (slot: ModelSlot) -> Unit
) {
    var selectedLlmPreset by remember { mutableStateOf(uiState.llmPresets.firstOrNull() ?: uiState.llmPreset) }
    var llmUrl by remember { mutableStateOf((uiState.llmPresets.firstOrNull() ?: uiState.llmPreset)?.downloadUrl.orEmpty()) }
    var embUrl by remember { mutableStateOf(uiState.embeddingPreset?.downloadUrl.orEmpty()) }
    var selectedAsrPreset by remember { mutableStateOf(uiState.asrPresets.firstOrNull()) }
    var asrUrl by remember { mutableStateOf(uiState.asrPresets.firstOrNull()?.downloadUrl.orEmpty()) }
    var selectedTtsPreset by remember { mutableStateOf(uiState.ttsPresets.firstOrNull()) }
    var ttsUrl by remember { mutableStateOf(uiState.ttsPresets.firstOrNull()?.downloadUrl.orEmpty()) }
    var hfToken by remember { mutableStateOf("") }

    val health = uiState.processHealth
    val isPresent = when (activeModelTab) {
        ModelSlot.LLM -> health.modelPresent
        ModelSlot.EMBEDDING -> health.embeddingPresent
        ModelSlot.ASR -> health.asrModelPresent
        ModelSlot.TTS -> health.ttsModelPresent
    }
    val activeName = when (activeModelTab) {
        ModelSlot.LLM -> health.activeModelName
        ModelSlot.EMBEDDING -> health.activeEmbeddingName
        ModelSlot.ASR -> health.activeAsrModelName
        ModelSlot.TTS -> health.activeTtsModelName
    }
    val activeMb = when (activeModelTab) {
        ModelSlot.LLM -> health.activeModelSizeMb
        ModelSlot.EMBEDDING -> health.activeEmbeddingSizeMb
        ModelSlot.ASR -> health.activeAsrModelSizeMb
        ModelSlot.TTS -> health.activeTtsModelSizeMb
    }
    val currentPreset: LlmModelPreset? = when (activeModelTab) {
        ModelSlot.LLM -> selectedLlmPreset ?: uiState.llmPreset
        ModelSlot.EMBEDDING -> uiState.embeddingPreset
        ModelSlot.ASR -> selectedAsrPreset
        ModelSlot.TTS -> selectedTtsPreset
    }
    val currentUrl = when (activeModelTab) {
        ModelSlot.LLM -> llmUrl
        ModelSlot.EMBEDDING -> embUrl
        ModelSlot.ASR -> asrUrl
        ModelSlot.TTS -> ttsUrl
    }
    val installedInSlot = uiState.installedModelsBySlot[activeModelTab].orEmpty()

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        isHighlighted = !health.modelPresent && activeModelTab == ModelSlot.LLM
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "ON-DEVICE MODEL SLOTS (LLM • EMBEDDING • ASR • TTS)",
                style = MaterialTheme.typography.labelSmall,
                color = NovaLavender
            )
            Spacer(modifier = Modifier.height(10.dp))

            // 2x2 Grid of Model Slots
            val slotRows = listOf(
                listOf(ModelSlot.LLM, ModelSlot.EMBEDDING),
                listOf(ModelSlot.ASR, ModelSlot.TTS)
            )
            slotRows.forEachIndexed { rowIdx, rowSlots ->
                if (rowIdx > 0) Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowSlots.forEach { slot ->
                        val selected = activeModelTab == slot
                        val slotInstalled = when (slot) {
                            ModelSlot.LLM -> health.modelPresent
                            ModelSlot.EMBEDDING -> health.embeddingPresent
                            ModelSlot.ASR -> health.asrModelPresent
                            ModelSlot.TTS -> health.ttsModelPresent
                        }
                        val slotCount = uiState.installedModelsBySlot[slot].orEmpty().size
                        val slotTitle = when (slot) {
                            ModelSlot.LLM -> "Core LLM"
                            ModelSlot.EMBEDDING -> "EmbeddingGemma"
                            ModelSlot.ASR -> "ASR Models"
                            ModelSlot.TTS -> "TTS Voices"
                        }
                        val slotSub = when {
                            slotInstalled && slotCount > 1 -> "$slotCount installed"
                            slotInstalled -> "Installed"
                            slot == ModelSlot.LLM -> "Gemma / LFM / Qwen"
                            slot == ModelSlot.EMBEDDING -> "Tier 1.5 (~165 MB)"
                            slot == ModelSlot.ASR -> "Whisper / Moonshine"
                            else -> "Kokoro-82M / Piper"
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selected) NovaViolet.copy(alpha = 0.28f) else Color(0x10FFFFFF))
                                .border(
                                    1.dp,
                                    if (selected) NovaViolet else NovaGlassBorder,
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable { onSelectModelTab(slot) }
                                .padding(horizontal = 10.dp, vertical = 9.dp)
                                .testTag("model_tab_${slot.name.lowercase()}")
                        ) {
                            Column {
                                Text(
                                    text = slotTitle,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = NovaTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = slotSub,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (slotInstalled) NovaEmerald else NovaAmber,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
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
                    text = when (activeModelTab) {
                        ModelSlot.LLM -> "CORE LLM (:llm)"
                        ModelSlot.EMBEDDING -> "EMBEDDING ENGINE (Proc A)"
                        ModelSlot.ASR -> "SPEECH RECOGNITION MODELS"
                        ModelSlot.TTS -> "NEURAL VOICE MODELS"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = NovaLavender
                )
                NovaStatusPill(
                    text = if (isPresent) "ACTIVE (${activeMb} MB)" else "NONE INSTALLED",
                    dotColor = if (isPresent) NovaEmerald else NovaAmber
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = when {
                    isPresent && activeModelTab == ModelSlot.LLM ->
                        "Active: $activeName • Backend: ${health.activeLlmBackend} • :llm ${health.processBStatus}"
                    isPresent && activeModelTab == ModelSlot.EMBEDDING ->
                        "Active: $activeName • Engine: ${if (health.embeddingReady) "READY (CPU)" else "IDLE"}"
                    isPresent && activeModelTab == ModelSlot.ASR ->
                        "Active ASR bundle: $activeName • Supports .onnx, .tflite, .bin, or .zip bundles."
                    isPresent && activeModelTab == ModelSlot.TTS ->
                        "Active TTS voice bundle: $activeName • Supports Kokoro-82M / Piper .onnx or .zip bundles."
                    activeModelTab == ModelSlot.LLM ->
                        "Choose a LiteRT-LM preset below (Gemma 4, Gemma 3n, LFM2.5, or Qwen 2.5) or import any .litertlm file from storage."
                    activeModelTab == ModelSlot.EMBEDDING ->
                        "Optional for neural Tier 1.5 lookup & hybrid vector search. Download or import EmbeddingGemma 2."
                    activeModelTab == ModelSlot.ASR ->
                        "Download a preset or import a local Whisper, Moonshine, or Sherpa-ONNX Zipformer file/.zip bundle."
                    else ->
                        "Download or import Kokoro-82M or Piper VITS (.onnx / .zip bundle) for offline neural voice choices."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = NovaTextSecondary
            )

            // Preset chooser chips for LLM, ASR, and TTS slots
            val slotPresets = when (activeModelTab) {
                ModelSlot.LLM -> uiState.llmPresets
                ModelSlot.ASR -> uiState.asrPresets
                ModelSlot.TTS -> uiState.ttsPresets
                else -> emptyList()
            }
            if (slotPresets.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    slotPresets.forEach { preset ->
                        val isChosen = currentPreset?.id == preset.id
                        Box(
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (isChosen) NovaCyan.copy(alpha = 0.22f) else NovaGlassFill)
                                .border(
                                    1.dp,
                                    if (isChosen) NovaCyan else NovaGlassBorder,
                                    RoundedCornerShape(999.dp)
                                )
                                .clickable {
                                    when (activeModelTab) {
                                        ModelSlot.LLM -> {
                                            selectedLlmPreset = preset
                                            llmUrl = preset.downloadUrl
                                        }
                                        ModelSlot.ASR -> {
                                            selectedAsrPreset = preset
                                            asrUrl = preset.downloadUrl
                                        }
                                        else -> {
                                            selectedTtsPreset = preset
                                            ttsUrl = preset.downloadUrl
                                        }
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .testTag("preset_chip_${preset.id}")
                        ) {
                            Text(
                                text = "${preset.displayName} (${preset.approxSizeLabel})",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isChosen) NovaTextPrimary else NovaTextSecondary
                            )
                        }
                    }
                }
                currentPreset?.notes?.takeIf { it.isNotBlank() }?.let { note ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = NovaTextMuted
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = currentUrl,
                onValueChange = { updated ->
                    when (activeModelTab) {
                        ModelSlot.LLM -> llmUrl = updated
                        ModelSlot.EMBEDDING -> embUrl = updated
                        ModelSlot.ASR -> asrUrl = updated
                        ModelSlot.TTS -> ttsUrl = updated
                    }
                },
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
                        text = "Working on ${dl.fileName}: $dlMb MB / $totalMb",
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
                        border = BorderStroke(1.dp, NovaCoralRed),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancel", color = NovaCoralRed)
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
                                val fileName = currentPreset?.fileName ?: when (activeModelTab) {
                                    ModelSlot.LLM -> "gemma-4-E2B-it.litertlm"
                                    ModelSlot.EMBEDDING -> "embeddinggemma-2.litertlm"
                                    ModelSlot.ASR -> "asr_model.onnx"
                                    ModelSlot.TTS -> "tts_model.onnx"
                                }
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
                            onClick = { onLaunchModelImport(activeModelTab) },
                            shape = RoundedCornerShape(999.dp),
                            border = BorderStroke(1.dp, NovaLavender.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("import_llm_button")
                        ) {
                            Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp), tint = NovaLavender)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Import File/.zip", color = NovaLavender)
                        }
                    }
                }
            }

            // Installed Models & Bundles List for Active Slot
            if (installedInSlot.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "INSTALLED IN ${activeModelTab.name} SLOT (${installedInSlot.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = NovaCyan
                )
                Spacer(modifier = Modifier.height(6.dp))
                installedInSlot.forEach { model ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (model.isActive) NovaViolet.copy(alpha = 0.2f) else Color(0x12FFFFFF))
                            .border(
                                1.dp,
                                if (model.isActive) NovaViolet else NovaGlassBorder,
                                RoundedCornerShape(12.dp)
                            )
                            .clickable { onSelectInstalledModel(activeModelTab, model.fileName) }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("installed_model_${model.fileName}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = if (model.isActive) "Active model" else "Select model",
                                tint = if (model.isActive) NovaEmerald else NovaTextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = model.fileName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NovaTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val kindLabel = if (model.isBundleDir) "Bundle" else "File"
                                val sizeLabel = if (model.sizeMb >= 1L) "${model.sizeMb} MB" else "${model.sizeBytes / 1024L} KB"
                                Text(
                                    text = "$kindLabel • $sizeLabel${if (model.isActive) " • ACTIVE" else " • Tap to activate"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (model.isActive) NovaEmerald else NovaTextMuted
                                )
                            }
                        }
                        IconButton(
                            onClick = { onDeleteSpecificModel(activeModelTab, model.fileName) },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("delete_model_${model.fileName}")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DeleteOutline,
                                contentDescription = "Delete ${model.fileName}",
                                tint = NovaCoralRed
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onReloadModel(activeModelTab) },
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, NovaCyan.copy(alpha = 0.5f)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp), tint = NovaCyan)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Reload", color = NovaCyan)
                    }
                    OutlinedButton(
                        onClick = { onDeleteAllInSlot(activeModelTab) },
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, NovaCoralRed.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("delete_llm_button")
                    ) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp), tint = NovaCoralRed)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Clear Slot", color = NovaCoralRed)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VoiceAudioEnginesCard(
    uiState: NovaUiState,
    onTogglePauseWakeDuringMedia: (Boolean) -> Unit,
    onSelectAsrEngine: (AsrEngineOption) -> Unit,
    onSelectTtsEngine: (TtsEngineOption) -> Unit,
    onSelectSystemTtsEngine: (String) -> Unit,
    onSelectSystemTtsVoice: (String) -> Unit,
    onSetTtsSpeechRate: (Float) -> Unit,
    onTestSpeakVoice: () -> Unit,
    onRefreshVoices: () -> Unit,
    onQuickImportSlot: (ModelSlot) -> Unit
) {
    val health = uiState.processHealth
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "VOICE, MUSIC PROTECTION, ASR & TTS",
                    style = MaterialTheme.typography.labelSmall,
                    color = NovaLavender
                )
                if (health.externalMediaPlaying) {
                    NovaStatusPill(
                        text = "MUSIC ACTIVE",
                        dotColor = NovaCyan
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Music Protection Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = NovaCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = "Pause Wake Mic While Music Plays",
                            style = MaterialTheme.typography.titleMedium,
                            color = NovaTextPrimary
                        )
                        Text(
                            text = "Keep OFF (default) to ask Nova questions via 'Hey Nova' or mic tap while music ducks gently; turn ON to mute hands-free wake during media playback.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NovaTextMuted
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = health.pauseWakeDuringMedia,
                    onCheckedChange = onTogglePauseWakeDuringMedia,
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = NovaViolet),
                    modifier = Modifier.testTag("pause_wake_during_media_switch")
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ASR Engine Choice
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.GraphicEq,
                        contentDescription = null,
                        tint = NovaLavender,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "ASR ENGINE (SPEECH-TO-TEXT)",
                        style = MaterialTheme.typography.labelSmall,
                        color = NovaCyan
                    )
                }
                Box(
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(999.dp))
                        .background(NovaGlassFill)
                        .border(1.dp, NovaLavender.copy(alpha = 0.5f), RoundedCornerShape(999.dp))
                        .clickable { onQuickImportSlot(ModelSlot.ASR) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("quick_import_asr_btn")
                ) {
                    Text("Import ASR", style = MaterialTheme.typography.labelSmall, color = NovaLavender)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                AsrEngineOption.entries.forEach { option ->
                    val selected = health.asrEngine == option
                    Box(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) NovaViolet.copy(alpha = 0.28f) else Color(0x10FFFFFF))
                            .border(
                                1.dp,
                                if (selected) NovaViolet else NovaGlassBorder,
                                RoundedCornerShape(12.dp)
                            )
                            .clickable { onSelectAsrEngine(option) }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("asr_engine_${option.id}")
                    ) {
                        Text(
                            text = option.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) NovaTextPrimary else NovaTextSecondary
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = buildString {
                    append(health.asrEngine.subtitle)
                    if (health.asrEngine == AsrEngineOption.LOCAL_ASR_MODEL) {
                        append(
                            if (health.asrModelPresent) " • Active model: ${health.activeAsrModelName}"
                            else " • No local ASR file imported yet."
                        )
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = NovaTextMuted
            )

            Spacer(modifier = Modifier.height(14.dp))

            // TTS Engine Choice (System TTS, Kokoro-82M, Piper VITS, Custom Bundle)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.RecordVoiceOver,
                        contentDescription = null,
                        tint = NovaLavender,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "TTS ENGINE & NEURAL VOICE",
                        style = MaterialTheme.typography.labelSmall,
                        color = NovaCyan
                    )
                }
                Box(
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(999.dp))
                        .background(NovaGlassFill)
                        .border(1.dp, NovaLavender.copy(alpha = 0.5f), RoundedCornerShape(999.dp))
                        .clickable { onQuickImportSlot(ModelSlot.TTS) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("quick_import_tts_btn")
                ) {
                    Text("Import TTS", style = MaterialTheme.typography.labelSmall, color = NovaLavender)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                TtsEngineOption.entries.forEach { option ->
                    val selected = health.ttsEngine == option
                    Box(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) NovaViolet.copy(alpha = 0.28f) else Color(0x10FFFFFF))
                            .border(
                                1.dp,
                                if (selected) NovaViolet else NovaGlassBorder,
                                RoundedCornerShape(12.dp)
                            )
                            .clickable { onSelectTtsEngine(option) }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("tts_engine_${option.id}")
                    ) {
                        Text(
                            text = option.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) NovaTextPrimary else NovaTextSecondary
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = buildString {
                    append(health.ttsEngine.subtitle)
                    if (health.ttsEngine != TtsEngineOption.ANDROID_SYSTEM) {
                        append(
                            if (health.ttsModelPresent) " • Staged bundle: ${health.activeTtsModelName}"
                            else " • Download or import a Kokoro/Piper model in the TTS slot above, or select Sherpa-ONNX TTS Engine below."
                        )
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = NovaTextMuted
            )

            // Installed Android TTS Engines Selector (allows picking Google Neural, Sherpa-ONNX Kokoro/Piper TTS, Samsung, etc.)
            if (uiState.availableTtsEngines.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "INSTALLED ANDROID TTS ENGINES",
                    style = MaterialTheme.typography.labelSmall,
                    color = NovaTextSecondary
                )
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val isDefault = health.selectedSystemTtsEnginePkg.isBlank()
                    Box(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (isDefault) NovaCyan.copy(alpha = 0.2f) else NovaGlassFill)
                            .border(1.dp, if (isDefault) NovaCyan else NovaGlassBorder, RoundedCornerShape(999.dp))
                            .clickable { onSelectSystemTtsEngine("") }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("system_tts_engine_default")
                    ) {
                        Text("System Default", style = MaterialTheme.typography.labelSmall, color = NovaTextPrimary)
                    }
                    uiState.availableTtsEngines.forEach { engine ->
                        val selected = health.selectedSystemTtsEnginePkg == engine.packageName
                        Box(
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (selected) NovaCyan.copy(alpha = 0.2f) else NovaGlassFill)
                                .border(1.dp, if (selected) NovaCyan else NovaGlassBorder, RoundedCornerShape(999.dp))
                                .clickable { onSelectSystemTtsEngine(engine.packageName) }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .testTag("system_tts_engine_${engine.packageName}")
                        ) {
                            Text(engine.label, style = MaterialTheme.typography.labelSmall, color = NovaTextPrimary)
                        }
                    }
                }
            }

            // Installed Voices Selector
            if (uiState.availableTtsVoices.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "VOICE SELECTION (${uiState.availableTtsVoices.size})",
                        style = MaterialTheme.typography.labelSmall,
                        color = NovaTextSecondary
                    )
                    IconButton(
                        onClick = onRefreshVoices,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh voices",
                            tint = NovaCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    uiState.availableTtsVoices.take(8).forEach { voice ->
                        val selected = health.selectedSystemTtsVoiceName == voice.name
                        Box(
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (selected) NovaViolet.copy(alpha = 0.26f) else NovaGlassFill)
                                .border(1.dp, if (selected) NovaViolet else NovaGlassBorder, RoundedCornerShape(999.dp))
                                .clickable { onSelectSystemTtsVoice(voice.name) }
                                .padding(horizontal = 10.dp, vertical = 7.dp)
                                .testTag("tts_voice_${voice.name}")
                        ) {
                            Text(
                                text = voice.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) NovaTextPrimary else NovaTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Speech Rate + Test Voice Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.85f to "0.85x", 1.0f to "1.0x", 1.15f to "1.15x", 1.3f to "1.3x").forEach { (rate, label) ->
                        val selected = kotlin.math.abs(health.ttsSpeechRate - rate) < 0.05f
                        Box(
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (selected) NovaViolet.copy(alpha = 0.3f) else NovaGlassFill)
                                .border(1.dp, if (selected) NovaViolet else NovaGlassBorder, RoundedCornerShape(999.dp))
                                .clickable { onSetTtsSpeechRate(rate) }
                                .padding(horizontal = 10.dp, vertical = 7.dp)
                                .testTag("tts_rate_$label")
                        ) {
                            Text(label, style = MaterialTheme.typography.labelSmall, color = NovaTextPrimary)
                        }
                    }
                }
                OutlinedButton(
                    onClick = onTestSpeakVoice,
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(1.dp, NovaCyan.copy(alpha = 0.6f)),
                    modifier = Modifier.testTag("tts_test_voice_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Test voice output",
                        tint = NovaCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Test Voice", color = NovaCyan, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
