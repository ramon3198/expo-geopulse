package expo.modules.geopulse.trip

import expo.modules.geopulse.geofence.GeofenceManager.Companion.haversineMeters
import java.util.UUID

/**
 * On-device trip & visit (stay-point) detection.
 *
 * Runs an online state machine over the stream of (already Kalman-filtered)
 * fixes. It distinguishes a brief pass-by from a real **visit** (the device
 * stayed within [visitRadiusMeters] for at least [minVisitDwellMs]) and tracks
 * the **trip** between two visits, accumulating real travelled distance.
 *
 * This is the classic stay-point algorithm (Li et al.): cluster nearby points,
 * confirm by dwell time, emit a visit; everything between visits is a trip. It
 * massively shrinks payloads — apps get "left Home 8:05, arrived Work 8:34,
 * 12.3 km" instead of hundreds of raw points.
 */
class TripVisitManager(
  private var visitRadiusMeters: Double,
  private var minVisitDwellMs: Long,
) {
  interface Listener {
    fun onVisitArrive(visit: Visit)
    fun onVisitDepart(visit: Visit)
    fun onTripStart(trip: Trip)
    fun onTripEnd(trip: Trip)
  }

  data class Visit(
    val uuid: String,
    val latitude: Double,
    val longitude: Double,
    val arrivalTime: Long,
    var departureTime: Long?,
  ) {
    fun toMap(): Map<String, Any?> = mapOf(
      "uuid" to uuid,
      "latitude" to latitude,
      "longitude" to longitude,
      "arrivalTime" to arrivalTime,
      "departureTime" to departureTime,
      "dwellMs" to (departureTime?.minus(arrivalTime)),
    )
  }

  data class Trip(
    val uuid: String,
    val startTime: Long,
    var endTime: Long?,
    val startLat: Double,
    val startLng: Double,
    var endLat: Double,
    var endLng: Double,
    var distanceMeters: Double,
    var pointCount: Int,
  ) {
    fun toMap(): Map<String, Any?> = mapOf(
      "uuid" to uuid,
      "startTime" to startTime,
      "endTime" to endTime,
      "startLatitude" to startLat,
      "startLongitude" to startLng,
      "endLatitude" to endLat,
      "endLongitude" to endLng,
      "distanceMeters" to distanceMeters,
      "pointCount" to pointCount,
      "durationMs" to (endTime?.minus(startTime)),
    )
  }

  var listener: Listener? = null

  // Candidate cluster being evaluated as a potential visit.
  private var clusterLat = 0.0
  private var clusterLng = 0.0
  private var clusterCount = 0
  private var clusterFirstTime = 0L
  // Trip distance/points at the moment the candidate cluster started, so that if
  // it confirms as a visit we can roll back the stationary GPS jitter that
  // accumulated during the dwell window (it isn't real travel).
  private var clusterTripDistance = 0.0
  private var clusterTripPointCount = 0

  private var currentVisit: Visit? = null   // confirmed visit we are currently inside
  private var currentTrip: Trip? = null     // trip in progress (between visits)
  private var lastLat = 0.0
  private var lastLng = 0.0
  private var hasLast = false

  fun setParams(radiusMeters: Double, minDwellMs: Long) {
    visitRadiusMeters = radiusMeters
    minVisitDwellMs = minDwellMs
  }

  fun reset() {
    clusterCount = 0
    currentVisit = null
    currentTrip = null
    hasLast = false
  }

  /** Feed one fix. Coordinates should be post-fusion. */
  fun onLocation(latitude: Double, longitude: Double, timeMs: Long) {
    // Accumulate trip distance from the previous point.
    if (hasLast) {
      currentTrip?.let { trip ->
        trip.distanceMeters += haversineMeters(lastLat, lastLng, latitude, longitude)
        trip.endLat = latitude
        trip.endLng = longitude
        trip.endTime = timeMs
        trip.pointCount += 1
      }
    }
    lastLat = latitude
    lastLng = longitude
    hasLast = true

    val visit = currentVisit
    if (visit != null) {
      // We're parked at a confirmed visit. Did we leave its radius?
      val dist = haversineMeters(visit.latitude, visit.longitude, latitude, longitude)
      if (dist > visitRadiusMeters) {
        // Departure: close the visit, start a trip.
        visit.departureTime = timeMs
        listener?.onVisitDepart(visit)
        currentVisit = null
        startTrip(latitude, longitude, timeMs)
        seedCluster(latitude, longitude, timeMs)
      }
      return
    }

    // Not at a confirmed visit: grow/replace the candidate cluster.
    if (clusterCount == 0) {
      seedCluster(latitude, longitude, timeMs)
      return
    }

    val distToCluster = haversineMeters(clusterLat, clusterLng, latitude, longitude)
    if (distToCluster <= visitRadiusMeters) {
      // Still inside the candidate: update centroid (running average) and check dwell.
      clusterLat = (clusterLat * clusterCount + latitude) / (clusterCount + 1)
      clusterLng = (clusterLng * clusterCount + longitude) / (clusterCount + 1)
      clusterCount += 1
      if (timeMs - clusterFirstTime >= minVisitDwellMs) {
        confirmVisit(timeMs)
      }
    } else {
      // Moved away before dwelling long enough: this is travel, not a visit.
      seedCluster(latitude, longitude, timeMs)
    }
  }

  private fun seedCluster(lat: Double, lng: Double, time: Long) {
    clusterLat = lat
    clusterLng = lng
    clusterCount = 1
    clusterFirstTime = time
    currentTrip?.let {
      clusterTripDistance = it.distanceMeters
      clusterTripPointCount = it.pointCount
    }
  }

  private fun confirmVisit(timeMs: Long) {
    val visit = Visit(
      uuid = UUID.randomUUID().toString(),
      latitude = clusterLat,
      longitude = clusterLng,
      arrivalTime = clusterFirstTime,
      departureTime = null,
    )
    currentVisit = visit
    clusterCount = 0
    // Arriving somewhere ends any in-progress trip. Align the trip's end with the
    // arrival (cluster start), not the confirm time ~minVisitDwell later, and roll
    // back the in-cluster GPS jitter so the trip distance/duration aren't inflated
    // by the time spent parked.
    currentTrip?.let { trip ->
      trip.distanceMeters = clusterTripDistance
      trip.pointCount = clusterTripPointCount
      trip.endLat = clusterLat
      trip.endLng = clusterLng
      trip.endTime = clusterFirstTime
      listener?.onTripEnd(trip)
      currentTrip = null
    }
    listener?.onVisitArrive(visit)
  }

  private fun startTrip(lat: Double, lng: Double, time: Long) {
    val trip = Trip(
      uuid = UUID.randomUUID().toString(),
      startTime = time,
      endTime = null,
      startLat = lat,
      startLng = lng,
      endLat = lat,
      endLng = lng,
      distanceMeters = 0.0,
      pointCount = 1,
    )
    currentTrip = trip
    listener?.onTripStart(trip)
  }

  fun activeTripMap(): Map<String, Any?>? = currentTrip?.toMap()
}
