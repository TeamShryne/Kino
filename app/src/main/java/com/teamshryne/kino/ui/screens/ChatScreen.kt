package com.teamshryne.kino.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.teamshryne.kino.domain.model.KinoMessage
import com.teamshryne.kino.domain.model.MessageRole
import com.teamshryne.kino.domain.model.SearchSource
import com.teamshryne.kino.domain.model.SearchSourceCodec
import com.teamshryne.kino.domain.model.ToolActivity
import com.teamshryne.kino.ui.components.MarkdownText
import com.teamshryne.kino.ui.components.StreamingMarkdownText
import com.teamshryne.kino.ui.components.EmptyPanel
import com.teamshryne.kino.data.provider.ProviderModel
import org.json.JSONObject
import android.content.Intent
import android.content.ClipData
import android.widget.Toast
import java.io.File
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    title: String,
    messages: List<KinoMessage>,
    showToolDetails: Boolean,
    tableCellMinWidth: Int,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onSend: (String) -> Unit,
    models: List<ProviderModel>,
    selectedModel: String,
    thinkingEnabled: Boolean,
    searchEnabled: Boolean,
    contextTokens: Long,
    contextLimit: Long,
    isBusy: Boolean,
    liveText: String,
    liveTools: List<KinoMessage>,
    error: String?,
    attachedFiles: List<Pair<String, String>>,
    onModelChange: (String) -> Unit,
    onThinkingChange: (Boolean) -> Unit,
    onSearchChange: (Boolean) -> Unit,
    onAttach: (List<android.net.Uri>) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onStop: () -> Unit,
    onProviderSettings: () -> Unit,
    browserOpen: Boolean,
    onToggleBrowser: () -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var selectedSource by remember { mutableStateOf<SearchSource?>(null) }
    val rows = remember(messages) { groupToolActivity(messages) }
    val listState = rememberLazyListState()
    var followBottom by remember { mutableStateOf(true) }
    // Follow rendered-line batches instead of every incoming token; this keeps
    // rapid provider chunks from repeatedly restarting the list animation.
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val approximateCharsPerLine = ((screenWidthDp - 64) / 8).coerceAtLeast(24)
    val streamLineRevision = liveText.length / approximateCharsPerLine + liveText.count { it == '\n' }
    val streamRevision = streamLineRevision + liveTools.sumOf { it.tool?.detail?.length ?: 0 } / 64
    val latestStreamRevision by rememberUpdatedState(streamRevision)

    LaunchedEffect(listState) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()
            layout.totalItemsCount == 0 || (
                lastVisible != null && lastVisible.index == layout.totalItemsCount - 1 &&
                    lastVisible.offset + lastVisible.size <= layout.viewportEndOffset + 72
                )
        }.distinctUntilChanged().collect { followBottom = it }
    }
    LaunchedEffect(messages) {
        if (messages.isNotEmpty()) {
            withFrameNanos { }
            listState.jumpToBottom()
            followBottom = true
        }
    }
    LaunchedEffect(isBusy, listState) {
        if (isBusy) {
            snapshotFlow { latestStreamRevision }
                .distinctUntilChanged()
                .sample(160)
                .collect {
                    if (followBottom) supervisorScope {
                        launch { listState.animateToBottom() }.join()
                    }
                }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back") } },
                title = { Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(onClick = onToggleBrowser) { Icon(Icons.Rounded.DesktopWindows, if (browserOpen) "Close Kino Browser" else "Open Kino Browser", tint = if (browserOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, "Chat actions") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("DeepSeek settings") },
                                leadingIcon = { Icon(Icons.Rounded.Settings, null) },
                                onClick = { menuOpen = false; onProviderSettings() },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete conversation") },
                                leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                                onClick = { menuOpen = false; onDelete() },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            Composer(
                value = draft, onChange = { draft = it },
                onSend = { if (draft.isNotBlank() && !isBusy) { onSend(draft); draft = "" } },
                models = models, selectedModel = selectedModel, thinkingEnabled = thinkingEnabled,
                searchEnabled = searchEnabled, isBusy = isBusy, attachedFiles = attachedFiles, error = error,
                onModelChange = onModelChange, onThinkingChange = onThinkingChange, onSearchChange = onSearchChange,
                onAttach = onAttach, onRemoveAttachment = onRemoveAttachment, onStop = onStop,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { insets ->
        if (messages.isEmpty()) {
            EmptyPanel(
                title = "What would you like to talk about?",
                subtitle = "",
                button = null,
                onClick = {},
                modifier = Modifier.fillMaxSize().padding(insets),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(insets),
                state = listState,
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is TimelineRow.Message -> when (row.message.role) {
                            MessageRole.USER -> UserMessage(row.message)
                            MessageRole.ASSISTANT -> AssistantMessage(row.message.text, tableCellMinWidth = tableCellMinWidth)
                            MessageRole.TOOL -> Unit
                        }
                        is TimelineRow.WorkGroup -> ToolActivityBlock(row.items, showToolDetails, tableCellMinWidth) { selectedSource = it }
                    }
                }
                liveTools.forEach { activity ->
                    item(key = "live-tool-${activity.id}") {
                        ToolActivityBlock(listOf(activity), showToolDetails, tableCellMinWidth) { selectedSource = it }
                    }
                }
                if (isBusy) item(key = "live-response") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (liveText.isNotBlank()) AssistantMessage(liveText, streaming = true, tableCellMinWidth = tableCellMinWidth)
                        if (liveText.isBlank() && liveTools.isEmpty()) Text("Thinking…", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (contextTokens > 0) item(key = "context-usage") {
                    val meter = if (contextLimit > 0) "$contextTokens / $contextLimit context tokens" else "$contextTokens context tokens"
                    Text(meter, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    selectedSource?.let { source ->
        ModalBottomSheet(
            onDismissRequest = { selectedSource = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            SearchSourceSheet(source, tableCellMinWidth)
        }
    }
}

private suspend fun LazyListState.jumpToBottom() {
    val lastIndex = layoutInfo.totalItemsCount - 1
    if (lastIndex < 0) return
    scrollToItem(lastIndex)
    withFrameNanos { }
    val last = layoutInfo.visibleItemsInfo.lastOrNull() ?: return
    val remaining = (last.offset + last.size - layoutInfo.viewportEndOffset).coerceAtLeast(0)
    if (remaining > 0) scroll { scrollBy(remaining.toFloat()) }
}

private suspend fun LazyListState.animateToBottom() {
    val lastIndex = layoutInfo.totalItemsCount - 1
    if (lastIndex < 0) return
    if (layoutInfo.visibleItemsInfo.lastOrNull()?.index != lastIndex) animateScrollToItem(lastIndex)
    withFrameNanos { }
    val last = layoutInfo.visibleItemsInfo.lastOrNull()?.takeIf { it.index == lastIndex } ?: return
    val remaining = (last.offset + last.size - layoutInfo.viewportEndOffset).coerceAtLeast(0)
    if (remaining > 0) animateScrollBy(remaining.toFloat())
}

@Composable
private fun UserMessage(message: KinoMessage) {
    val files = runCatching { JSONObject(message.metadata.orEmpty()).optJSONArray("files") }.getOrNull()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            modifier = Modifier.fillMaxWidth(0.88f)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 5.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 15.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (files != null) for (index in 0 until files.length()) {
                val file = files.optJSONObject(index) ?: continue
                Text("📎 ${file.optString("name", "Attachment")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(message.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun AssistantMessage(text: String, streaming: Boolean = false, tableCellMinWidth: Int = 112) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (streaming) StreamingMarkdownText(text, tableCellMinWidth)
        else MarkdownText(text, tableCellMinWidthDp = tableCellMinWidth)
    }
}

private sealed interface TimelineRow {
    val key: String
    data class Message(val message: KinoMessage) : TimelineRow {
        override val key: String = "message-${message.id}"
    }
    data class WorkGroup(val items: List<KinoMessage>) : TimelineRow {
        override val key: String = "work-${items.first().id}"
    }
}

private fun groupToolActivity(messages: List<KinoMessage>): List<TimelineRow> {
    val rows = mutableListOf<TimelineRow>()
    messages.forEach { message ->
        if (message.role == MessageRole.TOOL) rows += TimelineRow.WorkGroup(listOf(message))
        else rows += TimelineRow.Message(message)
    }
    return rows
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolActivityBlock(items: List<KinoMessage>, showDetails: Boolean, tableCellMinWidth: Int, onSourceClick: (SearchSource) -> Unit) {
    val context = LocalContext.current
    var expanded by rememberSaveable(items.first().id) { mutableStateOf(showDetails) }
    LaunchedEffect(showDetails) { expanded = showDetails }
    val heading = if (items.size == 1) items.first().text else "Worked through ${items.size} steps"
    Surface(
        modifier = Modifier.fillMaxWidth().border(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f),
            RoundedCornerShape(18.dp),
        ).clickable { expanded = !expanded },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (items.any { it.tool?.name == "browser" }) Icons.Rounded.DesktopWindows else Icons.Rounded.Terminal, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(9.dp))
                Text(heading, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(items.last().tool?.status ?: "Done", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(5.dp))
                Icon(if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(expanded) {
                Column {
                    Spacer(Modifier.height(9.dp))
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                    Spacer(Modifier.height(7.dp))
                    items.forEachIndexed { index, message ->
                        if (index > 0) Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(message.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text(message.tool?.name ?: "Tool", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (message.tool?.name == "search") {
                            val sources = remember(message.tool.detail) { SearchSourceCodec.decode(message.tool.detail) }
                            if (sources.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    sources.forEach { source -> SearchSourceChip(source) { onSourceClick(source) } }
                                }
                            } else {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    if (message.tool.status == "Working") "Searching the web…" else "No links were returned",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else if (message.tool?.name == "html_to_pdf") {
                            val pdf = remember(message.tool.detail) { runCatching { JSONObject(message.tool.detail) }.getOrNull() }
                            val filename = pdf?.optString("filename").orEmpty()
                            val safeFilename = filename.takeIf { it.matches(Regex("kino-[a-f0-9-]{36}\\.pdf")) }
                            Spacer(Modifier.height(3.dp))
                            if (safeFilename != null) {
                                Text(pdf?.optString("title").orEmpty().ifBlank { "PDF ready" }, style = MaterialTheme.typography.bodySmall)
                                val pdfUri = remember(safeFilename) {
                                    val file = File(context.filesDir, "pdf-exports/$safeFilename")
                                    if (file.exists()) runCatching { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file) }.getOrNull() else null
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    androidx.compose.material3.TextButton(
                                        onClick = {
                                            pdfUri?.let { uri ->
                                                val open = Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(uri, "application/pdf")
                                                    clipData = ClipData.newUri(context.contentResolver, "Kino PDF", uri)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                runCatching { context.startActivity(Intent.createChooser(open, "Open PDF")) }
                                                    .onFailure { Toast.makeText(context, "No PDF viewer is available.", Toast.LENGTH_SHORT).show() }
                                            }
                                        }, enabled = pdfUri != null,
                                    ) { Text("Open PDF") }
                                    androidx.compose.material3.TextButton(
                                        onClick = {
                                            pdfUri?.let { uri ->
                                                val share = Intent(Intent.ACTION_SEND).apply {
                                                    type = "application/pdf"
                                                    putExtra(Intent.EXTRA_STREAM, uri)
                                                    clipData = ClipData.newUri(context.contentResolver, "Kino PDF", uri)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                runCatching { context.startActivity(Intent.createChooser(share, "Share PDF")) }
                                                    .onFailure { Toast.makeText(context, "Could not open the share sheet.", Toast.LENGTH_SHORT).show() }
                                            }
                                        }, enabled = pdfUri != null,
                                    ) { Text("Share") }
                                }
                            } else {
                                Text(message.tool.detail.ifBlank { "Preparing PDF…" }, style = MaterialTheme.typography.bodySmall)
                            }
                        } else if (!message.tool?.detail.isNullOrBlank()) {
                            Spacer(Modifier.height(3.dp))
                            MarkdownText(message.tool?.detail.orEmpty(), tableCellMinWidthDp = tableCellMinWidth, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchSourceChip(source: SearchSource, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick),
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(Icons.Rounded.TravelExplore, null, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(source.siteName.ifBlank { source.title }, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SearchSourceSheet(source: SearchSource, tableCellMinWidth: Int) {
    val uriHandler = LocalUriHandler.current
    Column(
        Modifier.fillMaxWidth().fillMaxHeight(0.88f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(source.title, style = MaterialTheme.typography.titleLarge)
        if (source.siteName.isNotBlank()) Text(source.siteName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (source.url.isNotBlank()) {
            Text(
                source.url,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { runCatching { uriHandler.openUri(source.url) } }.padding(vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
        val body = source.content.ifBlank { source.snippet }
        if (body.isNotBlank()) MarkdownText(body, tableCellMinWidthDp = tableCellMinWidth)
        else Text("The search response did not include page text for this link.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Composer(
    value: String,
    onChange: (String) -> Unit,
    onSend: () -> Unit,
    models: List<ProviderModel>,
    selectedModel: String,
    thinkingEnabled: Boolean,
    searchEnabled: Boolean,
    isBusy: Boolean,
    attachedFiles: List<Pair<String, String>>,
    error: String?,
    onModelChange: (String) -> Unit,
    onThinkingChange: (Boolean) -> Unit,
    onSearchChange: (Boolean) -> Unit,
    onAttach: (List<android.net.Uri>) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onStop: () -> Unit,
) {
    val selected = models.firstOrNull { it.id == selectedModel }
    var optionsOpen by rememberSaveable { mutableStateOf(false) }
    var modelMenu by rememberSaveable { mutableStateOf(false) }
    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onAttach(uris)
    }
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)),
        ) {
            Column(
                Modifier.fillMaxWidth().animateContentSize().padding(horizontal = 9.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (error != null) Text(error, modifier = Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (attachedFiles.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        attachedFiles.forEach { (id, name) ->
                            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    IconButton(onClick = { onRemoveAttachment(id) }, modifier = Modifier.size(30.dp)) { Icon(Icons.Rounded.Close, "Remove $name", modifier = Modifier.size(15.dp)) }
                                }
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val plusRotation by animateFloatAsState(if (optionsOpen) 45f else 0f, label = "composer plus rotation")
                    IconButton(onClick = { optionsOpen = true }, enabled = !isBusy, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Rounded.Add, "More options", modifier = Modifier.size(25.dp).rotate(plusRotation), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onChange,
                        modifier = Modifier.weight(1f).heightIn(min = 40.dp, max = 144.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        minLines = 1,
                        maxLines = 6,
                        decorationBox = { innerTextField ->
                            Box(Modifier.fillMaxWidth().defaultMinSize(minHeight = 40.dp), contentAlignment = Alignment.CenterStart) {
                                if (value.isEmpty()) Text("Message Kino…", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                innerTextField()
                            }
                        },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
                    )
                    IconButton(
                        onClick = if (isBusy) onStop else onSend,
                        enabled = isBusy || value.isNotBlank(),
                        modifier = Modifier.size(40.dp).clip(CircleShape).background(if (isBusy) MaterialTheme.colorScheme.error else if (value.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                    ) { Icon(if (isBusy) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward, if (isBusy) "Stop response" else "Send", tint = if (value.isNotBlank() || isBusy) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(19.dp)) }
                }
            }
        }
    }

    if (optionsOpen) {
        ModalBottomSheet(
            onDismissRequest = { optionsOpen = false; modelMenu = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Message options", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                if (selected?.supportsThinking == true) {
                    ComposerOptionRow(Icons.Rounded.Psychology, "Thinking", "Let Kino reason before replying", thinkingEnabled) { onThinkingChange(!thinkingEnabled) }
                }
                ComposerOptionRow(Icons.Rounded.TravelExplore, "Web browsing", "Let Kino use its shared browser for this reply", searchEnabled) { onSearchChange(!searchEnabled) }
                Box {
                    ComposerOptionRow(
                        icon = Icons.Rounded.Chat,
                        title = "Model",
                        subtitle = selected?.name ?: "Choose a model",
                        selected = false,
                        trailing = { Icon(Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        onClick = { modelMenu = true },
                    )
                    DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                        models.forEach { model -> DropdownMenuItem(text = { Text(model.name) }, onClick = { modelMenu = false; onModelChange(model.id) }) }
                    }
                }
                ComposerOptionRow(
                    icon = Icons.Rounded.AttachFile,
                    title = "Add files",
                    subtitle = if (attachedFiles.isEmpty()) "Photos, documents, and more" else "${attachedFiles.size} attached",
                    selected = false,
                    trailing = { Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = {
                        optionsOpen = false
                        attachmentPicker.launch(arrayOf("*/*"))
                    },
                )
            }
        }
    }
}

@Composable
private fun ComposerOptionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            trailing?.invoke()
        }
    }
}
