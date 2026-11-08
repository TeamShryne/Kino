package com.teamshryne.kino.ui

import android.app.Application
import com.teamshryne.kino.data.browser.KinoBrowserSession
import com.teamshryne.kino.data.provider.DeepSeekCoreClient
import com.teamshryne.kino.domain.model.KinoMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class ChatGeneration(val chatId: String) {
    val text = MutableStateFlow("")
    val tools = MutableStateFlow<List<KinoMessage>>(emptyList())
    val cancelled = AtomicBoolean(false)
    @Volatile var job: Job? = null
    @Volatile var liveSearchToolId: Long? = null
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
}
