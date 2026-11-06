package com.teamshryne.kino.ui.screens

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.core.net.toUri
import com.teamshryne.kino.data.security.DeepSeekCredentials
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private const val DEEPSEEK_CHAT_URL = "https://chat.deepseek.com/"

@Composable
fun DeepSeekLoginScreen(
    connected: Boolean,
    onCredentials: (DeepSeekCredentials) -> Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val webViewVersion = remember { WebView.getCurrentWebViewPackage()?.versionName ?: "unknown" }
    var showBrowser by rememberSaveable { mutableStateOf(false) }
    var captureMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var reloadKey by rememberSaveable { mutableIntStateOf(0) }
    var popupOpen by rememberSaveable { mutableStateOf(false) }
    var popupCloseKey by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Back") }
            Text("Connect DeepSeek", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        if (!showBrowser) {
            Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Sign in to DeepSeek", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("Choose where to open the official chat.deepseek.com sign-in page. The in-app option uses Android System WebView’s default browser settings.", style = MaterialTheme.typography.bodyLarge)
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("Before you continue", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("• Your password is entered only on DeepSeek’s page; Kino never reads it.")
                        Text("• Kino captures only DeepSeek’s signed-in session headers after login.")
                        Text("• The session is encrypted with Android Keystore and stays on this device.")
                        Text("• You can disconnect any time in Settings. DeepSeek may ask you to sign in again when the session expires.")
                    }
                }
                Text("Kino does not add a JavaScript bridge to DeepSeek’s page. The full browser option cannot pass its sign-in session back to Kino.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Kino WebView: $webViewVersion · system default user agent", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                captureMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                Button(onClick = { showBrowser = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Try in-app WebView")
                }
                TextButton(
                    onClick = {
                        CustomTabsIntent.Builder().build().launchUrl(context, DEEPSEEK_CHAT_URL.toUri())
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.OpenInBrowser, contentDescription = null)
                    Text("Open full browser", modifier = Modifier.padding(start = 9.dp))
                }
            }
        } else {
            Text("Sign in on the page below. Kino will show Connected when the session is ready.", modifier = Modifier.padding(horizontal = 18.dp, vertical = 5.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp)) {
                Text(captureMessage ?: "Sign in on the page below.", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { captureMessage = "Reloading DeepSeek…"; reloadKey++ }) { Text("Reload") }
            }
            if (popupOpen) {
                TextButton(
                    onClick = { popupCloseKey++; popupOpen = false },
                    modifier = Modifier.align(Alignment.End).padding(end = 12.dp),
                ) { Text("Close sign-in window") }
            }
            DeepSeekWebView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                reloadKey = reloadKey,
                popupCloseKey = popupCloseKey,
                onCapture = { credentials ->
                    captureMessage = if (onCredentials(credentials)) "Session captured and saved securely." else "Kino could not save this session securely. Go back and try again."
                },
                onStatus = { captureMessage = it },
                onPopupChanged = { popupOpen = it },
            )
            if (connected) {
                Text("Connected to DeepSeek", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
private fun DeepSeekWebView(
    modifier: Modifier,
    reloadKey: Int,
    popupCloseKey: Int,
    onCapture: (DeepSeekCredentials) -> Unit,
    onStatus: (String) -> Unit,
    onPopupChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val delivered = remember { AtomicBoolean(false) }
    val mainFrameFailed = remember { AtomicBoolean(false) }
    val captureLock = remember { Any() }
    val collected = remember { mutableMapOf<String, String>() }
    AndroidView(
        modifier = modifier,
        factory = {
            CookieManager.getInstance().setAcceptCookie(true)
            val container = DeepSeekWebViewContainer(context)
            val mainView = WebView(context)
            container.mainView = mainView
            container.addView(mainView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

            fun configure(webView: WebView) {
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.allowFileAccess = false
                webView.settings.allowContentAccess = false
                webView.settings.javaScriptCanOpenWindowsAutomatically = false
                webView.settings.setSupportMultipleWindows(true)
                // Keep WebView's system default user agent. Customizing it can
                // make browser feature hints inconsistent with the real engine.
                val version = WebView.getCurrentWebViewPackage()?.versionName ?: "unknown"
                onStatus("Android System WebView $version · default user agent · popup windows enabled")
                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                        if (message.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                            onStatus("DeepSeek loaded but a page script failed. Tap Reload or update Android System WebView.")
                        }
                        return true
                    }

                    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                        if (!isUserGesture || container.popupView != null) return false
                        val popup = WebView(view.context)
                        container.popupView = popup
                        container.addView(popup, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                        configure(popup)
                        val transport = resultMsg.obj as? WebView.WebViewTransport ?: run {
                            container.closePopup()
                            return false
                        }
                        transport.webView = popup
                        resultMsg.sendToTarget()
                        onPopupChanged(true)
                        return true
                    }

                    override fun onCloseWindow(window: WebView) {
                        if (window == container.popupView) {
                            container.closePopup()
                            onPopupChanged(false)
                        }
                    }
                }
                webView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                        super.onReceivedError(view, request, error)
                        if (request.isForMainFrame) {
                            mainFrameFailed.set(true)
                            onStatus("Could not load DeepSeek (WebView error ${error.errorCode}). Check your connection and tap Reload.")
                        }
                    }

                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: android.webkit.WebResourceResponse) {
                        super.onReceivedHttpError(view, request, response)
                        val headers = response.responseHeaders.orEmpty()
                        val wafChallenge = headers.any { (name, value) ->
                            name.equals("x-amzn-waf-action", ignoreCase = true) && value.equals("challenge", ignoreCase = true)
                        }
                        if (request.isForMainFrame && wafChallenge) {
                            mainFrameFailed.set(true)
                            onStatus("DeepSeek’s AWS security check challenged this embedded browser. Tap Reload; if it remains blank, update Android System WebView and try again.")
                        } else if (request.isForMainFrame && response.statusCode >= 400) {
                            mainFrameFailed.set(true)
                            onStatus("DeepSeek returned HTTP ${response.statusCode}. Tap Reload or try again later.")
                        }
                    }

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                        val url = request.url
                        if (url.scheme != "https" || !url.host.equals("chat.deepseek.com", ignoreCase = true) || !url.path.orEmpty().startsWith("/api/")) {
                            return super.shouldInterceptRequest(view, request)
                        }
                        val headers = request.requestHeaders.entries.associate { (name, value) -> name.lowercase(Locale.ROOT) to value }
                        val cookies = parseCookies(headers["cookie"].orEmpty().ifBlank { CookieManager.getInstance().getCookie("https://chat.deepseek.com") .orEmpty() })
                        synchronized(captureLock) {
                            headers["authorization"]?.removePrefix("Bearer ")?.takeIf(String::isNotBlank)?.let { collected["token"] = it }
                            headers["x-hif-dliq"]?.takeIf(String::isNotBlank)?.let { collected["hif_dliq"] = it }
                            headers["x-hif-leim"]?.takeIf(String::isNotBlank)?.let { collected["hif_leim"] = it }
                            cookies["aws-waf-token"]?.takeIf(String::isNotBlank)?.let { collected["waf_token"] = it }
                            cookies["ds_session_id"]?.takeIf(String::isNotBlank)?.let { collected["ds_session_id"] = it }
                            val required = listOf("token", "waf_token", "ds_session_id", "hif_dliq", "hif_leim")
                            if (!delivered.get() && required.all { !collected[it].isNullOrBlank() }) {
                                delivered.set(true)
                                val credential = DeepSeekCredentials(
                                    token = collected.getValue("token"),
                                    wafToken = collected.getValue("waf_token"),
                                    hifDliq = collected.getValue("hif_dliq"),
                                    hifLeim = collected.getValue("hif_leim"),
                                    sessionId = collected["ds_session_id"].orEmpty(),
                                    userAgent = headers["user-agent"].orEmpty(),
                                )
                                view.post { onCapture(credential) }
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        if (url?.startsWith("https://chat.deepseek.com") == true && !delivered.get() && !mainFrameFailed.get()) {
                            onStatus("DeepSeek finished loading. If it is blank, its security check may not have completed; tap Reload.")
                        }
                    }
                }
            }
            configure(mainView)
            mainView.loadUrl(DEEPSEEK_CHAT_URL)
            container
        },
        update = { container ->
            if (container.lastReloadKey != reloadKey) {
                container.lastReloadKey = reloadKey
                if (reloadKey > 0) {
                    delivered.set(false)
                    mainFrameFailed.set(false)
                    synchronized(captureLock) { collected.clear() }
                    container.closePopup()
                    onPopupChanged(false)
                    container.mainView?.reload()
                }
            }
            if (container.lastPopupCloseKey != popupCloseKey) {
                container.lastPopupCloseKey = popupCloseKey
                container.closePopup()
            }
        },
        onRelease = { container -> container.destroyViews() },
    )
}

private class DeepSeekWebViewContainer(context: Context) : FrameLayout(context) {
    var mainView: WebView? = null
    var popupView: WebView? = null
    var lastReloadKey: Int = -1
    var lastPopupCloseKey: Int = 0

    fun closePopup() {
        popupView?.let { popup ->
            removeView(popup)
            popup.stopLoading()
            popup.destroy()
        }
        popupView = null
    }

    fun destroyViews() {
        closePopup()
        mainView?.let { main ->
            removeView(main)
            main.stopLoading()
            main.destroy()
        }
        mainView = null
    }
}

private fun parseCookies(header: String): Map<String, String> = header.split(';').mapNotNull { part ->
    val separator = part.indexOf('=')
    if (separator <= 0) null else part.substring(0, separator).trim() to part.substring(separator + 1).trim()
}.toMap()
