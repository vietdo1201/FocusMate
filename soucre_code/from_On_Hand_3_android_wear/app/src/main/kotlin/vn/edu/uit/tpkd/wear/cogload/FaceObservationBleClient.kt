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
import android.util.Log
import vn.edu.uit.tpkd.wear.cogload.protocol.GattProfile
import java.util.UUID

/** Platform-only BLE central for the FocusMate GATT profile. */
@SuppressLint("MissingPermission")
// Every asynchronous entry point below checks the runtime grants. GATT calls are also
// wrapped because Android can revoke a grant between the check and the binder call.
class FaceObservationBleClient(
    context: Context,
    private val ingestor: FaceObservationIngestor,
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
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val scanTimeout = Runnable {
        if (scanning) {
            stopScan()
            ingestor.disconnected("Không tìm thấy ESP")
            scheduleReconnect()
        }
    }
    private val reconnect = Runnable { scan() }
    private val connectTimeout = Runnable {
        val current = gatt ?: return@Runnable
        failConnection(current, "GATT timeout sau ${CONNECT_TIMEOUT_MS / 1_000}s")
    }
    private val bondTimeout = Runnable {
        val current = gatt
        if (current != null) failConnection(current, "Ghép đôi timeout sau ${BOND_TIMEOUT_MS / 1_000}s")
        else {
            ingestor.disconnected("Ghép đôi timeout")
            scheduleReconnect()
        }
    }

    fun start() {
        if (running) return
        running = true
        reconnectAttempt = 0
        registerBondReceiver()
        scan()
    }

    fun stop() {
        running = false
        cancelAllTimers()
        stopScan()
        unregisterBondReceiver()
        val current = detachGatt()
        runCatching {
            current?.getService(SERVICE_UUID)?.getCharacteristic(CONTROL_UUID)?.let { control ->
                control.value = GattProfile.stopCommand()
                current.writeCharacteristic(control)
            }
        }
        runCatching { current?.disconnect() }
        runCatching { current?.close() }
        ingestor.disconnected("Đã dừng BLE")
    }

    private fun scan() {
        if (!running) return
        handler.removeCallbacks(reconnect)
        if (!hasPermissions()) {
            ingestor.disconnected("Thiếu quyền Bluetooth")
            return
        }
        val scanner = adapter?.bluetoothLeScanner
        if (adapter?.isEnabled != true || scanner == null) {
            ingestor.disconnected("Bluetooth đang tắt")
            scheduleReconnect()
            return
        }
        ingestor.connecting()
        if (connectKnownDevice()) return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
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
        val bonded = runCatching { adapter?.bondedDevices.orEmpty() }.getOrDefault(emptySet())
        val remembered = preferences.getString(PREF_DEVICE_ADDRESS, null)
            ?.let { address -> bonded.firstOrNull { it.address == address } }
        val candidates = bonded.filter { device ->
            runCatching { device.name?.startsWith(DEVICE_NAME_PREFIX) == true }.getOrDefault(false)
        }
        val selected = remembered ?: candidates.singleOrNull() ?: return false
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
        runCatching {
            gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            handler.removeCallbacks(connectTimeout)
            handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
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
                    handler.removeCallbacks(connectTimeout)
                    reconnectAttempt = 0
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
            handleDeviceInfo(client, characteristic, characteristic.value.copyOf(), status)
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
            handleDeviceInfo(client, characteristic, value.copyOf(), status)
        }

        @Deprecated("API 33 callback remains required on Wear OS 4")
        override fun onCharacteristicChanged(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (running && gatt === client && characteristic.uuid == OBSERVATION_UUID) {
                ingestor.onNotification(characteristic.value.copyOf())
            }
        }

        override fun onCharacteristicChanged(
            client: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (running && gatt === client && characteristic.uuid == OBSERVATION_UUID) {
                ingestor.onNotification(value.copyOf())
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
            client.getService(SERVICE_UUID)?.getCharacteristic(CONTROL_UUID)?.let { control ->
                control.value = GattProfile.startCommand()
                client.writeCharacteristic(control)
            }
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
        preferences.edit().putString(PREF_DEVICE_ADDRESS, client.device.address).apply()
        enableObservation(client)
    }

    private fun requestServices(client: BluetoothGatt) {
        if (gatt !== client || servicesRequested) return
        servicesRequested = true
        val started = runCatching { client.discoverServices() }.getOrDefault(false)
        if (!started) {
            servicesRequested = false
            failConnection(client, "Không thể khám phá GATT")
        }
    }

    private fun readDeviceInfo(client: BluetoothGatt) {
        if (gatt !== client) return
        val info = client.getService(SERVICE_UUID)?.getCharacteristic(DEVICE_INFO_UUID)
        val readStarted = info != null && runCatching { client.readCharacteristic(info) }.getOrDefault(false)
        if (!readStarted) failConnection(client, "Thiếu Device Info")
    }

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
            if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED || !running) return
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
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
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
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (!client.writeDescriptor(cccd)) failConnection(client, "Không ghi được CCCD")
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
    }

    private fun closeCurrentGatt() {
        val current = detachGatt()
        runCatching { current?.disconnect() }
        runCatching { current?.close() }
    }

    private fun detachGatt(): BluetoothGatt? {
        handler.removeCallbacks(connectTimeout)
        handler.removeCallbacks(bondTimeout)
        servicesRequested = false
        activeDeviceAddress = null
        val current = gatt
        gatt = null
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
    }

    private fun scheduleReconnect() {
        if (!running) return
        val delay = (RECONNECT_BASE_MS shl reconnectAttempt.coerceAtMost(4)).coerceAtMost(RECONNECT_MAX_MS)
        reconnectAttempt++
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
        val SERVICE_UUID: UUID = UUID.fromString(GattProfile.SERVICE_UUID)
        val DEVICE_INFO_UUID: UUID = UUID.fromString(GattProfile.DEVICE_INFO_UUID)
        val OBSERVATION_UUID: UUID = UUID.fromString(GattProfile.FACE_OBSERVATION_UUID)
        val CONTROL_UUID: UUID = UUID.fromString(GattProfile.CONTROL_UUID)
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val SCAN_TIMEOUT_MS = 15_000L
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val BOND_TIMEOUT_MS = 45_000L
        const val RECONNECT_BASE_MS = 1_000L
        const val RECONNECT_MAX_MS = 30_000L
        const val GATT_INSUFFICIENT_AUTHENTICATION = 5
        const val GATT_INSUFFICIENT_ENCRYPTION = 15
    }
}
