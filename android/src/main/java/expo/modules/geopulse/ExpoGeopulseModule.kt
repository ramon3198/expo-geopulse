package expo.modules.geopulse

import android.Manifest
import android.os.Build
import expo.modules.geopulse.core.GeoPulseConfig
import expo.modules.geopulse.core.GeoPulseController
import expo.modules.geopulse.core.PermissionsManager
import expo.modules.geopulse.headless.GeoPulseHeadlessService
import expo.modules.geopulse.location.LocationSettings
import expo.modules.geopulse.util.Json
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

/** Thrown by API surface that is declared but not yet implemented in this milestone. */
private class NotImplementedException(
  feature: String,
) : CodedException("$feature is not implemented yet (coming in a later milestone).")

class ExpoGeopulseModule : Module() {
  private val controller get() = GeoPulseController

  // Pending promise for the "turn on location" system dialog (resolved in OnActivityResult).
  private var enableLocationPromise: Promise? = null

  override fun definition() =
    ModuleDefinition {
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
        // Settle a pending "enable location" promise so a runtime teardown while the
        // system dialog is open doesn't leave ensurePermissions()/track() hanging.
        enableLocationPromise?.resolve(false)
        enableLocationPromise = null
        controller.detach()
      }

      // Result of the "turn on location" system dialog.
      OnActivityResult { _, payload ->
        if (payload.requestCode == LocationSettings.REQUEST_CODE) {
          val promise = enableLocationPromise
          enableLocationPromise = null
          // resultCode RESULT_OK (-1) means the user enabled location.
          promise?.resolve(payload.resultCode == android.app.Activity.RESULT_OK)
        }
      }

      // ---- lifecycle / tracking ----

      AsyncFunction("ready") { config: GeoPulseConfig, promise: Promise ->
        controller.ready(config)
        promise.resolve(controller.stateMap())
      }

      // Merge: only the keys present in the JS object are applied; the rest of the
      // running config (url, autoSync, trip/driving detection, ...) is preserved.
      AsyncFunction("setConfig") { patch: Map<String, Any?>, promise: Promise ->
        controller.setConfig(patch)
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

      // Requests ACCESS_BACKGROUND_LOCATION. On Android 10+ this MUST come after a
      // foreground grant; the result map's `background` field reflects the outcome.
      // If the OS won't show a dialog (already denied / "only this time" history),
      // the app should fall back to openAppSettings().
      AsyncFunction("requestBackgroundPermission") { promise: Promise ->
        val permissions = appContext.permissions
        val context = appContext.reactContext
        if (permissions == null || context == null) {
          promise.reject(CodedException("Permissions manager is unavailable."))
          return@AsyncFunction
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
          promise.resolve(PermissionsManager.statusMap(context)) // covered by foreground grant
          return@AsyncFunction
        }
        if (!PermissionsManager.hasLocationPermission(context)) {
          // Background can't be granted without foreground first.
          promise.reject(CodedException("NEEDS_FOREGROUND: grant foreground location before background."))
          return@AsyncFunction
        }
        permissions.askForPermissions(
          { _ -> promise.resolve(PermissionsManager.statusMap(context)) },
          Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
      }

      AsyncFunction("openAppSettings") { promise: Promise ->
        val context = appContext.reactContext
        if (context == null) {
          promise.reject(CodedException("Context is unavailable."))
        } else {
          PermissionsManager.openAppSettings(context)
          promise.resolve(null)
        }
      }

      // Prompt the user to turn on device location via the Play-services dialog.
      // Resolves true if location is (or becomes) enabled, false otherwise.
      AsyncFunction("requestEnableLocation") { promise: Promise ->
        val context = appContext.reactContext
        val activity = appContext.currentActivity
        if (context == null) {
          promise.reject(CodedException("Context is unavailable."))
          return@AsyncFunction
        }
        LocationSettings.check(
          context = context,
          onEnabled = { promise.resolve(true) },
          onResolvable = { resolvable ->
            if (activity == null) {
              promise.resolve(false)
            } else {
              // Settle any orphaned prior request before overwriting it.
              enableLocationPromise?.resolve(false)
              enableLocationPromise = promise
              try {
                resolvable.startResolutionForResult(activity, LocationSettings.REQUEST_CODE)
              } catch (e: Throwable) {
                enableLocationPromise = null
                promise.resolve(false)
              }
            }
          },
          onUnavailable = { promise.resolve(false) },
        )
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

      AsyncFunction("setOdometer") { value: Double, promise: Promise ->
        controller.odometer = value
        promise.resolve(controller.lastLocationMap())
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

      // testing — directly dispatch a headless task (validates the registered JS
      // headless task runs via the native HeadlessJsTaskService), independent of
      // the app being killed. Uses the last known location, or a synthetic one.
      AsyncFunction("simulateHeadless") { promise: Promise ->
        val ctx = appContext.reactContext?.applicationContext
        if (ctx == null) {
          promise.reject(CodedException("Context unavailable."))
          return@AsyncFunction
        }
        val loc =
          controller.lastLocationMap() ?: mapOf(
            "uuid" to "headless-test-${System.currentTimeMillis()}",
            "timestamp" to System.currentTimeMillis(),
            "coords" to mapOf("latitude" to 0.0, "longitude" to 0.0, "accuracy" to 5.0),
          )
        GeoPulseHeadlessService.dispatch(ctx, "onLocation", Json.toJson(loc))
        promise.resolve(true)
      }
    }
}
