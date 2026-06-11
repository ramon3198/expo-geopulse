package expo.modules.geopulse.sync

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream

/** Posts a JSON body to a configured endpoint via HttpURLConnection (no extra deps). */
object HttpUploader {
  data class Result(
    val success: Boolean,
    val status: Int,
    val body: String?,
    /** Parsed `Retry-After` delta-seconds (429/503), or null if absent/non-numeric. */
    val retryAfterSeconds: Long? = null,
  )

  // Below this size gzip's ~20-byte header/overhead isn't worth it; above it, a
  // batch of locations compresses ~80-90%, cutting upload bytes and battery.
  private const val GZIP_MIN_BYTES = 256

  fun upload(
    url: String,
    method: String,
    headers: Map<String, String>,
    body: String,
    timeoutMs: Int = 30_000,
  ): Result {
    var connection: HttpURLConnection? = null
    return try {
      val raw = body.toByteArray(Charsets.UTF_8)
      val gzip = raw.size >= GZIP_MIN_BYTES
      val payload = if (gzip) gzip(raw) else raw
      connection =
        (URL(url).openConnection() as HttpURLConnection).apply {
          requestMethod = if (method.equals("PUT", ignoreCase = true)) "PUT" else "POST"
          connectTimeout = timeoutMs
          readTimeout = timeoutMs
          doOutput = true
          setRequestProperty("Content-Type", "application/json")
          // NOTE: don't set Accept-Encoding manually — Android's HttpURLConnection
          // already requests gzip and transparently decompresses; setting it
          // ourselves would disable that and hand us raw gzip bytes below.
          if (gzip) setRequestProperty("Content-Encoding", "gzip")
          setFixedLengthStreamingMode(payload.size)
          for ((key, value) in headers) setRequestProperty(key, value)
        }
      connection.outputStream.use { it.write(payload) }
      val code = connection.responseCode
      val success = code in 200..299
      val stream = if (success) connection.inputStream else connection.errorStream
      val responseBody = stream?.bufferedReader()?.use { it.readText() }
      val retryAfter = SyncPolicy.parseRetryAfterSeconds(connection.getHeaderField("Retry-After"))
      Result(success, code, responseBody, retryAfter)
    } catch (t: Throwable) {
      Result(false, 0, t.message)
    } finally {
      connection?.disconnect()
    }
  }

  /**
   * Builds the upload body from pre-serialized location JSON strings. Without
   * `params` it's a bare array `[loc, loc, ...]` (backward-compatible); with
   * `params` set it's `{ "locations": [...], ...params }` so callers can attach
   * custom fields (e.g. an auth/device token) to every sync request.
   */
  fun buildBody(
    locationJsons: List<String>,
    params: Map<String, Any?>,
  ): String {
    // params serializes to a well-formed object; splice its inner fields next to
    // "locations". (Safe: a serialized object's outer braces are always its first
    // and last characters, so stripping them never touches values.)
    val inner =
      if (params.isEmpty()) {
        ""
      } else {
        expo.modules.geopulse.util.Json
          .toJson(params)
          .trim()
          .removePrefix("{")
          .removeSuffix("}")
          .trim()
      }
    // Single pre-sized builder: join-then-wrap re-copied the whole payload per
    // step (several MB of transient strings for a large batchSync upload).
    val wrap = params.isNotEmpty()
    var capacity = locationJsons.size + 32 + inner.length
    for (json in locationJsons) capacity += json.length
    val sb = StringBuilder(capacity)
    if (wrap) sb.append("{\"locations\":")
    sb.append('[')
    locationJsons.forEachIndexed { i, json ->
      if (i > 0) sb.append(',')
      sb.append(json)
    }
    sb.append(']')
    if (wrap) {
      if (inner.isNotEmpty()) sb.append(',').append(inner)
      sb.append('}')
    }
    return sb.toString()
  }

  private fun gzip(data: ByteArray): ByteArray {
    // Batched location JSON compresses to ~10-20%; size/4 leaves headroom while
    // avoiding the grow-and-copy churn a size/2 over-allocation was meant to
    // prevent and a too-small default (32B) would cause.
    val out = ByteArrayOutputStream((data.size / 4).coerceAtLeast(64))
    GZIPOutputStream(out).use { it.write(data) }
    return out.toByteArray()
  }
}
