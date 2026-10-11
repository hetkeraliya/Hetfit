package com.example.run

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import androidx.core.content.FileProvider
import com.example.ble.BandSampleStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Sharing / exporting helpers used by the web UI bridge. Everything here works offline. */
object ShareHelper {

  private fun shareDir(ctx: Context): File = File(ctx.cacheDir, "share").apply { mkdirs() }

  private fun uriFor(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)

  private fun decode(b64: String): ByteArray {
    val clean = b64.substringAfter("base64,", b64)
    return Base64.decode(clean, Base64.DEFAULT)
  }

  private fun launch(ctx: Context, i: Intent) {
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ctx.startActivity(i)
  }

  /** target: "story" (Instagram story, falls back to the share sheet), "whatsapp", "system". */
  fun shareImage(ctx: Context, b64: String, fileName: String, text: String, target: String): String {
    return try {
      val f = File(shareDir(ctx), fileName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
      f.writeBytes(decode(b64))
      val uri = uriFor(ctx, f)
      if (target == "story") {
        try {
          val ig = Intent("com.instagram.share.ADD_TO_STORY").apply {
            setDataAndType(uri, "image/png")
            putExtra("source_application", ctx.packageName)
            setPackage("com.instagram.android")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
          }
          ctx.grantUriPermission("com.instagram.android", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
          launch(ctx, ig)
          return "ok"
        } catch (_: Exception) { /* Instagram missing: use the normal share sheet */ }
      }
      val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        if (text.isNotEmpty()) putExtra(Intent.EXTRA_TEXT, text)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (target == "whatsapp") setPackage("com.whatsapp")
      }
      try {
        launch(ctx, if (target == "whatsapp") send else Intent.createChooser(send, "Share activity"))
      } catch (_: Exception) {
        send.setPackage(null)
        launch(ctx, Intent.createChooser(send, "Share activity"))
      }
      "ok"
    } catch (e: Exception) {
      "error: ${e.message}"
    }
  }

  fun shareText(ctx: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
    launch(ctx, Intent.createChooser(send, "Share activity"))
  }

  fun saveImageToGallery(ctx: Context, b64: String, fileName: String): String {
    return try {
      val bytes = decode(b64)
      val name = if (fileName.endsWith(".png")) fileName else "$fileName.png"
      if (Build.VERSION.SDK_INT >= 29) {
        val cv = ContentValues().apply {
          put(MediaStore.Images.Media.DISPLAY_NAME, name)
          put(MediaStore.Images.Media.MIME_TYPE, "image/png")
          put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Kestrel")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return "error: could not create image"
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        "Saved to Pictures/Kestrel/$name"
      } else {
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: ctx.filesDir
        File(dir, name).writeBytes(bytes)
        "Saved to ${File(dir, name).absolutePath}"
      }
    } catch (e: Exception) {
      "error: ${e.message}"
    }
  }

  /** Saves text (JSON / CSV / GPX) into the public Downloads folder. */
  fun saveTextFile(ctx: Context, name: String, mime: String, text: String): String {
    return try {
      val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
      if (Build.VERSION.SDK_INT >= 29) {
        val cv = ContentValues().apply {
          put(MediaStore.Downloads.DISPLAY_NAME, safe)
          put(MediaStore.Downloads.MIME_TYPE, mime)
          put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Kestrel")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return "error: could not create file"
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        "Saved to Downloads/Kestrel/$safe"
      } else {
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
        File(dir, safe).writeText(text)
        "Saved to ${File(dir, safe).absolutePath}"
      }
    } catch (e: Exception) {
      "error: ${e.message}"
    }
  }

  fun shareTextFile(ctx: Context, name: String, mime: String, text: String): String {
    return try {
      val f = File(shareDir(ctx), name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
      f.writeText(text)
      val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uriFor(ctx, f))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      launch(ctx, Intent.createChooser(send, "Share file"))
      "ok"
    } catch (e: Exception) {
      "error: ${e.message}"
    }
  }

  fun buildGpx(store: BandSampleStore, startSec: Long, name: String): String {
    val pts = store.getTrack(startSec, 100000)
    val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    val sb = StringBuilder()
    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    sb.append("<gpx version=\"1.1\" creator=\"Kestrel\" xmlns=\"http://www.topografix.com/GPX/1/1\" ")
    sb.append("xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\">\n")
    sb.append("<metadata><time>").append(iso.format(Date(startSec * 1000))).append("</time></metadata>\n")
    sb.append("<trk><name>").append(name.replace("&", "and").replace("<", "").replace(">", "")).append("</name><trkseg>\n")
    for (p in pts) {
      sb.append("<trkpt lat=\"").append(p.lat).append("\" lon=\"").append(p.lon).append("\">")
      sb.append("<ele>").append(String.format(Locale.US, "%.1f", p.alt)).append("</ele>")
      sb.append("<time>").append(iso.format(Date(p.ts * 1000))).append("</time>")
      if (p.hr > 0) sb.append("<extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>").append(p.hr).append("</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>")
      sb.append("</trkpt>\n")
    }
    sb.append("</trkseg></trk></gpx>\n")
    return sb.toString()
  }
}
