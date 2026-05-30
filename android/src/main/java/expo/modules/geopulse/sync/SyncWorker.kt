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
    val batch = store.getAll(batchSize)
    if (batch.isEmpty()) return Result.success()

    val body = "[" + batch.joinToString(",") { it.json } + "]"
    val result = HttpUploader.upload(url, config.httpMethod, config.headers, body)

    return if (result.success) {
      store.deleteByIds(batch.map { it.id })
      // If more remain, ask WorkManager to run us again.
      if (store.count() > 0) Result.retry() else Result.success()
    } else {
      Result.retry()
    }
  }

  companion object {
    const val UNIQUE_WORK_NAME = "geopulse-sync"
  }
}
