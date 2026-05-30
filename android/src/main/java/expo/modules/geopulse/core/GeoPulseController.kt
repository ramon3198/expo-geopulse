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
import expo.modules.geopulse.location.LocationEngine
import expo.modules.geopulse.service.LocationService
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

  private var lastLocation: Map<String, Any?>? = null
  private var lastAndroidLocation: Location? = null
  private var fusion: KalmanBridge? = null

  private val ioExecutor = Executors.newSingleThreadExecutor()
  private var store: LocationStore? = null
  private var geofenceManager: GeofenceManager? = null

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
    config = cfg
    rebuildFusion()
    persistConfig(cfg)
  }

  fun setConfig(cfg: GeoPulseConfig) {
    config = cfg
    rebuildFusion()
    persistConfig(cfg)
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

  fun start() {
    enabled = true
    val ctx = appContext ?: return
    val intent = Intent(ctx, LocationService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      ctx.startForegroundService(intent)
    } else {
      ctx.startService(intent)
    }
  }

  fun stop() {
    enabled = false
    fusion?.reset()
    val ctx = appContext ?: return
    ctx.stopService(Intent(ctx, LocationService::class.java))
  }

  // ---- location pipeline ----

  /** Called by the foreground service for every fix it receives. */
  fun onLocationUpdate(location: Location) {
    val cfg = config
    var lat = location.latitude
    var lng = location.longitude
    var accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else 30.0
    var filtered = false
    var provider = location.provider ?: "fused"

    val engine = ensureFusion(cfg)
    if (engine != null) {
      val r = engine.process(lat, lng, accuracy, location.time)
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
    )
    lastLocation = map
    emit("onLocation", map)

    if (cfg.url != null) {
      persist(map, cfg)
    }
    geofenceManager?.onLocation(lat, lng)
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
    val location = if (latitude != null && longitude != null) {
      mapOf(
        "uuid" to UUID.randomUUID().toString(),
        "timestamp" to (time ?: System.currentTimeMillis()),
        "coords" to mapOf(
          "latitude" to latitude,
          "longitude" to longitude,
          "accuracy" to (accuracy ?: 0.0),
        ),
        "provider" to "geofence",
      )
    } else {
      lastLocation
    }
    emit("onGeofence", mapOf("identifier" to identifier, "action" to action, "location" to location))
  }

  // ---- geofences ----

  private fun geofences(): GeofenceManager? {
    val ctx = appContext ?: return null
    return geofenceManager ?: GeofenceManager(ctx).also { geofenceManager = it }
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

  private fun ensureFusion(cfg: GeoPulseConfig): KalmanBridge? {
    fusion?.let { return it }
    if (!KalmanBridge.isAvailable()) return null
    return try {
      KalmanBridge(cfg.enableKalman, cfg.accuracyFilter, MAX_SPEED_MPS, PROCESS_NOISE)
        .also { fusion = it }
    } catch (t: Throwable) {
      null
    }
  }

  private fun rebuildFusion() {
    fusion?.destroy()
    fusion = null
  }

  // ---- persistence + sync ----

  private fun store(): LocationStore? {
    val ctx = appContext ?: return null
    return store ?: LocationStore(ctx).also { store = it }
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

  fun getLocations(onResult: (List<Map<String, Any?>>) -> Unit) {
    val locationStore = store()
    if (locationStore == null) {
      onResult(emptyList())
      return
    }
    ioExecutor.execute {
      val list = runCatching { locationStore.getAll(0).map { Json.toMap(it.json) } }
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
      val batch = runCatching {
        locationStore.getAll(if (cfg.maxBatchSize > 0) cfg.maxBatchSize else 250)
      }.getOrDefault(emptyList())
      if (batch.isEmpty()) {
        onResult(emptyList())
        return@execute
      }
      val body = "[" + batch.joinToString(",") { it.json } + "]"
      val result = HttpUploader.upload(url, cfg.httpMethod, cfg.headers, body)
      if (result.success) {
        runCatching { locationStore.deleteByIds(batch.map { it.id }) }
        onResult(batch.map { Json.toMap(it.json) })
      } else {
        onResult(null)
      }
    }
  }

  // ---- event emission ----

  /** Forward an event to JS if a runtime is currently attached. */
  fun emit(event: String, payload: Map<String, Any?>) {
    dispatcher?.dispatch(event, payload)
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
}
