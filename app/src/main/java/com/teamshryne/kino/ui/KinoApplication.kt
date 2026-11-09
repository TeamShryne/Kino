package com.teamshryne.kino.ui

import android.app.Application
import android.os.SystemClock
import com.teamshryne.kino.data.browser.KinoBrowserSession
import com.teamshryne.kino.data.provider.DeepSeekCoreClient
import com.teamshryne.kino.domain.model.KinoMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.Job
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class CompletionPacer {
    private companion object {
        const val MAX_PER_MINUTE = 8
        const val WINDOW_MS = 60_000L
        const val SAFETY_DELAY_MS = 250L
    }

    private val starts = ArrayDeque<Long>()

    suspend fun waitForSlot(cancelled: AtomicBoolean) {
        while (!cancelled.get()) {
            val waitMillis = synchronized(this) {
                val now = SystemClock.elapsedRealtime()
                while (starts.isNotEmpty() && now - starts.first() >= WINDOW_MS) {
                    starts.removeFirst()
                }
                if (starts.size < MAX_PER_MINUTE) {
                    starts.addLast(now)
                    0L
                } else {
                    WINDOW_MS - (now - starts.first()) + SAFETY_DELAY_MS
                }
            }
            if (waitMillis <= 0L) return
            delay(waitMillis)
        }
    }
}

internal class ChatGeneration(val chatId: String) {
    val text = MutableStateFlow("")
    val tools = MutableStateFlow<List<KinoMessage>>(emptyList())
    val cancelled = AtomicBoolean(false)
    @Volatile var job: Job? = null
    @Volatile var liveSearchToolId: Long? = null
    @Volatile var liveThinkingToolId: Long? = null
}

internal data class GenerationRevision(val chatId: String, val sequence: Long)

class KinoApplication : Application() {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val generations = ConcurrentHashMap<String, ChatGeneration>()
    internal val nextLiveToolId = AtomicLong(-10L)
    internal val nextGenerationRevision = AtomicLong(0L)
    internal val generationRevision = MutableStateFlow(GenerationRevision("", 0L))
    internal val browserMutex = Mutex()
    internal val serviceForeground = AtomicBoolean(false)
    @Volatile internal var activeGenerationCount: Int = 0
    internal val provider by lazy { DeepSeekCoreClient(this) }
    internal val browserSession by lazy { KinoBrowserSession() }
    internal val completionPacer = CompletionPacer()
}
