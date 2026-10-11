package com.example.ble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

/**
 * Mirrors phone call state to the band (Gadgetbridge onSetCallState).
 * Register for android.intent.action.PHONE_STATE in the manifest.
 * Requires READ_PHONE_STATE (and on some OEMs READ_CALL_LOG for the number).
 */
class BandCallReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
    try {
      val prefs = context.getSharedPreferences("band_notify", Context.MODE_PRIVATE)
      if (!prefs.getBoolean("calls", true)) return
      val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
      val mgr = MiBandManager.get(context)
      if (mgr.uiState.value.status != ConnectionStatus.CONNECTED) return
      when (state) {
        TelephonyManager.EXTRA_STATE_RINGING -> {
          val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty()
          mgr.sendCallAlert(number.ifBlank { "Incoming call" }, ringing = true)
        }
        TelephonyManager.EXTRA_STATE_OFFHOOK, TelephonyManager.EXTRA_STATE_IDLE -> {
          mgr.sendCallAlert("", ringing = false)
        }
      }
    } catch (e: Exception) {
      android.util.Log.w("BandCall", "failed: ${e.message}")
    }
  }
}
