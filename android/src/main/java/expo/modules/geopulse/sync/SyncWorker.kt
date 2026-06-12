package expo.modules.geopulse.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import expo.modules.geopulse.core.GeoPulseConfig
import expo.modules.geopulse.core.GeoPulseController
import expo.modules.geopulse.db.LocationStore
import java.util.concurrent.TimeUnit

/**
 * Uploads buffered locations to the configured endpoint in batches via
 * [SyncEngine], which applies the documented status-code policy (2xx delete /
 * 400,413,422 discard / else retry). WorkManager handles retry/backoff and
 * network constraints, so data survives offline periods and app restarts.
 */
class SyncWorker(
  context: Context,
  params: WorkerParameters,
) : Worker(context, params) {
  override fun doWork(): Result {
    // WorkManager can run us in a fresh process (after the app was killed, or after a
    // reboot) where the controller is uninitialized. ensureInitialized restores the
    // persisted config AND appContext into the controller — both so a backlog buffered
    // before the restart still uploads, and so a terminal HTTP error below can still
    // reach the headless task (emit() dispatches to headless only when enableHeadless
    // and appContext are both set, which they are not in a default controller).
    GeoPulseController.ensureInitialized(applicationContext)
    val config = GeoPulseController.config
    val url = config.url ?: return Result.success()

    val store = LocationStore.getInstance(applicationContext)
    // batchSync=true uploads the whole backlog in one request; otherwise chunk by
    // maxBatchSize (the default). var: an HTTP 413 halves it mid-run (see below).
    var batchSize =
      if (config.batchSync) {
        // getAll() treats <= 0 as "no limit", so pass maxRecordsToPersist straight
        // through (0 = unlimited persistence -> all rows). coerceAtLeast(1) was wrong:
        // it shrank the unlimited case to a single row per run, breaking the contract.
        config.maxRecordsToPersist
      } else {
        if (config.maxBatchSize > 0) config.maxBatchSize else 250
      }
    val discard = config.discardStatusCodes.toSet()
    val retry = config.retryStatusCodes.toSet()

    // Drain the whole backlog in this run: keep uploading batches while rows
    // remain and uploads succeed. MAX_BATCHES caps a single run. The claim and
    // the settle (delete) are serialized with the manual sync() path via
    // syncLock, but the upload itself runs WITHOUT the lock so a slow server
    // can't block a manual sync() for the whole request — if both paths race the
    // same in-flight rows, the server's dedup-by-uuid absorbs the duplicate.
    repeat(MAX_BATCHES_PER_RUN) {
      val batch = synchronized(LocationStore.syncLock) { store.getAll(batchSize) }
      if (batch.isEmpty()) return Result.success()

      // Re-read effective headers per batch so a JS token refresh (setAuthHeaders)
      // triggered by a 401 mid-drain is picked up on the very next batch.
      val outcome =
        SyncEngine.upload(
          url,
          config.httpMethod,
          GeoPulseController.effectiveHeaders(config),
          config.params,
          batch,
          discard,
          retry,
        )
      if (outcome is SyncEngine.Outcome.Discarded && outcome.status == 413 && batch.size > 1) {
        // Payload too large for the server: halve the batch and retry right away
        // instead of dropping the points. Only a SINGLE point that still 413s is
        // genuinely poisonous — that case falls through to the normal discard.
        batchSize = (batch.size / 2).coerceAtLeast(1)
        return@repeat
      }
      synchronized(LocationStore.syncLock) { SyncEngine.settle(store, batch, outcome) }
      when (outcome) {
        is SyncEngine.Outcome.Success -> Unit // keep draining
        is SyncEngine.Outcome.Discarded -> Unit // batch dropped; keep draining
        is SyncEngine.Outcome.Retry -> {
          val delay = outcome.delaySeconds
          return if (delay != null) {
            // Respect Retry-After precisely: schedule a delayed continuation and
            // report success so WorkManager's own backoff doesn't also kick in.
            scheduleContinuation(applicationContext, delay)
            Result.success()
          } else if (runAttemptCount >= MAX_RETRY_ATTEMPTS) {
            // Give up after ~30 min of exponential backoff instead of silently
            // retrying for hours against a broken backend. The batch STAYS
            // buffered — the next autoSync fix, manual sync() or periodic drain
            // starts a fresh attempt cycle — and the consumer gets an explicit
            // signal to investigate (bad URL, dead auth, server down).
            GeoPulseController.emit(
              "onError",
              mapOf(
                "code" to "SYNC_ABANDONED",
                "message" to
                  "Sync gave up after $runAttemptCount failed attempts (last HTTP ${outcome.status}); " +
                  "points stay buffered. Check the url and auth headers.",
                "status" to outcome.status,
                "attempts" to runAttemptCount,
              ),
            )
            Result.failure()
          } else {
            // Network error / 5xx / 408 / 429 (no Retry-After) / recoverable auth:
            // back off and retry the same batch.
            Result.retry()
          }
        }
      }
    }
    // Hit the per-run cap with rows still pending but no upload error: this is
    // not a failure, so don't trigger WorkManager's exponential backoff. Enqueue
    // a fresh continuation (attempt count resets -> no backoff) that runs as soon
    // as this one completes, and report success.
    if (store.count() > 0) {
      runCatching { scheduleContinuation(applicationContext, 0) }
    }
    return Result.success()
  }

  /**
   * Pre-Android-12 fallback when this run is expedited: WorkManager promotes the
   * worker to a foreground service and needs a (quiet, minimal) notification.
   * On 12+ expedited work runs via the job scheduler and this isn't shown.
   */
  override fun getForegroundInfo(): ForegroundInfo {
    val channelId = "geopulse_sync"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      manager.createNotificationChannel(
        NotificationChannel(channelId, "Location sync", NotificationManager.IMPORTANCE_MIN),
      )
    }
    val notification =
      NotificationCompat
        .Builder(applicationContext, channelId)
        .setContentTitle("Uploading locations")
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .build()
    return ForegroundInfo(SYNC_NOTIFICATION_ID, notification)
  }

  companion object {
    const val UNIQUE_WORK_NAME = "geopulse-sync"
    private const val PERIODIC_WORK_NAME = "geopulse-periodic-drain"
    private const val PERIODIC_INTERVAL_MINUTES = 15L
    private const val MAX_BATCHES_PER_RUN = 50
    private const val MAX_RETRY_ATTEMPTS = 6 // ~30 min of 30s exponential backoff
    private const val SYNC_NOTIFICATION_ID = 48151624

    /**
     * Sync work constraints from the config: always network-connected, plus the
     * optional `syncOnWifiOnly` (unmetered) and `syncRequiresBatteryNotLow` gates.
     */
    fun syncConstraints(config: GeoPulseConfig): Constraints {
      val builder =
        Constraints
          .Builder()
          .setRequiredNetworkType(
            if (config.syncOnWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
          )
      if (config.syncRequiresBatteryNotLow) builder.setRequiresBatteryNotLow(true)
      return builder.build()
    }

    /**
     * Mark an auto-sync request expedited so Doze (Android 12+) runs it promptly
     * instead of deferring minutes-to-hours; degrades to a normal request when
     * the quota is exhausted. Skipped with `syncRequiresBatteryNotLow` — expedited
     * work only supports network/storage constraints and would throw.
     */
    fun applyExpedited(
      builder: OneTimeWorkRequest.Builder,
      config: GeoPulseConfig,
    ): OneTimeWorkRequest.Builder {
      if (!config.syncRequiresBatteryNotLow) {
        builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
      }
      return builder
    }

    /**
     * Safety-net drain (15 min, network-constrained): sweeps any backlog that has
     * no pending one-shot work — autoSync off, an enqueue lost to a crash, or
     * points recorded offline before the process died. KEEP makes this idempotent;
     * it no-ops quickly when the buffer is empty. Cancel via [cancelPeriodicDrain]
     * when no `url` is configured so it doesn't tick for nothing.
     */
    fun ensurePeriodicDrain(
      context: Context,
      config: GeoPulseConfig,
    ) {
      val request =
        PeriodicWorkRequestBuilder<SyncWorker>(PERIODIC_INTERVAL_MINUTES, TimeUnit.MINUTES)
          .setConstraints(syncConstraints(config))
          .build()
      runCatching {
        WorkManager
          .getInstance(context)
          .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
      }
    }

    fun cancelPeriodicDrain(context: Context) {
      runCatching { WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME) }
    }

    /** Enqueue another drain, optionally after [delaySeconds] (Retry-After). */
    private fun scheduleContinuation(
      context: Context,
      delaySeconds: Long,
    ) {
      val builder =
        OneTimeWorkRequestBuilder<SyncWorker>()
          .setConstraints(syncConstraints(GeoPulseController.config))
      if (delaySeconds > 0) builder.setInitialDelay(delaySeconds, TimeUnit.SECONDS)
      WorkManager
        .getInstance(context)
        .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, builder.build())
    }
  }
}
