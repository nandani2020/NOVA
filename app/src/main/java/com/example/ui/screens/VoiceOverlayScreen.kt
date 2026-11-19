package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import com.example.data.AsrEngineOption
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceOverlayScreen(
    voiceState: VoiceOverlayUiState,
    asrEngine: AsrEngineOption = AsrEngineOption.ANDROID_SYSTEM,
    thinkingEnabled: Boolean = true,
    onToggleThinking: (Boolean) -> Unit = {},
    autoListenOnOverlay: Boolean = false,
    onConsumeAutoListen: () -> Unit = {},
    onToggleKeyboard: () -> Unit,
    onSubmitCommand: (String) -> Unit,
    onListeningStateChange: (Boolean, Float) -> Unit,
    onPartialTranscript: (String, Float) -> Unit,
    onAmplitudeChanged: (Float) -> Unit = {},
    onMicPermissionGranted: () -> Unit = {},
    onFollowUpSpeechStarted: () -> Unit = {},
    onFollowUpSpeechNoMatch: () -> Unit = {},
    onCancelFollowUp: () -> Unit = {},
    onOverlayVisibilityChanged: (Boolean) -> Unit = {},
    onFallbackServiceListen: () -> Unit = {},
    onFallbackServiceOneShotMic: () -> Unit = {},
    onInterruptSpeaking: (Boolean) -> Unit = {},
    onKillSwitchAbort: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var typedCommand by remember { mutableStateOf("") }
    var recognizerGeneration by remember { mutableIntStateOf(0) }
    var followUpFailureCount by remember { mutableIntStateOf(0) }
    var captureAttemptId by remember { mutableIntStateOf(0) }
    var micReadyForCurrentAttempt by remember { mutableStateOf(false) }
    var lastHandledCancelTrigger by remember { mutableStateOf(voiceState.followUpCancelTrigger) }
    val latestAwaitingFollowUp by rememberUpdatedState(voiceState.isAwaitingFollowUp)
    val latestIsSpeakingTts by rememberUpdatedState(voiceState.isSpeakingTts)
    val latestUseServiceFollowUpMic by rememberUpdatedState(voiceState.useServiceFollowUpMic)
    val latestOnFollowUpSpeechStarted by rememberUpdatedState(onFollowUpSpeechStarted)
    val latestOnFollowUpSpeechNoMatch by rememberUpdatedState(onFollowUpSpeechNoMatch)
    val latestOnFallbackServiceListen by rememberUpdatedState(onFallbackServiceListen)
    val latestOnFallbackServiceOneShotMic by rememberUpdatedState(onFallbackServiceOneShotMic)

    val suggestionChips = remember {
        listOf(
            "Turn on bluetooth",
            "Read my notifications",
            "Create reminder buy milk",
            "Play a random song",
            "What apps do I have?",
            "Add coffee to shopping list",
            "What's on my screen?",
            "Set timer 5 min"
        )
    }

    val speechRecognizer = remember(context, recognizerGeneration, asrEngine) {
        val standardAvail = SpeechRecognizer.isRecognitionAvailable(context)
        val onDeviceAvail = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        val preferOnDevice = asrEngine == AsrEngineOption.ANDROID_ON_DEVICE
        when {
            preferOnDevice && onDeviceAvail && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
            standardAvail -> runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
            onDeviceAvail && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
            else -> null
        }
    }

    fun startSpeechCapture(silenceTimeoutMs: Long = 2500L) {
        val recognizer = speechRecognizer
        if (recognizer == null) {
            if (latestAwaitingFollowUp && !latestUseServiceFollowUpMic) {
                latestOnFallbackServiceListen()
                return
            }
            // Device/emulator without Google Speech service: open keyboard input seamlessly
            onListeningStateChange(false, 0.2f)
            if (!voiceState.showKeyboardInput) onToggleKeyboard()
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceTimeoutMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceTimeoutMs)
        }
        micReadyForCurrentAttempt = false
        val attemptId = ++captureAttemptId
        onListeningStateChange(true, 0.45f)
        runCatching {
            recognizer.startListening(intent)
        }.onSuccess {
            if (latestAwaitingFollowUp && !latestUseServiceFollowUpMic) {
                coroutineScope.launch {
                    delay(1800L)
                    if (!micReadyForCurrentAttempt &&
                        captureAttemptId == attemptId &&
                        latestAwaitingFollowUp &&
                        !latestIsSpeakingTts &&
                        !latestUseServiceFollowUpMic
                    ) {
                        followUpFailureCount++
                        if (followUpFailureCount >= 2) {
                            latestOnFallbackServiceListen()
                        } else {
                            recognizerGeneration++
                        }
                    }
                }
            }
        }.onFailure {
            followUpFailureCount++
            if (latestAwaitingFollowUp && !latestIsSpeakingTts) {
                if (followUpFailureCount <= 2) {
                    recognizerGeneration++
                } else {
                    latestOnFallbackServiceListen()
                }
            } else {
                onListeningStateChange(false, 0.2f)
                if (!voiceState.showKeyboardInput) onToggleKeyboard()
            }
        }
    }

    DisposableEffect(Unit) {
        onOverlayVisibilityChanged(true)
        onDispose {
            onOverlayVisibilityChanged(false)
            onCancelFollowUp()
            onListeningStateChange(false, 0.2f)
        }
    }

    DisposableEffect(speechRecognizer) {
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                micReadyForCurrentAttempt = true
                followUpFailureCount = 0
                onListeningStateChange(true, 0.35f)
            }

            override fun onBeginningOfSpeech() {
                micReadyForCurrentAttempt = true
                followUpFailureCount = 0
                if (latestAwaitingFollowUp) {
                    latestOnFollowUpSpeechStarted()
                }
                onListeningStateChange(true, 0.65f)
            }

            override fun onRmsChanged(rmsdB: Float) {
                val norm = ((rmsdB + 2f) / 12f).coerceIn(0.2f, 1.0f)
                onAmplitudeChanged(norm)
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                onListeningStateChange(false, 0.25f)
            }

            override fun onError(error: Int) {
                micReadyForCurrentAttempt = false
                onListeningStateChange(false, 0.2f)
                val hardwareOrBusy = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                    error == SpeechRecognizer.ERROR_CLIENT ||
                    error == SpeechRecognizer.ERROR_AUDIO ||
                    error == SpeechRecognizer.ERROR_NETWORK ||
                    error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                    error == 11 // ERROR_SERVER_DISCONNECTED
                if (!latestAwaitingFollowUp) {
                    if (!latestIsSpeakingTts &&
                        (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                            error == SpeechRecognizer.ERROR_AUDIO ||
                            error == SpeechRecognizer.ERROR_CLIENT)
                    ) {
                        latestOnFallbackServiceOneShotMic()
                    }
                    return
                }
                if (latestIsSpeakingTts || latestUseServiceFollowUpMic) return

                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    if (!voiceState.showKeyboardInput) onToggleKeyboard()
                    return
                }

                val retryableTimeout = error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

                if (hardwareOrBusy) {
                    followUpFailureCount++
                    if (followUpFailureCount >= 3) {
                        latestOnFallbackServiceListen()
                        return
                    }
                    coroutineScope.launch {
                        delay(300L)
                        if (latestAwaitingFollowUp && !latestIsSpeakingTts && !latestUseServiceFollowUpMic) {
                            recognizerGeneration++
                        }
                    }
                } else if (retryableTimeout) {
                    latestOnFollowUpSpeechNoMatch()
                    coroutineScope.launch {
                        delay(180L)
                        if (latestAwaitingFollowUp && !latestIsSpeakingTts && !latestUseServiceFollowUpMic) {
                            // Recreate fresh SpeechRecognizer instance so Android vendor HAL reopens reliably
                            recognizerGeneration++
                        }
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spoken = matches?.firstOrNull().orEmpty()
                if (spoken.isNotBlank()) {
                    followUpFailureCount = 0
                    onSubmitCommand(spoken)
                } else {
                    onListeningStateChange(false, 0.2f)
                    if (latestAwaitingFollowUp && !latestIsSpeakingTts && !latestUseServiceFollowUpMic) {
                        latestOnFollowUpSpeechNoMatch()
                        coroutineScope.launch {
                            delay(180L)
                            if (latestAwaitingFollowUp && !latestIsSpeakingTts && !latestUseServiceFollowUpMic) {
                                recognizerGeneration++
                            }
                        }
                    }
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (partial.isNotBlank()) {
                    if (latestAwaitingFollowUp) {
                        latestOnFollowUpSpeechStarted()
                    }
                    onPartialTranscript(partial, 0.6f)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        onDispose {
            runCatching { speechRecognizer?.destroy() }
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onMicPermissionGranted()
            startSpeechCapture()
        } else {
            if (!voiceState.showKeyboardInput) onToggleKeyboard()
        }
    }

    fun handleMicTap() {
        if (voiceState.isSpeakingTts) {
            onInterruptSpeaking(true)
            return
        }
        if (voiceState.isListening) {
            onCancelFollowUp()
            runCatching { speechRecognizer?.stopListening() }
            onListeningStateChange(false, 0.2f)
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            onMicPermissionGranted()
            startSpeechCapture()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(autoListenOnOverlay, voiceState.isSpeakingTts, voiceState.useServiceFollowUpMic) {
        if (autoListenOnOverlay && !voiceState.isSpeakingTts && !voiceState.useServiceFollowUpMic) {
            delay(260L)
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                startSpeechCapture(silenceTimeoutMs = 10_000L)
            } else {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            // Consume AFTER capture begins so autoListenOnOverlay key change does not cancel delay(260L)
            onConsumeAutoListen()
        }
    }

    LaunchedEffect(
        voiceState.followUpListenTrigger,
        voiceState.isAwaitingFollowUp,
        voiceState.isSpeakingTts,
        voiceState.useServiceFollowUpMic,
        speechRecognizer
    ) {
        if (voiceState.useServiceFollowUpMic) {
            runCatching { speechRecognizer?.cancel() }
            return@LaunchedEffect
        }
        if ((voiceState.followUpListenTrigger > 0L || autoListenOnOverlay) &&
            voiceState.isAwaitingFollowUp &&
            !voiceState.isSpeakingTts
        ) {
            delay(280L)
            if (!voiceState.isAwaitingFollowUp || voiceState.isSpeakingTts || voiceState.useServiceFollowUpMic) {
                return@LaunchedEffect
            }
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                startSpeechCapture(silenceTimeoutMs = 10_000L)
            } else {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    LaunchedEffect(voiceState.followUpCancelTrigger) {
        val trigger = voiceState.followUpCancelTrigger
        if (trigger == 0L) {
            lastHandledCancelTrigger = 0L
        } else if (trigger != lastHandledCancelTrigger) {
            lastHandledCancelTrigger = trigger
            if (!latestAwaitingFollowUp) {
                runCatching { speechRecognizer?.cancel() }
                onListeningStateChange(false, 0.2f)
            }
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

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = voiceState.statusHeader,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (voiceState.isAwaitingFollowUp) NovaCyan else NovaLavender,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("voice_status_header")
                    )
                    Row(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                if (thinkingEnabled) NovaViolet.copy(alpha = 0.28f) else NovaGlassFill
                            )
                            .border(
                                1.dp,
                                if (thinkingEnabled) NovaViolet else NovaGlassBorder,
                                RoundedCornerShape(999.dp)
                            )
                            .clickable { onToggleThinking(!thinkingEnabled) }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                            .testTag("voice_thinking_toggle"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Psychology,
                            contentDescription = if (thinkingEnabled) "Turn off thinking mode" else "Turn on thinking mode",
                            tint = if (thinkingEnabled) NovaLavender else NovaTextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (thinkingEnabled) "Thinking ON" else "Thinking OFF",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (thinkingEnabled) NovaTextPrimary else NovaTextMuted
                        )
                    }
                }
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

                if (voiceState.isSpeakingTts) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(999.dp))
                            .background(NovaCoralRed.copy(alpha = 0.24f))
                            .border(1.dp, NovaCoralRed.copy(alpha = 0.65f), RoundedCornerShape(999.dp))
                            .clickable { onInterruptSpeaking(true) }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .testTag("voice_interrupt_button"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Stop,
                            contentDescription = "Interrupt Nova and speak",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Tap to Interrupt & Speak",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White
                        )
                    }
                }

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
                            if (voiceState.requiresAccessibilitySetup) {
                                Spacer(modifier = Modifier.height(12.dp))
                                androidx.compose.material3.Button(
                                    onClick = {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            )
                                        }
                                    },
                                    shape = RoundedCornerShape(999.dp),
                                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                        containerColor = NovaCyan,
                                        contentColor = Color.Black
                                    ),
                                    modifier = Modifier.testTag("voice_enable_accessibility_button")
                                ) {
                                    Text("Enable Accessibility Screen Control", style = MaterialTheme.typography.labelLarge)
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

                    // Concentric Glowing Mic / Interrupt Trigger
                    Box(
                        modifier = Modifier
                            .size(82.dp)
                            .clip(CircleShape)
                            .background(
                                if (voiceState.isSpeakingTts) NovaCoralRed.copy(alpha = 0.22f)
                                else NovaViolet.copy(alpha = 0.22f)
                            )
                            .border(
                                1.dp,
                                if (voiceState.isSpeakingTts) NovaCoralRed.copy(alpha = 0.5f)
                                else NovaLavender.copy(alpha = 0.45f),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .shadow(
                                    18.dp,
                                    CircleShape,
                                    ambientColor = if (voiceState.isSpeakingTts) NovaCoralRed else NovaViolet,
                                    spotColor = if (voiceState.isSpeakingTts) NovaAmber else NovaMagenta
                                )
                                .clip(CircleShape)
                                .background(
                                    if (voiceState.isSpeakingTts) {
                                        Brush.linearGradient(listOf(NovaCoralRed, NovaAmber))
                                    } else {
                                        Brush.linearGradient(listOf(NovaViolet, NovaMagenta))
                                    }
                                )
                                .clickable { handleMicTap() }
                                .testTag("voice_concentric_mic"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (voiceState.isSpeakingTts) Icons.Filled.Stop else Icons.Filled.Mic,
                                contentDescription = when {
                                    voiceState.isSpeakingTts -> "Interrupt assistant and speak"
                                    voiceState.isListening -> "Stop listening"
                                    else -> "Speak to Nova"
                                },
                                tint = Color.White,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }

                    // Immediate Kill-Switch / Close ('X') Button
                    IconButton(
                        onClick = {
                            onCancelFollowUp()
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
