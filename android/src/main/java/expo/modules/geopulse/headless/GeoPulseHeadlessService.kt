package expo.modules.geopulse.headless

import android.content.Context
import android.content.Intent
import com.facebook.react.HeadlessJsTaskService
import com.facebook.react.bridge.Arguments
import com.facebook.react.jstasks.HeadlessJsTaskConfig

/**
 * Runs a registered JS *headless* task for a single event when no JS runtime is
 * attached — i.e. the app was killed/swiped away but the foreground service is
 * still tracking. React Native spins up a short-lived JS context, runs the task
 * the app registered via `GeoPulse.registerHeadlessTask(...)`, then tears down.
 *
 * The event name and its payload (JSON) are passed as string intent extras so we
 * don't have to marshal arbitrary nested maps into a Bundle; the JS side parses
 * the payload back into an object.
 */
class GeoPulseHeadlessService : HeadlessJsTaskService() {
  override fun getTaskConfig(intent: Intent?): HeadlessJsTaskConfig? {
    val event = intent?.getStringExtra(EXTRA_EVENT) ?: return null
    val payload = intent.getStringExtra(EXTRA_PAYLOAD) ?: "{}"
    val data =
      Arguments.createMap().apply {
        putString("event", event)
        putString("payload", payload)
      }
    // Last arg is allowedInForeground = true (run even while the app is foregrounded).
    return HeadlessJsTaskConfig(TASK_KEY, data, TIMEOUT_MS, true)
  }

  companion object {
    /** Must match the key the JS side registers with `AppRegistry.registerHeadlessTask`. */
    const val TASK_KEY = "ExpoGeopulseHeadless"
    private const val EXTRA_EVENT = "event"
    private const val EXTRA_PAYLOAD = "payload"
    private const val TIMEOUT_MS = 30_000L

    /**
     * Starts a headless task for [event] with a JSON [payloadJson]. Safe to call
     * from the running foreground service (the process is alive, so the
     * background-service-start restriction doesn't apply).
     */
    fun dispatch(
      context: Context,
      event: String,
      payloadJson: String,
    ) {
      val ctx = context.applicationContext
      val intent =
        Intent(ctx, GeoPulseHeadlessService::class.java).apply {
          putExtra(EXTRA_EVENT, event)
          putExtra(EXTRA_PAYLOAD, payloadJson)
        }
      // Start the service FIRST, and only then hold the wakelock — and only if the
      // start was accepted. Acquiring before startService would leak the wakelock
      // forever if the start is blocked (a background-start restriction), because
      // the service would never run onDestroy() to release it. The acquire is the
      // next synchronous line, so it still happens before onStartCommand runs.
      val started = runCatching { ctx.startService(intent) != null }.getOrDefault(false)
      if (started) {
        runCatching { acquireWakeLockNow(ctx) }
      }
    }
  }
}
