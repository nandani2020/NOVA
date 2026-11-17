package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.components.DockDestination
import com.example.ui.components.NovaFloatingBottomBar
import com.example.ui.components.NovaGradientBackground
import com.example.ui.screens.HomeHubScreen
import com.example.ui.screens.NovaSettingsSheet
import com.example.ui.screens.RadialControlScreen
import com.example.ui.screens.TierRedConfirmationSheet
import com.example.ui.screens.VoiceOverlayScreen
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

    LaunchedEffect(uiState.statusToast) {
        if (uiState.statusToast != null) {
            delay(3200L)
            viewModel.clearStatusToast()
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
                        onToggleKeyboard = viewModel::toggleKeyboardInput,
                        onSubmitCommand = viewModel::submitCommand,
                        onListeningStateChange = viewModel::setVoiceListening,
                        onPartialTranscript = viewModel::updateLiveTranscript,
                        onKillSwitchAbort = viewModel::killSwitchAbort,
                        onOpenSettings = { viewModel.setSettingsVisible(true) },
                        onDismiss = { navController.popBackStack() }
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
                    onDeleteModel = viewModel::deleteInstalledModel,
                    onReloadModel = viewModel::reloadModel,
                    onToggleWakeWord = viewModel::setWakeWordEnabled,
                    onTogglePreferNpu = viewModel::setPreferNpuBackend,
                    onUpdateUserName = viewModel::updateUserName,
                    onSaveDeviceAccount = viewModel::saveDeviceAccount,
                    onDetectDeviceAccount = viewModel::detectDeviceAccount,
                    onSyncApps = viewModel::syncInstalledAppsToFts,
                    onSyncContacts = viewModel::syncContactsToFts,
                    onSyncCalendar = viewModel::syncCalendarToFts,
                    onIndexDocument = viewModel::indexDocumentUriToFts,
                    onFtsQueryChange = viewModel::refreshFtsQuery,
                    onAddMemoryNote = viewModel::addLocalMemoryNote,
                    onDismiss = { viewModel.setSettingsVisible(false) }
                )
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
        }
    }
}
