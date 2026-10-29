package dev.arcane.ui.viewer

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.arcane.data.AppGraph
import dev.arcane.ui.components.ArcaneChip
import dev.arcane.ui.theme.ArcanePalette
import dev.arcane.ui.theme.ArcaneSpace
import dev.arcane.ui.theme.ArcaneType
import dev.arcane.vault.index.FileRecord
import java.io.ByteArrayInputStream

/**
 * A web page, with the teeth filed out.
 *
 * JavaScript is off, remote loads are refused, and file and content access are both closed — a vault that
 * auto-executes script from an untrusted `.html` is an exploit chain with a file manager attached. The
 * toggle below is per file and deliberate: it says what turning it on means rather than hiding the option,
 * because some page someone saved in 2011 is genuinely unreadable without it.
 *
 * The bytes stay in RAM. `loadDataWithBaseURL` takes the string and a null base, so there is no URL for a
 * relative resource to resolve against and nothing on disk for another app to read.
 */
@Composable
fun HtmlViewer(graph: AppGraph, record: FileRecord) {
    var html by remember(record) { mutableStateOf<ByteArray?>(null) }
    var failure by remember(record) { mutableStateOf<String?>(null) }
    var scriptsAllowed by remember(record) { mutableStateOf(false) }

    LaunchedEffect(record) {
        html = null
        failure = null
        val outcome = runCatching { graph.repository.readAll(record.fileId) }
        outcome.onSuccess { html = it }.onFailure {
            failure = it.message ?: "This page would not open."
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = ArcaneSpace.gutter, vertical = ArcaneSpace.tight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.xs),
        ) {
            ArcaneChip(
                label = if (scriptsAllowed) "Scripts on" else "Scripts off",
                onClick = { scriptsAllowed = !scriptsAllowed },
                selected = scriptsAllowed,
            )
            Text(
                if (scriptsAllowed) {
                    "This page can run code now. It is only safe because you decided it is."
                } else {
                    "No scripts, no network, no other files."
                },
                style = ArcaneType.Scale.labelSmall,
                color = if (scriptsAllowed) ArcanePalette.Amber else ArcanePalette.TextFaint,
                modifier = Modifier.weight(1f),
            )
        }
        Box(Modifier.fillMaxSize().background(ArcanePalette.Ink0)) {
            val page = html
            when {
                failure != null -> ViewerNote(failure ?: "")
                page == null -> ViewerNote("Opening…")
                else -> RenderedPage(page, scriptsAllowed)
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun RenderedPage(bytes: ByteArray, scriptsAllowed: Boolean) {
    // Decoded once per file: the update block below runs again whenever this composable recomposes, and
    // re-decoding a two-megabyte page to hand it to a webview that already has it would be pure churn.
    val markup = remember(bytes) { String(bytes, Charsets.UTF_8) }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { viewContext ->
            WebView(viewContext).apply {
                setBackgroundColor(0x00000000)
                webViewClient = object : WebViewClient() {
                    /**
                     * Remote loads die here, in code rather than by relying on a missing permission — the app can
                     * reach a socket now, so a saved page that fetches its images, its scripts or something worse
                     * from a server would find one. A document in the vault is read as the bytes it already is,
                     * which is also the only way to say afterwards that nothing phoned home while it was on screen.
                     */
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

                    /** Navigations are swallowed for the same reason, and because an error page reads as the document. */
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                }
            }
        },
        update = { view ->
            view.settings.javaScriptEnabled = scriptsAllowed
            view.settings.javaScriptCanOpenWindowsAutomatically = false
            view.settings.allowFileAccess = false
            view.settings.allowContentAccess = false
            view.settings.domStorageEnabled = false
            view.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            view.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            view.loadDataWithBaseURL(null, markup, "text/html", "utf-8", null)
        },
    )
}
