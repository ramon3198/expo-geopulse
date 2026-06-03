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
  /**
   * Radius (m) of the stationary geofence used to wake tracking.
   * @remarks Reserved — not yet implemented. Stationary detection currently uses
   * activity recognition + the significant-motion sensor, not a radius.
   */
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
  /**
   * Run a registered JS task for events while the app is killed (the foreground
   * service keeps tracking). Register the task with
   * {@link GeoPulse.registerHeadlessTask} at your app's entry point. When `false`
   * the data pipeline is still headless-safe (fixes are buffered and synced
   * natively); only the JS callbacks require this.
   */
  enableHeadless?: boolean;
  /**
   * Coalesce headless `onLocation` deliveries (with `enableHeadless`): batch fixes
   * and deliver them to the headless task as a `Location[]` once this many seconds
   * have passed since the first buffered fix. `0` (default) = off (one event per
   * fix). Avoids spawning an ephemeral JS context per fix on, e.g., a highway. The
   * SQLite sync pipeline is unaffected — it still stores every fix individually.
   */
  headlessCoalesceWindow?: number;
  /** Coalesce headless `onLocation` after this many fixes. `0` (default) = off. */
  headlessCoalesceCount?: number;
  startOnBoot?: boolean;

  // --- HTTP / persistence (M5) ---
  url?: string;
  httpMethod?: 'POST' | 'PUT';
  headers?: Record<string, string>;
  /**
   * Extra key/values merged into the root of each sync request body. When set,
   * the body becomes `{ "locations": [...], ...params }` instead of a bare array
   * — useful for attaching an auth token, device metadata, etc.
   */
  params?: Record<string, unknown>;
  autoSync?: boolean;
  /** Upload once this many records are queued (0 = upload each location). */
  autoSyncThreshold?: number;
  /**
   * When `true`, upload the entire queued backlog in a single request; when
   * `false` (default) upload in chunks of `maxBatchSize`.
   */
  batchSync?: boolean;
  maxBatchSize?: number;
  /** Max points kept in the local buffer (the sync cap). Default 10000. */
  maxRecordsToPersist?: number;
  /**
   * What to do when the buffer reaches `maxRecordsToPersist`: `'dropOldest'`
   * (default — right for route tracking) drops the oldest queued points;
   * `'dropNewest'` drops the incoming fix. Either way an `onError`
   * (`BUFFER_OVERFLOW`, with a `dropped` count) fires.
   */
  bufferOverflowPolicy?: 'dropOldest' | 'dropNewest';
  /**
   * HTTP status codes that should make sync **drop** the batch (in addition to
   * the defaults `400`, `413`, `422`). Use for backend-specific permanent errors.
   * A re-tryable code (see {@link retryStatusCodes}) always wins, so you never
   * accidentally discard data.
   */
  discardStatusCodes?: number[];
  /**
   * HTTP status codes that should make sync **retry** the batch with backoff,
   * overriding the defaults (e.g. force-retry a `400` your backend returns for a
   * transient reason). Takes precedence over {@link discardStatusCodes}.
   */
  retryStatusCodes?: number[];

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

/** Emitted on `onSyncError` after a failed upload attempt (any non-2xx or network error). */
export interface SyncErrorEvent {
  /** HTTP status (0 = network error / no response). */
  status: number;
  /** Number of points in the affected batch. */
  count: number;
}

export interface GeoPulseError {
  /**
   * Machine-readable code. Sync/buffer-related codes:
   * - `BATCH_REJECTED` — a batch was permanently rejected (`400/413/422`, or a
   *   `discardStatusCodes` match) and dropped from the buffer; includes `status` + `count`.
   * - `AUTH_FAILED` — a `401`; refresh credentials (see {@link GeoPulse.registerAuthProvider}).
   * - `BUFFER_OVERFLOW` — the buffer hit `maxRecordsToPersist`; includes `dropped` + `policy`.
   * - `DB_MIGRATION_FAILED` — a schema migration failed and was rolled back (data
   *   preserved, not wiped); includes `fromVersion` + `toVersion`.
   * - `BACKGROUND_PERMISSION_MISSING` — tracking started with foreground-only
   *   location; it will pause when the app is backgrounded until "Allow all the
   *   time" is granted.
   */
  code: string;
  message: string;
  /** HTTP status, present for sync errors (`BATCH_REJECTED`, `AUTH_FAILED`). */
  status?: number;
  /** Points dropped, present for `BATCH_REJECTED`. */
  count?: number;
  /** Points dropped, present for `BUFFER_OVERFLOW`. */
  dropped?: number;
  /** Overflow policy that ran, present for `BUFFER_OVERFLOW`. */
  policy?: 'dropOldest' | 'dropNewest';
  /** Schema version migrated from, present for `DB_MIGRATION_FAILED`. */
  fromVersion?: number;
  /** Schema version migrated to, present for `DB_MIGRATION_FAILED`. */
  toVersion?: number;
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

/**
 * Coarse three-level location-permission state (Android 11+):
 * - `none` — no location permission.
 * - `foregroundOnly` — "While using the app"; tracking works only while the app is
 *   visible (a background upload/track will pause when backgrounded).
 * - `background` — "Allow all the time"; full background tracking.
 */
export type ProviderLevel = 'none' | 'foregroundOnly' | 'background';

export interface PermissionStatus {
  fine: boolean;
  coarse: boolean;
  background: boolean;
  notifications: boolean;
  activityRecognition: boolean;
  /** Whether device location services (GPS/network) are turned on. */
  locationServicesEnabled: boolean;
  status: AuthorizationStatus;
  /** Coarse three-level summary of the location grant. */
  level: ProviderLevel;
}

/** Strongly-typed event map consumed by `NativeModule<...>`. */
export type GeoPulseEvents = {
  onLocation: (location: Location) => void;
  onMotionChange: (event: MotionChangeEvent) => void;
  onActivityChange: (event: ActivityChangeEvent) => void;
  onGeofence: (event: GeofenceEvent) => void;
  onProviderChange: (event: ProviderChangeEvent) => void;
  /** @remarks Reserved — not yet implemented; no heartbeat events are emitted yet. */
  onHeartbeat: (event: HeartbeatEvent) => void;
  onError: (error: GeoPulseError) => void;
  onVisit: (event: VisitEvent) => void;
  onTrip: (event: TripEvent) => void;
  onDrivingEvent: (event: DrivingEvent) => void;
  /** Fires after a failed sync upload attempt (any non-2xx or network error). */
  onSyncError: (event: SyncErrorEvent) => void;
};

/**
 * Payload delivered to a {@link GeoPulse.registerHeadlessTask} handler when an
 * event fires while the app is killed.
 */
export interface HeadlessEvent {
  /** Native event name, e.g. `'onLocation'`, `'onGeofence'`, `'onError'`, `'onTrip'`. */
  event: keyof GeoPulseEvents | string;
  /**
   * The event's payload — the same object the matching `on(...)` callback would
   * receive (a `Location` for `'onLocation'`, a `GeofenceEvent` for `'onGeofence'`,
   * etc.). Narrow it by `event`. When headless coalescing is enabled
   * (`headlessCoalesceWindow` / `headlessCoalesceCount`), `'onLocation'` delivers a
   * batch — `data` is a `Location[]` instead of a single `Location`.
   */
  data: unknown;
}
