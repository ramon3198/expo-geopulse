// Standalone JVM unit test for the pure HeadlessCoalescer (P1-7). Compiled + run
// in CI with kotlinc (no Android SDK needed), like GeoMathTest / SyncPolicyTest.
//
//   kotlinc android/src/main/java/expo/modules/geopulse/headless/HeadlessCoalescer.kt \
//           tools/host-test/HeadlessCoalescerTest.kt -include-runtime -d /tmp/coalescer-test.jar
//   java -jar /tmp/coalescer-test.jar

import expo.modules.geopulse.headless.HeadlessCoalescer
import kotlin.system.exitProcess

private var failures = 0

private fun check(name: String, cond: Boolean) {
  println((if (cond) "  [PASS] " else "  [FAIL] ") + name)
  if (!cond) failures++
}

fun main() {
  println("== HeadlessCoalescer tests ==")

  // --- isActive ---
  check("inactive when window=0 & count=0", !HeadlessCoalescer<Int>(0, 0).isActive())
  check("active with a window", HeadlessCoalescer<Int>(5000, 0).isActive())
  check("active with a count", HeadlessCoalescer<Int>(0, 3).isActive())

  // --- count-based flush ---
  run {
    val c = HeadlessCoalescer<Int>(0, 3)
    check("count: 1st add no flush", c.add(1, 0).flushNow == null)
    check("count: 2nd add no flush", c.add(2, 0).flushNow == null)
    val d = c.add(3, 0)
    check("count: 3rd add flushes [1,2,3]", d.flushNow == listOf(1, 2, 3))
    check("count: no timer arming (window=0)", !d.armTimer)
    check("count: buffer cleared after flush", c.drainNow() == null)
  }

  // --- window-based flush: the acceptance scenario ---
  // 10 fixes within 5s, 5s window -> exactly one batch of 10.
  run {
    val c = HeadlessCoalescer<Int>(5000, 0)
    val first = c.add(0, 0)
    check("window: first add arms the timer", first.armTimer && first.flushNow == null)
    for (i in 1..9) {
      val d = c.add(i, (i * 500).toLong()) // t = 500..4500
      check("window: add $i buffers, no flush, no re-arm", d.flushNow == null && !d.armTimer)
    }
    check("window: not due before 5s", c.flushIfDue(4999) == null)
    check("window: flush at 5s yields all 10", c.flushIfDue(5000) == (0..9).toList())
    check("window: empty after flush", c.flushIfDue(10_000) == null)
  }

  // --- a stale timer from a prior window is a harmless no-op ---
  run {
    val c = HeadlessCoalescer<Int>(5000, 3)
    c.add(1, 0)
    c.add(2, 0)
    check("mixed: count flush drains [1,2,3]", c.add(3, 0).flushNow == listOf(1, 2, 3))
    check("mixed: re-open arms a fresh timer", c.add(4, 1000).armTimer)
    check("mixed: old timer (t=5000) not due for the new window", c.flushIfDue(5000) == null)
    check("mixed: new window due at t=6000", c.flushIfDue(6000) == listOf(4))
  }

  // --- drainNow ---
  run {
    val c = HeadlessCoalescer<Int>(5000, 0)
    check("drainNow empty -> null", c.drainNow() == null)
    c.add(7, 0)
    c.add(8, 100)
    check("drainNow returns the buffer", c.drainNow() == listOf(7, 8))
    check("drainNow after drain -> null", c.drainNow() == null)
  }

  println()
  if (failures > 0) {
    println("FAILED ($failures check(s))")
    exitProcess(1)
  }
  println("All checks passed.")
}
