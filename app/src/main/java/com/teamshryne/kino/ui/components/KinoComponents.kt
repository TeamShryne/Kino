package com.teamshryne.kino.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.absoluteValue

/** Tinted tiles behind list rows, so every tab reads like the same list style. */
private val tilePalette = listOf(
    Color(0xFF2E2447) to Color(0xFFB79CFF),
    Color(0xFF17352A) to Color(0xFF74D6A8),
    Color(0xFF1B2C48) to Color(0xFF8AB4F8),
    Color(0xFF3A2136) to Color(0xFFF49ACB),
    Color(0xFF12333A) to Color(0xFF6ED0D8),
    Color(0xFF3A2A14) to Color(0xFFE3AC6B),
)

@Composable
fun EmptyPanel(
    title: String,
    subtitle: String,
    button: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 35.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(17.dp).size(28.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (button != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onClick, shape = RoundedCornerShape(50)) { Text(button) }
        }
    }
}

/**
 * Shared screen header. Both tabs lead with the app name so the two screens
 * share one header, with an optional trailing action.
 */
@Composable
fun KinoHeader(action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(25.dp),
        )
        Spacer(Modifier.width(9.dp))
        Text(
            "Kino",
            style = MaterialTheme.typography.headlineMedium,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.8).sp,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
}

/** Rounded search field used by the chats and settings tabs. */
@Composable
fun KinoSearchField(query: String, onQuery: (String) -> Unit, placeholder: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 18.dp, end = 20.dp).height(46.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }, modifier = Modifier.size(26.dp)) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Clear search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
    }
}

/**
 * Rounded square tile that fronts a list row. The tint is picked from the row
 * seed so every entry keeps a stable colour.
 */
@Composable
fun KinoGlyphTile(glyph: ImageVector?, seed: String, fallbackText: String? = null) {
    val (background, foreground) = remember(seed) {
        tilePalette[seed.hashCode().absoluteValue % tilePalette.size]
    }
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(background),
        contentAlignment = Alignment.Center,
    ) {
        if (glyph != null) {
            Icon(glyph, contentDescription = null, tint = foreground, modifier = Modifier.size(19.dp))
        } else if (!fallbackText.isNullOrBlank()) {
            Text(
                fallbackText,
                color = foreground,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

fun relativeTime(time: Long): String {
    val elapsed = (System.currentTimeMillis() - time).coerceAtLeast(0)
    return when {
        elapsed < 60_000 -> "Just now"
        elapsed < 3_600_000 -> "${elapsed / 60_000} min ago"
        elapsed < 86_400_000 -> "${elapsed / 3_600_000} hr ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(time))
    }
}

/** Short timestamp for dense list rows, e.g. "4 min" or "17 hr". */
fun compactRelativeTime(time: Long): String {
    val elapsed = (System.currentTimeMillis() - time).coerceAtLeast(0)
    return when {
        elapsed < 60_000 -> "now"
        elapsed < 3_600_000 -> "${elapsed / 60_000} min"
        elapsed < 86_400_000 -> "${elapsed / 3_600_000} hr"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(time))
    }
}
