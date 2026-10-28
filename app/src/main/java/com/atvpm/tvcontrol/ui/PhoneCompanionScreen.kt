package com.atvpm.tvcontrol.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atvpm.tvcontrol.ui.components.StatusPill
import com.atvpm.tvcontrol.ui.components.TvButton
import com.atvpm.tvcontrol.ui.components.TvFocusableSurface
import com.atvpm.tvcontrol.ui.theme.TvColors

@Composable
fun PhoneCompanionScreen(
    companionUrl: String?,
    qrBitmap: Bitmap?,
    onRefreshBridge: () -> Unit,
    onKillAll: () -> Unit,
    onReloadLauncher: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(TvColors.Surface)
            .border(BorderStroke(1.dp, TvColors.Divider))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left Column: QR Code & Connection Details
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = TvColors.SurfaceElevated,
            border = BorderStroke(1.dp, TvColors.Divider),
            modifier = Modifier
                .weight(0.42f)
                .fillMaxHeight(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                StatusPill(
                    text = "● WEB BRIDGE ACTIVE",
                    color = Color(0xFF10B981),
                    backgroundColor = Color(0x2210B981),
                )

                Spacer(modifier = Modifier.height(10.dp))

                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                        .padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Scan with phone",
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(
                            text = "Generating QR...",
                            color = Color.Black,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = companionUrl ?: "Detecting Wi-Fi IP...",
                    color = TvColors.FocusRing,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Scan with phone camera or enter in any web browser",
                    color = TvColors.TextMuted,
                    fontSize = 11.sp,
                )

                Spacer(modifier = Modifier.height(10.dp))

                TvButton(
                    text = "🔄 Refresh Wi-Fi IP",
                    onClick = onRefreshBridge,
                )
            }
        }

        // Right Column: Guide & Instant Actions
        Column(
            modifier = Modifier
                .weight(0.58f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "📱 Manage from Phone & PC",
                    color = TvColors.TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )

                Text(
                    text = "No more typing with a TV remote. Open the Web Bridge on your mobile browser to drag-and-drop APKs, kill sluggish background tasks, and trigger power controls wirelessly.",
                    color = TvColors.TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )

                Spacer(modifier = Modifier.height(4.dp))

                FeaturePointCard(
                    icon = "📥",
                    title = "Drag & Drop APK Sideloading",
                    description = "Upload APK files from your phone or PC directly to the TV — installs automatically without USB sticks or third-party downloader apps.",
                )

                FeaturePointCard(
                    icon = "⚡",
                    title = "1-Tap Background App Cleaner",
                    description = "Safely terminate background apps and recover TV RAM from your phone screen without interrupting playback.",
                )

                FeaturePointCard(
                    icon = "🔄",
                    title = "Remote Launcher & TV Power",
                    description = "Instantly reload frozen Android TV/Google TV home screens, put the TV to sleep, or trigger clean reboots.",
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TvButton(
                    text = "🧹 Kill Background Apps",
                    onClick = onKillAll,
                    modifier = Modifier.weight(1f),
                )
                TvButton(
                    text = "🔄 Reload Launcher",
                    onClick = onReloadLauncher,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun FeaturePointCard(
    icon: String,
    title: String,
    description: String,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = TvColors.SurfaceElevated.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, TvColors.Divider),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text = icon, fontSize = 20.sp)
            Column {
                Text(
                    text = title,
                    color = TvColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = description,
                    color = TvColors.TextMuted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
        }
    }
}
