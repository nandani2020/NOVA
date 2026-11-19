package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MarkChatUnread
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.engine.ImportantNotificationFilter.ImportantNotificationAlert
import com.example.engine.ImportantNotificationFilter.ImportantNotificationType
import com.example.ui.components.DockDestination
import com.example.ui.components.GlassCard
import com.example.ui.components.NovaFloatingBottomBar
import com.example.ui.components.NovaGradientBackground
import com.example.ui.components.NovaStatusPill
import com.example.ui.screens.HiddenReadmeScreen
import com.example.ui.screens.HomeHubScreen
import com.example.ui.screens.NovaSettingsSheet
import com.example.ui.screens.RadialControlScreen
import com.example.ui.screens.RoutineStudioScreen
import com.example.ui.screens.TierRedConfirmationSheet
import com.example.ui.screens.VoiceOverlayScreen
import com.example.ui.theme.NovaAmber
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaEmerald
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import com.example.ui.theme.NovaViolet
import kotlinx.coroutines.delay

object NovaRoutes {
    const val HOME_HUB = "home_hub"
    const val VOICE_OVERLAY = "voice_overlay"
    const val RADIAL_CONTROL = "radial_control"
}

@Composable
fun NovaApp(
    viewModel: NovaViewModel,
    initialOpenVoice: Boolean = false
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: NovaRoutes.HOME_HUB
    var openedInitialVoice by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(initialOpenVoice) {
        if (initialOpenVoice && !openedInitialVoice) {
            openedInitialVoice = true
            navController.navigate(NovaRoutes.VOICE_OVERLAY)
        }
    }

    LaunchedEffect(uiState.openVoiceTrigger) {
        if (uiState.openVoiceTrigger > 0L && currentRoute != NovaRoutes.VOICE_OVERLAY) {
            navController.navigate(NovaRoutes.VOICE_OVERLAY)
        }
    }

    LaunchedEffect(uiState.statusToast) {
        if (uiState.statusToast != null) {
            delay(3200L)
            viewModel.clearStatusToast()
        }
    }

    LaunchedEffect(uiState.activeNotificationPrompt?.id) {
        if (uiState.activeNotificationPrompt != null) {
            delay(18000L)
            viewModel.dismissNotificationPrompt()
        }
    }

    NovaGradientBackground {
        Box(modifier = Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = NovaRoutes.HOME_HUB,
                modifier = Modifier.fillMaxSize()
            ) {
                composable(NovaRoutes.HOME_HUB) {
                    HomeHubScreen(
                        uiState = uiState,
                        onSelectCategory = viewModel::selectCategory,
                        onToggleControl = viewModel::toggleControl,
                        onOpenControlDetail = { control ->
                            viewModel.selectDetailControl(control.id)
                            navController.navigate(NovaRoutes.RADIAL_CONTROL)
                        },
                        onRunAppOrRoutineCommand = { cmd ->
                            if (cmd == "Sync installed apps") {
                                viewModel.syncInstalledAppsToFts()
                            } else {
                                navController.navigate(NovaRoutes.VOICE_OVERLAY)
                                viewModel.submitCommand(cmd)
                            }
                        },
                        onOpenVoiceOverlay = {
                            navController.navigate(NovaRoutes.VOICE_OVERLAY)
                        },
                        onOpenSettings = {
                            viewModel.setSettingsVisible(true)
                        },
                        onEnableWakeWordFromHub = {
                            viewModel.setWakeWordEnabled(true)
                        },
                        onLaunchFloatingRoutineControl = {
                            viewModel.launchFloatingRoutineControl()
                        },
                        onStopAndSaveFloatingRecording = {
                            viewModel.stopAndSaveFloatingRecording()
                        },
                        onOpenRoutineEditor = viewModel::openRoutineEditor,
                        onRunRecordedRoutine = { rt ->
                            viewModel.runRecordedRoutine(rt)
                        },
                        onDeleteRecordedRoutine = viewModel::deleteRecordedRoutine,
                        onOpenHiddenReadme = { viewModel.setHiddenReadmeVisible(true) },
                        onExitStandMode = viewModel::toggleAmbientStandMode
                    )
                }

                composable(NovaRoutes.RADIAL_CONTROL) {
                    val activeControl = uiState.controls.firstOrNull {
                        it.id == uiState.activeDetailControlId
                    } ?: uiState.controls.first()

                    RadialControlScreen(
                        control = activeControl,
                        onBack = { navController.popBackStack() },
                        onToggleMaster = { viewModel.toggleControl(activeControl.id) },
                        onValueChange = { v -> viewModel.updateControlValue(activeControl.id, v) },
                        onSelectMode = { mode -> viewModel.updateControlMode(activeControl.id, mode) },
                        onSelectIntensity = { preset ->
                            viewModel.updateControlIntensity(activeControl.id, preset)
                        }
                    )
                }

                composable(NovaRoutes.VOICE_OVERLAY) {
                    VoiceOverlayScreen(
                        voiceState = uiState.voiceState,
                        asrEngine = uiState.processHealth.asrEngine,
                        thinkingEnabled = uiState.processHealth.thinkingEnabled,
                        onToggleThinking = viewModel::setThinkingEnabled,
                        autoListenOnOverlay = uiState.autoListenOnOverlay,
                        onConsumeAutoListen = viewModel::consumeAutoListen,
                        onToggleKeyboard = viewModel::toggleKeyboardInput,
                        onSubmitCommand = viewModel::submitCommand,
                        onListeningStateChange = viewModel::setVoiceListening,
                        onPartialTranscript = viewModel::updateLiveTranscript,
                        onAmplitudeChanged = viewModel::updateVoiceAmplitude,
                        onMicPermissionGranted = viewModel::onMicPermissionGranted,
                        onFollowUpSpeechStarted = viewModel::onFollowUpSpeechStarted,
                        onFollowUpSpeechNoMatch = viewModel::onFollowUpSpeechNoMatch,
                        onCancelFollowUp = viewModel::cancelFollowUpWindow,
                        onOverlayVisibilityChanged = viewModel::setVoiceOverlayOpen,
                        onFallbackServiceListen = viewModel::requestServiceFollowUpFallback,
                        onFallbackServiceOneShotMic = viewModel::requestServiceOneShotMicCapture,
                        onInterruptSpeaking = viewModel::interruptSpeaking,
                        onKillSwitchAbort = viewModel::killSwitchAbort,
                        onOpenSettings = { viewModel.setSettingsVisible(true) },
                        onDismiss = {
                            viewModel.cancelFollowUpWindow()
                            navController.popBackStack()
                        }
                    )
                }
            }

            // Floating Frosted Bottom Dock visible on Hub and Radial Control Detail screens
            if (currentRoute != NovaRoutes.VOICE_OVERLAY && !uiState.processHealth.ambientStandMode) {
                val dockDest = when {
                    uiState.showSettingsScreen -> DockDestination.SETTINGS
                    currentRoute == NovaRoutes.RADIAL_CONTROL -> DockDestination.CONTROLS
                    else -> DockDestination.HUB
                }
                NovaFloatingBottomBar(
                    currentDestination = dockDest,
                    ambientStandMode = uiState.processHealth.ambientStandMode,
                    onNavigateHub = {
                        viewModel.setSettingsVisible(false)
                        if (currentRoute != NovaRoutes.HOME_HUB) {
                            navController.popBackStack(NovaRoutes.HOME_HUB, inclusive = false)
                        }
                    },
                    onNavigateControls = {
                        viewModel.setSettingsVisible(false)
                        if (currentRoute != NovaRoutes.RADIAL_CONTROL) {
                            navController.navigate(NovaRoutes.RADIAL_CONTROL)
                        }
                    },
                    onOpenVoiceOverlay = {
                        viewModel.setSettingsVisible(false)
                        navController.navigate(NovaRoutes.VOICE_OVERLAY)
                    },
                    onOpenSettings = {
                        viewModel.setSettingsVisible(true)
                    },
                    onToggleStandMode = viewModel::toggleAmbientStandMode,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            // Settings & Engine Sheet (LLM Download, Permissions, FTS5)
            AnimatedVisibility(
                visible = uiState.showSettingsScreen,
                enter = slideInVertically { it / 2 } + fadeIn(),
                exit = slideOutVertically { it / 2 } + fadeOut()
            ) {
                BackHandler { viewModel.setSettingsVisible(false) }
                NovaSettingsSheet(
                    uiState = uiState,
                    onStartModelDownload = viewModel::startModelDownload,
                    onCancelModelDownload = viewModel::cancelModelDownload,
                    onImportLocalModel = viewModel::importLocalModel,
                    onSelectInstalledModel = viewModel::selectInstalledModel,
                    onDeleteSpecificModel = viewModel::deleteSpecificModel,
                    onDeleteModel = viewModel::deleteInstalledModel,
                    onReloadModel = viewModel::reloadModel,
                    onToggleWakeWord = viewModel::setWakeWordEnabled,
                    onRecordWakeCalibrationSample = viewModel::recordWakeCalibrationSample,
                    onResetWakeVoiceCalibration = viewModel::resetWakeVoiceCalibration,
                    onTogglePauseWakeDuringMedia = viewModel::setPauseWakeDuringMedia,
                    onSelectAsrEngine = viewModel::selectAsrEngine,
                    onSelectTtsEngine = viewModel::selectTtsEngine,
                    onSelectSystemTtsEngine = viewModel::selectSystemTtsEngine,
                    onSelectSystemTtsVoice = viewModel::selectSystemTtsVoice,
                    onSetTtsSpeechRate = viewModel::setTtsSpeechRate,
                    onTestSpeakVoice = viewModel::testSpeakVoice,
                    onRefreshVoices = viewModel::refreshInstalledModelsAndVoices,
                    onToggleProactiveNotifPrompts = viewModel::setProactiveNotificationPrompts,
                    onTogglePreferNpu = viewModel::setPreferNpuBackend,
                    onToggleThinking = viewModel::setThinkingEnabled,
                    onUpdateUserName = viewModel::updateUserName,
                    onSaveDeviceAccount = viewModel::saveDeviceAccount,
                    onDetectDeviceAccount = viewModel::detectDeviceAccount,
                    onSyncApps = viewModel::syncInstalledAppsToFts,
                    onSyncContacts = viewModel::syncContactsToFts,
                    onSyncCalendar = viewModel::syncCalendarToFts,
                    onIndexDocument = viewModel::indexDocumentUriToFts,
                    onFtsQueryChange = viewModel::refreshFtsQuery,
                    onAddMemoryNote = viewModel::addLocalMemoryNote,
                    onSelectPersonality = viewModel::setAssistantPersonality,
                    onDeleteAgenticMemory = viewModel::deleteAgenticMemory,
                    onDeleteLearnedProcess = viewModel::deleteLearnedProcess,
                    onDeleteLearnedProcessStep = viewModel::deleteLearnedProcessStep,
                    onRunLearnedProcess = viewModel::runLearnedProcess,
                    onClearAllAgenticLearning = viewModel::clearAllAgenticLearning,
                    onOpenHiddenReadme = { viewModel.setHiddenReadmeVisible(true) },
                    onDismiss = { viewModel.setSettingsVisible(false) }
                )
            }

            // Hidden Developer README & Live Capabilities Screen
            AnimatedVisibility(
                visible = uiState.showHiddenReadme,
                enter = slideInVertically { it / 2 } + fadeIn(),
                exit = slideOutVertically { it / 2 } + fadeOut()
            ) {
                HiddenReadmeScreen(
                    uiState = uiState,
                    onDismiss = { viewModel.setHiddenReadmeVisible(false) },
                    onNotifyToast = viewModel::showStatusToast
                )
            }

            // Routine Studio Overlay Modal
            AnimatedVisibility(
                visible = uiState.editingRoutine != null,
                enter = slideInVertically { it / 2 } + fadeIn(),
                exit = slideOutVertically { it / 2 } + fadeOut()
            ) {
                uiState.editingRoutine?.let { routine ->
                    RoutineStudioScreen(
                        initialRoutine = routine,
                        installedApps = uiState.installedAppsForRoutines,
                        accessibilityConnected = uiState.processHealth.accessibilityConnected,
                        onLaunchFloatingControl = { scope, appName, pkg, autoRec ->
                            viewModel.launchFloatingRoutineControl(
                                scope = scope,
                                targetAppName = appName,
                                targetPackage = pkg,
                                autoStartRecording = autoRec
                            )
                        },
                        onSaveRoutine = viewModel::saveRecordedRoutine,
                        onTestRunRoutine = { rt -> viewModel.runRecordedRoutine(rt) },
                        onDeleteRoutine = viewModel::deleteRecordedRoutine,
                        onDismiss = viewModel::closeRoutineEditor
                    )
                }
            }

            // Screen 4: Tier-Red Confirmation Bottom Sheet
            AnimatedVisibility(
                visible = uiState.pendingConfirmation != null,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                uiState.pendingConfirmation?.let { confirmation ->
                    BackHandler { viewModel.abortPendingTierRed() }
                    TierRedConfirmationSheet(
                        confirmation = confirmation,
                        onApprove = viewModel::approvePendingTierRed,
                        onAbort = viewModel::abortPendingTierRed
                    )
                }
            }

            // Proactive Important Notification Prompt Banner (Messages, Missed Calls, Reminders ONLY)
            AnimatedVisibility(
                visible = uiState.activeNotificationPrompt != null && uiState.pendingConfirmation == null,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                uiState.activeNotificationPrompt?.let { alert ->
                    ImportantNotificationPromptBanner(
                        alert = alert,
                        onReadAloud = viewModel::readActiveNotificationAloud,
                        onPrimaryAction = { viewModel.actOnNotificationPrompt(alert) },
                        onDismiss = viewModel::dismissNotificationPrompt
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportantNotificationPromptBanner(
    alert: ImportantNotificationAlert,
    onReadAloud: () -> Unit,
    onPrimaryAction: () -> Unit,
    onDismiss: () -> Unit
) {
    val badgeColor = when (alert.type) {
        ImportantNotificationType.MISSED_CALL -> NovaAmber
        ImportantNotificationType.REMINDER -> NovaEmerald
        ImportantNotificationType.MESSAGE -> NovaCyan
        ImportantNotificationType.GENERAL -> NovaCyan
    }
    val typeIcon = when (alert.type) {
        ImportantNotificationType.MISSED_CALL -> Icons.AutoMirrored.Filled.CallMissed
        ImportantNotificationType.REMINDER -> Icons.Filled.NotificationsActive
        ImportantNotificationType.MESSAGE -> Icons.Filled.MarkChatUnread
        ImportantNotificationType.GENERAL -> Icons.Filled.NotificationsActive
    }
    val actionLabel = when (alert.type) {
        ImportantNotificationType.MISSED_CALL -> "Call Back"
        ImportantNotificationType.REMINDER -> "Open"
        ImportantNotificationType.MESSAGE -> "Open"
        ImportantNotificationType.GENERAL -> "Open"
    }

    GlassCard(
        modifier = Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .testTag("important_notification_prompt"),
        cornerRadius = 20.dp,
        isHighlighted = true
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = typeIcon,
                        contentDescription = alert.type.label,
                        tint = badgeColor,
                        modifier = Modifier.size(18.dp)
                    )
                    NovaStatusPill(
                        text = "IMPORTANT ${alert.type.label.uppercase()}",
                        dotColor = badgeColor
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .testTag("dismiss_notif_prompt_btn")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Dismiss notification prompt",
                        tint = NovaTextSecondary
                    )
                }
            }

            Text(
                text = alert.shortLabel,
                style = MaterialTheme.typography.titleMedium,
                color = NovaTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (alert.body.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = alert.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NovaTextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onReadAloud,
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("read_notif_prompt_btn")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Read notification aloud",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Read", style = MaterialTheme.typography.labelLarge)
                }
                Button(
                    onClick = onPrimaryAction,
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NovaViolet,
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("act_notif_prompt_btn")
                ) {
                    Text(actionLabel, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
