package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.data.PipelineStepPill
import com.example.data.StepStatus
import com.example.ui.VoiceOverlayUiState
import com.example.ui.components.GlassCard
import com.example.ui.components.VoiceOrbCanvas
import com.example.ui.theme.NovaAmber
import com.example.ui.theme.NovaCoralRed
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaEmerald
import com.example.ui.theme.NovaGlassBorder
import com.example.ui.theme.NovaGlassFill
import com.example.ui.theme.NovaLavender
import com.example.ui.theme.NovaMagenta
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import com.example.ui.theme.NovaViolet
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceOverlayScreen(
    voiceState: VoiceOverlayUiState,
    onToggleKeyboard: () -> Unit,
    onSubmitCommand: (String) -> Unit,
    onListeningStateChange: (Boolean, Float) -> Unit,
    onPartialTranscript: (String, Float) -> Unit,
    onKillSwitchAbort: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var typedCommand by remember { mutableStateOf("") }

    val suggestionChips = remember {
        listOf(
            "Turn on bluetooth",
            "What apps do I have?",
            "Add coffee to shopping list",
            "What's on my screen?",
            "Battery level",
            "Set timer 5 min",
            "Open Settings"
        )
    }

    val speechRecognizer = remember(context) {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else null
    }

    DisposableEffect(speechRecognizer) {
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                onListeningStateChange(true, 0.35f)
            }

            override fun onBeginningOfSpeech() {
                onListeningStateChange(true, 0.65f)
            }

            override fun onRmsChanged(rmsdB: Float) {
                val norm = ((rmsdB + 2f) / 12f).coerceIn(0.2f, 1.0f)
                onPartialTranscript(voiceState.transcript, norm)
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                onListeningStateChange(false, 0.25f)
            }

            override fun onError(error: Int) {
                onListeningStateChange(false, 0.2f)
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spoken = matches?.firstOrNull().orEmpty()
                if (spoken.isNotBlank()) {
                    onSubmitCommand(spoken)
                } else {
                    onListeningStateChange(false, 0.2f)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (partial.isNotBlank()) {
                    onPartialTranscript(partial, 0.6f)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        onDispose {
            runCatching { speechRecognizer?.destroy() }
        }
    }

    fun startSpeechCapture() {
        if (speechRecognizer == null) {
            // Device/emulator without Google Speech service: open keyboard input seamlessly
            onListeningStateChange(true, 0.5f)
            if (!voiceState.showKeyboardInput) onToggleKeyboard()
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        onListeningStateChange(true, 0.45f)
        runCatching { speechRecognizer.startListening(intent) }
            .onFailure {
                onListeningStateChange(false, 0.2f)
                if (!voiceState.showKeyboardInput) onToggleKeyboard()
            }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startSpeechCapture()
        } else {
            if (!voiceState.showKeyboardInput) onToggleKeyboard()
        }
    }

    fun handleMicTap() {
        if (voiceState.isListening) {
            runCatching { speechRecognizer?.stopListening() }
            onListeningStateChange(false, 0.2f)
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            startSpeechCapture()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 620.dp)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Suggestion Chips + Close
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(suggestionChips) { chip ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(NovaGlassFill)
                                .border(1.dp, NovaGlassBorder, RoundedCornerShape(999.dp))
                                .clickable { onSubmitCommand(chip) }
                                .padding(horizontal = 14.dp, vertical = 9.dp)
                                .testTag("voice_chip_${chip.take(12).lowercase().replace(' ', '_')}")
                        ) {
                            Text(
                                text = chip,
                                style = MaterialTheme.typography.bodyMedium,
                                color = NovaTextSecondary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = voiceState.statusHeader,
                    style = MaterialTheme.typography.labelMedium,
                    color = NovaLavender,
                    modifier = Modifier.testTag("voice_status_header")
                )
            }

            // Centerpiece 3D Voice Orb + Live Transcription + Step Pills
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                VoiceOrbCanvas(
                    mode = voiceState.orbMode,
                    amplitude = voiceState.amplitude,
                    modifier = Modifier
                        .size(210.dp)
                        .clickable { handleMicTap() }
                )

                Spacer(modifier = Modifier.height(14.dp))

                if (voiceState.transcript.isNotBlank()) {
                    Text(
                        text = "\"${voiceState.transcript}\"",
                        style = MaterialTheme.typography.titleLarge,
                        color = NovaTextPrimary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .testTag("voice_live_transcript")
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                if (voiceState.stepPills.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        voiceState.stepPills.forEach { step ->
                            StepPillBadge(step)
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }

                if (voiceState.assistantResponse.isNotBlank()) {
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        cornerRadius = 20.dp
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = voiceState.lastRoutingTier?.displayName ?: "NOVA ON-DEVICE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NovaCyan
                                )
                                if (voiceState.lastLatencyMs != null) {
                                    Text(
                                        text = "${voiceState.lastLatencyMs} ms",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NovaEmerald
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = voiceState.assistantResponse,
                                style = MaterialTheme.typography.bodyLarge,
                                color = NovaTextPrimary,
                                modifier = Modifier.testTag("voice_response_text")
                            )
                            if (voiceState.requiresModelSetup) {
                                Spacer(modifier = Modifier.height(12.dp))
                                androidx.compose.material3.Button(
                                    onClick = {
                                        onDismiss()
                                        onOpenSettings()
                                    },
                                    shape = RoundedCornerShape(999.dp),
                                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                        containerColor = NovaViolet,
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier.testTag("voice_open_settings_button")
                                ) {
                                    Text("Open Settings to Download LLM", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                }
            }

            // Inline Keyboard Input + Bottom Concentric Mic / Kill-Switch Row
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AnimatedVisibility(visible = voiceState.showKeyboardInput) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = typedCommand,
                            onValueChange = { typedCommand = it },
                            placeholder = {
                                Text(
                                    "Ask Nova (e.g. Search local notes, Summarize screen)",
                                    color = NovaTextMuted
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
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    if (typedCommand.isNotBlank()) {
                                        onSubmitCommand(typedCommand)
                                        typedCommand = ""
                                    }
                                }
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("voice_keyboard_textfield")
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                if (typedCommand.isNotBlank()) {
                                    onSubmitCommand(typedCommand)
                                    typedCommand = ""
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(NovaViolet)
                                .testTag("voice_keyboard_send_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send command",
                                tint = Color.White
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Keyboard toggle button
                    IconButton(
                        onClick = onToggleKeyboard,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(if (voiceState.showKeyboardInput) NovaViolet else NovaGlassFill)
                            .border(1.dp, NovaGlassBorder, CircleShape)
                            .testTag("voice_keyboard_toggle")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Keyboard,
                            contentDescription = "Toggle keyboard command input",
                            tint = Color.White
                        )
                    }

                    // Concentric Glowing Mic Trigger
                    Box(
                        modifier = Modifier
                            .size(82.dp)
                            .clip(CircleShape)
                            .background(NovaViolet.copy(alpha = 0.22f))
                            .border(1.dp, NovaLavender.copy(alpha = 0.45f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .shadow(18.dp, CircleShape, ambientColor = NovaViolet, spotColor = NovaMagenta)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(listOf(NovaViolet, NovaMagenta)))
                                .clickable { handleMicTap() }
                                .testTag("voice_concentric_mic"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Mic,
                                contentDescription = if (voiceState.isListening) "Stop listening" else "Speak to Nova",
                                tint = Color.White,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }

                    // Immediate Kill-Switch / Close ('X') Button
                    IconButton(
                        onClick = {
                            runCatching { speechRecognizer?.cancel() }
                            onKillSwitchAbort()
                            onDismiss()
                        },
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(NovaCoralRed.copy(alpha = 0.18f))
                            .border(1.dp, NovaCoralRed.copy(alpha = 0.45f), CircleShape)
                            .testTag("voice_kill_switch_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Stop and close voice overlay",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepPillBadge(step: PipelineStepPill) {
    val (bg, tint, icon) = when (step.status) {
        StepStatus.DONE -> Triple(NovaEmerald.copy(alpha = 0.16f), NovaEmerald, Icons.Filled.CheckCircle)
        StepStatus.ACTIVE -> Triple(NovaCyan.copy(alpha = 0.18f), NovaCyan, Icons.Filled.RadioButtonChecked)
        StepStatus.BLOCKED -> Triple(NovaAmber.copy(alpha = 0.20f), NovaAmber, Icons.Filled.PauseCircle)
        StepStatus.PENDING -> Triple(NovaGlassFill, NovaTextMuted, Icons.Filled.PauseCircle)
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(1.dp, tint.copy(alpha = 0.4f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(5.dp))
        val suffix = step.latencyMs?.let { " • ${it}ms" }.orEmpty()
        Text(
            text = "${step.index}. ${step.label}$suffix",
            style = MaterialTheme.typography.labelSmall,
            color = NovaTextPrimary
        )
    }
}
