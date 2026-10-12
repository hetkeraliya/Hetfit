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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ble.ConnectionStatus
import com.example.ble.DiscoveredBand
import com.example.ble.MiBandState
import java.text.DateFormat
import java.util.Date

// Palette: Moonlight #D9DCE5 / Silver Blue #A7B0C4 / Midnight Slate #454B5C
private val Moonlight = Color(0xFFD9DCE5)
private val MoonlightLight = Color(0xFFE8EAF0)
private val SilverBlue = Color(0xFFA7B0C4)
private val MidnightSlate = Color(0xFF454B5C)
private val SlateMuted = Color(0x99454B5C)
private val PaneColor = Color(0xB3FFFFFF)
private val Hairline = Color(0x26454B5C)
private val StatusOk = Color(0xFF4E9F83)
private val StatusWarn = Color(0xFFC99A3B)
private val StatusBad = Color(0xFFB8554F)

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
  var showKey by remember { mutableStateOf(false) }

  val connected = uiState.status == ConnectionStatus.CONNECTED
  val scanning = uiState.status == ConnectionStatus.SCANNING
  val working = scanning || uiState.status == ConnectionStatus.CONNECTING || uiState.status == ConnectionStatus.AUTHENTICATING

  val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
    initialValue = 0.85f,
    targetValue = 1.2f,
    animationSpec = infiniteRepeatable(
      animation = tween(900, easing = FastOutSlowInEasing),
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
      shape = RoundedCornerShape(28.dp),
      color = Color.Transparent,
      tonalElevation = 0.dp,
      shadowElevation = 0.dp
    ) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .background(
            brush = Brush.verticalGradient(listOf(MoonlightLight, Moonlight)),
            shape = RoundedCornerShape(28.dp)
          )
          .border(1.dp, Color(0x80FFFFFF), RoundedCornerShape(28.dp))
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
          // ---------- Header ----------
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Box(
                modifier = Modifier
                  .size(40.dp)
                  .background(MidnightSlate, CircleShape),
                contentAlignment = Alignment.Center
              ) {
                Icon(Icons.Default.Watch, contentDescription = null, tint = Moonlight, modifier = Modifier.size(20.dp))
              }
              Spacer(Modifier.width(12.dp))
              Column {
                Text("Band", color = MidnightSlate, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                  text = if (uiState.deviceName.isNotEmpty()) uiState.deviceName else "Mi Smart Band 5",
                  color = SlateMuted,
                  fontSize = 12.sp
                )
              }
            }
            IconButton(
              onClick = onDismiss,
              modifier = Modifier
                .size(36.dp)
                .background(PaneColor, CircleShape)
                .testTag("close_dialog_button")
            ) {
              Icon(Icons.Default.Close, contentDescription = "Close", tint = MidnightSlate, modifier = Modifier.size(18.dp))
            }
          }

          Spacer(Modifier.height(18.dp))

          // ---------- Status ----------
          GlassPane(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  val dot = when (uiState.status) {
                    ConnectionStatus.CONNECTED -> StatusOk
                    ConnectionStatus.CONNECTING, ConnectionStatus.AUTHENTICATING -> StatusWarn
                    ConnectionStatus.SCANNING -> SilverBlue
                    ConnectionStatus.DISCONNECTED -> SilverBlue
                  }
                  Box(
                    modifier = Modifier
                      .size(10.dp)
                      .then(if (working) Modifier.scale(pulse) else Modifier)
                      .background(dot, CircleShape)
                  )
                  Spacer(Modifier.width(10.dp))
                  Text(
                    text = when (uiState.status) {
                      ConnectionStatus.CONNECTED -> "Connected"
                      ConnectionStatus.CONNECTING -> "Connecting"
                      ConnectionStatus.AUTHENTICATING -> "Authenticating"
                      ConnectionStatus.SCANNING -> "Searching nearby"
                      ConnectionStatus.DISCONNECTED -> "Not connected"
                    },
                    color = MidnightSlate,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                  )
                }
                if (uiState.batteryPercent >= 0) {
                  Text(
                    text = "${uiState.batteryPercent}%",
                    color = if (uiState.batteryPercent > 20) MidnightSlate else StatusBad,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                  )
                }
              }

              if (connected) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                  Metric("Heart rate", if (uiState.liveHeartRate > 0) "${uiState.liveHeartRate}" else "–", "bpm", Modifier.weight(1f))
                  Metric("Steps", if (uiState.liveSteps > 0) "%,d".format(uiState.liveSteps) else "–", "", Modifier.weight(1f))
                }
              }

              if (uiState.syncing) {
                Spacer(Modifier.height(14.dp))
                Text(uiState.syncMessage ?: "Syncing…", color = SlateMuted, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                val frac = (uiState.syncPercent.coerceIn(0, 100) / 100f).coerceAtLeast(0.04f)
                Box(
                  Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Hairline)
                ) {
                  Box(
                    Modifier
                      .fillMaxWidth(frac)
                      .height(6.dp)
                      .background(MidnightSlate, RoundedCornerShape(3.dp))
                  )
                }
              } else if (connected && uiState.lastSyncMillis > 0L) {
                Spacer(Modifier.height(10.dp))
                Text(
                  text = "Last sync " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(uiState.lastSyncMillis)),
                  color = SlateMuted,
                  fontSize = 12.sp
                )
              }
            }
          }

          if (uiState.errorMessage != null) {
            Spacer(Modifier.height(10.dp))
            Text(
              text = uiState.errorMessage,
              color = StatusBad,
              fontSize = 12.sp,
              lineHeight = 17.sp,
              modifier = Modifier
                .fillMaxWidth()
                .background(Color(0x1AB8554F), RoundedCornerShape(14.dp))
                .padding(12.dp)
            )
          }

          Spacer(Modifier.height(16.dp))

          // ---------- Main action ----------
          if (connected) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
              Button(
                onClick = onSyncAllDataClick,
                modifier = Modifier
                  .weight(1f)
                  .height(48.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MidnightSlate, contentColor = Moonlight)
              ) { Text("Sync now", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
              OutlinedButton(
                onClick = onDisconnectClick,
                modifier = Modifier
                  .weight(1f)
                  .height(48.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, SilverBlue),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MidnightSlate)
              ) { Text("Disconnect", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
            }
          } else {
            Button(
              onClick = if (scanning) onStopScanClick else onScanClick,
              modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
              shape = RoundedCornerShape(16.dp),
              colors = ButtonDefaults.buttonColors(containerColor = MidnightSlate, contentColor = Moonlight)
            ) {
              Icon(
                imageVector = if (scanning) Icons.Default.BluetoothSearching else Icons.Default.Bluetooth,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
              )
              Spacer(Modifier.width(8.dp))
              Text(if (scanning) "Stop searching" else "Search for band", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }

            // ---------- Devices ----------
            Spacer(Modifier.height(14.dp))
            if (discoveredDevices.isEmpty()) {
              Text(
                text = if (scanning) "Looking for your band…" else "Turn on Bluetooth and location, wake the band, then search.",
                color = SlateMuted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
              )
            } else {
              Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                discoveredDevices.take(8).forEach { band ->
                  GlassPane(Modifier.fillMaxWidth()) {
                    Row(
                      modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                      Column(Modifier.weight(1f)) {
                        Text(
                          text = band.name.ifEmpty { "Unknown device" },
                          color = MidnightSlate,
                          fontWeight = FontWeight.Bold,
                          fontSize = 14.sp,
                          maxLines = 1,
                          overflow = TextOverflow.Ellipsis
                        )
                        Text(
                          text = "${band.address} · ${band.rssi} dBm" + if (band.isMiBand) " · Mi Band" else "",
                          color = SlateMuted,
                          fontSize = 11.sp,
                          maxLines = 1,
                          overflow = TextOverflow.Ellipsis
                        )
                      }
                      Spacer(Modifier.width(10.dp))
                      Button(
                        onClick = { onConnectClick(band, inputAuthKey) },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                          containerColor = if (band.isMiBand) MidnightSlate else SilverBlue,
                          contentColor = if (band.isMiBand) Moonlight else MidnightSlate
                        )
                      ) { Text("Pair", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                    }
                  }
                }
              }
            }
          }

          Spacer(Modifier.height(16.dp))

          // ---------- Auth key (collapsed) ----------
          GlassPane(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .height(44.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Icon(Icons.Default.Key, contentDescription = null, tint = MidnightSlate, modifier = Modifier.size(16.dp))
                  Spacer(Modifier.width(8.dp))
                  Text(
                    text = if (inputAuthKey.isBlank()) "Auth key" else "Auth key saved",
                    color = MidnightSlate,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                  )
                }
                IconButton(onClick = { showKey = !showKey }, modifier = Modifier.size(32.dp)) {
                  Icon(
                    imageVector = if (showKey) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = "Toggle auth key",
                    tint = MidnightSlate
                  )
                }
              }
              AnimatedVisibility(visible = showKey) {
                Column(Modifier.padding(bottom = 12.dp)) {
                  Text(
                    text = "Paste the 32-character key from your Zepp account. It is only needed once; the app remembers it.",
                    color = SlateMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                  )
                  Spacer(Modifier.height(10.dp))
                  OutlinedTextField(
                    value = inputAuthKey,
                    onValueChange = {
                      inputAuthKey = it
                      onSaveAuthKey(it)
                    },
                    placeholder = { Text("32 hex characters", color = SlateMuted, fontSize = 13.sp) },
                    trailingIcon = {
                      IconButton(
                        onClick = {
                          val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                          val clip = clipboard?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                          if (clip.isNotEmpty()) {
                            inputAuthKey = clip.trim()
                            onSaveAuthKey(clip.trim())
                          }
                        }
                      ) { Icon(Icons.Default.ContentPaste, contentDescription = "Paste", tint = MidnightSlate) }
                    },
                    singleLine = true,
                    modifier = Modifier
                      .fillMaxWidth()
                      .testTag("auth_key_text_field"),
                    colors = OutlinedTextFieldDefaults.colors(
                      focusedTextColor = MidnightSlate,
                      unfocusedTextColor = MidnightSlate,
                      focusedBorderColor = MidnightSlate,
                      unfocusedBorderColor = Hairline,
                      focusedContainerColor = Color(0x66FFFFFF),
                      unfocusedContainerColor = Color(0x66FFFFFF),
                      cursorColor = MidnightSlate
                    ),
                    shape = RoundedCornerShape(14.dp)
                  )
                }
              }
            }
          }

          Spacer(Modifier.height(8.dp))

          // ---------- Low-emphasis extras ----------
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            TextButton(onClick = {
              onReopenOnboarding()
              onDismiss()
            }) { Text("Setup guide", color = MidnightSlate, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            TextButton(onClick = onImportGadgetbridgeClick) {
              Text("Import Gadgetbridge zip", color = MidnightSlate, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
          }
        }
      }
    }
  }
}

/** A soft white pane used for every grouped block in the dialog. */
@Composable
private fun GlassPane(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  Box(
    modifier = modifier
      .background(PaneColor, RoundedCornerShape(20.dp))
      .border(1.dp, Hairline, RoundedCornerShape(20.dp))
  ) { content() }
}

@Composable
private fun Metric(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
  Column(
    modifier = modifier
      .background(Moonlight.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
      .padding(12.dp)
  ) {
    Text(label, color = SlateMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.Bottom) {
      Text(value, color = MidnightSlate, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
      if (unit.isNotEmpty()) {
        Spacer(Modifier.width(4.dp))
        Text(unit, color = SlateMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
      }
    }
  }
}
