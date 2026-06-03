package expo.modules.geopulse.sync

/** What to do with a batch after one upload attempt. */
enum class SyncAction { SUCCESS, RETRY, DISCARD }

/**
 * Maps an HTTP status code to a sync action — the contract documented in the
 * README. Pure and dependency-free (no Android imports) so it can be unit-tested
 * on the host JVM, like [expo.modules.geopulse.util.GeoMath].
 *
 * Default policy:
 * - `2xx`            -> SUCCESS (delete the batch).
 * - `400/413/422`    -> DISCARD (the batch itself is bad/too-large; retrying the
 *                       same body loops forever, so drop it).
 * - everything else  -> RETRY  (network error `0`, `401/403` recoverable auth,
 *                       `408/429`, `5xx`, and any unclassified code). We never
 *                       drop data unless we're sure the batch is at fault.
 *
 * A consumer can override the defaults per status via [discardCodes] / [retryCodes].
 */
object SyncPolicy {
  private val DEFAULT_DISCARD = setOf(400, 413, 422)

  fun classify(
    status: Int,
    discardCodes: Set<Int> = emptySet(),
    retryCodes: Set<Int> = emptySet(),
  ): SyncAction =
    when {
      status in 200..299 -> SyncAction.SUCCESS
      // Consumer overrides win over the defaults. RETRY is checked first so that a
      // code mistakenly listed in both sets never drops data.
      status in retryCodes -> SyncAction.RETRY
      status in discardCodes -> SyncAction.DISCARD
      status in DEFAULT_DISCARD -> SyncAction.DISCARD
      else -> SyncAction.RETRY
    }

  /**
   * Parses a `Retry-After` header expressed as delta-seconds. Returns null when
   * absent, negative, or an HTTP-date form (we don't honor dates — callers fall
   * back to normal exponential backoff in that case).
   */
  fun parseRetryAfterSeconds(value: String?): Long? = value?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
}
