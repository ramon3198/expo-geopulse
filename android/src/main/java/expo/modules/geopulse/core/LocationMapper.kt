package expo.modules.geopulse.core

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
  ): Map<String, Any?> {
    val coords = mutableMapOf<String, Any?>(
      "latitude" to (overrideLat ?: location.latitude),
      "longitude" to (overrideLng ?: location.longitude),
      "accuracy" to (overrideAccuracy ?: if (location.hasAccuracy()) location.accuracy.toDouble() else 0.0),
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
    return mapOf(
      "uuid" to UUID.randomUUID().toString(),
      "timestamp" to location.time,
      "coords" to coords,
      "isMoving" to isMoving,
      "filtered" to filtered,
      "provider" to (provider ?: location.provider ?: "fused"),
    )
  }
}
