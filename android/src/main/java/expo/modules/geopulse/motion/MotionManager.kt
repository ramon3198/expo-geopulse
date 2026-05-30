package expo.modules.geopulse.motion

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Build
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity

/**
 * Battery-saving motion intelligence.
 *
 * Subscribes to Activity Recognition transitions (still / walking / running /
 * cycling / in-vehicle) and to the low-power significant-motion hardware sensor.
 * The foreground service uses these signals to stop GPS while the device is
 * stationary and resume it the moment real movement is detected — the single
 * biggest battery win in background location tracking.
 */
class MotionManager(private val context: Context) {

  interface Listener {
    fun onActivity(type: String, confidence: Int)
    fun onMotionChange(isMoving: Boolean)
  }

  private var pendingIntent: PendingIntent? = null
  private var sensorManager: SensorManager? = null
  private var significantMotion: Sensor? = null
  private var triggerListener: TriggerEventListener? = null

  fun start() {
    requestActivityTransitions()
    registerSignificantMotion()
  }

  fun stop() {
    removeActivityTransitions()
    unregisterSignificantMotion()
    lastMoving = null
  }

  @SuppressLint("MissingPermission")
  private fun requestActivityTransitions() {
    val activities = intArrayOf(
      DetectedActivity.STILL,
      DetectedActivity.WALKING,
      DetectedActivity.RUNNING,
      DetectedActivity.ON_BICYCLE,
      DetectedActivity.IN_VEHICLE,
      DetectedActivity.ON_FOOT,
    )
    val transitions = activities.map { activity ->
      ActivityTransition.Builder()
        .setActivityType(activity)
        .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
        .build()
    }
    val request = ActivityTransitionRequest(transitions)

    val intent = Intent(context, ActivityTransitionReceiver::class.java)
    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    } else {
      PendingIntent.FLAG_UPDATE_CURRENT
    }
    val pi = PendingIntent.getBroadcast(context, 0, intent, flags)
    pendingIntent = pi

    runCatching {
      ActivityRecognition.getClient(context).requestActivityTransitionUpdates(request, pi)
    }
  }

  private fun removeActivityTransitions() {
    val pi = pendingIntent ?: return
    runCatching {
      ActivityRecognition.getClient(context).removeActivityTransitionUpdates(pi)
    }
    pi.cancel()
    pendingIntent = null
  }

  private fun registerSignificantMotion() {
    val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
    val sensor = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return
    sensorManager = sm
    significantMotion = sensor
    armSignificantMotion()
  }

  private fun armSignificantMotion() {
    val sm = sensorManager ?: return
    val sensor = significantMotion ?: return
    val listener = object : TriggerEventListener() {
      override fun onTrigger(event: TriggerEvent?) {
        activeListener?.onMotionChange(true)
        // TYPE_SIGNIFICANT_MOTION is one-shot; re-arm for the next motion.
        armSignificantMotion()
      }
    }
    triggerListener = listener
    runCatching { sm.requestTriggerSensor(listener, sensor) }
  }

  private fun unregisterSignificantMotion() {
    val sm = sensorManager ?: return
    val sensor = significantMotion ?: return
    val listener = triggerListener ?: return
    runCatching { sm.cancelTriggerSensor(listener, sensor) }
    triggerListener = null
  }

  companion object {
    @Volatile
    var activeListener: Listener? = null

    @Volatile
    private var lastMoving: Boolean? = null

    /** Called by [ActivityTransitionReceiver] for each ENTER transition. */
    fun handleTransition(activityType: Int, @Suppress("UNUSED_PARAMETER") transitionType: Int) {
      val type = toName(activityType)
      activeListener?.onActivity(type, 100)

      val moving = activityType != DetectedActivity.STILL
      if (lastMoving != moving) {
        lastMoving = moving
        activeListener?.onMotionChange(moving)
      }
    }

    private fun toName(activityType: Int): String = when (activityType) {
      DetectedActivity.STILL -> "still"
      DetectedActivity.WALKING, DetectedActivity.ON_FOOT -> "walking"
      DetectedActivity.RUNNING -> "running"
      DetectedActivity.ON_BICYCLE -> "on_bicycle"
      DetectedActivity.IN_VEHICLE -> "in_vehicle"
      else -> "unknown"
    }
  }
}
