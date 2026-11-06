package com.teamshryne.kino.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets.Companion.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Terminal
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teamshryne.kino.domain.model.KinoMessage
import com.teamshryne.kino.domain.model.MessageRole
import com.teamshryne.kino.ui.components.EmptyPanel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    title: String,
    messages: List<KinoMessage>,
    showToolDetails: Boolean,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onSend: (String) -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val rows = remember(messages) { groupToolActivity(messages) }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back") } },
                title = { Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, "Chat actions") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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
        bottomBar = { Composer(draft, { draft = it }, { if (draft.isNotBlank()) { onSend(draft); draft = "" } }) },
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
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is TimelineRow.Message -> when (row.message.role) {
                            MessageRole.USER -> UserMessage(row.message.text)
                            MessageRole.ASSISTANT -> AssistantMessage(row.message.text)
                            MessageRole.TOOL -> Unit
                        }
                        is TimelineRow.WorkGroup -> ToolActivityBlock(row.items, showToolDetails)
                    }
                }
            }
        }
    }
}

@Composable
private fun UserMessage(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            text,
            modifier = Modifier.fillMaxWidth(0.88f)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 5.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 15.dp, vertical = 11.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun AssistantMessage(text: String) {
    Text(text, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge, lineHeight = 25.sp)
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
    val work = mutableListOf<KinoMessage>()
    fun flush() {
        if (work.isNotEmpty()) {
            rows += TimelineRow.WorkGroup(work.toList())
            work.clear()
        }
    }
    messages.forEach { message ->
        if (message.role == MessageRole.TOOL) work += message
        else {
            flush()
            rows += TimelineRow.Message(message)
        }
    }
    flush()
    return rows
}

@Composable
private fun ToolActivityBlock(items: List<KinoMessage>, showDetails: Boolean) {
    var expanded by rememberSaveable(items.first().id) { mutableStateOf(showDetails) }
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
                Icon(Icons.Rounded.Terminal, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(17.dp))
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
                        if (!message.tool?.detail.isNullOrBlank()) {
                            Spacer(Modifier.height(3.dp))
                            Text(message.tool?.detail.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 17.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Composer(value: String, onChange: (String) -> Unit, onSend: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)),
        ) {
            Column(Modifier.padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 7.dp)) {
                TextField(
                    value = value,
                    onValueChange = onChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Message Kino…") },
                    minLines = 1,
                    maxLines = 6,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
                    keyboardActions = KeyboardActions(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("DeepSeek", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = onSend,
                        enabled = value.isNotBlank(),
                        modifier = Modifier.size(38.dp).clip(CircleShape).background(if (value.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                    ) { Icon(Icons.Rounded.ArrowUpward, "Send", tint = if (value.isNotBlank()) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(19.dp)) }
                }
            }
        }
    }
}
