package expo.modules.geopulse

import expo.modules.geopulse.core.GeoPulseConfig
import expo.modules.geopulse.core.GeoPulseController
import expo.modules.geopulse.core.PermissionsManager
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

/** Thrown by API surface that is declared but not yet implemented in this milestone. */
private class NotImplementedException(feature: String) :
  CodedException("$feature is not implemented yet (coming in a later milestone).")

class ExpoGeopulseModule : Module() {
  private val controller get() = GeoPulseController

  override fun definition() = ModuleDefinition {
    Name("ExpoGeopulse")

    Events(
      "onLocation",
      "onMotionChange",
      "onActivityChange",
      "onGeofence",
      "onProviderChange",
      "onHeartbeat",
      "onError",
      "onVisit",
      "onTrip",
      "onDrivingEvent",
    )

    OnCreate {
      val context = appContext.reactContext
      if (context != null) {
        // Forward core events to JS while this runtime is alive.
        controller.attach(context) { event, payload -> sendEvent(event, payload) }
      }
    }

    OnDestroy {
      controller.detach()
    }

    // ---- lifecycle / tracking ----

    AsyncFunction("ready") { config: GeoPulseConfig, promise: Promise ->
      controller.ready(config)
      promise.resolve(controller.stateMap())
    }

    AsyncFunction("setConfig") { config: GeoPulseConfig, promise: Promise ->
      controller.setConfig(config)
      promise.resolve(controller.stateMap())
    }

    AsyncFunction("start") { promise: Promise ->
      controller.start()
      promise.resolve(controller.stateMap())
    }

    AsyncFunction("stop") { promise: Promise ->
      controller.stop()
      promise.resolve(controller.stateMap())
    }

    AsyncFunction("getState") { promise: Promise ->
      promise.resolve(controller.stateMap())
    }

    AsyncFunction("getCurrentPosition") { _: Map<String, Any?>, promise: Promise ->
      controller.getCurrentPosition { location ->
        if (location != null) {
          promise.resolve(location)
        } else {
          promise.reject(
            CodedException("Unable to obtain a location (check permissions and that location services are enabled)."),
          )
        }
      }
    }

    // ---- permissions ----

    AsyncFunction("requestPermissions") { promise: Promise ->
      val permissions = appContext.permissions
      val context = appContext.reactContext
      if (permissions == null || context == null) {
        promise.reject(CodedException("Permissions manager is unavailable."))
      } else {
        permissions.askForPermissions(
          { _ -> promise.resolve(PermissionsManager.statusMap(context)) },
          *PermissionsManager.requestList(),
        )
      }
    }

    AsyncFunction("getProviderState") { promise: Promise ->
      val context = appContext.reactContext
      if (context == null) {
        promise.reject(CodedException("Context is unavailable."))
      } else {
        promise.resolve(PermissionsManager.statusMap(context))
      }
    }

    AsyncFunction("isIgnoringBatteryOptimizations") { promise: Promise ->
      val context = appContext.reactContext
      if (context == null) {
        promise.reject(CodedException("Context is unavailable."))
      } else {
        promise.resolve(PermissionsManager.isIgnoringBatteryOptimizations(context))
      }
    }

    AsyncFunction("requestIgnoreBatteryOptimizations") { promise: Promise ->
      val context = appContext.reactContext
      if (context == null) {
        promise.reject(CodedException("Context is unavailable."))
      } else {
        PermissionsManager.requestIgnoreBatteryOptimizations(context)
        promise.resolve(PermissionsManager.isIgnoringBatteryOptimizations(context))
      }
    }

    // ---- geofences (M6) ----

    AsyncFunction("addGeofence") { geofence: Map<String, Any?>, promise: Promise ->
      controller.addGeofence(geofence)
      promise.resolve(null)
    }

    AsyncFunction("addGeofences") { geofences: List<Map<String, Any?>>, promise: Promise ->
      controller.addGeofences(geofences)
      promise.resolve(null)
    }

    AsyncFunction("removeGeofence") { identifier: String, promise: Promise ->
      controller.removeGeofence(identifier)
      promise.resolve(null)
    }

    AsyncFunction("removeGeofences") { promise: Promise ->
      controller.removeGeofences()
      promise.resolve(null)
    }

    AsyncFunction("getGeofences") { promise: Promise ->
      promise.resolve(controller.getGeofences())
    }

    // ---- trip & visit ----

    AsyncFunction("getActiveTrip") { promise: Promise ->
      promise.resolve(controller.getActiveTrip())
    }

    // ---- persistence + sync (M5) ----

    AsyncFunction("getLocations") { limit: Int, promise: Promise ->
      controller.getLocations(limit) { promise.resolve(it) }
    }

    AsyncFunction("getCount") { promise: Promise ->
      controller.getCount { promise.resolve(it) }
    }

    AsyncFunction("destroyLocations") { promise: Promise ->
      controller.destroyLocations { promise.resolve(null) }
    }

    AsyncFunction("sync") { promise: Promise ->
      controller.syncNow { uploaded ->
        if (uploaded != null) {
          promise.resolve(uploaded)
        } else {
          promise.reject(CodedException("Sync failed (HTTP error or no network)."))
        }
      }
    }

    // ---- odometer ----

    AsyncFunction("getOdometer") { promise: Promise ->
      promise.resolve(controller.odometer)
    }

    AsyncFunction("setOdometer") { _: Double, promise: Promise ->
      promise.reject(NotImplementedException("setOdometer"))
    }

    // ---- debug ----

    Function("emitTestLocation") {
      controller.emitTestLocation()
    }

    AsyncFunction("simulateLocation") { location: Map<String, Any?>, promise: Promise ->
      val lat = (location["latitude"] as? Number)?.toDouble()
      val lng = (location["longitude"] as? Number)?.toDouble()
      if (lat == null || lng == null) {
        promise.reject(CodedException("simulateLocation requires latitude and longitude."))
      } else {
        controller.simulateLocation(
          latitude = lat,
          longitude = lng,
          accuracy = (location["accuracy"] as? Number)?.toDouble() ?: 5.0,
          speed = (location["speed"] as? Number)?.toDouble() ?: 0.0,
          timestamp = (location["timestamp"] as? Number)?.toLong() ?: 0L,
        )
        promise.resolve(null)
      }
    }
  }
}
