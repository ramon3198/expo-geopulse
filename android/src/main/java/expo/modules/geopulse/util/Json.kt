package expo.modules.geopulse.util

import org.json.JSONArray
import org.json.JSONObject

/** Minimal, dependency-free JSON conversion between maps/lists and strings. */
object Json {
  fun toJson(value: Any?): String = wrap(value).toString()

  private fun wrap(value: Any?): Any =
    when (value) {
      null -> JSONObject.NULL
      is Map<*, *> ->
        JSONObject().apply {
          for ((k, v) in value) put(k.toString(), wrap(v))
        }
      is List<*> ->
        JSONArray().apply {
          for (v in value) put(wrap(v))
        }
      else -> value
    }

  fun toMap(jsonString: String): Map<String, Any?> = unwrapObject(JSONObject(jsonString))

  private fun unwrapObject(obj: JSONObject): Map<String, Any?> {
    val map = LinkedHashMap<String, Any?>()
    val keys = obj.keys()
    while (keys.hasNext()) {
      val key = keys.next()
      map[key] = unwrap(obj.get(key))
    }
    return map
  }

  private fun unwrap(value: Any?): Any? =
    when (value) {
      JSONObject.NULL -> null
      is JSONObject -> unwrapObject(value)
      is JSONArray -> (0 until value.length()).map { unwrap(value.get(it)) }
      else -> value
    }
}
