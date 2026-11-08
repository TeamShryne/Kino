package com.teamshryne.kino.ui.components

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.teamshryne.kino.data.browser.BrowserUiState
import com.teamshryne.kino.data.browser.KinoBrowserSession

private class BrowserHostFrameLayout(context: Context) : FrameLayout(context) {
    var interactive = false
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = !interactive || super.onInterceptTouchEvent(event)
    override fun onTouchEvent(event: MotionEvent): Boolean = if (interactive) super.onTouchEvent(event) else true
}

@Composable
fun BrowserSessionPane(
    session: KinoBrowserSession,
    state: BrowserUiState,
    visible: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var address by rememberSaveable { mutableStateOf("") }
    val host = remember { mutableStateOf<BrowserHostFrameLayout?>(null) }
    LaunchedEffect(state.activeTabId, state.activeUrl) {
        if (!state.userHasControl) address = state.activeUrl
    }

    Box(modifier.fillMaxSize().alpha(if (visible) 1f else 0f)) {
        AndroidView(
            modifier = Modifier.fillMaxSize().then(if (visible) Modifier.padding(top = 216.dp) else Modifier),
            factory = { context ->
                BrowserHostFrameLayout(context).also { container ->
                    host.value = container
                    session.attach(context, container)
                }
            },
            update = { container ->
                container.interactive = visible
                host.value = container
            },
        )
        LaunchedEffect(state.activeTabId) { host.value?.let(session::renderInto) }

        if (visible) {
            Surface(Modifier.fillMaxWidth().align(Alignment.TopCenter), color = MaterialTheme.colorScheme.background) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(Icons.Rounded.DesktopWindows, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Kino Browser", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            Text(
                                when {
                                    state.userHasControl -> "You have control · Kino is waiting"
                                    state.loading -> "Kino is browsing · ${state.progress}%"
                                    else -> "Shared with this chat"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        TextButton(onClick = { session.setUserControl(!state.userHasControl) }) {
                            Text(if (state.userHasControl) "Return to Kino" else "Take control")
                        }
                        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close browser") }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        IconButton(onClick = session::goBack, enabled = state.canGoBack, modifier = Modifier.size(38.dp)) { Icon(Icons.Rounded.ArrowBack, "Back") }
                        IconButton(onClick = session::goForward, enabled = state.canGoForward, modifier = Modifier.size(38.dp)) { Icon(Icons.Rounded.ArrowForward, "Forward") }
                        IconButton(onClick = session::reload, modifier = Modifier.size(38.dp)) {
                            if (state.loading) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Rounded.Refresh, "Reload")
                        }
                        OutlinedTextField(
                            value = address,
                            onValueChange = { address = it },
                            modifier = Modifier.weight(1f).onFocusChanged { if (it.isFocused) session.setUserControl(true) },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            placeholder = { Text("https://example.com") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { session.userNavigate(address) }),
                        )
                        IconButton(onClick = { session.userNavigate(address) }, enabled = address.isNotBlank(), modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Rounded.OpenInBrowser, "Go to address")
                        }
                    }
                    if (state.tabs.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            state.tabs.forEach { tab ->
                                Surface(
                                    modifier = Modifier.clickable { session.selectUserTab(tab.id) },
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (tab.id == state.activeTabId) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                                ) {
                                    Row(
                                        Modifier.width(170.dp).padding(start = 10.dp, end = 2.dp, top = 7.dp, bottom = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(tab.title.ifBlank { "New tab" }, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        if (state.tabs.size > 1) IconButton(onClick = { session.closeUserTab(tab.id) }, modifier = Modifier.size(26.dp)) { Icon(Icons.Rounded.Close, "Close tab", modifier = Modifier.size(14.dp)) }
                                    }
                                }
                            }
                            IconButton(onClick = session::newUserTab, enabled = state.tabs.size < KinoBrowserSession.MAX_TABS, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.Add, "New tab") }
                        }
                    }
                    if (state.loading) LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) }
                    if (state.userHasControl) {
                        Text("Sensitive fields such as passwords are never sent to the model. Complete verification here, then return control to Kino.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp))
                    }
                }
            }
        }
    }
}
