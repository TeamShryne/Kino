package com.teamshryne.kino.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teamshryne.kino.domain.model.KinoMessage
import com.teamshryne.kino.domain.model.MessageRole
import com.teamshryne.kino.ui.components.EmptyPanel

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
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("DeepSeek · setup later", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreHoriz, "More") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Delete conversation") },
                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
        if (messages.isEmpty()) {
            EmptyPanel(
                title = "What would you like to talk about?",
                subtitle = "A simple chat, with useful tools when you need them.",
                button = null,
                onClick = {},
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 17.dp, end = 17.dp, top = 20.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val rows = remember(messages) { groupToolActivity(messages) }
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is TimelineRow.Message -> when (row.message.role) {
                            MessageRole.USER -> UserMessage(row.message.text)
                            MessageRole.ASSISTANT -> AssistantMessage(row.message.text)
                            MessageRole.TOOL -> Unit // Tool messages are grouped before rendering.
                        }
                        is TimelineRow.WorkGroup -> ToolActivityBlock(row.items, showToolDetails)
                    }
                }
            }
        }
        Composer(draft, { draft = it }, { if (draft.isNotBlank()) { onSend(draft); draft = "" } })
    }
}

@Composable
private fun UserMessage(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            text,
            modifier = Modifier.fillMaxWidth(0.88f).clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 13.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun AssistantMessage(text: String) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text("Kino", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(7.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, lineHeight = 25.sp)
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
    val firstId = items.first().id
    var expanded by rememberSaveable(firstId) { mutableStateOf(showDetails) }
    val heading = when (items.size) {
        1 -> items.first().text
        else -> "Worked through ${items.size} steps"
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)
            .clickable { expanded = !expanded }.padding(horizontal = 13.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Terminal, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(heading, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text("Tool activity  ·  ${items.last().tool?.status ?: "Done"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight, if (expanded) "Collapse details" else "Expand details", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(expanded) {
            Column {
                Spacer(Modifier.height(10.dp))
                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                Spacer(Modifier.height(9.dp))
                items.forEachIndexed { index, message ->
                    if (index > 0) Spacer(Modifier.height(11.dp))
                    Text(message.text, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(2.dp))
                    Text("${message.tool?.name ?: "Tool"} · ${message.tool?.status ?: "Done"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!message.tool?.detail.isNullOrBlank()) {
                        Spacer(Modifier.height(5.dp))
                        Text(message.tool?.detail.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 19.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Composer(value: String, onChange: (String) -> Unit, onSend: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(25.dp)).background(MaterialTheme.colorScheme.surface)
                .padding(start = 16.dp, top = 13.dp, bottom = 10.dp, end = 9.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            BasicTextField(
                value = value, onValueChange = onChange, modifier = Modifier.weight(1f).padding(bottom = 7.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                decorationBox = { inner -> Box {
                    if (value.isEmpty()) Text("Message Kino…", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                    inner()
                } },
            )
            IconButton(
                onClick = onSend, enabled = value.isNotBlank(),
                modifier = Modifier.size(38.dp).clip(CircleShape).background(if (value.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
            ) { Icon(Icons.Rounded.ArrowUpward, "Send", tint = if (value.isNotBlank()) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(19.dp)) }
        }
        Text("Saved on this device · provider setup comes next", modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 7.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
