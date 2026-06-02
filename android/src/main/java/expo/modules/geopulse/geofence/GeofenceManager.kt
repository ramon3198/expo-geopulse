package expo.modules.geopulse.geofence

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import expo.modules.geopulse.core.GeoPulseController
import expo.modules.geopulse.util.GeoMath
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Geofencing with two advantages over typical wrappers:
 *
 *  1. "Infinite" geofences — the OS caps an app at 100 active geofences, so we
 *     keep a full in-memory registry and only register the [MAX_ACTIVE] nearest
 *     to the device, re-reconciling as it moves.
 *  2. Polygon geofences (free) — a polygon is registered as its bounding circle,
 *     then refined with a precise point-in-polygon test when it triggers.
 */
class GeofenceManager(private val context: Context) {

  private val client: GeofencingClient by lazy {
    LocationServices.getGeofencingClient(context)
  }
  private var pendingIntent: PendingIntent? = null
  private var lastRegLat: Double? = null
  private var lastRegLng: Double? = null

  init {
    // Restore any persisted registry so reconcile (driven by movement) is
    // consistent after a process restart, not only when a transition fires.
    ensureRestored(context)
  }

  fun add(spec: GeofenceSpec) {
    synchronized(registry) { registry[spec.identifier] = spec }
    persistRegistry()
    reconcile(force = true)
  }

  fun addAll(specs: List<GeofenceSpec>) {
    synchronized(registry) { specs.forEach { registry[it.identifier] = it } }
    persistRegistry()
    reconcile(force = true)
  }

  fun remove(identifier: String) {
    synchronized(registry) { registry.remove(identifier) }
    runCatching { client.removeGeofences(listOf(identifier)) }
    persistRegistry()
    reconcile(force = true)
  }

  fun removeAll() {
    val ids = synchronized(registry) {
      val keys = registry.keys.toList()
      registry.clear()
      registeredIds.clear()
      keys
    }
    if (ids.isNotEmpty()) runCatching { client.removeGeofences(ids) }
    lastRegLat = null
    lastRegLng = null
    val store = GeofenceStore(context)
    store.saveRegistry(emptyList())
    store.saveRegistered(emptyList())
  }

  fun getAll(): List<GeofenceSpec> = synchronized(registry) { registry.values.toList() }

  private fun persistRegistry() {
    GeofenceStore(context).saveRegistry(getAll().map { it.toMap() })
  }

  /** Called as the device moves; re-registers the nearest geofences when needed. */
  fun onLocation(latitude: Double, longitude: Double) {
    currentLat = latitude
    currentLng = longitude
    val moved = lastRegLat == null ||
      haversineMeters(lastRegLat!!, lastRegLng!!, latitude, longitude) > RECONCILE_DISTANCE_M
    if (moved) reconcile(force = false)
  }

  // All reconciliation runs on one thread so concurrent triggers (location
  // worker vs. JS add/remove) can't interleave their Play Services calls or
  // corrupt registeredIds.
  private val reconcileExecutor = Executors.newSingleThreadExecutor()

  private fun reconcile(force: Boolean) {
    runCatching { reconcileExecutor.execute { reconcileNow() } }
  }

  @SuppressLint("MissingPermission")
  private fun reconcileNow() {
    val all = getAll()
    if (all.isEmpty()) {
      removeAll()
      return
    }
    val lat = currentLat
    val lng = currentLng

    val selected = if (lat != null && lng != null) {
      all.sortedBy { haversineMeters(lat, lng, it.centerLat, it.centerLng) }.take(MAX_ACTIVE)
    } else {
      all.take(MAX_ACTIVE)
    }
    val geofences = selected.map { it.toGeofence() }
    if (geofences.isEmpty()) return
    val selectedIds = selected.map { it.identifier }.toSet()
    val stale = synchronized(registry) { registeredIds - selectedIds }

    val request = GeofencingRequest.Builder()
      .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
      .addGeofences(geofences)
      .build()

    // Await each step in order: remove stale BEFORE adding, so the OS-registered
    // count can't transiently exceed the 100 cap, and record state only once the
    // add is confirmed. Running on the single reconcile thread, Tasks.await is
    // safe (never the main thread) and makes the whole sequence atomic per run.
    runCatching {
      if (stale.isNotEmpty()) Tasks.await(client.removeGeofences(stale.toList()))
      Tasks.await(client.addGeofences(request, geofencePendingIntent()))
      synchronized(registry) {
        registeredIds.clear()
        registeredIds.addAll(selectedIds)
      }
      GeofenceStore(context).saveRegistered(selectedIds)
      lastRegLat = lat
      lastRegLng = lng
    }.onFailure { e ->
      GeoPulseController.emit(
        "onError",
        mapOf(
          "code" to "GEOFENCE_ERROR",
          "message" to (e.message ?: "Failed to register geofences"),
        ),
      )
    }
  }

  private fun geofencePendingIntent(): PendingIntent {
    pendingIntent?.let { return it }
    val intent = Intent(context, GeofenceReceiver::class.java)
    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    } else {
      PendingIntent.FLAG_UPDATE_CURRENT
    }
    return PendingIntent.getBroadcast(context, 0, intent, flags).also { pendingIntent = it }
  }

  companion object {
    private const val MAX_ACTIVE = 100
    private const val RECONCILE_DISTANCE_M = 500.0

    private val registry = LinkedHashMap<String, GeofenceSpec>()
    // Ids currently registered with Play Services (guarded by `registry`).
    private val registeredIds = LinkedHashSet<String>()

    @Volatile private var currentLat: Double? = null
    @Volatile private var currentLng: Double? = null
    @Volatile private var restored = false

    fun specFor(identifier: String): GeofenceSpec? =
      synchronized(registry) { registry[identifier] }

    /** Whether any geofence is registered (in memory). Cheap; no I/O. */
    fun hasAny(): Boolean = synchronized(registry) { registry.isNotEmpty() }

    /**
     * Restores the persisted registry + registered-id set into a fresh process
     * (e.g. when [GeofenceReceiver] is invoked after the app was killed but the
     * OS-held geofences keep firing). No-op once loaded or if already populated.
     */
    fun ensureRestored(context: Context) {
      if (restored) return
      synchronized(registry) {
        if (restored) return
        if (registry.isEmpty()) {
          val store = GeofenceStore(context)
          for (m in store.loadRegistry()) {
            val spec = specFromMap(m)
            if (spec.identifier.isNotEmpty()) registry[spec.identifier] = spec
          }
          registeredIds.clear()
          registeredIds.addAll(store.loadRegistered())
        }
        restored = true
      }
    }

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
      GeoMath.haversineMeters(lat1, lon1, lat2, lon2)

    /** Ray-casting point-in-polygon test. Vertices are [lat, lng] pairs. */
    fun pointInPolygon(lat: Double, lng: Double, vertices: List<DoubleArray>): Boolean =
      GeoMath.pointInPolygon(lat, lng, vertices)

    fun specFromMap(map: Map<String, Any?>): GeofenceSpec {
      val vertices = (map["vertices"] as? List<*>)?.mapNotNull { entry ->
        val pair = entry as? List<*> ?: return@mapNotNull null
        val lat = (pair.getOrNull(0) as? Number)?.toDouble() ?: return@mapNotNull null
        val lng = (pair.getOrNull(1) as? Number)?.toDouble() ?: return@mapNotNull null
        doubleArrayOf(lat, lng)
      }?.takeIf { it.isNotEmpty() }
      return GeofenceSpec(
        identifier = map["identifier"]?.toString() ?: "",
        latitude = (map["latitude"] as? Number)?.toDouble() ?: 0.0,
        longitude = (map["longitude"] as? Number)?.toDouble() ?: 0.0,
        radius = (map["radius"] as? Number)?.toDouble() ?: 100.0,
        notifyOnEntry = map["notifyOnEntry"] as? Boolean ?: true,
        notifyOnExit = map["notifyOnExit"] as? Boolean ?: true,
        notifyOnDwell = map["notifyOnDwell"] as? Boolean ?: false,
        loiteringDelay = (map["loiteringDelay"] as? Number)?.toInt() ?: 0,
        vertices = vertices,
      )
    }
  }
}

/** A geofence definition; circular unless [vertices] is set (then it's a polygon). */
data class GeofenceSpec(
  val identifier: String,
  val latitude: Double,
  val longitude: Double,
  val radius: Double,
  val notifyOnEntry: Boolean,
  val notifyOnExit: Boolean,
  val notifyOnDwell: Boolean,
  val loiteringDelay: Int,
  val vertices: List<DoubleArray>?,
) {
  val isPolygon: Boolean get() = !vertices.isNullOrEmpty()

  // For polygons, the registered circle is the bounding circle of the vertices.
  val centerLat: Double get() = if (isPolygon) vertices!!.map { it[0] }.average() else latitude
  val centerLng: Double get() = if (isPolygon) vertices!!.map { it[1] }.average() else longitude
  val effectiveRadius: Double
    get() = if (isPolygon) {
      val cLat = centerLat
      val cLng = centerLng
      var r = 0.0
      for (v in vertices!!) {
        r = max(r, GeofenceManager.haversineMeters(cLat, cLng, v[0], v[1]))
      }
      r + 1.0
    } else {
      radius
    }

  fun toMap(): Map<String, Any?> = mapOf(
    "identifier" to identifier,
    "latitude" to latitude,
    "longitude" to longitude,
    "radius" to radius,
    "notifyOnEntry" to notifyOnEntry,
    "notifyOnExit" to notifyOnExit,
    "notifyOnDwell" to notifyOnDwell,
    "loiteringDelay" to loiteringDelay,
    "vertices" to vertices?.map { listOf(it[0], it[1]) },
  )

  fun toGeofence(): Geofence {
    var transitionTypes = 0
    if (notifyOnEntry) transitionTypes = transitionTypes or Geofence.GEOFENCE_TRANSITION_ENTER
    if (notifyOnExit) transitionTypes = transitionTypes or Geofence.GEOFENCE_TRANSITION_EXIT
    if (notifyOnDwell) transitionTypes = transitionTypes or Geofence.GEOFENCE_TRANSITION_DWELL
    if (transitionTypes == 0) {
      transitionTypes = Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT
    }
    val builder = Geofence.Builder()
      .setRequestId(identifier)
      .setCircularRegion(centerLat, centerLng, effectiveRadius.toFloat())
      .setExpirationDuration(Geofence.NEVER_EXPIRE)
      .setTransitionTypes(transitionTypes)
    if (notifyOnDwell) builder.setLoiteringDelay(if (loiteringDelay > 0) loiteringDelay else 30000)
    return builder.build()
  }
}
