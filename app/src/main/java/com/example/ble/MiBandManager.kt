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
import android.content.Context
import android.content.SharedPreferences
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
  val authKey: String = ""
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
      liveSteps = prefs.getInt("cached_steps", 0),
      liveHeartRate = prefs.getInt("cached_hr", 0),
      batteryPercent = prefs.getInt("cached_battery", -1)
    )
  )
  val uiState: StateFlow<MiBandState> = _uiState.asStateFlow()

  private val _discoveredDevices = MutableStateFlow<List<DiscoveredBand>>(emptyList())
  val discoveredDevices: StateFlow<List<DiscoveredBand>> = _discoveredDevices.asStateFlow()

  var onLiveMetricReceived: ((type: String, value: Any) -> Unit)? = null

  fun getStoredAuthKey(): String {
    return prefs.getString("mi_auth_key", "") ?: ""
  }

  fun saveAuthKey(key: String) {
    val clean = key.trim().replace("0x", "").replace(" ", "").replace(":", "")
    prefs.edit().putString("mi_auth_key", clean).apply()
    _uiState.value = _uiState.value.copy(authKey = clean)
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

    val cleanKey = authKeyHex.trim().replace("0x", "").replace(" ", "").replace(":", "")
    if (cleanKey.isNotEmpty()) {
      saveAuthKey(cleanKey)
    }

    val name = device.name ?: if (device.address.isNotEmpty()) "Mi Smart Band 5" else "Mi Band"
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

    // Trigger Android system pairing if not already bonded
    if (device.bondState != BluetoothDevice.BOND_BONDED) {
      try {
        device.createBond()
      } catch (e: Exception) {
        Log.w(TAG, "createBond exception: ${e.message}")
      }
    }

    // Connect with TRANSPORT_LE
    bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
  }

  @SuppressLint("MissingPermission")
  fun disconnect() {
    clearGattQueue()
    try {
      bluetoothGatt?.disconnect()
      bluetoothGatt?.close()
    } catch (e: Exception) {
      Log.e(TAG, "Disconnect error: ${e.message}")
    } finally {
      bluetoothGatt = null
      _uiState.value = _uiState.value.copy(
        status = ConnectionStatus.DISCONNECTED
      )
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
        disconnect()
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
      val authService = gatt.getService(AUTH_SERVICE_UUID)
      val authChar = authService?.getCharacteristic(AUTH_CHAR_UUID)

      val key = _uiState.value.authKey
      if (authChar != null && key.length == 32) {
        // Run Huami AES-128 Challenge Handshake
        Log.d(TAG, "Starting Huami AES-128 Auth Handshake with key...")
        gatt.setCharacteristicNotification(authChar, true)
        val descriptor = authChar.getDescriptor(CLIENT_CONFIG_DESCRIPTOR_UUID)
        if (descriptor != null) {
          descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
          enqueueGatt { gatt.writeDescriptor(descriptor) }
        } else {
          sendRequestRandomKey(gatt, authChar)
        }
      } else {
        // Connect directly via standard pairing / standard BLE sensors
        Log.d(TAG, "No 32-char key or standard BLE connection. Subscribing to HR, Steps, and Battery...")
        if (authChar != null) {
          // Send request pairing command [0x01, 0x00] or [0x02, 0x08] which vibrates band and asks "Pair with phone?"
          try {
            authChar.value = byteArrayOf(0x02, 0x08)
            enqueueGatt { gatt.writeCharacteristic(authChar) }
          } catch (e: Exception) {
            Log.w(TAG, "Pairing prompt write failed: ${e.message}")
          }
        }
        _uiState.value = _uiState.value.copy(
          status = ConnectionStatus.CONNECTED,
          errorMessage = null
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
      completeGattOperation()
    }

    @SuppressLint("MissingPermission")
    override fun onCharacteristicRead(
      gatt: BluetoothGatt?,
      characteristic: BluetoothGattCharacteristic?,
      status: Int
    ) {
      Log.d(TAG, "onCharacteristicRead uuid: ${characteristic?.uuid} status: $status")
      val value = characteristic?.value
      if (value != null) {
        when (characteristic.uuid) {
          STEPS_CHAR_UUID -> handleStepsData(value)
          BATTERY_CHAR_UUID, STANDARD_BATTERY_CHAR_UUID -> handleBatteryData(value)
          HR_MEASUREMENT_CHAR_UUID -> handleHeartRateData(value)
        }
      }
      completeGattOperation()
    }

    @SuppressLint("MissingPermission")
    override fun onCharacteristicChanged(
      gatt: BluetoothGatt?,
      characteristic: BluetoothGattCharacteristic?
    ) {
      val value = characteristic?.value ?: return
      val uuid = characteristic.uuid

      if (uuid == AUTH_CHAR_UUID && gatt != null) {
        handleAuthNotification(gatt, characteristic, value)
      } else if (uuid == HR_MEASUREMENT_CHAR_UUID) {
        handleHeartRateData(value)
      } else if (uuid == STEPS_CHAR_UUID) {
        handleStepsData(value)
      } else if (uuid == BATTERY_CHAR_UUID || uuid == STANDARD_BATTERY_CHAR_UUID) {
        handleBatteryData(value)
      }
    }
  }

  @SuppressLint("MissingPermission")
  private fun sendRequestRandomKey(gatt: BluetoothGatt, authChar: BluetoothGattCharacteristic) {
    Log.d(TAG, "Sending Step 1: Request Random Key [0x01, 0x08]")
    enqueueGatt {
      authChar.value = byteArrayOf(0x01, 0x08)
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

    val opCode = value[1].toInt()
    val status = value[2].toInt()

    if (opCode == 0x01) {
      // Challenge received [0x10, 0x01, 0x01, ...16 challenge bytes]
      if (status != 0x01 || value.size < 19) {
        Log.w(TAG, "Challenge rejected or not needed. Falling back to direct connection.")
        _uiState.value = _uiState.value.copy(status = ConnectionStatus.CONNECTED)
        subscribeToSensors(gatt)
        return
      }

      val challenge = value.copyOfRange(3, 19)
      try {
        val authKeyBytes = hexStringToByteArray(_uiState.value.authKey)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        val keySpec = SecretKeySpec(authKeyBytes, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        val encryptedChallenge = cipher.doFinal(challenge)

        val responsePacket = ByteArray(18)
        responsePacket[0] = 0x03
        responsePacket[1] = 0x08
        System.arraycopy(encryptedChallenge, 0, responsePacket, 2, 16)

        enqueueGatt {
          authChar.value = responsePacket
          gatt.writeCharacteristic(authChar)
        }
        Log.d(TAG, "Sent AES-128 challenge response to band.")
      } catch (e: Exception) {
        Log.e(TAG, "AES encryption error: ${e.message}. Proceeding to sensors.")
        _uiState.value = _uiState.value.copy(status = ConnectionStatus.CONNECTED)
        subscribeToSensors(gatt)
      }
    } else if (opCode == 0x03) {
      if (status == 0x01) {
        Log.i(TAG, "MI BAND 5 AUTHENTICATION SUCCESSFUL!")
        _uiState.value = _uiState.value.copy(
          status = ConnectionStatus.CONNECTED,
          errorMessage = null
        )
      } else {
        Log.w(TAG, "Band auth challenge status $status, proceeding with standard services.")
        _uiState.value = _uiState.value.copy(
          status = ConnectionStatus.CONNECTED,
          errorMessage = null
        )
      }
      subscribeToSensors(gatt)
    }
  }

  @SuppressLint("MissingPermission")
  private fun subscribeToSensors(gatt: BluetoothGatt) {
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

    prefs.edit().putInt("cached_steps", steps).apply()
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
    val len = s.length
    val data = ByteArray(len / 2)
    var i = 0
    while (i < len) {
      data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
      i += 2
    }
    return data
  }
}
