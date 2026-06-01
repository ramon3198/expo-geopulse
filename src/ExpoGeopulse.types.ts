/**
 * Public type surface for `expo-geopulse`.
 *
 * The API is intentionally stable from day one — later milestones fill in the
 * native implementations behind these types, but the shapes won't change.
 */

/** Desired location accuracy. Maps to Android FusedLocation priorities. */
export enum Accuracy {
  /** `PRIORITY_HIGH_ACCURACY` — GPS, best for navigation / active tracking. */
  High = 0,
  /** `PRIORITY_BALANCED_POWER_ACCURACY` — ~block level. Good default for background. */
  Balanced = 1,
  /** `PRIORITY_LOW_POWER` — ~city level, low battery. */
  Low = 2,
  /** `PRIORITY_PASSIVE` — zero cost; only piggyback on other apps' updates. */
  Passive = 3,
}

/**
 * Adaptive accuracy presets. When set on the config, a preset overrides
 * `desiredAccuracy`, `distanceFilter` and the update intervals with a tuned
 * battery/accuracy trade-off — the simplest way to configure tracking.
 *
 * - `eco`: low power, 50 m filter, 30 s interval
 * - `standard`: balanced, 25 m filter, 10 s interval
 * - `high`: GPS, 10 m filter, 5 s interval
 */
export type AccuracyPreset = 'eco' | 'standard' | 'high';

/** Detected motion activity (from Activity Recognition + sensors). */
export enum MotionActivityType {
  Still = 'still',
  Walking = 'walking',
  Running = 'running',
  OnBicycle = 'on_bicycle',
  InVehicle = 'in_vehicle',
  Unknown = 'unknown',
}

export enum LogLevel {
  Off = 0,
  Error = 1,
  Warn = 2,
  Info = 3,
  Debug = 4,
  Verbose = 5,
}

/** Foreground-service notification appearance. */
export interface NotificationConfig {
  title?: string;
  text?: string;
  channelName?: string;
  /** Drawable resource name for the small icon (e.g. `ic_stat_location`). */
  smallIcon?: string;
  priority?: 'default' | 'high' | 'low' | 'min' | 'max';
}

/** Full SDK configuration. Every field is optional; sensible defaults are applied natively. */
export interface GeoPulseConfig {
  // --- tracking ---
  desiredAccuracy?: Accuracy;
  /** Minimum distance (m) between reported locations. */
  distanceFilter?: number;
  /** Preferred interval between updates while moving (ms). */
  locationUpdateInterval?: number;
  /** Fastest interval the engine will accept (ms). */
  fastestLocationUpdateInterval?: number;
  /**
   * Adaptive accuracy preset. When set, it overrides `desiredAccuracy`,
   * `distanceFilter` and the intervals above with a tuned trade-off.
   */
  preset?: AccuracyPreset;
  /**
   * Auto-degrade to the `eco` preset once the battery is at/below this level
   * (0.0–1.0) and not charging. `0` disables. Restored on the next `start()`.
   */
  lowBatteryThreshold?: number;

  // --- battery intelligence (M4) ---
  /** Stop GPS when the device is detected stationary (the big battery saver). */
  stopOnStationary?: boolean;
  /** Radius (m) of the stationary geofence used to wake tracking. */
  stationaryRadius?: number;

  // --- reliability ---
  /** Reject locations the OS flags as mock/spoofed. An `onError` (`MOCK_LOCATION`) still fires. */
  disableMockLocations?: boolean;
  /**
   * Emit an outage (`onProviderChange` with `outage: true`) when no fix arrives
   * for this long (ms). `0` = auto (3× the update interval, min 30 s).
   */
  outageThreshold?: number;

  // --- trip & visit detection ---
  /** Detect visits (stay-points) and trips on-device, emitting `onVisit` / `onTrip`. */
  enableTripDetection?: boolean;
  /** Cluster radius (m) that defines a stay-point. Default 100. */
  visitRadius?: number;
  /** Minimum dwell time (ms) to confirm a visit. Default 180000 (3 min). */
  minVisitDwell?: number;

  // --- driving-behaviour events ---
  /** Detect harsh braking/acceleration, speeding and idling on-device, emitting `onDrivingEvent`. */
  enableDrivingEvents?: boolean;
  /** Harsh-acceleration threshold (m/s²). Default 3.0. */
  harshAccelThreshold?: number;
  /** Harsh-braking threshold (m/s², magnitude). Default 3.5. */
  harshBrakeThreshold?: number;
  /** Speed limit (m/s) above which `speeding` fires. `0` disables. */
  speedLimit?: number;
  /** Continuous near-zero-speed time (ms) before `idling` fires. Default 180000 (3 min). */
  idleTimeout?: number;
  /**
   * Minimum GPS speed (m/s) before harsh-accel/brake events count — avoids
   * false positives from handling the phone. Default 2.0. Set `0` to detect
   * regardless of speed (useful for bench testing by hand).
   */
  drivingMinSpeed?: number;

  // --- accuracy / fusion (M3) ---
  /** Run raw fixes through the C++/NDK Kalman fusion filter. */
  enableKalman?: boolean;
  /** Discard fixes whose accuracy is worse than this (m). `0` keeps all. */
  accuracyFilter?: number;

  // --- lifecycle ---
  enableHeadless?: boolean;
  startOnBoot?: boolean;

  // --- HTTP / persistence (M5) ---
  url?: string;
  httpMethod?: 'POST' | 'PUT';
  headers?: Record<string, string>;
  params?: Record<string, unknown>;
  autoSync?: boolean;
  /** Upload once this many records are queued (0 = upload each location). */
  autoSyncThreshold?: number;
  batchSync?: boolean;
  maxBatchSize?: number;
  maxRecordsToPersist?: number;

  // --- debug ---
  debug?: boolean;
  logLevel?: LogLevel;
  notification?: NotificationConfig;
}

export interface Coords {
  latitude: number;
  longitude: number;
  /** Horizontal accuracy in meters. */
  accuracy: number;
  altitude?: number;
  altitudeAccuracy?: number;
  /** Bearing in degrees (0–360). */
  heading?: number;
  /** Speed in m/s. */
  speed?: number;
  speedAccuracy?: number;
}

export interface Battery {
  /** 0.0–1.0 */
  level: number;
  isCharging: boolean;
}

export interface Activity {
  type: MotionActivityType;
  /** 0–100 */
  confidence: number;
}

export interface Location {
  uuid: string;
  /** Epoch milliseconds. */
  timestamp: number;
  coords: Coords;
  /** True if produced by the Kalman fusion filter rather than a raw fix. */
  filtered?: boolean;
  isMoving?: boolean;
  activity?: Activity;
  battery?: Battery;
  /** `'gps' | 'fused' | 'network' | 'kalman' | 'test'` */
  provider?: string;
  /** True if the OS reports this fix came from a mock-location (spoofing) provider. */
  isMock?: boolean;
  /** Quality score 0–100, driven by accuracy and Kalman filtering. */
  confidence?: number;
  extras?: Record<string, unknown>;
}

export type GeofenceTransition = 'ENTER' | 'EXIT' | 'DWELL';

export interface Geofence {
  identifier: string;
  latitude: number;
  longitude: number;
  /** Circle radius in meters. Ignored when `vertices` is provided. */
  radius: number;
  notifyOnEntry?: boolean;
  notifyOnExit?: boolean;
  notifyOnDwell?: boolean;
  /** Dwell delay (ms) before a DWELL transition fires. */
  loiteringDelay?: number;
  /** Polygon vertices `[lat, lng]`. When set, the geofence is a polygon, not a circle. */
  vertices?: Array<[number, number]>;
  extras?: Record<string, unknown>;
}

export interface GeofenceEvent {
  identifier: string;
  action: GeofenceTransition;
  location: Location;
  /** Quality score 0–100 for the triggering fix. */
  confidence?: number;
}

export interface MotionChangeEvent {
  isMoving: boolean;
  location: Location;
}

export interface ActivityChangeEvent {
  activity: MotionActivityType;
  /** 0–100 */
  confidence: number;
}

export interface ProviderChangeEvent {
  enabled: boolean;
  gps: boolean;
  network: boolean;
  status: number;
  /** True while a signal outage (no fixes) is in progress; false when it recovers. */
  outage?: boolean;
  /** Outage duration so far / total recovered duration (ms). */
  outageDuration?: number;
}

export interface HeartbeatEvent {
  location: Location | null;
}

/** A confirmed stay-point: the device dwelled in one place long enough to count as a visit. */
export interface Visit {
  uuid: string;
  latitude: number;
  longitude: number;
  /** Epoch ms when the visit began. */
  arrivalTime: number;
  /** Epoch ms when the device left, or null while still there. */
  departureTime: number | null;
  /** Time spent at the visit (ms), or null while still there. */
  dwellMs: number | null;
}

export type VisitAction = 'arrive' | 'depart';

export interface VisitEvent {
  action: VisitAction;
  visit: Visit;
}

/** The journey between two visits, with accumulated travelled distance. */
export interface Trip {
  uuid: string;
  startTime: number;
  endTime: number | null;
  startLatitude: number;
  startLongitude: number;
  endLatitude: number;
  endLongitude: number;
  /** Real travelled distance (m), summed over fixes (not straight-line). */
  distanceMeters: number;
  pointCount: number;
  durationMs: number | null;
}

export type TripAction = 'start' | 'end';

export interface TripEvent {
  action: TripAction;
  trip: Trip;
}

export type DrivingEventType =
  | 'harsh_braking'
  | 'harsh_acceleration'
  | 'speeding'
  | 'idling';

export type DrivingSeverity = 'warning' | 'alert' | 'critical';

/** A detected driving-behaviour event (telematics-style), derived on-device. */
export interface DrivingEvent {
  type: DrivingEventType;
  severity: DrivingSeverity;
  /** Acceleration magnitude (m/s²) for harsh events; speed (m/s) for speeding; 0 for idling. */
  magnitude: number;
  /** Speed at the moment of the event (m/s). */
  speed: number;
  /** Epoch ms. */
  timestamp: number;
  /** The last known location when the event fired. */
  location: Location | null;
}

export interface GeoPulseError {
  code: string;
  message: string;
}

export interface GeoPulseState {
  enabled: boolean;
  isMoving: boolean;
  trackingMode: 'location' | 'geofence';
  /** Total distance traveled (m) since the odometer was last reset. */
  odometer: number;
  config: GeoPulseConfig;
}

export interface CurrentPositionOptions {
  /** Max time to wait for a fix (seconds). */
  timeout?: number;
  /** Accept a cached fix no older than this (ms). */
  maximumAge?: number;
  /** Number of fixes to sample; the most accurate is returned. */
  samples?: number;
  /** Persist the resulting location to the local DB. */
  persist?: boolean;
}

export enum AuthorizationStatus {
  NotDetermined = 0,
  Denied = 1,
  WhenInUse = 2,
  Always = 3,
}

export interface PermissionStatus {
  fine: boolean;
  coarse: boolean;
  background: boolean;
  notifications: boolean;
  activityRecognition: boolean;
  /** Whether device location services (GPS/network) are turned on. */
  locationServicesEnabled: boolean;
  status: AuthorizationStatus;
}

/** Strongly-typed event map consumed by `NativeModule<...>`. */
export type GeoPulseEvents = {
  onLocation: (location: Location) => void;
  onMotionChange: (event: MotionChangeEvent) => void;
  onActivityChange: (event: ActivityChangeEvent) => void;
  onGeofence: (event: GeofenceEvent) => void;
  onProviderChange: (event: ProviderChangeEvent) => void;
  onHeartbeat: (event: HeartbeatEvent) => void;
  onError: (error: GeoPulseError) => void;
  onVisit: (event: VisitEvent) => void;
  onTrip: (event: TripEvent) => void;
  onDrivingEvent: (event: DrivingEvent) => void;
};
