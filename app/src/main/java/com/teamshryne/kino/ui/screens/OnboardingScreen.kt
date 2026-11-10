package com.teamshryne.kino.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.teamshryne.kino.data.libraries.LibraryState
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private enum class OnboardingArt { Welcome, Chat, Tools, Libraries, Connect, Appearance, Finish }

private data class OnboardingPage(
    val title: String,
    val subtitle: String,
    val art: OnboardingArt,
)

private val onboardingPages = listOf(
    OnboardingPage(
        "Welcome to Kino",
        "Your everyday AI chat — calm, fast, and right at home on your phone.",
        OnboardingArt.Welcome,
    ),
    OnboardingPage(
        "Chat that feels effortless",
        "Talk naturally and watch replies stream in. Every conversation stays saved on your device.",
        OnboardingArt.Chat,
    ),
    OnboardingPage(
        "Handy built-in tools",
        "Calculate, search the web, and export polished PDFs without ever leaving the chat.",
        OnboardingArt.Tools,
    ),
    OnboardingPage(
        "Install Kino Core",
        "Kino Core powers chat, search, and file support. Download it now to get started — this step is required.",
        OnboardingArt.Libraries,
    ),
    OnboardingPage(
        "Bring your DeepSeek account",
        "Sign in once to start chatting, or skip now and connect anytime from Settings.",
        OnboardingArt.Connect,
    ),
    OnboardingPage(
        "Pick your look",
        "Start in light or dark. You can switch themes whenever you like.",
        OnboardingArt.Appearance,
    ),
    OnboardingPage(
        "You're all set",
        "Everything is ready. Start a conversation and let Kino take it from there.",
        OnboardingArt.Finish,
    ),
)

private const val LIBRARIES_PAGE = 3
private const val CONNECT_PAGE = 4
private const val APPEARANCE_PAGE = 5
private const val FINISH_PAGE = 6
private val ArtSize = 270.dp

private val ArtAccents = listOf(
    Color(0xFF1B4ED8),
    Color(0xFF0EA5A5),
    Color(0xFFF59E0B),
    Color(0xFFEC4899),
    Color(0xFF8B5CF6),
)

private fun artAccent(index: Int): Color = ArtAccents[index % ArtAccents.size]

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    dark: Boolean,
    connected: Boolean,
    libraries: List<LibraryState> = emptyList(),
    libraryBusy: String? = null,
    libraryProgress: Float = 0f,
    libraryError: String? = null,
    onThemeChange: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onInstallLibrary: (String) -> Unit = {},
    onRefreshLibraries: () -> Unit = {},
    onFinish: () -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { onboardingPages.size })
    val scope = rememberCoroutineScope()
    val currentPage = pagerState.currentPage
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true; onRefreshLibraries() }
    val enterAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "onboardingEnter",
    )

    BackHandler(enabled = currentPage > 0) {
        scope.launch { pagerState.animateScrollToPage(currentPage - 1) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .graphicsLayer { alpha = enterAlpha }
    ) {
        OnboardingBackdrop(Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                beyondViewportPageCount = 1,
            ) { page ->
                OnboardingPageContent(
                    page = page,
                    pagerState = pagerState,
                    dark = dark,
                    connected = connected,
                    libraries = libraries,
                    libraryBusy = libraryBusy,
                    libraryProgress = libraryProgress,
                    libraryError = libraryError,
                    onThemeChange = onThemeChange,
                    onConnect = onConnect,
                    onInstallLibrary = onInstallLibrary,
                    onRefreshLibraries = onRefreshLibraries,
                    onContinue = { scope.launch { pagerState.animateScrollToPage(page + 1) } },
                    onFinish = onFinish,
                )
            }
            OnboardingBottomBar(
                page = currentPage,
                pageCount = onboardingPages.size,
                onSkip = { scope.launch { pagerState.animateScrollToPage(CONNECT_PAGE) } },
                onNext = { scope.launch { pagerState.animateScrollToPage(currentPage + 1) } },
            )
        }
    }
}

@Composable
private fun OnboardingPageContent(
    page: Int,
    pagerState: PagerState,
    dark: Boolean,
    connected: Boolean,
    libraries: List<LibraryState>,
    libraryBusy: String?,
    libraryProgress: Float,
    libraryError: String?,
    onThemeChange: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onInstallLibrary: (String) -> Unit,
    onRefreshLibraries: () -> Unit,
    onContinue: () -> Unit,
    onFinish: () -> Unit,
) {
    val spec = onboardingPages[page]
    val offset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
    val absOffset = abs(offset)
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    Column(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val scale = 1f - (absOffset * 0.08f).coerceIn(0f, 0.12f)
                scaleX = scale
                scaleY = scale
                alpha = 1f - (absOffset * 0.45f).coerceIn(0f, 0.65f)
            }
            .padding(horizontal = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.fillMaxWidth().weight(0.44f),
            enter = fadeIn(tween(480)) + scaleIn(initialScale = 0.82f, animationSpec = tween(480)),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val artScale = (minOf(maxWidth, maxHeight).value / ArtSize.value).coerceIn(0.55f, 1f)
                Box(Modifier.size(ArtSize * artScale)) {
                    OnboardingArt(art = spec.art, dark = dark, scale = artScale, modifier = Modifier.fillMaxSize())
                }
            }
        }
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(520, delayMillis = 90)) + slideInVertically(tween(520, delayMillis = 90)) { it / 4 },
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    spec.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    letterSpacing = (-0.3).sp,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    spec.subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.fillMaxWidth().weight(0.56f),
            enter = fadeIn(tween(560, delayMillis = 180)) + slideInVertically(tween(560, delayMillis = 180)) { it / 3 },
        ) {
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                OnboardingPageAction(
                    page = page,
                    dark = dark,
                    connected = connected,
                    libraries = libraries,
                    libraryBusy = libraryBusy,
                    libraryProgress = libraryProgress,
                    libraryError = libraryError,
                    onThemeChange = onThemeChange,
                    onConnect = onConnect,
                    onInstallLibrary = onInstallLibrary,
                    onRefreshLibraries = onRefreshLibraries,
                    onContinue = onContinue,
                    onFinish = onFinish,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun OnboardingPageAction(
    page: Int,
    dark: Boolean,
    connected: Boolean,
    libraries: List<LibraryState>,
    libraryBusy: String?,
    libraryProgress: Float,
    libraryError: String?,
    onThemeChange: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onInstallLibrary: (String) -> Unit,
    onRefreshLibraries: () -> Unit,
    onContinue: () -> Unit,
    onFinish: () -> Unit,
) {
    when (page) {
        LIBRARIES_PAGE -> LibrariesAction(
            libraries = libraries,
            busy = libraryBusy,
            progress = libraryProgress,
            error = libraryError,
            onInstall = onInstallLibrary,
            onRefresh = onRefreshLibraries,
            onContinue = onContinue,
        )
        CONNECT_PAGE -> ConnectAction(connected = connected, onConnect = onConnect, onContinue = onContinue)
        APPEARANCE_PAGE -> AppearanceAction(dark = dark, onThemeChange = onThemeChange)
        FINISH_PAGE -> FinishAction(
            coreInstalled = libraries.any { it.library.id == "kino-core" && it.installed },
            onFinish = onFinish,
        )
    }
}

@Composable
private fun ConnectAction(connected: Boolean, onConnect: () -> Unit, onContinue: () -> Unit) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (connected) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 14.dp)) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(7.dp))
                Text("Connected to DeepSeek", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
        PrimaryPillButton(
            text = if (connected) "Continue" else "Connect DeepSeek",
            icon = if (connected) Icons.Rounded.ArrowForward else Icons.Rounded.Link,
            onClick = if (connected) onContinue else onConnect,
        )
        if (!connected) {
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onContinue) { Text("Skip for now") }
        }
    }
}

@Composable
private fun AppearanceAction(dark: Boolean, onThemeChange: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ThemeCard(
                title = "Light",
                icon = Icons.Rounded.LightMode,
                selected = !dark,
                previewDark = false,
                onClick = { onThemeChange(false) },
                modifier = Modifier.weight(1f),
            )
            ThemeCard(
                title = "Dark",
                icon = Icons.Rounded.DarkMode,
                selected = dark,
                previewDark = true,
                onClick = { onThemeChange(true) },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Change it anytime from Settings → Appearance.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FinishAction(coreInstalled: Boolean, onFinish: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (coreInstalled) {
            PrimaryPillButton(
                text = "Start chatting",
                icon = Icons.Rounded.AutoAwesome,
                onClick = onFinish,
            )
        } else {
            Text("Install Kino Core first to finish onboarding.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun LibrariesAction(
    libraries: List<LibraryState>,
    busy: String?,
    progress: Float,
    error: String?,
    onInstall: (String) -> Unit,
    onRefresh: () -> Unit,
    onContinue: () -> Unit,
) {
    val core = libraries.firstOrNull { it.library.id == "kino-core" }
    val installed = core?.installed == true
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (installed) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(7.dp))
                Text("Kino Core installed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        } else {
            Text(
                "Kino Core is required for chat, search, and file support. Download it now — this step cannot be skipped.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (core != null) {
            Text(
                "${core.library.name} ${core.library.version}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (busy == "kino-core") {
                androidx.compose.material3.LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(0.7f))
            }
            if (!installed) {
                PrimaryPillButton(
                    text = if (busy == "kino-core") "Installing…" else "Download Kino Core",
                    icon = Icons.Rounded.Download,
                    onClick = { if (busy == null) onInstall("kino-core") },
                )
            }
        } else {
            Text("Loading library catalog…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onRefresh) { Text("Refresh") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
        if (installed) {
            PrimaryPillButton(
                text = "Continue",
                icon = Icons.Rounded.ArrowForward,
                onClick = onContinue,
            )
        }
    }
}

@Composable
private fun OnboardingBottomBar(
    page: Int,
    pageCount: Int,
    onSkip: () -> Unit,
    onNext: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, bottom = 14.dp)
    ) {
        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(pageCount) { index ->
                val active = index == page
                val width by animateDpAsState(
                    targetValue = if (active) 22.dp else 7.dp,
                    animationSpec = tween(240),
                    label = "progressDot",
                )
                Box(
                    Modifier
                        .size(width = width, height = 7.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                        )
                )
            }
        }
        if (page < FINISH_PAGE && page != LIBRARIES_PAGE) {
            CircularNextButton(
                onClick = onNext,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
        if (page < LIBRARIES_PAGE) {
            TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.CenterStart)) { Text("Skip") }
        }
    }
}

@Composable
private fun PrimaryPillButton(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "pillScale",
    )
    Row(
        modifier
            .fillMaxWidth()
            .height(58.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(elevation = 10.dp, shape = RoundedCornerShape(29.dp))
            .clip(RoundedCornerShape(29.dp))
            .background(MaterialTheme.colorScheme.primary)
            .border(1.5.dp, MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.3f), RoundedCornerShape(29.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(21.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text,
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun CircularNextButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "nextScale",
    )
    Box(
        modifier
            .size(58.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(elevation = 8.dp, shape = CircleShape)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .border(1.5.dp, MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.35f), CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.ArrowForward,
            contentDescription = "Continue",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(25.dp),
        )
    }
}

@Composable
private fun ThemeCard(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    previewDark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "themeScale",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(220),
        label = "themeBorder",
    )
    val cardColor = if (previewDark) Color(0xFF141417) else Color(0xFFFDFDFF)
    val onCard = if (previewDark) Color(0xFFF4F4F5) else Color(0xFF1F1F23)
    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(22.dp))
            .background(cardColor)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(22.dp),
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else onCard.copy(alpha = 0.7f),
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text(title, style = MaterialTheme.typography.labelLarge, color = onCard, modifier = Modifier.weight(1f))
            AnimatedVisibility(visible = selected, enter = scaleIn(initialScale = 0.4f) + fadeIn()) {
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        ThemePreviewLines(dark = previewDark)
    }
}

@Composable
private fun ThemePreviewLines(dark: Boolean) {
    val bubble = if (dark) Color(0xFF2A2A30) else Color(0xFFF1F1F4)
    val accent = if (dark) Color(0xFF3D5B9E) else Color(0xFFDCE6FF)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(accent))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.fillMaxWidth(0.55f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(bubble))
                Box(Modifier.fillMaxWidth(0.4f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(bubble))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.fillMaxWidth(0.62f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(accent.copy(alpha = 0.7f)))
                Box(Modifier.fillMaxWidth(0.45f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(accent.copy(alpha = 0.7f)))
            }
        }
    }
}

@Composable
private fun OnboardingArt(art: OnboardingArt, dark: Boolean, scale: Float, modifier: Modifier = Modifier) {
    when (art) {
        OnboardingArt.Welcome -> WelcomeArt(modifier, scale)
        OnboardingArt.Chat -> ChatArt(modifier, scale)
        OnboardingArt.Tools -> ToolsArt(modifier, scale)
        OnboardingArt.Libraries -> LibrariesArt(modifier, scale)
        OnboardingArt.Private -> PrivateArt(modifier, scale)
        OnboardingArt.Connect -> ConnectArt(modifier, scale)
        OnboardingArt.Appearance -> AppearanceArt(dark, modifier, scale)
        OnboardingArt.Finish -> FinishArt(modifier, scale)
    }
}

@Composable
private fun OnboardingBackdrop(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "backdrop")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(20000, easing = LinearEasing), RepeatMode.Restart),
        label = "backdropDrift",
    )
    Canvas(modifier) {
        val blobs = listOf(
            Triple(0.15f, 0.18f, 0.42f),
            Triple(0.85f, 0.25f, 0.34f),
            Triple(0.5f, 0.88f, 0.40f),
        )
        blobs.forEachIndexed { index, (x, y, radiusFraction) ->
            val angle = Math.toRadians((drift + index * 120f).toDouble())
            val radius = minOf(size.width, size.height) * radiusFraction * 0.45f
            drawCircle(
                color = if (index % 2 == 0) primary.copy(alpha = 0.04f) else artAccent(index).copy(alpha = 0.04f),
                radius = radius,
                center = Offset(
                    x = size.width * x + (radius * 0.08f * cos(angle)).toFloat(),
                    y = size.height * y + (radius * 0.08f * sin(angle)).toFloat(),
                ),
            )
        }
    }
}

@Composable
private fun WelcomeArt(modifier: Modifier = Modifier, scale: Float = 1f) {
    val primary = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "welcomeArt")
    val orbit by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Restart),
        label = "welcomeOrbit",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "welcomePulse",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = size.minDimension / 2f
            drawCircle(
                color = primary.copy(alpha = 0.22f),
                radius = baseRadius * 0.66f,
                center = center,
                style = Stroke(width = 2.dp.toPx()),
            )
            repeat(5) { index ->
                val angle = Math.toRadians((orbit + index * 72f).toDouble())
                val radius = baseRadius * 0.66f
                drawCircle(
                    color = artAccent(index).copy(alpha = 0.85f),
                    radius = (4 + index % 2 * 2).dp.toPx(),
                    center = Offset(
                        x = center.x + (radius * cos(angle)).toFloat(),
                        y = center.y + (radius * sin(angle)).toFloat(),
                    ),
                )
            }
        }
        Box(
            Modifier
                .fillMaxSize(0.40f * pulse)
                .clip(CircleShape)
                .background(primary.copy(alpha = 0.14f))
                .border(2.dp, primary.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = primary,
                modifier = Modifier.fillMaxSize(0.46f),
            )
        }
    }
}

@Composable
private fun ChatArt(modifier: Modifier = Modifier, scale: Float = 1f) {
    val primary = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "chatArt")
    val float by transition.animateFloat(
        initialValue = -7f,
        targetValue = 7f,
        animationSpec = infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "chatFloat",
    )
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "chatPhase",
    )
    val assistantShape = RoundedCornerShape(
        topStart = (20f * scale).dp,
        topEnd = (20f * scale).dp,
        bottomEnd = (20f * scale).dp,
        bottomStart = (6f * scale).dp,
    )
    val userShape = RoundedCornerShape(
        topStart = (20f * scale).dp,
        topEnd = (20f * scale).dp,
        bottomStart = (20f * scale).dp,
        bottomEnd = (6f * scale).dp,
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(color = primary.copy(alpha = 0.1f), radius = size.minDimension * 0.48f, center = center)
            drawCircle(
                color = primary.copy(alpha = 0.22f),
                radius = size.minDimension * 0.48f,
                center = center,
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
        Column(
            Modifier
                .offset(x = (-54f * scale).dp, y = (-46f * scale).dp + float.dp)
                .width((150f * scale).dp)
                .clip(assistantShape)
                .background(primary.copy(alpha = 0.13f))
                .border(1.5.dp, primary.copy(alpha = 0.45f), assistantShape)
                .padding(horizontal = (14f * scale).dp, vertical = (12f * scale).dp),
            verticalArrangement = Arrangement.spacedBy((7f * scale).dp),
        ) {
            Box(Modifier.fillMaxWidth(0.8f).height(7.dp).clip(RoundedCornerShape(4.dp)).background(primary.copy(alpha = 0.45f)))
            Box(Modifier.fillMaxWidth(0.95f).height(7.dp).clip(RoundedCornerShape(4.dp)).background(primary.copy(alpha = 0.28f)))
            Box(Modifier.fillMaxWidth(0.55f).height(7.dp).clip(RoundedCornerShape(4.dp)).background(primary.copy(alpha = 0.28f)))
        }
        Column(
            Modifier
                .offset(x = (54f * scale).dp, y = (48f * scale).dp - float.dp)
                .width((130f * scale).dp)
                .clip(userShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, userShape)
                .padding(horizontal = (14f * scale).dp, vertical = (14f * scale).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(3) { index ->
                    val wave = (phase + index / 3f) % 1f
                    val alpha = 0.3f + 0.7f * (1f - abs(2f * wave - 1f))
                    Box(Modifier.size(9.dp).clip(CircleShape).background(primary.copy(alpha = alpha)))
                }
            }
        }
    }
}

@Composable
private fun ToolsArt(modifier: Modifier = Modifier, scale: Float = 1f) {
    val primary = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "toolsArt")
    val revolveAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(18000, easing = LinearEasing), RepeatMode.Restart),
        label = "toolsRevolve",
    )
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "toolsPulse",
    )
    val toolIcons = listOf(
        Icons.Rounded.Calculate,
        Icons.Rounded.Search,
        Icons.Rounded.PictureAsPdf,
        Icons.Rounded.Terminal,
        Icons.Rounded.ChatBubbleOutline,
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        toolIcons.forEachIndexed { index, icon ->
            val angle = Math.toRadians((index * 72f - 90f + revolveAngle).toDouble())
            val wave = (pulse + index / 5f) % 1f
            val iconScale = 0.88f + 0.14f * (1f - abs(2f * wave - 1f))
            Box(
                Modifier
                    .offset(
                        x = (96f * scale * cos(angle)).dp,
                        y = (96f * scale * sin(angle)).dp,
                    )
                    .size((46f * scale).dp)
                    .graphicsLayer { scaleX = iconScale; scaleY = iconScale }
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.5.dp, primary.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = artAccent(index), modifier = Modifier.size((22f * scale).dp))
            }
        }
        Box(
            Modifier
                .size((66f * scale).dp)
                .clip(CircleShape)
                .background(primary.copy(alpha = 0.14f))
                .border(2.dp, primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = primary, modifier = Modifier.size((32f * scale).dp))
        }
    }
}

@Composable
private fun LibrariesArt(modifier: Modifier = Modifier, scale: Float = 1f) {
    val primary = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "librariesArt")
    val pulse by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(1900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "librariesPulse",
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart),
        label = "librariesSpin",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(color = primary.copy(alpha = 0.1f), radius = size.minDimension * 0.46f, center = center)
            drawCircle(
                color = primary.copy(alpha = 0.22f),
                radius = size.minDimension * 0.46f,
                center = center,
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
        repeat(3) { index ->
            val angle = Math.toRadians((spin + index * 120f).toDouble())
            val radius = 96f * scale
            Box(
                Modifier
                    .offset(x = (radius * cos(angle)).dp, y = (radius * sin(angle)).dp)
                    .size((26f * scale).dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.5.dp, artAccent(index).copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null, tint = artAccent(index), modifier = Modifier.size((14f * scale).dp))
            }
        }
        Box(
            Modifier
                .fillMaxSize(0.34f * pulse)
                .clip(RoundedCornerShape(18.dp))
                .background(primary.copy(alpha = 0.14f))
                .border(2.dp, primary, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Storage, contentDescription = null, tint = primary, modifier = Modifier.fillMaxSize(0.46f))
        }
    }
}

@Composable
private fun PrivateArt(modifier: Modifier = Modifier, scale: Float = 1f) {
    val primary = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "privateArt")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "privatePulse",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            repeat(1) { index ->
                val progress = (pulse + index * 0.5f) % 1f
                drawCircle(
                    color = primary.copy(alpha = (1f - progress) * 0.2f),
                    radius = size.minDimension * 0.48f * progress,
                    center = center,
                )
            }
            drawCircle(color = primary.copy(alpha = 0.12f), radius = size.minDimension * 0.42f, center = center)
        }
        Canvas(Modifier.fillMaxSize(0.56f)) {
            val w = size.width * 0.86f
            val h = size.height * 0.92f
            val cx = size.width / 2f
            val top = size.height / 2f - h / 2f
            val path = Path().apply {
                moveTo(cx, top)
                lineTo(cx + w / 2f, top + h * 0.14f)
                lineTo(cx + w / 2f, top + h * 0.52f)
                quadraticBezierTo(cx + w / 2f, top + h * 0.86f, cx, top + h)
                quadraticBezierTo(cx - w / 2f, top + h * 0.86f, cx - w / 2f, top + h * 0.52f)
                lineTo(cx - w / 2f, top + h * 0.14f)
                close()
            }
            drawPath(path, color = primary.copy(alpha = 0.14f))
            drawPath(path, color = primary.copy(alpha = 0.85f), style = Stroke(width = 2.5.dp.toPx()))
        }
        Icon(
            Icons.Rounded.Lock,
            contentDescription = null,
            tint = primary,
            modifier = Modifier.fillMaxSize(0.19f),
        )
    }
}

@Composable
private fun ConnectArt(modifier: Modifier = Modifier, scale: Float = 1f) {
    val primary = MaterialTheme.colorScheme.primary
    val accent = artAccent(0)
    val transition = rememberInfiniteTransition(label = "connectArt")
    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "connectPulse",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(color = primary.copy(alpha = 0.1f), radius = size.minDimension * 0.46f, center = center)
            drawCircle(
                color = primary.copy(alpha = 0.25f),
                radius = size.minDimension * 0.46f,
                center = center,
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
        Box(
            Modifier
                .offset(x = (-34f * scale).dp, y = 0.dp)
                .fillMaxSize(0.41f * pulse)
                .clip(CircleShape)
                .background(primary.copy(alpha = 0.16f))
                .border(2.dp, primary, CircleShape),
        )
        Box(
            Modifier
                .offset(x = (34f * scale).dp, y = 0.dp)
                .fillMaxSize(0.41f * pulse)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.16f))
                .border(2.dp, accent, CircleShape),
        )
        Box(
            Modifier
                .size((58f * scale).dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Link, contentDescription = null, tint = primary, modifier = Modifier.size((28f * scale).dp))
        }
    }
}

@Composable
private fun AppearanceArt(dark: Boolean, modifier: Modifier = Modifier, scale: Float = 1f) {
    val primary = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "appearanceArt")
    val orbit by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(16000, easing = LinearEasing), RepeatMode.Restart),
        label = "appearanceOrbit",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension * 0.45f
            drawCircle(color = primary.copy(alpha = 0.1f), radius = radius, center = center)
            drawCircle(
                color = primary.copy(alpha = 0.22f),
                radius = radius,
                center = center,
                style = Stroke(width = 1.5.dp.toPx()),
            )
            val angle = Math.toRadians(orbit.toDouble())
            drawCircle(
                color = artAccent(0),
                radius = 6.dp.toPx(),
                center = Offset(
                    x = center.x + (radius * cos(angle)).toFloat(),
                    y = center.y + (radius * sin(angle)).toFloat(),
                ),
            )
        }
        Crossfade(targetState = dark, animationSpec = tween(420), label = "sunMoon") { isDark ->
            if (isDark) MoonArt() else SunArt()
        }
    }
}

@Composable
private fun SunArt(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize(0.43f)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * 0.27f
        repeat(8) { index ->
            val angle = Math.toRadians(index * 45.0)
            drawLine(
                color = Color(0xFFF59E0B),
                start = Offset(
                    x = center.x + (radius * 1.4f * cos(angle)).toFloat(),
                    y = center.y + (radius * 1.4f * sin(angle)).toFloat(),
                ),
                end = Offset(
                    x = center.x + (radius * 1.8f * cos(angle)).toFloat(),
                    y = center.y + (radius * 1.8f * sin(angle)).toFloat(),
                ),
                strokeWidth = 5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        drawCircle(color = Color(0xFFFBBF24), radius = radius, center = center)
        drawCircle(color = Color(0xFFF59E0B), radius = radius * 0.65f, center = center)
    }
}

@Composable
private fun MoonArt(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize(0.43f)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * 0.3f
        drawCircle(color = Color(0xFFE2E8F0), radius = radius, center = center)
        drawCircle(
            color = Color(0xFF94A3B8),
            radius = radius * 0.82f,
            center = Offset(center.x - radius * 0.38f, center.y - radius * 0.22f),
        )
    }
}

@Composable
private fun FinishArt(modifier: Modifier = Modifier, scale: Float = 1f) {
    val primary = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "finishArt")
    val burst by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "finishBurst",
    )
    val pop by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "finishPop",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxRadius = size.minDimension * 0.48f
            repeat(8) { index ->
                val angle = Math.toRadians((index * 36f).toDouble())
                val distance = maxRadius * burst
                drawCircle(
                    color = artAccent(index).copy(alpha = (1f - burst).coerceIn(0f, 1f) * 0.8f),
                    radius = (3f + (index % 3) * 2f).dp.toPx(),
                    center = Offset(
                        x = center.x + (distance * cos(angle)).toFloat(),
                        y = center.y + (distance * sin(angle)).toFloat(),
                    ),
                )
            }
        }
        Box(
            Modifier
                .fillMaxSize(0.34f * pop)
                .clip(CircleShape)
                .background(primary)
                .border(2.dp, MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.fillMaxSize(0.5f),
            )
        }
    }
}
