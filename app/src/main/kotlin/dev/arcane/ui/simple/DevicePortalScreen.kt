package dev.arcane.ui.simple

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.arcane.data.AppGraph
import dev.arcane.portal.DevicePortalServer
import dev.arcane.portal.DevicePortalService
import dev.arcane.ui.components.ArcaneAction
import dev.arcane.ui.components.ArcaneButton
import dev.arcane.ui.theme.ArcanePalette
import dev.arcane.ui.theme.ArcanePill
import dev.arcane.ui.theme.ArcaneRadius
import dev.arcane.ui.theme.ArcaneSpace
import dev.arcane.ui.util.formatBytes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Dedicated Control & Stats Screen for Phone Web Portal:
 *
 * Provides real-time server controls, live connection URL,
 * background & screen-lock persistence telemetry, data transfer metrics,
 * and live connection activity logs.
 *
 * Strict Isolation: Operates solely on standard Android MediaStore items.
 * Completely decoupled from the encrypted vault.
 */
@Composable
fun DevicePortalScreen(
    graph: AppGraph,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val portalStatus by graph.devicePortal.status.collectAsStateWithLifecycle()
    val portalStats by graph.devicePortal.stats.collectAsStateWithLifecycle()

    // Live tick for uptime counter
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(portalStatus.isRunning) {
        while (portalStatus.isRunning) {
            delay(1000L)
            currentTime = System.currentTimeMillis()
        }
    }

    // Permission launcher for POST_NOTIFICATIONS on Android 13+
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { _ ->
        // Start service regardless; user will receive notification if granted
        DevicePortalService.start(context, port = 8080)
    }

    val toggleServer: () -> Unit = {
        if (portalStatus.isRunning) {
            DevicePortalService.stop(context)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                DevicePortalService.start(context, port = 8080)
            }
        }
    }

    BackHandler(onBack = onClose)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ArcanePalette.Ink0),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            // Top App Bar
            PortalTopBar(
                isRunning = portalStatus.isRunning,
                port = portalStatus.port,
                onClose = onClose,
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = ArcaneSpace.gutter),
                verticalArrangement = Arrangement.spacedBy(ArcaneSpace.md),
            ) {
                // Hero Server Power & Status Card
                item(key = "hero_power") {
                    HeroPowerCard(
                        isRunning = portalStatus.isRunning,
                        onToggle = toggleServer,
                    )
                }

                // Connection Credentials Card (Active Server Only)
                if (portalStatus.isRunning) {
                    item(key = "credentials") {
                        CredentialsCard(
                            url = portalStatus.url,
                            onCopyUrl = {
                                clipboardManager.setText(AnnotatedString(portalStatus.url))
                                Toast.makeText(context, "URL copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            onOpenBrowser = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(portalStatus.url))
                                context.startActivity(intent)
                            },
                        )
                    }

                    // Live Telemetry Dashboard Grid
                    item(key = "stats_grid") {
                        TelemetryDashboardGrid(
                            stats = portalStats,
                            uptime = formatUptime(portalStats.startedAtMillis, currentTime),
                        )
                    }

                    // Live Activity Event Stream
                    item(key = "live_events") {
                        LiveActivityCard(
                            events = portalStats.recentEvents,
                            onClear = { graph.devicePortal.clearEvents() },
                        )
                    }
                } else {
                    item(key = "config_card") {
                        OfflineConfigCard(
                            localIp = graph.devicePortal.currentLocalIp(),
                        )
                    }
                }

                item(key = "bottom_spacer") {
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// TOP BAR
// -------------------------------------------------------------------------------------------------

@Composable
private fun PortalTopBar(
    isRunning: Boolean,
    port: Int,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ArcaneSpace.gutter, vertical = ArcaneSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.xs),
        ) {
            ArcaneAction(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                hint = "Back to file browser",
                onClick = onClose,
                target = 48.dp,
            )
            Column {
                Text(
                    text = "Phone Web Portal",
                    style = MaterialTheme.typography.titleMedium,
                    color = ArcanePalette.TextHigh,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Local Wi-Fi File Sharing",
                    style = MaterialTheme.typography.labelSmall,
                    color = ArcanePalette.TextFaint,
                )
            }
        }

        // Live Status Badge
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(100.dp))
                .background(if (isRunning) ArcanePalette.Mint.copy(alpha = 0.15f) else ArcanePalette.Ink2)
                .border(
                    width = 1.dp,
                    color = if (isRunning) ArcanePalette.Mint.copy(alpha = 0.4f) else ArcanePalette.Edge,
                    shape = RoundedCornerShape(100.dp),
                )
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (isRunning) {
                PulsingDot(color = ArcanePalette.Mint)
                Text(
                    text = "LIVE :$port",
                    style = MaterialTheme.typography.labelSmall,
                    color = ArcanePalette.Mint,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(ArcanePalette.TextFaint),
                )
                Text(
                    text = "OFFLINE",
                    style = MaterialTheme.typography.labelSmall,
                    color = ArcanePalette.TextFaint,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.5.sp,
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// HERO POWER CARD
// -------------------------------------------------------------------------------------------------

@Composable
private fun HeroPowerCard(
    isRunning: Boolean,
    onToggle: () -> Unit,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_scale",
    )

    val shape = RoundedCornerShape(ArcaneRadius.card)
    val bgBrush = if (isRunning) {
        Brush.verticalGradient(
            colors = listOf(
                ArcanePalette.Mint.copy(alpha = 0.12f),
                ArcanePalette.Ink1,
            ),
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                ArcanePalette.Ink2,
                ArcanePalette.Ink1,
            ),
        )
    }

    val borderColor = if (isRunning) ArcanePalette.Mint.copy(alpha = 0.4f) else ArcanePalette.Edge

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bgBrush)
            .border(1.dp, borderColor, shape)
            .padding(ArcaneSpace.md),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ArcaneSpace.sm),
        ) {
            // Power Button with glowing halo
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(72.dp)
                    .scale(if (isRunning) pulseScale else 1f)
                    .clip(CircleShape)
                    .background(
                        if (isRunning) {
                            ArcanePalette.Mint.copy(alpha = 0.2f)
                        } else {
                            ArcanePalette.Rose.copy(alpha = 0.15f)
                        },
                    )
                    .border(
                        width = 2.dp,
                        color = if (isRunning) ArcanePalette.Mint else ArcanePalette.Rose.copy(alpha = 0.6f),
                        shape = CircleShape,
                    )
                    .clickable(onClick = onToggle),
            ) {
                Icon(
                    imageVector = Icons.Rounded.PowerSettingsNew,
                    contentDescription = if (isRunning) "Stop Server" else "Start Server",
                    tint = if (isRunning) ArcanePalette.Mint else ArcanePalette.Rose,
                    modifier = Modifier.size(36.dp),
                )
            }

            Text(
                text = if (isRunning) "Phone Web Server" else "Phone Web Server Offline",
                style = MaterialTheme.typography.titleMedium,
                color = ArcanePalette.TextHigh,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )

            Text(
                text = if (isRunning) "Active on local Wi-Fi" else "Tap power button to start",
                style = MaterialTheme.typography.bodySmall,
                color = ArcanePalette.TextMedium,
                textAlign = TextAlign.Center,
            )

            ArcaneButton(
                label = if (isRunning) "Stop Server" else "Start Phone Server",
                onClick = onToggle,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = ArcaneSpace.xs),
            )
        }
    }
}

// -------------------------------------------------------------------------------------------------
// CREDENTIALS CARD
// -------------------------------------------------------------------------------------------------

@Composable
private fun CredentialsCard(
    url: String,
    onCopyUrl: () -> Unit,
    onOpenBrowser: () -> Unit,
) {
    val shape = RoundedCornerShape(ArcaneRadius.card)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ArcanePalette.Ink1)
            .border(1.dp, ArcanePalette.Edge, shape)
            .padding(ArcaneSpace.md),
        verticalArrangement = Arrangement.spacedBy(ArcaneSpace.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Laptop,
                    contentDescription = null,
                    tint = ArcanePalette.Mint,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "PC Connection Access",
                    style = MaterialTheme.typography.titleSmall,
                    color = ArcanePalette.TextHigh,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(100.dp))
                    .background(ArcanePalette.Mint.copy(alpha = 0.15f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Wifi,
                    contentDescription = null,
                    tint = ArcanePalette.Mint,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = "Open Wi-Fi",
                    style = MaterialTheme.typography.labelSmall,
                    color = ArcanePalette.Mint,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        HorizontalDivider(color = ArcanePalette.Edge)

        // URL display box
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "PORTAL WEB ADDRESS",
                style = MaterialTheme.typography.labelSmall,
                color = ArcanePalette.TextFaint,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(ArcaneRadius.field))
                    .background(ArcanePalette.Ink2)
                    .border(1.dp, ArcanePalette.Edge, RoundedCornerShape(ArcaneRadius.field))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = ArcanePalette.Mint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ArcaneAction(
                        icon = Icons.Rounded.ContentCopy,
                        hint = "Copy URL",
                        onClick = onCopyUrl,
                        target = 36.dp,
                    )
                    ArcaneAction(
                        icon = Icons.Rounded.OpenInBrowser,
                        hint = "Open in browser",
                        onClick = onOpenBrowser,
                        target = 36.dp,
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// TELEMETRY DASHBOARD GRID
// -------------------------------------------------------------------------------------------------

@Composable
private fun TelemetryDashboardGrid(
    stats: DevicePortalServer.PortalStats,
    uptime: String,
) {
    val shape = RoundedCornerShape(ArcaneRadius.card)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ArcanePalette.Ink1)
            .border(1.dp, ArcanePalette.Edge, shape)
            .padding(ArcaneSpace.md),
        verticalArrangement = Arrangement.spacedBy(ArcaneSpace.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Live Telemetry",
                style = MaterialTheme.typography.titleSmall,
                color = ArcanePalette.TextHigh,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Uptime: $uptime",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = ArcanePalette.Mint,
            )
        }

        HorizontalDivider(color = ArcanePalette.Edge)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.sm),
        ) {
            MetricBox(
                icon = Icons.Rounded.Laptop,
                label = "Active Clients",
                value = "${stats.activeClients}",
                modifier = Modifier.weight(1f),
            )
            MetricBox(
                icon = Icons.Rounded.PlayCircle,
                label = "Streamed",
                value = formatBytes(stats.bytesStreamed),
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.sm),
        ) {
            MetricBox(
                icon = Icons.Rounded.CloudUpload,
                label = "Uploaded to Phone",
                value = "${stats.filesUploaded} files",
                subValue = formatBytes(stats.bytesUploaded),
                modifier = Modifier.weight(1f),
            )
            MetricBox(
                icon = Icons.Rounded.Refresh,
                label = "HTTP Requests",
                value = "${stats.totalRequests}",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MetricBox(
    icon: ImageVector,
    label: String,
    value: String,
    subValue: String? = null,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(ArcaneRadius.field)
    Column(
        modifier = modifier
            .clip(shape)
            .background(ArcanePalette.Ink2)
            .border(1.dp, ArcanePalette.Edge, shape)
            .padding(ArcaneSpace.sm),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = ArcanePalette.Mint,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = ArcanePalette.TextFaint,
                maxLines = 1,
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            ),
            color = ArcanePalette.TextHigh,
        )
        if (subValue != null) {
            Text(
                text = subValue,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = ArcanePalette.TextMedium,
            )
        }
    }
}

// -------------------------------------------------------------------------------------------------
// LIVE ACTIVITY CARD
// -------------------------------------------------------------------------------------------------

@Composable
private fun LiveActivityCard(
    events: List<DevicePortalServer.PortalEvent>,
    onClear: () -> Unit,
) {
    val shape = RoundedCornerShape(ArcaneRadius.card)
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ArcanePalette.Ink1)
            .border(1.dp, ArcanePalette.Edge, shape)
            .padding(ArcaneSpace.md),
        verticalArrangement = Arrangement.spacedBy(ArcaneSpace.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Live Activity Stream",
                    style = MaterialTheme.typography.titleSmall,
                    color = ArcanePalette.TextHigh,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "(${events.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = ArcanePalette.TextFaint,
                )
            }
            if (events.isNotEmpty()) {
                ArcaneAction(
                    icon = Icons.Rounded.DeleteOutline,
                    hint = "Clear event log",
                    onClick = onClear,
                    target = 32.dp,
                )
            }
        }

        HorizontalDivider(color = ArcanePalette.Edge)

        if (events.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = ArcaneSpace.md),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Awaiting browser connections...",
                    style = MaterialTheme.typography.bodySmall,
                    color = ArcanePalette.TextFaint,
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                events.take(12).forEach { event ->
                    EventRow(event = event, timeFormat = timeFormat)
                }
            }
        }
    }
}

@Composable
private fun EventRow(
    event: DevicePortalServer.PortalEvent,
    timeFormat: SimpleDateFormat,
) {
    val (icon: ImageVector, tint: Color) = when (event.type) {
        DevicePortalServer.PortalEvent.EventType.SERVER_START -> Icons.Rounded.PowerSettingsNew to ArcanePalette.Mint
        DevicePortalServer.PortalEvent.EventType.SERVER_STOP -> Icons.Rounded.PowerSettingsNew to ArcanePalette.Rose
        DevicePortalServer.PortalEvent.EventType.CLIENT_CONNECTED -> Icons.Rounded.Laptop to ArcanePalette.Mint
        DevicePortalServer.PortalEvent.EventType.STREAM_START -> Icons.Rounded.PlayCircle to ArcanePalette.Mint
        DevicePortalServer.PortalEvent.EventType.DOWNLOAD -> Icons.Rounded.Download to ArcanePalette.Mint
        DevicePortalServer.PortalEvent.EventType.UPLOAD_START -> Icons.Rounded.CloudUpload to ArcanePalette.Mint
        DevicePortalServer.PortalEvent.EventType.UPLOAD_COMPLETE -> Icons.Rounded.CheckCircle to ArcanePalette.Mint
        DevicePortalServer.PortalEvent.EventType.ACCESS_DENIED -> Icons.Rounded.DeleteOutline to ArcanePalette.Rose
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(100.dp))
            .background(ArcanePalette.Ink2)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = timeFormat.format(Date(event.timestamp)),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = ArcanePalette.TextFaint,
        )
        Text(
            text = event.description,
            style = MaterialTheme.typography.bodySmall,
            color = ArcanePalette.TextHigh,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (event.details != null) {
            Text(
                text = event.details,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = ArcanePalette.TextMedium,
            )
        }
    }
}

// -------------------------------------------------------------------------------------------------
// OFFLINE CONFIG & NETWORK INFO CARD
// -------------------------------------------------------------------------------------------------

@Composable
private fun OfflineConfigCard(
    localIp: String,
) {
    val shape = RoundedCornerShape(ArcaneRadius.card)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ArcanePalette.Ink1)
            .border(1.dp, ArcanePalette.Edge, shape)
            .padding(ArcaneSpace.md),
        verticalArrangement = Arrangement.spacedBy(ArcaneSpace.sm),
    ) {
        Text(
            text = "Portal Configuration",
            style = MaterialTheme.typography.titleSmall,
            color = ArcanePalette.TextHigh,
            fontWeight = FontWeight.SemiBold,
        )

        HorizontalDivider(color = ArcanePalette.Edge)

        // Network Status
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Wifi,
                    contentDescription = null,
                    tint = if (localIp.isNotEmpty()) ArcanePalette.Mint else ArcanePalette.TextFaint,
                    modifier = Modifier.size(18.dp),
                )
                Column {
                    Text(
                        text = "Local Wi-Fi IP",
                        style = MaterialTheme.typography.labelSmall,
                        color = ArcanePalette.TextFaint,
                    )
                    Text(
                        text = if (localIp.isNotEmpty()) localIp else "No Wi-Fi connection",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = if (localIp.isNotEmpty()) ArcanePalette.TextHigh else ArcanePalette.TextFaint,
                    )
                }
            }

            Text(
                text = "Port 8080",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = ArcanePalette.Mint,
            )
        }
    }
}

// -------------------------------------------------------------------------------------------------
// PULSING DOT COMPONENT
// -------------------------------------------------------------------------------------------------

@Composable
private fun PulsingDot(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "pulse_dot")
    val alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_dot_alpha",
    )

    Box(
        modifier = modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha)),
    )
}

// -------------------------------------------------------------------------------------------------
// FORMAT HELPERS
// -------------------------------------------------------------------------------------------------

private fun formatUptime(startMillis: Long, currentMillis: Long): String {
    if (startMillis <= 0L) return "00:00:00"
    val diffSec = ((currentMillis - startMillis) / 1000L).coerceAtLeast(0L)
    val hours = diffSec / 3600
    val minutes = (diffSec % 3600) / 60
    val seconds = diffSec % 60
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}
