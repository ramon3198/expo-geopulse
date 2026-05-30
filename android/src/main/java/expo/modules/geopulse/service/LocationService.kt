package expo.modules.geopulse.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import expo.modules.geopulse.core.GeoPulseController
import expo.modules.geopulse.core.PermissionsManager
import expo.modules.geopulse.location.LocationEngine
import expo.modules.geopulse.motion.MotionManager

/**
 * Foreground service that owns continuous background location tracking.
 *
 * Declared in the library manifest with `foregroundServiceType="location"`, so it
 * merges into every consuming app automatically. Posts an ongoing notification
 * (mandatory), streams fixes from [LocationEngine] into [GeoPulseController], and
 * runs [MotionManager] to pause GPS while stationary (the big battery win).
 */
class LocationService : Service() {
  companion object {
    private const val CHANNEL_ID = "geopulse_tracking"
    private const val NOTIFICATION_ID = 48151623
  }

  private var engine: LocationEngine? = null
  private var motion: MotionManager? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    startForegroundWithNotification()
    startTracking()
    // START_STICKY: the OS restarts the service if it is killed while tracking.
    return START_STICKY
  }

  private fun startTracking() {
    if (!PermissionsManager.hasLocationPermission(applicationContext)) {
      GeoPulseController.emit(
        "onError",
        mapOf("code" to "PERMISSION_DENIED", "message" to "Location permission not granted"),
      )
      stopTrackingAndSelf()
      return
    }
    resumeLocationUpdates()
    startMotionDetection()
  }

  private fun startMotionDetection() {
    MotionManager.activeListener = object : MotionManager.Listener {
      override fun onActivity(type: String, confidence: Int) {
        GeoPulseController.notifyActivity(type, confidence)
      }

      override fun onMotionChange(isMoving: Boolean) {
        GeoPulseController.notifyMotionChange(isMoving)
        if (GeoPulseController.config.stopOnStationary) {
          if (isMoving) resumeLocationUpdates() else pauseLocationUpdates()
        }
      }
    }
    val manager = MotionManager(applicationContext)
    motion = manager
    manager.start()
  }

  private fun resumeLocationUpdates() {
    val eng = engine ?: LocationEngine(applicationContext).also { engine = it }
    eng.start(GeoPulseController.config) { location ->
      GeoPulseController.onLocationUpdate(location)
    }
  }

  private fun pauseLocationUpdates() {
    engine?.stop()
  }

  private fun teardown() {
    motion?.stop()
    motion = null
    MotionManager.activeListener = null
    engine?.stop()
    engine = null
  }

  private fun stopTrackingAndSelf() {
    teardown()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      stopForeground(STOP_FOREGROUND_REMOVE)
    } else {
      @Suppress("DEPRECATION")
      stopForeground(true)
    }
    stopSelf()
  }

  override fun onDestroy() {
    teardown()
    super.onDestroy()
  }

  private fun startForegroundWithNotification() {
    val notification = buildNotification()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  private fun buildNotification(): Notification {
    val cfg = GeoPulseController.config.notification
    val title = cfg?.title ?: "Location tracking active"
    val text = cfg?.text ?: "Recording your location in the background"

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      val channel = NotificationChannel(
        CHANNEL_ID,
        cfg?.channelName ?: "Location tracking",
        NotificationManager.IMPORTANCE_LOW,
      )
      manager.createNotificationChannel(channel)
    }

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle(title)
      .setContentText(text)
      .setSmallIcon(resolveSmallIcon(cfg?.smallIcon))
      .setOngoing(true)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .build()
  }

  private fun resolveSmallIcon(name: String?): Int {
    if (!name.isNullOrEmpty()) {
      val drawable = resources.getIdentifier(name, "drawable", packageName)
      if (drawable != 0) return drawable
      val mipmap = resources.getIdentifier(name, "mipmap", packageName)
      if (mipmap != 0) return mipmap
    }
    val appIcon = applicationInfo.icon
    return if (appIcon != 0) appIcon else android.R.drawable.ic_menu_mylocation
  }
}
