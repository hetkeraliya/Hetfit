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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ble.ConnectionStatus
import com.example.ble.DiscoveredBand
import com.example.ble.MiBandState

// Kestrel Web App Theme Colors & Frosted Glass Tokens (matching web app aura theme)
private val KestrelHeroTop = Color(0xFFC4B3C2)
private val KestrelHeroMid = Color(0xFFAA98A9)
private val KestrelHeroBot = Color(0xFF8F7C8E)
private val KestrelCream = Color(0xFFFFEED7)
private val KestrelText = Color(0xFF3D3340)
private val KestrelMauveDark = Color(0xFF7A6879)
private val KestrelMuted = Color(0x993D3340)

// Frosted glass layers
private val GlassBackground = Color(0xF2FFEED7) // 95% translucent warm cream frosted overlay
private val GlassCardBg = Color(0xCCFFFFFF) // 80% translucent crisp white
private val GlassCardInner = Color(0x99FFFFFF) // 60% translucent soft inner pill
private val GlassBorder = Color(0x55AA98A9) // Luminous mauve-white border
private val GlassBorderLight = Color(0x99FFFFFF) // Highlights along frosted edges

@Composable
fun MiFitDeviceMenuDialog(
  uiState: MiBandState,
  discoveredDevices: List<DiscoveredBand>,
  onScanClick: () -> Unit,
  onStopScanClick: () -> Unit,
  onConnectClick: (DiscoveredBand, String) -> Unit,
  onDisconnectClick: () -> Unit,
  onSaveAuthKey: (String) -> Unit,
  onSyncAllDataClick: () -> Unit = {},
  onImportGadgetbridgeClick: () -> Unit = {},
  onReopenOnboarding: () -> Unit = {},
  onDismiss: () -> Unit
) {
  val context = LocalContext.current
  var inputAuthKey by remember(uiState.authKey) { mutableStateOf(uiState.authKey) }
  var showKeyHelp by remember { mutableStateOf(false) }

  val infiniteTransition = rememberInfiniteTransition(label = "pulse")
  val pulseScale by infiniteTransition.animateFloat(
    initialValue = 0.95f,
    targetValue = 1.08f,
    animationSpec = infiniteRepeatable(
      animation = tween(800, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "pulse_scale"
  )

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false)
  ) {
    Surface(
      modifier = Modifier
        .fillMaxWidth(0.94f)
        .padding(vertical = 16.dp)
        .testTag("mi_fit_device_dialog"),
      shape = RoundedCornerShape(32.dp),
      color = Color.Transparent,
      tonalElevation = 0.dp,
      shadowElevation = 24.dp
    ) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .background(
            brush = Brush.verticalGradient(
              colors = listOf(
                Color(0xFAFFEED7),
                Color(0xF2F5E5DC),
                Color(0xEEECDEDF)
              )
            ),
            shape = RoundedCornerShape(32.dp)
          )
          .border(
            width = 1.5.dp,
            brush = Brush.linearGradient(
              colors = listOf(
                Color(0xD9FFFFFF),
                Color(0x55AA98A9),
                Color(0x88FFFFFF)
              )
            ),
            shape = RoundedCornerShape(32.dp)
          )
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(22.dp)
        ) {
          // --- Frosted Header Themed Exactly Like Kestrel Aura Header ---
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Box(
                modifier = Modifier
                  .size(46.dp)
                  .background(
                    brush = Brush.linearGradient(
                      listOf(KestrelHeroTop, KestrelMauveDark)
                    ),
                    shape = CircleShape
                  )
                  .border(1.dp, Color(0x66FFFFFF), CircleShape),
                contentAlignment = Alignment.Center
              ) {
                Icon(
                  imageVector = Icons.Default.Watch,
                  contentDescription = "Mi Band",
                  tint = Color.White,
                  modifier = Modifier.size(24.dp)
                )
              }
              Spacer(modifier = Modifier.width(12.dp))
              Column {
                Text(
                  text = "Mi Smart Band 5",
                  color = KestrelText,
                  fontSize = 18.sp,
                  fontWeight = FontWeight.ExtraBold
                )
                Text(
                  text = "Realtime BLE Communication",
                  color = KestrelMauveDark,
                  fontSize = 12.sp,
                  fontWeight = FontWeight.Medium
                )
              }
            }

            IconButton(
              onClick = onDismiss,
              modifier = Modifier
                .size(36.dp)
                .background(Color(0x22AA98A9), CircleShape)
                .testTag("close_dialog_button")
            ) {
              Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = KestrelText,
                modifier = Modifier.size(18.dp)
              )
            }
          }

          Spacer(modifier = Modifier.height(16.dp))

          // --- Active Device Frosted Status Card ---
          Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = GlassCardBg),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder)
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                val statusColor = when (uiState.status) {
                  ConnectionStatus.CONNECTED -> Color(0xFF10B981)
                  ConnectionStatus.CONNECTING, ConnectionStatus.AUTHENTICATING -> Color(0xFFF59E0B)
                  ConnectionStatus.SCANNING -> KestrelHeroMid
                  ConnectionStatus.DISCONNECTED -> Color(0xFF94A3B8)
                }

                Box(
                  modifier = Modifier
                    .size(12.dp)
                    .background(statusColor, CircleShape)
                    .then(
                      if (uiState.status == ConnectionStatus.CONNECTING || uiState.status == ConnectionStatus.SCANNING) {
                        Modifier.scale(pulseScale)
                      } else Modifier
                    )
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                  Text(
                    text = when (uiState.status) {
                      ConnectionStatus.CONNECTED -> "Connected"
                      ConnectionStatus.CONNECTING -> "Connecting..."
                      ConnectionStatus.AUTHENTICATING -> "Authenticating Band..."
                      ConnectionStatus.SCANNING -> "Scanning Nearby..."
                      ConnectionStatus.DISCONNECTED -> "Not Connected"
                    },
                    color = KestrelText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                  )
                  if (uiState.deviceName.isNotEmpty()) {
                    Text(
                      text = uiState.deviceName,
                      color = KestrelMauveDark,
                      fontSize = 12.sp
                    )
                  }
                }
              }

              // Battery Level Badge
              if (uiState.batteryPercent >= 0) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  modifier = Modifier
                    .background(GlassCardInner, RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0x33AA98A9), RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                  Icon(
                    imageVector = Icons.Default.BatteryChargingFull,
                    contentDescription = "Battery",
                    tint = if (uiState.batteryPercent > 20) Color(0xFF10B981) else Color(0xFFEF4444),
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
          }

          // --- Live Health Metrics Grid (Realtime Band Telemetry) ---
          if (uiState.liveHeartRate > 0 || uiState.liveSteps > 0) {
            Spacer(modifier = Modifier.height(14.dp))
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              // Live Heart Rate Tile with frosted styling
              Card(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = GlassCardBg),
                border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder)
              ) {
                Column(modifier = Modifier.padding(14.dp)) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                  ) {
                    Text("Live Heart Rate", color = KestrelMauveDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Icon(
                      imageVector = Icons.Default.Favorite,
                      contentDescription = null,
                      tint = Color(0xFFF43F5E),
                      modifier = Modifier
                        .size(16.dp)
                        .scale(pulseScale)
                    )
                  }
                  Spacer(modifier = Modifier.height(6.dp))
                  Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                      text = if (uiState.liveHeartRate > 0) "${uiState.liveHeartRate}" else "–",
                      color = KestrelText,
                      fontSize = 24.sp,
                      fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("BPM", color = KestrelMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 3.dp))
                  }
                }
              }

              // Live Steps Tile with frosted styling
              Card(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = GlassCardBg),
                border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder)
              ) {
                Column(modifier = Modifier.padding(14.dp)) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                  ) {
                    Text("Live Steps", color = KestrelMauveDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Icon(
                      imageVector = Icons.Default.DirectionsWalk,
                      contentDescription = null,
                      tint = Color(0xFF0284C7),
                      modifier = Modifier.size(16.dp)
                    )
                  }
                  Spacer(modifier = Modifier.height(6.dp))
                  Text(
                    text = "%,d".format(uiState.liveSteps),
                    color = KestrelText,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold
                  )
                }
              }
            }
          }

          Spacer(modifier = Modifier.height(14.dp))

          // --- 16-byte Auth Key (Zepp Token) Section ---
          Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = GlassCardBg),
            border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder)
          ) {
            Column(modifier = Modifier.padding(16.dp)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = null,
                    tint = KestrelMauveDark,
                    modifier = Modifier.size(16.dp)
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                  Text(
                    text = "Zepp Auth Key (Optional)",
                    color = KestrelText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                  )
                }

                IconButton(
                  onClick = { showKeyHelp = !showKeyHelp },
                  modifier = Modifier.size(24.dp)
                ) {
                  Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Help",
                    tint = KestrelMauveDark,
                    modifier = Modifier.size(16.dp)
                  )
                }
              }

              AnimatedVisibility(visible = showKeyHelp) {
                Text(
                  text = "If you extracted your 32-hex Auth Key from Zepp Life / Mi Fit, paste it here. If not, you can pair directly via Standard BLE Pairing below.",
                  color = KestrelMuted,
                  fontSize = 11.sp,
                  lineHeight = 16.sp,
                  modifier = Modifier.padding(vertical = 6.dp)
                )
              }

              Spacer(modifier = Modifier.height(8.dp))

              OutlinedTextField(
                value = inputAuthKey,
                onValueChange = {
                  val clean = it.trim()
                    .replace("0x", "", ignoreCase = true)
                    .replace(" ", "").replace(":", "").replace("-", "")
                    .lowercase()
                  inputAuthKey = clean
                  onSaveAuthKey(clean)
                },
                placeholder = {
                  Text(
                    text = "32 hex chars (0x prefix OK)",
                    color = KestrelMuted,
                    fontSize = 12.sp
                  )
                },
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
                    Icon(
                      imageVector = Icons.Default.ContentPaste,
                      contentDescription = "Paste",
                      tint = KestrelMauveDark
                    )
                  }
                },
                singleLine = true,
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("auth_key_text_field"),
                colors = OutlinedTextFieldDefaults.colors(
                  focusedTextColor = KestrelText,
                  unfocusedTextColor = KestrelText,
                  focusedBorderColor = KestrelMauveDark,
                  unfocusedBorderColor = GlassBorder,
                  focusedContainerColor = GlassCardInner,
                  unfocusedContainerColor = GlassCardInner
                ),
                shape = RoundedCornerShape(14.dp)
              )
            }
          }

          Spacer(modifier = Modifier.height(14.dp))

          // --- Discovered Bands / Scanner Section ---
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "Nearby Devices",
              color = KestrelText,
              fontSize = 14.sp,
              fontWeight = FontWeight.ExtraBold
            )

            if (uiState.status == ConnectionStatus.SCANNING) {
              OutlinedButton(
                onClick = onStopScanClick,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD97706)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFD97706))
              ) {
                CircularProgressIndicator(
                  modifier = Modifier.size(12.dp),
                  color = Color(0xFFD97706),
                  strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Stop", fontSize = 12.sp, fontWeight = FontWeight.Bold)
              }
            } else {
              Button(
                onClick = onScanClick,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                  containerColor = KestrelMauveDark,
                  contentColor = Color.White
                ),
                modifier = Modifier.testTag("scan_bands_button")
              ) {
                Icon(
                  imageVector = Icons.Default.Refresh,
                  contentDescription = null,
                  modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Search Band", fontSize = 12.sp, fontWeight = FontWeight.Bold)
              }
            }
          }

          Spacer(modifier = Modifier.height(8.dp))

          // Device List
          if (discoveredDevices.isEmpty()) {
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .background(GlassCardBg, RoundedCornerShape(18.dp))
                .border(1.dp, GlassBorder, RoundedCornerShape(18.dp)),
              contentAlignment = Alignment.Center
            ) {
              Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                Icon(
                  imageVector = if (uiState.status == ConnectionStatus.SCANNING) Icons.Default.BluetoothSearching else Icons.Default.Bluetooth,
                  contentDescription = null,
                  tint = KestrelMauveDark,
                  modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                  text = if (uiState.status == ConnectionStatus.SCANNING) "Searching for Mi Band 5..." else "Tap 'Search Band' with phone Bluetooth & GPS ON",
                  color = KestrelMuted,
                  fontSize = 12.sp,
                  textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
              }
            }
          } else {
            LazyColumn(
              modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              items(discoveredDevices) { band ->
                Card(
                  modifier = Modifier
                    .fillMaxWidth()
                    .testTag("discovered_device_${band.address}"),
                  shape = RoundedCornerShape(18.dp),
                  colors = CardDefaults.cardColors(
                    containerColor = if (band.isMiBand) Color(0xF2FFFFFF) else GlassCardBg
                  ),
                  border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (band.isMiBand) KestrelHeroMid else GlassBorder
                  )
                ) {
                  Row(
                    modifier = Modifier
                      .fillMaxWidth()
                      .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                  ) {
                    Row(
                      verticalAlignment = Alignment.CenterVertically,
                      modifier = Modifier.weight(1f)
                    ) {
                      Icon(
                        imageVector = if (band.isMiBand) Icons.Default.Watch else Icons.Default.Bluetooth,
                        contentDescription = null,
                        tint = if (band.isMiBand) KestrelMauveDark else KestrelHeroMid,
                        modifier = Modifier.size(20.dp)
                      )
                      Spacer(modifier = Modifier.width(10.dp))
                      Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                          Text(
                            text = band.name,
                            color = KestrelText,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                          )
                          if (band.isMiBand) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                              modifier = Modifier
                                .background(Color(0xFF10B981).copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                            ) {
                              Text("Mi Band", color = Color(0xFF10B981), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                          }
                        }
                        Text(
                          text = "${band.address}  •  ${band.rssi} dBm",
                          color = KestrelMuted,
                          fontSize = 11.sp
                        )
                      }
                    }

                    // Pair / Connect Button
                    Button(
                      onClick = { onConnectClick(band, inputAuthKey) },
                      shape = RoundedCornerShape(16.dp),
                      colors = ButtonDefaults.buttonColors(
                        containerColor = if (band.isMiBand) KestrelMauveDark else KestrelHeroMid,
                        contentColor = Color.White
                      ),
                      modifier = Modifier.testTag("pair_device_button")
                    ) {
                      Text("Pair", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                  }
                }
              }
            }
          }

          // Error message banner if any
          if (uiState.errorMessage != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
              text = uiState.errorMessage,
              color = Color(0xFFDC2626),
              fontSize = 12.sp,
              lineHeight = 16.sp
            )
          }

          // Sync All Data & Re-run Setup Buttons
          Spacer(modifier = Modifier.height(14.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Button(
              onClick = {
                onSyncAllDataClick()
                onDismiss()
              },
              modifier = Modifier.weight(1f),
              shape = RoundedCornerShape(16.dp),
              colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF10B981),
                contentColor = Color.White
              )
            ) {
              Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("Sync All Data", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
              onClick = {
                onDismiss()
                onReopenOnboarding()
              },
              modifier = Modifier.weight(1f),
              shape = RoundedCornerShape(16.dp),
              colors = ButtonDefaults.outlinedButtonColors(contentColor = KestrelMauveDark),
              border = androidx.compose.foundation.BorderStroke(1.dp, KestrelMauveDark)
            ) {
              Text("Setup Wizard", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
          }

          // Import Gadgetbridge Export / Workouts
          Spacer(modifier = Modifier.height(10.dp))
          Button(
            onClick = {
              onDismiss()
              onImportGadgetbridgeClick()
            },
            modifier = Modifier
              .fillMaxWidth()
              .testTag("import_gadgetbridge_button"),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
              containerColor = KestrelMauveDark,
              contentColor = Color.White
            )
          ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Import Gadgetbridge Zip (Workouts & History)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
          }

          // Disconnect Button if connected
          if (uiState.status == ConnectionStatus.CONNECTED) {
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(
              onClick = onDisconnectClick,
              shape = RoundedCornerShape(16.dp),
              modifier = Modifier
                .fillMaxWidth()
                .testTag("disconnect_band_button"),
              colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
              border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444))
            ) {
              Text("Disconnect Mi Band", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
          }
        }
      }
    }
  }
}
