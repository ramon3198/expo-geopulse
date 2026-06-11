package expo.modules.geopulse.sync

import expo.modules.geopulse.core.GeoPulseController
import expo.modules.geopulse.db.LocationStore

/**
 * Uploads a single batch and applies the documented status-code policy, so the
 * manual `syncNow()` path and the WorkManager [SyncWorker] behave identically.
 *
 * Split into [upload] (network I/O — callers run it WITHOUT holding
 * [LocationStore.syncLock], so a slow server can't block the other sync path for
 * the whole request) and [settle] (buffer deletes + events — run under the lock).
 * If the two paths ever race the same rows during an upload window, the server's
 * dedup-by-uuid makes the duplicate upload a no-op and the double delete is
 * id-keyed, so correctness is unaffected.
 *
 * Headless-safe: it needs no JS runtime. Events are emitted via
 * [GeoPulseController.emit], which dispatches to the headless task when the app
 * is killed.
 */
object SyncEngine {
  sealed class Outcome {
    /** 2xx — the batch was uploaded; [settle] deletes it from the buffer. */
    object Success : Outcome()

    /** Permanent client error — [settle] drops the batch from the buffer. */
    data class Discarded(
      val status: Int,
    ) : Outcome()

    /** Transient/recoverable — the batch stays buffered; caller should back off. */
    data class Retry(
      val status: Int,
      val delaySeconds: Long?,
    ) : Outcome()
  }

  /** Upload one batch and classify the response. Pure network — no buffer writes. */
  fun upload(
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
      SyncAction.SUCCESS -> Outcome.Success
      SyncAction.DISCARD -> Outcome.Discarded(result.status)
      SyncAction.RETRY -> Outcome.Retry(result.status, result.retryAfterSeconds)
    }
  }

  /** Apply [outcome]'s buffer side effects and emit the policy events. */
  fun settle(
    store: LocationStore,
    batch: List<LocationStore.Record>,
    outcome: Outcome,
  ) {
    when (outcome) {
      is Outcome.Success -> store.deleteByIds(batch.map { it.id })
      is Outcome.Discarded -> {
        // The batch itself is bad/too-large: retrying the same body would wedge
        // the pipeline forever. Drop it and tell the consumer what was lost.
        store.deleteByIds(batch.map { it.id })
        GeoPulseController.emit(
          "onError",
          mapOf(
            "code" to "BATCH_REJECTED",
            "message" to "Sync batch rejected with HTTP ${outcome.status}; ${batch.size} point(s) dropped.",
            "status" to outcome.status,
            "count" to batch.size,
          ),
        )
        emitSyncError(outcome.status, batch.size)
      }
      is Outcome.Retry -> {
        if (outcome.status == 401) {
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
        emitSyncError(outcome.status, batch.size)
      }
    }
  }

  private fun emitSyncError(
    status: Int,
    count: Int,
  ) = GeoPulseController.emit("onSyncError", mapOf("status" to status, "count" to count))
}
