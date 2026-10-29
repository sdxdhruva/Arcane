package dev.arcane

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.arcane.data.AppGraph
import dev.arcane.ui.ArcaneShell
import dev.arcane.ui.theme.ArcaneTheme

/**
 * The window is the last line of defence for whatever is on screen, so the flag that blocks screenshots and
 * the recents thumbnail is decided here, from the one fact that says what is open: a realm.
 *
 * Only the decrypted folder gets it, and nothing else does — not the gate, not plain browsing. A flag set on
 * a screen that holds no secret is itself the tell: it means a screenshot of this app's own recents card
 * would advertise that some other state of this app refuses screenshots.
 */
class MainActivity : ComponentActivity() {

    private val secure = WindowManager.LayoutParams.FLAG_SECURE

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is dark whatever the system theme says, so the bar icons are decided here rather than left
        // to `auto`, which would hand a light-mode phone dark icons on a near-black backdrop.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val graph = (application as ArcaneApplication).graph
        setContent {
            ArcaneTheme {
                val opened by graph.holder.opened.collectAsStateWithLifecycle()
                LaunchedEffect(opened != null) {
                    if (opened != null) window.addFlags(secure) else window.clearFlags(secure)
                }
                ArcaneShell(graph = graph)
            }
        }
        hideSystemBars()
    }

    /**
     * Both bars go, and come back only as a transient overlay on a swipe from the edge.
     *
     * The status bar is the reason: a notification headline slides across the top of an open folder, visible
     * to whoever is holding the phone, and no in-app screen can retract it. Hiding the bars costs nothing the
     * app needs — the phone's own gestures still work, and a swipe shows the bars for a few seconds — and it
     * buys a frame edge to edge, which is also what makes a video fill the glass.
     */
    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(SYSTEM_BARS)
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * The picker window, the permission sheet and the recents card all reset the bars on their way out, and
     * nothing else puts them back. Re-asserting on focus is what stops the vault from being handed back with
     * the notification shade's permanent home.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** Any touch resets the idle clock, which is the whole of what "active" means here. */
    override fun onUserInteraction() {
        super.onUserInteraction()
        (application as ArcaneApplication).graph.session.touch()
    }

    companion object {
        private val SYSTEM_BARS = WindowInsetsCompat.Type.systemBars()
    }
}
