package expo.modules.geopulse.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import expo.modules.geopulse.core.GeoPulseController
import expo.modules.geopulse.core.PermissionsManager
import expo.modules.geopulse.driving.DrivingEventsManager
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
    private const val WATCHDOG_INTERVAL_MS = 10_000L
  }

  private var engine: LocationEngine? = null
  private var motion: MotionManager? = null
  private var driving: DrivingEventsManager? = null

  // Outage watchdog: detects signal loss (tunnel, indoors) and recovery.
  private val watchdogHandler = Handler(Looper.getMainLooper())
  private var lastFixElapsed = 0L
  private var outageActive = false
  private var paused = false
  private val watchdogTick = object : Runnable {
    override fun run() {
      checkOutage()
      watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    startForegroundWithNotification()
    startTracking()
    // START_STICKY: the OS restarts the service if it is killed while tracking.
    return START_STICKY
  }

  private fun outageThresholdMs(): Long {
    val cfg = GeoPulseController.config
    return if (cfg.outageThreshold > 0) {
      cfg.outageThreshold
    } else {
      (cfg.locationUpdateInterval * 3).coerceAtLeast(30_000)
    }
  }

  private fun checkOutage() {
    // While paused for being stationary, "no fixes" is expected — not an outage.
    if (paused || lastFixElapsed == 0L) return
    val silence = SystemClock.elapsedRealtime() - lastFixElapsed
    if (!outageActive && silence >= outageThresholdMs()) {
      outageActive = true
      GeoPulseController.notifyOutage(active = true, durationMs = silence)
    }
  }

  private fun onFixReceived() {
    val now = SystemClock.elapsedRealtime()
    if (outageActive) {
      val duration = now - lastFixElapsed
      outageActive = false
      GeoPulseController.notifyOutage(active = false, durationMs = duration)
    }
    lastFixElapsed = now
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
    startDrivingDetection()
    watchdogHandler.removeCallbacks(watchdogTick)
    watchdogHandler.postDelayed(watchdogTick, WATCHDOG_INTERVAL_MS)
  }

  private fun startDrivingDetection() {
    val cfg = GeoPulseController.config
    if (!cfg.enableDrivingEvents) return
    val manager = DrivingEventsManager(
      applicationContext,
      cfg.harshAccelThreshold,
      cfg.harshBrakeThreshold,
      cfg.speedLimit,
      cfg.idleTimeout,
      cfg.drivingMinSpeed,
    )
    manager.listener = object : DrivingEventsManager.Listener {
      override fun onDrivingEvent(type: String, severity: String, magnitude: Double, speedMps: Double) {
        GeoPulseController.notifyDrivingEvent(type, severity, magnitude, speedMps)
      }
    }
    driving = manager
    manager.start()
    // Route GPS speed from the location pipeline into the detector.
    GeoPulseController.drivingSpeedSink = { speed -> driving?.onSpeed(speed) }
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
    paused = false
    lastFixElapsed = SystemClock.elapsedRealtime() // grace period before flagging an outage
    val eng = engine ?: LocationEngine(applicationContext).also { engine = it }
    eng.start(GeoPulseController.config) { location ->
      // Ignore any fix drained from the engine's queue after tracking stopped
      // (LocationEngine.stop() uses quitSafely(), which delivers pending fixes).
      if (!GeoPulseController.enabled) return@start
      onFixReceived()
      GeoPulseController.onLocationUpdate(location)
    }
  }

  private fun pauseLocationUpdates() {
    paused = true
    engine?.stop()
  }

  private fun teardown() {
    watchdogHandler.removeCallbacks(watchdogTick)
    motion?.stop()
    motion = null
    MotionManager.activeListener = null
    driving?.stop()
    driving = null
    GeoPulseController.drivingSpeedSink = null
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
