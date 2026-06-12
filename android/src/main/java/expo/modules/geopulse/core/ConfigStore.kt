package expo.modules.geopulse.core

import android.content.Context
import expo.modules.geopulse.util.Json

/**
 * Persists the last applied configuration so tracking can be restored after a
 * reboot (see BootReceiver) or a process restart without a JS runtime.
 */
class ConfigStore(
  context: Context,
) {
  private val prefs =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  fun saveConfig(map: Map<String, Any?>) {
    prefs.edit().putString(KEY_CONFIG, Json.toJson(map)).apply()
  }

  fun loadConfig(): Map<String, Any?>? {
    val raw = prefs.getString(KEY_CONFIG, null) ?: return null
    return runCatching { Json.toMap(raw) }.getOrNull()
  }

  /**
   * Auth headers are persisted separately from the config: JS refreshes them at
   * runtime (`setAuthHeaders`) and the headless sync worker must read the latest
   * token even when started in a fresh process with no JS runtime.
   */
  fun saveAuthHeaders(headers: Map<String, String>) {
    prefs.edit().putString(KEY_AUTH_HEADERS, Json.toJson(headers)).apply()
  }

  fun loadAuthHeaders(): Map<String, String> {
    val raw = prefs.getString(KEY_AUTH_HEADERS, null) ?: return emptyMap()
    val map = runCatching { Json.toMap(raw) }.getOrNull() ?: return emptyMap()
    return map.entries.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
  }

  /**
   * The current tracking session id: a fresh UUID per `start()` (including the
   * boot-resume start), persisted so a cold process restart of the service or
   * sync worker mid-run continues the SAME logical session instead of
   * fragmenting it.
   */
  fun saveSessionId(id: String) {
    prefs.edit().putString(KEY_SESSION, id).apply()
  }

  fun loadSessionId(): String? = prefs.getString(KEY_SESSION, null)

  companion object {
    private const val PREFS_NAME = "geopulse_prefs"
    private const val KEY_CONFIG = "config"
    private const val KEY_AUTH_HEADERS = "auth_headers"
    private const val KEY_SESSION = "session_id"
  }
}
