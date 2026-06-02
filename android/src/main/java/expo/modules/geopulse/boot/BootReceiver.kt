package expo.modules.geopulse.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import expo.modules.geopulse.core.GeoPulseController

/**
 * Restarts tracking after device reboot when `startOnBoot` is enabled. Reads the
 * persisted config and re-launches the foreground service — no JS runtime needed.
 */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(
    context: Context,
    intent: Intent?,
  ) {
    when (intent?.action) {
      Intent.ACTION_BOOT_COMPLETED,
      "android.intent.action.QUICKBOOT_POWERON",
      -> GeoPulseController.restoreAndStart(context)
    }
  }
}
