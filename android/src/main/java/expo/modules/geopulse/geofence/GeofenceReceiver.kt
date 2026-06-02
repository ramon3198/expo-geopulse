package expo.modules.geopulse.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import expo.modules.geopulse.core.GeoPulseController

/** Receives geofence transition broadcasts and forwards them to [GeoPulseController]. */
class GeofenceReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val event = GeofencingEvent.fromIntent(intent) ?: return
    if (event.hasError()) return

    val action = when (event.geofenceTransition) {
      Geofence.GEOFENCE_TRANSITION_ENTER -> "ENTER"
      Geofence.GEOFENCE_TRANSITION_EXIT -> "EXIT"
      Geofence.GEOFENCE_TRANSITION_DWELL -> "DWELL"
      else -> return
    }
    val triggering = event.triggeringGeofences ?: return
    val location = event.triggeringLocation

    // The OS keeps firing geofences across process death; restore the persisted
    // specs (and the controller's context + config) so a fresh process can still
    // refine the transition AND dispatch a headless event for it.
    GeofenceManager.ensureRestored(context)
    GeoPulseController.ensureInitialized(context)

    for (geofence in triggering) {
      val spec = GeofenceManager.specFor(geofence.requestId) ?: continue
      // Polygon refinement: a polygon is registered as its bounding circle, so
      // for ENTER/DWELL only fire when the point is truly inside the polygon.
      // EXIT is left as-is: leaving the bounding circle is necessarily outside
      // the polygon, so the point-in-polygon test would wrongly drop every EXIT.
      if (spec.isPolygon && action != "EXIT" && location != null && spec.vertices != null) {
        if (!GeofenceManager.pointInPolygon(location.latitude, location.longitude, spec.vertices)) {
          continue
        }
      }
      GeoPulseController.notifyGeofence(
        identifier = geofence.requestId,
        action = action,
        latitude = location?.latitude,
        longitude = location?.longitude,
        accuracy = location?.accuracy?.toDouble(),
        time = location?.time,
      )
    }
  }
}
