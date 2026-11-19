package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.DoNotDisturbOn
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.ControlIconType
import com.example.data.HubCategory
import com.example.data.OrbVisualMode
import com.example.data.RecordedRoutine
import com.example.data.RoutineScope
import com.example.data.SmartControlItem
import com.example.data.WakeWordStatus
import com.example.ui.NovaUiState
import com.example.ui.components.GlassCard
import com.example.ui.components.NovaStatusPill
import com.example.ui.components.VoiceOrbCanvas
import com.example.ui.theme.NovaAmber
import com.example.ui.theme.NovaCoralRed
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaEmerald
import com.example.ui.theme.NovaLavender
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import com.example.ui.theme.NovaViolet
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun HomeHubScreen(
    uiState: NovaUiState,
    onSelectCategory: (HubCategory) -> Unit,
    onToggleControl: (String) -> Unit,
    onOpenControlDetail: (SmartControlItem) -> Unit,
    onRunAppOrRoutineCommand: (String) -> Unit,
    onOpenVoiceOverlay: () -> Unit,
    onOpenSettings: () -> Unit,
    onEnableWakeWordFromHub: () -> Unit = {},
    onLaunchFloatingRoutineControl: () -> Unit = {},
    onStopAndSaveFloatingRecording: () -> Unit = {},
    onOpenRoutineEditor: (RecordedRoutine?) -> Unit = {},
    onRunRecordedRoutine: (RecordedRoutine) -> Unit = {},
    onDeleteRecordedRoutine: (String) -> Unit = {},
    onOpenHiddenReadme: () -> Unit = {},
    onExitStandMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (uiState.processHealth.ambientStandMode) {
        AmbientStandModeView(
            uiState = uiState,
            onOpenVoiceOverlay = onOpenVoiceOverlay,
            onExitStandMode = onExitStandMode,
            modifier = modifier
        )
        return
    }

    val context = LocalContext.current
    var routinePendingDelete by remember { mutableStateOf<RecordedRoutine?>(null) }
    val wakeMicLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onEnableWakeWordFromHub()
        }
    }

    val filteredControls = remember(uiState.selectedCategory, uiState.controls) {
        uiState.controls.filter { it.category == uiState.selectedCategory }
    }

    val timeGreeting = remember {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when {
            hour < 12 -> "Good Morning"
            hour < 18 -> "Good Afternoon"
            else -> "Good Evening"
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 156.dp),
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Top Greeting & Avatar Header
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = onOpenHiddenReadme
                            )
                            .testTag("home_greeting_header")
                    ) {
                        Text(
                            text = "HI, ${uiState.userName}!",
                            style = MaterialTheme.typography.labelMedium,
                            color = NovaLavender
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = timeGreeting,
                            style = MaterialTheme.typography.headlineMedium,
                            color = NovaTextPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        val (statusText, dotColor) = when {
                            uiState.processHealth.isLlmReady ->
                                "LOCAL LLM READY (${uiState.processHealth.activeModelName ?: "Loaded"})" to NovaEmerald
                            uiState.processHealth.modelPresent ->
                                "MODEL PRESENT • ${uiState.processHealth.processBStatus}" to NovaAmber
                            else ->
                                "LLM NOT INSTALLED • REFLEX ONLY" to NovaCoralRed
                        }
                        val hasMicPerm = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        val (wakePillText, wakePillColor) = when {
                            uiState.processHealth.wakeWordStatus == WakeWordStatus.UNAVAILABLE ->
                                "HEY NOVA N/A • TAP MIC" to NovaAmber
                            !hasMicPerm || uiState.processHealth.wakeWordStatus == WakeWordStatus.MIC_PERMISSION_NEEDED ->
                                "ENABLE HEY NOVA" to NovaAmber
                            uiState.processHealth.wakeWordStatus == WakeWordStatus.PAUSED_FOR_MEDIA ->
                                "WAKE PAUSED • MUSIC" to NovaCyan
                            uiState.processHealth.wakeWordEnabled ->
                                "HEY NOVA READY" to NovaEmerald
                            else ->
                                "HEY NOVA OFF" to NovaAmber
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            NovaStatusPill(
                                text = statusText,
                                dotColor = dotColor,
                                modifier = Modifier.testTag("local_ai_status_pill"),
                                onClick = onOpenSettings
                            )
                            NovaStatusPill(
                                text = wakePillText,
                                dotColor = wakePillColor,
                                modifier = Modifier.testTag("hey_nova_status_pill"),
                                onClick = {
                                    if (!hasMicPerm) {
                                        wakeMicLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    } else if (!uiState.processHealth.wakeWordEnabled) {
                                        onEnableWakeWordFromHub()
                                    } else {
                                        onOpenVoiceOverlay()
                                    }
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Image(
                        painter = painterResource(id = R.drawable.img_user_avatar),
                        contentDescription = "Open Nova Settings",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .border(2.dp, NovaViolet, CircleShape)
                            .clickable(onClick = onOpenSettings)
                    )
                }
            }

            // Status Toast Banner
            if (uiState.statusToast != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    AnimatedVisibility(visible = true) {
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 16.dp,
                            isHighlighted = true
                        ) {
                            Text(
                                text = uiState.statusToast,
                                style = MaterialTheme.typography.bodyMedium,
                                color = NovaTextPrimary,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                            )
                        }
                    }
                }
            }

            // Real Device & LLM Status Summary Card
            item(span = { GridItemSpan(maxLineSpan) }) {
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("summary_status_card"),
                    cornerRadius = 20.dp
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "ON-DEVICE ENGINE STATUS",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NovaCyan
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                val modelHeadline = if (uiState.processHealth.modelPresent) {
                                    "${uiState.processHealth.activeModelName} (${uiState.processHealth.activeModelSizeMb} MB)"
                                } else {
                                    "Local LLM not downloaded"
                                }
                                Text(
                                    text = modelHeadline,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = NovaTextPrimary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${uiState.processHealth.indexedDocCount} ${uiState.processHealth.ftsMode} records • " +
                                        "${uiState.processHealth.unreadMessagesCount} alerts • " +
                                        if (uiState.processHealth.accessibilityConnected) "Accessibility ON" else "Accessibility OFF",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NovaTextSecondary
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Button(
                                onClick = onOpenSettings,
                                shape = RoundedCornerShape(999.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NovaViolet,
                                    contentColor = Color.White
                                ),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                                modifier = Modifier.testTag("view_details_button")
                            ) {
                                Text(
                                    text = if (uiState.processHealth.modelPresent) "Settings" else "Get LLM",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }
            }

            // Horizontal Scrollable Category Tabs (Smart Home excluded)
            item(span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(HubCategory.entries) { category ->
                        val selected = category == uiState.selectedCategory
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(
                                    if (selected) NovaViolet else Color(0x14FFFFFF)
                                )
                                .border(
                                    1.dp,
                                    if (selected) NovaLavender else Color(0x1FFFFFFF),
                                    RoundedCornerShape(999.dp)
                                )
                                .clickable { onSelectCategory(category) }
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                                .testTag("category_tab_${category.name.lowercase()}")
                        ) {
                            Text(
                                text = category.title,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) Color.White else NovaTextSecondary
                            )
                        }
                    }
                }
            }

            if (uiState.selectedCategory == HubCategory.ROUTINES) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    RoutineRecorderStudioBanner(
                        uiState = uiState,
                        onLaunchFloating = onLaunchFloatingRoutineControl,
                        onStopAndSave = onStopAndSaveFloatingRecording,
                        onNewRoutine = { onOpenRoutineEditor(null) },
                        onOpenAccessibilitySettings = {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                    )
                }

                items(
                    items = uiState.recordedRoutines,
                    key = { "recorded_${it.id}" },
                    span = { GridItemSpan(maxLineSpan) }
                ) { routine ->
                    RecordedRoutineHubCard(
                        routine = routine,
                        onStart = { onRunRecordedRoutine(routine) },
                        onEdit = { onOpenRoutineEditor(routine) },
                        onDelete = { routinePendingDelete = routine }
                    )
                }
            }

            // 2-Column Quick Action & Device Grid
            items(filteredControls, key = { it.id }) { item ->
                DeviceGridTile(
                    item = item,
                    onToggle = { onToggleControl(item.id) },
                    onCardClick = {
                        when {
                            item.hasRadialDetail -> onOpenControlDetail(item)
                            item.packageHint != null -> onRunAppOrRoutineCommand(item.packageHint)
                            else -> onToggleControl(item.id)
                        }
                    }
                )
            }
        }

        val toDelete = routinePendingDelete
        if (toDelete != null) {
            AlertDialog(
                onDismissRequest = { routinePendingDelete = null },
                title = { Text("Delete Routine?") },
                text = {
                    Text("Delete \"${toDelete.name}\" and its ${toDelete.steps.size} saved steps? This cannot be undone.")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onDeleteRecordedRoutine(toDelete.id)
                            routinePendingDelete = null
                        },
                        modifier = Modifier.testTag("confirm_delete_routine_dialog_btn")
                    ) {
                        Text("Delete", color = NovaCoralRed)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { routinePendingDelete = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
private fun RoutineRecorderStudioBanner(
    uiState: NovaUiState,
    onLaunchFloating: () -> Unit,
    onStopAndSave: () -> Unit,
    onNewRoutine: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit
) {
    val rec = uiState.recorderState
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("routine_recorder_banner"),
        cornerRadius = 20.dp,
        isHighlighted = rec.isRecording || rec.isFloatingVisible
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ROUTINE RECORDER & STUDIO",
                        style = MaterialTheme.typography.labelSmall,
                        color = NovaCyan
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = when {
                            rec.isRecording -> "Recording (${rec.capturedSteps.size} steps captured)"
                            rec.isFloatingVisible -> "Floating control pill is active on screen"
                            else -> "Record taps, swipes, timing & IF conditions"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = NovaTextPrimary
                    )
                }
                if (!uiState.processHealth.accessibilityConnected) {
                    NovaStatusPill(
                        text = "Accessibility OFF",
                        dotColor = NovaAmber,
                        onClick = onOpenAccessibilitySettings
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (rec.isRecording) {
                    Button(
                        onClick = onStopAndSave,
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NovaEmerald,
                            contentColor = Color.Black
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("home_save_recording_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Stop,
                            contentDescription = "Save Recording",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save Recording", style = MaterialTheme.typography.labelLarge)
                    }
                } else {
                    Button(
                        onClick = onLaunchFloating,
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NovaCoralRed,
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("home_floating_routine_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.RadioButtonChecked,
                            contentDescription = "Open Floating Control",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Floating Control", style = MaterialTheme.typography.labelLarge)
                    }
                }

                OutlinedButton(
                    onClick = onNewRoutine,
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("home_new_routine_btn")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "New Routine",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Routine Studio", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun RecordedRoutineHubCard(
    routine: RecordedRoutine,
    onStart: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("recorded_routine_card_${routine.id}"),
        cornerRadius = 18.dp,
        onClick = onEdit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = routine.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = NovaTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (routine.hasConditionals()) {
                        Text(
                            text = "IF/ELSE",
                            style = MaterialTheme.typography.labelSmall,
                            color = NovaAmber,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(NovaAmber.copy(alpha = 0.16f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = routine.summaryLine(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (routine.scope == RoutineScope.PER_APP) NovaLavender else NovaCyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onStart,
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NovaEmerald,
                        contentColor = Color.Black
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("run_routine_${routine.id}")
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Start ${routine.name}",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Start", style = MaterialTheme.typography.labelMedium)
                }
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("edit_routine_${routine.id}")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "Edit ${routine.name}",
                        tint = NovaTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("delete_routine_card_${routine.id}")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Delete ${routine.name}",
                        tint = NovaCoralRed,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceGridTile(
    item: SmartControlItem,
    onToggle: () -> Unit,
    onCardClick: () -> Unit
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .height(148.dp)
            .testTag("device_card_${item.id}"),
        cornerRadius = 20.dp,
        isHighlighted = item.isEnabled,
        onClick = onCardClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (item.isEnabled) NovaViolet.copy(alpha = 0.25f) else Color(0x14FFFFFF)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = iconForControl(item.iconType),
                        contentDescription = item.title,
                        tint = if (item.isEnabled) NovaLavender else NovaTextSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                if (item.category != HubCategory.PHONE_APPS && item.category != HubCategory.ROUTINES) {
                    Switch(
                        checked = item.isEnabled,
                        onCheckedChange = { onToggle() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = NovaViolet,
                            uncheckedThumbColor = NovaTextMuted,
                            uncheckedTrackColor = Color(0x26FFFFFF)
                        ),
                        modifier = Modifier.testTag("switch_${item.id}")
                    )
                } else {
                    Text(
                        text = if (item.category == HubCategory.PHONE_APPS) "TOOL" else "RUN",
                        style = MaterialTheme.typography.labelSmall,
                        color = NovaCyan,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(NovaCyan.copy(alpha = 0.16f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Column {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = NovaTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (item.isEnabled) NovaLavender else NovaTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun AmbientStandModeView(
    uiState: NovaUiState,
    onOpenVoiceOverlay: () -> Unit,
    onExitStandMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentTime = remember { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) }
    val currentDate = remember { SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(Date()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NovaStatusPill(
                text = if (uiState.processHealth.isLlmReady) "STAND MODE • LLM READY" else "STAND MODE • REFLEX ONLY",
                dotColor = if (uiState.processHealth.isLlmReady) NovaEmerald else NovaAmber
            )
            Text(
                text = "Handheld Mode",
                style = MaterialTheme.typography.labelLarge,
                color = NovaLavender,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(onClick = onExitStandMode)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = currentTime,
                fontSize = 72.sp,
                fontWeight = FontWeight.Bold,
                color = NovaTextPrimary,
                style = MaterialTheme.typography.displayLarge
            )
            Text(
                text = currentDate,
                style = MaterialTheme.typography.titleLarge,
                color = NovaTextSecondary
            )
            Spacer(modifier = Modifier.height(24.dp))
            VoiceOrbCanvas(
                mode = OrbVisualMode.IDLE,
                amplitude = 0.25f,
                modifier = Modifier
                    .size(210.dp)
                    .clickable(onClick = onOpenVoiceOverlay)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Tap orb to speak • Local On-Device Engine",
                style = MaterialTheme.typography.bodyLarge,
                color = NovaTextSecondary
            )
        }

        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 84.dp),
            cornerRadius = 20.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (uiState.processHealth.modelPresent) {
                            "LLM: ${uiState.processHealth.activeModelName}"
                        } else {
                            "LLM: Not Installed"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = NovaTextPrimary
                    )
                    Text(
                        text = "${uiState.processHealth.indexedDocCount} local ${uiState.processHealth.ftsMode} records",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NovaTextMuted
                    )
                }
                Text(
                    text = "${uiState.processHealth.unreadMessagesCount} Alerts",
                    style = MaterialTheme.typography.labelLarge,
                    color = NovaCyan
                )
            }
        }
    }
}

private fun iconForControl(type: ControlIconType): ImageVector = when (type) {
    ControlIconType.TIMER -> Icons.Outlined.Timer
    ControlIconType.MEDIA -> Icons.Outlined.MusicNote
    ControlIconType.FLASHLIGHT -> Icons.Outlined.FlashlightOn
    ControlIconType.WIFI -> Icons.Outlined.Wifi
    ControlIconType.BLUETOOTH -> Icons.Outlined.Bluetooth
    ControlIconType.DND -> Icons.Outlined.DoNotDisturbOn
    ControlIconType.APP -> Icons.Outlined.Apps
    ControlIconType.ROUTINE -> Icons.Outlined.AutoAwesome
}
