package com.teamshryne.kino.ui.components

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.LocalContext
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

private data class SearchEngineChoice(val name: String, val template: String)

private val builtinSearchEngines = listOf(
    SearchEngineChoice("Google", "https://www.google.com/search?q=%s"),
    SearchEngineChoice("DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    SearchEngineChoice("Bing", "https://www.bing.com/search?q=%s"),
    SearchEngineChoice("Brave", "https://search.brave.com/search?q=%s"),
)

@Composable
fun BrowserSessionPane(
    session: KinoBrowserSession,
    state: BrowserUiState,
    visible: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("kino-settings", Context.MODE_PRIVATE) }
    var address by rememberSaveable { mutableStateOf("") }
    var editingAddress by rememberSaveable { mutableStateOf(false) }
    var engineMenuOpen by remember { mutableStateOf(false) }
    var customEngineDialog by remember { mutableStateOf(false) }
    var customTemplate by rememberSaveable {
        mutableStateOf(preferences.getString("browser_search_engine_template", "") ?: "")
    }
    var searchTemplate by remember {
        mutableStateOf(preferences.getString("browser_search_engine_template", builtinSearchEngines.first().template) ?: builtinSearchEngines.first().template)
    }
    val host = remember { mutableStateOf<BrowserHostFrameLayout?>(null) }
    val engineName = builtinSearchEngines.firstOrNull { it.template == searchTemplate }?.name ?: "Custom"
    val iconColors = IconButtonDefaults.iconButtonColors(
        contentColor = MaterialTheme.colorScheme.onSurface,
        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.95f),
    )

    LaunchedEffect(visible) {
        if (visible) session.ensureTab()
    }
    LaunchedEffect(state.activeTabId, state.activeUrl) {
        if (!editingAddress) address = state.activeUrl
    }

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).alpha(if (visible) 1f else 0f)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
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
                IconButton(onClick = onClose, colors = iconColors, modifier = Modifier.size(38.dp)) { Icon(Icons.Rounded.Close, "Close browser") }
            }

            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { viewContext ->
                    BrowserHostFrameLayout(viewContext).also { container ->
                        host.value = container
                        session.attach(viewContext, container)
                    }
                },
                update = { container ->
                    container.interactive = visible
                    host.value = container
                },
            )
            LaunchedEffect(state.activeTabId) { host.value?.let(session::renderInto) }

            if (state.loading) LinearProgressIndicator(
                progress = { state.progress / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
            state.message?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
            }
            if (state.userHasControl) {
                Text(
                    "Sensitive fields such as passwords stay on this device. Complete verification here, then return control to Kino.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp),
                )
            }

            if (state.tabs.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    state.tabs.forEach { tab ->
                        Surface(
                            modifier = Modifier.clickable { editingAddress = false; session.selectUserTab(tab.id) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (tab.id == state.activeTabId) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Row(
                                Modifier.width(160.dp).padding(start = 10.dp, end = 2.dp, top = 5.dp, bottom = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(tab.title.ifBlank { "New tab" }, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                if (state.tabs.size > 1) IconButton(onClick = { editingAddress = false; session.closeUserTab(tab.id) }, colors = iconColors, modifier = Modifier.size(26.dp)) { Icon(Icons.Rounded.Close, "Close tab", modifier = Modifier.size(14.dp)) }
                            }
                        }
                    }
                    IconButton(onClick = { editingAddress = false; session.newUserTab(); address = "" }, enabled = state.tabs.size < KinoBrowserSession.MAX_TABS, colors = iconColors, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Rounded.Add, "New tab")
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(start = 6.dp, end = 8.dp, top = 3.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                IconButton(onClick = { editingAddress = false; session.goBack() }, enabled = state.canGoBack, colors = iconColors, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.ArrowBack, "Back") }
                IconButton(onClick = { editingAddress = false; session.goForward() }, enabled = state.canGoForward, colors = iconColors, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.ArrowForward, "Forward") }
                IconButton(onClick = { editingAddress = false; session.reload() }, colors = iconColors, modifier = Modifier.size(36.dp)) {
                    if (state.loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Refresh, "Reload")
                }
                Box {
                    TextButton(onClick = { engineMenuOpen = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 4.dp)) {
                        Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(17.dp))
                        Text(engineName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                        Icon(Icons.Rounded.ExpandMore, null, modifier = Modifier.size(17.dp))
                    }
                    DropdownMenu(expanded = engineMenuOpen, onDismissRequest = { engineMenuOpen = false }) {
                        builtinSearchEngines.forEach { engine ->
                            DropdownMenuItem(
                                text = { Text(engine.name) },
                                onClick = {
                                    searchTemplate = engine.template
                                    preferences.edit().putString("browser_search_engine_template", engine.template).apply()
                                    engineMenuOpen = false
                                },
                            )
                        }
                        DropdownMenuItem(text = { Text("Custom search URL…") }, onClick = {
                            customTemplate = if (engineName == "Custom") searchTemplate else "https://www.google.com/search?q=%s"
                            customEngineDialog = true
                            engineMenuOpen = false
                        })
                    }
                }
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it; editingAddress = true },
                    modifier = Modifier.weight(1f).onFocusChanged { if (it.isFocused) session.setUserControl(true) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    placeholder = { Text("Search or enter a URL", maxLines = 1) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    keyboardActions = KeyboardActions(onGo = {
                        editingAddress = false
                        session.userNavigate(address, searchTemplate)
                    }),
                )
                IconButton(
                    onClick = { editingAddress = false; session.userNavigate(address, searchTemplate) },
                    enabled = address.isNotBlank(),
                    colors = iconColors,
                    modifier = Modifier.size(38.dp),
                ) { Icon(Icons.Rounded.OpenInBrowser, "Search or open address") }
            }
        }
    }

    if (customEngineDialog) {
        AlertDialog(
            onDismissRequest = { customEngineDialog = false },
            title = { Text("Custom search engine") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter an HTTPS search URL and include %s where the encoded query should go.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = customTemplate,
                        onValueChange = { customTemplate = it },
                        singleLine = true,
                        placeholder = { Text("https://example.com/search?q=%s") },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val candidate = customTemplate.trim()
                    if (candidate.startsWith("https://") && (candidate.contains("%s") || candidate.contains("{query}"))) {
                        searchTemplate = candidate
                        preferences.edit().putString("browser_search_engine_template", candidate).apply()
                        customEngineDialog = false
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { customEngineDialog = false }) { Text("Cancel") } },
        )
    }
}
