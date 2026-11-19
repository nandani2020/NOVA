package com.example.ui.screens

import android.content.Intent
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.ConditionType
import com.example.data.ElseBehavior
import com.example.data.RecordedRoutine
import com.example.data.RoutineActionType
import com.example.data.RoutineCondition
import com.example.data.RoutineScope
import com.example.data.RoutineStep
import com.example.engine.AppCapabilityRegistry
import com.example.ui.components.GlassCard
import com.example.ui.components.NovaGradientBackground
import com.example.ui.components.NovaStatusPill
import com.example.ui.theme.NovaAmber
import com.example.ui.theme.NovaCoralRed
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaEmerald
import com.example.ui.theme.NovaLavender
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import com.example.ui.theme.NovaViolet
import java.util.UUID
import kotlin.math.roundToLong

/**
 * Routine Studio Editor:
 * Customize recorded & conditional routines with Global vs Per-App scope,
 * step reordering, per-step custom delays, editable Tap/Swipe/Text/Hardware gestures,
 * IF/ELSE conditional statements, and Floating Control launch.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoutineStudioScreen(
    initialRoutine: RecordedRoutine,
    installedApps: List<AppCapabilityRegistry.InstalledAppProfile>,
    accessibilityConnected: Boolean,
    onLaunchFloatingControl: (RoutineScope, String?, String?, Boolean) -> Unit,
    onSaveRoutine: (RecordedRoutine) -> Unit,
    onTestRunRoutine: (RecordedRoutine) -> Unit,
    onDeleteRoutine: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onDismiss() }

    val context = LocalContext.current
    var draftName by remember(initialRoutine.id) { mutableStateOf(initialRoutine.name) }
    var draftDesc by remember(initialRoutine.id) { mutableStateOf(initialRoutine.description) }
    var draftScope by remember(initialRoutine.id) { mutableStateOf(initialRoutine.scope) }
    var draftPkg by remember(initialRoutine.id) { mutableStateOf(initialRoutine.targetPackage.orEmpty()) }
    var draftAppName by remember(initialRoutine.id) { mutableStateOf(initialRoutine.targetAppName.orEmpty()) }
    var steps by remember(initialRoutine.id) { mutableStateOf(initialRoutine.steps) }
    var expandedStepId by remember(initialRoutine.id) {
        mutableStateOf(initialRoutine.steps.firstOrNull()?.id)
    }
    var showDeleteRoutineConfirm by remember(initialRoutine.id) { mutableStateOf(false) }
    var showDeleteAllStepsConfirm by remember(initialRoutine.id) { mutableStateOf(false) }

    fun buildCurrentRoutine(): RecordedRoutine = initialRoutine.copy(
        name = draftName.trim().ifEmpty { "Custom Routine" },
        description = draftDesc.trim(),
        scope = draftScope,
        targetPackage = if (draftScope == RoutineScope.PER_APP) draftPkg.ifBlank { null } else null,
        targetAppName = if (draftScope == RoutineScope.PER_APP) draftAppName.ifBlank { null } else null,
        steps = steps
    )

    NovaGradientBackground(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Studio Header
            Row(
                modifier = Modifier
                    .widthIn(max = 720.dp)
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
                            .testTag("close_routine_studio_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close Routine Studio",
                            tint = NovaTextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = "ROUTINE STUDIO",
                            style = MaterialTheme.typography.labelSmall,
                            color = NovaCyan
                        )
                        Text(
                            text = "Customize & Automate",
                            style = MaterialTheme.typography.titleLarge,
                            color = NovaTextPrimary
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = { showDeleteRoutineConfirm = true },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .testTag("header_delete_routine_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Delete Routine",
                            tint = NovaCoralRed
                        )
                    }
                    Button(
                        onClick = { onSaveRoutine(buildCurrentRoutine()) },
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NovaViolet,
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("save_routine_button")
                    ) {
                        Text("Save Routine", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .widthIn(max = 720.dp)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Floating Recorder Control Banner
                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 20.dp,
                        isHighlighted = true
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                NovaStatusPill(
                                    text = if (accessibilityConnected) "ACCESSIBILITY READY" else "ACCESSIBILITY OFF",
                                    dotColor = if (accessibilityConnected) NovaEmerald else NovaAmber
                                )
                                if (!accessibilityConnected) {
                                    Text(
                                        text = "Enable Now",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = NovaAmber,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                            .clickable {
                                                runCatching {
                                                    context.startActivity(
                                                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    )
                                                }
                                            }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Floating Control & Screen Recording",
                                style = MaterialTheme.typography.titleMedium,
                                color = NovaTextPrimary
                            )
                            Text(
                                text = "Launch a draggable floating pill over any app to start your routine, capture exact taps/swipes, and insert IF conditions.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = NovaTextSecondary
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        onLaunchFloatingControl(
                                            draftScope,
                                            draftAppName.ifBlank { null },
                                            draftPkg.ifBlank { null },
                                            true
                                        )
                                    },
                                    shape = RoundedCornerShape(999.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = NovaCoralRed,
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .minimumInteractiveComponentSize()
                                        .testTag("launch_floating_recorder_btn")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.RadioButtonChecked,
                                        contentDescription = "Record with floating control",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Record Floating", style = MaterialTheme.typography.labelLarge)
                                }
                                OutlinedButton(
                                    onClick = {
                                        onLaunchFloatingControl(
                                            draftScope,
                                            draftAppName.ifBlank { null },
                                            draftPkg.ifBlank { null },
                                            false
                                        )
                                    },
                                    shape = RoundedCornerShape(999.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .minimumInteractiveComponentSize()
                                        .testTag("show_floating_control_only_btn")
                                ) {
                                    Text("Show Floating Pill", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                }

                // Name, Description & Scope (Global vs Per-App)
                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 20.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = draftName,
                                onValueChange = { draftName = it },
                                label = { Text("Routine Name (callable by voice)") },
                                singleLine = true,
                                colors = novaTextFieldColors(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("routine_name_input")
                            )
                            OutlinedTextField(
                                value = draftDesc,
                                onValueChange = { draftDesc = it },
                                label = { Text("Description / Notes") },
                                singleLine = true,
                                colors = novaTextFieldColors(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("routine_desc_input")
                            )

                            Text(
                                text = "SCOPE",
                                style = MaterialTheme.typography.labelSmall,
                                color = NovaCyan
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                RoutineScope.entries.forEach { scopeOption ->
                                    val selected = draftScope == scopeOption
                                    StudioChip(
                                        text = scopeOption.label,
                                        selected = selected,
                                        modifier = Modifier.testTag("scope_${scopeOption.name.lowercase()}_chip"),
                                        onClick = { draftScope = scopeOption }
                                    )
                                }
                            }

                            if (draftScope == RoutineScope.PER_APP) {
                                Text(
                                    text = "TARGET APP (${draftAppName.ifBlank { "Pick below" }})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NovaLavender
                                )
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(installedApps) { app ->
                                        val selected = draftPkg == app.packageName
                                        StudioChip(
                                            text = app.appName,
                                            selected = selected,
                                            onClick = {
                                                draftPkg = app.packageName
                                                draftAppName = app.appName
                                            }
                                        )
                                    }
                                }
                                OutlinedTextField(
                                    value = draftAppName,
                                    onValueChange = { draftAppName = it },
                                    label = { Text("App Name (or custom label)") },
                                    singleLine = true,
                                    colors = novaTextFieldColors(),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                // Add Step Palette
                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 20.dp
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "ADD PERSONALIZED STEP OR CONDITION",
                                style = MaterialTheme.typography.labelSmall,
                                color = NovaCyan
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                AddStepActionButton("+ Tap", Icons.Outlined.TouchApp, "add_step_tap") {
                                    val s = RoutineStep(
                                        id = "st_${UUID.randomUUID().toString().take(6)}",
                                        actionType = RoutineActionType.TAP,
                                        title = "Tap Button",
                                        x = 540f,
                                        y = 1100f,
                                        holdDurationMs = 60L,
                                        delayBeforeMs = 500L,
                                        targetLabel = "Play"
                                    )
                                    steps = steps + s
                                    expandedStepId = s.id
                                }
                                AddStepActionButton("+ Swipe", Icons.Outlined.SwapVert, "add_step_swipe") {
                                    val s = RoutineStep(
                                        id = "st_${UUID.randomUUID().toString().take(6)}",
                                        actionType = RoutineActionType.SWIPE,
                                        title = "Swipe Up (320ms)",
                                        x = 540f,
                                        y = 1550f,
                                        endX = 540f,
                                        endY = 650f,
                                        holdDurationMs = 320L,
                                        delayBeforeMs = 500L,
                                        useExactCoordinates = true
                                    )
                                    steps = steps + s
                                    expandedStepId = s.id
                                }
                                AddStepActionButton("+ IF Condition", Icons.AutoMirrored.Outlined.AltRoute, "add_step_if") {
                                    val cond = RoutineCondition(
                                        type = ConditionType.SCREEN_CONTAINS_TEXT,
                                        expectedValue = "Skip",
                                        elseBehavior = ElseBehavior.SKIP_NEXT
                                    )
                                    val s = RoutineStep(
                                        id = "st_${UUID.randomUUID().toString().take(6)}",
                                        actionType = RoutineActionType.IF_CONDITION,
                                        title = cond.describe(),
                                        delayBeforeMs = 300L,
                                        condition = cond
                                    )
                                    steps = steps + s
                                    expandedStepId = s.id
                                }
                                AddStepActionButton("+ Type Text", Icons.Filled.Edit, "add_step_type") {
                                    val s = RoutineStep(
                                        id = "st_${UUID.randomUUID().toString().take(6)}",
                                        actionType = RoutineActionType.TYPE_TEXT,
                                        title = "Type Text",
                                        delayBeforeMs = 450L,
                                        targetLabel = "Search",
                                        textValue = ""
                                    )
                                    steps = steps + s
                                    expandedStepId = s.id
                                }
                                AddStepActionButton("+ Open App", Icons.Outlined.Apps, "add_step_app") {
                                    val firstApp = installedApps.firstOrNull()
                                    val s = RoutineStep(
                                        id = "st_${UUID.randomUUID().toString().take(6)}",
                                        actionType = RoutineActionType.LAUNCH_APP,
                                        title = "Open ${firstApp?.appName ?: "App"}",
                                        delayBeforeMs = 400L,
                                        targetLabel = firstApp?.appName ?: "YouTube",
                                        targetPackage = firstApp?.packageName ?: ""
                                    )
                                    steps = steps + s
                                    expandedStepId = s.id
                                }
                            }
                        }
                    }
                }

                // Timeline Steps
                if (steps.isEmpty()) {
                    item {
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 20.dp
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "No Steps Yet",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = NovaTextPrimary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Tap 'Record Floating' to capture live taps and swipes in any app, or add custom steps and IF conditions above.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NovaTextMuted
                                )
                            }
                        }
                    }
                } else {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "ROUTINE STEPS (${steps.size})",
                                style = MaterialTheme.typography.labelSmall,
                                color = NovaCyan
                            )
                            OutlinedButton(
                                onClick = { showDeleteAllStepsConfirm = true },
                                shape = RoundedCornerShape(999.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("delete_all_steps_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = "Delete all steps",
                                    tint = NovaCoralRed,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Delete Steps", color = NovaCoralRed, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    itemsIndexed(steps, key = { _, st -> st.id }) { index, step ->
                        RoutineStepCustomizerCard(
                            index = index,
                            totalCount = steps.size,
                            step = step,
                            expanded = expandedStepId == step.id,
                            onToggleExpand = {
                                expandedStepId = if (expandedStepId == step.id) null else step.id
                            },
                            onUpdate = { updated ->
                                steps = steps.map { if (it.id == step.id) updated else it }
                            },
                            onMoveUp = {
                                if (index > 0) {
                                    val mutable = steps.toMutableList()
                                    val item = mutable.removeAt(index)
                                    mutable.add(index - 1, item)
                                    steps = mutable
                                }
                            },
                            onMoveDown = {
                                if (index < steps.lastIndex) {
                                    val mutable = steps.toMutableList()
                                    val item = mutable.removeAt(index)
                                    mutable.add(index + 1, item)
                                    steps = mutable
                                }
                            },
                            onDuplicate = {
                                val clone = step.copy(id = "st_${UUID.randomUUID().toString().take(6)}")
                                val mutable = steps.toMutableList()
                                mutable.add(index + 1, clone)
                                steps = mutable
                                expandedStepId = clone.id
                            },
                            onDelete = {
                                steps = steps.filterNot { it.id == step.id }
                            }
                        )
                    }
                }

                // Bottom Run / Delete Actions
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { onTestRunRoutine(buildCurrentRoutine()) },
                            enabled = steps.isNotEmpty(),
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NovaEmerald,
                                contentColor = Color.Black
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("test_run_routine_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "Test Run Routine",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Test Run", style = MaterialTheme.typography.labelLarge)
                        }

                        OutlinedButton(
                            onClick = { showDeleteRoutineConfirm = true },
                            shape = RoundedCornerShape(999.dp),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("delete_routine_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "Delete Routine",
                                tint = NovaCoralRed,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Delete Routine", color = NovaCoralRed, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }

        if (showDeleteRoutineConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteRoutineConfirm = false },
                title = { Text("Delete Routine?") },
                text = {
                    Text("Delete \"${draftName.trim().ifEmpty { initialRoutine.name }}\" and all of its steps? This cannot be undone.")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteRoutineConfirm = false
                            onDeleteRoutine(initialRoutine.id)
                        },
                        modifier = Modifier.testTag("confirm_delete_studio_routine_btn")
                    ) {
                        Text("Delete Routine", color = NovaCoralRed)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteRoutineConfirm = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showDeleteAllStepsConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteAllStepsConfirm = false },
                title = { Text("Delete All Steps?") },
                text = {
                    Text("Remove all ${steps.size} steps from this routine? You can tap Save Routine afterwards to persist the change.")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            steps = emptyList()
                            expandedStepId = null
                            showDeleteAllStepsConfirm = false
                        },
                        modifier = Modifier.testTag("confirm_delete_all_steps_btn")
                    ) {
                        Text("Delete All Steps", color = NovaCoralRed)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteAllStepsConfirm = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoutineStepCustomizerCard(
    index: Int,
    totalCount: Int,
    step: RoutineStep,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onUpdate: (RoutineStep) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    val accentColor = when (step.actionType) {
        RoutineActionType.IF_CONDITION -> NovaAmber
        RoutineActionType.SWIPE -> NovaCyan
        RoutineActionType.TAP -> NovaLavender
        RoutineActionType.LAUNCH_APP -> NovaEmerald
        else -> NovaTextSecondary
    }

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("routine_step_card_$index"),
        cornerRadius = 18.dp,
        isHighlighted = expanded
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(accentColor.copy(alpha = 0.2f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "#${index + 1} ${step.actionType.label.uppercase()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = accentColor,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = step.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = NovaTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onMoveUp,
                        enabled = index > 0,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowUp,
                            contentDescription = "Move step up",
                            tint = if (index > 0) NovaTextPrimary else NovaTextMuted
                        )
                    }
                    IconButton(
                        onClick = onMoveDown,
                        enabled = index < totalCount - 1,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = "Move step down",
                            tint = if (index < totalCount - 1) NovaTextPrimary else NovaTextMuted
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("delete_step_btn_$index")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Delete step ${index + 1}",
                            tint = NovaCoralRed
                        )
                    }
                }
            }

            // Always-accessible inter-step delay personalization row
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Schedule,
                        contentDescription = "Delay before step",
                        tint = NovaCyan,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "Wait ${step.delayBeforeMs} ms before step",
                        style = MaterialTheme.typography.bodySmall,
                        color = NovaTextSecondary
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DelayQuickChip("-100ms") {
                        onUpdate(step.copy(delayBeforeMs = (step.delayBeforeMs - 100L).coerceAtLeast(0L)))
                    }
                    DelayQuickChip("+100ms") {
                        onUpdate(step.copy(delayBeforeMs = (step.delayBeforeMs + 100L).coerceAtMost(30_000L)))
                    }
                    DelayQuickChip("+500ms") {
                        onUpdate(step.copy(delayBeforeMs = (step.delayBeforeMs + 500L).coerceAtMost(30_000L)))
                    }
                }
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Delay before execution: ${step.delayBeforeMs} ms",
                    style = MaterialTheme.typography.labelMedium,
                    color = NovaLavender
                )
                Slider(
                    value = step.delayBeforeMs.toFloat().coerceIn(0f, 5000f),
                    onValueChange = { raw ->
                        val snapped = ((raw / 50f).roundToLong() * 50L).coerceIn(0L, 5000L)
                        onUpdate(step.copy(delayBeforeMs = snapped))
                    },
                    valueRange = 0f..5000f,
                    colors = SliderDefaults.colors(
                        thumbColor = NovaCyan,
                        activeTrackColor = NovaCyan
                    )
                )

                OutlinedTextField(
                    value = step.title,
                    onValueChange = { onUpdate(step.copy(title = it)) },
                    label = { Text("Step Label") },
                    singleLine = true,
                    colors = novaTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))

                when (step.actionType) {
                    RoutineActionType.TAP -> {
                        OutlinedTextField(
                            value = step.targetLabel,
                            onValueChange = { onUpdate(step.copy(targetLabel = it)) },
                            label = { Text("Target Button Text (used when exact coords is OFF)") },
                            singleLine = true,
                            colors = novaTextFieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = step.x.toInt().toString(),
                                onValueChange = { v ->
                                    v.toFloatOrNull()?.let { onUpdate(step.copy(x = it)) }
                                },
                                label = { Text("Tap X") },
                                singleLine = true,
                                colors = novaTextFieldColors(),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = step.y.toInt().toString(),
                                onValueChange = { v ->
                                    v.toFloatOrNull()?.let { onUpdate(step.copy(y = it)) }
                                },
                                label = { Text("Tap Y") },
                                singleLine = true,
                                colors = novaTextFieldColors(),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Tap Hold Duration: ${step.holdDurationMs} ms",
                            style = MaterialTheme.typography.labelMedium,
                            color = NovaTextSecondary
                        )
                        Slider(
                            value = step.holdDurationMs.toFloat().coerceIn(40f, 1500f),
                            onValueChange = { raw ->
                                val dur = ((raw / 20f).roundToLong() * 20L).coerceIn(40L, 1500L)
                                onUpdate(step.copy(holdDurationMs = dur))
                            },
                            valueRange = 40f..1500f,
                            colors = SliderDefaults.colors(
                                thumbColor = NovaViolet,
                                activeTrackColor = NovaViolet
                            )
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Force exact X/Y coordinates",
                                style = MaterialTheme.typography.bodyMedium,
                                color = NovaTextPrimary
                            )
                            Switch(
                                checked = step.useExactCoordinates,
                                onCheckedChange = { onUpdate(step.copy(useExactCoordinates = it)) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = NovaViolet
                                )
                            )
                        }
                    }

                    RoutineActionType.SWIPE -> {
                        Text(
                            text = "QUICK SWIPE PRESETS",
                            style = MaterialTheme.typography.labelSmall,
                            color = NovaCyan
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            StudioChip("Swipe Up", false) {
                                onUpdate(step.copy(title = "Swipe Up", x = 540f, y = 1550f, endX = 540f, endY = 650f))
                            }
                            StudioChip("Swipe Down", false) {
                                onUpdate(step.copy(title = "Swipe Down", x = 540f, y = 650f, endX = 540f, endY = 1550f))
                            }
                            StudioChip("Swipe Left", false) {
                                onUpdate(step.copy(title = "Swipe Left", x = 900f, y = 1100f, endX = 180f, endY = 1100f))
                            }
                            StudioChip("Swipe Right", false) {
                                onUpdate(step.copy(title = "Swipe Right", x = 180f, y = 1100f, endX = 900f, endY = 1100f))
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = step.x.toInt().toString(),
                                onValueChange = { v -> v.toFloatOrNull()?.let { onUpdate(step.copy(x = it)) } },
                                label = { Text("Start X") },
                                singleLine = true,
                                colors = novaTextFieldColors(),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = step.y.toInt().toString(),
                                onValueChange = { v -> v.toFloatOrNull()?.let { onUpdate(step.copy(y = it)) } },
                                label = { Text("Start Y") },
                                singleLine = true,
                                colors = novaTextFieldColors(),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = step.endX.toInt().toString(),
                                onValueChange = { v -> v.toFloatOrNull()?.let { onUpdate(step.copy(endX = it)) } },
                                label = { Text("End X") },
                                singleLine = true,
                                colors = novaTextFieldColors(),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = step.endY.toInt().toString(),
                                onValueChange = { v -> v.toFloatOrNull()?.let { onUpdate(step.copy(endY = it)) } },
                                label = { Text("End Y") },
                                singleLine = true,
                                colors = novaTextFieldColors(),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Swipe Duration (speed): ${step.holdDurationMs} ms",
                            style = MaterialTheme.typography.labelMedium,
                            color = NovaTextSecondary
                        )
                        Slider(
                            value = step.holdDurationMs.toFloat().coerceIn(120f, 1600f),
                            onValueChange = { raw ->
                                val dur = ((raw / 20f).roundToLong() * 20L).coerceIn(120L, 1600L)
                                onUpdate(step.copy(holdDurationMs = dur))
                            },
                            valueRange = 120f..1600f,
                            colors = SliderDefaults.colors(
                                thumbColor = NovaCyan,
                                activeTrackColor = NovaCyan
                            )
                        )
                    }

                    RoutineActionType.TYPE_TEXT -> {
                        OutlinedTextField(
                            value = step.targetLabel,
                            onValueChange = { onUpdate(step.copy(targetLabel = it)) },
                            label = { Text("Target Input Field Label") },
                            singleLine = true,
                            colors = novaTextFieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = step.textValue,
                            onValueChange = { onUpdate(step.copy(textValue = it)) },
                            label = { Text("Text to Type") },
                            singleLine = true,
                            colors = novaTextFieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    RoutineActionType.LAUNCH_APP -> {
                        OutlinedTextField(
                            value = step.targetLabel,
                            onValueChange = { onUpdate(step.copy(targetLabel = it)) },
                            label = { Text("App Name (e.g. YouTube Music, Spotify)") },
                            singleLine = true,
                            colors = novaTextFieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    RoutineActionType.HARDWARE_TOGGLE -> {
                        OutlinedTextField(
                            value = step.targetLabel,
                            onValueChange = { onUpdate(step.copy(targetLabel = it)) },
                            label = { Text("Feature (flashlight, wifi, mute)") },
                            singleLine = true,
                            colors = novaTextFieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StudioChip("ON", step.textValue != "off") {
                                onUpdate(step.copy(textValue = "on"))
                            }
                            StudioChip("OFF", step.textValue == "off") {
                                onUpdate(step.copy(textValue = "off"))
                            }
                        }
                    }

                    RoutineActionType.GLOBAL_ACTION -> {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("HOME", "BACK", "RECENTS", "NOTIFICATIONS", "QUICK_SETTINGS", "SCREENSHOT").forEach { act ->
                                StudioChip(act, step.textValue.equals(act, ignoreCase = true)) {
                                    onUpdate(step.copy(textValue = act, title = "OS $act"))
                                }
                            }
                        }
                    }

                    RoutineActionType.IF_CONDITION -> {
                        // Condition builder rendered immediately below
                    }
                }

                // Conditional Statement Builder (for IF_CONDITION or optional guard on any step)
                val activeCond = step.condition
                if (step.actionType != RoutineActionType.IF_CONDITION) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Guard step with IF condition",
                            style = MaterialTheme.typography.bodyMedium,
                            color = NovaAmber
                        )
                        Switch(
                            checked = activeCond != null,
                            onCheckedChange = { enabled ->
                                val nextCond = if (enabled) {
                                    RoutineCondition(ConditionType.SCREEN_CONTAINS_TEXT, "Skip", "", ElseBehavior.CONTINUE)
                                } else null
                                onUpdate(step.copy(condition = nextCond))
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = NovaAmber
                            )
                        )
                    }
                }

                val editableCond = activeCond ?: if (step.actionType == RoutineActionType.IF_CONDITION) {
                    RoutineCondition(ConditionType.SCREEN_CONTAINS_TEXT, "Skip", "", ElseBehavior.SKIP_NEXT)
                } else null

                if (editableCond != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    ConditionEditorBlock(
                        condition = editableCond,
                        onConditionChanged = { next ->
                            val nextTitle = if (step.actionType == RoutineActionType.IF_CONDITION) {
                                next.describe()
                            } else step.title
                            onUpdate(step.copy(condition = next, title = nextTitle))
                        }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDuplicate,
                        shape = RoundedCornerShape(999.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = "Duplicate step",
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Duplicate", style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = onDelete,
                        shape = RoundedCornerShape(999.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("expanded_delete_step_btn_$index")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Delete step ${index + 1}",
                            tint = NovaCoralRed,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Delete Step", color = NovaCoralRed, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConditionEditorBlock(
    condition: RoutineCondition,
    onConditionChanged: (RoutineCondition) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x18F59E0B))
            .border(1.dp, NovaAmber.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "IF CONDITION",
            style = MaterialTheme.typography.labelSmall,
            color = NovaAmber
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ConditionType.entries.forEach { cType ->
                StudioChip(
                    text = cType.label,
                    selected = condition.type == cType,
                    onClick = { onConditionChanged(condition.copy(type = cType)) }
                )
            }
        }

        if (condition.type.needsExpectedValue) {
            OutlinedTextField(
                value = condition.expectedValue,
                onValueChange = { onConditionChanged(condition.copy(expectedValue = it)) },
                label = {
                    Text(
                        when (condition.type) {
                            ConditionType.TIME_BETWEEN -> "Start Time (HH:mm)"
                            ConditionType.BATTERY_AT_LEAST,
                            ConditionType.BATTERY_BELOW -> "Battery % (e.g. 20)"
                            ConditionType.APP_IN_FOREGROUND -> "Package or App Name"
                            else -> "Expected Text / Value"
                        }
                    )
                },
                singleLine = true,
                colors = novaTextFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            if (condition.type == ConditionType.TIME_BETWEEN) {
                OutlinedTextField(
                    value = condition.secondValue,
                    onValueChange = { onConditionChanged(condition.copy(secondValue = it)) },
                    label = { Text("End Time (HH:mm)") },
                    singleLine = true,
                    colors = novaTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Text(
            text = "ELSE (WHEN FALSE)",
            style = MaterialTheme.typography.labelSmall,
            color = NovaAmber
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ElseBehavior.entries.forEach { beh ->
                StudioChip(
                    text = beh.label,
                    selected = condition.elseBehavior == beh,
                    onClick = { onConditionChanged(condition.copy(elseBehavior = beh)) }
                )
            }
        }
    }
}

@Composable
private fun AddStepActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0x1E8B5CF6))
            .border(1.dp, NovaGlassBorderBrightColor, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = NovaLavender,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = NovaTextPrimary)
    }
}

private val NovaGlassBorderBrightColor = Color(0x44A78BFA)

@Composable
private fun DelayQuickChip(
    label: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0x1E38BDF8))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = NovaCyan)
    }
}

@Composable
private fun StudioChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) NovaViolet else Color(0x16FFFFFF))
            .border(
                1.dp,
                if (selected) NovaLavender else Color(0x28FFFFFF),
                RoundedCornerShape(999.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Color.White else NovaTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun novaTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = NovaTextPrimary,
    unfocusedTextColor = NovaTextPrimary,
    focusedBorderColor = NovaViolet,
    unfocusedBorderColor = Color(0x33FFFFFF),
    focusedLabelColor = NovaLavender,
    unfocusedLabelColor = NovaTextMuted,
    cursorColor = NovaCyan
)
