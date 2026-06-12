package expo.modules.geopulse.fusion

/**
 * Fixed-lag track smoother: each point is re-estimated using up to [lag] PAST
 * and [lag] FUTURE fixes before being emitted, trading `lag` fixes of emission
 * latency for near-offline track quality (a live filter can only ever use the
 * past, so it trails and corner-cuts; the smoother sees both sides).
 *
 * Positions are blended with inverse-variance weights (tighter-accuracy fixes
 * count more) decayed by window distance, so one bad fix can't drag its
 * neighbors far. Timestamps, uuids and every other payload field are preserved
 * — only the coordinates move.
 *
 * Pure and host-testable; thread-safe (`@Synchronized`, mirrors the other
 * single-purpose pipeline helpers).
 */
class TrackSmoother<T>(
  private val lag: Int,
) {
  data class Point<T>(
    val lat: Double,
    val lng: Double,
    val accuracy: Double,
    val payload: T,
  )

  data class Smoothed<T>(
    val lat: Double,
    val lng: Double,
    val payload: T,
  )

  // Sliding window of the most recent fixes; at most 2*lag+1 are needed to
  // smooth the point that sits `lag` behind the newest one.
  private val window = ArrayDeque<Point<T>>()
  private var emitted = 0
  private var received = 0

  fun isActive(): Boolean = lag > 0

  /**
   * Add a fix. Returns the (older) smoothed point that just became final — the
   * one now `lag` fixes behind — or null while the initial window fills.
   */
  @Synchronized
  fun add(point: Point<T>): Smoothed<T>? {
    window.addLast(point)
    if (window.size > 2 * lag + 1) window.removeFirst()
    received++
    if (received <= lag) return null
    // The point to finalize is `lag` from the end of the window.
    val center = window.size - 1 - lag
    return smoothAt(center).also { emitted++ }
  }

  /**
   * Emit every point still pending (the trailing `lag` fixes), smoothed over
   * whatever window remains. Call when tracking stops so the tail of the route
   * isn't stranded. Returns them oldest-first; the smoother is reset.
   */
  @Synchronized
  fun flush(): List<Smoothed<T>> {
    val pendingStart = (window.size - (received - emitted)).coerceAtLeast(0)
    val out = ArrayList<Smoothed<T>>(window.size - pendingStart)
    for (i in pendingStart until window.size) out.add(smoothAt(i))
    window.clear()
    emitted = 0
    received = 0
    return out
  }

  private fun smoothAt(center: Int): Smoothed<T> {
    val c = window.elementAt(center)
    var sumW = 0.0
    var lat = 0.0
    var lng = 0.0
    for (i in window.indices) {
      val d = i - center
      if (d < -lag || d > lag) continue
      val p = window.elementAt(i)
      val acc = if (p.accuracy > 0.5) p.accuracy else 0.5
      // Inverse-variance weight decayed by distance from the center, so the
      // point's own (and best-accuracy) fixes dominate the estimate.
      val w = 1.0 / (acc * acc * (1.0 + d * d))
      sumW += w
      lat += w * p.lat
      lng += w * p.lng
    }
    return Smoothed(lat / sumW, lng / sumW, c.payload)
  }
}
