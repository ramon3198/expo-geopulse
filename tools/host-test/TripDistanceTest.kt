// Standalone JVM test for TripVisitManager trip-distance accounting, incl. the
// P2-8 stationary-gap rule (markGap). Compiled + run in CI with kotlinc.
//
//   kotlinc android/src/main/java/expo/modules/geopulse/util/GeoMath.kt \
//           android/src/main/java/expo/modules/geopulse/trip/TripVisitManager.kt \
//           tools/host-test/TripDistanceTest.kt -include-runtime -d /tmp/trip-test.jar
//   java -jar /tmp/trip-test.jar

import expo.modules.geopulse.trip.TripVisitManager
import kotlin.math.abs
import kotlin.system.exitProcess

private var failures = 0

private fun check(name: String, cond: Boolean) {
  println((if (cond) "  [PASS] " else "  [FAIL] ") + name)
  if (!cond) failures++
}

private const val DEG_PER_METER_LAT = 1.0 / 111320.0
private const val BASE_LAT = 37.0
private const val LNG = -122.0

private fun lat(meters: Double) = BASE_LAT + meters * DEG_PER_METER_LAT

private fun noopListener() =
  object : TripVisitManager.Listener {
    override fun onVisitArrive(visit: TripVisitManager.Visit) {}
    override fun onVisitDepart(visit: TripVisitManager.Visit) {}
    override fun onTripStart(trip: TripVisitManager.Trip) {}
    override fun onTripEnd(trip: TripVisitManager.Trip) {}
  }

private fun distance(m: TripVisitManager) = m.activeTripMap()!!["distanceMeters"] as Double

fun main() {
  println("== TripVisitManager distance tests ==")

  // Small radius so 100 m moves count as travel; small dwell so a visit confirms fast.
  val m = TripVisitManager(visitRadiusMeters = 50.0, minVisitDwellMs = 1000L)
  m.listener = noopListener()

  // Confirm a visit at A, then depart to B (1 km) -> a trip starts at B.
  m.onLocation(lat(0.0), LNG, 0L)
  m.onLocation(lat(0.0), LNG, 2000L) // dwell 2 s > 1 s -> visit A
  m.onLocation(lat(1000.0), LNG, 3000L) // depart -> trip starts at B (1000 m)

  // Real segment B -> C (+100 m).
  m.onLocation(lat(1100.0), LNG, 4000L)
  check("after B->C, distance ~100 m", abs(distance(m) - 100.0) < 5.0)

  // A stationary GPS-off stop: markGap, then a fix 5 km away. The C->D jump must
  // NOT be added to the trip distance.
  m.markGap()
  m.onLocation(lat(6100.0), LNG, 600_000L)
  check("gap jump NOT added (still ~100 m)", abs(distance(m) - 100.0) < 5.0)

  // Real segment D -> E (+100 m) resumes accumulating normally.
  m.onLocation(lat(6200.0), LNG, 601_000L)
  check("real segment after gap added (~200 m total)", abs(distance(m) - 200.0) < 5.0)

  // Control: the SAME 5 km jump WITHOUT markGap is counted (proves the gap rule matters).
  run {
    val c = TripVisitManager(visitRadiusMeters = 50.0, minVisitDwellMs = 1000L)
    c.listener = noopListener()
    c.onLocation(lat(0.0), LNG, 0L)
    c.onLocation(lat(0.0), LNG, 2000L)
    c.onLocation(lat(1000.0), LNG, 3000L) // trip starts at B
    c.onLocation(lat(1100.0), LNG, 4000L) // B->C 100 m
    c.onLocation(lat(6100.0), LNG, 5000L) // C->D 5 km, no markGap -> counted
    check("control: without markGap the 5 km jump IS counted", distance(c) > 5000.0)
  }

  println()
  if (failures > 0) {
    println("FAILED ($failures check(s))")
    exitProcess(1)
  }
  println("All checks passed.")
}
