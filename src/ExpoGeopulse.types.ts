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

  // --- battery intelligence (M4) ---
  /** Stop GPS when the device is detected stationary (the big battery saver). */
  stopOnStationary?: boolean;
  /** Radius (m) of the stationary geofence used to wake tracking. */
  stationaryRadius?: number;

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
}

export interface HeartbeatEvent {
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
};
