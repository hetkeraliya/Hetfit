package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.json.JSONObject
import com.example.ble.BandSampleStore
import com.example.ble.BandSyncService
import com.example.run.RunTrackingService
import com.example.run.ShareHelper
import com.example.ble.ConnectionStatus
import com.example.ble.MiBandDataSyncEngine
import com.example.ble.MiBandManager
import com.example.ui.BandOnboardingScreen
import com.example.ui.MiFitDeviceMenuDialog
import com.example.ui.theme.MyApplicationTheme
import com.example.widget.KestrelWidgetManager
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

// Local bundled web asset guarantees 0ms offline load with NO web page timeout
private const val KESTREL_LOCAL_URL = "file:///android_asset/web/index.html"
private const val TAG = "KestrelApp"

// Kestrel Theme Palette (Dusty Mauve, Obsidian Plum, Warm Cream)
private val PlumBg = Color(0xFF2A232B)
private val PlumCard = Color(0xFF3D3340)
private val MauveAccent = Color(0xFFAA98A9)
private val LilacAccent = Color(0xFFC4B3C2)
private val CreamText = Color(0xFFFFEED7)

class MainActivity : ComponentActivity() {

  private lateinit var miBandManager: MiBandManager
  private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
  private var pendingZeppImport = false
  /** Held so native imports (Zepp/HC) can refresh the dashboard. */
  @Volatile var liveWebView: WebView? = null

  private val filePickerLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) { result ->
    val uris: Array<Uri>? = if (result.resultCode == RESULT_OK) {
      val clip = result.data?.clipData
      val uri = result.data?.data
      when {
        clip != null -> Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
        uri != null -> arrayOf(uri)
        else -> null
      }
    } else {
      null
    }
    fileChooserCallback?.onReceiveValue(uris)
    fileChooserCallback = null
  }

  private val healthConnectPermissionLauncher = registerForActivityResult(
    androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()
  ) { granted ->
    val need = com.example.ble.ZeppHealthConnectImporter.permissionStrings().toSet()
    val prefs = getSharedPreferences("kestrel_ble_prefs", Context.MODE_PRIVATE)
    if (com.example.ble.ZeppHealthConnectImporter.hasEnough(granted)) {
      prefs.edit().putInt("hc_denied", 0).apply()
    } else {
      prefs.edit().putInt("hc_denied", prefs.getInt("hc_denied", 0) + 1).apply()
      Toast.makeText(
        this,
        "Allow Steps, Heart rate, Sleep and Exercise for this app in Health Connect to import Zepp data.",
        Toast.LENGTH_LONG
      ).show()
    }
    if (pendingZeppImport) {
      pendingZeppImport = false
      if (com.example.ble.ZeppHealthConnectImporter.hasEnough(granted)) runZeppImport()
    }
  }

  private var hcAskedThisLaunch = false

  /**
   * Called once per launch right after the Bluetooth / notification prompts: shows the Health Connect permission
   * dialog (this is also what makes the app appear in Health Connect's app list) and imports Zepp data when allowed.
   */
  fun requestHealthConnectAtStartup() {
    if (hcAskedThisLaunch) return
    hcAskedThisLaunch = true
    try {
      val status = androidx.health.connect.client.HealthConnectClient.getSdkStatus(this)
      if (status != androidx.health.connect.client.HealthConnectClient.SDK_AVAILABLE) {
        Toast.makeText(this, "Install or update Health Connect to import Zepp data.", Toast.LENGTH_LONG).show()
        try {
          startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata")))
        } catch (_: Exception) { }
        return
      }
      val client = androidx.health.connect.client.HealthConnectClient.getOrCreate(this)
      MainScope().launch {
        val granted = try {
          client.permissionController.getGrantedPermissions()
        } catch (e: Exception) {
          return@launch
        }
        val need = com.example.ble.ZeppHealthConnectImporter.permissionStrings().toSet()
        if (com.example.ble.ZeppHealthConnectImporter.hasEnough(granted)) {
          runZeppImport()
        } else if (getSharedPreferences("kestrel_ble_prefs", Context.MODE_PRIVATE).getInt("hc_denied", 0) < 3) {
          pendingZeppImport = true
          healthConnectPermissionLauncher.launch(need)
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Health Connect startup request failed: ${e.message}")
    }
  }

  /** Silent import used when the band is connected without an auth key (no dialogs). */
  fun importZeppIfPermitted() {
    try {
      if (androidx.health.connect.client.HealthConnectClient.getSdkStatus(this)
        != androidx.health.connect.client.HealthConnectClient.SDK_AVAILABLE
      ) return
      val client = androidx.health.connect.client.HealthConnectClient.getOrCreate(this)
      MainScope().launch {
        val granted = try { client.permissionController.getGrantedPermissions() } catch (e: Exception) { return@launch }
        if (com.example.ble.ZeppHealthConnectImporter.hasEnough(granted)) runZeppImport()
      }
    } catch (_: Exception) { }
  }

  /** Called from the JS bridge — request permissions if needed, then import. */
  fun importZeppFromHealthConnect() {
    try {
      if (androidx.health.connect.client.HealthConnectClient.getSdkStatus(this)
        != androidx.health.connect.client.HealthConnectClient.SDK_AVAILABLE
      ) {
        Toast.makeText(this, "Install or update Health Connect first", Toast.LENGTH_LONG).show()
        openHealthConnectSettingsSafe()
        return
      }
      val client = androidx.health.connect.client.HealthConnectClient.getOrCreate(this)
      MainScope().launch {
        val granted = try {
          client.permissionController.getGrantedPermissions()
        } catch (e: Exception) {
          Toast.makeText(this@MainActivity, "HC error: ${e.message}", Toast.LENGTH_LONG).show()
          return@launch
        }
        val need = com.example.ble.ZeppHealthConnectImporter.permissionStrings().toSet()
        if (!com.example.ble.ZeppHealthConnectImporter.hasEnough(granted)) {
          pendingZeppImport = true
          healthConnectPermissionLauncher.launch(need)
        } else {
          runZeppImport()
        }
      }
    } catch (e: Exception) {
      Toast.makeText(this, "Health Connect: ${e.message}", Toast.LENGTH_LONG).show()
    }
  }

  private fun openHealthConnectSettingsSafe() {
    try {
      val i = com.example.ble.ZeppHealthConnectImporter.permissionContractIntent(this)
        ?: Intent(Settings.ACTION_SETTINGS)
      startActivity(i)
    } catch (_: Exception) {}
  }

  private fun runZeppImport() {
    MainScope().launch {
      val msg = try {
        com.example.ble.ZeppHealthConnectImporter.importLastDays(applicationContext, 30)
      } catch (e: Exception) {
        "Import failed: ${e.message}"
      }
      Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
      try {
        val store = BandSampleStore.get(applicationContext)
        MiBandDataSyncEngine.pushToWebView(
          liveWebView,
          store,
          miBandManager.uiState.value,
          fullHistory = true
        )
        liveWebView?.evaluateJavascript(
          "window.kestrelToast && window.kestrelToast(${JSONObject.quote(msg)});",
          null
        )
      } catch (_: Exception) {}
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    miBandManager = MiBandManager.get(applicationContext)
    miBandManager.onNoKeySync = { importZeppIfPermitted() }
    // Sleep used to be read over Bluetooth; now it only comes from Health Connect, so drop the old Bluetooth sleep flags once.
    getSharedPreferences("kestrel_ble_prefs", Context.MODE_PRIVATE).let { p ->
      if (!p.getBoolean("sleep_hc_only_v1", false)) {
        Thread { try { BandSampleStore.get(applicationContext).clearSleepRows() } catch (_: Exception) { } }.start()
        p.edit().putBoolean("sleep_hc_only_v1", true).apply()
      }
    }

    setContent {
      MyApplicationTheme {
        KestrelMainScreen(
          miBandManager = miBandManager,
          onShowFileChooser = { callback, params ->
            fileChooserCallback?.onReceiveValue(null)
            fileChooserCallback = callback
            val intent = params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
              addCategory(Intent.CATEGORY_OPENABLE)
              type = "*/*"
            }
            try {
              filePickerLauncher.launch(intent)
              true
            } catch (e: Exception) {
              fileChooserCallback?.onReceiveValue(null)
              fileChooserCallback = null
              false
            }
          }
        )
      }
    }
  }

  // The band connection now lives in BandSyncService, so closing the UI must NOT disconnect it.
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun KestrelMainScreen(
  miBandManager: MiBandManager,
  onShowFileChooser: (ValueCallback<Array<Uri>>?, WebChromeClient.FileChooserParams?) -> Boolean = { _, _ -> false }
) {
  val context = LocalContext.current
  val uiState by miBandManager.uiState.collectAsState()
  val discoveredDevices by miBandManager.discoveredDevices.collectAsState()

  val prefs = remember { context.getSharedPreferences("kestrel_ble_prefs", Context.MODE_PRIVATE) }
  var isOnboardingCompleted by remember { mutableStateOf(prefs.getBoolean("onboarding_completed", false)) }

  var webViewInstance by remember { mutableStateOf<WebView?>(null) }
  var isLoading by remember { mutableStateOf(false) }
  var loadingProgress by remember { mutableFloatStateOf(0f) }
  var hasNetworkError by remember { mutableStateOf(false) }
  var errorMessage by remember { mutableStateOf("") }
  var showDeviceMenu by remember { mutableStateOf(false) }

  val store = remember { BandSampleStore.get(context) }

  /** Pushes the REAL stored band history to the web UI and refreshes the widgets. */
  fun syncFullBandData(webView: WebView?, state: com.example.ble.MiBandState) {
    try {
      KestrelWidgetManager.refresh(context)
      MiBandDataSyncEngine.pushToWebView(webView, store, state, fullHistory = true)
    } catch (e: Exception) {
      Log.e(TAG, "Error pushing band data: ${e.message}")
    }
  }

  // Keep the connection alive in the background service (notification with Sync / Disconnect / Connect).
  LaunchedEffect(uiState.status) {
    if (uiState.status == ConnectionStatus.CONNECTED) {
      BandSyncService.start(context)
    }
  }

  // Live run tracking: stream ticks into the web UI and refresh data when a run is saved.
  LaunchedEffect(Unit) {
    RunTrackingService.listener = { json ->
      webViewInstance?.evaluateJavascript("window.kestrelRunTick && window.kestrelRunTick($json);", null)
    }
    RunTrackingService.onFinished = { startSec ->
      MiBandDataSyncEngine.pushToWebView(webViewInstance, store, miBandManager.uiState.value, fullHistory = false)
      webViewInstance?.evaluateJavascript("window.kestrelRunFinished && window.kestrelRunFinished($startSec);", null)
    }
  }

  // Band started a workout → ask the user whether to track it on the phone (GPS map + band HR).
  LaunchedEffect(miBandManager) {
    miBandManager.onBandWorkoutOffer = { typeCode, label ->
      val safe = label.replace("'", "\\'")
      webViewInstance?.evaluateJavascript(
        "window.kestrelBandWorkoutOffer && window.kestrelBandWorkoutOffer({type:$typeCode,label:'$safe'});",
        null
      )
    }
  }

  // After every finished band sync, push the new history into the dashboard and widgets.
  LaunchedEffect(miBandManager) {
    miBandManager.onHistorySynced = { _ ->
      val st = miBandManager.uiState.value
      KestrelWidgetManager.refresh(context)
      MiBandDataSyncEngine.pushToWebView(webViewInstance, store, st, fullHistory = false)
      // Sleep comes from Health Connect: refresh it right after every Bluetooth sync.
      (context as? MainActivity)?.importZeppIfPermitted()
    }
  }

  // Realtime Data Sync: Send live metrics from BLE directly into WebView DOM and chart engine
  val lastLivePush = remember { mutableMapOf<String, Long>() }
  LaunchedEffect(miBandManager) {
    miBandManager.onLiveMetricReceived = { type, value ->
      if (type == "steps" && value is Number) {
        KestrelWidgetManager.saveHealthData(context, steps = value.toInt())
      } else if (type == "heart_rate" && value is Number) {
        KestrelWidgetManager.saveHealthData(context, heartRate = value.toInt())
      } else if (type == "calories" && value is Number) {
        KestrelWidgetManager.saveHealthData(context, calories = value.toInt())
      }
      if (value is Number) {
        val now = System.currentTimeMillis()
        val minGap = if (type == "heart_rate") 5000L else 10000L
        if (now - (lastLivePush[type] ?: 0L) >= minGap) {
          lastLivePush[type] = now
          MiBandDataSyncEngine.pushLiveMetric(webViewInstance, type, value)
        }
      }
    }
  }

  // Request Bluetooth & Location permissions for BLE scanning
  val requiredPermissions = remember {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
      )
    } else {
      arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
      )
    }
  }

  var hasPermissions by remember {
    mutableStateOf(
      requiredPermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
      }
    )
  }

  val notificationLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission()
  ) { (context as? MainActivity)?.requestHealthConnectAtStartup() }

  fun askNotificationPermission() {
    if (Build.VERSION.SDK_INT >= 33 &&
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
      notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    } else {
      // Nothing else to ask: now show the Health Connect permission dialog.
      (context as? MainActivity)?.requestHealthConnectAtStartup()
    }
  }

  val permissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestMultiplePermissions()
  ) { perms ->
    val allGranted = perms.values.all { it }
    hasPermissions = allGranted
    if (allGranted) {
      askNotificationPermission()
      miBandManager.startScan()
    } else {
      Log.w(TAG, "Some Bluetooth/Location permissions were not granted.")
      (context as? MainActivity)?.requestHealthConnectAtStartup()
    }
  }

  val enableBtLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartActivityForResult()
  ) {
    if (miBandManager.isBluetoothEnabled() && hasPermissions) {
      miBandManager.startScan()
    }
  }

  LaunchedEffect(Unit) {
    if (!hasPermissions) {
      permissionLauncher.launch(requiredPermissions)
    } else {
      askNotificationPermission()
      // Show the band notification right away and reconnect to the last band unless the user disconnected it.
      if (miBandManager.hasSavedDevice()) {
        BandSyncService.start(context)
        if (!miBandManager.wasUserDisconnected()) miBandManager.connectSaved()
      }
    }
  }

  // First Onboarding Screen: User connects band first, pulls all telemetry, then enters dashboard
  if (!isOnboardingCompleted) {
    BandOnboardingScreen(
      uiState = uiState,
      discoveredDevices = discoveredDevices,
      hasPermissions = hasPermissions,
      onRequestPermissions = { permissionLauncher.launch(requiredPermissions) },
      isBluetoothEnabled = miBandManager.isBluetoothEnabled(),
      onEnableBluetoothClick = {
        try {
          enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } catch (_: Exception) {
          try {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
          } catch (_: Exception) {}
        }
      },
      isLocationEnabled = miBandManager.isLocationEnabled(),
      onOpenLocationSettingsClick = {
        try {
          context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        } catch (_: Exception) {}
      },
      onScanClick = {
        if (!hasPermissions) {
          permissionLauncher.launch(requiredPermissions)
        } else {
          miBandManager.startScan()
        }
      },
      onStopScanClick = { miBandManager.stopScan() },
      onConnectClick = { band, authKey ->
        miBandManager.connectToDevice(band.device, authKey)
      },
      onConnectByAddressClick = { address, authKey ->
        miBandManager.connectByAddress(address, authKey)
      },
      onSaveAuthKey = { miBandManager.saveAuthKey(it) },
      onEnterDashboardClick = {
        prefs.edit().putBoolean("onboarding_completed", true).apply()
        isOnboardingCompleted = true
        syncFullBandData(webViewInstance, uiState)
      },
      onSkipClick = {
        prefs.edit().putBoolean("onboarding_completed", true).apply()
        isOnboardingCompleted = true
      }
    )
    return
  }

  // Hardware Back button handling inside WebView
  BackHandler(enabled = webViewInstance?.canGoBack() == true) {
    webViewInstance?.goBack()
  }

  Scaffold(
    modifier = Modifier
      .fillMaxSize()
      .testTag("kestrel_main_scaffold"),
    containerColor = Color(0xFFFFEED7)
  ) { innerPadding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
    ) {
      // 1. Android WebView loading local bundled Kestrel Web App (0ms latency, 100% offline, zero timeout)
      AndroidView(
        modifier = Modifier
          .fillMaxSize()
          .testTag("kestrel_web_view"),
        factory = { ctx ->
          WebView(ctx).apply {
            webViewInstance = this
            var c: Context? = ctx
            while (c is android.content.ContextWrapper) {
              if (c is MainActivity) { c.liveWebView = this; break }
              c = c.baseContext
            }
            setBackgroundColor(android.graphics.Color.parseColor("#FFEED7"))

            settings.apply {
              javaScriptEnabled = true
              domStorageEnabled = true
              databaseEnabled = true
              allowFileAccess = true
              allowContentAccess = true
              loadWithOverviewMode = true
              useWideViewPort = true
              mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
              cacheMode = WebSettings.LOAD_DEFAULT
              userAgentString = "$userAgentString KestrelAndroidApp/1.0"
            }

            addJavascriptInterface(
              KestrelAndroidBridge(ctx) { action ->
                if (action == "open_bluetooth_menu") {
                  showDeviceMenu = true
                }
              },
              "KestrelAndroidBridge"
            )

            webChromeClient = object : WebChromeClient() {
              override fun onProgressChanged(view: WebView?, newProgress: Int) {
                loadingProgress = newProgress / 100f
                if (newProgress >= 100) {
                  isLoading = false
                }
              }

              override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
              ): Boolean {
                return onShowFileChooser(filePathCallback, fileChooserParams)
              }

              override fun onPermissionRequest(request: PermissionRequest?) {
                try {
                  request?.grant(request.resources)
                } catch (e: Exception) {
                  Log.e(TAG, "Permission grant error: ${e.message}")
                }
              }

              override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                Log.d(TAG, "[WebViewConsole] ${consoleMessage?.message()}")
                return true
              }
            }

            webViewClient = object : WebViewClient() {
              override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                isLoading = true
                hasNetworkError = false
              }

              override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isLoading = false

                // INJECT CSS: Keep dashboard and replace-btn visible, remove obsolete Web BLE buttons
                view?.evaluateJavascript(
                  """
                  (function() {
                    const style = document.createElement('style');
                    style.id = 'kestrel-cleanup-style';
                    style.innerHTML = `
                      #connectBle, #btnBleConnect, [data-action="ble-connect"], 
                      .btn-connect-ble, .ble-status, #bleStatus {
                        display: none !important;
                        visibility: hidden !important;
                        height: 0 !important;
                        overflow: hidden !important;
                      }
                      #replace-btn {
                        display: inline-flex !important;
                        visibility: visible !important;
                      }
                      #ble-dash-btn {
                        display: inline-flex !important;
                        visibility: visible !important;
                      }
                    `;
                    if (!document.getElementById('kestrel-cleanup-style')) {
                      document.head.appendChild(style);
                    }
                    const dashView = document.getElementById('dashboard-view');
                    const uploadView = document.getElementById('upload-view');
                    if (dashView && (!uploadView || uploadView.classList.contains('hidden'))) {
                      dashView.classList.remove('hidden');
                    }
                    console.log('[Kestrel Native] Web Dashboard activated successfully.');
                  })();
                  """.trimIndent(),
                  null
                )

                // The dashboard just (re)loaded: hand it the real history the band has given us so far.
                syncFullBandData(view, miBandManager.uiState.value)
              }

              override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
              ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                  isLoading = false
                  hasNetworkError = true
                  errorMessage = error?.description?.toString() ?: "Loading offline"
                }
              }
            }

            loadUrl(KESTREL_LOCAL_URL)
          }
        },
        update = {
          webViewInstance = it
        }
      )

      // Top Progress Indicator
      if (isLoading && loadingProgress < 0.99f) {
        LinearProgressIndicator(
          progress = { loadingProgress },
          modifier = Modifier
            .fillMaxWidth()
            .height(2.5.dp)
            .align(Alignment.TopCenter),
          color = MauveAccent,
          trackColor = PlumCard
        )
      }

      // Offline / Error Fallback Screen
      if (hasNetworkError) {
        Surface(
          modifier = Modifier.fillMaxSize(),
          color = PlumBg
        ) {
          Column(
            modifier = Modifier
              .fillMaxSize()
              .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
          ) {
            Icon(
              imageVector = Icons.Default.Warning,
              contentDescription = "Offline",
              tint = Color(0xFFF59E0B),
              modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
              text = "Kestrel Health Engine",
              fontSize = 20.sp,
              fontWeight = FontWeight.Bold,
              color = CreamText
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
              text = "Tap below to reload local assets or pair your Mi Band 5.",
              fontSize = 13.sp,
              color = MauveAccent,
              textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              Button(
                onClick = {
                  hasNetworkError = false
                  isLoading = true
                  webViewInstance?.loadUrl(KESTREL_LOCAL_URL)
                },
                colors = ButtonDefaults.buttonColors(
                  containerColor = MauveAccent,
                  contentColor = PlumBg
                ),
                shape = RoundedCornerShape(12.dp)
              ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Reload Local App", fontWeight = FontWeight.Bold)
              }

              Button(
                onClick = { showDeviceMenu = true },
                colors = ButtonDefaults.buttonColors(
                  containerColor = Color(0xFF10B981),
                  contentColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
              ) {
                Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Mi Band Menu", fontWeight = FontWeight.Bold)
              }
            }
          }
        }
      }

      // Mi Fit & Zepp Life Style Bluetooth Connection Dialog
      if (showDeviceMenu) {
        MiFitDeviceMenuDialog(
          uiState = uiState,
          discoveredDevices = discoveredDevices,
          onScanClick = { miBandManager.startScan() },
          onStopScanClick = { miBandManager.stopScan() },
          onConnectClick = { band, authKey ->
            miBandManager.connectToDevice(band.device, authKey)
          },
          onDisconnectClick = { miBandManager.disconnect() },
          onSaveAuthKey = { key -> miBandManager.saveAuthKey(key) },
          onSyncAllDataClick = {
            miBandManager.syncNow()
            syncFullBandData(webViewInstance, uiState)
          },
          onImportGadgetbridgeClick = {
            showDeviceMenu = false
            webViewInstance?.post {
              webViewInstance?.evaluateJavascript("if (typeof showUpload === 'function') showUpload();", null)
            }
          },
          onReopenOnboarding = { isOnboardingCompleted = false },
          onDismiss = {
            miBandManager.stopScan()
            showDeviceMenu = false
          }
        )
      }
    }
  }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
  Text(text = "Hello $name!", modifier = modifier)
}

class KestrelAndroidBridge(
  private val context: Context,
  private val onAction: (String) -> Unit
) {
  @JavascriptInterface
  fun openBluetoothMenu() {
    onAction("open_bluetooth_menu")
  }

  @JavascriptInterface
  fun showToast(message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
  }

  @JavascriptInterface
  fun isAndroidApp(): Boolean = true

  @JavascriptInterface
  fun getAppVersion(): String = "1.0.0"

  @JavascriptInterface
  fun syncHealthToWidget(
    steps: Int,
    calories: Int,
    hr: Int,
    sleepMinutes: Int,
    workoutName: String,
    workoutMins: Int,
    streak: Int
  ) {
    KestrelWidgetManager.saveHealthData(
      context = context,
      steps = steps,
      calories = calories,
      heartRate = hr,
      sleepMinutes = sleepMinutes,
      workoutName = workoutName,
      workoutMins = workoutMins,
      streak = streak
    )
  }

  // ---------------- Running / activity recording ----------------
  @JavascriptInterface fun startRun(type: Int, voice: Boolean) { RunTrackingService.send(context, RunTrackingService.ACTION_START, type, voice) }
  @JavascriptInterface fun pauseRun() { RunTrackingService.send(context, RunTrackingService.ACTION_PAUSE) }
  @JavascriptInterface fun resumeRun() { RunTrackingService.send(context, RunTrackingService.ACTION_RESUME) }
  @JavascriptInterface fun finishRun() { RunTrackingService.send(context, RunTrackingService.ACTION_FINISH) }
  @JavascriptInterface fun discardRun() { RunTrackingService.send(context, RunTrackingService.ACTION_DISCARD) }
  @JavascriptInterface fun getRunState(): String = RunTrackingService.stateJson
  @JavascriptInterface fun getLiveTrack(): String = RunTrackingService.liveTrackJson()

  /** User tapped Yes on the “band started a workout — track on phone?” dialog. */
  @JavascriptInterface fun acceptBandWorkout(type: Int): String =
    MiBandManager.get(context).acceptBandWorkoutOffer(type)

  /** User tapped No — band keeps recording alone. */
  @JavascriptInterface fun declineBandWorkout() {
    MiBandManager.get(context).declineBandWorkoutOffer()
  }

  @JavascriptInterface
  fun getRunTrack(startSec: Long): String {
    val arr = org.json.JSONArray()
    BandSampleStore.get(context).getTrack(startSec, 2500).forEach {
      arr.put(org.json.JSONArray().put(it.ts).put(it.lat).put(it.lon).put(Math.round(it.alt * 10) / 10.0).put(it.hr))
    }
    return arr.toString()
  }

  @JavascriptInterface
  fun deleteRun(startSec: Long) { BandSampleStore.get(context).deleteWorkout(startSec) }

  /** mode: "share" opens the share sheet, "save" writes to Downloads/Kestrel. */
  @JavascriptInterface
  fun exportGpx(startSec: Long, name: String, mode: String): String {
    val gpx = ShareHelper.buildGpx(BandSampleStore.get(context), startSec, name)
    val file = (if (name.isBlank()) "activity" else name).replace(' ', '_') + "_" + startSec + ".gpx"
    return if (mode == "share") ShareHelper.shareTextFile(context, file, "application/gpx+xml", gpx)
    else ShareHelper.saveTextFile(context, file, "application/gpx+xml", gpx)
  }

  @JavascriptInterface
  fun shareImage(base64Png: String, fileName: String, text: String, target: String): String =
    ShareHelper.shareImage(context, base64Png, fileName, text, target)

  @JavascriptInterface
  fun saveImageToGallery(base64Png: String, fileName: String): String = ShareHelper.saveImageToGallery(context, base64Png, fileName)

  @JavascriptInterface
  fun shareText(text: String) { ShareHelper.shareText(context, text) }

  @JavascriptInterface
  fun saveFile(name: String, mime: String, text: String): String = ShareHelper.saveTextFile(context, name, mime, text)

  /** Sleep stages for the night ending on [dateKey] (yyyy-MM-dd): window is previous day 18:00 .. that day 18:00. */
  @JavascriptInterface
  fun getSleepNight(dateKey: String): String {
    val o = org.json.JSONObject()
    val arr = org.json.JSONArray()
    try {
      val d = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(dateKey)
      val cal = java.util.Calendar.getInstance().apply { time = d!! }
      val dayStart = cal.timeInMillis / 1000
      BandSampleStore.get(context).sleepSegments(dayStart - 6 * 3600, dayStart + 18 * 3600).forEach {
        arr.put(org.json.JSONArray().put(it[0]).put(it[1]).put(it[2]))
      }
    } catch (_: Exception) { }
    o.put("segs", arr)
    return o.toString()
  }

  // ---------------- Band settings / alarms / notifications ----------------
  @JavascriptInterface fun getBandSettings(): String = MiBandManager.get(context).getBandSettingsJson()

  /** Pull the next Clock-app alarm from the phone and write it to band slot 0. */
  @JavascriptInterface fun syncPhoneAlarmsToBand(): String {
    var result = "Failed"
    val latch = java.util.concurrent.CountDownLatch(1)
    android.os.Handler(android.os.Looper.getMainLooper()).post {
      result = MiBandManager.get(context).syncPhoneAlarmsToBand()
      latch.countDown()
    }
    try { latch.await(4, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
    return result
  }

  @JavascriptInterface
  fun saveBandSettings(json: String): String {
    val mgr = MiBandManager.get(context)
    var result = ""
    val latch = java.util.concurrent.CountDownLatch(1)
    android.os.Handler(android.os.Looper.getMainLooper()).post { result = mgr.saveBandSettings(json); latch.countDown() }
    latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
    return result
  }

  @JavascriptInterface fun getBandStatus(): String = MiBandManager.get(context).statusJson()

  @JavascriptInterface
  fun measureHeartRateNow(): String {
    val mgr = MiBandManager.get(context)
    var result = "Band not ready"
    val latch = java.util.concurrent.CountDownLatch(1)
    android.os.Handler(android.os.Looper.getMainLooper()).post { result = mgr.measureHeartRateNow(); latch.countDown() }
    latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
    return result
  }

  @JavascriptInterface
  fun getNotifyConfig(): String {
    val p = context.getSharedPreferences("band_notify", Context.MODE_PRIVATE)
    val o = org.json.JSONObject()
    o.put("enabled", p.getBoolean("enabled", false))
    o.put("all", p.getBoolean("all", false))
    o.put("packages", org.json.JSONArray(p.getStringSet("packages", com.example.ble.BandNotificationListener.DEFAULT_PACKAGES)!!.toList()))
    o.put("access", isNotificationAccessGranted())
    return o.toString()
  }

  @JavascriptInterface
  fun setNotifyConfig(json: String) {
    val o = org.json.JSONObject(json)
    val set = HashSet<String>()
    val arr = o.optJSONArray("packages")
    if (arr != null) for (i in 0 until arr.length()) set.add(arr.getString(i))
    context.getSharedPreferences("band_notify", Context.MODE_PRIVATE).edit()
      .putBoolean("enabled", o.optBoolean("enabled")).putBoolean("all", o.optBoolean("all")).putStringSet("packages", set).apply()
  }

  private fun isNotificationAccessGranted(): Boolean =
    androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

  @JavascriptInterface
  fun openNotificationAccess() {
    val i = Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(i)
  }

  @JavascriptInterface
  fun sendTestNotification(): Boolean =
    MiBandManager.get(context).sendBandNotification("Kestrel", "Test notification from your phone", 11)

  @JavascriptInterface fun findBand(): String = runOnBle { MiBandManager.get(context).findBand(true) }
  @JavascriptInterface fun stopFindBand(): String = runOnBle { MiBandManager.get(context).stopFindBand() }
  @JavascriptInterface fun syncTimeToBand(): String = runOnBle { MiBandManager.get(context).syncTimeToBand() }
  @JavascriptInterface fun setStepGoal(steps: Int): String = runOnBle { MiBandManager.get(context).setStepGoal(steps) }
  @JavascriptInterface fun sendWeather(city: String, tempC: Int, condition: Int): String =
    runOnBle { MiBandManager.get(context).sendWeather(city, tempC, condition) }
  @JavascriptInterface fun sendCallAlert(name: String, ringing: Boolean): Boolean =
    MiBandManager.get(context).sendCallAlert(name, ringing)
  @JavascriptInterface fun sendReminder(slot: Int, enabled: Boolean, text: String, atMs: Double, repeat: String): String =
    runOnBle { MiBandManager.get(context).sendReminder(slot, enabled, text, atMs.toLong(), repeat) }
  @JavascriptInterface fun sendMusicInfo(artist: String, album: String, track: String, playing: Boolean, positionSec: Int, durationSec: Int): String =
    runOnBle { MiBandManager.get(context).sendMusicInfo(artist, album, track, playing, positionSec, durationSec) }
  @JavascriptInterface fun sendMusicVolume(vol: Int): String =
    runOnBle { MiBandManager.get(context).sendMusicVolume(vol) }
  @JavascriptInterface fun factoryResetDisplay(): String =
    runOnBle { MiBandManager.get(context).factoryResetDisplay() }
  @JavascriptInterface fun pushNowPlayingToBand(): String {
    return try {
      val am = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as android.media.session.MediaSessionManager
      val controllers = am.getActiveSessions(
        android.content.ComponentName(context, com.example.ble.BandNotificationListener::class.java)
      )
      if (controllers.isEmpty()) return "No media session — play music and grant notification access"
      val c = controllers.first()
      val meta = c.metadata
      val st = c.playbackState
      val artist = meta?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
      val album = meta?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
      val track = meta?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE).orEmpty()
      val dur = ((meta?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L) / 1000L).toInt()
      val pos = ((st?.position ?: 0L) / 1000L).toInt()
      val playing = st?.state == android.media.session.PlaybackState.STATE_PLAYING
      runOnBle { MiBandManager.get(context).sendMusicInfo(artist, album, track, playing, pos, dur) }
    } catch (e: Exception) {
      "Need notification access for media: ${e.message}"
    }
  }

  private fun runOnBle(block: () -> String): String {
    var result = "Failed"
    val latch = java.util.concurrent.CountDownLatch(1)
    android.os.Handler(android.os.Looper.getMainLooper()).post {
      result = try { block() } catch (e: Exception) { e.message ?: "Error" }
      latch.countDown()
    }
    try { latch.await(4, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
    return result
  }

  // ---------------- Band data diagnostics / repair ----------------
  @JavascriptInterface fun getStoreStats(): String = BandSampleStore.get(context).diagnostics()
  @JavascriptInterface fun syncBandNow() { android.os.Handler(android.os.Looper.getMainLooper()).post { MiBandManager.get(context).syncNow() } }
  @JavascriptInterface fun resyncEverything() { MiBandManager.get(context).resetAndResync() }
  @JavascriptInterface
  fun exportSamplesCsv(): String {
    val sb = StringBuilder("timestamp,kind,intensity,steps,heart_rate,sleep,deep,rem\n")
    BandSampleStore.get(context).forEachChunk(0L, 5000) { rows ->
      rows.forEach { r -> sb.append(r.ts).append(',').append(r.kind).append(',').append(r.intensity).append(',').append(r.steps).append(',')
        .append(r.hr).append(',').append(r.sleep).append(',').append(r.deep).append(',').append(r.rem).append('\n') }
    }
    return ShareHelper.saveTextFile(context, "band_minutes.csv", "text/csv", sb.toString())
  }

  // ---------------- Zepp data on this phone (via Health Connect) ----------------
  @JavascriptInterface fun getHealthConnectStatus(): String =
    com.example.ble.ZeppHealthConnectImporter.status(context)

  @JavascriptInterface fun openHealthConnectSettings() {
    try {
      val i = com.example.ble.ZeppHealthConnectImporter.permissionContractIntent(context)
        ?: Intent(Settings.ACTION_SETTINGS)
      i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(i)
    } catch (_: Exception) {
      Toast.makeText(context, "Open Health Connect from system settings", Toast.LENGTH_SHORT).show()
    }
  }

  /** Import steps / HR / sleep / workouts Zepp wrote to Health Connect. */
  @JavascriptInterface fun importFromZepp(): String {
    (context as? MainActivity)?.importZeppFromHealthConnect()
      ?: run {
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
          val msg = try {
            com.example.ble.ZeppHealthConnectImporter.importLastDays(context.applicationContext, 30)
          } catch (e: Exception) { "Import failed: ${e.message}" }
          Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
      }
    return "Importing from Zepp / Health Connect…"
  }
}
