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

  // adaptive accuracy preset: "" (manual), "eco", "standard", "high".
  // When set, it overrides the three fields above via resolvePreset().
  @Field var preset: String = ""
  // Auto-degrade to the eco preset when battery is at/below this level (0..1). 0 disables.
  @Field var lowBatteryThreshold: Double = 0.0

  // battery intelligence
  @Field var stopOnStationary: Boolean = true
  @Field var stationaryRadius: Double = 50.0

  // reliability
  @Field var disableMockLocations: Boolean = false
  // Emit an outage event when no fix arrives for this long (ms). 0 = auto (3x interval, min 30s).
  @Field var outageThreshold: Long = 0

  // trip & visit detection
  @Field var enableTripDetection: Boolean = false
  @Field var visitRadius: Double = 100.0       // cluster radius (m) for a stay-point
  @Field var minVisitDwell: Long = 180_000     // min dwell (ms) to confirm a visit (3 min)

  // driving-behaviour events
  @Field var enableDrivingEvents: Boolean = false
  @Field var harshAccelThreshold: Double = 3.0   // m/s^2
  @Field var harshBrakeThreshold: Double = 3.5   // m/s^2
  @Field var speedLimit: Double = 0.0            // m/s; 0 disables speeding detection
  @Field var idleTimeout: Long = 180_000         // ms of near-zero speed -> idling (3 min)
  @Field var drivingMinSpeed: Double = 2.0       // min GPS speed (m/s) before accel events count; 0 = always

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

  /**
   * Applies the named accuracy preset (eco / standard / high) to the tracking
   * fields, or — when battery-low auto-degrade kicks in — forces the eco preset.
   * No-op when [preset] is blank and [forceEco] is false. Returns this config.
   */
  fun resolvePreset(forceEco: Boolean = false): GeoPulseConfig {
    val name = if (forceEco) "eco" else preset.lowercase()
    when (name) {
      "eco" -> {
        desiredAccuracy = Accuracy.LOW
        distanceFilter = 50.0
        locationUpdateInterval = 30_000
        fastestLocationUpdateInterval = 15_000
      }
      "standard" -> {
        desiredAccuracy = Accuracy.BALANCED
        distanceFilter = 25.0
        locationUpdateInterval = 10_000
        fastestLocationUpdateInterval = 5_000
      }
      "high" -> {
        desiredAccuracy = Accuracy.HIGH
        distanceFilter = 10.0
        locationUpdateInterval = 5_000
        fastestLocationUpdateInterval = 1_000
      }
    }
    return this
  }

  fun toMap(): Map<String, Any?> = mapOf(
    "desiredAccuracy" to desiredAccuracy,
    "distanceFilter" to distanceFilter,
    "locationUpdateInterval" to locationUpdateInterval,
    "fastestLocationUpdateInterval" to fastestLocationUpdateInterval,
    "preset" to preset,
    "lowBatteryThreshold" to lowBatteryThreshold,
    "stopOnStationary" to stopOnStationary,
    "stationaryRadius" to stationaryRadius,
    "disableMockLocations" to disableMockLocations,
    "outageThreshold" to outageThreshold,
    "enableTripDetection" to enableTripDetection,
    "visitRadius" to visitRadius,
    "minVisitDwell" to minVisitDwell,
    "enableDrivingEvents" to enableDrivingEvents,
    "harshAccelThreshold" to harshAccelThreshold,
    "harshBrakeThreshold" to harshBrakeThreshold,
    "speedLimit" to speedLimit,
    "idleTimeout" to idleTimeout,
    "drivingMinSpeed" to drivingMinSpeed,
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

  /**
   * Applies only the keys *present* in [map] onto this config, leaving every
   * other field untouched. This is what makes `setConfig` a true MERGE — a JS
   * call like `setConfig({ preset: 'eco' })` changes the mode without wiping
   * `url`, `autoSync`, trip/driving detection, etc.
   */
  fun applyMap(map: Map<String, Any?>): GeoPulseConfig {
    (map["desiredAccuracy"] as? Number)?.let { desiredAccuracy = it.toInt() }
    (map["distanceFilter"] as? Number)?.let { distanceFilter = it.toDouble() }
    (map["locationUpdateInterval"] as? Number)?.let { locationUpdateInterval = it.toLong() }
    (map["fastestLocationUpdateInterval"] as? Number)?.let { fastestLocationUpdateInterval = it.toLong() }
    (map["preset"] as? String)?.let { preset = it }
    (map["lowBatteryThreshold"] as? Number)?.let { lowBatteryThreshold = it.toDouble() }
    (map["stopOnStationary"] as? Boolean)?.let { stopOnStationary = it }
    (map["stationaryRadius"] as? Number)?.let { stationaryRadius = it.toDouble() }
    (map["disableMockLocations"] as? Boolean)?.let { disableMockLocations = it }
    (map["outageThreshold"] as? Number)?.let { outageThreshold = it.toLong() }
    (map["enableTripDetection"] as? Boolean)?.let { enableTripDetection = it }
    (map["visitRadius"] as? Number)?.let { visitRadius = it.toDouble() }
    (map["minVisitDwell"] as? Number)?.let { minVisitDwell = it.toLong() }
    (map["enableDrivingEvents"] as? Boolean)?.let { enableDrivingEvents = it }
    (map["harshAccelThreshold"] as? Number)?.let { harshAccelThreshold = it.toDouble() }
    (map["harshBrakeThreshold"] as? Number)?.let { harshBrakeThreshold = it.toDouble() }
    (map["speedLimit"] as? Number)?.let { speedLimit = it.toDouble() }
    (map["idleTimeout"] as? Number)?.let { idleTimeout = it.toLong() }
    (map["drivingMinSpeed"] as? Number)?.let { drivingMinSpeed = it.toDouble() }
    (map["enableKalman"] as? Boolean)?.let { enableKalman = it }
    (map["accuracyFilter"] as? Number)?.let { accuracyFilter = it.toDouble() }
    (map["enableHeadless"] as? Boolean)?.let { enableHeadless = it }
    (map["startOnBoot"] as? Boolean)?.let { startOnBoot = it }
    (map["url"] as? String)?.let { url = it }
    (map["httpMethod"] as? String)?.let { httpMethod = it }
    @Suppress("UNCHECKED_CAST")
    (map["headers"] as? Map<String, String>)?.let { headers = it }
    @Suppress("UNCHECKED_CAST")
    (map["params"] as? Map<String, Any?>)?.let { params = it }
    (map["autoSync"] as? Boolean)?.let { autoSync = it }
    (map["autoSyncThreshold"] as? Number)?.let { autoSyncThreshold = it.toInt() }
    (map["batchSync"] as? Boolean)?.let { batchSync = it }
    (map["maxBatchSize"] as? Number)?.let { maxBatchSize = it.toInt() }
    (map["maxRecordsToPersist"] as? Number)?.let { maxRecordsToPersist = it.toInt() }
    (map["debug"] as? Boolean)?.let { debug = it }
    (map["logLevel"] as? Number)?.let { logLevel = it.toInt() }
    return this
  }

  companion object {
    /** Rebuilds a config from a persisted map (used to restore after reboot). */
    fun fromMap(map: Map<String, Any?>): GeoPulseConfig = GeoPulseConfig().applyMap(map)
  }
}
