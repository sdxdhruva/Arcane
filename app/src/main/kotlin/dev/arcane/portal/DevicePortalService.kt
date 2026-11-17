package dev.arcane.portal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.arcane.ArcaneApplication
import dev.arcane.MainActivity
import dev.arcane.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground Service for Phone Web Portal:
 *
 * Keeps the unencrypted local Wi-Fi web server running continuously even when:
 * 1. The user switches to other apps or the home screen.
 * 2. The phone screen turns off or locks.
 *
 * Isolation & Zero Vault Footprint:
 * - This service strictly communicates with [DevicePortalServer] (unencrypted phone storage).
 * - It has ZERO awareness of or dependency on [VaultRepository], [VaultRealm], or vault keys.
 * - Even when the phone locks and the vault evaporates from RAM, this service continues
 *   serving public device photos/videos/audio/downloads over local Wi-Fi until manually stopped.
 */
class DevicePortalService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_STOP -> {
                stopPortal()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val port = intent?.getIntExtra(EXTRA_PORT, 8080) ?: 8080
                startPortal(port)
                return START_NOT_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    private fun startPortal(port: Int) {
        val app = application as? ArcaneApplication ?: return
        val server = app.graph.devicePortal

        // Immediate initial notification to satisfy Android Foreground Service requirements
        val initialNotification = buildNotification("Starting Phone Web Portal...", null)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, initialNotification)
            }
        } catch (_: Exception) {
            // ForegroundServiceStartNotAllowedException or SecurityException on Android 12+
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        // Acquire WakeLock and WifiLock to prevent network/CPU sleep during background/screen-off
        acquireLocks()

        // Start server instance
        server.start(preferredPort = port)

        // Observe server status updates to update the notification dynamically
        scope.launch {
            server.status.collectLatest { status ->
                if (!status.isRunning) {
                    stopPortal()
                } else {
                    val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    val notification = buildNotification(
                        title = "Phone Web Portal Active",
                        content = "${status.url} • Local Wi-Fi File Sharing",
                    )
                    notifManager.notify(NOTIFICATION_ID, notification)
                }
            }
        }
    }

    private fun stopPortal() {
        val app = application as? ArcaneApplication
        app?.graph?.devicePortal?.stop()

        releaseLocks()
        scope.cancel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "Arcane:DevicePortalWakeLock",
                )?.apply {
                    setReferenceCounted(false)
                    acquire(24 * 60 * 60 * 1000L) // 24-hour safeguard ceiling
                }
            }

            if (wifiLock == null) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL
                }
                wifiLock = wifiManager?.createWifiLock(mode, "Arcane:DevicePortalWifiLock")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (_: Exception) {}
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null

            wifiLock?.let {
                if (it.isHeld) it.release()
            }
            wifiLock = null
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Phone Web Portal",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows live status and quick controls for the Phone Web Portal local server"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, content: String?): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = Intent(this, DevicePortalService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(content ?: "Local Wi-Fi file sharing active")
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop Server",
                stopPendingIntent,
            )

        return builder.build()
    }

    override fun onDestroy() {
        stopPortal()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "arcane_phone_portal"
        const val NOTIFICATION_ID = 2026
        const val ACTION_START = "dev.arcane.portal.START"
        const val ACTION_STOP = "dev.arcane.portal.STOP"
        const val EXTRA_PORT = "extra_port"

        fun start(context: Context, port: Int = 8080) {
            val intent = Intent(context, DevicePortalService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PORT, port)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DevicePortalService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
