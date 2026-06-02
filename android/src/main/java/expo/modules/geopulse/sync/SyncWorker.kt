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

/**
 * Uploads buffered locations to the configured endpoint in batches, deleting them
 * only on a successful (2xx) response. WorkManager handles retry/backoff and
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

    // Serialize the whole drain with the manual sync() path so they can't claim
    // and upload the same rows twice.
    synchronized(LocationStore.syncLock) {
      // Drain the whole backlog in this run: keep uploading batches while rows
      // remain and uploads succeed. MAX_BATCHES caps a single run.
      repeat(MAX_BATCHES_PER_RUN) {
        val batch = store.getAll(batchSize)
        if (batch.isEmpty()) return Result.success()

        val body = HttpUploader.buildBody(batch.map { it.json }, config.params)
        val result = HttpUploader.upload(url, config.httpMethod, config.headers, body)
        if (!result.success) {
          return if (isTransient(result.status)) {
            // Network error / 5xx / 408 / 429: back off and retry the same batch.
            Result.retry()
          } else {
            // Permanent client error (400/401/403/413/422/...). Retrying the same
            // body would spin on backoff forever and wedge the whole pipeline, so
            // surface it and stop; the batch stays buffered for the next trigger
            // (or until the app fixes the cause, e.g. refreshes an expired token).
            GeoPulseController.emit(
              "onError",
              mapOf(
                "code" to "HTTP_ERROR",
                "message" to "Sync rejected with HTTP ${result.status}",
                "status" to result.status,
              ),
            )
            Result.success()
          }
        }

        store.deleteByIds(batch.map { it.id })
      }
    }
    // Hit the per-run cap with rows still pending but no upload error: this is
    // not a failure, so don't trigger WorkManager's exponential backoff. Enqueue
    // a fresh continuation (attempt count resets -> no backoff) that runs as soon
    // as this one completes, and report success.
    if (store.count() > 0) {
      runCatching {
        val next =
          OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
              Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            ).build()
        WorkManager
          .getInstance(applicationContext)
          .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, next)
      }
    }
    return Result.success()
  }

  /** Transient = worth retrying with backoff: network error, 5xx, 408, 429. */
  private fun isTransient(status: Int): Boolean = status == 0 || status == 408 || status == 429 || status in 500..599

  companion object {
    const val UNIQUE_WORK_NAME = "geopulse-sync"
    private const val MAX_BATCHES_PER_RUN = 50
  }
}
