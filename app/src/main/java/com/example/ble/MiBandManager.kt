package com.example.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.example.run.RunTrackingService
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

private const val TAG = "MiBandManager"

// UUID Constants for Xiaomi / Huami Mi Band 5
val AUTH_SERVICE_UUID: UUID = UUID.fromString("0000fee1-0000-1000-8000-00805f9b34fb")
val AUTH_CHAR_UUID: UUID = UUID.fromString("00000009-0000-3512-2118-0009af100700")

// Bluetooth SIG Standard Heart Rate Service
val HR_SERVICE_UUID: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
val HR_MEASUREMENT_CHAR_UUID: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
val HR_CONTROL_POINT_CHAR_UUID: UUID = UUID.fromString("00002a39-0000-1000-8000-00805f9b34fb")

// Huami / Mi Band Activity & Battery
val ACTIVITY_SERVICE_UUID: UUID = UUID.fromString("0000fee0-0000-1000-8000-00805f9b34fb")
val STEPS_CHAR_UUID: UUID = UUID.fromString("00000007-0000-3512-2118-0009af100700")
val BATTERY_CHAR_UUID: UUID = UUID.fromString("00000006-0000-3512-2118-0009af100700")

// Standard Battery Service (SIG)
val STANDARD_BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
val STANDARD_BATTERY_CHAR_UUID: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

val CLIENT_CONFIG_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

// Huami device-event + workout characteristics (same ones Gadgetbridge listens to)
val DEVICE_EVENT_CHAR_UUID: UUID = UUID.fromString("00000010-0000-3512-2118-0009af100700")
val WORKOUT_CHAR_UUID: UUID = UUID.fromString("0000000f-0000-3512-2118-0009af100700")

/** Bluetooth SIG Immediate Alert (find device / vibrate). */
val IMMEDIATE_ALERT_SERVICE_UUID: UUID = UUID.fromString("00001802-0000-1000-8000-00805f9b34fb")
val ALERT_LEVEL_CHAR_UUID: UUID = UUID.fromString("00002a06-0000-1000-8000-00805f9b34fb")

/** Bluetooth SIG Current Time Service (sync phone clock to band). */
val CURRENT_TIME_SERVICE_UUID: UUID = UUID.fromString("00001805-0000-1000-8000-00805f9b34fb")
val CURRENT_TIME_CHAR_UUID: UUID = UUID.fromString("00002a2b-0000-1000-8000-00805f9b34fb")

/** Huami user-settings characteristic (step goal, user info). */
val USER_SETTINGS_CHAR_UUID: UUID = UUID.fromString("00000008-0000-3512-2118-0009af100700")

enum class ConnectionStatus {
  DISCONNECTED,
  SCANNING,
  CONNECTING,
  AUTHENTICATING,
  CONNECTED
}

data class DiscoveredBand(
  val device: BluetoothDevice,
  val name: String,
  val address: String,
  val rssi: Int,
  val isMiBand: Boolean = false
)

data class MiBandState(
  val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
  val deviceName: String = "",
  val deviceAddress: String = "",
  val batteryPercent: Int = -1,
  val liveHeartRate: Int = 0,
  val liveSteps: Int = 0,
  val liveDistanceMeters: Int = 0,
  val liveCalories: Int = 0,
  val lastUpdateMillis: Long = 0L,
  val errorMessage: String? = null,
  val authKey: String = "",
  val syncing: Boolean = false,
  val syncPercent: Int = 0,
  val syncMessage: String? = null,
  val lastSyncMillis: Long = 0L
)

class MiBandManager(private val context: Context) {

  private val bluetoothManager: BluetoothManager? =
    context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
  private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
  private val prefs: SharedPreferences =
    context.getSharedPreferences("kestrel_ble_prefs", Context.MODE_PRIVATE)

  private var bluetoothGatt: BluetoothGatt? = null
  private var isScanning = false
  private val handler = Handler(Looper.getMainLooper())

  // Thread-safe GATT operation queue to prevent Android status 133 / busy drops
  private val gattQueue = ConcurrentLinkedQueue<() -> Unit>()
  private var isGattBusy = false

  private val _uiState = MutableStateFlow(
    MiBandState(
      authKey = getStoredAuthKey(),
      liveSteps = if (prefs.getString("cached_steps_day", "") == todayKey()) prefs.getInt("cached_steps", 0) else 0,
      liveHeartRate = prefs.getInt("cached_hr", 0),
      batteryPercent = prefs.getInt("cached_battery", -1),
      deviceName = prefs.getString("last_name", "") ?: "",
      deviceAddress = prefs.getString("last_address", "") ?: "",
      lastSyncMillis = prefs.getLong("fetch_last_sync_ms", 0L)
    )
  )
  val uiState: StateFlow<MiBandState> = _uiState.asStateFlow()

  private val _discoveredDevices = MutableStateFlow<List<DiscoveredBand>>(emptyList())
  val discoveredDevices: StateFlow<List<DiscoveredBand>> = _discoveredDevices.asStateFlow()

  var onLiveMetricReceived: ((type: String, value: Any) -> Unit)? = null

  /** Fired on the main thread after a history fetch finished and new rows are in the store. */
  var onHistorySynced: ((newSamples: Int) -> Unit)? = null

  private val store = BandSampleStore.get(context)
  private var userDisconnected = prefs.getBoolean("user_disconnected", false)
  private var reconnectAttempts = 0

  private fun todayKey(): String {
    val c = java.util.Calendar.getInstance()
    return "%04d-%02d-%02d".format(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
  }

  fun wasUserDisconnected(): Boolean = userDisconnected
  fun hasSavedDevice(): Boolean = !(prefs.getString("last_address", "") ?: "").isNullOrEmpty()

  /** Reconnects to the last used band (notification "Connect" button / app start). */
  fun connectSaved() {
    val addr = prefs.getString("last_address", "") ?: ""
    if (addr.isEmpty()) {
      _uiState.value = _uiState.value.copy(errorMessage = "No band saved yet. Open Bluetooth and pick your band first.")
      return
    }
    val st = _uiState.value.status
    if (st == ConnectionStatus.CONNECTED || st == ConnectionStatus.CONNECTING || st == ConnectionStatus.AUTHENTICATING) return
    connectByAddress(addr, getStoredAuthKey())
  }

  // ---- History sync (Gadgetbridge-style activity fetch) ----
  private val gattIo = object : BandGattIo {
    override fun characteristic(uuid: UUID): BluetoothGattCharacteristic? =
      bluetoothGatt?.getService(ACTIVITY_SERVICE_UUID)?.getCharacteristic(uuid)

    @SuppressLint("MissingPermission")
    override fun setNotify(ch: BluetoothGattCharacteristic, enable: Boolean) {
      val gatt = bluetoothGatt ?: return
      enqueueGatt {
        gatt.setCharacteristicNotification(ch, enable)
        val d = ch.getDescriptor(CLIENT_CONFIG_DESCRIPTOR_UUID)
        if (d == null) {
          completeGattOperation()
        } else {
          d.value = if (enable) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
          if (!gatt.writeDescriptor(d)) completeGattOperation()
        }
      }
    }

    @SuppressLint("MissingPermission")
    override fun write(ch: BluetoothGattCharacteristic, value: ByteArray) {
      val gatt = bluetoothGatt ?: return
      enqueueGatt {
        ch.value = value
        if (!gatt.writeCharacteristic(ch)) completeGattOperation()
      }
    }
  }

  private val fetchListener = object : HuamiActivityFetcher.Listener {
    override fun onProgress(percent: Int, message: String) {
      handler.post {
        _uiState.value = _uiState.value.copy(syncing = true, syncPercent = percent, syncMessage = message)
      }
    }

    override fun onFinished(success: Boolean, newSamples: Int, message: String) {
      handler.post {
        val last = prefs.getLong("fetch_last_sync_ms", 0L)
        _uiState.value = _uiState.value.copy(
          syncing = false,
          syncPercent = 100,
          syncMessage = message,
          lastSyncMillis = if (success) System.currentTimeMillis() else _uiState.value.lastSyncMillis,
          errorMessage = if (success || message == "Sync cancelled") null else message
        )
        if (success) prefs.edit().putLong("last_history_sync_wall", System.currentTimeMillis()).apply()
        Log.i(TAG, "History sync finished success=$success new=$newSamples ($message) lastSample=$last")
        if (newSamples > 0 || success) onHistorySynced?.invoke(newSamples)
      }
    }
  }

  private val fetcher = HuamiActivityFetcher(gattIo, store, prefs, handler, fetchListener)

  /** Forget what was synced (activity / stress / workouts) and download everything the band still holds. */
  fun resetAndResync() {
    handler.post {
      prefs.edit().remove("fetch_last_sync_ms").remove("fetch_last_stress_ms").remove("fetch_last_sports_ms").apply()
      store.resetForResync()
      syncNow()
    }
  }

  fun syncNow() {
    if (_uiState.value.status != ConnectionStatus.CONNECTED || bluetoothGatt == null) {
      _uiState.value = _uiState.value.copy(errorMessage = "Connect your band first.")
      return
    }
    if (!hasValidAuthKey()) {
      _uiState.value = _uiState.value.copy(
        errorMessage = "Auth key missing or invalid. Paste the 32-character key from Zepp / Gadgetbridge, then reconnect.",
        syncing = false,
        syncMessage = null
      )
      return
    }
    if (fetcher.running) return
    _uiState.value = _uiState.value.copy(syncing = true, syncPercent = 0, syncMessage = "Syncing history…", errorMessage = null)
    if (!fetcher.start()) {
      _uiState.value = _uiState.value.copy(syncing = false)
    }
  }

  fun getStoredAuthKey(): String {
    return prefs.getString("mi_auth_key", "") ?: ""
  }

  fun saveAuthKey(key: String) {
    val clean = key.trim()
      .replace("0x", "", ignoreCase = true)
      .replace(" ", "")
      .replace(":", "")
      .replace("-", "")
      .lowercase()
    prefs.edit().putString("mi_auth_key", clean).apply()
    _uiState.value = _uiState.value.copy(authKey = clean)
  }

  /** True when a valid 32-hex auth key is stored (required for history sync on Mi Band 4/5). */
  fun hasValidAuthKey(): Boolean {
    val k = getStoredAuthKey().lowercase()
    return k.length == 32 && k.all { it in "0123456789abcdef" }
  }

  fun isBluetoothEnabled(): Boolean {
    return bluetoothAdapter?.isEnabled == true
  }

  fun isLocationEnabled(): Boolean {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    return locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
      locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
  }

  @SuppressLint("MissingPermission")
  fun startScan() {
    if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
      _uiState.value = _uiState.value.copy(
        status = ConnectionStatus.DISCONNECTED,
        errorMessage = "Bluetooth is turned OFF. Please turn ON Bluetooth in phone settings."
      )
      return
    }

    val scanner = bluetoothAdapter.bluetoothLeScanner
    if (scanner == null) {
      _uiState.value = _uiState.value.copy(
        status = ConnectionStatus.DISCONNECTED,
        errorMessage = "BLE Scanner not available on this device."
      )
      return
    }

    // Immediately populate with already bonded/paired Bluetooth devices so they appear instantly!
    val initialList = mutableListOf<DiscoveredBand>()
    try {
      val bonded = bluetoothAdapter.bondedDevices ?: emptySet()
      for (b in bonded) {
        val rawName = b.name ?: ""
        val isMi = rawName.contains("band", ignoreCase = true) ||
          rawName.contains("mi", ignoreCase = true) ||
          rawName.contains("amazfit", ignoreCase = true) ||
          rawName.contains("huami", ignoreCase = true) ||
          rawName.contains("xmsh", ignoreCase = true)
        val displayName = when {
          rawName.isNotEmpty() -> rawName
          isMi -> "Mi Smart Band 5"
          else -> "Bluetooth Device (${b.address.takeLast(5)})"
        }
        initialList.add(
          DiscoveredBand(
            device = b,
            name = displayName,
            address = b.address,
            rssi = -55,
            isMiBand = isMi
          )
        )
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed reading bonded devices: ${e.message}")
    }

    _discoveredDevices.value = initialList
    isScanning = true

    val isLocEnabled = isLocationEnabled()
    _uiState.value = _uiState.value.copy(
      status = ConnectionStatus.SCANNING,
      errorMessage = if (!isLocEnabled) "Notice: Location/GPS is OFF. Turn ON phone GPS if nearby devices are not detected." else null
    )

    val settingsBuilder = ScanSettings.Builder()
      .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)

    try {
      settingsBuilder.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
      settingsBuilder.setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
    } catch (_: Exception) {}

    val settings = settingsBuilder.build()

    try {
      try {
        scanner.startScan(null, settings, scanCallback)
      } catch (fallbackEx: Exception) {
        Log.w(TAG, "Custom scan settings failed, falling back to basic scan: ${fallbackEx.message}")
        scanner.startScan(scanCallback)
      }

      // Automatically stop scan after 30 seconds
      handler.postDelayed({
        stopScan()
      }, 30000)
    } catch (e: Exception) {
      Log.e(TAG, "Error starting scan: ${e.message}")
      isScanning = false
      _uiState.value = _uiState.value.copy(
        status = ConnectionStatus.DISCONNECTED,
        errorMessage = "Scan error: ${e.localizedMessage}. Please verify Bluetooth & Location permissions are granted."
      )
    }
  }

  @SuppressLint("MissingPermission")
  fun stopScan() {
    if (!isScanning) return
    isScanning = false
    try {
      bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
    } catch (e: Exception) {
      Log.e(TAG, "Error stopping scan: ${e.message}")
    }
    if (_uiState.value.status == ConnectionStatus.SCANNING) {
      _uiState.value = _uiState.value.copy(status = ConnectionStatus.DISCONNECTED)
    }
  }

  @SuppressLint("MissingPermission")
  fun connectByAddress(macAddress: String, authKeyHex: String) {
    try {
      val cleanMac = macAddress.trim().uppercase()
      val device = bluetoothAdapter?.getRemoteDevice(cleanMac)
      if (device != null) {
        connectToDevice(device, authKeyHex)
      } else {
        _uiState.value = _uiState.value.copy(errorMessage = "Invalid Bluetooth MAC address: $macAddress")
      }
    } catch (e: Exception) {
      Log.e(TAG, "Connect by address error: ${e.message}")
      _uiState.value = _uiState.value.copy(errorMessage = "Failed to connect: ${e.localizedMessage}")
    }
  }

  private val scanCallback = object : ScanCallback() {
    @SuppressLint("MissingPermission")
    override fun onScanResult(callbackType: Int, result: ScanResult?) {
      result ?: return
      val device = result.device ?: return
      val record = result.scanRecord

      val rawName = device.name ?: record?.deviceName ?: ""
      val uuids = record?.serviceUuids?.map { it.uuid.toString().lowercase() } ?: emptyList()
      val hasHuamiManufacturer = record?.getManufacturerSpecificData(343) != null || // 0x0157
        record?.getManufacturerSpecificData(0x0157) != null

      val isMiBand = rawName.contains("band", ignoreCase = true) ||
        rawName.contains("mi", ignoreCase = true) ||
        rawName.contains("amazfit", ignoreCase = true) ||
        rawName.contains("huami", ignoreCase = true) ||
        rawName.contains("xmsh", ignoreCase = true) ||
        hasHuamiManufacturer ||
        uuids.any { it.contains("fee0") || it.contains("fee1") || it.contains("180d") }

      val displayName = when {
        rawName.isNotEmpty() -> rawName
        isMiBand -> "Mi Smart Band 5"
        else -> "BLE Device (${device.address.takeLast(5)})"
      }

      val currentList = _discoveredDevices.value.toMutableList()
      val existingIndex = currentList.indexOfFirst { it.address == device.address }

      val band = DiscoveredBand(
        device = device,
        name = displayName,
        address = device.address,
        rssi = result.rssi,
        isMiBand = isMiBand
      )

      if (existingIndex >= 0) {
        currentList[existingIndex] = band
      } else {
        if (isMiBand) {
          currentList.add(0, band) // Prioritize Mi Band at top
        } else {
          currentList.add(band)
        }
      }
      _discoveredDevices.value = currentList
    }

    override fun onScanFailed(errorCode: Int) {
      Log.e(TAG, "Scan failed with code: $errorCode")
      isScanning = false
      _uiState.value = _uiState.value.copy(
        status = ConnectionStatus.DISCONNECTED,
        errorMessage = "Bluetooth scan failed (Code $errorCode). Check phone Bluetooth & Location permissions."
      )
    }
  }

  @SuppressLint("MissingPermission")
  fun connectToDevice(device: BluetoothDevice, authKeyHex: String) {
    stopScan()
    clearGattQueue()

    val cleanKey = authKeyHex.trim().replace("0x", "", ignoreCase = true).replace(" ", "").replace(":", "").replace("-", "").lowercase()
    if (cleanKey.isNotEmpty()) {
      saveAuthKey(cleanKey)
    }

    val name = device.name ?: if (device.address.isNotEmpty()) "Mi Smart Band 5" else "Mi Band"
    userDisconnected = false
    prefs.edit()
      .putString("last_address", device.address)
      .putString("last_name", name)
      .putBoolean("user_disconnected", false)
      .apply()
    _uiState.value = _uiState.value.copy(
      status = ConnectionStatus.CONNECTING,
      deviceName = name,
      deviceAddress = device.address,
      errorMessage = null
    )

    try {
      bluetoothGatt?.close()
    } catch (e: Exception) {
      Log.w(TAG, "Gatt close error: ${e.message}")
    }

    authTrace = ""
    limitedMode = false
    // Like Gadgetbridge: finish Android pairing FIRST, then open the GATT link. Doing both at once made the band drop
    // or ignore the authentication step.
    if (device.bondState != BluetoothDevice.BOND_BONDED) {
      trace("pairing")
      var started = false
      lateinit var receiver: BroadcastReceiver
      val go = {
        if (!started) {
          started = true
          try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
          trace("bond=" + device.bondState)
          handler.post { startGatt(device) }
        }
      }
      receiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(c: Context?, i: Intent?) {
          val d = i?.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
          if (d?.address != device.address) return
          val st = i?.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1) ?: -1
          val prev = i?.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1) ?: -1
          if (st == BluetoothDevice.BOND_BONDED || (st == BluetoothDevice.BOND_NONE && prev == BluetoothDevice.BOND_BONDING)) go()
        }
      }
      try {
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        val ok = device.createBond()
        if (!ok) go() else handler.postDelayed({ go() }, 30_000)
      } catch (e: Exception) {
        Log.w(TAG, "createBond exception: ${e.message}")
        go()
      }
    } else {
      startGatt(device)
    }
  }

  @SuppressLint("MissingPermission")
  private fun startGatt(device: BluetoothDevice) {
    if (userDisconnected) return
    try { bluetoothGatt?.close() } catch (_: Exception) {}
    bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
  }

  /** User pressed Disconnect (app or notification). Does not auto-reconnect. */
  fun disconnect() {
    userDisconnected = true
    reconnectAttempts = 0
    prefs.edit().putBoolean("user_disconnected", true).apply()
    handler.removeCallbacksAndMessages(null)
    teardown()
  }

  @SuppressLint("MissingPermission")
  private fun teardown() {
    authFlowId++
    fetcher.cancel()
    clearGattQueue()
    try {
      bluetoothGatt?.disconnect()
      bluetoothGatt?.close()
    } catch (e: Exception) {
      Log.e(TAG, "Disconnect error: ${e.message}")
    } finally {
      bluetoothGatt = null
      _uiState.value = _uiState.value.copy(
        status = ConnectionStatus.DISCONNECTED,
        syncing = false
      )
    }
  }

  /** Link dropped by the band / out of range: try a few quiet reconnects. */
  private fun onLinkLost() {
    val wasConnected = _uiState.value.status == ConnectionStatus.CONNECTED
    teardown()
    if (!userDisconnected && hasSavedDevice() && reconnectAttempts < 4) {
      reconnectAttempts++
      handler.postDelayed({
        if (!userDisconnected && _uiState.value.status == ConnectionStatus.DISCONNECTED) connectSaved()
      }, if (wasConnected) 3000L else 10000L)
    }
  }

  private fun enqueueGatt(action: () -> Unit) {
    gattQueue.add(action)
    processNextGatt()
  }

  private fun processNextGatt() {
    if (isGattBusy) return
    val next = gattQueue.poll() ?: return
    isGattBusy = true
    // A GATT call that returns false never fires its callback; without this the whole queue (auth included) froze.
    val token = ++gattOpToken
    handler.postDelayed({
      if (isGattBusy && token == gattOpToken) {
        Log.w(TAG, "GATT operation timed out — releasing the queue")
        completeGattOperation()
      }
    }, 4000)
    handler.post {
      try {
        next.invoke()
      } catch (e: Exception) {
        Log.e(TAG, "Gatt queue execution failed: ${e.message}")
        completeGattOperation()
      }
    }
  }

  private fun completeGattOperation() {
    isGattBusy = false
    handler.postDelayed({
      processNextGatt()
    }, 50)
  }

  private fun clearGattQueue() {
    gattQueue.clear()
    isGattBusy = false
  }

  private val gattCallback = object : BluetoothGattCallback() {
    @SuppressLint("MissingPermission")
    override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
      Log.d(TAG, "onConnectionStateChange status: $status, newState: $newState")
      if (newState == BluetoothProfile.STATE_CONNECTED) {
        _uiState.value = _uiState.value.copy(status = ConnectionStatus.AUTHENTICATING)
        handler.postDelayed({
          gatt?.discoverServices()
        }, 500)
      } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
        onLinkLost()
      }
    }

    @SuppressLint("MissingPermission")
    override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
      if (status != BluetoothGatt.GATT_SUCCESS || gatt == null) {
        _uiState.value = _uiState.value.copy(
          status = ConnectionStatus.DISCONNECTED,
          errorMessage = "Service discovery failed (status $status). Re-connecting..."
        )
        return
      }

      Log.d(TAG, "Services discovered. Checking authentication & sensor services...")
      trace("services")
      // Zepp OS bands (Mi Band 7/8, Amazfit GTR 4 ...) expose the chunked 2021 characteristics and use a different, encrypted login.
      zeppOsBand = gatt.services.any { sv -> sv.getCharacteristic(UUID.fromString("00000016-0000-3512-2118-0009af100700")) != null }
      trace(if (zeppOsBand) "ZEPP-OS-BAND" else "legacy-band")
      authUseKeyFirst = false
      val authService = gatt.getService(AUTH_SERVICE_UUID)
      val authChar = authService?.getCharacteristic(AUTH_CHAR_UUID)

      val key = _uiState.value.authKey.trim().replace("0x", "", ignoreCase = true)
        .replace(" ", "").replace(":", "").replace("-", "").lowercase()
      if (authChar != null && key.length == 32 && key.all { it in "0123456789abcdef" }) {
        Log.d(TAG, "Starting Huami AES-128 auth handshake…")
        authByte = 0x00
        authTriedAltByte = false
        authUseKeyFirst = false
        authFlowId++
        armAuthWatchdog(gatt)
        // Persist cleaned key
        if (key != _uiState.value.authKey) saveAuthKey(key)
        gatt.setCharacteristicNotification(authChar, true)
        val descriptor = authChar.getDescriptor(CLIENT_CONFIG_DESCRIPTOR_UUID)
        if (descriptor != null) {
          descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
          enqueueGatt { gatt.writeDescriptor(descriptor) }
          // sendAuthKey runs from onDescriptorWrite → sendRequestRandomKey
        } else {
          sendRequestRandomKey(gatt, authChar)
        }
      } else {
        // No valid key: live sensors may work after OS pairing, but history sync will not.
        Log.w(TAG, "No valid 32-hex auth key — connecting without Huami auth (history sync disabled)")
        _uiState.value = _uiState.value.copy(
          status = ConnectionStatus.CONNECTED,
          errorMessage = if (key.isNotEmpty())
            "Auth key looks wrong (${key.length} chars). Need 32 hex digits for full sync."
          else
            "Connected without auth key — live HR/steps only. Add Zepp key for sleep & history sync."
        )
        subscribeToSensors(gatt)
      }
    }

    @SuppressLint("MissingPermission")
    override fun onDescriptorWrite(
      gatt: BluetoothGatt?,
      descriptor: BluetoothGattDescriptor?,
      status: Int
    ) {
      Log.d(TAG, "onDescriptorWrite uuid: ${descriptor?.uuid} status: $status")
      if (descriptor?.characteristic?.uuid == AUTH_CHAR_UUID) trace("cccd=$status")
      completeGattOperation()

      if (descriptor?.characteristic?.uuid == AUTH_CHAR_UUID && gatt != null) {
        val authService = gatt.getService(AUTH_SERVICE_UUID)
        val authChar = authService?.getCharacteristic(AUTH_CHAR_UUID)
        if (authChar != null) {
          sendRequestRandomKey(gatt, authChar)
        }
      }
    }

    @SuppressLint("MissingPermission")
    override fun onCharacteristicWrite(
      gatt: BluetoothGatt?,
      characteristic: BluetoothGattCharacteristic?,
      status: Int
    ) {
      Log.d(TAG, "onCharacteristicWrite uuid: ${characteristic?.uuid} status: $status")
      if (characteristic?.uuid == AUTH_CHAR_UUID) trace("write=$status")
      completeGattOperation()
    }

    @Deprecated("Deprecated in API 33")
    @SuppressLint("MissingPermission")
    override fun onCharacteristicChanged(
      gatt: BluetoothGatt?,
      characteristic: BluetoothGattCharacteristic?
    ) {
      if (Build.VERSION.SDK_INT >= 33) return // handled by the 3-arg overload below
      val value = characteristic?.value ?: return
      dispatchNotification(gatt, characteristic.uuid, characteristic, value)
    }

    @SuppressLint("MissingPermission")
    override fun onCharacteristicChanged(
      gatt: BluetoothGatt,
      characteristic: BluetoothGattCharacteristic,
      value: ByteArray
    ) {
      dispatchNotification(gatt, characteristic.uuid, characteristic, value)
    }

    @Deprecated("Deprecated in API 33")
    override fun onCharacteristicRead(
      gatt: BluetoothGatt?,
      characteristic: BluetoothGattCharacteristic?,
      status: Int
    ) {
      if (Build.VERSION.SDK_INT >= 33) return
      val value = characteristic?.value
      if (value != null && characteristic != null) dispatchRead(characteristic.uuid, value)
      completeGattOperation()
    }

    override fun onCharacteristicRead(
      gatt: BluetoothGatt,
      characteristic: BluetoothGattCharacteristic,
      value: ByteArray,
      status: Int
    ) {
      dispatchRead(characteristic.uuid, value)
      completeGattOperation()
    }
  }

  private fun dispatchRead(uuid: UUID, value: ByteArray) {
    when (uuid) {
      STEPS_CHAR_UUID -> handleStepsData(value)
      BATTERY_CHAR_UUID, STANDARD_BATTERY_CHAR_UUID -> handleBatteryData(value)
      HR_MEASUREMENT_CHAR_UUID -> handleHeartRateData(value)
    }
  }

  @SuppressLint("MissingPermission")
  private fun dispatchNotification(
    gatt: BluetoothGatt?,
    uuid: UUID,
    characteristic: BluetoothGattCharacteristic,
    value: ByteArray
  ) {
    when (uuid) {
      AUTH_CHAR_UUID -> if (gatt != null) handleAuthNotification(gatt, characteristic, value)
      HR_MEASUREMENT_CHAR_UUID -> handleHeartRateData(value)
      STEPS_CHAR_UUID -> handleStepsData(value)
      BATTERY_CHAR_UUID, STANDARD_BATTERY_CHAR_UUID -> handleBatteryData(value)
      FETCH_CONTROL_UUID -> handler.post { fetcher.onControlNotification(value) }
      FETCH_DATA_UUID -> handler.post { fetcher.onDataNotification(value) }
      configCharUuid -> handler.post { handleConfigNotification(value) }
      DEVICE_EVENT_CHAR_UUID -> handler.post { handleDeviceEvent(value) }
      WORKOUT_CHAR_UUID -> handler.post { handleWorkoutNotification(value) }
      HR_CONTROL_POINT_CHAR_UUID -> Log.d(TAG, "HR control reply: " + value.joinToString(" ") { "%02x".format(it) })
    }
  }

  /**
   * Huami auth protocol (Mi Band 4/5/6, Amazfit Band 5) — same as Gadgetbridge / web ble.js:
   *  1. Write [0x01, AUTH_BYTE] + 16-byte key
   *  2. Band replies [0x10, 0x01, 0x01]
   *  3. Write [0x02, AUTH_BYTE]  (request random)
   *  4. Band replies [0x10, 0x02, 0x01] + 16 challenge bytes
   *  5. Write [0x03, AUTH_BYTE] + AES-ECB(key, challenge)
   *  6. Band replies [0x10, 0x03, 0x01] on success
   *
   * AUTH_BYTE is 0x00 on most firmwares; some Mi Band 5 builds use 0x08.
   */
  private var authByte: Byte = 0x00
  private var authTriedAltByte = false
  /** Gadgetbridge only sends the key while pairing; on a normal reconnect it goes straight to "request random number". */
  private var authUseKeyFirst = false
  @Volatile private var zeppOsBand = false
  /** Use whichever write type the band's auth characteristic actually supports (forcing "with response" on a no-response-only char fails). */
  private fun authWriteType(ch: BluetoothGattCharacteristic): Int {
    val p = ch.properties
    return if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
  }
  @Volatile private var authTrace = ""
  @Volatile private var limitedMode = false
  private fun trace(msg: String) {
    authTrace = (if (authTrace.isEmpty()) msg else "$authTrace > $msg").takeLast(170)
    Log.d(TAG, "auth trace: $authTrace")
  }
  private var authFlowId = 0
  private var gattOpToken = 0

  @SuppressLint("MissingPermission")
  private fun sendAuthKey(gatt: BluetoothGatt, authChar: BluetoothGattCharacteristic) {
    val keyHex = _uiState.value.authKey.trim().replace("0x", "", ignoreCase = true)
      .replace(" ", "").replace(":", "").lowercase()
    if (keyHex.length != 32 || !keyHex.all { it in "0123456789abcdef" }) {
      _uiState.value = _uiState.value.copy(
        status = ConnectionStatus.DISCONNECTED,
        errorMessage = "Invalid auth key — need exactly 32 hex characters (from Zepp / Gadgetbridge)."
      )
      try { gatt.disconnect() } catch (_: Exception) {}
      return
    }
    val keyBytes = hexStringToByteArray(keyHex)
    val packet = ByteArray(18)
    packet[0] = 0x01
    packet[1] = authByte
    System.arraycopy(keyBytes, 0, packet, 2, 16)
    Log.d(TAG, "Auth step 1: send key (byte=0x%02x)".format(authByte))
    trace("sent-key")
    enqueueGatt {
      authChar.value = packet
      authChar.writeType = authWriteType(authChar)
      gatt.writeCharacteristic(authChar)
    }
  }

  @SuppressLint("MissingPermission")
  private fun sendRequestRandomKey(gatt: BluetoothGatt, authChar: BluetoothGattCharacteristic) {
    // Called after the auth CCCD is enabled. Gadgetbridge's normal reconnect path asks for the random number straight
    // away (0x02 0x00). Re-sending the key first made already-paired bands go silent, which looked like
    // "stuck at authentication". The key-first path stays as an automatic fallback.
    if (authUseKeyFirst) sendAuthKey(gatt, authChar) else requestRandomNumber(gatt, authChar)
  }

  @SuppressLint("MissingPermission")
  private fun requestRandomNumber(gatt: BluetoothGatt, authChar: BluetoothGattCharacteristic) {
    Log.d(TAG, "Auth step 2: request random (byte=0x%02x)".format(authByte))
    trace("sent-random")
    enqueueGatt {
      authChar.value = byteArrayOf(0x02, authByte)
      authChar.writeType = authWriteType(authChar)
      gatt.writeCharacteristic(authChar)
    }
  }

  @SuppressLint("MissingPermission")
  private fun handleAuthNotification(
    gatt: BluetoothGatt,
    authChar: BluetoothGattCharacteristic,
    value: ByteArray
  ) {
    if (value.size < 3 || value[0] != 0x10.toByte()) return

    val opCode = value[1].toInt() and 0xFF
    val status = value[2].toInt() and 0xFF
    Log.d(TAG, "Auth notify: op=0x%02x status=0x%02x len=%d".format(opCode, status, value.size))
    trace("reply %02x/%02x".format(opCode, status))

    when (opCode) {
      0x01 -> {
        // Key accepted / rejected
        if (status != 0x01) {
          if (!authTriedAltByte) {
            authTriedAltByte = true
            authByte = if (authByte.toInt() == 0) 0x08 else 0x00
            Log.w(TAG, "Key step rejected — retrying with AUTH_BYTE=0x%02x".format(authByte))
            sendAuthKey(gatt, authChar)
            return
          }
          failAuth(gatt, "Auth key rejected by band (token invalid). Re-copy the 32-char key from Zepp Life or Gadgetbridge.")
          return
        }
        requestRandomNumber(gatt, authChar)
      }
      0x02 -> {
        // Challenge: [0x10, 0x02, 0x01, ...16 bytes]
        if (status != 0x01 || value.size < 19) {
          if (!authUseKeyFirst) {
            authUseKeyFirst = true
            Log.w(TAG, "Random challenge refused — falling back to key-first pairing handshake")
            sendAuthKey(gatt, authChar)
            armAuthWatchdog(gatt)
            return
          }
          failAuth(gatt, "Band rejected random challenge — check the auth key.")
          return
        }
        val challenge = value.copyOfRange(3, 19)
        try {
          val keyHex = _uiState.value.authKey.trim().replace("0x", "", ignoreCase = true)
            .replace(" ", "").replace(":", "")
          val authKeyBytes = hexStringToByteArray(keyHex)
          if (authKeyBytes.size != 16) {
            failAuth(gatt, "Invalid auth key length (need 16 bytes / 32 hex chars).")
            return
          }
          val cipher = Cipher.getInstance("AES/ECB/NoPadding")
          cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(authKeyBytes, "AES"))
          val encrypted = cipher.doFinal(challenge)
          val response = ByteArray(18)
          response[0] = 0x03
          response[1] = authByte
          System.arraycopy(encrypted, 0, response, 2, 16)
          Log.d(TAG, "Auth step 3: send encrypted challenge")
          enqueueGatt {
            authChar.value = response
            authChar.writeType = authWriteType(authChar)
            gatt.writeCharacteristic(authChar)
          }
        } catch (e: Exception) {
          failAuth(gatt, "AES error: ${e.message}")
        }
      }
      0x03 -> {
        if (status == 0x01) {
          Log.i(TAG, "Huami authentication successful")
          authFlowId++            // cancels the auth watchdog
          authTriedAltByte = false
          _uiState.value = _uiState.value.copy(status = ConnectionStatus.CONNECTED, errorMessage = null)
          subscribeToSensors(gatt)
        } else {
          // Wrong key → status often 0x10 or 0x00
          if (!authUseKeyFirst) {
            authUseKeyFirst = true
            Log.w(TAG, "Encrypted response refused — falling back to key-first pairing handshake")
            sendAuthKey(gatt, authChar)
            armAuthWatchdog(gatt)
            return
          }
          if (!authTriedAltByte) {
            authTriedAltByte = true
            authByte = if (authByte.toInt() == 0) 0x08 else 0x00
            Log.w(TAG, "Encrypted response rejected — retrying with AUTH_BYTE=0x%02x".format(authByte))
            sendAuthKey(gatt, authChar)
            return
          }
          failAuth(gatt, "Token invalid — auth key does not match this band. Get a fresh key from Zepp / Gadgetbridge and try again.")
        }
      }
    }
  }

  /** If the band never answers an auth step, retry once the other way, then give a clear error instead of hanging. */
  @SuppressLint("MissingPermission")
  private fun armAuthWatchdog(gatt: BluetoothGatt) {
    val id = authFlowId
    handler.postDelayed({
      if (id != authFlowId || _uiState.value.status != ConnectionStatus.AUTHENTICATING) return@postDelayed
      val ch = gatt.getService(AUTH_SERVICE_UUID)?.getCharacteristic(AUTH_CHAR_UUID)
      if (ch != null && !authUseKeyFirst) {
        Log.w(TAG, "No auth reply after 8 s — retrying with the key-first handshake")
        authUseKeyFirst = true
        isGattBusy = false
        sendAuthKey(gatt, ch)
        armAuthWatchdog(gatt)
      } else {
        // Keep the link instead of dropping it: live heart-rate / steps / battery still work on many firmwares.
        trace("no-reply")
        limitedMode = true
        _uiState.value = _uiState.value.copy(
          status = ConnectionStatus.CONNECTED,
          errorMessage = if (zeppOsBand)
            "This band uses the newer Zepp OS login (Mi Band 7/8 and similar). This app only speaks the older Mi Band 4/5 login, so it cannot authenticate or sync history with it."
          else
            "Band did not answer authentication, so history sync is off (live mode only). Force-stop Zepp / Gadgetbridge, forget the band in Android Bluetooth settings, then Connect / pair again."
        )
        subscribeToSensors(gatt)
      }
    }, 8000)
  }

  @SuppressLint("MissingPermission")
  private fun failAuth(gatt: BluetoothGatt, message: String) {
    Log.e(TAG, message)
    _uiState.value = _uiState.value.copy(
      status = ConnectionStatus.DISCONNECTED,
      errorMessage = message,
      syncing = false,
      syncMessage = null
    )
    try { gatt.disconnect() } catch (_: Exception) {}
  }

  @SuppressLint("MissingPermission")
  private fun subscribeToSensors(gatt: BluetoothGatt) {
    reconnectAttempts = 0
    // Gadgetbridge enables notifications on the configuration, device-event, workout and HR-control characteristics
    // BEFORE it sends alarms / heart-rate settings. Without them many firmwares silently ignore the writes.
    lastAck = null
    gatt.getService(ACTIVITY_SERVICE_UUID)?.let { svc ->
      listOf(configCharUuid, DEVICE_EVENT_CHAR_UUID, WORKOUT_CHAR_UUID).forEach { u -> svc.getCharacteristic(u)?.let { gattIo.setNotify(it, true) } }
    }
    gatt.getService(HR_SERVICE_UUID)?.getCharacteristic(HR_CONTROL_POINT_CHAR_UUID)?.let { gattIo.setNotify(it, true) }
    // Align band clock with the phone as soon as the link is ready (Gadgetbridge onSetTime).
    handler.postDelayed({ if (linkReady()) try { syncTimeToBand() } catch (_: Exception) {} }, 900)
    // Apply stored alarms / heart-rate settings once those subscriptions are in place, and retry once for slow bands.
    handler.postDelayed({ applyBandSettings() }, 2500)
    handler.postDelayed({ applyBandSettings() }, 9000)
    // Pull the band's stored history as soon as the link is usable (like Gadgetbridge does on connect).
    handler.postDelayed({
      if (_uiState.value.status == ConnectionStatus.CONNECTED && !limitedMode && !fetcher.running) syncNow()
    }, 3000)
    // 1. Subscribe to Steps / Activity
    val activityService = gatt.getService(ACTIVITY_SERVICE_UUID)
    val stepsChar = activityService?.getCharacteristic(STEPS_CHAR_UUID)
    if (stepsChar != null) {
      gatt.setCharacteristicNotification(stepsChar, true)
      val descriptor = stepsChar.getDescriptor(CLIENT_CONFIG_DESCRIPTOR_UUID)
      if (descriptor != null) {
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        enqueueGatt { gatt.writeDescriptor(descriptor) }
      }
      // Read initial steps
      enqueueGatt { gatt.readCharacteristic(stepsChar) }
    }

    // 2. Read Battery
    val batteryChar = activityService?.getCharacteristic(BATTERY_CHAR_UUID)
      ?: gatt.getService(STANDARD_BATTERY_SERVICE_UUID)?.getCharacteristic(STANDARD_BATTERY_CHAR_UUID)
    if (batteryChar != null) {
      enqueueGatt { gatt.readCharacteristic(batteryChar) }
    }

    // 3. Subscribe to Heart Rate Service (Standard 0x180D)
    val hrService = gatt.getService(HR_SERVICE_UUID)
    val hrChar = hrService?.getCharacteristic(HR_MEASUREMENT_CHAR_UUID)
    if (hrChar != null) {
      gatt.setCharacteristicNotification(hrChar, true)
      val descriptor = hrChar.getDescriptor(CLIENT_CONFIG_DESCRIPTOR_UUID)
      if (descriptor != null) {
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        enqueueGatt { gatt.writeDescriptor(descriptor) }
      }

      // Start continuous HR sensor via Control Point
      val hrControlChar = hrService.getCharacteristic(HR_CONTROL_POINT_CHAR_UUID)
      if (hrControlChar != null) {
        enqueueGatt {
          hrControlChar.value = byteArrayOf(0x15, 0x01, 0x01)
          gatt.writeCharacteristic(hrControlChar)
        }
      }
    }
  }

  private fun handleHeartRateData(value: ByteArray) {
    if (value.size < 2) return
    val flags = value[0].toInt()
    val hr = if ((flags and 1) == 0) {
      value[1].toInt() and 0xFF
    } else {
      (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
    }

    if (hr in 30..240) {
      prefs.edit().putInt("cached_hr", hr).apply()
      _uiState.value = _uiState.value.copy(
        liveHeartRate = hr,
        lastUpdateMillis = System.currentTimeMillis()
      )
      onLiveMetricReceived?.invoke("heart_rate", hr)
    }
  }

  private fun handleStepsData(value: ByteArray) {
    if (value.size < 4) return
    var steps = 0
    var distance = 0
    var calories = 0

    if (value.size >= 5) {
      steps = (value[1].toInt() and 0xFF) or
        ((value[2].toInt() and 0xFF) shl 8) or
        ((value[3].toInt() and 0xFF) shl 16) or
        ((value[4].toInt() and 0xFF) shl 24)
    } else {
      steps = (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
    }

    if (value.size >= 9) {
      distance = (value[5].toInt() and 0xFF) or
        ((value[6].toInt() and 0xFF) shl 8) or
        ((value[7].toInt() and 0xFF) shl 16) or
        ((value[8].toInt() and 0xFF) shl 24)
    }

    if (value.size >= 13) {
      calories = (value[9].toInt() and 0xFF) or
        ((value[10].toInt() and 0xFF) shl 8) or
        ((value[11].toInt() and 0xFF) shl 16) or
        ((value[12].toInt() and 0xFF) shl 24)
    }

    prefs.edit().putInt("cached_steps", steps).putString("cached_steps_day", todayKey()).apply()
    _uiState.value = _uiState.value.copy(
      liveSteps = steps,
      liveDistanceMeters = distance,
      liveCalories = calories,
      lastUpdateMillis = System.currentTimeMillis()
    )
    onLiveMetricReceived?.invoke("steps", steps)
  }

  private fun handleBatteryData(value: ByteArray) {
    if (value.isNotEmpty()) {
      val battery = if (value.size >= 2) value[1].toInt() and 0xFF else value[0].toInt() and 0xFF
      if (battery in 0..100) {
        prefs.edit().putInt("cached_battery", battery).apply()
        _uiState.value = _uiState.value.copy(batteryPercent = battery)
        onLiveMetricReceived?.invoke("battery", battery)
      }
    }
  }

  private fun hexStringToByteArray(s: String): ByteArray {
    val clean = s.trim()
      .replace("0x", "", ignoreCase = true)
      .replace(" ", "")
      .replace(":", "")
      .replace("-", "")
      .lowercase()
    require(clean.length % 2 == 0) { "Hex string must have even length" }
    val data = ByteArray(clean.length / 2)
    var i = 0
    while (i < clean.length) {
      val hi = Character.digit(clean[i], 16)
      val lo = Character.digit(clean[i + 1], 16)
      require(hi >= 0 && lo >= 0) { "Invalid hex: ${clean[i]}${clean[i + 1]}" }
      data[i / 2] = ((hi shl 4) + lo).toByte()
      i += 2
    }
    return data
  }

  // ---------------- Band settings, alarms and phone notifications (Gadgetbridge Huami protocol) ----------------
  private val configCharUuid: UUID = UUID.fromString("00000003-0000-3512-2118-0009af100700")
  private val chunkedCharUuid: UUID = UUID.fromString("00000020-0000-3512-2118-0009af100700")
  private val controlPrefs = context.getSharedPreferences("band_control", Context.MODE_PRIVATE)

  private fun linkReady(): Boolean = _uiState.value.status == ConnectionStatus.CONNECTED && bluetoothGatt != null
  private fun fee0Char(uuid: UUID): BluetoothGattCharacteristic? = bluetoothGatt?.getService(ACTIVITY_SERVICE_UUID)?.getCharacteristic(uuid)

  private fun writeConfig(bytes: ByteArray): Boolean {
    if (!linkReady()) return false
    val ch = fee0Char(configCharUuid) ?: return false
    gattIo.write(ch, bytes)
    return true
  }

  private fun writeHrControl(bytes: ByteArray): Boolean {
    if (!linkReady()) return false
    val ch = bluetoothGatt?.getService(HR_SERVICE_UUID)?.getCharacteristic(HR_CONTROL_POINT_CHAR_UUID) ?: return false
    gattIo.write(ch, bytes)
    return true
  }

  fun getBandSettingsJson(): String = controlPrefs.getString("settings", "{}") ?: "{}"

  /** Stores the settings JSON from the web UI and sends them to the band now (or on the next connect). */
  fun saveBandSettings(json: String): String {
    try { org.json.JSONObject(json) } catch (e: Exception) { return "Invalid settings" }
    controlPrefs.edit().putString("settings", json).apply()
    return if (linkReady()) { applyBandSettings(); "Sent to band" } else "Saved. It will be sent when the band connects."
  }

  // ---------------- Status, acknowledgements, band workout events ----------------
  @Volatile private var lastAck: String? = null
  private var pendingBandWorkoutType = -1
  private var mirroredRun = false
  /** Offered to the UI when the band starts a workout; UI should ask the user Yes/No. */
  @Volatile var onBandWorkoutOffer: ((typeCode: Int, label: String) -> Unit)? = null
  private var pendingOfferType = -1
  private var offerTimeout: Runnable? = null

  /** Everything the Settings page shows in its "Band connection" card. */
  fun statusJson(): String {
    val st = _uiState.value
    val o = org.json.JSONObject()
    o.put("status", st.status.name); o.put("battery", st.batteryPercent); o.put("hr", st.liveHeartRate)
    o.put("lastSync", st.lastSyncMillis); o.put("syncing", st.syncing); o.put("percent", st.syncPercent)
    o.put("message", st.syncMessage ?: ""); o.put("error", st.errorMessage ?: ""); o.put("ack", lastAck ?: ""); o.put("trace", authTrace); o.put("limited", limitedMode)
    return o.toString()
  }

  /** The band answers every config write with [0x10, command, 0x01 = ok]. Gadgetbridge leaves this as a TODO; we surface it. */
  private fun handleConfigNotification(v: ByteArray) {
    Log.d(TAG, "config reply: " + v.joinToString(" ") { "%02x".format(it) })
    if (v.size >= 3 && v[0] == 0x10.toByte()) {
      val ok = v[2] == 0x01.toByte()
      val what = when (v[1].toInt() and 0xFF) { 0x02 -> "Alarm"; 0x06 -> "Setting"; 0x08 -> "Reminder"; else -> "Command" }
      lastAck = if (ok) "$what accepted" else "$what rejected by band"
    }
  }

  /** 30-second manual heart-rate reading (same command Gadgetbridge uses for "measure now"). */
  fun measureHeartRateNow(): String {
    if (!linkReady()) return "Connect the band first"
    writeHrControl(byteArrayOf(0x15, 0x02, 0x01))
    handler.postDelayed({ if (linkReady()) writeHrControl(byteArrayOf(0x15, 0x02, 0x00)) }, 30_000)
    return "Measuring… keep the band snug for about 20 seconds"
  }

  // ---------------- Gadgetbridge-style band features ----------------

  private var findDeviceStop: Runnable? = null

  /**
   * Find band — vibrate repeatedly (Huami ALERT_LEVEL = 3), same as Gadgetbridge onFindDevice.
   * Stops automatically after ~12 s, or call [stopFindBand].
   */
  fun findBand(start: Boolean = true): String {
    if (!linkReady()) return "Connect the band first"
    findDeviceStop?.let { handler.removeCallbacks(it) }
    findDeviceStop = null
    val gatt = bluetoothGatt ?: return "Not connected"
    val ch = gatt.getService(IMMEDIATE_ALERT_SERVICE_UUID)?.getCharacteristic(ALERT_LEVEL_CHAR_UUID)
      ?: gatt.getService(ACTIVITY_SERVICE_UUID)?.getCharacteristic(ALERT_LEVEL_CHAR_UUID)
    if (ch == null) {
      // Fallback: short vibration via config endpoint (0x03 style pulse)
      writeConfig(byteArrayOf(0x03, 0x01, 0x00))
      return if (start) "Buzz sent" else "Stopped"
    }
    fun writeLevel(level: Int) {
      ch.value = byteArrayOf(level.toByte())
      ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
      enqueueGatt {
        try { gatt.writeCharacteristic(ch) } catch (_: Exception) {}
        handler.postDelayed({ completeGattOperation() }, 80)
      }
    }
    if (!start) {
      writeLevel(0)
      return "Find stopped"
    }
    writeLevel(3) // continuous high alert
    // Re-trigger every 2.5 s for ~12 s (Mi Fit / Gadgetbridge style)
    var pulses = 0
    val loop = object : Runnable {
      override fun run() {
        if (!linkReady() || pulses >= 4) {
          writeLevel(0)
          findDeviceStop = null
          return
        }
        pulses++
        writeLevel(3)
        handler.postDelayed(this, 2500)
      }
    }
    findDeviceStop = loop
    handler.postDelayed(loop, 2500)
    handler.postDelayed({ stopFindBand() }, 12_000)
    return "Finding band — it should vibrate"
  }

  fun stopFindBand(): String {
    findDeviceStop?.let { handler.removeCallbacks(it) }
    findDeviceStop = null
    return findBand(false)
  }

  /** Sync phone date/time to the band (Gadgetbridge onSetTime / Current Time Service). */
  fun syncTimeToBand(): String {
    if (!linkReady()) return "Connect the band first"
    val gatt = bluetoothGatt ?: return "Not connected"
    val ch = gatt.getService(CURRENT_TIME_SERVICE_UUID)?.getCharacteristic(CURRENT_TIME_CHAR_UUID)
    val cal = java.util.Calendar.getInstance()
    val year = cal.get(java.util.Calendar.YEAR)
    val payload = byteArrayOf(
      (year and 0xFF).toByte(), ((year shr 8) and 0xFF).toByte(),
      (cal.get(java.util.Calendar.MONTH) + 1).toByte(),
      cal.get(java.util.Calendar.DAY_OF_MONTH).toByte(),
      cal.get(java.util.Calendar.HOUR_OF_DAY).toByte(),
      cal.get(java.util.Calendar.MINUTE).toByte(),
      cal.get(java.util.Calendar.SECOND).toByte(),
      (cal.get(java.util.Calendar.DAY_OF_WEEK) - 1).toByte(), // 0=Sun in BLE CTS
      0, // fractions256
      0  // adjust reason
    )
    if (ch != null) {
      ch.value = payload
      ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
      enqueueGatt {
        try { gatt.writeCharacteristic(ch) } catch (_: Exception) {}
        handler.postDelayed({ completeGattOperation() }, 120)
      }
    }
    // Also push via Huami config endpoint used by some firmwares
    writeConfig(byteArrayOf(
      0x0a,
      (year and 0xFF).toByte(), ((year shr 8) and 0xFF).toByte(),
      (cal.get(java.util.Calendar.MONTH) + 1).toByte(),
      cal.get(java.util.Calendar.DAY_OF_MONTH).toByte(),
      cal.get(java.util.Calendar.HOUR_OF_DAY).toByte(),
      cal.get(java.util.Calendar.MINUTE).toByte(),
      cal.get(java.util.Calendar.SECOND).toByte(),
      0x00, 0x00
    ))
    lastAck = "Time synced"
    return "Phone time sent to band"
  }

  /** Step goal (Gadgetbridge goal) — written to user-settings char. */
  fun setStepGoal(steps: Int): String {
    if (!linkReady()) return "Connect the band first"
    val goal = steps.coerceIn(1000, 100_000)
    val gatt = bluetoothGatt ?: return "Not connected"
    val ch = gatt.getService(ACTIVITY_SERVICE_UUID)?.getCharacteristic(USER_SETTINGS_CHAR_UUID)
    // Huami: [0x10, goal LE u16, 0x00, 0x00] style goal packet used by GB
    val goalBytes = byteArrayOf(
      0x10, (goal and 0xFF).toByte(), ((goal shr 8) and 0xFF).toByte(), 0x00, 0x00
    )
    if (ch != null) {
      ch.value = goalBytes
      ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
      enqueueGatt {
        try { gatt.writeCharacteristic(ch) } catch (_: Exception) {}
        handler.postDelayed({ completeGattOperation() }, 120)
      }
    }
    writeConfig(byteArrayOf(0x03, 0x00, (goal and 0xFF).toByte(), ((goal shr 8) and 0xFF).toByte()))
    lastAck = "Step goal $goal"
    return "Step goal set to $goal"
  }

  /**
   * Incoming / ended call alert (Gadgetbridge onSetCallState).
   * category 3 = IncomingCall. alert level 2 = phone call vibration pattern.
   */
  fun sendCallAlert(nameOrNumber: String, ringing: Boolean): Boolean {
    if (!linkReady()) return false
    if (ringing) {
      sendBandNotification(nameOrNumber.ifBlank { "Incoming call" }, "Calling…", iconId = -1, category = 3)
      // Also pulse alert level for phone-call vibration pattern
      val gatt = bluetoothGatt ?: return true
      val ch = gatt.getService(IMMEDIATE_ALERT_SERVICE_UUID)?.getCharacteristic(ALERT_LEVEL_CHAR_UUID) ?: return true
      ch.value = byteArrayOf(2) // ALERT_LEVEL_PHONE_CALL
      ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
      enqueueGatt {
        try { gatt.writeCharacteristic(ch) } catch (_: Exception) {}
        handler.postDelayed({ completeGattOperation() }, 60)
      }
    } else {
      // Stop call vibration
      val gatt = bluetoothGatt ?: return true
      val ch = gatt.getService(IMMEDIATE_ALERT_SERVICE_UUID)?.getCharacteristic(ALERT_LEVEL_CHAR_UUID)
      if (ch != null) {
        ch.value = byteArrayOf(0)
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        enqueueGatt {
          try { gatt.writeCharacteristic(ch) } catch (_: Exception) {}
          handler.postDelayed({ completeGattOperation() }, 60)
        }
      }
    }
    return true
  }

  /**
   * Weather snapshot for the band (Gadgetbridge onSendWeather, chunked type 1).
   * temps in °C; condition: 0 clear, 1 clouds, 2 rain, 3 snow, 4 haze, 5 wind, 6 thunder.
   */
  fun sendWeather(
    city: String,
    tempC: Int,
    condition: Int,
    humidity: Int = 50,
    windMs: Int = 0,
    maxC: Int = tempC + 2,
    minC: Int = tempC - 2
  ): String {
    if (!linkReady()) return "Connect the band first"
    val ch = fee0Char(chunkedCharUuid) ?: return "Band not ready"
    val name = city.toByteArray(Charsets.UTF_8).let { if (it.size > 16) it.copyOf(16) else it }
    // Compact Huami weather payload used by older GB path (type 1)
    val buf = java.nio.ByteBuffer.allocate(20 + name.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    buf.put(0x08.toByte()) // weather subcommand variant
    buf.put(condition.coerceIn(0, 6).toByte())
    buf.put(tempC.toByte())
    buf.put(maxC.toByte())
    buf.put(minC.toByte())
    buf.put(humidity.coerceIn(0, 100).toByte())
    buf.put(windMs.coerceIn(0, 50).toByte())
    buf.put(0) // aqi placeholder
    buf.putInt((System.currentTimeMillis() / 1000).toInt())
    buf.put(name.size.toByte())
    buf.put(name)
    writeChunked(ch, 1, buf.array())
    lastAck = "Weather sent ($tempC°C)"
    return "Weather sent to band: $tempC°C, $city"
  }

  /** Enable / disable continuous realtime steps notifications (already on by default after connect). */
  fun setRealtimeSteps(enable: Boolean): String {
    if (!linkReady()) return "Connect the band first"
    val gatt = bluetoothGatt ?: return "Not connected"
    val ch = gatt.getService(ACTIVITY_SERVICE_UUID)?.getCharacteristic(STEPS_CHAR_UUID) ?: return "No steps char"
    gatt.setCharacteristicNotification(ch, enable)
    val desc = ch.getDescriptor(CLIENT_CONFIG_DESCRIPTOR_UUID)
    if (desc != null) {
      desc.value = if (enable) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
      enqueueGatt {
        try { gatt.writeDescriptor(desc) } catch (_: Exception) {}
        handler.postDelayed({ completeGattOperation() }, 100)
      }
    }
    return if (enable) "Live steps on" else "Live steps off"
  }

  private fun settingFlag(key: String, def: Boolean): Boolean =
    try { org.json.JSONObject(getBandSettingsJson()).optBoolean(key, def) } catch (e: Exception) { def }

  /** Device events: 0x14 = a workout screen was opened on the band. value[2] = needs GPS, value[3] = workout type. */
  private fun handleDeviceEvent(v: ByteArray) {
    if (v.isEmpty()) return
    if (v[0] == 0x14.toByte() && v.size >= 4) {
      pendingBandWorkoutType = v[3].toInt() and 0xFF
      Log.i(TAG, "Workout opened on band, type=$pendingBandWorkoutType")
    }
  }

  /** Band workout type (Huami) -> Kestrel run type (1 run, 3 walk, 4 ride); -1 = no GPS tracking. */
  private fun appRunTypeFor(bandType: Int): Int = when (bandType) { 0x01 -> 1; 0x04 -> 3; 0x03 -> 4; else -> -1 }

  private fun labelForAppType(t: Int): String = when (t) { 3 -> "Walk"; 4 -> "Ride"; 15 -> "Hike"; else -> "Run" }

  /**
   * Workout characteristic: [0x11, status] with 2 start, 3 pause, 4 resume, 5 end.
   * On Start we do NOT auto-record — we ask the user via [onBandWorkoutOffer].
   * Only if they accept do we start phone GPS + pull live HR from the band.
   */
  private fun handleWorkoutNotification(v: ByteArray) {
    if (v.size < 2 || v[0] != 0x11.toByte()) return
    if (!settingFlag("mirrorWorkout", true)) return
    val active = RunTrackingService.stateJson.contains("\"active\":true")
    try {
      when (v[1].toInt() and 0xFF) {
        0x02 -> { // Start on band
          val t = appRunTypeFor(pendingBandWorkoutType)
          if (t <= 0 || active || mirroredRun) return
          pendingOfferType = t
          offerTimeout?.let { handler.removeCallbacks(it) }
          val label = labelForAppType(t)
          Log.i(TAG, "Band workout started ($label) — offering phone tracking")
          onBandWorkoutOffer?.invoke(t, label)
          // Auto-decline after 45s if the user never answers
          val timeout = Runnable {
            if (pendingOfferType == t && !mirroredRun) {
              pendingOfferType = -1
              Log.i(TAG, "Band workout offer timed out — not tracking on phone")
            }
          }
          offerTimeout = timeout
          handler.postDelayed(timeout, 45_000)
        }
        0x03 -> if (mirroredRun) RunTrackingService.send(context, RunTrackingService.ACTION_PAUSE)
        0x04 -> if (mirroredRun) RunTrackingService.send(context, RunTrackingService.ACTION_RESUME)
        0x05 -> { // End on band → always stop phone recording if we were mirroring
          offerTimeout?.let { handler.removeCallbacks(it) }
          pendingOfferType = -1
          if (mirroredRun) {
            Log.i(TAG, "Band ended workout — finishing phone recording")
            RunTrackingService.send(context, RunTrackingService.ACTION_FINISH)
            mirroredRun = false
          }
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Could not handle band workout: ${e.message}")
    }
  }

  /** User accepted the “track this band workout on phone?” prompt. Starts GPS map + band HR. */
  fun acceptBandWorkoutOffer(typeCode: Int = -1): String {
    val t = if (typeCode > 0) typeCode else pendingOfferType
    if (t <= 0) return "No pending band workout"
    if (RunTrackingService.stateJson.contains("\"active\":true")) return "A recording is already active"
    offerTimeout?.let { handler.removeCallbacks(it) }
    pendingOfferType = -1
    mirroredRun = true
    RunTrackingService.send(context, RunTrackingService.ACTION_START, t, true, fromBand = true)
    return "Tracking ${labelForAppType(t)} on phone — map from GPS, pace + HR from band"
  }

  /** User declined — band keeps recording on its own; phone does nothing. */
  fun declineBandWorkoutOffer() {
    offerTimeout?.let { handler.removeCallbacks(it) }
    pendingOfferType = -1
    mirroredRun = false
    Log.i(TAG, "User declined phone tracking for band workout")
  }

  // ---------------- Phone-started activity -> band ----------------
  // Official Zepp/Mi Fit can open a workout on the band from the phone; that exact command is not
  // fully reverse-engineered for Mi Band 5 in Gadgetbridge. We approximate the same experience:
  //  1) continuous HR on the band
  //  2) try workout-status START on the workout characteristic
  //  3) stream phone GPS to the band (Gadgetbridge "Send GPS during workout" format)
  //  4) km buzz with pace + HR
  fun bandRunStarted(label: String, bandType: Int = 0x01) {
    mirroredRun = false
    if (!linkReady()) return
    writeHrControl(byteArrayOf(0x15, 0x01, 0x01))
    // Best-effort: signal workout start on the workout characteristic (same channel the band uses).
    try {
      val ch = fee0Char(WORKOUT_CHAR_UUID)
      if (ch != null) {
        gattIo.write(ch, byteArrayOf(0x11, 0x02)) // status Start (as observed band→phone)
      }
    } catch (e: Exception) {
      Log.w(TAG, "Workout start write failed: ${e.message}")
    }
    sendPhoneGps(status = 0x02, location = null) // SEARCHING
    if (settingFlag("runBuzz", true)) {
      sendBandNotification("Kestrel", "$label started — live HR + GPS", iconId = 1)
    }
  }
  fun bandRunKeepAlive() {
    if (!linkReady()) return
    writeHrControl(byteArrayOf(0x15, 0x01, 0x01))
  }
  fun bandRunKm(km: Int, split: String, hr: Int) {
    if (!linkReady() || !settingFlag("runBuzz", true)) return
    val body = "$km km · $split/km" + if (hr in 30..220) " · $hr bpm" else ""
    sendBandNotification("Kestrel", body, iconId = 1)
  }
  fun bandRunFinished(summary: String) {
    if (!linkReady()) return
    try {
      fee0Char(WORKOUT_CHAR_UUID)?.let { gattIo.write(it, byteArrayOf(0x11, 0x05)) } // End
    } catch (_: Exception) { }
    if (settingFlag("runBuzz", true)) sendBandNotification("Kestrel", summary, iconId = 1)
    writeHrControl(byteArrayOf(0x15, 0x01, 0x00))
    applyBandSettings()
  }

  /**
   * Stream phone GPS to the band — same protocol as Gadgetbridge HuamiSupport.sendPhoneGps
   * (Mi Band 4/5/6, Amazfit Band 5). Used by Zepp / Mi Fit during outdoor workouts so the
   * band can show live speed, distance and the route cursor.
   *
   * status: 0x01 ACQUIRED, 0x02 SEARCHING, 0x04 DISABLED, null = position-only update.
   * location may be null only for SEARCHING / DISABLED status packets.
   */
  @Volatile private var lastPhoneGpsSentMs = 0L

  fun sendPhoneGps(status: Int?, location: android.location.Location?) {
    if (!linkReady()) return
    if (!settingFlag("sendGpsToBand", true)) return
    // Status-only packets (SEARCHING / DISABLED) are allowed without a fix.
    if (location == null && status == null) return
    val ch = fee0Char(chunkedCharUuid) ?: return

    var flags = 0
    var length = 4
    if (status != null) { flags = flags or 0x1; length += 1 }          // WORKOUT_GPS_FLAG_STATUS
    if (location != null) { flags = flags or 0x40000; length += 31 }  // WORKOUT_GPS_FLAG_POSITION

    // Payload matches Gadgetbridge encodePhoneGpsPayload + leading 0x06 type byte.
    val buf = java.nio.ByteBuffer.allocate(1 + length).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    buf.put(0x06.toByte())
    buf.putInt(flags)
    if (status != null) buf.put(status.toByte())
    if (location != null) {
      buf.putInt((location.longitude * 3_000_000.0).toInt())
      buf.putInt((location.latitude * 3_000_000.0).toInt())
      val speed = if (location.hasSpeed()) location.speed else 0f
      buf.putInt((speed * 10f).toInt())
      val alt = if (location.hasAltitude()) location.altitude else 0.0
      buf.putInt((alt * 100.0).toInt())
      buf.putLong(if (location.time > 0) location.time else System.currentTimeMillis())
      buf.putInt(-1) // 0xffffffff — constant in GB captures
      val bearing = if (location.hasBearing()) location.bearing.toInt().toShort() else 0
      buf.putShort(bearing)
      buf.put(0.toByte())
    }
    writeChunked(ch, 6, buf.array())
    lastPhoneGpsSentMs = System.currentTimeMillis()
  }

  /** True if we should flag the next fix as ACQUIRED (GPS was lost for > 5 s). */
  fun needsGpsAcquiredFlag(): Boolean =
    System.currentTimeMillis() - lastPhoneGpsSentMs > 5_000

  /** Gadgetbridge writeToChunkedOld: [0, flags|type, counter] + data (max MTU-6, default 17). */
  private fun writeChunked(ch: BluetoothGattCharacteristic, type: Int, payload: ByteArray) {
    val maxChunk = 17
    var remaining = payload.size
    var count = 0
    while (remaining > 0) {
      val n = minOf(remaining, maxChunk)
      var flags = type and 0x0F
      if (remaining <= maxChunk) {
        flags = flags or 0x80 // last chunk
        if (count == 0) flags = flags or 0x40
      } else if (count > 0) {
        flags = flags or 0x40 // consecutive
      }
      val chunk = ByteArray(n + 3)
      chunk[0] = 0
      chunk[1] = flags.toByte()
      chunk[2] = (count and 0xFF).toByte()
      System.arraycopy(payload, count * maxChunk, chunk, 3, n)
      gattIo.write(ch, chunk)
      remaining -= n
      count++
    }
  }

  /** Alarm slot 0..4: [0x02, slot|0x80 if enabled, hour, minute, repeat-days mask]. */
  private fun sendAlarm(slot: Int, enabled: Boolean, hour: Int, minute: Int, days: Int) {
    val repeat = if (days and 0x7F == 0) 0x80 else days and 0x7F   // 0x80 = one-shot
    val idx = slot or (if (enabled) 0x80 else 0x00)
    writeConfig(byteArrayOf(0x02, idx.toByte(), hour.toByte(), minute.toByte(), repeat.toByte()))
  }

  /**
   * Read the phone's next system alarm (Clock app) and push it to band slot 0 as a one-shot.
   * Android only exposes the *next* alarm via [AlarmManager.nextAlarmClock], not the full list.
   */
  fun syncPhoneAlarmsToBand(): String {
    if (!linkReady()) return "Connect the band first"
    return try {
      val am = context.getSystemService(android.app.AlarmManager::class.java)
      val next = am?.nextAlarmClock
      if (next == null) return "No upcoming phone alarm found"
      val cal = java.util.Calendar.getInstance().apply { timeInMillis = next.triggerTime }
      val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
      val minute = cal.get(java.util.Calendar.MINUTE)
      sendAlarm(0, true, hour, minute, 0) // one-shot on slot 0
      // Persist into settings so reconnect re-applies it
      val s = try { org.json.JSONObject(getBandSettingsJson()) } catch (_: Exception) { org.json.JSONObject() }
      val arr = s.optJSONArray("alarms") ?: org.json.JSONArray()
      while (arr.length() < 1) arr.put(org.json.JSONObject())
      arr.put(0, org.json.JSONObject().put("slot", 0).put("enabled", true).put("hour", hour).put("minute", minute).put("days", 0))
      s.put("alarms", arr)
      controlPrefs.edit().putString("settings", s.toString()).apply()
      val label = "%02d:%02d".format(hour, minute)
      lastAck = "Phone alarm $label sent to band"
      "Synced phone alarm $label → band slot 1"
    } catch (e: Exception) {
      Log.w(TAG, "syncPhoneAlarmsToBand: ${e.message}")
      "Could not read phone alarms: ${e.message}"
    }
  }

  fun applyBandSettings() {
    if (!linkReady()) return
    val s = try { org.json.JSONObject(getBandSettingsJson()) } catch (e: Exception) { return }
    if (s.has("hrInterval")) writeHrControl(byteArrayOf(0x14, s.optInt("hrInterval", 0).coerceIn(0, 120).toByte()))
    if (s.has("sleepHr")) writeHrControl(byteArrayOf(0x15, 0x00, if (s.optBoolean("sleepHr")) 0x01 else 0x00))
    if (s.has("lift")) writeConfig(
      if (s.optBoolean("lift")) byteArrayOf(0x06, 0x05, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00) else byteArrayOf(0x06, 0x05, 0x00, 0x00)
    )
    if (s.has("goalNotify")) writeConfig(byteArrayOf(0x06, 0x06, 0x00, if (s.optBoolean("goalNotify")) 0x01 else 0x00))
    if (s.has("inactivity")) {
      val i = s.optJSONObject("inactivity")
      if (i != null) writeConfig(
        byteArrayOf(
          0x08, if (i.optBoolean("enabled")) 0x01 else 0x00, i.optInt("threshold", 60).coerceIn(15, 120).toByte(), 0x00,
          i.optInt("startHour", 8).coerceIn(0, 23).toByte(), 0x00, i.optInt("endHour", 21).coerceIn(0, 23).toByte(), 0x00,
          0x00, 0x00, 0x00, 0x00
        )
      )
    }
    // Display / units / DND (Gadgetbridge ENDPOINT_DISPLAY + ENDPOINT_DND)
    if (s.has("time24h")) writeConfig(if (s.optBoolean("time24h", true)) byteArrayOf(0x06, 0x02, 0x00, 0x01) else byteArrayOf(0x06, 0x02, 0x00, 0x00))
    if (s.has("dateOnBand")) writeConfig(if (s.optBoolean("dateOnBand", true)) byteArrayOf(0x06, 0x0a, 0x00, 0x03) else byteArrayOf(0x06, 0x0a, 0x00, 0x00))
    if (s.has("metric")) writeConfig(if (s.optBoolean("metric", true)) byteArrayOf(0x06, 0x03, 0x00, 0x00) else byteArrayOf(0x06, 0x03, 0x00, 0x01))
    if (s.has("rotateWrist")) writeConfig(byteArrayOf(0x06, 0x0d, 0x00, if (s.optBoolean("rotateWrist")) 0x01 else 0x00))
    if (s.has("showCaller")) writeConfig(byteArrayOf(0x06, 0x10, 0x00, 0x00, if (s.optBoolean("showCaller", true)) 0x01 else 0x00))
    if (s.has("disconnectAlert")) writeConfig(
      if (s.optBoolean("disconnectAlert")) byteArrayOf(0x06, 0x0c, 0x00, 0x01, 0, 0, 0, 0) else byteArrayOf(0x06, 0x0c, 0x00, 0x00, 0, 0, 0, 0)
    )
    if (s.has("dnd")) {
      when (s.optString("dnd", "off")) {
        "off" -> writeConfig(byteArrayOf(0x09, 0x82.toByte()))
        "auto" -> writeConfig(byteArrayOf(0x09, 0x83.toByte()))
        "scheduled" -> writeConfig(byteArrayOf(
          0x09, 0x81.toByte(),
          s.optInt("dndStartH", 22).coerceIn(0, 23).toByte(), s.optInt("dndStartM", 0).coerceIn(0, 59).toByte(),
          s.optInt("dndEndH", 7).coerceIn(0, 23).toByte(), s.optInt("dndEndM", 0).coerceIn(0, 59).toByte()
        ))
      }
    }
    if (s.has("stepGoal")) try { setStepGoal(s.optInt("stepGoal", 8000)) } catch (_: Exception) {}
    val alarms = s.optJSONArray("alarms")
    if (alarms != null) {
      for (k in 0 until alarms.length()) {
        val a = alarms.optJSONObject(k) ?: continue
        sendAlarm(a.optInt("slot", k).coerceIn(0, 4), a.optBoolean("enabled"), a.optInt("hour", 7).coerceIn(0, 23), a.optInt("minute", 0).coerceIn(0, 59), a.optInt("days", 0))
      }
    }
    val reminders = s.optJSONArray("reminders")
    if (reminders != null) {
      for (k in 0 until minOf(reminders.length(), 8)) {
        val r = reminders.optJSONObject(k) ?: continue
        sendReminder(k, r.optBoolean("enabled"), r.optString("text", ""), r.optLong("atMs", 0L), r.optString("repeat", "once"))
      }
      for (k in reminders.length() until 8) sendReminder(k, false, "", 0L, "once")
    }
  }

  /** Reminder slot — Gadgetbridge sendReminderToDevice (chunked type 2). */
  fun sendReminder(position: Int, enabled: Boolean, text: String, atMs: Long, repeat: String): String {
    if (!linkReady()) return "Connect the band first"
    val ch = fee0Char(chunkedCharUuid) ?: return "Band not ready"
    val pos = position.coerceIn(0, 7)
    if (!enabled || text.isBlank() || atMs <= 0L) {
      writeChunked(ch, 2, byteArrayOf(0x0b, pos.toByte(), 0x08, 0, 0, 0, 0))
      return "Reminder slot ${pos + 1} cleared"
    }
    val msg = text.toByteArray(Charsets.UTF_8).let { if (it.size > 40) it.copyOf(40) else it }
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = atMs }
    var eventConfig = 0x01 or 0x08
    when (repeat) {
      "daily" -> eventConfig = eventConfig or 0x0fe0
      "weekly" -> {
        val dow = (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
        eventConfig = eventConfig or (0x20 shl dow)
      }
    }
    val buf = java.nio.ByteBuffer.allocate(14 + msg.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    buf.put(0x0b.toByte()); buf.put(pos.toByte()); buf.putInt(eventConfig)
    buf.put((cal.get(java.util.Calendar.YEAR) - 2000).toByte())
    buf.put((cal.get(java.util.Calendar.MONTH) + 1).toByte())
    buf.put(cal.get(java.util.Calendar.DAY_OF_MONTH).toByte())
    buf.put(cal.get(java.util.Calendar.HOUR_OF_DAY).toByte())
    buf.put(cal.get(java.util.Calendar.MINUTE).toByte())
    buf.put(cal.get(java.util.Calendar.SECOND).toByte())
    buf.put(0); buf.put(msg); buf.put(0)
    writeChunked(ch, 2, buf.array())
    return "Reminder sent to slot ${pos + 1}"
  }

  /** Music now-playing — Gadgetbridge encodeMusicState (chunked type 3). */
  fun sendMusicInfo(artist: String, album: String, track: String, playing: Boolean, positionSec: Int = 0, durationSec: Int = 0): String {
    if (!linkReady()) return "Connect the band first"
    val ch = fee0Char(chunkedCharUuid) ?: return "Band not ready"
    fun trunc(s: String, n: Int) = s.toByteArray(Charsets.UTF_8).let { if (it.size > n) it.copyOf(n) else it }
    val a = trunc(artist, 60); val b = trunc(album, 60); val t = trunc(track, 60)
    var flags = 0x01; var length = 5
    if (a.isNotEmpty()) { flags = flags or 0x02; length += a.size + 1 }
    if (b.isNotEmpty()) { flags = flags or 0x04; length += b.size + 1 }
    if (t.isNotEmpty()) { flags = flags or 0x08; length += t.size + 1 }
    if (durationSec > 0) { flags = flags or 0x10; length += 2 }
    val buf = java.nio.ByteBuffer.allocate(length).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    buf.put(flags.toByte()); buf.put(if (playing) 1 else 0); buf.put(0)
    buf.putShort(positionSec.coerceIn(0, 65535).toShort())
    if (a.isNotEmpty()) { buf.put(a); buf.put(0) }
    if (b.isNotEmpty()) { buf.put(b); buf.put(0) }
    if (t.isNotEmpty()) { buf.put(t); buf.put(0) }
    if (durationSec > 0) buf.putShort(durationSec.coerceIn(0, 65535).toShort())
    writeChunked(ch, 3, buf.array())
    return "Music info sent"
  }

  fun sendMusicVolume(volume0to100: Int): String {
    if (!linkReady()) return "Connect the band first"
    val ch = fee0Char(chunkedCharUuid) ?: return "Band not ready"
    writeChunked(ch, 3, byteArrayOf(0x40, volume0to100.coerceIn(0, 100).toByte()))
    return "Volume sent"
  }

  fun factoryResetDisplay(): String {
    if (!linkReady()) return "Connect the band first"
    writeConfig(byteArrayOf(0x06, 0x0b, 0x00, 0x01))
    return "Display reset command sent"
  }

  /**
   * Shows a notification on the band (Gadgetbridge onNotification).
   * category: 0xFA custom Huami (default), 3 IncomingCall, 5 SMS, 1 Email.
   * iconId: Huami icon for custom category; ignored for SMS/Email/Call.
   */
  fun sendBandNotification(title: String, body: String, iconId: Int = 11, category: Int = 0xFA): Boolean {
    if (!linkReady()) return false
    val ch = fee0Char(chunkedCharUuid) ?: return false
    fun clip(t: String, max: Int): ByteArray {
      var b = t.toByteArray(Charsets.UTF_8)
      if (b.size > max) { var n = max; while (n > 0 && (b[n].toInt() and 0xC0) == 0x80) n--; b = b.copyOf(n) }
      return b
    }
    val titleB = clip(title.replace('\n', ' '), 32)
    val bodyB = clip(body, 180)
    val msg = ByteArray(titleB.size + 1 + bodyB.size)
    System.arraycopy(titleB, 0, msg, 0, titleB.size)
    System.arraycopy(bodyB, 0, msg, titleB.size + 1, bodyB.size)
    val cat = if (iconId < 0 && category == 0xFA) 3 else category // iconId -1 → treat as call
    val payload = when (cat) {
      3, 5, 1 -> {
        // AlertCategory IncomingCall / SMS / Email — no custom icon byte
        byteArrayOf(cat.toByte(), 1) + msg + "\u0000 \u0000".toByteArray(Charsets.UTF_8)
      }
      else -> {
        val app = "\u0000Kestrel\u0000".toByteArray(Charsets.UTF_8)
        val head = byteArrayOf(0xFA.toByte(), 0, 0, 0, 0, 1, iconId.coerceIn(0, 127).toByte())
        head + msg + app
      }
    }
    writeChunked(ch, 0, payload)
    return true
  }

  companion object {
    @Volatile private var instance: MiBandManager? = null

    /** One shared manager for the activity AND the foreground service, so the connection survives the UI. */
    fun get(context: Context): MiBandManager =
      instance ?: synchronized(this) {
        instance ?: MiBandManager(context.applicationContext).also { instance = it }
      }
  }
}
