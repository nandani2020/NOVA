package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.SmartControlItem
import com.example.ui.theme.NovaGlassBorder
import com.example.ui.theme.NovaGlassFill
import com.example.ui.theme.NovaLavender
import com.example.ui.theme.NovaMagenta
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import com.example.ui.theme.NovaViolet
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Screen 3: Precision Control & Detail View (Smart Controls / Timers / Media).
 */
@Composable
fun RadialControlScreen(
    control: SmartControlItem,
    onBack: () -> Unit,
    onToggleMaster: () -> Unit,
    onValueChange: (Float) -> Unit,
    onSelectMode: (String) -> Unit,
    onSelectIntensity: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .padding(bottom = 110.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(NovaGlassFill)
                        .testTag("radial_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to Hub",
                        tint = NovaTextPrimary
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = control.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = NovaTextPrimary
                    )
                    Text(
                        text = control.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NovaLavender
                    )
                }

                Switch(
                    checked = control.isEnabled,
                    onCheckedChange = { onToggleMaster() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = NovaViolet,
                        uncheckedThumbColor = NovaTextMuted,
                        uncheckedTrackColor = Color(0x26FFFFFF)
                    ),
                    modifier = Modifier.testTag("radial_master_switch")
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Central Interactive Radial Dial
            val progress = ((control.primaryValue - control.minValue) /
                (control.maxValue - control.minValue).coerceAtLeast(1f)).coerceIn(0f, 1f)

            Box(
                modifier = Modifier
                    .size(270.dp)
                    .pointerInput(control.id, control.minValue, control.maxValue) {
                        detectDragGestures { change, _ ->
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val angleRad = atan2(change.position.y - cy, change.position.x - cx)
                            var deg = Math.toDegrees(angleRad.toDouble()).toFloat()
                            // Sweep starts at 135 deg, spans 270 deg clockwise
                            if (deg < 0f) deg += 360f
                            val adjusted = (deg - 135f + 360f) % 360f
                            if (adjusted <= 270f) {
                                val frac = (adjusted / 270f).coerceIn(0f, 1f)
                                val raw = control.minValue + frac * (control.maxValue - control.minValue)
                                val snapped = (raw / control.stepSize).roundToInt() * control.stepSize
                                onValueChange(snapped)
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeW = 18.dp.toPx()
                    val pad = 26.dp.toPx()
                    val arcSize = Size(size.width - pad * 2, size.height - pad * 2)
                    val topLeft = Offset(pad, pad)
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val radius = arcSize.width / 2f

                    // Tick marks
                    val tickCount = 30
                    for (i in 0..tickCount) {
                        val tDeg = 135f + (i.toFloat() / tickCount) * 270f
                        val tRad = Math.toRadians(tDeg.toDouble())
                        val outerR = radius + 16.dp.toPx()
                        val innerR = radius + 9.dp.toPx()
                        val isLit = (i.toFloat() / tickCount) <= progress
                        drawLine(
                            color = if (isLit) NovaLavender else Color(0x28FFFFFF),
                            start = Offset(
                                cx + (innerR * cos(tRad)).toFloat(),
                                cy + (innerR * sin(tRad)).toFloat()
                            ),
                            end = Offset(
                                cx + (outerR * cos(tRad)).toFloat(),
                                cy + (outerR * sin(tRad)).toFloat()
                            ),
                            strokeWidth = if (i % 5 == 0) 2.5.dp.toPx() else 1.2.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }

                    // Background track arc
                    drawArc(
                        color = Color(0x1EFFFFFF),
                        startAngle = 135f,
                        sweepAngle = 270f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeW, cap = StrokeCap.Round)
                    )

                    // Glowing white-to-violet active progress arc
                    val sweep = (270f * progress).coerceAtLeast(4f)
                    drawArc(
                        brush = Brush.sweepGradient(
                            colors = listOf(Color.White, NovaLavender, NovaViolet, NovaMagenta),
                            center = Offset(cx, cy)
                        ),
                        startAngle = 135f,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeW, cap = StrokeCap.Round)
                    )

                    // Thumb knob on arc tip
                    val tipRad = Math.toRadians((135f + sweep).toDouble())
                    val tipX = cx + (radius * cos(tipRad)).toFloat()
                    val tipY = cy + (radius * sin(tipRad)).toFloat()
                    drawCircle(
                        color = Color.White,
                        radius = 10.dp.toPx(),
                        center = Offset(tipX, tipY)
                    )
                }

                // Center bold tabular readout + step controls
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val readoutText = if (control.unit == "min") {
                        "${control.primaryValue.toInt()}:00"
                    } else {
                        "${control.primaryValue.toInt()}${control.unit}"
                    }
                    Text(
                        text = readoutText,
                        style = MaterialTheme.typography.displayLarge,
                        color = NovaTextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        IconButton(
                            onClick = { onValueChange(control.primaryValue - control.stepSize) },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color(0x1EFFFFFF))
                                .testTag("dial_decrement_button")
                        ) {
                            Icon(Icons.Filled.Remove, contentDescription = "Decrease", tint = Color.White)
                        }
                        IconButton(
                            onClick = { onValueChange(control.primaryValue + control.stepSize) },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(NovaViolet)
                                .testTag("dial_increment_button")
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "Increase", tint = Color.White)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(26.dp))

            // Mode Pill Selector
            if (control.modes.isNotEmpty()) {
                Text(
                    text = "MODE",
                    style = MaterialTheme.typography.labelSmall,
                    color = NovaTextMuted,
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0x12FFFFFF))
                        .border(1.dp, NovaGlassBorder, RoundedCornerShape(999.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    control.modes.forEach { mode ->
                        val active = mode == control.selectedMode
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (active) NovaViolet else Color.Transparent)
                                .clickable { onSelectMode(mode) }
                                .padding(vertical = 10.dp)
                                .testTag("mode_pill_${mode.lowercase()}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = mode,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (active) Color.White else NovaTextSecondary,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 3-Column Intensity / Preset Cards
            Text(
                text = "INTENSITY PRESETS",
                style = MaterialTheme.typography.labelSmall,
                color = NovaTextMuted,
                modifier = Modifier.align(Alignment.Start)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                control.intensities.forEach { preset ->
                    val active = preset == control.selectedIntensity
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp)
                            .then(
                                if (active) Modifier.shadow(
                                    elevation = 16.dp,
                                    shape = RoundedCornerShape(20.dp),
                                    ambientColor = NovaViolet,
                                    spotColor = NovaViolet
                                ) else Modifier
                            )
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (active) NovaViolet else NovaGlassFill)
                            .border(
                                1.dp,
                                if (active) NovaLavender else NovaGlassBorder,
                                RoundedCornerShape(20.dp)
                            )
                            .clickable { onSelectIntensity(preset) }
                            .testTag("intensity_card_${preset.lowercase()}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = preset,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (active) Color.White else NovaTextSecondary
                        )
                    }
                }
            }
        }
    }
}
