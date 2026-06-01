package expo.modules.geopulse.sync

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import expo.modules.geopulse.core.GeoPulseController
import expo.modules.geopulse.db.LocationStore

/**
 * Uploads buffered locations to the configured endpoint in batches, deleting them
 * only on a successful (2xx) response. WorkManager handles retry/backoff and
 * network constraints, so data survives offline periods and app restarts.
 */
class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
  override fun doWork(): Result {
    val config = GeoPulseController.config
    val url = config.url ?: return Result.success()

    val store = LocationStore(applicationContext)
    val batchSize = if (config.maxBatchSize > 0) config.maxBatchSize else 250

    // Drain the whole backlog in this run: keep uploading batches while rows
    // remain and uploads succeed. We only return retry() on a real HTTP failure
    // (so WorkManager's exponential backoff is reserved for genuine errors, not
    // for "there's simply more data to send"). MAX_BATCHES caps a single run.
    repeat(MAX_BATCHES_PER_RUN) {
      val batch = store.getAll(batchSize)
      if (batch.isEmpty()) return Result.success()

      val body = "[" + batch.joinToString(",") { it.json } + "]"
      val result = HttpUploader.upload(url, config.httpMethod, config.headers, body)
      if (!result.success) return Result.retry()

      store.deleteByIds(batch.map { it.id })
    }
    // Hit the per-run cap with rows still pending: reschedule promptly.
    return if (store.count() > 0) Result.retry() else Result.success()
  }

  companion object {
    const val UNIQUE_WORK_NAME = "geopulse-sync"
    private const val MAX_BATCHES_PER_RUN = 50
  }
}
