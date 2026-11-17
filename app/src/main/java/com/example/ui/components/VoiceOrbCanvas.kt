package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.data.OrbVisualMode
import com.example.ui.theme.NovaAmber
import com.example.ui.theme.NovaCoralRed
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaEmerald
import com.example.ui.theme.NovaLavender
import com.example.ui.theme.NovaMagenta
import com.example.ui.theme.NovaViolet
import kotlin.math.cos
import kotlin.math.sin

/**
 * Luminous 3D-inspired fluid purple-pink-indigo petal/wave orb.
 * Adapts visually to processing tier:
 * - IDLE / LISTENING: Gentle pulsing violet-pink harmonic waves
 * - FAST_PATH_FLASH: Electric violet-emerald flash + checkmark
 * - THINKING: Deep indigo-violet swirling petals
 * - UI_NAVIGATING: Orbiting cyan/violet light ring
 * - CONFIRMATION_PAUSED: Amber warning halo
 */
@Composable
fun VoiceOrbCanvas(
    mode: OrbVisualMode,
    amplitude: Float,
    modifier: Modifier = Modifier
) {
    val infinite = rememberInfiniteTransition(label = "nova_orb")
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orb_phase"
    )
    val pulse by infinite.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb_pulse"
    )

    val primaryColor = when (mode) {
        OrbVisualMode.FAST_PATH_FLASH -> NovaEmerald
        OrbVisualMode.CONFIRMATION_PAUSED -> NovaAmber
        OrbVisualMode.ERROR_ABORTED -> NovaCoralRed
        OrbVisualMode.UI_NAVIGATING -> NovaCyan
        else -> NovaViolet
    }
    val secondaryColor = when (mode) {
        OrbVisualMode.FAST_PATH_FLASH -> NovaViolet
        OrbVisualMode.CONFIRMATION_PAUSED -> NovaCoralRed
        OrbVisualMode.UI_NAVIGATING -> NovaViolet
        else -> NovaMagenta
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val baseRadius = size.minDimension * 0.34f * pulse * (0.9f + amplitude * 0.22f)

            // Outer ambient nebula bloom
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.42f),
                        secondaryColor.copy(alpha = 0.16f),
                        Color.Transparent
                    ),
                    center = Offset(cx, cy),
                    radius = baseRadius * 1.9f
                ),
                center = Offset(cx, cy),
                radius = baseRadius * 1.9f
            )

            // 3 layered harmonic petal ribbons
            for (layer in 0..2) {
                val layerPath = Path()
                val steps = 72
                val layerScale = 1f - layer * 0.12f
                val layerOffset = phase * (if (layer % 2 == 0) 1f else -1.3f) + layer * 1.1f
                val waveAmp = baseRadius * (0.14f + amplitude * 0.12f)

                for (i in 0..steps) {
                    val theta = (i.toFloat() / steps) * (2f * Math.PI.toFloat())
                    val harmonic = sin(theta * (3 + layer) + layerOffset) * waveAmp +
                        cos(theta * 2f - layerOffset) * (waveAmp * 0.5f)
                    val r = baseRadius * layerScale + harmonic
                    val x = cx + r * cos(theta)
                    val y = cy + r * sin(theta)
                    if (i == 0) layerPath.moveTo(x, y) else layerPath.lineTo(x, y)
                }
                layerPath.close()

                val layerBrush = Brush.sweepGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.55f - layer * 0.1f),
                        secondaryColor.copy(alpha = 0.65f - layer * 0.1f),
                        NovaLavender.copy(alpha = 0.50f),
                        primaryColor.copy(alpha = 0.55f - layer * 0.1f)
                    ),
                    center = Offset(cx, cy)
                )
                drawPath(path = layerPath, brush = layerBrush)
                drawPath(
                    path = layerPath,
                    color = Color.White.copy(alpha = 0.24f),
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }

            // Inner glowing core sphere
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.92f),
                        NovaLavender.copy(alpha = 0.75f),
                        primaryColor.copy(alpha = 0.25f),
                        Color.Transparent
                    ),
                    center = Offset(cx - baseRadius * 0.15f, cy - baseRadius * 0.15f),
                    radius = baseRadius * 0.68f
                ),
                center = Offset(cx, cy),
                radius = baseRadius * 0.58f
            )

            // Orbiting scanner ring when autonomously navigating third-party UI
            if (mode == OrbVisualMode.UI_NAVIGATING || mode == OrbVisualMode.THINKING) {
                val ringRadius = baseRadius * 1.28f
                val startDeg = Math.toDegrees(phase.toDouble()).toFloat()
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(Color.Transparent, NovaCyan, NovaMagenta, Color.Transparent),
                        center = Offset(cx, cy)
                    ),
                    startAngle = startDeg,
                    sweepAngle = 220f,
                    useCenter = false,
                    topLeft = Offset(cx - ringRadius, cy - ringRadius),
                    size = androidx.compose.ui.geometry.Size(ringRadius * 2, ringRadius * 2),
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        if (mode == OrbVisualMode.FAST_PATH_FLASH) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Instant reflex executed",
                tint = Color.White,
                modifier = Modifier.size(34.dp)
            )
        } else if (mode == OrbVisualMode.CONFIRMATION_PAUSED) {
            Icon(
                imageVector = Icons.Filled.WarningAmber,
                contentDescription = "Confirmation required",
                tint = Color.White,
                modifier = Modifier.size(34.dp)
            )
        }
    }
}
