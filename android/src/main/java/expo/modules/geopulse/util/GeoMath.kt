package expo.modules.geopulse.util

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure geo math with no Android dependencies, so it can be unit-tested on a plain
 * JVM (see tools/host-test/GeoMathTest.kt, run in CI). Shared by geofencing and
 * trip-distance accumulation.
 */
object GeoMath {
  const val EARTH_RADIUS_M = 6_371_000.0

  /** Great-circle distance in meters between two lat/lng points. */
  fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
      cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
  }

  /** Ray-casting point-in-polygon test. Vertices are `[lat, lng]` pairs. */
  fun pointInPolygon(lat: Double, lng: Double, vertices: List<DoubleArray>): Boolean {
    var inside = false
    var j = vertices.size - 1
    for (i in vertices.indices) {
      val yi = vertices[i][0]
      val xi = vertices[i][1]
      val yj = vertices[j][0]
      val xj = vertices[j][1]
      val intersect = (yi > lat) != (yj > lat) &&
        lng < (xj - xi) * (lat - yi) / (yj - yi) + xi
      if (intersect) inside = !inside
      j = i
    }
    return inside
  }
}
