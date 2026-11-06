package com.teamshryne.kino.ui.screens

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.teamshryne.kino.data.security.DeepSeekCredentials
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun DeepSeekLoginScreen(
    connected: Boolean,
    onCredentials: (DeepSeekCredentials) -> Boolean,
    onBack: () -> Unit,
) {
    var showBrowser by rememberSaveable { mutableStateOf(false) }
    var captureMessage by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Back") }
            Text("Connect DeepSeek", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        if (!showBrowser) {
            Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Sign in to DeepSeek", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("Kino opens the official chat.deepseek.com sign-in page. Sign in there with your DeepSeek account, then leave the page open for a moment while Kino securely reads the session needed to send chats.", style = MaterialTheme.typography.bodyLarge)
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("Before you continue", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("• Your password is entered only on DeepSeek’s page; Kino never reads it.")
                        Text("• Kino captures only DeepSeek’s signed-in session headers after login.")
                        Text("• The session is encrypted with Android Keystore and stays on this device.")
                        Text("• You can disconnect any time in Settings. DeepSeek may ask you to sign in again when the session expires.")
                    }
                }
                Text("Only requests to chat.deepseek.com are inspected. Kino does not add a JavaScript bridge to the page.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                captureMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                Button(onClick = { showBrowser = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.OpenInBrowser, contentDescription = null)
                    Text("Continue to DeepSeek", modifier = Modifier.padding(start = 9.dp))
                }
            }
        } else {
            Text("Sign in on the page below. Kino will show Connected when the session is ready.", modifier = Modifier.padding(horizontal = 18.dp, vertical = 5.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            DeepSeekWebView(
                modifier = Modifier.weight(1f),
                onCapture = { credentials ->
                    captureMessage = if (onCredentials(credentials)) "Session captured and saved securely." else "Kino could not save this session securely. Go back and try again."
                },
                onStatus = { captureMessage = it },
            )
            captureMessage?.let {
                Text(it, modifier = Modifier.padding(horizontal = 18.dp, vertical = 5.dp), style = MaterialTheme.typography.bodySmall, color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (connected) {
                Text("Connected to DeepSeek", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
private fun DeepSeekWebView(
    modifier: Modifier,
    onCapture: (DeepSeekCredentials) -> Unit,
    onStatus: (String) -> Unit,
) {
    val context = LocalContext.current
    val delivered = remember { AtomicBoolean(false) }
    val captureLock = remember { Any() }
    val collected = remember { mutableMapOf<String, String>() }
    AndroidView(
        modifier = modifier,
        factory = {
            CookieManager.getInstance().setAcceptCookie(true)
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

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
                            val required = listOf("token", "waf_token", "hif_dliq", "hif_leim")
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
                        if (url?.startsWith("https://chat.deepseek.com") == true && !delivered.get()) {
                            onStatus("DeepSeek is open. Finish signing in, then wait for Kino to capture the session.")
                        }
                    }
                }
                loadUrl("https://chat.deepseek.com/")
            }
        },
        update = { },
        onRelease = { webView -> webView.stopLoading(); webView.destroy() },
    )
}

private fun parseCookies(header: String): Map<String, String> = header.split(';').mapNotNull { part ->
    val separator = part.indexOf('=')
    if (separator <= 0) null else part.substring(0, separator).trim() to part.substring(separator + 1).trim()
}.toMap()
