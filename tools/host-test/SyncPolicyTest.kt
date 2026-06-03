// Standalone JVM unit test for the pure SyncPolicy helpers. Compiled + run in CI
// with kotlinc (no Android SDK needed), mirroring GeoMathTest / the C++ fusion test.
//
//   kotlinc android/src/main/java/expo/modules/geopulse/sync/SyncPolicy.kt \
//           tools/host-test/SyncPolicyTest.kt -include-runtime -d /tmp/syncpolicy-test.jar
//   java -jar /tmp/syncpolicy-test.jar

import expo.modules.geopulse.sync.SyncAction
import expo.modules.geopulse.sync.SyncPolicy
import kotlin.system.exitProcess

private var failures = 0

private fun check(name: String, cond: Boolean) {
  println((if (cond) "  [PASS] " else "  [FAIL] ") + name)
  if (!cond) failures++
}

fun main() {
  println("== SyncPolicy tests ==")

  // --- default classification ---
  check("200 -> SUCCESS", SyncPolicy.classify(200) == SyncAction.SUCCESS)
  check("201 -> SUCCESS", SyncPolicy.classify(201) == SyncAction.SUCCESS)
  check("204 -> SUCCESS", SyncPolicy.classify(204) == SyncAction.SUCCESS)

  check("400 -> DISCARD", SyncPolicy.classify(400) == SyncAction.DISCARD)
  check("413 -> DISCARD", SyncPolicy.classify(413) == SyncAction.DISCARD)
  check("422 -> DISCARD", SyncPolicy.classify(422) == SyncAction.DISCARD)

  check("0 (network) -> RETRY", SyncPolicy.classify(0) == SyncAction.RETRY)
  check("401 -> RETRY", SyncPolicy.classify(401) == SyncAction.RETRY)
  check("403 -> RETRY", SyncPolicy.classify(403) == SyncAction.RETRY)
  check("408 -> RETRY", SyncPolicy.classify(408) == SyncAction.RETRY)
  check("429 -> RETRY", SyncPolicy.classify(429) == SyncAction.RETRY)
  check("500 -> RETRY", SyncPolicy.classify(500) == SyncAction.RETRY)
  check("503 -> RETRY", SyncPolicy.classify(503) == SyncAction.RETRY)
  check("404 (unclassified 4xx) -> RETRY", SyncPolicy.classify(404) == SyncAction.RETRY)

  // --- consumer overrides ---
  check(
    "consumer can discard a 500",
    SyncPolicy.classify(500, discardCodes = setOf(500)) == SyncAction.DISCARD,
  )
  check(
    "consumer can force-retry a 400",
    SyncPolicy.classify(400, retryCodes = setOf(400)) == SyncAction.RETRY,
  )
  check(
    "retry override wins over discard override (never lose data)",
    SyncPolicy.classify(418, discardCodes = setOf(418), retryCodes = setOf(418)) == SyncAction.RETRY,
  )
  check(
    "2xx always SUCCESS even if listed",
    SyncPolicy.classify(200, discardCodes = setOf(200)) == SyncAction.SUCCESS,
  )

  // --- Retry-After parsing ---
  check("Retry-After '120' -> 120", SyncPolicy.parseRetryAfterSeconds("120") == 120L)
  check("Retry-After '  30 ' -> 30", SyncPolicy.parseRetryAfterSeconds("  30 ") == 30L)
  check("Retry-After '0' -> 0", SyncPolicy.parseRetryAfterSeconds("0") == 0L)
  check("Retry-After null -> null", SyncPolicy.parseRetryAfterSeconds(null) == null)
  check("Retry-After '-5' -> null", SyncPolicy.parseRetryAfterSeconds("-5") == null)
  check(
    "Retry-After HTTP-date -> null (not honored)",
    SyncPolicy.parseRetryAfterSeconds("Wed, 21 Oct 2015 07:28:00 GMT") == null,
  )

  println()
  if (failures > 0) {
    println("FAILED ($failures check(s))")
    exitProcess(1)
  }
  println("All checks passed.")
}
