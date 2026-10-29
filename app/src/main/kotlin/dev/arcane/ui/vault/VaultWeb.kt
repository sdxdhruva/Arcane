package dev.arcane.ui.vault

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ServiceWorkerController
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.arcane.data.AppGraph
import dev.arcane.ui.components.ArcaneAction
import dev.arcane.ui.components.ArcaneChip
import dev.arcane.ui.components.TransferMeter
import dev.arcane.ui.theme.ArcanePalette
import dev.arcane.ui.theme.ArcanePill
import dev.arcane.ui.theme.ArcaneSpace
import dev.arcane.ui.theme.arcaneCard
import dev.arcane.ui.util.formatBytes

/**
 * A page browser that lives inside the vault and leaves nothing behind.
 *
 * The WebView is the one component in the app that writes plaintext to flash by itself — cache, cookies,
 * DOM storage, form data, all in app-private files but still readable at rest. So the session is built the
 * only way this app can promise "the vault closes and nothing remains": storage is switched off rather than
 * cleared later, cached responses are refused, and whatever the WebView managed to keep anyway is wiped and
 * the view destroyed when this screen goes away. Leaving the browser, locking the vault, backgrounding the
 * app and a process death all unmount this composition, which is what makes the cleanup reliable.
 *
 * Downloads are handed to [AppGraph.repository], which streams the response through the chunk writer, so a
 * file pulled from the web exists on flash only as encrypted chunks.
 */
@Composable
fun VaultWeb(
    graph: AppGraph,
    folderId: String?,
    folderName: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val transfers by graph.repository.transfers.collectAsStateWithLifecycle()

    var page by remember { mutableStateOf<String?>(null) }
    var address by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    var loading by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    val go: () -> Unit = {
        val url = normalise(address)
        if (url == null) {
            failure = "Type an http or https link, for example https://example.com/guide.pdf"
        } else {
            address = url
            page = url
            failure = null
            focus.clearFocus()
            // Null only before the first load, when the factory below picks the committed page up itself.
            webView?.loadUrl(url)
        }
    }

    // Nothing from this session survives the screen: not the view, not the storage it touched, not a cookie.
    DisposableEffect(Unit) {
        onDispose {
            webView?.let(::retire)
            wipeSessionData(context)
        }
    }

    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onClose()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = ArcaneSpace.tight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.tight),
        ) {
            ArcaneAction(Icons.Rounded.Close, "Leave the web page", onClose, target = 48.dp)
            AddressField(
                value = address,
                onChange = { address = it },
                onGo = go,
                modifier = Modifier.weight(1f),
            )
            val currentUrl = webView?.url?.takeIf { it.isNotBlank() && it != "about:blank" }
                ?: address.takeIf { it.isNotBlank() && (it.startsWith("http://") || it.startsWith("https://")) }
                ?: page
            val canDownload = currentUrl != null && (currentUrl.startsWith("http://") || currentUrl.startsWith("https://"))
            ArcaneAction(
                icon = Icons.Rounded.CloudDownload,
                hint = "Download open file to vault",
                onClick = {
                    if (currentUrl != null && canDownload) {
                        graph.repository.download(currentUrl, folderId)
                    }
                },
                target = 48.dp,
                enabled = canDownload,
                tint = if (canDownload) ArcanePalette.Cyan else ArcanePalette.TextFaint,
            )
            ArcaneAction(
                if (loading) Icons.Rounded.Stop else Icons.Rounded.Refresh,
                if (loading) "Stop loading" else "Reload this page",
                onClick = {
                    when {
                        loading -> {
                            webView?.stopLoading()
                            loading = false
                        }
                        page != null -> webView?.reload()
                        else -> go()
                    }
                },
                target = 48.dp,
                enabled = page != null,
            )
        }

        Box(Modifier.fillMaxWidth().height(2.dp).background(ArcanePalette.Ink3)) {
            if (loading) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.coerceIn(0.03f, 1f))
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }

        Box(Modifier.fillMaxSize()) {
            if (page == null) {
                StartPad(folderName = folderName)
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        val view = buildWebView(
                            context = viewContext,
                            graph = graph,
                            folderId = folderId,
                            onPage = { url -> address = url },
                            onLoading = { running -> loading = running },
                            onProgress = { value -> progress = value },
                            onFailure = { message -> failure = message },
                            onGone = {
                                // A dead renderer cannot be reused. Dropping the reference sends the screen
                                // back to the start pad, where the same link gets a fresh process.
                                webView = null
                                page = null
                                loading = false
                            },
                        )
                        webView = view
                        // The state only flips to a page once, and this is the view that page was meant for.
                        page?.let(view::loadUrl)
                        view
                    },
                )
            }

            // Both bars live in one bottom column so a failing download and a failing page cannot be drawn
            // on top of each other. Only a running transfer earns a meter: a failed one stays in the list
            // until dismissed, and the refusal itself arrives as a notice, not as a bar frozen at "Failed".
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(ArcaneSpace.sm),
                verticalArrangement = Arrangement.spacedBy(ArcaneSpace.xs),
            ) {
                transfers.lastOrNull { it.failure == null }?.let { transfer ->
                    TransferMeter(
                        fraction = transfer.fraction,
                        label = "Saving ${transfer.name}",
                        detail = "${formatBytes(transfer.doneBytes)} / ${formatBytes(transfer.totalBytes)}",
                        modifier = Modifier
                            .arcaneCard(raised = true)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                }

                failure?.let { message ->
                    FailureBar(
                        message = message,
                        onRetry = {
                            failure = null
                            page?.let { webView?.reload() } ?: go()
                        },
                        onDismiss = { failure = null },
                    )
                }
            }
        }
    }
}

@Composable
private fun AddressField(
    value: String,
    onChange: (String) -> Unit,
    onGo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = ArcanePill
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, ArcanePalette.EdgeStrong, shape)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(
                "Type or paste a link",
                style = MaterialTheme.typography.bodySmall,
                color = ArcanePalette.TextFaint,
                maxLines = 1,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = ArcanePalette.TextHigh,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onGo = { onGo() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The first screen: what this browser is, and where a download will land. */
@Composable
private fun StartPad(folderName: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = ArcaneSpace.lg)
            .padding(top = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ArcaneSpace.sm),
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(ArcanePill)
                .background(ArcanePalette.VioletDim),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Public,
                contentDescription = null,
                tint = ArcanePalette.Violet,
                modifier = Modifier.size(30.dp),
            )
        }
        Text(
            "Open a page",
            style = MaterialTheme.typography.headlineSmall,
            color = ArcanePalette.TextHigh,
            modifier = Modifier.padding(top = ArcaneSpace.tight),
        )
        Text(
            "Pages run in a throwaway session — no history, no cache, no cookies on this phone. Anything you " +
                "download goes straight into $folderName as encrypted pieces, never into Downloads.",
            style = MaterialTheme.typography.bodyMedium,
            color = ArcanePalette.TextFaint,
            modifier = Modifier.padding(horizontal = ArcaneSpace.xs),
        )
        Spacer(Modifier.height(ArcaneSpace.tight))
        Text(
            "An http:// page travels unencrypted between the server and this phone; https is worth typing.",
            style = MaterialTheme.typography.labelMedium,
            color = ArcanePalette.TextFaint,
        )
    }
}

@Composable
private fun FailureBar(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .arcaneCard(raised = true)
            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ArcaneSpace.xs),
    ) {
        Icon(
            Icons.Rounded.Error,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = ArcanePalette.TextHigh,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        ArcaneChip(label = "Retry", onClick = onRetry)
        ArcaneAction(Icons.Rounded.Close, "Dismiss", onDismiss, target = 44.dp, tint = ArcanePalette.TextFaint)
    }
}

/**
 * The one WebView configuration this app allows.
 *
 * Remote content is not the threat here — the user asked for it. The threats are everything WebView does
 * beside rendering: keeping a cache, keeping cookies, persisting form data, loading local files a hostile
 * page names, following `intent:` or `file:` links out of the sandbox, and answering permission prompts on
 * its own. Each of those is closed below, and `shouldOverrideUrlLoading` refuses every scheme but http(s).
 */
@SuppressLint("SetJavaScriptEnabled")
private fun buildWebView(
    context: Context,
    graph: AppGraph,
    folderId: String?,
    onPage: (String) -> Unit,
    onLoading: (Boolean) -> Unit,
    onProgress: (Float) -> Unit,
    onFailure: (String) -> Unit,
    onGone: () -> Unit,
): WebView = WebView(context).apply {
    WebView.setWebContentsDebuggingEnabled(false)
    setBackgroundColor(0xFF0B0E13.toInt())
    CookieManager.getInstance().setAcceptCookie(false)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)

    settings.apply {
        javaScriptEnabled = true
        javaScriptCanOpenWindowsAutomatically = false
        domStorageEnabled = false
        databaseEnabled = false
        cacheMode = WebSettings.LOAD_NO_CACHE
        allowFileAccess = false
        allowContentAccess = false
        @Suppress("DEPRECATION")
        allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        allowUniversalAccessFromFileURLs = false
        setGeolocationEnabled(false)
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        mediaPlaybackRequiresUserGesture = true
        setSupportMultipleWindows(false)
        @Suppress("DEPRECATION")
        setSaveFormData(false)
    }
    // Service worker caches live beside the HTTP cache and would otherwise survive every wipe we do here.
    runCatching {
        ServiceWorkerController.getInstance().serviceWorkerWebSettings.cacheMode = WebSettings.LOAD_NO_CACHE
    }

    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return true
            val path = request.url.path?.lowercase() ?: ""
            if (DIRECT_DOWNLOAD_EXTS.any { path.endsWith(it) }) {
                graph.repository.download(request.url.toString(), folderId)
                return true
            }
            return false
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (url == null || url == "about:blank") return
            onPage(url)
            onLoading(true)
            onProgress(0.05f)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (url == null || url == "about:blank") return
            onPage(url)
            onLoading(false)
            onProgress(1f)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            // Subresources fail all the time on a weak connection; only the page itself is worth reporting.
            if (request.isForMainFrame) {
                onLoading(false)
                onFailure(error.description?.toString() ?: "This page could not load.")
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // Left unhandled, a page that takes its renderer down takes this app down with it.
            onFailure("This page's process stopped. Type the link again to reopen it.")
            onGone()
            return true
        }
    }

    webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            onProgress(newProgress / 100f)
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            // Camera, microphone, geolocation: a vault browser never answers yes to these on its own.
            request.deny()
        }
    }

    setDownloadListener { url, _, _, _, _ ->
        val scheme = runCatching { url.toUri().scheme?.lowercase() }.getOrNull()
        if (scheme == "http" || scheme == "https") {
            // The repository names the file from the response headers itself, so a hostile
            // Content-Disposition never becomes a path on this phone.
            graph.repository.download(url, folderId)
        } else {
            onFailure("This page's download link cannot be saved directly. Open the file itself instead.")
        }
    }
}

/** Stops the view, clears what it kept in RAM, and destroys it once Compose has detached it. */
private fun retire(view: WebView) {
    runCatching {
        view.stopLoading()
        view.loadUrl("about:blank")
        view.clearHistory()
        view.clearCache(true)
        view.clearFormData()
        // Destroying an attached view is undefined; a post leaves the detach to the next frame.
        view.post { runCatching { view.destroy() } }
    }
}

/** Best-effort removal of whatever the platform persists outside the view itself. */
private fun wipeSessionData(context: Context) {
    runCatching {
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            // Flush writes the emptied jar over the file that held the old one.
            flush()
        }
        WebStorage.getInstance().deleteAllData()
        WebViewDatabase.getInstance(context).clearFormData()
        WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword()
    }
}

/** `example.com/a.pdf` becomes `https://example.com/a.pdf`; anything that is not web is refused. */
private fun normalise(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
    val parsed = runCatching { withScheme.toUri() }.getOrNull() ?: return null
    val scheme = parsed.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    if (parsed.host.isNullOrBlank()) return null
    return withScheme
}

private val DIRECT_DOWNLOAD_EXTS = setOf(
    ".apk", ".zip", ".rar", ".7z", ".tar", ".gz", ".tgz", ".bz2", ".xz", ".iso", ".dmg", ".bin"
)
