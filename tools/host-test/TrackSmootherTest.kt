// Standalone JVM test for the fixed-lag TrackSmoother + the GnssQuality mapping.
// Compiled + run in CI with kotlinc, like the other pure-logic host tests.
//
//   kotlinc android/src/main/java/expo/modules/geopulse/fusion/TrackSmoother.kt \
//           android/src/main/java/expo/modules/geopulse/util/GnssQuality.kt \
//           tools/host-test/TrackSmootherTest.kt -include-runtime -d /tmp/smoother-test.jar
//   java -jar /tmp/smoother-test.jar

import expo.modules.geopulse.fusion.TrackSmoother
import expo.modules.geopulse.util.GnssQuality
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.system.exitProcess

private var failures = 0

private fun check(name: String, cond: Boolean) {
  println((if (cond) "  [PASS] " else "  [FAIL] ") + name)
  if (!cond) failures++
}

private const val DEG_PER_METER = 1.0 / 111320.0

fun main() {
  println("== TrackSmoother tests ==")

  check("inactive when lag = 0", !TrackSmoother<Int>(0).isActive())
  check("active when lag > 0", TrackSmoother<Int>(2).isActive())

  // Ordering + latency: with lag=2, point N is released when N+2 arrives.
  run {
    val s = TrackSmoother<Int>(2)
    check("lag: 1st add held", s.add(TrackSmoother.Point(0.0, 0.0, 5.0, 1)) == null)
    check("lag: 2nd add held", s.add(TrackSmoother.Point(0.0, 0.0, 5.0, 2)) == null)
    val first = s.add(TrackSmoother.Point(0.0, 0.0, 5.0, 3))
    check("lag: 3rd add releases point #1", first?.payload == 1)
    val second = s.add(TrackSmoother.Point(0.0, 0.0, 5.0, 4))
    check("lag: 4th add releases point #2", second?.payload == 2)
    val tail = s.flush()
    check("flush: releases the held tail in order", tail.map { it.payload } == listOf(3, 4))
    check("flush: resets the smoother", s.add(TrackSmoother.Point(0.0, 0.0, 5.0, 9)) == null)
  }

  // Quality: a noisy straight track must come out measurably tighter. RMSE is
  // computed against the true (noise-free) line for raw vs smoothed positions.
  run {
    val rng = Random(2024)
    val lag = 3
    val s = TrackSmoother<Int>(lag)
    val noiseSd = 8.0
    var rawSumSq = 0.0
    var smoothSumSq = 0.0
    var n = 0
    val trueLats = DoubleArray(300) { it * 10.0 * DEG_PER_METER } // north @10 m/fix
    fun gauss() = (rng.nextDouble() + rng.nextDouble() + rng.nextDouble() + rng.nextDouble() - 2.0) * noiseSd * sqrt(3.0)
    for (i in trueLats.indices) {
      val mLat = trueLats[i] + gauss() * DEG_PER_METER
      val mLng = gauss() * DEG_PER_METER
      val rawErr = sqrt(((mLat - trueLats[i]) / DEG_PER_METER).let { it * it } + (mLng / DEG_PER_METER).let { it * it })
      val out = s.add(TrackSmoother.Point(mLat, mLng, noiseSd, i))
      if (out != null) {
        val t = trueLats[out.payload]
        val e = sqrt(((out.lat - t) / DEG_PER_METER).let { it * it } + (out.lng / DEG_PER_METER).let { it * it })
        rawSumSq += rawErr * rawErr // pair raw error of the arriving fix
        smoothSumSq += e * e
        n++
      }
    }
    val rawRmse = sqrt(rawSumSq / n)
    val smoothRmse = sqrt(smoothSumSq / n)
    println("  straight-track RMSE: raw=${"%.2f".format(rawRmse)}m  smoothed=${"%.2f".format(smoothRmse)}m")
    check("quality: smoothed RMSE < 60% of raw", smoothRmse < rawRmse * 0.6)
  }

  // Weighting: a tight-accuracy center fix dominates loose neighbors.
  run {
    val s = TrackSmoother<Int>(1)
    s.add(TrackSmoother.Point(0.0, 0.0, 50.0, 1))
    s.add(TrackSmoother.Point(100.0 * DEG_PER_METER, 0.0, 1.0, 2)) // precise, 100m north
    val out = s.add(TrackSmoother.Point(0.0, 0.0, 50.0, 3))!! // releases #2
    check("weights: precise center barely moves", abs(out.lat - 100.0 * DEG_PER_METER) < 5.0 * DEG_PER_METER)
  }

  println("== GnssQuality tests ==")
  check("open sky -> 1.0", GnssQuality.inflationFor(10, 32.0) == 1.0)
  check("light obstruction -> 1.2", GnssQuality.inflationFor(6, 25.0) == 1.2)
  check("urban canyon -> 1.5", GnssQuality.inflationFor(4, 20.0) == 1.5)
  check("indoors -> 2.0", GnssQuality.inflationFor(2, 12.0) == 2.0)
  check("weak signals dominate count", GnssQuality.inflationFor(9, 15.0) == 2.0)
  check("NaN C/N0 falls back to count", GnssQuality.inflationFor(8, Double.NaN) == 1.0)

  println()
  if (failures > 0) {
    println("FAILED ($failures check(s))")
    exitProcess(1)
  }
  println("All checks passed.")
}
