// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

data class CanonicalYawnSyncState(
    val session: String,
    val revision: Long,
    val totalCount: Int,
    val windowCount: Int,
)

enum class YawnSyncCompatibility {
    UNKNOWN,
    V2,
    LEGACY,
    INCOMPATIBLE,
}

object YawnSyncRuntimeStore {
    @Volatile
    var compatibility: YawnSyncCompatibility = YawnSyncCompatibility.UNKNOWN
        private set

    @Synchronized
    fun update(value: YawnSyncCompatibility) {
        compatibility = value
    }

    @Synchronized
    fun reset() {
        compatibility = YawnSyncCompatibility.UNKNOWN
    }
}

/** Authenticated, session-scoped Watch client for the ESP yawn broker V2. */
class YawnSyncClient(
    context: Context,
    private val onCanonicalState: (CanonicalYawnSyncState, Long?) -> Unit,
    private val onUnauthorized: () -> Unit,
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "focusmate-yawn-sync").apply { isDaemon = true }
    },
) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var running = false
    // BLE is negotiated first. HTTP is enabled only after the BLE path is
    // explicitly unavailable/unsupported, avoiding a wasted Wi-Fi wakeup on
    // every healthy service start.
    private var fallbackEnabled = false
    private var scheduled = false
    private var endpoint: LocalFrameAccessEndpoint? = null
    private var session: ActiveStudySession? = null
    private var joinedKey: String? = null
    private var retryAttempt = 0

    fun start() {
        synchronized(lock) { running = true }
        schedule(0L)
    }

    fun updateEndpoint(value: LocalFrameAccessEndpoint?) {
        synchronized(lock) {
            if (endpoint?.bootIdHex != value?.bootIdHex || endpoint?.tokenHex != value?.tokenHex) joinedKey = null
            endpoint = value
        }
        if (value == null) YawnSyncRuntimeStore.update(YawnSyncCompatibility.UNKNOWN)
        schedule(0L)
    }

    fun updateSession(value: ActiveStudySession?) {
        synchronized(lock) {
            if (session?.yawnSyncSessionId != value?.yawnSyncSessionId) joinedKey = null
            session = value
        }
        schedule(0L)
    }

    fun wake() {
        schedule(0L)
    }

    /** Keep HTTP dormant while the same broker is reachable through BLE V2. */
    fun setFallbackEnabled(enabled: Boolean) {
        val changed = synchronized(lock) {
            if (fallbackEnabled == enabled) false else {
                fallbackEnabled = enabled
                if (!enabled) joinedKey = null
                true
            }
        }
        if (changed && enabled) schedule(0L)
    }

    fun stop(closeSession: Boolean) {
        val finalRequest = synchronized(lock) {
            val value = if (closeSession) endpoint?.takeIf { it.usable }?.let { access ->
                session?.let { active -> access to active.yawnSyncSessionId }
            } else null
            running = false
            joinedKey = null
            value
        }
        if (!executor.isShutdown && finalRequest != null) {
            runCatching {
                executor.execute {
                    val network = wifiNetwork() ?: return@execute
                    runCatching {
                        call(network, finalRequest.first, "/api/watch/yawn/session", "POST", JSONObject().apply {
                            put("schema", 2)
                            put("action", "end")
                            put("session", finalRequest.second)
                        })
                    }
                }
            }
            executor.shutdown()
        } else {
            executor.shutdownNow()
        }
        YawnSyncRuntimeStore.reset()
    }

    private fun schedule(delayMs: Long) {
        val shouldSchedule = synchronized(lock) {
            if (running && fallbackEnabled && !scheduled && !executor.isShutdown) {
                true.also { scheduled = true }
            } else false
        }
        if (!shouldSchedule) return
        runCatching {
            executor.schedule({
                val next = runOnce()
                synchronized(lock) { scheduled = false }
                if (next != null) schedule(next)
            }, delayMs, TimeUnit.MILLISECONDS)
        }.onFailure { synchronized(lock) { scheduled = false } }
    }

    private fun runOnce(): Long? {
        val request = synchronized(lock) {
            if (!running || !fallbackEnabled) return null
            val currentEndpoint = endpoint
            val currentSession = session
            if (currentEndpoint == null || !currentEndpoint.usable || currentSession == null) null else {
                Request(currentEndpoint, currentSession, joinedKey == joinKey(currentEndpoint, currentSession))
            }
        } ?: return IDLE_RETRY_MS
        val network = wifiNetwork() ?: return retryDelay()

        try {
            if (!request.joined) {
                val now = System.currentTimeMillis()
                val localAges = request.session.recentYawnEventTimesMs.mapNotNull { timestamp ->
                    (now - timestamp).takeIf { it in 0L..WINDOW_MS }?.toInt()
                }.toMutableList()
                val missingRemote = (request.session.yawnSyncWindowCount - localAges.size)
                    .coerceIn(0, MAX_RECENT_EVENTS - localAges.size)
                val remoteAge = request.session.yawnSyncObservedAtMs?.let { (now - it).coerceIn(0L, WINDOW_MS).toInt() } ?: 0
                repeat(missingRemote) { localAges += remoteAge }
                val body = JSONObject().apply {
                    put("schema", 2)
                    put("action", "resume")
                    put("session", request.session.yawnSyncSessionId)
                    put("checkpoint_total", request.session.yawnCount)
                    put("recent_event_ages_ms", JSONArray(localAges.take(MAX_RECENT_EVENTS)))
                }
                val response = call(network, request.endpoint, "/api/watch/yawn/session", "POST", body)
                if (response.status == HttpURLConnection.HTTP_NOT_FOUND) {
                    YawnSyncRuntimeStore.update(YawnSyncCompatibility.LEGACY)
                    return LEGACY_REPROBE_MS
                }
                handleAuth(response.status)
                require(response.status == HttpURLConnection.HTTP_OK) { "session HTTP ${response.status}" }
                parseState(response.body)?.let { onCanonicalState(it, null) }
                synchronized(lock) { joinedKey = joinKey(request.endpoint, request.session) }
                retryAttempt = 0
                YawnSyncRuntimeStore.update(YawnSyncCompatibility.V2)
                return 0L
            }

            val latest = synchronized(lock) { session }
            val event = latest?.pendingYawnSyncEvents?.firstOrNull()
            val response = if (event != null) {
                call(network, request.endpoint, "/api/watch/yawn/event", "POST", JSONObject().apply {
                    put("schema", 2)
                    put("session", request.session.yawnSyncSessionId)
                    put("client", request.session.yawnSyncClientId)
                    put("event", event.eventId)
                    put("frame_sequence", event.frameSequence)
                    put("observed_uptime_ms", event.observedEspUptimeMs)
                })
            } else {
                call(network, request.endpoint, "/api/watch/yawn/state", "GET", null)
            }
            handleAuth(response.status)
            if (response.status == HttpURLConnection.HTTP_NOT_FOUND) {
                synchronized(lock) { joinedKey = null }
                YawnSyncRuntimeStore.update(YawnSyncCompatibility.LEGACY)
                return LEGACY_REPROBE_MS
            }
            if (response.status == HttpURLConnection.HTTP_CONFLICT) {
                synchronized(lock) { joinedKey = null }
                return 0L
            }
            require(response.status == HttpURLConnection.HTTP_OK) { "sync HTTP ${response.status}" }
            parseState(response.body)?.let { onCanonicalState(it, event?.eventId) }
            retryAttempt = 0
            YawnSyncRuntimeStore.update(YawnSyncCompatibility.V2)
            return if (event != null) 0L else STATE_POLL_MS
        } catch (error: Exception) {
            Log.w(TAG, "yawn sync failed: ${error.message}")
            return retryDelay()
        }
    }

    private fun handleAuth(status: Int) {
        if (status == HttpURLConnection.HTTP_UNAUTHORIZED) {
            synchronized(lock) { joinedKey = null }
            onUnauthorized()
            throw IllegalStateException("credential expired")
        }
    }

    private fun retryDelay(): Long {
        val delay = (RETRY_BASE_MS shl retryAttempt.coerceAtMost(4)).coerceAtMost(RETRY_MAX_MS)
        retryAttempt++
        return delay
    }

    private fun call(
        network: Network,
        endpoint: LocalFrameAccessEndpoint,
        path: String,
        method: String,
        body: JSONObject?,
    ): Response {
        val connection = network.openConnection(endpoint.watchApiUrl(path)) as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "FocusMate ${endpoint.tokenHex}")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            Response(status, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private fun parseState(raw: String): CanonicalYawnSyncState? = runCatching {
        val json = JSONObject(raw)
        if (json.optInt("schema") != 2 || !json.optBoolean("active")) return@runCatching null
        CanonicalYawnSyncState(
            session = json.getString("session").also { require(it.matches(SESSION_PATTERN)) },
            revision = json.getLong("revision").also { require(it in 1L..UINT32_MAX) },
            totalCount = json.getInt("total_count").also { require(it in 0..1_000_000) },
            windowCount = json.getInt("window_count").also { require(it in 0..MAX_RECENT_EVENTS) },
        )
    }.getOrNull()

    private fun wifiNetwork(): Network? {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return null
        return manager.allNetworks.firstOrNull { network ->
            manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    private data class Request(
        val endpoint: LocalFrameAccessEndpoint,
        val session: ActiveStudySession,
        val joined: Boolean,
    )

    private data class Response(val status: Int, val body: String)

    private companion object {
        val SESSION_PATTERN = Regex("[0-9a-f]{32}")
        const val TAG = "FocusMateYawnSync"
        const val UINT32_MAX = 4_294_967_295L
        const val WINDOW_MS = 10L * 60L * 1_000L
        const val MAX_RECENT_EVENTS = 64
        const val CONNECT_TIMEOUT_MS = 1_500
        const val READ_TIMEOUT_MS = 1_500
        const val RETRY_BASE_MS = 1_000L
        const val RETRY_MAX_MS = 30_000L
        const val IDLE_RETRY_MS = 1_000L
        const val STATE_POLL_MS = 5_000L
        const val LEGACY_REPROBE_MS = 30_000L

        fun joinKey(endpoint: LocalFrameAccessEndpoint, session: ActiveStudySession): String =
            "${endpoint.bootIdHex}:${session.yawnSyncSessionId}"
    }
}
