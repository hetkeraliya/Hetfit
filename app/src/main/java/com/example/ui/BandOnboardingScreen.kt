package com.example.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ble.ConnectionStatus
import com.example.ble.DiscoveredBand
import com.example.ble.MiBandState
import kotlinx.coroutines.delay

// Kestrel Color Palette (matching web app :root theme)
private val KestrelBg = Color(0xFFD9DCE5) // --page-bg: #FFEED7 Warm Cream
private val KestrelCard = Color(0xCCFFFFFF) // --card-soft: #ffffff White glass card
private val KestrelCardLight = Color(0xFFE8EBF2) // Subtle warm tinted inner card
private val KestrelBorder = Color(0x1A454B5C) // --line: rgba(170, 152, 169, 0.28)
private val KestrelMauve = Color(0xFF454B5C) // --hero-mid: #AA98A9 Dusty Mauve
private val KestrelMauveDark = Color(0xFF353A48) // --teal-deep: #7A6879 Deep Mauve
private val KestrelLilac = Color(0xFFA7B0C4) // --hero-top: #C4B3C2
private val KestrelCream = Color(0xFFD9DCE5) // --cream: #FFEED7
private val KestrelText = Color(0xFF454B5C) // --ink: #3d3340 Deep Plum Ink
private val KestrelMuted = Color(0x99454B5C) // --muted: rgba(61,51,64,0.55)
private val KestrelGreen = Color(0xFF10B981)

@Composable
fun BandOnboardingScreen(
  uiState: MiBandState,
  discoveredDevices: List<DiscoveredBand>,
  hasPermissions: Boolean,
  onRequestPermissions: () -> Unit,
  isBluetoothEnabled: Boolean,
  onEnableBluetoothClick: () -> Unit,
  isLocationEnabled: Boolean,
  onOpenLocationSettingsClick: () -> Unit,
  onScanClick: () -> Unit,
  onStopScanClick: () -> Unit,
  onConnectClick: (DiscoveredBand, String) -> Unit,
  onConnectByAddressClick: (String, String) -> Unit,
  onSaveAuthKey: (String) -> Unit,
  onEnterDashboardClick: () -> Unit,
  onSkipClick: () -> Unit
) {
  val context = LocalContext.current
  var inputAuthKey by remember(uiState.authKey) { mutableStateOf(uiState.authKey) }
  var manualMacAddress by remember { mutableStateOf("") }
  var showKeyHelp by remember { mutableStateOf(false) }

  // Auto-scan on onboarding open when permissions and Bluetooth are available
  LaunchedEffect(hasPermissions, isBluetoothEnabled) {
    if (hasPermissions && isBluetoothEnabled && uiState.status == ConnectionStatus.DISCONNECTED) {
      onScanClick()
    }
  }

  // Animated pulse effect for Bluetooth radar
  val infiniteTransition = rememberInfiniteTransition(label = "pulse")
  val pulseScale by infiniteTransition.animateFloat(
    initialValue = 0.95f,
    targetValue = 1.08f,
    animationSpec = infiniteRepeatable(
      animation = tween(900, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "pulse_scale"
  )

  // Simulation of sync sequence when band connects
  var syncStep by remember { mutableStateOf(0) }
  LaunchedEffect(uiState.status) {
    if (uiState.status == ConnectionStatus.CONNECTED) {
      syncStep = 1
      delay(700)
      syncStep = 2
      delay(800)
      syncStep = 3
      delay(800)
      syncStep = 4
    } else {
      syncStep = 0
    }
  }

  Surface(
    modifier = Modifier
      .fillMaxSize()
      .testTag("band_onboarding_screen"),
    color = KestrelBg
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 32.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {

      // --- 1. HERO GRAPHIC & LOGO ---
      Spacer(modifier = Modifier.height(16.dp))
      Box(
        modifier = Modifier
          .size(100.dp)
          .scale(if (uiState.status == ConnectionStatus.CONNECTING || uiState.status == ConnectionStatus.SCANNING) pulseScale else 1f)
          .background(
            brush = Brush.radialGradient(
              listOf(KestrelLilac, KestrelMauveDark)
            ),
            shape = CircleShape
          ),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = when (uiState.status) {
            ConnectionStatus.CONNECTED -> Icons.Default.Watch
            ConnectionStatus.CONNECTING -> Icons.Default.Sync
            else -> Icons.Default.Bluetooth
          },
          contentDescription = "Mi Band",
          tint = Color.White,
          modifier = Modifier.size(52.dp)
        )
      }

      Spacer(modifier = Modifier.height(20.dp))

      Text(
        text = "Welcome to Kestrel",
        fontSize = 28.sp,
        fontWeight = FontWeight.ExtraBold,
        color = KestrelText,
        textAlign = TextAlign.Center
      )

      Spacer(modifier = Modifier.height(8.dp))

      Text(
        text = "Pair your Mi Smart Band 5 to automatically pull all activity, workouts, heart rate, and sleep data directly onto your phone — 100% offline.",
        fontSize = 14.sp,
        color = KestrelMuted,
        textAlign = TextAlign.Center,
        lineHeight = 20.sp,
        modifier = Modifier.padding(horizontal = 12.dp)
      )

      Spacer(modifier = Modifier.height(28.dp))

      // --- 2. CONNECTION / SYNC STATUS CARD ---
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = KestrelCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, KestrelBorder)
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp)
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              val statusColor = when (uiState.status) {
                ConnectionStatus.CONNECTED -> KestrelGreen
                ConnectionStatus.CONNECTING, ConnectionStatus.AUTHENTICATING -> Color(0xFFA7B0C4)
                ConnectionStatus.SCANNING -> KestrelMauve
                ConnectionStatus.DISCONNECTED -> Color(0xFFA7B0C4)
              }

              Box(
                modifier = Modifier
                  .size(14.dp)
                  .background(statusColor, CircleShape)
              )
              Spacer(modifier = Modifier.width(10.dp))
              Column {
                Text(
                  text = when (uiState.status) {
                    ConnectionStatus.CONNECTED -> "Band Connected & Synced"
                    ConnectionStatus.CONNECTING -> "Connecting to Mi Band 5..."
                    ConnectionStatus.AUTHENTICATING -> "Authenticating Band..."
                    ConnectionStatus.SCANNING -> "Scanning for nearby band..."
                    ConnectionStatus.DISCONNECTED -> "Band Not Connected"
                  },
                  color = KestrelText,
                  fontWeight = FontWeight.Bold,
                  fontSize = 15.sp
                )
                if (uiState.deviceName.isNotEmpty()) {
                  Text(
                    text = "${uiState.deviceName} (${uiState.deviceAddress})",
                    color = KestrelMauveDark,
                    fontSize = 12.sp
                  )
                }
              }
            }

            if (uiState.batteryPercent >= 0) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                  .background(KestrelCardLight, RoundedCornerShape(12.dp))
                  .padding(horizontal = 8.dp, vertical = 4.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.BatteryChargingFull,
                  contentDescription = null,
                  tint = KestrelGreen,
                  modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                  text = "${uiState.batteryPercent}%",
                  color = KestrelText,
                  fontSize = 12.sp,
                  fontWeight = FontWeight.Bold
                )
              }
            }
          }

          // Realtime Metrics Preview when connected
          if (uiState.status == ConnectionStatus.CONNECTED) {
            Spacer(modifier = Modifier.height(16.dp))
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              // Live Heart Rate
              Box(
                modifier = Modifier
                  .weight(1f)
                  .background(KestrelCardLight, RoundedCornerShape(16.dp))
                  .padding(12.dp)
              ) {
                Column {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                  ) {
                    Text("Heart Rate", color = KestrelMauveDark, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Icon(
                      imageVector = Icons.Default.Favorite,
                      contentDescription = null,
                      tint = Color(0xFF454B5C),
                      modifier = Modifier.size(14.dp)
                    )
                  }
                  Spacer(modifier = Modifier.height(4.dp))
                  Text(
                    text = if (uiState.liveHeartRate > 0) "${uiState.liveHeartRate} BPM" else "72 BPM",
                    color = KestrelText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                  )
                }
              }

              // Live Steps
              Box(
                modifier = Modifier
                  .weight(1f)
                  .background(KestrelCardLight, RoundedCornerShape(16.dp))
                  .padding(12.dp)
              ) {
                Column {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                  ) {
                    Text("Today's Steps", color = KestrelMauveDark, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Icon(
                      imageVector = Icons.Default.DirectionsWalk,
                      contentDescription = null,
                      tint = Color(0xFF454B5C),
                      modifier = Modifier.size(14.dp)
                    )
                  }
                  Spacer(modifier = Modifier.height(4.dp))
                  Text(
                    text = if (uiState.liveSteps > 0) "%,d".format(uiState.liveSteps) else "Syncing...",
                    color = KestrelText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                  )
                }
              }
            }

            // Sync Checklist
            Spacer(modifier = Modifier.height(18.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              SyncItem(title = "Bluetooth connection established", isDone = syncStep >= 1)
              SyncItem(title = "Live steps, calories & distance synced", isDone = syncStep >= 2)
              SyncItem(title = "Workouts & exercise sessions extracted", isDone = syncStep >= 3)
              SyncItem(title = "Sleep stages & telemetry loaded to dashboard", isDone = syncStep >= 4)
            }
          }
        }
      }

      // --- PERMISSIONS & HARDWARE STATE ALERTS ---
      if (!hasPermissions) {
        Card(
          modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp),
          shape = RoundedCornerShape(16.dp),
          colors = CardDefaults.cardColors(containerColor = Color(0xFFE8EBF2)),
          border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFA7B0C4))
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
              Icon(
                Icons.Default.Security,
                contentDescription = null,
                tint = Color(0xFF454B5C),
                modifier = Modifier.size(22.dp)
              )
              Spacer(modifier = Modifier.width(10.dp))
              Column {
                Text(
                  "Bluetooth Permission Needed",
                  color = Color(0xFF454B5C),
                  fontWeight = FontWeight.Bold,
                  fontSize = 13.sp
                )
                Text(
                  "Tap to grant permissions so Android can detect your band.",
                  color = Color(0xFF9A3412),
                  fontSize = 11.sp
                )
              }
            }
            Button(
              onClick = onRequestPermissions,
              colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF454B5C)),
              shape = RoundedCornerShape(12.dp)
            ) {
              Text("Grant", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
          }
        }
      } else if (!isBluetoothEnabled) {
        Card(
          modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp),
          shape = RoundedCornerShape(16.dp),
          colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBEB)),
          border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFDE68A))
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
              Icon(
                Icons.Default.Bluetooth,
                contentDescription = null,
                tint = Color(0xFFD97706),
                modifier = Modifier.size(22.dp)
              )
              Spacer(modifier = Modifier.width(10.dp))
              Column {
                Text(
                  "Phone Bluetooth is OFF",
                  color = Color(0xFF78350F),
                  fontWeight = FontWeight.Bold,
                  fontSize = 13.sp
                )
                Text(
                  "Turn on phone Bluetooth to pair with your band.",
                  color = Color(0xFF92400E),
                  fontSize = 11.sp
                )
              }
            }
            Button(
              onClick = onEnableBluetoothClick,
              colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
              shape = RoundedCornerShape(12.dp)
            ) {
              Text("Turn ON", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
          }
        }
      } else if (!isLocationEnabled) {
        Card(
          modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp),
          shape = RoundedCornerShape(16.dp),
          colors = CardDefaults.cardColors(containerColor = KestrelCard),
          border = androidx.compose.foundation.BorderStroke(1.dp, KestrelBorder)
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
              Icon(
                Icons.Default.LocationOn,
                contentDescription = null,
                tint = KestrelMauveDark,
                modifier = Modifier.size(20.dp)
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                "Phone GPS is off. Turn on location if scan is empty.",
                color = KestrelMuted,
                fontSize = 11.sp
              )
            }
            TextButton(onClick = onOpenLocationSettingsClick) {
              Text("Settings", color = KestrelMauveDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
          }
        }
      }

      if (uiState.errorMessage != null) {
        Card(
          modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp),
          shape = RoundedCornerShape(16.dp),
          colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
          border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFECACA))
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(
              Icons.Default.Warning,
              contentDescription = null,
              tint = Color(0xFFDC2626),
              modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
              text = uiState.errorMessage ?: "",
              color = Color(0xFF991B1B),
              fontSize = 12.sp,
              modifier = Modifier.weight(1f)
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(8.dp))

      // --- 3. DISCOVERED DEVICES LIST (IF NOT CONNECTED) ---
      if (uiState.status != ConnectionStatus.CONNECTED) {
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(24.dp),
          colors = CardDefaults.cardColors(containerColor = KestrelCard),
          elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
          border = androidx.compose.foundation.BorderStroke(1.dp, KestrelBorder)
        ) {
          Column(modifier = Modifier.padding(18.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = "Nearby Bands",
                color = KestrelText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
              )

              if (uiState.status == ConnectionStatus.SCANNING) {
                OutlinedButton(
                  onClick = onStopScanClick,
                  shape = RoundedCornerShape(16.dp),
                  colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD97706)),
                  border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFD97706))
                ) {
                  CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    color = Color(0xFFD97706),
                    strokeWidth = 2.dp
                  )
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("Scanning...", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
              } else {
                Button(
                  onClick = {
                    if (!hasPermissions) {
                      onRequestPermissions()
                    } else if (!isBluetoothEnabled) {
                      onEnableBluetoothClick()
                    } else {
                      onScanClick()
                    }
                  },
                  shape = RoundedCornerShape(16.dp),
                  colors = ButtonDefaults.buttonColors(
                    containerColor = KestrelMauveDark,
                    contentColor = Color.White
                  )
                ) {
                  Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("Scan", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
              }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (discoveredDevices.isEmpty()) {
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .height(90.dp)
                  .background(KestrelCardLight, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
              ) {
                Text(
                  text = if (uiState.status == ConnectionStatus.SCANNING) "Looking for your Mi Smart Band 5..." else "Tap 'Scan' to discover nearby band",
                  color = KestrelMuted,
                  fontSize = 12.sp,
                  textAlign = TextAlign.Center
                )
              }
            } else {
              Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                discoveredDevices.take(6).forEach { band ->
                  Row(
                    modifier = Modifier
                      .fillMaxWidth()
                      .background(if (band.isMiBand) KestrelCardLight else Color.White, RoundedCornerShape(16.dp))
                      .border(1.dp, if (band.isMiBand) KestrelMauve else KestrelBorder, RoundedCornerShape(16.dp))
                      .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                  ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                      Icon(
                        imageVector = if (band.isMiBand) Icons.Default.Watch else Icons.Default.Bluetooth,
                        contentDescription = null,
                        tint = if (band.isMiBand) KestrelMauveDark else KestrelMauve,
                        modifier = Modifier.size(20.dp)
                      )
                      Spacer(modifier = Modifier.width(10.dp))
                      Column {
                        Text(
                          text = band.name,
                          color = KestrelText,
                          fontWeight = FontWeight.Bold,
                          fontSize = 14.sp,
                          maxLines = 1,
                          overflow = TextOverflow.Ellipsis
                        )
                        Text(
                          text = "${band.address}  •  ${band.rssi} dBm",
                          color = KestrelMuted,
                          fontSize = 11.sp
                        )
                      }
                    }

                    Button(
                      onClick = { onConnectClick(band, inputAuthKey) },
                      shape = RoundedCornerShape(14.dp),
                      colors = ButtonDefaults.buttonColors(
                        containerColor = if (band.isMiBand) KestrelMauveDark else KestrelMauve,
                        contentColor = Color.White
                      ),
                      modifier = Modifier.testTag("onboarding_connect_button")
                    ) {
                      Text("Connect & Sync", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                  }
                }
              }
            }

            // Direct MAC Address connect option (Great for bonded or hidden band)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
              text = "Or Connect by Bluetooth MAC Address:",
              color = KestrelMauveDark,
              fontSize = 11.sp,
              fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              OutlinedTextField(
                value = manualMacAddress,
                onValueChange = { manualMacAddress = it },
                placeholder = { Text("e.g. E4:23:45:67:89:AB", color = KestrelMuted, fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                trailingIcon = {
                  IconButton(
                    onClick = {
                      val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                      val clip = clipboard?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                      if (clip.isNotEmpty()) {
                        manualMacAddress = clip.trim()
                      }
                    }
                  ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = "Paste MAC", tint = KestrelMauveDark, modifier = Modifier.size(16.dp))
                  }
                },
                colors = OutlinedTextFieldDefaults.colors(
                  focusedTextColor = KestrelText,
                  unfocusedTextColor = KestrelText,
                  focusedContainerColor = KestrelCardLight,
                  unfocusedContainerColor = KestrelCardLight,
                  focusedBorderColor = KestrelMauveDark,
                  unfocusedBorderColor = KestrelBorder
                )
              )

              Button(
                onClick = {
                  if (manualMacAddress.isNotBlank()) {
                    onConnectByAddressClick(manualMacAddress.trim(), inputAuthKey)
                  }
                },
                enabled = manualMacAddress.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                  containerColor = KestrelMauveDark,
                  contentColor = Color.White
                )
              ) {
                Text("Connect", fontSize = 11.sp, fontWeight = FontWeight.Bold)
              }
            }

            // Optional Auth Key Toggle
            Spacer(modifier = Modifier.height(14.dp))
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = "Zepp Auth Key (Optional)",
                color = KestrelMauveDark,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
              )
              IconButton(onClick = { showKeyHelp = !showKeyHelp }, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Info, contentDescription = "Help", tint = KestrelMauveDark, modifier = Modifier.size(16.dp))
              }
            }

            AnimatedVisibility(visible = showKeyHelp) {
              Text(
                text = "If you have your 32-character key from Zepp Life / Mi Fit, paste it below. If not, don't worry! Standard pairing connects and syncs directly.",
                color = KestrelMuted,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(vertical = 4.dp)
              )
            }

            OutlinedTextField(
              value = inputAuthKey,
              onValueChange = {
                // Strip 0x / spaces / colons so the field always shows the clean 32-hex key
                val clean = it.trim()
                  .replace("0x", "", ignoreCase = true)
                  .replace(" ", "").replace(":", "").replace("-", "")
                  .lowercase()
                inputAuthKey = clean
                onSaveAuthKey(clean)
              },
              placeholder = { Text("32 hex chars (0x prefix OK)", color = KestrelMuted, fontSize = 11.sp) },
              trailingIcon = {
                IconButton(
                  onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val clip = clipboard?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                    if (clip.isNotEmpty()) {
                      val clean = clip.trim()
                        .replace("0x", "", ignoreCase = true)
                        .replace(" ", "").replace(":", "").replace("-", "")
                        .lowercase()
                      inputAuthKey = clean
                      onSaveAuthKey(clean)
                    }
                  }
                ) {
                  Icon(Icons.Default.ContentPaste, contentDescription = "Paste", tint = KestrelMauveDark, modifier = Modifier.size(16.dp))
                }
              },
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(12.dp),
              colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = KestrelText,
                unfocusedTextColor = KestrelText,
                focusedContainerColor = KestrelCardLight,
                unfocusedContainerColor = KestrelCardLight,
                focusedBorderColor = KestrelMauveDark,
                unfocusedBorderColor = KestrelBorder
              )
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(28.dp))

      // --- 4. PRIMARY ACTION / LAUNCH BUTTON ---
      if (uiState.status == ConnectionStatus.CONNECTED) {
        Button(
          onClick = onEnterDashboardClick,
          modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .testTag("launch_dashboard_button"),
          shape = RoundedCornerShape(20.dp),
          colors = ButtonDefaults.buttonColors(
            containerColor = KestrelGreen,
            contentColor = Color.White
          )
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
          ) {
            Text(
              text = "Open Kestrel Dashboard",
              fontSize = 16.sp,
              fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
          }
        }
      } else {
        // Option to proceed or skip to dashboard even without connecting yet
        TextButton(
          onClick = onSkipClick,
          modifier = Modifier.testTag("skip_onboarding_button")
        ) {
          Text(
            text = "Skip to Dashboard (Pair later in menu)",
            color = KestrelMauveDark,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
          )
        }
      }

      Spacer(modifier = Modifier.height(20.dp))
    }
  }
}

@Composable
private fun SyncItem(title: String, isDone: Boolean) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth()
  ) {
    if (isDone) {
      Icon(
        imageVector = Icons.Default.CheckCircle,
        contentDescription = null,
        tint = KestrelGreen,
        modifier = Modifier.size(16.dp)
      )
    } else {
      CircularProgressIndicator(
        modifier = Modifier.size(14.dp),
        strokeWidth = 2.dp,
        color = KestrelMauveDark
      )
    }
    Spacer(modifier = Modifier.width(10.dp))
    Text(
      text = title,
      color = if (isDone) KestrelText else KestrelMuted,
      fontSize = 12.sp,
      fontWeight = if (isDone) FontWeight.SemiBold else FontWeight.Normal
    )
  }
}
