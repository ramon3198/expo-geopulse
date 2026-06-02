package expo.modules.geopulse.core

import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import expo.modules.geopulse.db.LocationStore
import expo.modules.geopulse.fusion.KalmanBridge
import expo.modules.geopulse.geofence.GeofenceManager
import expo.modules.geopulse.headless.GeoPulseHeadlessService
import expo.modules.geopulse.location.LocationEngine
import expo.modules.geopulse.service.LocationService
import expo.modules.geopulse.trip.TripVisitManager
import expo.modules.geopulse.sync.HttpUploader
import expo.modules.geopulse.sync.SyncWorker
import expo.modules.geopulse.util.Json
import java.util.UUID
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
  // Battery must climb this far above the low-battery threshold (fraction, 0..1)
  // before auto-degrade is undone, so it doesn't flap right at the boundary.
  private const val BATTERY_RECOVERY_HYSTERESIS = 0.1

  // Tracking fields a named preset controls. Hand-tuning any of these (without
  // also passing `preset`) drops back to manual mode so the change sticks.
  private val PRESET_TUNING_KEYS = setOf(
    "desiredAccuracy",
    "distanceFilter",
    "locationUpdateInterval",
    "fastestLocationUpdateInterval",
  )

  private var dispatcher: EventDispatcher? = null
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
  private var store: LocationStore? = null
  private var geofenceManager: GeofenceManager? = null
  private var tripManager: TripVisitManager? = null

  // ---- attachment ----

  fun attach(context: Context, eventDispatcher: EventDispatcher) {
    appContext = context.applicationContext
    dispatcher = eventDispatcher
  }

  fun detach() {
    dispatcher = null
  }

  // ---- configuration / lifecycle ----

  fun ready(cfg: GeoPulseConfig) {
    val applied = synchronized(configLock) {
      val resolved = cfg.resolvePreset()
      config = resolved
      clearBatteryDegradeState()
      resolved
    }
    rebuildFusion()
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
    val applied = synchronized(configLock) {
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
    runCatching { ConfigStore(context).loadConfig() }.getOrNull()?.let {
      config = GeoPulseConfig.fromMap(it)
      rebuildFusion()
    }
  }

  fun start() {
    enabled = true
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
    if (!launchService()) enabled = false
  }

  /** Forget any battery auto-degrade state (an explicit config is authoritative). */
  private fun clearBatteryDegradeState() {
    degradedForBattery = false
    preDegradeConfig = null
  }

  /**
   * (Re)starts the foreground service. The service's `onStartCommand` re-applies
   * the current config — re-issuing the GPS request and reconciling driving
   * detection — so this doubles as "apply config to the running tracker".
   */
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
          "message" to (e.message
            ?: "Could not start the tracking service (it may be blocked from the background)."),
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
    var accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else 30.0
    var filtered = false
    var provider = location.provider ?: "fused"

    val r = fuse(lat, lng, accuracy, location.time)
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

    val map = LocationMapper.toMap(
      location,
      isMoving,
      provider = provider,
      filtered = filtered,
      overrideLat = lat,
      overrideLng = lng,
      overrideAccuracy = accuracy,
      context = appContext,
    )
    lastLocation = map
    emit("onLocation", map)

    if (cfg.url != null) {
      persist(map, cfg)
    }
    feedGeofences(lat, lng)

    if (cfg.enableTripDetection) {
      ensureTripManager(cfg).onLocation(lat, lng, location.time)
    }

    if (cfg.enableDrivingEvents && location.hasSpeed()) {
      drivingSpeedSink?.invoke(location.speed.toDouble())
    }
  }

  /** Set by LocationService so the controller can feed GPS speed to the sensor-based detector. */
  @Volatile
  var drivingSpeedSink: ((Double) -> Unit)? = null

  /** Called by the driving-events detector for each detected manoeuvre. */
  fun notifyDrivingEvent(type: String, severity: String, magnitude: Double, speedMps: Double) {
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
    manager.listener = object : TripVisitManager.Listener {
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
    val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager ?: return
    val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    if (level < 0) return
    val charging = bm.isCharging

    if (degradedForBattery) {
      // Recovery: restore the pre-degrade config once charging, or once the level
      // climbs back above the threshold plus hysteresis (avoids flapping at the
      // boundary). Without this, eco mode would stick for the whole session.
      val recovered = synchronized(configLock) {
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

    val degraded = synchronized(configLock) {
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
  fun notifyOutage(active: Boolean, durationMs: Long) {
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
  fun notifyActivity(type: String, confidence: Int) {
    emit("onActivityChange", mapOf("activity" to type, "confidence" to confidence))
  }

  /** Called by the motion manager when moving<->stationary state flips. */
  fun notifyMotionChange(moving: Boolean) {
    isMoving = moving
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
    val location = if (latitude != null && longitude != null) {
      mapOf(
        "uuid" to UUID.randomUUID().toString(),
        "timestamp" to (time ?: System.currentTimeMillis()),
        "coords" to mapOf(
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
  private fun feedGeofences(lat: Double, lng: Double) {
    geofenceManager?.let { it.onLocation(lat, lng); return }
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

  fun getGeofences(): List<Map<String, Any?>> =
    geofences()?.getAll()?.map { it.toMap() } ?: emptyList()

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
  ): KalmanBridge.Result? = synchronized(fusionLock) {
    ensureFusion()?.process(lat, lng, accuracy, timeMs)
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
      KalmanBridge(cfg.enableKalman, cfg.accuracyFilter, MAX_SPEED_MPS, PROCESS_NOISE)
        .also { fusion = it }
    } catch (t: Throwable) {
      null
    }
  }

  private fun rebuildFusion() = synchronized(fusionLock) {
    fusion?.destroy()
    fusion = null
  }

  // ---- persistence + sync ----

  private fun store(): LocationStore? {
    val ctx = appContext ?: return null
    return store ?: LocationStore.getInstance(ctx).also { store = it }
  }

  private fun persist(map: Map<String, Any?>, cfg: GeoPulseConfig) {
    val locationStore = store() ?: return
    val uuid = map["uuid"]?.toString() ?: ""
    val timestamp = (map["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis()
    val json = Json.toJson(map)
    ioExecutor.execute {
      runCatching {
        locationStore.insert(uuid, timestamp, json, cfg.maxRecordsToPersist)
        if (cfg.autoSync) {
          val threshold = cfg.autoSyncThreshold
          if (threshold <= 0 || locationStore.count() >= threshold) enqueueSync()
        }
      }
    }
  }

  private fun enqueueSync() {
    val ctx = appContext ?: return
    val constraints = Constraints.Builder()
      .setRequiredNetworkType(NetworkType.CONNECTED)
      .build()
    val request = OneTimeWorkRequestBuilder<SyncWorker>()
      .setConstraints(constraints)
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
      .build()
    runCatching {
      WorkManager.getInstance(ctx)
        .enqueueUniqueWork(SyncWorker.UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }
  }

  fun getLocations(limit: Int, onResult: (List<Map<String, Any?>>) -> Unit) {
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
      val list = runCatching { locationStore.getLatest(effectiveLimit).map { Json.toMap(it.json) } }
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

  /** Manual, immediate sync. Resolves with the uploaded locations, or null on failure. */
  fun syncNow(onResult: (List<Map<String, Any?>>?) -> Unit) {
    val cfg = config
    val locationStore = store()
    val url = cfg.url
    if (locationStore == null || url == null) {
      onResult(emptyList())
      return
    }
    ioExecutor.execute {
      // Serialize with the WorkManager sync path so the same rows can't be
      // claimed and uploaded twice.
      synchronized(LocationStore.syncLock) {
        val limit = if (cfg.batchSync) {
          cfg.maxRecordsToPersist.coerceAtLeast(1)
        } else {
          if (cfg.maxBatchSize > 0) cfg.maxBatchSize else 250
        }
        val batch = runCatching { locationStore.getAll(limit) }.getOrDefault(emptyList())
        if (batch.isEmpty()) {
          onResult(emptyList())
          return@execute
        }
        val body = HttpUploader.buildBody(batch.map { it.json }, cfg.params)
        val result = HttpUploader.upload(url, cfg.httpMethod, cfg.headers, body)
        if (result.success) {
          runCatching { locationStore.deleteByIds(batch.map { it.id }) }
          onResult(batch.map { Json.toMap(it.json) })
        } else {
          onResult(null)
        }
      }
    }
  }

  // ---- event emission ----

  /** Forward an event to JS if a runtime is currently attached. */
  fun emit(event: String, payload: Map<String, Any?>) {
    val d = dispatcher
    if (d != null) {
      d.dispatch(event, payload)
      return
    }
    // No JS runtime attached (app killed/swiped away while the foreground service
    // keeps tracking). If headless is enabled, run the registered JS task in a
    // short-lived RN context instead of dropping the event.
    if (config.enableHeadless) {
      appContext?.let { ctx ->
        runCatching {
          GeoPulseHeadlessService.dispatch(ctx, event, Json.toJson(payload))
        }
      }
    }
  }

  // ---- state ----

  fun lastLocationMap(): Map<String, Any?>? = lastLocation

  fun stateMap(): Map<String, Any?> = mapOf(
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
    val location = mapOf(
      "uuid" to UUID.randomUUID().toString(),
      "timestamp" to now,
      "coords" to mapOf(
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
    val loc = Location("simulated").apply {
      this.latitude = latitude
      this.longitude = longitude
      this.accuracy = accuracy.toFloat()
      this.speed = speed.toFloat()
      this.time = if (timestamp > 0) timestamp else System.currentTimeMillis()
      this.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
    }
    onLocationUpdate(loc)
  }
}
