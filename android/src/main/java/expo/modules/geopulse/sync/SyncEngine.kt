package expo.modules.geopulse.sync

import expo.modules.geopulse.core.GeoPulseController
import expo.modules.geopulse.db.LocationStore

/**
 * Uploads a single batch and applies the documented status-code policy, so the
 * manual `syncNow()` path and the WorkManager [SyncWorker] behave identically.
 *
 * Headless-safe: it needs no JS runtime. Events are emitted via
 * [GeoPulseController.emit], which dispatches to the headless task when the app
 * is killed.
 */
object SyncEngine {
  sealed class Outcome {
    /** 2xx — the batch was uploaded and deleted from the buffer. */
    object Success : Outcome()

    /** Permanent client error — the batch was dropped from the buffer. */
    data class Discarded(
      val status: Int,
    ) : Outcome()

    /** Transient/recoverable — the batch stays buffered; caller should back off. */
    data class Retry(
      val status: Int,
      val delaySeconds: Long?,
    ) : Outcome()
  }

  fun uploadBatch(
    store: LocationStore,
    url: String,
    method: String,
    headers: Map<String, String>,
    params: Map<String, Any?>,
    batch: List<LocationStore.Record>,
    discardCodes: Set<Int>,
    retryCodes: Set<Int>,
  ): Outcome {
    val body = HttpUploader.buildBody(batch.map { it.json }, params)
    val result = HttpUploader.upload(url, method, headers, body)
    return when (SyncPolicy.classify(result.status, discardCodes, retryCodes)) {
      SyncAction.SUCCESS -> {
        store.deleteByIds(batch.map { it.id })
        Outcome.Success
      }
      SyncAction.DISCARD -> {
        // The batch itself is bad/too-large: retrying the same body would wedge
        // the pipeline forever. Drop it and tell the consumer what was lost.
        store.deleteByIds(batch.map { it.id })
        GeoPulseController.emit(
          "onError",
          mapOf(
            "code" to "BATCH_REJECTED",
            "message" to "Sync batch rejected with HTTP ${result.status}; ${batch.size} point(s) dropped.",
            "status" to result.status,
            "count" to batch.size,
          ),
        )
        emitSyncError(result.status, batch.size)
        Outcome.Discarded(result.status)
      }
      SyncAction.RETRY -> {
        if (result.status == 401) {
          // Let the JS layer (if alive) refresh its token via the registered
          // getAuthHeaders provider and re-sync before the backoff window.
          GeoPulseController.emit(
            "onError",
            mapOf(
              "code" to "AUTH_FAILED",
              "message" to "Sync rejected with HTTP 401; refresh auth headers.",
              "status" to 401,
            ),
          )
        }
        emitSyncError(result.status, batch.size)
        Outcome.Retry(result.status, result.retryAfterSeconds)
      }
    }
  }

  private fun emitSyncError(
    status: Int,
    count: Int,
  ) = GeoPulseController.emit("onSyncError", mapOf("status" to status, "count" to count))
}
