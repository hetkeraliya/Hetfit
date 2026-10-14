package com.example.tile

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.example.run.RunTrackingService
import org.json.JSONObject

/**
 * Quick Settings tile — tap to start a run or open live tracking if already active.
 */
@RequiresApi(24)
class KestrelRunTileService : TileService() {

  override fun onStartListening() {
    super.onStartListening()
    refresh()
  }

  override fun onClick() {
    super.onClick()
    val active = try {
      val o = JSONObject(RunTrackingService.stateJson)
      o.optBoolean("active", false)
    } catch (_: Exception) { false }

    if (active) {
      // Open app to live screen
      val i = Intent(this, com.example.MainActivity::class.java).apply {
        action = "com.example.action.OPEN_LIVE"
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
      }
      startActivityAndCollapse(i)
    } else {
      RunTrackingService.send(this, RunTrackingService.ACTION_START, type = 1, voice = false)
      val i = Intent(this, com.example.MainActivity::class.java).apply {
        action = "com.example.action.OPEN_LIVE"
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
      }
      startActivityAndCollapse(i)
    }
    refresh()
  }

  private fun refresh() {
    val tile = qsTile ?: return
    val active = try {
      val o = JSONObject(RunTrackingService.stateJson)
      o.optBoolean("active", false)
    } catch (_: Exception) { false }
    tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
    tile.label = if (active) "Open run" else "Start run"
    tile.contentDescription = tile.label
    tile.updateTile()
  }
}
