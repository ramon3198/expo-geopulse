package expo.modules.geopulse.fusion

/**
 * JNI wrapper around the C++/NDK sensor-fusion core (`libgeopulse-fusion.so`):
 * accuracy gating, speed-based outlier rejection, and Kalman smoothing.
 *
 * Use [isAvailable] before constructing; if the native library can't load (which
 * should never happen on a correctly built app), callers fall back to raw fixes.
 */
class KalmanBridge(
  enableKalman: Boolean,
  useCvModel: Boolean,
  accuracyFilter: Double,
  maxSpeed: Double,
  processNoise: Double,
) {
  data class Result(
    val accepted: Boolean,
    val filtered: Boolean,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double,
  )

  private var handle: Long = nativeCreate(enableKalman, useCvModel, accuracyFilter, maxSpeed, processNoise)

  /**
   * Run a fix through the native pipeline. [hasVelocity] marks the doppler
   * params (speed m/s, compass bearing deg, speed accuracy m/s) as valid — the
   * CV model fuses them; the scalar filter ignores them.
   */
  fun process(
    latitude: Double,
    longitude: Double,
    accuracy: Double,
    timestampMs: Long,
    hasVelocity: Boolean = false,
    speedMps: Double = 0.0,
    bearingDeg: Double = 0.0,
    speedAccuracyMps: Double = -1.0,
  ): Result {
    // Defensive: never call into a destroyed handle (callers serialize this, but
    // a 0 handle must pass the fix through rather than crash the native layer).
    if (handle == 0L) return Result(accepted = true, filtered = false, latitude, longitude, accuracy)
    val a = nativeProcess(handle, latitude, longitude, accuracy, timestampMs, hasVelocity, speedMps, bearingDeg, speedAccuracyMps)
    return Result(
      accepted = a[0] != 0.0,
      filtered = a[1] != 0.0,
      latitude = a[2],
      longitude = a[3],
      accuracy = a[4],
    )
  }

  fun reset() {
    if (handle != 0L) nativeReset(handle)
  }

  /**
   * Activity-adaptive tuning: adjust the Kalman process noise (m/s) and the
   * outlier gate's max speed (m/s) for the current motion type. Non-positive
   * values leave the respective knob unchanged.
   */
  fun setMotionProfile(
    processNoise: Double,
    maxSpeed: Double,
  ) {
    if (handle != 0L) nativeSetMotionProfile(handle, processNoise, maxSpeed)
  }

  /**
   * Floor on each fix's reported accuracy (m). 1.0 by default; lower it to let
   * sub-meter sources (RTK) keep their real precision. Ignores values <= 0.
   */
  fun setMinAccuracy(minAccuracyMeters: Double) {
    if (handle != 0L) nativeSetMinAccuracy(handle, minAccuracyMeters)
  }

  fun destroy() {
    if (handle != 0L) {
      nativeDestroy(handle)
      handle = 0L
    }
  }

  private external fun nativeCreate(
    enableKalman: Boolean,
    useCvModel: Boolean,
    accuracyFilter: Double,
    maxSpeed: Double,
    processNoise: Double,
  ): Long

  private external fun nativeProcess(
    handle: Long,
    latitude: Double,
    longitude: Double,
    accuracy: Double,
    timestampMs: Long,
    hasVelocity: Boolean,
    speedMps: Double,
    bearingDeg: Double,
    speedAccuracyMps: Double,
  ): DoubleArray

  private external fun nativeReset(handle: Long)

  private external fun nativeSetMotionProfile(
    handle: Long,
    processNoise: Double,
    maxSpeed: Double,
  )

  private external fun nativeSetMinAccuracy(
    handle: Long,
    minAccuracy: Double,
  )

  private external fun nativeDestroy(handle: Long)

  companion object {
    @Volatile private var libraryLoaded = false
    @Volatile private var loadFailed = false

    /** Loads `libgeopulse-fusion.so` once. Returns false if unavailable. */
    fun isAvailable(): Boolean {
      if (libraryLoaded) return true
      if (loadFailed) return false
      return try {
        System.loadLibrary("geopulse-fusion")
        libraryLoaded = true
        true
      } catch (t: Throwable) {
        loadFailed = true
        false
      }
    }
  }
}
