package com.example.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.ble.MiBandManager
import java.util.concurrent.TimeUnit

/**
 * Quiet background pass — tries a light band status refresh / reconnect nudge.
 * Does not replace in-app sync; keeps widgets fresher when the app is closed.
 */
class BandReconcileWorker(
  appContext: Context,
  params: WorkerParameters
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result {
    return try {
      val mgr = MiBandManager.get(applicationContext)
      // Soft nudge: if we have a saved band, ask manager to refresh status
      try { mgr.syncNow() } catch (_: Exception) {}
      Result.success()
    } catch (e: Exception) {
      Result.retry()
    }
  }

  companion object {
    private const val UNIQUE = "kestrel_band_reconcile"

    fun schedule(context: Context) {
      val req = PeriodicWorkRequestBuilder<BandReconcileWorker>(6, TimeUnit.HOURS)
        .build()
      WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        UNIQUE,
        ExistingPeriodicWorkPolicy.KEEP,
        req
      )
    }
  }
}
