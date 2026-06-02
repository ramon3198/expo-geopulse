package expo.modules.geopulse.motion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransitionResult

/** Receives Activity Recognition transition broadcasts and forwards them to [MotionManager]. */
class ActivityTransitionReceiver : BroadcastReceiver() {
  override fun onReceive(
    context: Context,
    intent: Intent,
  ) {
    if (!ActivityTransitionResult.hasResult(intent)) return
    val result = ActivityTransitionResult.extractResult(intent) ?: return
    for (event in result.transitionEvents) {
      MotionManager.handleTransition(event.activityType, event.transitionType)
    }
  }
}
