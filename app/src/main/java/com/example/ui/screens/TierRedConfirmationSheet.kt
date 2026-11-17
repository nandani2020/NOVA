package com.example.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.remember
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.TierRedConfirmation
import com.example.ui.components.GlassCard
import com.example.ui.theme.NovaAmber
import com.example.ui.theme.NovaBgTop
import com.example.ui.theme.NovaCoralRed
import com.example.ui.theme.NovaGlassBorder
import com.example.ui.theme.NovaLavender
import com.example.ui.theme.NovaSurfaceDeep
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import com.example.ui.theme.NovaViolet

/**
 * Screen 4: Autonomous UI Transparency & Tier-Red Confirmation Bottom Sheet.
 * Displays a Picture-in-Picture live mini-view of the target app screen with a glowing
 * violet bounding box around the target Accessibility node, plus thumb-friendly Approve/Abort buttons.
 */
@Composable
fun TierRedConfirmationSheet(
    confirmation: TierRedConfirmation,
    onApprove: () -> Unit,
    onAbort: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xB3090A12)),
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(
                    Brush.verticalGradient(listOf(Color(0xFF1F1D3A), NovaSurfaceDeep, NovaBgTop))
                )
                .border(
                    width = 1.dp,
                    color = NovaAmber.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                )
                .navigationBarsPadding()
                .padding(20.dp)
                .testTag("tier_red_confirmation_sheet")
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Drag handle
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(42.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0x38FFFFFF))
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Risk Tag Banner
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(NovaAmber.copy(alpha = 0.18f))
                        .border(1.dp, NovaAmber.copy(alpha = 0.55f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Security,
                        contentDescription = null,
                        tint = NovaAmber,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = confirmation.riskTag,
                        style = MaterialTheme.typography.labelSmall,
                        color = NovaAmber
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Picture-in-Picture Live Mini-View of Target App Accessibility Nodes
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "PiP ACCESSIBILITY VIEW • ${confirmation.targetApp.uppercase()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = NovaLavender
                            )
                            Text(
                                text = "Target: [${confirmation.targetNodeId}]",
                                style = MaterialTheme.typography.labelSmall,
                                color = NovaAmber
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        val screenshotBitmap = remember(confirmation.pipScreenshotBytes) {
                            confirmation.pipScreenshotBytes?.let { bytes ->
                                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                            }
                        }
                        if (screenshotBitmap != null) {
                            Image(
                                bitmap = screenshotBitmap.asImageBitmap(),
                                contentDescription = "Live target screen preview",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(132.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(1.5.dp, NovaViolet.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                        }

                        confirmation.pipScreenNodes.forEach { node ->
                            val isTarget = node.id == confirmation.targetNodeId || node.isHighlighted
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .then(
                                        if (isTarget) {
                                            Modifier
                                                .shadow(12.dp, RoundedCornerShape(12.dp), ambientColor = NovaViolet, spotColor = NovaViolet)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(NovaViolet.copy(alpha = 0.25f))
                                                .border(2.dp, NovaViolet, RoundedCornerShape(12.dp))
                                        } else {
                                            Modifier
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(Color(0x0DFFFFFF))
                                        }
                                    )
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = node.id,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isTarget) NovaLavender else NovaTextMuted
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = node.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = NovaTextPrimary
                                    )
                                }
                                if (isTarget) {
                                    Icon(
                                        imageVector = Icons.Filled.TouchApp,
                                        contentDescription = "Target UI button highlighted",
                                        tint = NovaLavender,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = confirmation.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = NovaTextPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = confirmation.summary,
                    style = MaterialTheme.typography.bodyLarge,
                    color = NovaTextSecondary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Voice shortcut: Say \"Yes, send it\" to confirm or \"Cancel\" / Volume Down to abort.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NovaTextMuted
                )

                Spacer(modifier = Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onAbort,
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("tier_red_abort_button"),
                        border = androidx.compose.foundation.BorderStroke(1.dp, NovaCoralRed.copy(alpha = 0.65f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NovaCoralRed)
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Abort", style = MaterialTheme.typography.labelLarge)
                    }

                    Button(
                        onClick = onApprove,
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("tier_red_confirm_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NovaViolet,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Approve", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}
