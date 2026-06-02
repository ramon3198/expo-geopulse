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

  companion object {
    private const val PREFS_NAME = "geopulse_prefs"
    private const val KEY_CONFIG = "config"
  }
}
