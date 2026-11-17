package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.DoNotDisturbOn
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.ControlIconType
import com.example.data.HubCategory
import com.example.data.OrbVisualMode
import com.example.data.SmartControlItem
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

@Composable
fun HomeHubScreen(
    uiState: NovaUiState,
    onSelectCategory: (HubCategory) -> Unit,
    onToggleControl: (String) -> Unit,
    onOpenControlDetail: (SmartControlItem) -> Unit,
    onRunAppOrRoutineCommand: (String) -> Unit,
    onOpenVoiceOverlay: () -> Unit,
    onOpenSettings: () -> Unit,
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
                    Column(modifier = Modifier.weight(1f)) {
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
                        NovaStatusPill(
                            text = statusText,
                            dotColor = dotColor,
                            modifier = Modifier.testTag("local_ai_status_pill"),
                            onClick = onOpenSettings
                        )
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
