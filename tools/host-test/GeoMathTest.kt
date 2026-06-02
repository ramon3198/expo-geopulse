// Standalone JVM unit test for the pure GeoMath helpers. Compiled + run in CI
// with kotlinc (no Android SDK needed), mirroring the C++ fusion host test.
//
//   kotlinc android/src/main/java/expo/modules/geopulse/util/GeoMath.kt \
//           tools/host-test/GeoMathTest.kt -include-runtime -d /tmp/geomath-test.jar
//   java -jar /tmp/geomath-test.jar

import expo.modules.geopulse.util.GeoMath
import kotlin.math.abs
import kotlin.system.exitProcess

private var failures = 0

private fun check(name: String, cond: Boolean) {
  println((if (cond) "  [PASS] " else "  [FAIL] ") + name)
  if (!cond) failures++
}

fun main() {
  println("== GeoMath tests ==")

  // --- haversineMeters ---
  check("same point = 0 m", GeoMath.haversineMeters(40.4, -3.7, 40.4, -3.7) < 1e-6)
  check(
    "1 deg latitude ~= 111.2 km",
    abs(GeoMath.haversineMeters(0.0, 0.0, 1.0, 0.0) - 111_195.0) < 500.0,
  )
  check(
    "0.01 deg lon at equator ~= 1.11 km",
    abs(GeoMath.haversineMeters(0.0, 0.0, 0.0, 0.01) - 1113.2) < 50.0,
  )
  check("distance is symmetric", run {
    val a = GeoMath.haversineMeters(51.5, -0.12, 48.85, 2.35)
    val b = GeoMath.haversineMeters(48.85, 2.35, 51.5, -0.12)
    abs(a - b) < 1e-6
  })
  check(
    "London->Paris ~= 343 km",
    abs(GeoMath.haversineMeters(51.5074, -0.1278, 48.8566, 2.3522) - 343_000.0) < 5_000.0,
  )

  // --- pointInPolygon (vertices are [lat, lng]) ---
  val square = listOf(
    doubleArrayOf(0.0, 0.0),
    doubleArrayOf(0.0, 1.0),
    doubleArrayOf(1.0, 1.0),
    doubleArrayOf(1.0, 0.0),
  )
  check("square: center inside", GeoMath.pointInPolygon(0.5, 0.5, square))
  check("square: right outside", !GeoMath.pointInPolygon(0.5, 1.5, square))
  check("square: left outside", !GeoMath.pointInPolygon(0.5, -0.5, square))
  check("square: below outside", !GeoMath.pointInPolygon(-0.5, 0.5, square))
  check("square: above outside", !GeoMath.pointInPolygon(1.5, 0.5, square))

  val triangle = listOf(
    doubleArrayOf(0.0, 0.0),
    doubleArrayOf(0.0, 2.0),
    doubleArrayOf(2.0, 0.0),
  )
  check("triangle: inside", GeoMath.pointInPolygon(0.3, 0.3, triangle))
  check("triangle: outside the hypotenuse", !GeoMath.pointInPolygon(1.5, 1.5, triangle))

  if (failures == 0) {
    println("== ALL TESTS PASSED ==")
  } else {
    println("== $failures TEST(S) FAILED ==")
    exitProcess(1)
  }
}
