package expo.modules.geopulse.core

import android.content.Context
import android.location.Location
import android.os.Build
import java.util.UUID

/** Converts an Android [Location] into the JS-facing `Location` shape. */
object LocationMapper {
  fun toMap(
    location: Location,
    isMoving: Boolean,
    provider: String? = null,
    filtered: Boolean = false,
    overrideLat: Double? = null,
    overrideLng: Double? = null,
    overrideAccuracy: Double? = null,
    context: Context? = null,
  ): Map<String, Any?> {
    val accuracy = overrideAccuracy ?: if (location.hasAccuracy()) location.accuracy.toDouble() else 0.0
    val coords =
      mutableMapOf<String, Any?>(
        "latitude" to (overrideLat ?: location.latitude),
        "longitude" to (overrideLng ?: location.longitude),
        "accuracy" to accuracy,
        "altitude" to if (location.hasAltitude()) location.altitude else null,
        "heading" to if (location.hasBearing()) location.bearing.toDouble() else null,
        "speed" to if (location.hasSpeed()) location.speed.toDouble() else null,
      )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      if (location.hasVerticalAccuracy()) {
        coords["altitudeAccuracy"] = location.verticalAccuracyMeters.toDouble()
      }
      if (location.hasSpeedAccuracy()) {
        coords["speedAccuracy"] = location.speedAccuracyMetersPerSecond.toDouble()
      }
    }
    val map =
      mutableMapOf<String, Any?>(
        "uuid" to UUID.randomUUID().toString(),
        "timestamp" to location.time,
        "coords" to coords,
        "isMoving" to isMoving,
        "filtered" to filtered,
        "provider" to (provider ?: location.provider ?: "fused"),
        "isMock" to isMock(location),
        "confidence" to confidence(accuracy, filtered),
      )
    if (context != null) {
      batteryMap(context)?.let { map["battery"] = it }
    }
    return map
  }

  /** True when the OS reports this fix came from a mock-location provider. */
  fun isMock(location: Location): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      location.isMock
    } else {
      @Suppress("DEPRECATION")
      location.isFromMockProvider
    }

  /**
   * 0–100 confidence score. Driven primarily by horizontal accuracy (tighter is
   * better) with a bonus when the Kalman fusion filter has been applied (it
   * rejects outliers and tightens variance). Lets apps gate actions on quality.
   */
  fun confidence(
    accuracyMeters: Double,
    filtered: Boolean,
  ): Int {
    val base =
      when {
        accuracyMeters <= 0.0 -> 40    // unknown accuracy
        accuracyMeters <= 5.0 -> 100
        accuracyMeters <= 10.0 -> 90
        accuracyMeters <= 20.0 -> 75
        accuracyMeters <= 35.0 -> 60
        accuracyMeters <= 50.0 -> 45
        accuracyMeters <= 100.0 -> 30
        else -> 15
      }
    val bonus = if (filtered) 5 else 0
    return (base + bonus).coerceIn(0, 100)
  }

  private fun batteryMap(context: Context): Map<String, Any?>? {
    // Cached (30s TTL): reading BatteryManager is a binder IPC call, and this
    // runs on every fix.
    val state = BatteryReader.get(context) ?: return null
    if (state.level < 0) return null
    return mapOf("level" to state.level / 100.0, "isCharging" to state.isCharging)
  }
}
