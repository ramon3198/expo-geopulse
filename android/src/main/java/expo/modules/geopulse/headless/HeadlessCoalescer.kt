package expo.modules.geopulse.headless

/**
 * Buffers headless `onLocation` fixes and decides when to deliver them as one
 * batch (P1-7), so a device on a highway with `enableHeadless` + a low
 * `distanceFilter` doesn't spawn an ephemeral JS context per fix.
 *
 * Pure, thread-safe and host-testable: timing is driven by caller-supplied
 * timestamps, not a real clock. Flush when the buffer reaches [maxCount] (if > 0)
 * or [windowMs] elapsed since the first buffered fix (if > 0). [isActive] is false
 * when both are 0 — coalescing off, the caller delivers each fix immediately
 * (backward-compatible default).
 */
class HeadlessCoalescer<T>(
  private val windowMs: Long,
  private val maxCount: Int,
) {
  /** What [add] decided: a batch to deliver now (count hit), and/or arm a timer. */
  data class Decision<T>(
    val flushNow: List<T>?,
    val armTimer: Boolean,
  )

  private val lock = Any()
  private val buffer = ArrayList<T>()
  private var windowStartMs = 0L
  private var armed = false

  fun isActive(): Boolean = windowMs > 0L || maxCount > 0

  /**
   * Buffer [item]. Returns the batch to deliver now if the count threshold is hit
   * (else null), plus whether the caller should arm a timed flush for [windowMs]
   * from now (true only on the fix that opens a new window).
   */
  fun add(
    item: T,
    nowMs: Long,
  ): Decision<T> =
    synchronized(lock) {
      val openedWindow = !armed
      if (!armed) {
        windowStartMs = nowMs
        armed = true
      }
      buffer.add(item)
      if (maxCount > 0 && buffer.size >= maxCount) {
        return Decision(drain(), false)
      }
      Decision(null, openedWindow && windowMs > 0L)
    }

  /**
   * Timer callback: returns the batch if the window has elapsed by [nowMs], else
   * null (e.g. a count flush already drained it, or a newer window isn't due yet —
   * which makes a stale timer from a previous window a harmless no-op).
   */
  fun flushIfDue(nowMs: Long): List<T>? =
    synchronized(lock) {
      if (!armed) return null
      if (windowMs > 0L && nowMs - windowStartMs < windowMs) return null
      drain()
    }

  /** Deliver whatever is buffered right now (e.g. when tracking stops), or null. */
  fun drainNow(): List<T>? =
    synchronized(lock) {
      if (buffer.isEmpty()) null else drain()
    }

  private fun drain(): List<T> {
    val out = ArrayList(buffer)
    buffer.clear()
    armed = false
    return out
  }
}
