// SPDX-FileCopyrightText: 2026 vietdo1201
// SPDX-License-Identifier: Apache-2.0
package vn.edu.uit.tpkd.wear.cogload

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import vn.edu.uit.tpkd.wear.cogload.protocol.FrameAccessInfoV1
import vn.edu.uit.tpkd.wear.cogload.protocol.GattProfile
import vn.edu.uit.tpkd.wear.cogload.protocol.YawnBleV2
import java.util.UUID
import kotlin.random.Random

/** Platform-only BLE central for the FocusMate GATT profile. */
@SuppressLint("MissingPermission")
// Every asynchronous entry point below checks the runtime grants. GATT calls are also
// wrapped because Android can revoke a grant between the check and the binder call.
class FaceObservationBleClient(
    context: Context,
    private val ingestor: FaceObservationIngestor,
    private val onFrameAccess: (LocalFrameAccessEndpoint?) -> Unit = {},
    private val onYawnBleState: (CanonicalYawnSyncState, Long?) -> Unit = { _, _ -> },
    private val onYawnBleSupport: (Boolean) -> Unit = {},
    private val onEspBootChanged: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private var gatt: BluetoothGatt? = null
    private var running = false
    private var scanning = false
    private var reconnectAttempt = 0
    private var servicesRequested = false
    private var receiverRegistered = false
    private var activeDeviceAddress: String? = null
    private var frameAccessReadContinuesObservation = false
    private var cacheRefreshAttempted = false
    private var knownDeviceAttempted = false
    private var connectionHealthy = false
    private var connectionStep = "kết nối"
    private var streamingRequestedAtMs = 0L
    private var lastNotificationAtMs = 0L
    private var startRecoveryAttempted = false
    private var targetRateDhz = GattProfile.NOMINAL_RATE_DHZ
    private var interactiveMode = true
    private var connectionReady = false
    private var controlWriteInFlight = false
    private var activeControlIsStart = false
    private var activeControlIsYawn = false
    private var pendingControlCommand: ByteArray? = null
    private var pendingControlIsStart = false
    private var pendingYawnCommand: ByteArray? = null
    private var frameAccessRefreshPending = false
    private var yawnSession: ActiveStudySession? = null
    private var yawnJoinedSession: String? = null
    private var yawnBleSupported: Boolean? = null
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val scanTimeout = Runnable {
        if (scanning) {
            stopScan()
            ingestor.disconnected("Không tìm thấy ESP")
            scheduleReconnect()
        }
    }
    private val reconnect = Runnable { scan() }
    private val settleConnectionPriority = Runnable {
        val current = gatt ?: return@Runnable
        if (running && interactiveMode && connectionReady) requestDesiredConnectionPriority(current, warmup = false)
    }
    private val connectTimeout = Runnable {
        val current = gatt ?: return@Runnable
        failConnection(current, "$connectionStep timeout sau ${CONNECT_TIMEOUT_MS / 1_000}s")
    }
    private val bondTimeout = Runnable {
        val current = gatt
        if (current != null) failConnection(current, "Ghép đôi timeout sau ${BOND_TIMEOUT_MS / 1_000}s")
        else {
            ingestor.disconnected("Ghép đôi timeout")
            scheduleReconnect()
        }
    }
    private val notificationWatchdog = object : Runnable {
        override fun run() {
            val current = gatt
            if (!running || current == null || streamingRequestedAtMs == 0L) return
            val now = SystemClock.elapsedRealtime()
            val lastSignal = if (lastNotificationAtMs > 0L) lastNotificationAtMs else streamingRequestedAtMs
            val silentMs = now - lastSignal
            val thresholds = bleWatchdogThresholds(targetRateDhz)
            val restartMs = thresholds.restartMs
            val reconnectMs = thresholds.reconnectMs
            when {
                silentMs >= reconnectMs -> {
                    failConnection(current, "Không có notification trong ${reconnectMs / 1_000}s")
                    return
                }
                silentMs >= restartMs && !startRecoveryAttempted -> {
                    startRecoveryAttempted = true
                    sendStart(current, recovery = true)
                }
            }
            handler.postDelayed(this, WATCHDOG_TICK_MS)
        }
    }
    private val yawnStatePoller = object : Runnable {
        override fun run() {
            if (!running) return
            val active = yawnSession
            val current = gatt
            if (active != null && current != null && connectionReady && yawnBleSupported != false) {
                if (yawnJoinedSession == active.yawnSyncSessionId) {
                    queueYawnControl(current, YawnBleV2.stateRequestCommand(active.yawnSyncSessionId))
                } else {
                    queueNextYawnCommand(current)
                }
            }
            handler.postDelayed(this, YAWN_STATE_POLL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        reconnectAttempt = 0
        cacheRefreshAttempted = false
        knownDeviceAttempted = false
        registerBondReceiver()
        scan()
        handler.removeCallbacks(yawnStatePoller)
        handler.postDelayed(yawnStatePoller, YAWN_STATE_POLL_MS)
    }

    fun stop() {
        running = false
        cancelAllTimers()
        stopScan()
        unregisterBondReceiver()
        handler.removeCallbacks(yawnStatePoller)
        val current = detachGatt()
        runCatching {
            current?.getService(SERVICE_UUID)?.getCharacteristic(CONTROL_UUID)?.let { control ->
                control.value = GattProfile.stopCommand()
                current.writeCharacteristic(control)
            }
        }
        runCatching { current?.disconnect() }
        runCatching { current?.close() }
        onFrameAccess(null)
        ingestor.disconnected("Đã dừng BLE")
    }

    fun refreshFrameAccessInfo() {
        handler.post {
            val current = gatt ?: return@post
            if (!connectionReady || controlWriteInFlight) {
                frameAccessRefreshPending = true
            } else if (running && hasConnectPermission()) {
                readFrameAccessInfo(current, continueWithObservation = false)
            }
        }
    }

    fun setPowerMode(rateDhz: Int, interactive: Boolean) {
        require(rateDhz in GattProfile.MIN_RATE_DHZ..GattProfile.MAX_RATE_DHZ)
        handler.post {
            targetRateDhz = rateDhz
            interactiveMode = interactive
            val current = gatt ?: return@post
            if (!running || !hasConnectPermission()) return@post
            requestDesiredConnectionPriority(current, warmup = interactive)
            handler.removeCallbacks(settleConnectionPriority)
            if (interactive) handler.postDelayed(settleConnectionPriority, PRIORITY_WARMUP_MS)
            if (connectionReady) sendRate(current)
        }
    }

    fun updateYawnSession(value: ActiveStudySession?) {
        handler.post {
            if (yawnSession?.yawnSyncSessionId != value?.yawnSyncSessionId) yawnJoinedSession = null
            yawnSession = value
            val current = gatt
            if (current != null && connectionReady && yawnBleSupported != false) queueNextYawnCommand(current)
        }
    }

    private fun scan() {
        if (!running) return
        handler.removeCallbacks(reconnect)
        if (!hasPermissions()) {
            ingestor.disconnected("Thiếu quyền Bluetooth")
            scheduleReconnect()
            return
        }
        val scanner = adapter?.bluetoothLeScanner
        if (adapter?.isEnabled != true || scanner == null) {
            ingestor.disconnected("Bluetooth đang tắt")
            return
        }
        ingestor.connecting()
        if (connectKnownDevice()) return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(
                if (reconnectAttempt <= 2) ScanSettings.SCAN_MODE_LOW_LATENCY
                else ScanSettings.SCAN_MODE_BALANCED,
            )
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
            .build()
        // Some Android BLE stacks can deliver a cached result from startScan() immediately.
        // Publish the state first so the first matching callback can atomically consume it.
        scanning = true
        runCatching {
            scanner.startScan(listOf(filter), settings, scanCallback)
            handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
        }.onFailure {
            scanning = false
            ingestor.disconnected("Không thể scan BLE")
            scheduleReconnect()
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!running || !scanning || !hasPermissions()) return
            stopScan()
            val device = result.device
            Log.i(TAG, "scan matched FocusMate service; bondState=${device.bondState}")
            // The encrypted Device Info read asks Android to pair. Never race createBond()
            // against connectGatt(); the bond-state receiver owns the user-waiting phase.
            connectDevice(device)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            handler.removeCallbacks(scanTimeout)
            ingestor.disconnected("BLE scan lỗi $errorCode")
            scheduleReconnect()
        }
    }

    private fun connectKnownDevice(): Boolean {
        if (knownDeviceAttempted) return false
        if (reconnectAttempt >= 2 && reconnectAttempt % 5 != 0) return false
        val bonded = runCatching { adapter?.bondedDevices.orEmpty() }.getOrDefault(emptySet())
        val remembered = preferences.getString(PREF_DEVICE_ADDRESS, null)
            ?.let { address -> bonded.firstOrNull { it.address == address } }
        val candidates = bonded.filter { device ->
            runCatching { device.name?.startsWith(DEVICE_NAME_PREFIX) == true }.getOrDefault(false)
        }
        val selected = remembered ?: candidates.singleOrNull() ?: return false
        knownDeviceAttempted = true
        Log.i(TAG, "reconnect using bonded FocusMate device")
        connectDevice(selected)
        return true
    }

    private fun connectDevice(device: BluetoothDevice) {
        if (!running || !hasConnectPermission()) {
            ingestor.disconnected("Thiếu quyền Bluetooth")
            return
        }
        closeCurrentGatt()
        activeDeviceAddress = device.address
        servicesRequested = false
        connectionHealthy = false
        streamingRequestedAtMs = 0L
        lastNotificationAtMs = 0L
        startRecoveryAttempted = false
        connectionReady = false
        controlWriteInFlight = false
        activeControlIsStart = false
        activeControlIsYawn = false
        pendingControlCommand = null
        pendingControlIsStart = false
        pendingYawnCommand = null
        frameAccessRefreshPending = false
        yawnJoinedSession = null
        yawnBleSupported = null
        runCatching {
            gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            armConnectTimeout("Kết nối GATT")
        }.onFailure {
            Log.w(TAG, "connectGatt failed", it)
            ingestor.disconnected("Không thể kết nối GATT")
            scheduleReconnect()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(client: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== client) {
                Log.w(TAG, "ignoring stale GATT callback status=$status state=$newState")
                runCatching { client.close() }
                return
            }
            if (!running || !hasConnectPermission()) {
                permissionRevoked(client)
                return
            }
            Log.i(TAG, "connection status=$status state=$newState")
            runCatching {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    requestDesiredConnectionPriority(client, warmup = true)
                    armConnectTimeout("Thương lượng MTU")
                    if (!client.requestMtu(GattProfile.PREFERRED_MTU)) requestServices(client)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    detachAndClose(client)
                    ingestor.disconnected("GATT ngắt kết nối ($status)")
                    scheduleReconnect()
                }
            }.onFailure {
                Log.w(TAG, "GATT state callback failed", it)
                failConnection(client, "Không thể tiếp tục GATT")
            }
        }

        override fun onMtuChanged(client: BluetoothGatt, mtu: Int, status: Int) {
            if (gatt !== client) return
            if (!running || !hasConnectPermission()) {
                permissionRevoked(client)
                return
            }
            Log.i(TAG, "MTU negotiated=$mtu status=$status")
            ingestor.onMtuChanged(if (status == BluetoothGatt.GATT_SUCCESS) mtu else GattProfile.DEFAULT_MTU)
            requestServices(client)
        }

        override fun onServicesDiscovered(client: BluetoothGatt, status: Int) {
            if (gatt !== client) return
            if (!running || !hasConnectPermission()) {
                permissionRevoked(client)
                return
            }
            Log.i(TAG, "services discovered status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) failConnection(client, "Khám phá GATT lỗi ($status)")
            else readDeviceInfo(client)
        }

        @Deprecated("API 33 callback remains required on Wear OS 4")
        override fun onCharacteristicRead(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (gatt !== client) return
            handleCharacteristicRead(client, characteristic, characteristic.value.copyOf(), status)
        }

        override fun onCharacteristicRead(
            client: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (gatt !== client) return
            if (!running || !hasConnectPermission()) {
                permissionRevoked(client)
                return
            }
            handleCharacteristicRead(client, characteristic, value.copyOf(), status)
        }

        @Deprecated("API 33 callback remains required on Wear OS 4")
        override fun onCharacteristicChanged(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (running && gatt === client && characteristic.uuid == OBSERVATION_UUID) {
                if (handleObservationNotification(characteristic.value.copyOf())) markNotificationHealthy()
            }
        }

        override fun onCharacteristicChanged(
            client: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (running && gatt === client && characteristic.uuid == OBSERVATION_UUID) {
                if (handleObservationNotification(value.copyOf())) markNotificationHealthy()
            }
        }

        override fun onDescriptorWrite(client: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (gatt !== client) return
            if (!running || !hasConnectPermission()) {
                permissionRevoked(client)
                return
            }
            if (descriptor.uuid != CCCD_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failConnection(client, "Không subscribe được observation")
                return
            }
            Log.i(TAG, "observation subscribed; sending START at ${GattProfile.NOMINAL_RATE_DHZ} dHz")
            sendStart(client, recovery = false)
        }

        override fun onCharacteristicWrite(
            client: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (gatt !== client || characteristic.uuid != CONTROL_UUID) return
            val completedStart = activeControlIsStart
            val completedYawn = activeControlIsYawn
            controlWriteInFlight = false
            activeControlIsStart = false
            activeControlIsYawn = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                if (completedYawn) {
                    if (status in YAWN_UNSUPPORTED_STATUSES) {
                        yawnBleSupported = false
                        yawnJoinedSession = null
                        pendingYawnCommand = null
                        onYawnBleSupport(false)
                        Log.i(TAG, "BLE yawn V2 unsupported status=$status; keeping HTTP fallback")
                        drainControlQueue(client)
                    } else {
                        // A busy/controller error does not mean the firmware lacks
                        // yawn V2. Keep BLE authoritative and retry from the
                        // persisted outbox instead of unnecessarily enabling HTTP.
                        Log.w(TAG, "Transient BLE yawn write failure status=$status; retrying")
                        drainControlQueue(client)
                        scheduleYawnRetry(client)
                    }
                } else {
                    failConnection(client, "Không gửi được control ($status)")
                }
                return
            }
            if (completedStart) {
                handler.removeCallbacks(connectTimeout)
                connectionReady = true
                if (streamingRequestedAtMs == 0L) streamingRequestedAtMs = SystemClock.elapsedRealtime()
                handler.removeCallbacks(notificationWatchdog)
                handler.postDelayed(notificationWatchdog, WATCHDOG_TICK_MS)
            }
            drainControlQueue(client)
            if (completedStart && !controlWriteInFlight && yawnBleSupported != false) queueNextYawnCommand(client)
        }
    }

    private fun handleCharacteristicRead(
        client: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        when (characteristic.uuid) {
            DEVICE_INFO_UUID -> handleDeviceInfo(client, characteristic, value, status)
            FRAME_ACCESS_INFO_UUID -> handleFrameAccessInfo(client, value, status)
        }
    }

    private fun handleDeviceInfo(
        client: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        if (!running || !hasConnectPermission()) {
            permissionRevoked(client)
            return
        }
        if (characteristic.uuid != DEVICE_INFO_UUID) return
        if (status == GATT_INSUFFICIENT_AUTHENTICATION || status == GATT_INSUFFICIENT_ENCRYPTION) {
            beginBonding(client)
            return
        }
        val parsed = if (status == BluetoothGatt.GATT_SUCCESS) ingestor.onDeviceInfo(value) else null
        if (parsed == null || parsed.isFailure) {
            if (client.device.bondState == BluetoothDevice.BOND_BONDING) {
                ingestor.bonding()
                return
            }
            failConnection(client, "Device Info không hợp lệ")
            return
        }
        val info = parsed.getOrThrow()
        Log.i(
            TAG,
            "DeviceInfo protocol=${info.protocolVersion} framing=${info.framingVersion} " +
                "capabilities=0x${info.capabilityBits.toUInt().toString(16)} usable=${info.usable}",
        )
        val previousBootId = preferences.getString(PREF_BOOT_ID, null)
        preferences.edit()
            .putString(PREF_DEVICE_ADDRESS, client.device.address)
            .putString(PREF_BOOT_ID, info.bootIdHex)
            .apply()
        if (previousBootId != null && previousBootId != info.bootIdHex) {
            onEspBootChanged(info.bootIdHex)
        }
        if (info.supportsLocalFrameV1) {
            readFrameAccessInfo(client, continueWithObservation = true)
        } else {
            onFrameAccess(null)
            enableObservation(client)
        }
    }

    private fun handleFrameAccessInfo(client: BluetoothGatt, value: ByteArray, status: Int) {
        val continueWithObservation = frameAccessReadContinuesObservation
        frameAccessReadContinuesObservation = false
        val info = if (status == BluetoothGatt.GATT_SUCCESS) {
            runCatching { FrameAccessInfoV1.parse(value) }.getOrNull()
        } else {
            null
        }
        val endpoint = info?.toLocalFrameAccessEndpointOrNull()
        onFrameAccess(endpoint)
        Log.i(TAG, "FrameAccess read status=$status usable=${endpoint != null}")
        if (continueWithObservation) enableObservation(client)
        else handler.removeCallbacks(connectTimeout)
    }

    private fun requestServices(client: BluetoothGatt) {
        if (gatt !== client || servicesRequested) return
        servicesRequested = true
        armConnectTimeout("Khám phá GATT")
        val started = runCatching { client.discoverServices() }.getOrDefault(false)
        if (!started) {
            servicesRequested = false
            failConnection(client, "Không thể khám phá GATT")
        }
    }

    private fun readDeviceInfo(client: BluetoothGatt) {
        if (gatt !== client) return
        val info = client.getService(SERVICE_UUID)?.getCharacteristic(DEVICE_INFO_UUID)
        armConnectTimeout("Đọc Device Info")
        val readStarted = info != null && runCatching { client.readCharacteristic(info) }.getOrDefault(false)
        if (!readStarted) failConnection(client, "Thiếu Device Info")
    }

    private fun readFrameAccessInfo(client: BluetoothGatt, continueWithObservation: Boolean) {
        if (gatt !== client) return
        frameAccessReadContinuesObservation = continueWithObservation
        armConnectTimeout("Đọc Frame Access")
        val access = client.getService(SERVICE_UUID)?.getCharacteristic(FRAME_ACCESS_INFO_UUID)
        if (access == null && continueWithObservation && !cacheRefreshAttempted) {
            cacheRefreshAttempted = true
            frameAccessReadContinuesObservation = false
            onFrameAccess(null)
            if (refreshGattCache(client)) {
                Log.w(TAG, "Frame Access absent from cached GATT database; refreshed once")
                handler.postDelayed({
                    if (gatt === client && running) {
                        detachAndClose(client)
                        ingestor.connecting()
                        reconnectAttempt = 0
                        scheduleReconnect()
                    }
                }, GATT_CACHE_SETTLE_MS)
                return
            }
            Log.w(TAG, "Frame Access absent and platform GATT cache refresh unavailable")
        }
        val readStarted = access != null && runCatching { client.readCharacteristic(access) }.getOrDefault(false)
        if (!readStarted) {
            frameAccessReadContinuesObservation = false
            onFrameAccess(null)
            if (continueWithObservation) enableObservation(client)
        }
    }

    /**
     * One-shot compatibility path for a bonded Android device that retained
     * the pre-LOCAL_FRAME_V1 attribute table. Normal connections never call it.
     */
    private fun refreshGattCache(client: BluetoothGatt): Boolean = runCatching {
        val method = client.javaClass.getMethod("refresh")
        method.invoke(client) == true
    }.onFailure { Log.w(TAG, "BluetoothGatt.refresh unavailable", it) }.getOrDefault(false)

    private fun beginBonding(client: BluetoothGatt) {
        if (gatt !== client || !hasConnectPermission()) return
        handler.removeCallbacks(connectTimeout)
        ingestor.bonding()
        handler.removeCallbacks(bondTimeout)
        handler.postDelayed(bondTimeout, BOND_TIMEOUT_MS)
        if (client.device.bondState == BluetoothDevice.BOND_NONE) {
            val started = runCatching { client.device.createBond() }.getOrDefault(false)
            if (!started) failConnection(client, "Không thể bắt đầu ghép đôi")
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!running) return
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_OFF -> {
                        handler.removeCallbacks(reconnect)
                        stopScan()
                        closeCurrentGatt()
                        ingestor.disconnected("Bluetooth đang tắt")
                    }
                    BluetoothAdapter.STATE_ON -> {
                        reconnectAttempt = 0
                        knownDeviceAttempted = false
                        handler.removeCallbacks(reconnect)
                        handler.post(reconnect)
                    }
                }
                return
            }
            if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            if (device.address != activeDeviceAddress) return
            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
            val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
            Log.i(TAG, "bond state=$state previous=$previous")
            when (state) {
                BluetoothDevice.BOND_BONDING -> {
                    handler.removeCallbacks(connectTimeout)
                    ingestor.bonding()
                    handler.removeCallbacks(bondTimeout)
                    handler.postDelayed(bondTimeout, BOND_TIMEOUT_MS)
                }
                BluetoothDevice.BOND_BONDED -> {
                    handler.removeCallbacks(bondTimeout)
                    val current = gatt
                    if (current != null) readDeviceInfo(current) else scheduleReconnect()
                }
                BluetoothDevice.BOND_NONE -> if (previous == BluetoothDevice.BOND_BONDING) {
                    val current = gatt
                    if (current != null) failConnection(current, "Ghép đôi bị từ chối")
                    else {
                        ingestor.disconnected("Ghép đôi bị từ chối")
                        scheduleReconnect()
                    }
                }
            }
        }
    }

    private fun registerBondReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED).apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(bondReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(bondReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterBondReceiver() {
        if (!receiverRegistered) return
        runCatching { appContext.unregisterReceiver(bondReceiver) }
        receiverRegistered = false
    }

    private fun enableObservation(client: BluetoothGatt) {
        val observation = client.getService(SERVICE_UUID)?.getCharacteristic(OBSERVATION_UUID)
        val cccd = observation?.getDescriptor(CCCD_UUID)
        if (observation == null || cccd == null || !client.setCharacteristicNotification(observation, true)) {
            failConnection(client, "Observation/CCCD không tồn tại")
            return
        }
        armConnectTimeout("Bật notification")
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (!client.writeDescriptor(cccd)) failConnection(client, "Không ghi được CCCD")
    }

    private fun handleObservationNotification(value: ByteArray): Boolean =
        ingestor.onNotification(value) { payload -> handleYawnBleState(payload) }

    private fun handleYawnBleState(payload: ByteArray): Boolean {
        val state = YawnBleV2.parseState(payload) ?: return false
        val active = yawnSession
        if (active == null || state.session != active.yawnSyncSessionId) {
            yawnJoinedSession = null
            gatt?.takeIf { connectionReady && yawnBleSupported != false }?.let(::queueNextYawnCommand)
            return true
        }
        yawnBleSupported = true
        yawnJoinedSession = state.session
        onYawnBleSupport(true)
        Log.i(
            TAG,
            "BLE yawn state revision=${state.revision} total=${state.totalCount} " +
                "window=${state.windowCount} ack=${state.acknowledgedEventId ?: "-"}",
        )
        onYawnBleState(
            CanonicalYawnSyncState(state.session, state.revision, state.totalCount, state.windowCount),
            state.acknowledgedEventId,
        )
        return true
    }

    private fun queueNextYawnCommand(client: BluetoothGatt) {
        val active = yawnSession ?: return
        if (!running || gatt !== client || !connectionReady || yawnBleSupported == false) return
        val command = if (yawnJoinedSession != active.yawnSyncSessionId) {
            val now = System.currentTimeMillis()
            val ages = active.recentYawnEventTimesMs.mapNotNull { timestamp ->
                (now - timestamp).takeIf { it in 0L..YawnBleV2.WINDOW_MS }
            }
            YawnBleV2.resumeCommand(active.yawnSyncSessionId, active.yawnCount, ages)
        } else {
            active.pendingYawnSyncEvents.firstOrNull()?.let { event ->
                YawnBleV2.eventCommand(
                    active.yawnSyncSessionId,
                    active.yawnSyncClientId,
                    event.eventId,
                    event.frameSequence,
                    event.observedEspUptimeMs,
                )
            } ?: return
        }
        queueYawnControl(client, command)
    }

    private fun sendStart(client: BluetoothGatt, recovery: Boolean) {
        if (gatt !== client || !running || !hasConnectPermission()) return
        queueControl(client, GattProfile.startCommand(targetRateDhz), isStart = true, recovery = recovery)
    }

    private fun sendRate(client: BluetoothGatt) {
        if (gatt !== client || !running || !hasConnectPermission()) return
        queueControl(client, GattProfile.setRateCommand(targetRateDhz), isStart = false, recovery = false)
    }

    private fun queueControl(client: BluetoothGatt, command: ByteArray, isStart: Boolean, recovery: Boolean) {
        if (controlWriteInFlight) {
            pendingControlCommand = command
            pendingControlIsStart = isStart
            return
        }
        writeControl(client, command, isStart, recovery)
    }

    private fun queueYawnControl(client: BluetoothGatt, command: ByteArray) {
        if (controlWriteInFlight || pendingControlCommand != null) {
            pendingYawnCommand = command
            return
        }
        writeControl(client, command, isStart = false, recovery = false, isYawn = true)
    }

    private fun drainControlQueue(client: BluetoothGatt) {
        if (controlWriteInFlight || gatt !== client) return
        val pending = pendingControlCommand
        if (pending != null) {
            val pendingIsStart = pendingControlIsStart
            pendingControlCommand = null
            pendingControlIsStart = false
            writeControl(client, pending, pendingIsStart, recovery = false)
            return
        }
        val yawn = pendingYawnCommand
        if (yawn != null && yawnBleSupported != false) {
            pendingYawnCommand = null
            writeControl(client, yawn, isStart = false, recovery = false, isYawn = true)
            return
        }
        if (frameAccessRefreshPending) {
            frameAccessRefreshPending = false
            readFrameAccessInfo(client, continueWithObservation = false)
        }
    }

    private fun writeControl(
        client: BluetoothGatt,
        command: ByteArray,
        isStart: Boolean,
        recovery: Boolean,
        isYawn: Boolean = false,
    ) {
        val control = client.getService(SERVICE_UUID)?.getCharacteristic(CONTROL_UUID)
        if (control == null) {
            failConnection(client, "Control characteristic không tồn tại")
            return
        }
        if (isStart && !recovery) armConnectTimeout("Gửi START")
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                client.writeCharacteristic(
                    control,
                    command,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                control.value = command
                client.writeCharacteristic(control)
            }
        }.getOrDefault(false)
        if (started) {
            controlWriteInFlight = true
            activeControlIsStart = isStart
            activeControlIsYawn = isYawn
        } else {
            if (isYawn) {
                // writeCharacteristic can return false while Android still owns a
                // previous GATT operation. Treat that as backpressure, not as an
                // unsupported protocol response.
                Log.w(TAG, "BLE yawn write was busy before enqueue; retrying")
                scheduleYawnRetry(client)
            } else {
                failConnection(client, if (recovery) "Không gửi lại được START" else "Không gửi được control")
            }
        }
    }

    private fun scheduleYawnRetry(client: BluetoothGatt) {
        handler.postDelayed({
            if (running && gatt === client && connectionReady && yawnBleSupported != false) {
                queueNextYawnCommand(client)
            }
        }, YAWN_WRITE_RETRY_MS)
    }

    private fun markNotificationHealthy() {
        lastNotificationAtMs = SystemClock.elapsedRealtime()
        startRecoveryAttempted = false
        if (!connectionHealthy) {
            connectionHealthy = true
            reconnectAttempt = 0
            knownDeviceAttempted = false
            gatt?.let { requestDesiredConnectionPriority(it, warmup = false) }
            Log.i(TAG, "BLE link healthy after first notification")
        }
    }

    private fun requestDesiredConnectionPriority(client: BluetoothGatt, warmup: Boolean) {
        val priority = when {
            warmup && interactiveMode -> BluetoothGatt.CONNECTION_PRIORITY_HIGH
            interactiveMode -> BluetoothGatt.CONNECTION_PRIORITY_BALANCED
            else -> BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
        }
        runCatching { client.requestConnectionPriority(priority) }
    }

    private fun armConnectTimeout(step: String) {
        connectionStep = step
        handler.removeCallbacks(connectTimeout)
        handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
    }

    private fun failConnection(client: BluetoothGatt, reason: String) {
        Log.w(TAG, reason)
        ingestor.disconnected(reason)
        detachAndClose(client)
        scheduleReconnect()
    }

    private fun permissionRevoked(client: BluetoothGatt) {
        detachAndClose(client)
        ingestor.disconnected("Quyền Bluetooth đã bị thu hồi")
        scheduleReconnect()
    }

    private fun closeCurrentGatt() {
        val current = detachGatt()
        runCatching { current?.disconnect() }
        runCatching { current?.close() }
    }

    private fun detachGatt(): BluetoothGatt? {
        handler.removeCallbacks(connectTimeout)
        handler.removeCallbacks(bondTimeout)
        handler.removeCallbacks(notificationWatchdog)
        handler.removeCallbacks(settleConnectionPriority)
        servicesRequested = false
        frameAccessReadContinuesObservation = false
        activeDeviceAddress = null
        connectionHealthy = false
        streamingRequestedAtMs = 0L
        lastNotificationAtMs = 0L
        startRecoveryAttempted = false
        connectionReady = false
        controlWriteInFlight = false
        activeControlIsStart = false
        activeControlIsYawn = false
        pendingControlCommand = null
        pendingControlIsStart = false
        pendingYawnCommand = null
        frameAccessRefreshPending = false
        yawnJoinedSession = null
        yawnBleSupported = null
        val current = gatt
        gatt = null
        // connectDevice() also calls detachGatt() before the first GATT exists;
        // that setup path must not wake the HTTP fallback prematurely.
        if (current != null) onYawnBleSupport(false)
        onFrameAccess(null)
        return current
    }

    private fun detachAndClose(client: BluetoothGatt) {
        if (gatt === client) detachGatt()
        runCatching { client.disconnect() }
        runCatching { client.close() }
    }

    private fun cancelAllTimers() {
        handler.removeCallbacks(scanTimeout)
        handler.removeCallbacks(reconnect)
        handler.removeCallbacks(connectTimeout)
        handler.removeCallbacks(bondTimeout)
        handler.removeCallbacks(notificationWatchdog)
        handler.removeCallbacks(settleConnectionPriority)
        handler.removeCallbacks(yawnStatePoller)
    }

    private fun scheduleReconnect() {
        if (!running) return
        val base = reconnectBaseDelayMs(reconnectAttempt)
        val jitter = (base / 5L).coerceAtLeast(1L)
        val delay = (base + Random.nextLong(-jitter, jitter + 1L)).coerceAtLeast(250L)
        reconnectAttempt++
        knownDeviceAttempted = false
        handler.removeCallbacks(reconnect)
        handler.postDelayed(reconnect, delay)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        handler.removeCallbacks(scanTimeout)
        if (hasPermissions()) runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private fun hasPermissions(): Boolean = hasScanPermission() && hasConnectPermission()

    private fun hasScanPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    private fun hasConnectPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "FocusMateBLE"
        const val DEVICE_NAME_PREFIX = "FocusMate-"
        const val PREFERENCES_NAME = "focusmate_ble"
        const val PREF_DEVICE_ADDRESS = "last_device_address"
        const val PREF_BOOT_ID = "last_boot_id"
        val SERVICE_UUID: UUID = UUID.fromString(GattProfile.SERVICE_UUID)
        val DEVICE_INFO_UUID: UUID = UUID.fromString(GattProfile.DEVICE_INFO_UUID)
        val OBSERVATION_UUID: UUID = UUID.fromString(GattProfile.FACE_OBSERVATION_UUID)
        val CONTROL_UUID: UUID = UUID.fromString(GattProfile.CONTROL_UUID)
        val FRAME_ACCESS_INFO_UUID: UUID = UUID.fromString(GattProfile.FRAME_ACCESS_INFO_UUID)
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val SCAN_TIMEOUT_MS = 8_000L
        const val CONNECT_TIMEOUT_MS = 6_000L
        const val BOND_TIMEOUT_MS = 45_000L
        const val GATT_CACHE_SETTLE_MS = 500L
        const val WATCHDOG_TICK_MS = 1_000L
        const val YAWN_STATE_POLL_MS = 5_000L
        const val YAWN_WRITE_RETRY_MS = 1_000L
        const val PRIORITY_WARMUP_MS = 3_000L
        const val GATT_INSUFFICIENT_AUTHENTICATION = 5
        const val GATT_INSUFFICIENT_ENCRYPTION = 15
        val YAWN_UNSUPPORTED_STATUSES = setOf(6, 13, 19)
    }
}
