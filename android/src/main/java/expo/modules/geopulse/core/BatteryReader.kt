package expo.modules.geopulse.core

import android.content.Context
import android.os.BatteryManager
import android.os.SystemClock

/**
 * Cached battery state shared by the per-fix battery payload and the low-battery
 * auto-degrade check. Each BatteryManager property read is a binder IPC call to
 * the system service; doing 1-3 of them per fix (potentially 1 Hz for hours) is
 * pure overhead for a value that changes over minutes. One read serves all
 * callers for [TTL_MS] — worst case, a plug/unplug is noticed [TTL_MS] late,
 * which is harmless for both consumers.
 */
object BatteryReader {
  data class State(
    /** 0..100, or -1 when the OS can't report it. */
    val level: Int,
    val isCharging: Boolean,
  )

  private const val TTL_MS = 30_000L

  @Volatile private var cached: State? = null
  @Volatile private var readAtElapsed = 0L

  fun get(context: Context): State? {
    val now = SystemClock.elapsedRealtime()
    val hit = cached
    if (hit != null && now - readAtElapsed < TTL_MS) return hit
    val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return hit
    val state = State(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY), bm.isCharging)
    // Two threads racing here at most duplicate one IPC read; last write wins.
    cached = state
    readAtElapsed = now
    return state
  }
}
