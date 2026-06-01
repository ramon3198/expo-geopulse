package expo.modules.geopulse.core

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Helpers for inspecting and describing the SDK's runtime permissions. */
object PermissionsManager {
  private const val ACTIVITY_RECOGNITION = "android.permission.ACTIVITY_RECOGNITION"

  /** Foreground permissions to request together. Background location is requested separately. */
  fun requestList(): Array<String> {
    val list = mutableListOf(
      Manifest.permission.ACCESS_FINE_LOCATION,
      Manifest.permission.ACCESS_COARSE_LOCATION,
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      list.add(Manifest.permission.POST_NOTIFICATIONS)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      list.add(ACTIVITY_RECOGNITION)
    }
    return list.toTypedArray()
  }

  private fun granted(ctx: Context, perm: String): Boolean =
    ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

  fun hasLocationPermission(ctx: Context): Boolean =
    granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ||
      granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)

  fun hasBackgroundPermission(ctx: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    } else {
      hasLocationPermission(ctx) // pre-Android 10: foreground grant covers background
    }

  /** Opens this app's system settings page (for manual "Allow all the time"). */
  fun openAppSettings(ctx: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
      data = Uri.fromParts("package", ctx.packageName, null)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { ctx.startActivity(intent) }
  }

  fun isLocationEnabled(ctx: Context): Boolean {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
    return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
      lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
  }

  /** Whether the app is exempt from Doze battery optimization. */
  fun isIgnoringBatteryOptimizations(ctx: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return pm.isIgnoringBatteryOptimizations(ctx.packageName)
  }

  /** Opens the system dialog asking the user to exempt the app from Doze. */
  fun requestIgnoreBatteryOptimizations(ctx: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
      data = Uri.parse("package:${ctx.packageName}")
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { ctx.startActivity(intent) }
  }

  /** Builds the JS-facing `PermissionStatus` map from the current grant state. */
  fun statusMap(ctx: Context): Map<String, Any?> {
    val fine = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
    val background = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    } else {
      fine || coarse
    }
    val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      granted(ctx, Manifest.permission.POST_NOTIFICATIONS)
    } else {
      true
    }
    val activity = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      granted(ctx, ACTIVITY_RECOGNITION)
    } else {
      true
    }
    // 0 NotDetermined, 1 Denied, 2 WhenInUse, 3 Always
    val status = when {
      background && (fine || coarse) -> 3
      fine || coarse -> 2
      else -> 1
    }
    return mapOf(
      "fine" to fine,
      "coarse" to coarse,
      "background" to background,
      "notifications" to notifications,
      "activityRecognition" to activity,
      "locationServicesEnabled" to isLocationEnabled(ctx),
      "status" to status,
    )
  }
}
