package dev.arcane.ui.vault

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.arcane.data.AppGraph
import dev.arcane.portal.AirVaultServer
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
import kotlinx.coroutines.launch

/**
 * Dedicated Screen for AirVault Local Wi-Fi PC Portal.
 *
 * Provides real-time server controls, connection credentials (URL, 6-digit PIN, QR readiness),
 * granular folder permissions, real-time performance telemetry (bytes streamed, files ingested,
 * connected devices), and live activity logs.
 */
@Composable
fun VaultPortalScreen(
    graph: AppGraph,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val portalStatus by graph.portal.status.collectAsStateWithLifecycle()
    val portalStats by graph.portal.stats.collectAsStateWithLifecycle()

    var selectedScopeIndex by remember { mutableStateOf(0) } // 0 = Entire Vault, 1 = Specific Folder
    var selectedFolderId by remember { mutableStateOf<String?>(null) }
    var selectedFolderName by remember { mutableStateOf("Choose subfolder...") }

    // Fetch folder choices when stopped
    val folderChoices by produceState<List<Pair<String?, String>>>(initialValue = emptyList(), portalStatus.isRunning) {
        if (!portalStatus.isRunning) {
            value = graph.repository.folderChoices()
        }
    }

    // Live tick for uptime counter
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(portalStatus.isRunning) {
        while (portalStatus.isRunning) {
            delay(1000L)
            currentTime = System.currentTimeMillis()
        }
    }

    DisposableEffect(portalStatus.isRunning) {
        val activity = context as? Activity
        if (portalStatus.isRunning) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
                        onToggle = {
                            if (portalStatus.isRunning) {
                                graph.portal.stop()
                            } else {
                                val folderIds = if (selectedScopeIndex == 1 && selectedFolderId != null) {
                                    setOf(selectedFolderId!!)
                                } else null
                                val scopeName = if (folderIds == null) "Entire Vault" else selectedFolderName
                                graph.portal.start(
                                    sharedFolderIds = folderIds,
                                    sharedScopeName = scopeName,
                                )
                            }
                        },
                    )
                }

                // Connection Credentials Card (Active Server Only)
                if (portalStatus.isRunning) {
                    item(key = "credentials") {
                        CredentialsCard(
                            url = portalStatus.url,
                            pin = portalStatus.pin,
                            scopeName = portalStatus.sharedScopeName,
                            onCopyUrl = {
                                clipboardManager.setText(AnnotatedString(portalStatus.url))
                                Toast.makeText(context, "URL copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            onOpenBrowser = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(portalStatus.url))
                                context.startActivity(intent)
                            },
                            onCopyPin = {
                                clipboardManager.setText(AnnotatedString(portalStatus.pin))
                                Toast.makeText(context, "PIN copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            onRegeneratePin = {
                                val newPin = graph.portal.regeneratePin()
                                Toast.makeText(context, "New PIN: $newPin", Toast.LENGTH_SHORT).show()
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
                            onClear = { graph.portal.clearEvents() },
                        )
                    }
                } else {
                    // Scope Selection Card (When Server is Stopped)
                    item(key = "scope_selector") {
                        ScopeSelectionCard(
                            selectedIndex = selectedScopeIndex,
                            onSelectIndex = { selectedScopeIndex = it },
                            folders = folderChoices,
                            selectedFolderId = selectedFolderId,
                            selectedFolderName = selectedFolderName,
                            onSelectFolder = { id, name ->
                                selectedFolderId = id
                                selectedFolderName = name
                            },
                        )
                    }

                    // Security & Invariants Card
                    item(key = "security_card") {
                        SecurityInvariantsCard()
                    }
                }

                item(key = "bottom_spacer") {
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun PortalTopBar(
    isRunning: Boolean,
    port: Int,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ArcaneSpace.sm, vertical = ArcaneSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.xs),
        ) {
            ArcaneAction(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                hint = "Back to Vault",
                onClick = onClose,
            )
            Column {
                Text(
                    text = "AirVault PC Portal",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = ArcanePalette.TextHigh,
                )
                Text(
                    text = "Local Wi-Fi Gateway",
                    style = MaterialTheme.typography.labelSmall,
                    color = ArcanePalette.TextFaint,
                )
            }
        }

        // Live Status Pill
        Box(
            modifier = Modifier
                .clip(ArcanePill)
                .background(if (isRunning) ArcanePalette.Cyan.copy(alpha = 0.15f) else ArcanePalette.Ink2)
                .border(1.dp, if (isRunning) ArcanePalette.Cyan.copy(alpha = 0.45f) else ArcanePalette.Edge, ArcanePill)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (isRunning) {
                    val transition = rememberInfiniteTransition(label = "pulse")
                    val scale by transition.animateFloat(
                        initialValue = 0.85f,
                        targetValue = 1.25f,
                        animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse),
                        label = "scale",
                    )
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .scale(scale)
                            .clip(CircleShape)
                            .background(ArcanePalette.Cyan),
                    )
                }
                Text(
                    text = if (isRunning) "ONLINE · $port" else "OFFLINE",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (isRunning) ArcanePalette.Cyan else ArcanePalette.TextFaint,
                )
            }
        }
    }
}

@Composable
private fun HeroPowerCard(
    isRunning: Boolean,
    onToggle: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ArcaneRadius.card))
            .background(
                Brush.verticalGradient(
                    colors = if (isRunning) {
                        listOf(ArcanePalette.Cyan.copy(alpha = 0.18f), ArcanePalette.Ink1)
                    } else {
                        listOf(ArcanePalette.Ink2, ArcanePalette.Ink1)
                    },
                ),
            )
            .border(
                width = 1.dp,
                color = if (isRunning) ArcanePalette.Cyan.copy(alpha = 0.4f) else ArcanePalette.EdgeStrong,
                shape = RoundedCornerShape(ArcaneRadius.card),
            )
            .padding(24.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Neon Power Icon
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(
                        if (isRunning) ArcanePalette.Cyan.copy(alpha = 0.2f) else ArcanePalette.Ink3.copy(alpha = 0.6f),
                    )
                    .border(
                        width = 2.dp,
                        color = if (isRunning) ArcanePalette.Cyan else ArcanePalette.EdgeStrong,
                        shape = CircleShape,
                    )
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.PowerSettingsNew,
                    contentDescription = if (isRunning) "Stop Server" else "Start Server",
                    tint = if (isRunning) ArcanePalette.Cyan else ArcanePalette.TextFaint,
                    modifier = Modifier.size(38.dp),
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (isRunning) "PC Portal is Active" else "PC Portal is Offline",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = if (isRunning) ArcanePalette.Cyan else ArcanePalette.TextHigh,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (isRunning) {
                        "Broadcasting on your local Wi-Fi. Access files or stream 4K video directly from your PC browser."
                    } else {
                        "Start server to stream decrypted media and securely upload files over your local Wi-Fi."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = ArcanePalette.TextMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            ArcaneButton(
                label = if (isRunning) "Stop PC Portal" else "Start PC Portal",
                icon = if (isRunning) Icons.Rounded.PowerSettingsNew else Icons.Rounded.Wifi,
                filled = !isRunning,
                onClick = onToggle,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CredentialsCard(
    url: String,
    pin: String,
    scopeName: String,
    onCopyUrl: () -> Unit,
    onOpenBrowser: () -> Unit,
    onCopyPin: () -> Unit,
    onRegeneratePin: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ArcaneRadius.card))
            .background(ArcanePalette.Ink1)
            .border(1.dp, ArcanePalette.EdgeStrong, RoundedCornerShape(ArcaneRadius.card))
            .padding(ArcaneSpace.md),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ArcaneSpace.md)) {
            // URL Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "PC BROWSER ADDRESS",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                    color = ArcanePalette.TextFaint,
                )
                Text(
                    text = "Scope: $scopeName",
                    style = MaterialTheme.typography.labelSmall,
                    color = ArcanePalette.Violet,
                )
            }

            // Web Address Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(ArcaneRadius.field))
                    .background(ArcanePalette.Ink2)
                    .border(1.dp, ArcanePalette.Edge, RoundedCornerShape(ArcaneRadius.field))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = url,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = ArcanePalette.Cyan,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ArcaneAction(
                            icon = Icons.Rounded.ContentCopy,
                            hint = "Copy URL",
                            onClick = onCopyUrl,
                            target = 36.dp,
                            tint = ArcanePalette.Cyan,
                        )
                        ArcaneAction(
                            icon = Icons.Rounded.OpenInBrowser,
                            hint = "Open in Browser",
                            onClick = onOpenBrowser,
                            target = 36.dp,
                            tint = ArcanePalette.Cyan,
                        )
                    }
                }
            }

            // Security PIN Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "6-DIGIT SECURITY PIN",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                    color = ArcanePalette.TextFaint,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable(onClick = onRegeneratePin),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "New PIN",
                        tint = ArcanePalette.Cyan,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "New PIN",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = ArcanePalette.Cyan,
                    )
                }
            }

            // PIN Digit Boxes
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    pin.forEach { digit ->
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(ArcanePalette.Ink2)
                                .border(1.dp, ArcanePalette.Cyan.copy(alpha = 0.35f), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = digit.toString(),
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                ArcaneAction(
                    icon = Icons.Rounded.ContentCopy,
                    hint = "Copy PIN",
                    onClick = onCopyPin,
                    target = 40.dp,
                )
            }
        }
    }
}

@Composable
private fun TelemetryDashboardGrid(
    stats: AirVaultServer.PortalStats,
    uptime: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "LIVE PERFORMANCE METRICS",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
            color = ArcanePalette.TextFaint,
            modifier = Modifier.padding(start = 4.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatCard(
                title = "Connected Devices",
                value = "${stats.activeClients}",
                subtitle = if (stats.activeClients > 0) "Active socket session" else "Idle (Waiting)",
                icon = Icons.Rounded.Laptop,
                accent = ArcanePalette.Cyan,
                modifier = Modifier.weight(1f),
            )
            StatCard(
                title = "Direct Ingested",
                value = formatBytes(stats.bytesUploaded),
                subtitle = "${stats.filesUploaded} files encrypted",
                icon = Icons.Rounded.CloudUpload,
                accent = ArcanePalette.Violet,
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatCard(
                title = "Decrypted Streamed",
                value = formatBytes(stats.bytesStreamed),
                subtitle = "Decrypted on-the-fly",
                icon = Icons.Rounded.PlayCircle,
                accent = ArcanePalette.Cyan,
                modifier = Modifier.weight(1f),
            )
            StatCard(
                title = "Session Uptime",
                value = uptime,
                subtitle = "${stats.totalRequests} HTTP requests",
                icon = Icons.Rounded.Shield,
                accent = Color(0xFF34D399),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatCard(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(ArcaneRadius.card))
            .background(ArcanePalette.Ink1)
            .border(1.dp, ArcanePalette.Edge, RoundedCornerShape(ArcaneRadius.card))
            .padding(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = ArcanePalette.TextFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = ArcanePalette.TextHigh,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = ArcanePalette.TextFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LiveActivityCard(
    events: List<AirVaultServer.PortalEvent>,
    onClear: () -> Unit,
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ArcaneRadius.card))
            .background(ArcanePalette.Ink1)
            .border(1.dp, ArcanePalette.EdgeStrong, RoundedCornerShape(ArcaneRadius.card))
            .padding(ArcaneSpace.md),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ArcaneSpace.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "REAL-TIME ACTIVITY LOG",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                    color = ArcanePalette.TextFaint,
                )
                if (events.isNotEmpty()) {
                    ArcaneAction(
                        icon = Icons.Rounded.DeleteOutline,
                        hint = "Clear Log",
                        onClick = onClear,
                        target = 32.dp,
                    )
                }
            }

            if (events.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Waiting for browser connection...",
                        style = MaterialTheme.typography.bodySmall,
                        color = ArcanePalette.TextFaint,
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    events.take(10).forEach { event ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(ArcanePalette.Ink2)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = timeFormat.format(Date(event.timestamp)),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = ArcanePalette.TextFaint,
                            )
                            Icon(
                                imageVector = when (event.type) {
                                    AirVaultServer.PortalEvent.EventType.CLIENT_CONNECTED -> Icons.Rounded.CheckCircle
                                    AirVaultServer.PortalEvent.EventType.STREAM_START -> Icons.Rounded.PlayCircle
                                    AirVaultServer.PortalEvent.EventType.DOWNLOAD -> Icons.Rounded.Laptop
                                    AirVaultServer.PortalEvent.EventType.UPLOAD_COMPLETE -> Icons.Rounded.CloudUpload
                                    AirVaultServer.PortalEvent.EventType.PIN_REJECTED,
                                    AirVaultServer.PortalEvent.EventType.ACCESS_DENIED -> Icons.Rounded.WarningAmber
                                    else -> Icons.Rounded.Refresh
                                },
                                contentDescription = null,
                                tint = when (event.type) {
                                    AirVaultServer.PortalEvent.EventType.PIN_REJECTED,
                                    AirVaultServer.PortalEvent.EventType.ACCESS_DENIED -> Color(0xFFF87171)
                                    AirVaultServer.PortalEvent.EventType.UPLOAD_COMPLETE -> ArcanePalette.Violet
                                    else -> ArcanePalette.Cyan
                                },
                                modifier = Modifier.size(15.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = event.description,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                    color = ArcanePalette.TextHigh,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (!event.details.isNullOrEmpty()) {
                                    Text(
                                        text = event.details,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ArcanePalette.TextFaint,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScopeSelectionCard(
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    folders: List<Pair<String?, String>>,
    selectedFolderId: String?,
    selectedFolderName: String,
    onSelectFolder: (String?, String) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ArcaneRadius.card))
            .background(ArcanePalette.Ink1)
            .border(1.dp, ArcanePalette.Edge, RoundedCornerShape(ArcaneRadius.card))
            .padding(ArcaneSpace.md),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ArcaneSpace.sm)) {
            Text(
                text = "FOLDER ACCESS PERMISSION",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                color = ArcanePalette.TextFaint,
            )

            // Entire Vault Option
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(ArcaneRadius.field))
                    .background(if (selectedIndex == 0) ArcanePalette.Cyan.copy(alpha = 0.12f) else ArcanePalette.Ink2)
                    .border(
                        1.dp,
                        if (selectedIndex == 0) ArcanePalette.Cyan.copy(alpha = 0.45f) else ArcanePalette.Edge,
                        RoundedCornerShape(ArcaneRadius.field),
                    )
                    .clickable { onSelectIndex(0) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Shield,
                    contentDescription = null,
                    tint = if (selectedIndex == 0) ArcanePalette.Cyan else ArcanePalette.TextFaint,
                    modifier = Modifier.size(20.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Entire Vault",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (selectedIndex == 0) ArcanePalette.Cyan else ArcanePalette.TextHigh,
                    )
                    Text(
                        text = "PC can browse, stream, and upload across all folders",
                        style = MaterialTheme.typography.bodySmall,
                        color = ArcanePalette.TextFaint,
                    )
                }
            }

            // Subfolder Restriction Option
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(ArcaneRadius.field))
                    .background(if (selectedIndex == 1) ArcanePalette.Cyan.copy(alpha = 0.12f) else ArcanePalette.Ink2)
                    .border(
                        1.dp,
                        if (selectedIndex == 1) ArcanePalette.Cyan.copy(alpha = 0.45f) else ArcanePalette.Edge,
                        RoundedCornerShape(ArcaneRadius.field),
                    )
                    .clickable { onSelectIndex(1) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Folder,
                    contentDescription = null,
                    tint = if (selectedIndex == 1) ArcanePalette.Cyan else ArcanePalette.TextFaint,
                    modifier = Modifier.size(20.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Restricted Subfolder",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (selectedIndex == 1) ArcanePalette.Cyan else ArcanePalette.TextHigh,
                    )
                    Text(
                        text = "PC can only see and upload to one chosen folder",
                        style = MaterialTheme.typography.bodySmall,
                        color = ArcanePalette.TextFaint,
                    )
                }
            }

            // Folder list when restricted
            AnimatedVisibility(visible = selectedIndex == 1) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val subfolders = folders.filter { it.first != null }
                    if (subfolders.isEmpty()) {
                        Text(
                            text = "No subfolders created yet. Using The Vault root.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ArcanePalette.TextFaint,
                            modifier = Modifier.padding(8.dp),
                        )
                    } else {
                        subfolders.forEach { (id, name) ->
                            val isSelected = selectedFolderId == id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) ArcanePalette.Violet.copy(alpha = 0.2f) else ArcanePalette.Ink3)
                                    .border(1.dp, if (isSelected) ArcanePalette.Violet else Color.Transparent, RoundedCornerShape(8.dp))
                                    .clickable { onSelectFolder(id, name) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal),
                                    color = if (isSelected) ArcanePalette.Violet else ArcanePalette.TextHigh,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SecurityInvariantsCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ArcaneRadius.card))
            .background(ArcanePalette.Ink1)
            .border(1.dp, ArcanePalette.Edge, RoundedCornerShape(ArcaneRadius.card))
            .padding(ArcaneSpace.md),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Security,
                    contentDescription = null,
                    tint = ArcanePalette.Cyan,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "SECURITY & ZERO-LEAK GUARANTEES",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                    color = ArcanePalette.Cyan,
                )
            }
            Text(
                text = "• Local Wi-Fi Only: Binds to local IP. Never routes through any external cloud server or third-party relay.\n" +
                    "• Zero-Plaintext Flash: Incoming uploads are chunked and encrypted with AES-256-GCM in RAM before touching storage.\n" +
                    "• Auto-Teardown: Locking the vault or exiting Arcane immediately kills the server and wipes all active session tokens.",
                style = MaterialTheme.typography.bodySmall,
                color = ArcanePalette.TextMedium,
                lineHeight = 20.sp,
            )
        }
    }
}

private fun formatUptime(startedAtMillis: Long, nowMillis: Long): String {
    if (startedAtMillis <= 0L) return "00:00"
    val diffSeconds = maxOf(0L, (nowMillis - startedAtMillis) / 1000L)
    val mins = diffSeconds / 60L
    val secs = diffSeconds % 60L
    val hours = mins / 60L
    return if (hours > 0) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, mins % 60, secs)
    } else {
        String.format(Locale.US, "%02d:%02d", mins, secs)
    }
}
