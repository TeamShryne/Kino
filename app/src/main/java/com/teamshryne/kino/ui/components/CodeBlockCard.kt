package com.teamshryne.kino.ui.components

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Divider
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teamshryne.kino.data.tools.CodeSnippetSaver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A fenced code block: a compact header with the language and two icon actions, the body in a
 * monospace font that scrolls sideways instead of wrapping.
 */
@Composable
internal fun CodeBlockCard(
    language: String,
    code: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    var pendingCode by remember { mutableStateOf<String?>(null) }

    val fileName = remember(language) { codeFileName(language) }

    val documentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri: Uri? ->
        val content = pendingCode
        pendingCode = null
        if (uri != null && content != null) {
            scope.launch {
                val saved = runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(content.toByteArray())
                        true
                    } ?: false
                }.getOrDefault(false)
                Toast.makeText(
                    context,
                    if (saved) "Saved $fileName" else "Could not save $fileName",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1600)
            copied = false
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 1.dp, bottom = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = language.ifBlank { "code" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(code))
                        copied = true
                    },
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                        contentDescription = "Copy code",
                        modifier = Modifier.size(17.dp),
                        tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = {
                        if (CodeSnippetSaver.canSaveWithoutPrompt(context)) {
                            scope.launch {
                                val saved = CodeSnippetSaver.saveToDownloads(context, fileName, code)
                                Toast.makeText(
                                    context,
                                    if (saved) "Saved $fileName to Downloads" else "Could not save $fileName",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        } else {
                            pendingCode = code
                            documentLauncher.launch(fileName)
                        }
                    },
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = "Download code",
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    text = code,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    softWrap = false,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private val CODE_EXTENSIONS = mapOf(
    "bash" to "sh", "c" to "c", "c++" to "cpp", "cpp" to "cpp", "csharp" to "cs", "css" to "css",
    "diff" to "diff", "dockerfile" to "dockerfile", "go" to "go", "gradle" to "gradle",
    "graphql" to "graphql", "html" to "html", "java" to "java", "javascript" to "js", "js" to "js",
    "json" to "json", "kotlin" to "kt", "kt" to "kt", "lua" to "lua", "markdown" to "md", "md" to "md",
    "objective-c" to "m", "perl" to "pl", "php" to "php", "plaintext" to "txt", "python" to "py",
    "py" to "py", "r" to "r", "ruby" to "rb", "rust" to "rs", "scala" to "scala", "scss" to "scss",
    "sh" to "sh", "shell" to "sh", "sql" to "sql", "swift" to "swift", "text" to "txt",
    "toml" to "toml", "tsx" to "tsx", "typescript" to "ts", "ts" to "ts", "xml" to "xml",
    "yaml" to "yml", "yml" to "yml", "zsh" to "sh",
)

internal fun codeFileName(language: String): String {
    val key = language.trim().lowercase()
    val extension = CODE_EXTENSIONS[key] ?: CODE_EXTENSIONS[key.substringBefore('-')]
    return if (extension != null) "snippet.$extension" else "snippet.txt"
}
