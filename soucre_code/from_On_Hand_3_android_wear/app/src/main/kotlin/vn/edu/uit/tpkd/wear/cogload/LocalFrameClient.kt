package vn.edu.uit.tpkd.wear.cogload

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import vn.edu.uit.tpkd.wear.cogload.protocol.FrameAccessInfoV1

internal fun FrameAccessInfoV1.toLocalFrameAccessEndpointOrNull(): LocalFrameAccessEndpoint? =
    takeIf(FrameAccessInfoV1::usable)?.let { access ->
        runCatching {
            LocalFrameAccessEndpoint(
                ipv4 = access.ipv4,
                port = access.httpPort,
                bootIdHex = access.bootIdHex,
                tokenHex = access.tokenHex,
                lanReady = access.lanReady,
                tokenAuthRequired = access.tokenAuthRequired,
                faceMetaV1 = access.faceMetaV1,
            )
        }.getOrNull()
    }

data class LocalFrameAccessEndpoint(
    val ipv4: String,
    val port: Int,
    val bootIdHex: String,
    val tokenHex: String,
    val lanReady: Boolean,
    val tokenAuthRequired: Boolean,
    val faceMetaV1: Boolean,
) {
    init {
        require(isLocalOnlyIpv4(ipv4)) { "IPv4 must be private RFC1918 or link-local" }
        require(port in 1..65_535)
        require(bootIdHex.matches(HEX_128)) { "boot_id must be 32 lowercase hex chars" }
        require(tokenHex.matches(HEX_128)) { "token must be 32 lowercase hex chars" }
    }

    val usable: Boolean get() = lanReady && tokenAuthRequired && faceMetaV1

    fun frameUrl(afterSequence: Long): URL {
        require(afterSequence in 0L..UINT32_MAX)
        return URL("http://$ipv4:$port/api/watch/frame?after=$afterSequence")
    }

    private companion object {
        val HEX_128 = Regex("[0-9a-f]{32}")
        const val UINT32_MAX = 4_294_967_295L

        fun isLocalOnlyIpv4(value: String): Boolean {
            val parts = value.split('.')
            if (parts.size != 4 || !parts.all { part ->
                part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) &&
                    part.toIntOrNull() in 0..255 && (part == "0" || !part.startsWith('0'))
            }) return false
            val octets = parts.map(String::toInt)
            return octets[0] == 10 ||
                (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 192 && octets[1] == 168) ||
                (octets[0] == 169 && octets[1] == 254)
        }
    }
}

data class LocalFramePacket(
    val sequence: Long,
    val observedEspUptimeMs: Long,
    val faceMetaV1: PoseFaceMetaV1,
    val jpeg: ByteArray,
    val receivedAtMonoMs: Long,
    val bootIdHex: String,
    val yawnSync: RemoteYawnSync? = null,
)

data class RemoteYawnSync(
    val sequence: Long,
    val client: Long,
    val totalCount: Int,
    val windowCount: Int,
    val observedEspUptimeMs: Long,
)

enum class LocalFrameFetchState {
    STOPPED,
    WAITING_ACCESS,
    WAITING_WIFI,
    FETCHING,
    UNAUTHORIZED,
    ERROR,
}

internal data class ParsedLocalFrame(
    val sequence: Long,
    val observedEspUptimeMs: Long,
    val faceMetaV1: PoseFaceMetaV1,
    val jpeg: ByteArray,
    val yawnSync: RemoteYawnSync?,
)

internal object LocalFrameResponseParser {
    private const val UINT32_MAX = 4_294_967_295L

    fun parse(header: (String) -> String?, contentType: String?, jpeg: ByteArray): ParsedLocalFrame {
        require(contentType?.substringBefore(';')?.trim()?.equals("image/jpeg", ignoreCase = true) == true) {
            "frame response is not image/jpeg"
        }
        require(jpeg.size in 4..MAX_JPEG_BYTES) { "invalid JPEG size" }
        require(jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "missing JPEG SOI" }
        require(jpeg[jpeg.lastIndex - 1] == 0xFF.toByte() && jpeg[jpeg.lastIndex] == 0xD9.toByte()) {
            "missing JPEG EOI"
        }
        val sequence = requireNotNull(header("X-FocusMate-Frame-Sequence")?.toLongOrNull()) {
            "missing frame sequence"
        }
        require(sequence in 0L..UINT32_MAX) { "invalid frame sequence" }
        val uptime = requireNotNull(header("X-FocusMate-Observed-Uptime-Ms")?.toLongOrNull()) {
            "missing observed uptime"
        }
        require(uptime >= 0L) { "invalid observed uptime" }
        val metaHeader = requireNotNull(header("X-FocusMate-Face-Meta-V1")) { "missing face metadata" }
        require(metaHeader.isNotBlank() && metaHeader.length <= MAX_FACE_META_CHARS) { "invalid face metadata" }
        val meta = parseFaceMetaV1(metaHeader)
        // Yawn sync is optional metadata. A truncated/malformed optional group
        // must never discard the JPEG needed by posture and mouth calibration.
        val yawnSync = runCatching {
            val syncSequence = requireNotNull(
                header("X-FocusMate-Yawn-Sequence")?.toLongOrNull(),
            )
            require(syncSequence in 1L..UINT32_MAX)
            val client = requireNotNull(header("X-FocusMate-Yawn-Client")?.toLongOrNull())
            val total = requireNotNull(header("X-FocusMate-Yawn-Total")?.toIntOrNull())
            val window = requireNotNull(header("X-FocusMate-Yawn-Window")?.toIntOrNull())
            val observed = requireNotNull(
                header("X-FocusMate-Yawn-Observed-Uptime-Ms")?.toLongOrNull(),
            )
            require(client in 1L..UINT32_MAX && total in 0..1_000_000 && window in 0..1_000 && observed >= 0L)
            RemoteYawnSync(syncSequence, client, total, window, observed)
        }.getOrNull()
        return ParsedLocalFrame(sequence, uptime, meta, jpeg, yawnSync)
    }

    internal fun parseFaceMetaV1(encoded: String): PoseFaceMetaV1 {
        require(encoded.length == FACE_META_BASE64URL_CHARS && '=' !in encoded) { "invalid FaceMetaV1 encoding" }
        val bytes = runCatching { Base64.getUrlDecoder().decode(encoded) }
            .getOrElse { throw IllegalArgumentException("invalid FaceMetaV1 base64url", it) }
        require(bytes.size == FACE_META_BYTES) { "FaceMetaV1 must decode to 32 bytes" }
        fun unit(index: Int): Double {
            val offset = index * 2
            val value = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
            return value / 65_535.0
        }
        val detectedValue = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
        require(detectedValue == 0 || detectedValue == 1) { "invalid FaceMetaV1 detected flag" }
        return PoseFaceMetaV1(
            detected = detectedValue == 1,
            confidence = unit(1),
            centerX = unit(2),
            centerY = unit(3),
            width = unit(4),
            height = unit(5),
            leftEye = PoseFacePoint(unit(6), unit(7)),
            leftMouth = PoseFacePoint(unit(8), unit(9)),
            nose = PoseFacePoint(unit(10), unit(11)),
            rightEye = PoseFacePoint(unit(12), unit(13)),
            rightMouth = PoseFacePoint(unit(14), unit(15)),
        )
    }

    const val MAX_JPEG_BYTES = 512 * 1024
    private const val MAX_FACE_META_CHARS = 512
    private const val FACE_META_BYTES = 32
    private const val FACE_META_BASE64URL_CHARS = 43
}

/** Polls the authenticated, boot-scoped local frame endpoint without persisting images. */
class LocalFrameClient(
    context: Context,
    private val monotonicMs: () -> Long = SystemClock::elapsedRealtime,
    private val onFrame: (LocalFramePacket) -> Unit,
    private val onState: (LocalFrameFetchState, String) -> Unit = { _, _ -> },
    private val onUnauthorized: () -> Unit = {},
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "focusmate-local-frame").apply { isDaemon = true }
    },
) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var running = false
    private var enabled = true
    private var endpoint: LocalFrameAccessEndpoint? = null
    private var generation = 0L
    private var lastSequence: Long? = null
    private var pollDelayMs = DEFAULT_POLL_DELAY_MS
    private var fetchScheduledOrRunning = false
    private var activeConnection: HttpURLConnection? = null

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
        }
        schedule(0L)
    }

    fun updateEndpoint(value: LocalFrameAccessEndpoint?) {
        synchronized(lock) {
            endpoint = value
            generation++
            lastSequence = null
        }
        if (value == null || !value.usable) {
            onState(LocalFrameFetchState.WAITING_ACCESS, "Frame Access chưa sẵn sàng")
        }
        schedule(0L)
    }

    fun setEnabled(value: Boolean, delayMs: Long = DEFAULT_POLL_DELAY_MS) {
        synchronized(lock) {
            enabled = value
            pollDelayMs = delayMs.coerceIn(MINIMUM_POLL_DELAY_MS, MAXIMUM_POLL_DELAY_MS)
            if (!value) activeConnection?.disconnect()
        }
        if (value) schedule(0L)
    }

    fun stop() {
        synchronized(lock) {
            running = false
            generation++
            endpoint = null
            lastSequence = null
            fetchScheduledOrRunning = false
            activeConnection?.disconnect()
            activeConnection = null
        }
        executor.shutdownNow()
        onState(LocalFrameFetchState.STOPPED, "Đã dừng frame local")
    }

    private fun schedule(delayMs: Long) {
        val shouldSchedule = synchronized(lock) {
            if (running && enabled && !fetchScheduledOrRunning) {
                fetchScheduledOrRunning = true
                true
            } else {
                false
            }
        }
        if (!shouldSchedule || executor.isShutdown) return
        runCatching {
            executor.schedule(
                {
                    val nextDelay = fetchOnce()
                    synchronized(lock) { fetchScheduledOrRunning = false }
                    if (nextDelay != null) schedule(nextDelay)
                },
                delayMs,
                TimeUnit.MILLISECONDS,
            )
        }.onFailure {
            synchronized(lock) { fetchScheduledOrRunning = false }
        }
    }

    private fun fetchOnce(): Long? {
        val request = synchronized(lock) {
            if (!running || !enabled) return null
            val value = endpoint
            if (value == null || !value.usable) null else Request(value, generation, lastSequence ?: UINT32_MAX)
        }
        if (request == null) {
            onState(LocalFrameFetchState.WAITING_ACCESS, "Đang chờ Frame Access qua BLE")
            return RETRY_DELAY_MS
        }
        val wifiNetwork = wifiNetwork()
        if (wifiNetwork == null) {
            onState(LocalFrameFetchState.WAITING_WIFI, "Đồng hồ chưa có đường Wi-Fi local")
            return RETRY_DELAY_MS
        }
        onState(LocalFrameFetchState.FETCHING, "Đang lấy frame local")
        var connection: HttpURLConnection? = null
        try {
            connection = (wifiNetwork.openConnection(request.endpoint.frameUrl(request.afterSequence)) as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = false
                requestMethod = "GET"
                setRequestProperty("Accept", "image/jpeg")
                setRequestProperty("Authorization", "FocusMate ${request.endpoint.tokenHex}")
            }
            synchronized(lock) {
                if (request.generation != generation || !running || !enabled) return null
                activeConnection = connection
            }
            when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_NO_CONTENT -> Unit
                HttpURLConnection.HTTP_UNAUTHORIZED -> {
                    synchronized(lock) {
                        if (request.generation == generation) {
                            endpoint = null
                            generation++
                            lastSequence = null
                        }
                    }
                    onState(LocalFrameFetchState.UNAUTHORIZED, "Token frame hết hiệu lực; đang đọc lại BLE")
                    onUnauthorized()
                    return null
                }
                HttpURLConnection.HTTP_OK -> {
                    val bytes = connection.inputStream.use { stream ->
                        val output = ByteArrayOutputStream(minOf(connection.contentLength.coerceAtLeast(0), 64 * 1024))
                        val buffer = ByteArray(8 * 1024)
                        var total = 0
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > LocalFrameResponseParser.MAX_JPEG_BYTES) throw IOException("JPEG exceeds limit")
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    val parsed = LocalFrameResponseParser.parse(connection::getHeaderField, connection.contentType, bytes)
                    val accepted = synchronized(lock) {
                        if (request.generation == generation && running && enabled) {
                            lastSequence = parsed.sequence
                            true
                        } else {
                            false
                        }
                    }
                    if (accepted) {
                        onFrame(
                            LocalFramePacket(
                                sequence = parsed.sequence,
                                observedEspUptimeMs = parsed.observedEspUptimeMs,
                                faceMetaV1 = parsed.faceMetaV1,
                                jpeg = parsed.jpeg,
                                receivedAtMonoMs = monotonicMs(),
                                bootIdHex = request.endpoint.bootIdHex,
                                yawnSync = parsed.yawnSync,
                            ),
                        )
                    }
                }
                else -> throw IOException("frame HTTP $status")
            }
        } catch (error: Exception) {
            if (synchronized(lock) { running && enabled && request.generation == generation }) {
                Log.w(LOG_TAG, "local frame fetch failed", error)
                // The credential is carried only in a request header, so the
                // exception message cannot contain it. Preserve the bounded
                // transport/status detail to make real-device failures
                // diagnosable instead of collapsing every case to IOException.
                val detail = error.message
                    ?.replace(Regex("[\\r\\n\\t]+"), " ")
                    ?.take(96)
                    ?.takeIf(String::isNotBlank)
                onState(
                    LocalFrameFetchState.ERROR,
                    "Frame local lỗi ${error.javaClass.simpleName}${detail?.let { ": $it" }.orEmpty()}",
                )
            }
        } finally {
            synchronized(lock) {
                if (activeConnection === connection) activeConnection = null
            }
            connection?.disconnect()
        }
        return synchronized(lock) { if (running && enabled) pollDelayMs else null }
    }

    private fun wifiNetwork(): Network? {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return null
        return manager.allNetworks.firstOrNull { network ->
            manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    private data class Request(
        val endpoint: LocalFrameAccessEndpoint,
        val generation: Long,
        val afterSequence: Long,
    )

    private companion object {
        const val UINT32_MAX = 4_294_967_295L
        const val CONNECT_TIMEOUT_MS = 1_500
        const val READ_TIMEOUT_MS = 1_500
        const val DEFAULT_POLL_DELAY_MS = 200L
        const val MINIMUM_POLL_DELAY_MS = 150L
        const val MAXIMUM_POLL_DELAY_MS = 1_000L
        const val RETRY_DELAY_MS = 1_000L
        const val LOG_TAG = "FocusMateFrame"
    }
}
