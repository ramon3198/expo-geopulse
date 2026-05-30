package expo.modules.geopulse.core

import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record

/** Foreground-service notification appearance. */
class NotificationConfig : Record {
  @Field var title: String? = null
  @Field var text: String? = null
  @Field var channelName: String? = null
  @Field var smallIcon: String? = null
  @Field var priority: String? = null

  fun toMap(): Map<String, Any?> = mapOf(
    "title" to title,
    "text" to text,
    "channelName" to channelName,
    "smallIcon" to smallIcon,
    "priority" to priority,
  )
}

/**
 * Strongly-typed SDK configuration, parsed from the JS `GeoPulseConfig` object
 * via Expo's `Record` machinery. Defaults mirror `ExpoGeopulse.types.ts`.
 */
class GeoPulseConfig : Record {
  // tracking
  @Field var desiredAccuracy: Int = Accuracy.BALANCED
  @Field var distanceFilter: Double = 10.0
  @Field var locationUpdateInterval: Long = 5_000
  @Field var fastestLocationUpdateInterval: Long = 1_000

  // battery intelligence
  @Field var stopOnStationary: Boolean = true
  @Field var stationaryRadius: Double = 50.0

  // accuracy / fusion
  @Field var enableKalman: Boolean = true
  @Field var accuracyFilter: Double = 0.0

  // lifecycle
  @Field var enableHeadless: Boolean = false
  @Field var startOnBoot: Boolean = false

  // HTTP / persistence
  @Field var url: String? = null
  @Field var httpMethod: String = "POST"
  @Field var headers: Map<String, String> = emptyMap()
  @Field var params: Map<String, Any?> = emptyMap()
  @Field var autoSync: Boolean = false
  @Field var autoSyncThreshold: Int = 0
  @Field var batchSync: Boolean = false
  @Field var maxBatchSize: Int = 250
  @Field var maxRecordsToPersist: Int = 10_000

  // debug
  @Field var debug: Boolean = false
  @Field var logLevel: Int = 3
  @Field var notification: NotificationConfig? = null

  fun toMap(): Map<String, Any?> = mapOf(
    "desiredAccuracy" to desiredAccuracy,
    "distanceFilter" to distanceFilter,
    "locationUpdateInterval" to locationUpdateInterval,
    "fastestLocationUpdateInterval" to fastestLocationUpdateInterval,
    "stopOnStationary" to stopOnStationary,
    "stationaryRadius" to stationaryRadius,
    "enableKalman" to enableKalman,
    "accuracyFilter" to accuracyFilter,
    "enableHeadless" to enableHeadless,
    "startOnBoot" to startOnBoot,
    "url" to url,
    "httpMethod" to httpMethod,
    "headers" to headers,
    "params" to params,
    "autoSync" to autoSync,
    "autoSyncThreshold" to autoSyncThreshold,
    "batchSync" to batchSync,
    "maxBatchSize" to maxBatchSize,
    "maxRecordsToPersist" to maxRecordsToPersist,
    "debug" to debug,
    "logLevel" to logLevel,
    "notification" to notification?.toMap(),
  )

  /** Mirrors the JS `Accuracy` enum; resolved to FusedLocation priorities. */
  object Accuracy {
    const val HIGH = 0
    const val BALANCED = 1
    const val LOW = 2
    const val PASSIVE = 3
  }

  companion object {
    /** Rebuilds a config from a persisted map (used to restore after reboot). */
    fun fromMap(map: Map<String, Any?>): GeoPulseConfig {
      val c = GeoPulseConfig()
      (map["desiredAccuracy"] as? Number)?.let { c.desiredAccuracy = it.toInt() }
      (map["distanceFilter"] as? Number)?.let { c.distanceFilter = it.toDouble() }
      (map["locationUpdateInterval"] as? Number)?.let { c.locationUpdateInterval = it.toLong() }
      (map["fastestLocationUpdateInterval"] as? Number)?.let { c.fastestLocationUpdateInterval = it.toLong() }
      (map["stopOnStationary"] as? Boolean)?.let { c.stopOnStationary = it }
      (map["stationaryRadius"] as? Number)?.let { c.stationaryRadius = it.toDouble() }
      (map["enableKalman"] as? Boolean)?.let { c.enableKalman = it }
      (map["accuracyFilter"] as? Number)?.let { c.accuracyFilter = it.toDouble() }
      (map["enableHeadless"] as? Boolean)?.let { c.enableHeadless = it }
      (map["startOnBoot"] as? Boolean)?.let { c.startOnBoot = it }
      (map["url"] as? String)?.let { c.url = it }
      (map["httpMethod"] as? String)?.let { c.httpMethod = it }
      @Suppress("UNCHECKED_CAST")
      (map["headers"] as? Map<String, String>)?.let { c.headers = it }
      @Suppress("UNCHECKED_CAST")
      (map["params"] as? Map<String, Any?>)?.let { c.params = it }
      (map["autoSync"] as? Boolean)?.let { c.autoSync = it }
      (map["autoSyncThreshold"] as? Number)?.let { c.autoSyncThreshold = it.toInt() }
      (map["batchSync"] as? Boolean)?.let { c.batchSync = it }
      (map["maxBatchSize"] as? Number)?.let { c.maxBatchSize = it.toInt() }
      (map["maxRecordsToPersist"] as? Number)?.let { c.maxRecordsToPersist = it.toInt() }
      (map["debug"] as? Boolean)?.let { c.debug = it }
      (map["logLevel"] as? Number)?.let { c.logLevel = it.toInt() }
      return c
    }
  }
}
