package expo.modules.geopulse.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
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
    // maxBatchSize (the default).
    val batchSize =
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

  companion object {
    const val UNIQUE_WORK_NAME = "geopulse-sync"
    private const val MAX_BATCHES_PER_RUN = 50

    /** Enqueue another drain, optionally after [delaySeconds] (Retry-After). */
    private fun scheduleContinuation(
      context: Context,
      delaySeconds: Long,
    ) {
      val builder =
        OneTimeWorkRequestBuilder<SyncWorker>()
          .setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
          )
      if (delaySeconds > 0) builder.setInitialDelay(delaySeconds, TimeUnit.SECONDS)
      WorkManager
        .getInstance(context)
        .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, builder.build())
    }
  }
}
