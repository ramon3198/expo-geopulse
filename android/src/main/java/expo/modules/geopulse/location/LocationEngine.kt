package expo.modules.geopulse.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import expo.modules.geopulse.core.GeoPulseConfig
import expo.modules.geopulse.core.PermissionsManager

/**
 * Wraps location acquisition. Prefers [FusedLocationProviderClient] (Google Play
 * Services) and transparently falls back to the framework [LocationManager] on
 * GMS-free devices (e.g. Huawei, AOSP), which is one of our advantages over
 * GMS-only competitors.
 *
 * Permission checks are the caller's responsibility, but [start]/[getCurrentLocation]
 * also no-op safely if permission is missing.
 */
class LocationEngine(private val context: Context) {

  fun interface LocationUpdateListener {
    fun onLocation(location: Location)
  }

  private val usesGms: Boolean by lazy {
    runCatching {
      GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }.getOrDefault(false)
  }

  private var fusedClient: FusedLocationProviderClient? = null
  private var fusedCallback: LocationCallback? = null
  private var locationManager: LocationManager? = null
  private var rawListener: LocationListener? = null

  @SuppressLint("MissingPermission")
  fun start(config: GeoPulseConfig, listener: LocationUpdateListener) {
    if (!PermissionsManager.hasLocationPermission(context)) return
    stop() // idempotent: never double-register listeners
    if (usesGms) startFused(config, listener) else startRaw(config, listener)
  }

  @SuppressLint("MissingPermission")
  private fun startFused(config: GeoPulseConfig, listener: LocationUpdateListener) {
    val client = LocationServices.getFusedLocationProviderClient(context)
    fusedClient = client
    val request = LocationRequest.Builder(toGmsPriority(config.desiredAccuracy), config.locationUpdateInterval)
      .setMinUpdateIntervalMillis(config.fastestLocationUpdateInterval)
      .setMinUpdateDistanceMeters(config.distanceFilter.toFloat())
      .setWaitForAccurateLocation(false)
      .build()
    val callback = object : LocationCallback() {
      override fun onLocationResult(result: LocationResult) {
        result.lastLocation?.let { listener.onLocation(it) }
      }
    }
    fusedCallback = callback
    client.requestLocationUpdates(request, callback, Looper.getMainLooper())
  }

  @SuppressLint("MissingPermission")
  private fun startRaw(config: GeoPulseConfig, listener: LocationUpdateListener) {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    locationManager = lm
    val provider = when {
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        lm.allProviders.contains(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
      lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
      else -> LocationManager.NETWORK_PROVIDER
    }
    val l = LocationListener { listener.onLocation(it) }
    rawListener = l
    lm.requestLocationUpdates(
      provider,
      config.locationUpdateInterval,
      config.distanceFilter.toFloat(),
      l,
      Looper.getMainLooper(),
    )
  }

  fun stop() {
    fusedCallback?.let { fusedClient?.removeLocationUpdates(it) }
    fusedCallback = null
    fusedClient = null
    rawListener?.let { locationManager?.removeUpdates(it) }
    rawListener = null
    locationManager = null
  }

  @SuppressLint("MissingPermission")
  fun getCurrentLocation(config: GeoPulseConfig, onResult: (Location?) -> Unit) {
    if (!PermissionsManager.hasLocationPermission(context)) {
      onResult(null)
      return
    }
    if (usesGms) {
      val client = LocationServices.getFusedLocationProviderClient(context)
      val request = CurrentLocationRequest.Builder()
        .setPriority(toGmsPriority(config.desiredAccuracy))
        .build()
      client.getCurrentLocation(request, null)
        .addOnSuccessListener { onResult(it) }
        .addOnFailureListener { onResult(null) }
    } else {
      val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
      val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
      onResult(last)
    }
  }

  private fun toGmsPriority(accuracy: Int): Int = when (accuracy) {
    GeoPulseConfig.Accuracy.HIGH -> Priority.PRIORITY_HIGH_ACCURACY
    GeoPulseConfig.Accuracy.BALANCED -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
    GeoPulseConfig.Accuracy.LOW -> Priority.PRIORITY_LOW_POWER
    GeoPulseConfig.Accuracy.PASSIVE -> Priority.PRIORITY_PASSIVE
    else -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
  }
}
