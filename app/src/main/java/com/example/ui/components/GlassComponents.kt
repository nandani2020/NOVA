package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.NovaBgBottom
import com.example.ui.theme.NovaBgTop
import com.example.ui.theme.NovaEmerald
import com.example.ui.theme.NovaGlassBorder
import com.example.ui.theme.NovaGlassBorderBright
import com.example.ui.theme.NovaGlassFill
import com.example.ui.theme.NovaLavender
import com.example.ui.theme.NovaMagenta
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaViolet

@Composable
fun NovaGradientBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(NovaBgTop, Color(0xFF191930), NovaBgBottom)
                )
            )
            .drawBehind {
                // Subtle ambient violet glow behind top header
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x288B5CF6), Color.Transparent),
                        center = Offset(size.width * 0.8f, size.height * 0.12f),
                        radius = size.width * 0.65f
                    ),
                    center = Offset(size.width * 0.8f, size.height * 0.12f),
                    radius = size.width * 0.65f
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x1AEC4899), Color.Transparent),
                        center = Offset(size.width * 0.15f, size.height * 0.72f),
                        radius = size.width * 0.55f
                    ),
                    center = Offset(size.width * 0.15f, size.height * 0.72f),
                    radius = size.width * 0.55f
                )
            },
        content = content
    )
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    isHighlighted: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    val borderColor by animateColorAsState(
        targetValue = if (isHighlighted) NovaGlassBorderBright else NovaGlassBorder,
        label = "glass_border"
    )
    val fillBrush = if (isHighlighted) {
        Brush.linearGradient(
            listOf(Color(0x268B5CF6), Color(0x14EC4899))
        )
    } else {
        Brush.linearGradient(
            listOf(Color(0x14FFFFFF), NovaGlassFill)
        )
    }

    val clickableModifier = if (onClick != null) {
        Modifier
            .clip(shape)
            .clickable(onClick = onClick)
    } else {
        Modifier.clip(shape)
    }

    Box(
        modifier = modifier
            .then(clickableModifier)
            .background(brush = fillBrush, shape = shape)
            .border(width = 1.dp, color = borderColor, shape = shape),
        content = content
    )
}

@Composable
fun NovaStatusPill(
    text: String,
    dotColor: Color = NovaEmerald,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(999.dp)
    Surface(
        modifier = modifier
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = shape,
        color = Color(0x1AFFFFFF),
        border = BorderStroke(1.dp, Color(0x28FFFFFF))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = NovaTextPrimary
            )
        }
    }
}

enum class DockDestination { HUB, CONTROLS, SETTINGS }

@Composable
fun NovaFloatingBottomBar(
    currentDestination: DockDestination,
    ambientStandMode: Boolean,
    onNavigateHub: () -> Unit,
    onNavigateControls: () -> Unit,
    onOpenVoiceOverlay: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleStandMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        // Frosted dock bar
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .height(66.dp),
            cornerRadius = 999.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                DockNavItem(
                    icon = if (currentDestination == DockDestination.HUB) Icons.Filled.Home else Icons.Outlined.Home,
                    label = "Hub",
                    selected = currentDestination == DockDestination.HUB,
                    tag = "nav_hub",
                    onClick = onNavigateHub
                )
                DockNavItem(
                    icon = if (currentDestination == DockDestination.CONTROLS) Icons.Filled.Tune else Icons.Outlined.Tune,
                    label = "Dial",
                    selected = currentDestination == DockDestination.CONTROLS,
                    tag = "nav_controls",
                    onClick = onNavigateControls
                )

                // Spacer for floating center mic button
                Spacer(modifier = Modifier.width(74.dp))

                DockNavItem(
                    icon = if (currentDestination == DockDestination.SETTINGS) Icons.Filled.Settings else Icons.Outlined.Settings,
                    label = "Settings",
                    selected = currentDestination == DockDestination.SETTINGS,
                    tag = "nav_settings",
                    onClick = onOpenSettings
                )
                DockNavItem(
                    icon = Icons.Outlined.Bedtime,
                    label = if (ambientStandMode) "Stand" else "Desk",
                    selected = ambientStandMode,
                    tag = "nav_stand",
                    onClick = onToggleStandMode
                )
            }
        }

        // Floating glowing center microphone trigger
        Box(
            modifier = Modifier
                .offset(y = (-14).dp)
                .size(66.dp)
                .shadow(
                    elevation = 20.dp,
                    shape = CircleShape,
                    ambientColor = NovaViolet,
                    spotColor = NovaMagenta
                )
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(listOf(NovaViolet, NovaMagenta))
                )
                .border(2.dp, Color(0x55FFFFFF), CircleShape)
                .clickable(onClick = onOpenVoiceOverlay)
                .testTag("floating_mic_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = "Activate Nova Voice Assistant",
                tint = Color.White,
                modifier = Modifier.size(30.dp)
            )
        }
    }
}

@Composable
private fun DockNavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit
) {
    val tint = if (selected) NovaLavender else NovaTextMuted
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .testTag(tag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
    }
}
