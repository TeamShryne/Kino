package com.teamshryne.kino.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.teamshryne.kino.domain.model.KinoChat
import com.teamshryne.kino.ui.components.EmptyPanel
import com.teamshryne.kino.ui.components.KinoGlyphTile
import com.teamshryne.kino.ui.components.KinoHeader
import com.teamshryne.kino.ui.components.KinoSearchField
import com.teamshryne.kino.ui.components.compactRelativeTime
import java.util.Calendar

private enum class ChatFilter(val label: String) {
    All("All"),
    Pinned("Pinned"),
    Files("Files"),
    Today("Today"),
}

@Composable
fun ChatsScreen(
    chats: List<KinoChat>,
    query: String,
    onQuery: (String) -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onPin: (String, Boolean) -> Unit,
    onNewChat: () -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf(ChatFilter.All) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingText by rememberSaveable { mutableStateOf("") }

    val visible = remember(chats, query, filter) {
        chats.filter { chat ->
            val matchesQuery = query.isBlank() ||
                chat.title.contains(query, ignoreCase = true) ||
                chat.preview.contains(query, ignoreCase = true)
            val matchesFilter = when (filter) {
                ChatFilter.All -> true
                ChatFilter.Pinned -> chat.pinned
                ChatFilter.Files -> chat.hasFiles
                ChatFilter.Today -> isToday(chat.updatedAt)
            }
            matchesQuery && matchesFilter
        }
    }

    fun startEditing(chat: KinoChat) {
        editingId = chat.id
        editingText = chat.title
    }

    fun commitEditing(id: String) {
        if (editingId != id) return
        val title = editingText.trim()
        val original = chats.firstOrNull { it.id == id }?.title
        editingId = null
        if (title.isNotEmpty() && title != original) onRename(id, title)
    }

    if (editingId != null) BackHandler { editingId = null }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    ) {
        KinoHeader { NewChatButton(onNewChat) }

        KinoSearchField(query = query, onQuery = onQuery, placeholder = "Search your chats")

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ChatFilter.entries.forEach { option ->
                FilterChip(option.label, option == filter) { filter = option }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Recent",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${visible.size} chats",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (visible.isEmpty()) {
            EmptyPanel(
                title = if (query.isBlank()) "No conversations yet" else "No matching chats",
                subtitle = if (query.isBlank()) "Start a chat to see it here." else "Try another search.",
                button = null,
                onClick = {},
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 20.dp),
            ) {
                itemsIndexed(visible, key = { _, chat -> chat.id }) { index, chat ->
                    ChatRow(
                        chat = chat,
                        editing = editingId == chat.id,
                        editingText = editingText,
                        onEditingTextChange = { editingText = it },
                        onStartEditing = { startEditing(chat) },
                        onCommitEditing = { commitEditing(chat.id) },
                        onOpen = { onOpen(chat.id) },
                        onDelete = { onDelete(chat.id) },
                        onPin = { onPin(chat.id, it) },
                    )
                    if (index < visible.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 20.dp, end = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NewChatButton(onNewChat: () -> Unit) {
    Surface(
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onNewChat),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primary,
    ) {
        Row(
            Modifier.padding(start = 13.dp, end = 15.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "New chat",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick),
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChatRow(
    chat: KinoChat,
    editing: Boolean,
    editingText: String,
    onEditingTextChange: (String) -> Unit,
    onStartEditing: () -> Unit,
    onCommitEditing: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onPin: (Boolean) -> Unit,
) {
    var menuOpen by rememberSaveable(chat.id) { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(editing) { if (editing) focusRequester.requestFocus() }

    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = !editing) { onOpen() }
            .padding(start = 20.dp, end = 8.dp, top = 13.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KinoGlyphTile(
            glyph = chatGlyph(chat),
            seed = chat.id,
            fallbackText = chat.title.trim().take(1).uppercase(),
        )
        Spacer(Modifier.width(12.dp))
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (editing) {
                BasicTextField(
                    value = editingText,
                    onValueChange = onEditingTextChange,
                    modifier = Modifier.weight(1f)
                        .focusRequester(focusRequester)
                        .onFocusChanged { if (!it.isFocused) onCommitEditing() },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleSmall.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onCommitEditing() }),
                )
            } else {
                if (chat.pinned) {
                    Icon(
                        Icons.Rounded.PushPin,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    chat.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                compactRelativeTime(chat.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = "Chat actions",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                    onClick = { menuOpen = false; onStartEditing() },
                )
                DropdownMenuItem(
                    text = { Text(if (chat.pinned) "Unpin" else "Pin") },
                    leadingIcon = { Icon(Icons.Rounded.PushPin, null) },
                    onClick = { menuOpen = false; onPin(!chat.pinned) },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}

private fun chatGlyph(chat: KinoChat): ImageVector? {
    val text = "${chat.title} ${chat.preview}".lowercase()
    return when {
        "pdf" in text -> Icons.Rounded.PictureAsPdf
        listOf("doc", "word", "ppt", "slide", "excel", "sheet", "report", "file").any { it in text } -> Icons.Rounded.Description
        listOf("code", "kotlin", "python", "javascript").any { it in text } -> Icons.Rounded.Code
        listOf("search", "browse", "web").any { it in text } -> Icons.Rounded.TravelExplore
        listOf("hello", "hey", "hi ").any { it in text } -> Icons.Rounded.ChatBubbleOutline
        else -> null
    }
}

private fun isToday(time: Long): Boolean {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = time }
    return now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
}
