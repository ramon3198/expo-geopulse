package expo.modules.geopulse.sync

import java.net.HttpURLConnection
import java.net.URL

/** Posts a JSON body to a configured endpoint via HttpURLConnection (no extra deps). */
object HttpUploader {
  data class Result(val success: Boolean, val status: Int, val body: String?)

  fun upload(
    url: String,
    method: String,
    headers: Map<String, String>,
    body: String,
    timeoutMs: Int = 30_000,
  ): Result {
    var connection: HttpURLConnection? = null
    return try {
      connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = if (method.equals("PUT", ignoreCase = true)) "PUT" else "POST"
        connectTimeout = timeoutMs
        readTimeout = timeoutMs
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        for ((key, value) in headers) setRequestProperty(key, value)
      }
      connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
      val code = connection.responseCode
      val success = code in 200..299
      val stream = if (success) connection.inputStream else connection.errorStream
      val responseBody = stream?.bufferedReader()?.use { it.readText() }
      Result(success, code, responseBody)
    } catch (t: Throwable) {
      Result(false, 0, t.message)
    } finally {
      connection?.disconnect()
    }
  }
}
