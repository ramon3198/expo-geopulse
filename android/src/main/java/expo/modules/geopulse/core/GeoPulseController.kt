package expo.modules.geopulse.core

import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import expo.modules.geopulse.db.LocationStore
import expo.modules.geopulse.fusion.KalmanBridge
import expo.modules.geopulse.fusion.TrackSmoother
import expo.modules.geopulse.geofence.GeofenceManager
import expo.modules.geopulse.headless.GeoPulseHeadlessService
import expo.modules.geopulse.headless.HeadlessCoalescer
import expo.modules.geopulse.location.LocationEngine
import expo.modules.geopulse.service.LocationService
import expo.modules.geopulse.sync.SyncEngine
import expo.modules.geopulse.sync.SyncWorker
import expo.modules.geopulse.trip.TripVisitManager
import expo.modules.geopulse.util.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Process-wide brain of the SDK.
 *
 * It is a singleton (not tied to the React lifecycle) so the foreground service,
 * receivers and headless tasks can all funnel events through the same place. The
 * Expo module attaches an [EventDispatcher] while a JS runtime is alive and
 * detaches on teardown.
 */
object GeoPulseController {
  private const val MAX_SPEED_MPS = 100.0
  private const val PROCESS_NOISE = 3.0
  private const val DEFAULT_GET_LIMIT = 1000
  // Coalesce BUFFER_OVERFLOW emissions: emit once this many points have been
  // dropped, so a full buffer doesn't spam one event per fix.
  private const val BUFFER_OVERFLOW_EMIT_THRESHOLD = 50
  // Battery must climb this far above the low-battery threshold (fraction, 0..1)
  // before auto-degrade is undone, so it doesn't flap right at the boundary.
  private const val BATTERY_RECOVERY_HYSTERESIS = 0.1

  // Tracking fields a named preset controls. Hand-tuning any of these (without
  // also passing `preset`) drops back to manual mode so the change sticks.
  private val PRESET_TUNING_KEYS =
    setOf(
      "desiredAccuracy",
      "distanceFilter",
      "locationUpdateInterval",
      "fastestLocationUpdateInterval",
    )

  // Written by attach/detach on the JS thread, read by emit() on the location
  // worker thread — volatile so a detach is seen promptly (no stale dispatch).
  @Volatile private var dispatcher: EventDispatcher? = null

  // Event names JS currently has at least one listener for (maintained by the
  // module's per-event OnStartObserving/OnStopObserving). With a runtime attached
  // but no listener, dispatching would serialize the payload across the bridge
  // just for JS to drop it — for onLocation at ~1 Hz that's hours of wasted
  // CPU. JS observably sees the same thing either way: nothing.
  private val observedEvents: MutableSet<String> = ConcurrentHashMap.newKeySet()
  private var appContext: Context? = null

  @Volatile
  var config: GeoPulseConfig = GeoPulseConfig()
    private set

  @Volatile
  var enabled: Boolean = false
    private set

  @Volatile
  var isMoving: Boolean = false
    private set

  @Volatile
  var odometer: Double = 0.0

  // Read from several threads (location worker, motion/driving detectors, the
  // JS module thread) so its visibility must be guaranteed.
  @Volatile
  private var lastLocation: Map<String, Any?>? = null
  // Read/written from the location worker thread and (via simulateLocation) the
  // JS thread; volatile for safe publication of the reference.
  @Volatile private var lastAndroidLocation: Location? = null
  private var fusion: KalmanBridge? = null
  // Serializes all native-handle access (create / process / reset / destroy) so
  // a config-driven rebuild on one thread can't free the handle while the
  // location worker thread is mid-process() — that would be a use-after-free.
  private val fusionLock = Any()

  private val ioExecutor = Executors.newSingleThreadExecutor()
  // Schedules window-based flushes for headless onLocation coalescing (P1-7).
  private val scheduler = Executors.newSingleThreadScheduledExecutor()
  @Volatile private var coalescer: HeadlessCoalescer<Map<String, Any?>>? = null

  // Fixed-lag track smoother (config smoothingLag > 0): emitted/persisted points
  // are re-estimated with future fixes before release. Null when off.
  @Volatile private var smoother: TrackSmoother<Map<String, Any?>>? = null

  // Accuracy inflation from GNSS signal quality, written by the LocationService's
  // GnssStatus monitor (1.0 = healthy constellation; >1 = trust fixes less).
  @Volatile var gnssAccuracyInflation: Double = 1.0

  // Tracking session id: fresh per explicit start(), persisted so a cold process
  // restart continues the same logical session. Stamped on every location so
  // backends/dashboards can group points per tracking run instead of one
  // ever-growing trace.
  @Volatile private var sessionId: String? = null
  private var store: LocationStore? = null
  // Auth headers refreshed by JS at runtime (setAuthHeaders) and persisted, so the
  // headless sync worker uses a live token even in a cold process. Loaded lazily.
  @Volatile private var authHeaders: Map<String, String> = emptyMap()
  @Volatile private var authHeadersLoaded = false
  private var geofenceManager: GeofenceManager? = null
  private var tripManager: TripVisitManager? = null

  // ---- attachment ----

  fun attach(
    context: Context,
    eventDispatcher: EventDispatcher,
  ) {
    appContext = context.applicationContext
    // A fresh runtime starts with no listeners; observation state is rebuilt by
    // OnStartObserving as JS re-subscribes (stale truths would defeat the gate).
    observedEvents.clear()
    dispatcher = eventDispatcher
  }

  fun detach() {
    dispatcher = null
  }

  /** Tracks per-event JS listener presence (module OnStartObserving/OnStopObserving). */
  fun setEventObserved(
    event: String,
    observed: Boolean,
  ) {
    if (observed) observedEvents.add(event) else observedEvents.remove(event)
  }

  // ---- configuration / lifecycle ----

  fun ready(cfg: GeoPulseConfig) {
    val applied =
      synchronized(configLock) {
        val resolved = cfg.resolvePreset()
        config = resolved
        clearBatteryDegradeState()
        resolved
      }
    rebuildFusion()
    rebuildCoalescer()
    reconcilePeriodicDrain()
    persistConfig(applied)
  }

  /** Merge only the provided keys onto the current config (does NOT replace it). */
  fun setConfig(patch: Map<String, Any?>) {
    // Merge onto the user's real config, not a temporary battery-degraded one —
    // otherwise e.g. setConfig({ url }) during a low-battery degrade would freeze
    // the eco cadence in place. clearBatteryDegradeState() below then lets
    // auto-degrade re-apply from the new config if the battery is still low.
    // Compute on a fresh copy and publish it atomically via the @Volatile
    // `config` reference (the location worker thread reads it concurrently).
    // The whole read-modify-write runs under configLock so it can't interleave
    // with the auto-degrade on the worker thread.
    val applied =
      synchronized(configLock) {
        val base = preDegradeConfig ?: config
        val merged = base.copy().applyMap(patch)
        // If the caller hand-tuned a preset-controlled tracking field without also
        // naming a preset, switch to manual mode so resolvePreset() below doesn't
        // silently overwrite that change.
        if (PRESET_TUNING_KEYS.any { patch.containsKey(it) } && !patch.containsKey("preset")) {
          merged.preset = ""
        }
        val resolved = merged.resolvePreset()
        config = resolved
        clearBatteryDegradeState() // an explicit config supersedes any auto-degrade
        resolved
      }
    rebuildFusion()
    rebuildCoalescer()
    reconcilePeriodicDrain()
    // Persist the config we just set — never a transient eco that a concurrent
    // battery auto-degrade may have written to the live field after the lock.
    persistConfig(applied)
    // Apply "while running": re-issue the GPS request and reconcile driving
    // detection on the live service (the API promises setConfig takes effect
    // without a stop/start).
    if (enabled) launchService()
  }

  private fun persistConfig(cfg: GeoPulseConfig) {
    val ctx = appContext ?: return
    ioExecutor.execute { runCatching { ConfigStore(ctx).saveConfig(cfg.toMap()) } }
  }

  /**
   * Restores persisted config and resumes tracking after a reboot/process
   * restart. Runs without a JS runtime — locations are buffered and synced via
   * WorkManager until the app is opened again.
   */
  fun restoreAndStart(context: Context) {
    if (appContext == null) appContext = context.applicationContext
    val map = runCatching { ConfigStore(context).loadConfig() }.getOrNull() ?: return
    config = GeoPulseConfig.fromMap(map)
    rebuildFusion()
    rebuildCoalescer()
    reconcilePeriodicDrain()
    // Drain any backlog that survived the reboot regardless of startOnBoot —
    // "resume tracking" and "upload what was already recorded" are independent
    // concerns. No-ops when the buffer is empty or no url is configured.
    if (config.url != null) enqueueSync()
    if (config.startOnBoot) start()
  }

  /**
   * Ensure the controller has an app context and config in a process that may
   * have started cold — woken by a receiver/worker/START_STICKY service with no
   * JS runtime ever attached. Without this, `emit()` would see a default config
   * (`enableHeadless=false`) and a null `appContext`, dropping the event in
   * exactly the "app killed" case headless exists for. No-op once initialized
   * (warm process, or boot already restored).
   */
  fun ensureInitialized(context: Context) {
    if (appContext != null) return
    appContext = context.applicationContext
    // A cold service/worker restart continues the persisted session — it's the
    // same logical tracking run. (An explicit start(), including the boot
    // resume, generates a fresh one instead.)
    if (sessionId == null) {
      sessionId = runCatching { ConfigStore(context).loadSessionId() }.getOrNull()
    }
    runCatching { ConfigStore(context).loadConfig() }.getOrNull()?.let {
      config = GeoPulseConfig.fromMap(it)
      rebuildFusion()
      rebuildCoalescer()
      reconcilePeriodicDrain()
    }
  }

  fun start() {
    enabled = true
    // A fresh tracking session: every explicit start() begins a new one (cold
    // service restarts reuse the persisted id instead — see ensureInitialized).
    val newSession = UUID.randomUUID().toString()
    sessionId = newSession
    appContext?.let { ctx ->
      ioExecutor.execute { runCatching { ConfigStore(ctx).saveSessionId(newSession) } }
    }
    // Fresh start at the configured accuracy: undo any prior battery auto-degrade
    // (it re-applies on the next fix if the battery is still low).
    synchronized(configLock) {
      if (degradedForBattery) {
        preDegradeConfig?.let { config = it }
        clearBatteryDegradeState()
      }
    }
    // If the service couldn't actually start (e.g. a background-start restriction
    // on Android 12+, or no background permission at boot), reflect that instead
    // of reporting tracking active with no service running — important at boot,
    // where there's no JS dispatcher to receive the SERVICE_START_FAILED error.
    if (!launchService()) {
      enabled = false
    } else {
      warnIfBackgroundPermissionMissing()
    }
  }

  /**
   * On start with foreground-only location (no ACCESS_BACKGROUND_LOCATION), warn
   * the consumer (P1-6): tracking works while the app is visible but pauses when
   * backgrounded. Non-fatal — the SDK never assumes background or crashes; the
   * consumer can keep tracking degraded or request the upgrade.
   */
  private fun warnIfBackgroundPermissionMissing() {
    val ctx = appContext ?: return
    if (PermissionsManager.hasLocationPermission(ctx) && !PermissionsManager.hasBackgroundPermission(ctx)) {
      emit(
        "onError",
        mapOf(
          "code" to "BACKGROUND_PERMISSION_MISSING",
          "message" to
            "Tracking started with foreground-only location; it pauses when the app is backgrounded. " +
            "Request \"Allow all the time\" for background tracking.",
        ),
      )
    }
  }

  /** Forget any battery auto-degrade state (an explicit config is authoritative). */
  private fun clearBatteryDegradeState() {
    degradedForBattery = false
    preDegradeConfig = null
  }

  /**
   * (Re)starts the foreground service and reports whether the start was accepted.
   * The service's `onStartCommand` re-applies the current config — re-issuing the
   * GPS request and reconciling driving detection — so this doubles as "apply
   * config to the running tracker".
   */
  private fun launchService(): Boolean {
    val ctx = appContext ?: return false
    val intent = Intent(ctx, LocationService::class.java)
    // Guard against ForegroundServiceStartNotAllowedException: re-launching to
    // apply config (or a battery degrade) can happen while the app is in the
    // background on Android 12+. The service is normally already running, but
    // OEM/edge behavior varies, so never let a failed (re)start crash the caller.
    return runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ctx.startForegroundService(intent)
      } else {
        ctx.startService(intent)
      }
    }.onFailure { e ->
      // The service couldn't be (re)started — most likely a background-start
      // restriction on Android 12+. Surface it instead of failing silently, so a
      // consumer that called start()/setConfig() isn't left thinking tracking is
      // active when no fixes will arrive.
      emit(
        "onError",
        mapOf(
          "code" to "SERVICE_START_FAILED",
          "message" to (
            e.message
              ?: "Could not start the tracking service (it may be blocked from the background)."
          ),
        ),
      )
    }.isSuccess
  }

  fun stop() {
    markStoppedByService()
    val ctx = appContext ?: return
    ctx.stopService(Intent(ctx, LocationService::class.java))
  }

  /**
   * Resets tracking state without issuing stopService. Called by [stop] and by
   * the service itself when it stops on its own (e.g. it found location permission
   * was revoked), so `getState()` never reports tracking active with no service
   * actually running.
   */
  fun markStoppedByService() {
    enabled = false
    synchronized(fusionLock) { fusion?.reset() }
    tripManager?.reset()
    // Release the smoother's tail (the last `lag` fixes it was still holding)
    // so a stop never strands the end of the route.
    val stopCfg = config
    smoother?.flush()?.forEach { sm ->
      deliver(patchCoords(sm.payload, sm.lat, sm.lng), stopCfg)
    }
    // Flush any buffered headless fixes so a graceful stop doesn't strand them.
    val ctx = appContext
    if (ctx != null) {
      coalescer?.drainNow()?.let { dispatchHeadless(ctx, "onLocation", Json.toJson(it)) }
    }
    // Final sync flush: without this the tail of the route sits in the buffer
    // until the app next opens (autoSync only fires per-fix, and fixes just
    // stopped). The worker no-ops if the buffer is empty; KEEP dedupes.
    if (config.url != null) enqueueSync()
  }

  // ---- location pipeline ----

  /** Called by the foreground service for every fix it receives. */
  fun onLocationUpdate(location: Location) {
    val cfg = config

    // Anti-spoofing: detect and (optionally) reject mock locations.
    if (LocationMapper.isMock(location)) {
      emit(
        "onError",
        mapOf(
          "code" to "MOCK_LOCATION",
          "message" to "A mock (spoofed) location was detected.",
        ),
      )
      if (cfg.disableMockLocations) return
    }

    // Battery-low auto-degrade: drop to the eco preset once below the threshold.
    maybeDegradeForBattery()

    var lat = location.latitude
    var lng = location.longitude
    var accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else cfg.defaultAccuracy
    var filtered = false
    var provider = location.provider ?: "fused"
    // Chip-reported values, kept for the debugIncludeRaw A/B payload.
    val rawLat = lat
    val rawLng = lng
    val rawAccuracy = accuracy

    // GNSS-quality gating: in an urban canyon the chip often keeps reporting
    // optimistic accuracy; scale what the filter sees by the constellation's
    // actual health (written by the service's GnssStatus monitor).
    if (cfg.gnssQualityGating) accuracy *= gnssAccuracyInflation

    // Doppler velocity for the CV model: usable with a bearing, or — when
    // parked — with near-zero speed, where the bearing is irrelevant and the
    // zero pins the filter's velocity state (kills stationary wander).
    val speedMps = if (location.hasSpeed()) location.speed.toDouble() else -1.0
    val hasVelocity = speedMps >= 0.0 && (location.hasBearing() || speedMps < 1.0)
    val bearingDeg = if (location.hasBearing()) location.bearing.toDouble() else 0.0
    val speedAccuracyMps =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasSpeedAccuracy()) {
        location.speedAccuracyMetersPerSecond.toDouble()
      } else {
        -1.0
      }

    val r = fuse(lat, lng, accuracy, location.time, hasVelocity, speedMps.coerceAtLeast(0.0), bearingDeg, speedAccuracyMps)
    if (r != null) {
      if (!r.accepted) return // outlier or below accuracy threshold -> drop
      lat = r.latitude
      lng = r.longitude
      accuracy = r.accuracy
      filtered = r.filtered
      if (filtered) provider = "kalman"
    } else if (cfg.accuracyFilter > 0.0 && accuracy > cfg.accuracyFilter) {
      return // native unavailable: apply the accuracy gate in Kotlin
    }

    lastAndroidLocation?.let { previous ->
      odometer += previous.distanceTo(location).toDouble()
    }
    lastAndroidLocation = location

    // No JS runtime, no headless task and no sync URL -> nothing consumes the
    // JS-shaped payload, so skip building it (UUID + battery + 9-key map per
    // fix). Odometer, geofences, trips and driving detection below still run on
    // the raw fix. lastLocation refreshes on the first fix after a runtime
    // attaches (one fix-interval of staleness, only in this consumer-less mode).
    val hasConsumer = dispatcher != null || cfg.enableHeadless || cfg.url != null
    if (hasConsumer) {
      var map =
        LocationMapper.toMap(
          location,
          isMoving,
          provider = provider,
          filtered = filtered,
          overrideLat = lat,
          overrideLng = lng,
          overrideAccuracy = accuracy,
          context = appContext,
        )
      val extras = mutableMapOf<String, Any?>()
      sessionId?.let { extras["sessionId"] = it }
      if (cfg.debugIncludeRaw && filtered) {
        extras["raw"] = mapOf("latitude" to rawLat, "longitude" to rawLng, "accuracy" to rawAccuracy)
      }
      if (extras.isNotEmpty()) {
        map = map.toMutableMap().apply { putAll(extras) }
      }
      // lastLocation tracks the LIVE fix even when smoothing delays emission.
      lastLocation = map

      val s = smoother
      if (s != null && s.isActive()) {
        // Fixed-lag smoothing: this fix is held until `lag` future ones refine
        // it; what comes back now is an older, finalized point (or nothing
        // while the window fills). Stop()/flush releases the tail.
        s.add(TrackSmoother.Point(lat, lng, accuracy, map))?.let { sm ->
          deliver(patchCoords(sm.payload, sm.lat, sm.lng), cfg)
        }
      } else {
        deliver(map, cfg)
      }
    }
    feedGeofences(lat, lng)

    if (cfg.enableTripDetection) {
      ensureTripManager(cfg).onLocation(lat, lng, location.time)
    }

    if (cfg.enableDrivingEvents && location.hasSpeed()) {
      drivingSpeedSink?.invoke(location.speed.toDouble())
    }
  }

  /** Emit a location to JS/headless and persist it for sync — one exit point. */
  private fun deliver(
    map: Map<String, Any?>,
    cfg: GeoPulseConfig,
  ) {
    emit("onLocation", map)
    if (cfg.url != null) persist(map, cfg)
  }

  /** Copy [map] with its coords moved to the smoothed position (rest untouched). */
  private fun patchCoords(
    map: Map<String, Any?>,
    lat: Double,
    lng: Double,
  ): Map<String, Any?> {
    val coords = (map["coords"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }?.toMutableMap() ?: return map
    coords["latitude"] = lat
    coords["longitude"] = lng
    return map.toMutableMap().apply { put("coords", coords) }
  }

  /** Set by LocationService so the controller can feed GPS speed to the sensor-based detector. */
  @Volatile
  var drivingSpeedSink: ((Double) -> Unit)? = null

  /** Called by the driving-events detector for each detected manoeuvre. */
  fun notifyDrivingEvent(
    type: String,
    severity: String,
    magnitude: Double,
    speedMps: Double,
  ) {
    emit(
      "onDrivingEvent",
      mapOf(
        "type" to type,
        "severity" to severity,
        "magnitude" to magnitude,
        "speed" to speedMps,
        "location" to lastLocation,
        "timestamp" to System.currentTimeMillis(),
      ),
    )
  }

  // ---- trip & visit detection ----

  private fun ensureTripManager(cfg: GeoPulseConfig): TripVisitManager {
    tripManager?.let {
      it.setParams(cfg.visitRadius, cfg.minVisitDwell)
      return it
    }
    val manager = TripVisitManager(cfg.visitRadius, cfg.minVisitDwell)
    manager.listener =
      object : TripVisitManager.Listener {
        override fun onVisitArrive(visit: TripVisitManager.Visit) {
          emit("onVisit", mapOf("action" to "arrive", "visit" to visit.toMap()))
        }

        override fun onVisitDepart(visit: TripVisitManager.Visit) {
          emit("onVisit", mapOf("action" to "depart", "visit" to visit.toMap()))
        }

        override fun onTripStart(trip: TripVisitManager.Trip) {
          emit("onTrip", mapOf("action" to "start", "trip" to trip.toMap()))
        }

        override fun onTripEnd(trip: TripVisitManager.Trip) {
          emit("onTrip", mapOf("action" to "end", "trip" to trip.toMap()))
        }
      }
    tripManager = manager
    return manager
  }

  fun getActiveTrip(): Map<String, Any?>? = tripManager?.activeTripMap()

  /**
   * If a low-battery threshold is configured and the device is at/below it (and
   * not charging), collapse to the eco preset once, remembering the prior config.
   * The original cadence is restored on the next `start()` (or replaced when JS
   * calls `ready()` / `setConfig()`).
   */
  @Volatile private var degradedForBattery = false
  // The config in effect just before a low-battery auto-degrade, so start() can
  // restore it (auto-degrade then re-applies on the next fix if still low).
  @Volatile private var preDegradeConfig: GeoPulseConfig? = null
  // Serializes config + battery-degrade transitions across threads: ready /
  // setConfig / start (JS thread) vs. this auto-degrade (location worker thread).
  private val configLock = Any()

  private fun maybeDegradeForBattery() {
    // Skip entirely only when the feature is off AND we're not currently degraded
    // (we still need to check for recovery while degraded).
    if (config.lowBatteryThreshold <= 0.0 && !degradedForBattery) return
    val ctx = appContext ?: return
    // Cached (30s TTL): this runs per fix, and each BatteryManager read is a
    // binder IPC call. A degrade/recovery reacting up to 30s late is harmless.
    val battery = BatteryReader.get(ctx) ?: return
    val level = battery.level
    if (level < 0) return
    val charging = battery.isCharging

    if (degradedForBattery) {
      // Recovery: restore the pre-degrade config once charging, or once the level
      // climbs back above the threshold plus hysteresis (avoids flapping at the
      // boundary). Without this, eco mode would stick for the whole session.
      val recovered =
        synchronized(configLock) {
          if (!degradedForBattery) return@synchronized false
          val thr = preDegradeConfig?.lowBatteryThreshold ?: 0.0
          if (charging || (thr > 0.0 && level / 100.0 >= thr + BATTERY_RECOVERY_HYSTERESIS)) {
            preDegradeConfig?.let { config = it }
            clearBatteryDegradeState()
            true
          } else {
            false
          }
        }
      if (recovered) {
        emit("onError", mapOf("code" to "BATTERY_OK", "message" to "Tracking restored to normal accuracy (battery $level%)."))
        launchService()
      }
      return
    }

    val degraded =
      synchronized(configLock) {
        // Re-check under the lock; setConfig/start may have just run on another
        // thread. Read the current config here so we degrade from the latest one.
        val base = config
        if (degradedForBattery || base.lowBatteryThreshold <= 0.0) {
          false
        } else if (level / 100.0 <= base.lowBatteryThreshold && !charging) {
          preDegradeConfig = base
          degradedForBattery = true
          config = base.copy().resolvePreset(forceEco = true)
          true
        } else {
          false
        }
      }
    if (degraded) {
      emit("onError", mapOf("code" to "BATTERY_LOW", "message" to "Tracking degraded to eco mode (battery $level%)."))
      // Re-apply the lighter LocationRequest immediately.
      launchService()
    }
  }

  /** Called by the LocationService watchdog when fixes stop / resume arriving. */
  fun notifyOutage(
    active: Boolean,
    durationMs: Long,
  ) {
    emit(
      "onProviderChange",
      mapOf(
        "enabled" to enabled,
        "gps" to !active,
        "network" to !active,
        "status" to if (active) 0 else 2,
        "outage" to active,
        "outageDuration" to durationMs,
      ),
    )
  }

  /** Called by the motion manager when the detected activity changes. */
  fun notifyActivity(
    type: String,
    confidence: Int,
  ) {
    emit("onActivityChange", mapOf("activity" to type, "confidence" to confidence))
    // Activity-adaptive fusion tuning: a still device gets strong smoothing, a
    // vehicle a filter that doesn't fight it (less lag in turns).
    val profile = motionProfileFor(type)
    lastMotionProfile = profile
    synchronized(fusionLock) { fusion?.setMotionProfile(profile.first, profile.second) }
  }

  // (processNoise m/s, maxSpeed m/s) per detected activity. q tracks how fast the
  // device can really move; maxSpeed is the outlier gate. Pedestrian gates stay at
  // 40 m/s on purpose: activity recognition lags transitions by 10-60 s, and a
  // tight gate would reject every real fix of a drive still classified as walking
  // — 40 still catches multipath teleports (typically hundreds of m/s).
  private fun motionProfileFor(activity: String): Pair<Double, Double> =
    when (activity) {
      "still" -> 0.5 to 40.0
      "walking" -> 1.2 to 40.0
      "running" -> 2.0 to 40.0
      "on_bicycle" -> 2.5 to 40.0
      "in_vehicle" -> 8.0 to MAX_SPEED_MPS
      else -> PROCESS_NOISE to MAX_SPEED_MPS
    }

  // Last applied profile, so a rebuilt fusion engine starts from the current
  // activity instead of the defaults. Volatile: written on the motion thread,
  // read under fusionLock on the location worker.
  @Volatile private var lastMotionProfile: Pair<Double, Double> = PROCESS_NOISE to MAX_SPEED_MPS

  /** Called by the motion manager when moving<->stationary state flips. */
  fun notifyMotionChange(moving: Boolean) {
    val wasMoving = isMoving
    isMoving = moving
    // Leaving stationary while stopOnStationary is on: GPS was off for the stop, so
    // the Kalman's last position/variance is stale. Reset it (P1-5) so the first
    // post-resume fix re-seeds the filter and skips the speed-outlier gate — no
    // false "jump" or rejection when movement starts far from where it stopped.
    if (moving && !wasMoving && config.stopOnStationary) {
      synchronized(fusionLock) { fusion?.reset() }
      // Don't let the as-the-crow-flies jump across the GPS-off stop inflate the
      // trip distance (P2-8): the first post-resume fix starts a fresh segment.
      tripManager?.markGap()
    }
    emit("onMotionChange", mapOf("isMoving" to moving, "location" to lastLocation))
  }

  /** Called by the geofence receiver on an ENTER / EXIT / DWELL transition. */
  fun notifyGeofence(
    identifier: String,
    action: String,
    latitude: Double?,
    longitude: Double?,
    accuracy: Double?,
    time: Long?,
  ) {
    val acc = accuracy ?: 0.0
    val confidence = LocationMapper.confidence(acc, filtered = false)
    val location =
      if (latitude != null && longitude != null) {
        mapOf(
          "uuid" to UUID.randomUUID().toString(),
          "timestamp" to (time ?: System.currentTimeMillis()),
          "coords" to
            mapOf(
              "latitude" to latitude,
              "longitude" to longitude,
              "accuracy" to acc,
            ),
          "provider" to "geofence",
          "confidence" to confidence,
        )
      } else {
        lastLocation
      }
    emit(
      "onGeofence",
      mapOf(
        "identifier" to identifier,
        "action" to action,
        "location" to location,
        "confidence" to confidence,
      ),
    )
  }

  // ---- geofences ----

  private fun geofences(): GeofenceManager? {
    val ctx = appContext ?: return null
    return geofenceManager ?: GeofenceManager(ctx).also { geofenceManager = it }
  }

  /**
   * Feed a fix to the geofence reconciler. On the restore path (boot/process
   * restart) `geofenceManager` is null because nothing on the tracking path
   * built it; create it lazily, but only if geofences were actually persisted —
   * so apps that don't use geofencing pay nothing.
   */
  private fun feedGeofences(
    lat: Double,
    lng: Double,
  ) {
    geofenceManager?.let {
      it.onLocation(lat, lng)
      return
    }
    val ctx = appContext ?: return
    GeofenceManager.ensureRestored(ctx)
    if (GeofenceManager.hasAny()) geofences()?.onLocation(lat, lng)
  }

  fun addGeofence(map: Map<String, Any?>) {
    geofences()?.add(GeofenceManager.specFromMap(map))
  }

  fun addGeofences(list: List<Map<String, Any?>>) {
    geofences()?.addAll(list.map { GeofenceManager.specFromMap(it) })
  }

  fun removeGeofence(identifier: String) {
    geofences()?.remove(identifier)
  }

  fun removeGeofences() {
    geofences()?.removeAll()
  }

  fun getGeofences(): List<Map<String, Any?>> = geofences()?.getAll()?.map { it.toMap() } ?: emptyList()

  /** One-shot location fix for `getCurrentPosition`. */
  fun getCurrentPosition(onResult: (Map<String, Any?>?) -> Unit) {
    val ctx = appContext
    if (ctx == null) {
      onResult(null)
      return
    }
    LocationEngine(ctx).getCurrentLocation(config) { location ->
      if (location != null) {
        val map = LocationMapper.toMap(location, isMoving)
        lastLocation = map
        onResult(map)
      } else {
        onResult(null)
      }
    }
  }

  /**
   * Runs a fix through the fusion engine under [fusionLock] so it can't race a
   * rebuild/destroy on another thread. Returns null when no native engine is
   * available (caller then applies the Kotlin accuracy gate).
   */
  private fun fuse(
    lat: Double,
    lng: Double,
    accuracy: Double,
    timeMs: Long,
    hasVelocity: Boolean = false,
    speedMps: Double = 0.0,
    bearingDeg: Double = 0.0,
    speedAccuracyMps: Double = -1.0,
  ): KalmanBridge.Result? =
    synchronized(fusionLock) {
      ensureFusion()?.process(lat, lng, accuracy, timeMs, hasVelocity, speedMps, bearingDeg, speedAccuracyMps)
    }

  /**
   * Must be called while holding [fusionLock]. Builds the engine from the
   * *current* [config], not a fix's captured snapshot — otherwise an in-flight
   * fix that captured an older config could recreate the engine with stale
   * params right after a rebuild, and later fixes would reuse that stale engine.
   */
  private fun ensureFusion(): KalmanBridge? {
    fusion?.let { return it }
    if (!KalmanBridge.isAvailable()) return null
    val cfg = config
    return try {
      KalmanBridge(cfg.enableKalman, cfg.enableCvKalman, cfg.accuracyFilter, MAX_SPEED_MPS, PROCESS_NOISE)
        .also {
          // Start from the current activity's tuning, not the defaults — a
          // rebuild mid-drive shouldn't fall back to fighting the vehicle.
          val (q, maxSpeed) = lastMotionProfile
          it.setMotionProfile(q, maxSpeed)
          it.setMinAccuracy(cfg.minKalmanAccuracy)
          fusion = it
        }
    } catch (t: Throwable) {
      null
    }
  }

  private fun rebuildFusion() =
    synchronized(fusionLock) {
      fusion?.destroy()
      fusion = null
    }

  /**
   * (Re)build the headless onLocation coalescer (P1-7) from the current config.
   * Null when headless is off or both coalesce knobs are 0 — then each fix is
   * delivered to the headless task immediately (backward-compatible default).
   * Also rebuilds the fixed-lag smoother (same config-change lifecycle).
   */
  private fun rebuildCoalescer() {
    val cfg = config
    coalescer =
      if (cfg.enableHeadless && (cfg.headlessCoalesceWindow > 0L || cfg.headlessCoalesceCount > 0)) {
        HeadlessCoalescer(cfg.headlessCoalesceWindow * 1000L, cfg.headlessCoalesceCount)
      } else {
        null
      }
    smoother = if (cfg.smoothingLag > 0) TrackSmoother(cfg.smoothingLag) else null
  }

  // ---- persistence + sync ----

  private fun store(): LocationStore? {
    val ctx = appContext ?: return null
    return store ?: LocationStore.getInstance(ctx).also { store = it }
  }

  /**
   * Replace the runtime auth headers (e.g. a refreshed bearer token) and persist
   * them. The JS layer calls this via its `getAuthHeaders` provider; native sync
   * always uploads with these layered over the static `config.headers`.
   */
  fun setAuthHeaders(headers: Map<String, String>) {
    authHeaders = headers
    authHeadersLoaded = true
    val ctx = appContext ?: return
    ioExecutor.execute { runCatching { ConfigStore(ctx).saveAuthHeaders(headers) } }
  }

  /** Static config headers with the persisted auth headers layered on top. */
  fun effectiveHeaders(cfg: GeoPulseConfig): Map<String, String> {
    if (!authHeadersLoaded) loadAuthHeaders()
    val auth = authHeaders
    return if (auth.isEmpty()) cfg.headers else cfg.headers + auth
  }

  private fun loadAuthHeaders() {
    val ctx = appContext ?: return
    authHeaders = runCatching { ConfigStore(ctx).loadAuthHeaders() }.getOrDefault(emptyMap())
    authHeadersLoaded = true
  }

  private fun persist(
    map: Map<String, Any?>,
    cfg: GeoPulseConfig,
  ) {
    val locationStore = store() ?: return
    val uuid = map["uuid"]?.toString() ?: ""
    val timestamp = (map["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis()
    val json = Json.toJson(map)
    ioExecutor.execute {
      runCatching {
        val dropOldest = !cfg.bufferOverflowPolicy.equals("dropNewest", ignoreCase = true)
        val dropped = locationStore.insert(uuid, timestamp, json, cfg.maxRecordsToPersist, dropOldest)
        if (dropped > 0) reportBufferOverflow(dropped, cfg)
        if (cfg.autoSync) {
          val threshold = cfg.autoSyncThreshold
          if (threshold <= 0 || locationStore.count() >= threshold) enqueueSync()
        }
      }
    }
  }

  // Accumulates dropped-point counts so a steadily-full buffer emits at most one
  // BUFFER_OVERFLOW per ~TRIM_EVERY drops instead of one per fix. Only touched on
  // the single-threaded ioExecutor, so it needs no extra synchronization.
  private var droppedSinceEmit = 0

  private fun reportBufferOverflow(
    dropped: Int,
    cfg: GeoPulseConfig,
  ) {
    droppedSinceEmit += dropped
    if (droppedSinceEmit < BUFFER_OVERFLOW_EMIT_THRESHOLD) return
    val total = droppedSinceEmit
    droppedSinceEmit = 0
    emit(
      "onError",
      mapOf(
        "code" to "BUFFER_OVERFLOW",
        "message" to
          "Location buffer full (max ${cfg.maxRecordsToPersist}); dropped $total point(s) per ${cfg.bufferOverflowPolicy} policy.",
        "dropped" to total,
        "policy" to cfg.bufferOverflowPolicy,
      ),
    )
  }

  private fun enqueueSync() {
    val ctx = appContext ?: return
    val builder =
      OneTimeWorkRequestBuilder<SyncWorker>()
        .setConstraints(SyncWorker.syncConstraints(config))
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
    // Expedited so Doze (Android 12+) runs the upload promptly instead of
    // deferring it for minutes-to-hours.
    SyncWorker.applyExpedited(builder, config)
    runCatching {
      WorkManager
        .getInstance(ctx)
        .enqueueUniqueWork(SyncWorker.UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, builder.build())
    }
  }

  /**
   * Keep the 15-min safety-net drain in step with the config: scheduled while a
   * sync `url` exists (sweeps backlogs that have no pending one-shot work —
   * autoSync off, an enqueue lost to a crash, points recorded offline before the
   * process died), cancelled when sync is unconfigured so it doesn't tick idle.
   */
  private fun reconcilePeriodicDrain() {
    val ctx = appContext ?: return
    val cfg = config
    if (cfg.url != null) {
      SyncWorker.ensurePeriodicDrain(ctx, cfg)
    } else {
      SyncWorker.cancelPeriodicDrain(ctx)
    }
  }

  fun getLocations(
    limit: Int,
    onResult: (List<Map<String, Any?>>) -> Unit,
  ) {
    val locationStore = store()
    if (locationStore == null) {
      onResult(emptyList())
      return
    }
    // Cap the default so a large buffer never floods the JS bridge in one call.
    val effectiveLimit = if (limit > 0) limit else DEFAULT_GET_LIMIT
    ioExecutor.execute {
      // getLatest: most recent N, in chronological order (callers want the
      // latest track, not the oldest backlog).
      val list =
        runCatching { locationStore.getLatest(effectiveLimit).map { Json.toMap(it.json) } }
          .getOrDefault(emptyList())
      onResult(list)
    }
  }

  fun getCount(onResult: (Int) -> Unit) {
    val locationStore = store()
    if (locationStore == null) {
      onResult(0)
      return
    }
    ioExecutor.execute { onResult(runCatching { locationStore.count() }.getOrDefault(0)) }
  }

  fun destroyLocations(onResult: () -> Unit) {
    val locationStore = store()
    if (locationStore == null) {
      onResult()
      return
    }
    ioExecutor.execute {
      runCatching { locationStore.deleteAll() }
      onResult()
    }
  }

  /**
   * Manual, immediate sync of one batch. Resolves with a result map — `count`
   * (points uploaded and removed from the buffer), plus `discarded`/`status`
   * when the batch was permanently rejected, plus `locations` only when
   * [returnLocations] (marshalling up to 10k points across the JS bridge by
   * default was an unbounded payload most callers threw away). Null on
   * transient failure, so `sync()` rejects.
   */
  fun syncNow(
    returnLocations: Boolean,
    onResult: (Map<String, Any?>?) -> Unit,
  ) {
    val cfg = config
    val locationStore = store()
    val url = cfg.url
    if (locationStore == null || url == null) {
      onResult(mapOf("count" to 0))
      return
    }
    ioExecutor.execute {
      val limit =
        if (cfg.batchSync) {
          // Whole backlog in one request; getAll() treats <= 0 as "no limit", so
          // pass maxRecordsToPersist through (0 = unlimited persistence -> all rows).
          cfg.maxRecordsToPersist
        } else {
          if (cfg.maxBatchSize > 0) cfg.maxBatchSize else 250
        }
      // Claim and settle are serialized with the WorkManager path via syncLock;
      // the upload itself runs unlocked (a slow server shouldn't block the other
      // path — the server's dedup-by-uuid absorbs a rare overlapped batch).
      val batch =
        synchronized(LocationStore.syncLock) {
          runCatching { locationStore.getAll(limit) }.getOrDefault(emptyList())
        }
      if (batch.isEmpty()) {
        onResult(mapOf("count" to 0))
        return@execute
      }
      val outcome =
        runCatching {
          SyncEngine.upload(
            url,
            cfg.httpMethod,
            effectiveHeaders(cfg),
            cfg.params,
            batch,
            cfg.discardStatusCodes.toSet(),
            cfg.retryStatusCodes.toSet(),
          )
        }.getOrElse { SyncEngine.Outcome.Retry(0, null) }
      synchronized(LocationStore.syncLock) {
        runCatching { SyncEngine.settle(locationStore, batch, outcome) }
      }
      when (outcome) {
        is SyncEngine.Outcome.Success -> {
          val result = mutableMapOf<String, Any?>("count" to batch.size)
          if (returnLocations) result["locations"] = batch.map { Json.toMap(it.json) }
          onResult(result)
        }
        // The batch was permanently rejected and dropped; nothing was uploaded.
        is SyncEngine.Outcome.Discarded ->
          onResult(mapOf("count" to 0, "discarded" to true, "status" to outcome.status))
        // Transient failure: batch stays buffered; report failure so sync() rejects.
        is SyncEngine.Outcome.Retry -> onResult(null)
      }
    }
  }

  // ---- event emission ----

  /** Forward an event to JS if a runtime is currently attached. */
  fun emit(
    event: String,
    payload: Map<String, Any?>,
  ) {
    val d = dispatcher
    if (d != null) {
      // Skip the bridge serialization when JS has no listener for this event —
      // it would be dropped on the JS side anyway. (A listener registered right
      // after this check misses the event, exactly as it would have today.)
      if (event in observedEvents) d.dispatch(event, payload)
      return
    }
    // No JS runtime attached (app killed/swiped away while the foreground service
    // keeps tracking). If headless is enabled, run the registered JS task in a
    // short-lived RN context instead of dropping the event.
    if (!config.enableHeadless) return
    val ctx = appContext ?: return
    val c = coalescer
    if (c != null && event == "onLocation") {
      // P1-7: batch onLocation fixes so a low distanceFilter doesn't spawn one
      // ephemeral JS context per fix. The SQLite sync pipeline still stores each
      // fix individually (unaffected) — coalescing is only for JS delivery.
      val decision = c.add(payload, SystemClock.elapsedRealtime())
      decision.flushNow?.let { dispatchHeadless(ctx, "onLocation", Json.toJson(it)) }
      if (decision.armTimer) {
        scheduler.schedule(
          Runnable {
            val due = c.flushIfDue(SystemClock.elapsedRealtime())
            if (due != null) dispatchHeadless(ctx, "onLocation", Json.toJson(due))
          },
          config.headlessCoalesceWindow,
          TimeUnit.SECONDS,
        )
      }
      return
    }
    // A non-coalesced event: flush any pending coalesced fixes first so ordering is
    // preserved, then deliver this event immediately.
    c?.drainNow()?.let { dispatchHeadless(ctx, "onLocation", Json.toJson(it)) }
    dispatchHeadless(ctx, event, Json.toJson(payload))
  }

  private fun dispatchHeadless(
    ctx: Context,
    event: String,
    payloadJson: String,
  ) {
    runCatching { GeoPulseHeadlessService.dispatch(ctx, event, payloadJson) }
  }

  // ---- state ----

  fun lastLocationMap(): Map<String, Any?>? = lastLocation

  fun stateMap(): Map<String, Any?> =
    mapOf(
      "enabled" to enabled,
      "isMoving" to isMoving,
      "trackingMode" to "location",
      "odometer" to odometer,
      "config" to config.toMap(),
    )

  // ---- debug ----

  /**
   * Emit a synthetic location through the exact dispatch path the real location
   * engine uses. Lets us validate native -> JS wiring without GPS.
   */
  fun emitTestLocation() {
    val now = System.currentTimeMillis()
    val location =
      mapOf(
        "uuid" to UUID.randomUUID().toString(),
        "timestamp" to now,
        "coords" to
          mapOf(
            "latitude" to 37.33233141,
            "longitude" to -122.0312186,
            "accuracy" to 5.0,
            "altitude" to 0.0,
            "heading" to 0.0,
            "speed" to 0.0,
          ),
        "isMoving" to isMoving,
        "filtered" to false,
        "provider" to "test",
      )
    lastLocation = location
    emit("onLocation", location)
  }

  /**
   * Inject a synthetic fix through the **full** processing pipeline
   * (Kalman fusion, trip/visit detection, geofences, persistence) exactly as if
   * it came from the GPS. A legitimate testing utility: lets developers exercise
   * geofences and trip/visit logic from a desk, without walking a route.
   *
   * `timestamp` is epoch ms; pass increasing values to simulate motion over time.
   */
  fun simulateLocation(
    latitude: Double,
    longitude: Double,
    accuracy: Double,
    speed: Double,
    timestamp: Long,
  ) {
    val loc =
      Location("simulated").apply {
        this.latitude = latitude
        this.longitude = longitude
        this.accuracy = accuracy.toFloat()
        this.speed = speed.toFloat()
        this.time = if (timestamp > 0) timestamp else System.currentTimeMillis()
        this.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
      }
    onLocationUpdate(loc)
  }

  /**
   * Debug/testing (P2-9): force the GMS-free LocationManager fallback even where
   * Play Services is available, then relaunch the running tracker so it
   * re-registers on the framework provider (subsequent fixes carry the raw
   * provider name). Emits onProviderChange to signal the switch.
   */
  fun simulateProviderFailure(provider: String) {
    if (!provider.equals("gms", ignoreCase = true)) return
    LocationEngine.forceRawProvider = true
    if (enabled) launchService()
    emit(
      "onProviderChange",
      mapOf("enabled" to enabled, "gps" to true, "network" to true, "status" to 2),
    )
  }

  /**
   * Debug/testing (P2-9): simulate a signal outage of [durationMs]. Fires the same
   * onProviderChange(outage=true) the watchdog would after `outageThreshold`, then
   * onProviderChange(outage=false) once the simulated outage ends.
   */
  fun simulateOutage(durationMs: Long) {
    val threshold =
      if (config.outageThreshold > 0) {
        config.outageThreshold
      } else {
        (config.locationUpdateInterval * 3).coerceAtLeast(30_000)
      }
    scheduler.schedule(
      Runnable { notifyOutage(active = true, durationMs = threshold) },
      threshold,
      TimeUnit.MILLISECONDS,
    )
    scheduler.schedule(
      Runnable { notifyOutage(active = false, durationMs = durationMs) },
      threshold + durationMs,
      TimeUnit.MILLISECONDS,
    )
  }
}
