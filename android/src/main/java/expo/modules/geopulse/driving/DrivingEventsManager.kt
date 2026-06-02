package expo.modules.geopulse.driving

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * On-device driving-behaviour detection (the kind of signal Samsara / Geotab
 * capture): harsh braking, harsh acceleration, speeding, and idling — all
 * derived from the linear-acceleration sensor plus GPS speed, with no backend.
 *
 * The accelerometer detects sharp longitudinal/lateral events at high frequency;
 * GPS provides speed for speeding/idling. Events are debounced so a single
 * manoeuvre fires once, and carry a severity score (how far past threshold).
 */
class DrivingEventsManager(
  private val context: Context,
  private var harshAccelThreshold: Double, // m/s^2
  private var harshBrakeThreshold: Double, // m/s^2 (magnitude; brake = deceleration)
  private var speedLimitMps: Double, // m/s; 0 disables speeding
  private var idleTimeoutMs: Long, // continuous near-zero speed to call idling
  private var minSpeedMps: Double,          // min GPS speed before accel events count (0 = always)
) : SensorEventListener {
  interface Listener {
    /** type: harsh_braking | harsh_acceleration | speeding | idling */
    fun onDrivingEvent(
      type: String,
      severity: String,
      magnitude: Double,
      speedMps: Double,
    )
  }

  var listener: Listener? = null

  private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
  private var sensor: Sensor? = null
  private var usingRawAccelerometer = false

  // onSpeed runs on the location worker thread; onSensorChanged on the main
  // thread. These shared fields are @Volatile so the sensor thread sees the
  // latest speed/braking trend instead of a stale cached value.
  @Volatile private var lastEventElapsed = 0L
  @Volatile private var lastSpeedMps = 0.0
  private var idleStartElapsed = 0L
  private var idlingReported = false
  private var speedingReported = false
  // Guards a sensor event already queued when stop() ran from firing afterwards.
  @Volatile private var stopped = false

  fun setParams(
    accel: Double,
    brake: Double,
    speedLimit: Double,
    idleTimeout: Long,
    minSpeed: Double,
  ) {
    harshAccelThreshold = accel
    harshBrakeThreshold = brake
    speedLimitMps = speedLimit
    idleTimeoutMs = idleTimeout
    minSpeedMps = minSpeed
  }

  fun start() {
    stopped = false
    val sm = sensorManager ?: return
    val linear = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    if (linear != null) {
      sensor = linear
      usingRawAccelerometer = false
    } else {
      sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
      usingRawAccelerometer = true
    }
    sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
  }

  fun stop() {
    stopped = true
    sensorManager?.unregisterListener(this)
    idleStartElapsed = 0L
    idlingReported = false
    speedingReported = false
    // Also clear the speed/brake baseline so a stale value can't drive a phantom
    // event if the manager is reused or a queued sensor event slips through.
    lastSpeedMps = 0.0
    decelerating = false
    lastEventElapsed = 0L
  }

  override fun onSensorChanged(event: SensorEvent) {
    if (stopped) return
    // Magnitude of horizontal acceleration. For the raw accelerometer we drop the
    // dominant gravity axis by using only the two smaller-variance axes' vector;
    // a simple robust proxy is the total magnitude minus ~g when near 9.81.
    val x = event.values[0]
    val y = event.values[1]
    val z = event.values[2]
    val magnitude =
      if (usingRawAccelerometer) {
        // Subtract gravity: |a| deviation from 9.81 captures real acceleration spikes.
        kotlin.math.abs(sqrt((x * x + y * y + z * z).toDouble()) - SensorManager.GRAVITY_EARTH)
      } else {
        // Linear acceleration already excludes gravity; use horizontal magnitude.
        sqrt((x * x + y * y).toDouble())
      }

    val now = SystemClock.elapsedRealtime()
    if (now - lastEventElapsed < DEBOUNCE_MS) return

    // Only meaningful while actually moving (avoids phone-handling false positives).
    // minSpeedMps = 0 disables the gate (useful for bench testing by hand).
    if (minSpeedMps > 0.0 && lastSpeedMps < minSpeedMps) return

    // We can't reliably know sign from magnitude alone, so use deceleration vs
    // acceleration heuristics from the speed trend captured in onSpeed().
    val braking = decelerating
    if (braking && magnitude >= harshBrakeThreshold) {
      fire("harsh_braking", magnitude, harshBrakeThreshold, now)
    } else if (!braking && magnitude >= harshAccelThreshold) {
      fire("harsh_acceleration", magnitude, harshAccelThreshold, now)
    }
  }

  override fun onAccuracyChanged(
    sensor: Sensor?,
    accuracy: Int,
  ) {}

  @Volatile private var decelerating = false

  /** Fed from the location pipeline on every fix. */
  fun onSpeed(speedMps: Double) {
    decelerating = speedMps < lastSpeedMps - 0.2
    lastSpeedMps = speedMps

    // Speeding (edge-triggered: fire once on crossing, reset when back under).
    if (speedLimitMps > 0.0) {
      if (speedMps > speedLimitMps && !speedingReported) {
        speedingReported = true
        val sev = severityFor(speedMps, speedLimitMps)
        listener?.onDrivingEvent("speeding", sev, speedMps, speedMps)
      } else if (speedMps <= speedLimitMps * 0.95) {
        speedingReported = false
      }
    }

    // Idling: near-zero speed sustained.
    if (idleTimeoutMs > 0) {
      val now = SystemClock.elapsedRealtime()
      if (speedMps < IDLE_SPEED_MPS) {
        if (idleStartElapsed == 0L) idleStartElapsed = now
        if (!idlingReported && now - idleStartElapsed >= idleTimeoutMs) {
          idlingReported = true
          listener?.onDrivingEvent("idling", "warning", 0.0, speedMps)
        }
      } else {
        idleStartElapsed = 0L
        idlingReported = false
      }
    }
  }

  private fun fire(
    type: String,
    magnitude: Double,
    threshold: Double,
    now: Long,
  ) {
    lastEventElapsed = now
    listener?.onDrivingEvent(type, severityFor(magnitude, threshold), magnitude, lastSpeedMps)
  }

  private fun severityFor(
    value: Double,
    threshold: Double,
  ): String {
    if (threshold <= 0.0) return "warning"
    val ratio = value / threshold
    return when {
      ratio >= 1.6 -> "critical"
      ratio >= 1.25 -> "alert"
      else -> "warning"
    }
  }

  companion object {
    private const val DEBOUNCE_MS = 2_000L
    private const val IDLE_SPEED_MPS = 0.5
  }
}
