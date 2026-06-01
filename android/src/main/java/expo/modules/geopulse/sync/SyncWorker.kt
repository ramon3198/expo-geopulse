package expo.modules.geopulse.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import expo.modules.geopulse.core.ConfigStore
import expo.modules.geopulse.core.GeoPulseConfig
import expo.modules.geopulse.core.GeoPulseController
import expo.modules.geopulse.db.LocationStore

/**
 * Uploads buffered locations to the configured endpoint in batches, deleting them
 * only on a successful (2xx) response. WorkManager handles retry/backoff and
 * network constraints, so data survives offline periods and app restarts.
 */
class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
  override fun doWork(): Result {
    var config = GeoPulseController.config
    if (config.url == null) {
      // WorkManager can run us in a fresh process (after the app was killed, or
      // after a reboot) where the in-memory controller config is still default.
      // Fall back to the persisted config so a backlog buffered before the
      // restart still uploads instead of being silently dropped.
      runCatching { ConfigStore(applicationContext).loadConfig() }
        .getOrNull()
        ?.let { config = GeoPulseConfig.fromMap(it) }
    }
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
    // Hit the per-run cap with rows still pending but no upload error: this is
    // not a failure, so don't trigger WorkManager's exponential backoff. Enqueue
    // a fresh continuation (attempt count resets -> no backoff) that runs as soon
    // as this one completes, and report success.
    if (store.count() > 0) {
      runCatching {
        val next = OneTimeWorkRequestBuilder<SyncWorker>()
          .setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
          )
          .build()
        WorkManager.getInstance(applicationContext)
          .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, next)
      }
    }
    return Result.success()
  }

  companion object {
    const val UNIQUE_WORK_NAME = "geopulse-sync"
    private const val MAX_BATCHES_PER_RUN = 50
  }
}
