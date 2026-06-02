package expo.modules.geopulse.geofence

import android.content.Context
import expo.modules.geopulse.util.Json

/**
 * Persists the geofence registry (and which ids are currently registered with
 * Play Services) so geofences survive a process restart: the OS keeps delivering
 * transitions to a fresh process, and we need the specs back to refine/forward
 * them — and the registered-id set to reconcile correctly without exceeding the
 * 100-geofence OS cap.
 */
class GeofenceStore(
  context: Context,
) {
  private val prefs =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  fun saveRegistry(specs: List<Map<String, Any?>>) {
    prefs.edit().putString(KEY_REGISTRY, Json.toJson(mapOf("items" to specs))).apply()
  }

  fun loadRegistry(): List<Map<String, Any?>> {
    val raw = prefs.getString(KEY_REGISTRY, null) ?: return emptyList()
    val map = runCatching { Json.toMap(raw) }.getOrNull() ?: return emptyList()
    @Suppress("UNCHECKED_CAST")
    return (map["items"] as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()
  }

  fun saveRegistered(ids: Collection<String>) {
    prefs.edit().putString(KEY_REGISTERED, Json.toJson(mapOf("ids" to ids.toList()))).apply()
  }

  fun loadRegistered(): List<String> {
    val raw = prefs.getString(KEY_REGISTERED, null) ?: return emptyList()
    val map = runCatching { Json.toMap(raw) }.getOrNull() ?: return emptyList()
    return (map["ids"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
  }

  companion object {
    private const val PREFS_NAME = "geopulse_geofences"
    private const val KEY_REGISTRY = "registry"
    private const val KEY_REGISTERED = "registered"
  }
}
