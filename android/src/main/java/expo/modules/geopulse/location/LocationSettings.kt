package expo.modules.geopulse.location

import android.content.Context
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority

/**
 * Checks whether device location is on, and — when it isn't — surfaces the
 * Google Play-services [ResolvableApiException] so the caller can show the
 * standard in-app "Turn on location?" dialog (no trip to system Settings).
 */
object LocationSettings {
  const val REQUEST_CODE = 0x6e01 // arbitrary, unique to this module

  /**
   * @param onEnabled invoked when location is already usable.
   * @param onResolvable invoked with the exception that can show the dialog.
   * @param onUnavailable invoked when location is off and can't be resolved (no GMS, etc.).
   */
  fun check(
    context: Context,
    onEnabled: () -> Unit,
    onResolvable: (ResolvableApiException) -> Unit,
    onUnavailable: () -> Unit,
  ) {
    val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000).build()
    val settingsRequest =
      LocationSettingsRequest
        .Builder()
        .addLocationRequest(request)
        .setAlwaysShow(true)
        .build()

    LocationServices
      .getSettingsClient(context)
      .checkLocationSettings(settingsRequest)
      .addOnSuccessListener { onEnabled() }
      .addOnFailureListener { e ->
        if (e is ResolvableApiException) onResolvable(e) else onUnavailable()
      }
  }
}
