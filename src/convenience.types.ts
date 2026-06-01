import type { Accuracy } from './ExpoGeopulse.types';

/** Friendly tracking presets (strings instead of the `Accuracy` enum). */
export type TrackMode = 'eco' | 'balanced' | 'high';

/** Unified event names for the single `on(...)` subscriber. */
export type EventName =
  | 'location'
  | 'motion'
  | 'activity'
  | 'geofence'
  | 'provider'
  | 'heartbeat'
  | 'error'
  | 'visit'
  | 'trip'
  | 'driving';

/** High-level options for `track()` / `ensurePermissions()`. Sensible defaults applied. */
export interface TrackOptions {
  /** `'eco' | 'balanced' | 'high'` — overrides accuracy/interval/distance. Default `'balanced'`. */
  mode?: TrackMode;
  /** Also request "Allow all the time" (background) so tracking survives the app being closed. Default `true`. */
  background?: boolean;
  /** Detect trips & visits (stay-points). Default `false`. */
  trips?: boolean;
  /** Detect harsh braking / acceleration / speeding / idling. Default `false`. */
  driving?: boolean;
  /** Upload locations to this URL automatically. */
  url?: string;
  /** Extra HTTP headers for the upload (e.g. an auth token, a device id). */
  headers?: Record<string, string>;
  /** Notification shown while tracking in the background. */
  notification?: { title?: string; text?: string };
  /** Minimum distance (m) between reported locations. */
  distanceFilter?: number;
}

/** Result of the guided permission flow. */
export interface PermissionResult {
  granted: boolean;
  foreground: boolean;
  background: boolean;
  locationServicesEnabled: boolean;
  /** Why it isn't fully granted, if `granted` is false. */
  reason?: 'foreground_denied' | 'location_off' | 'background_denied';
}

/** Handle returned by `track()` — call `stop()` to end tracking and clean up. */
export interface Tracker {
  /** Stop tracking and remove the listener. */
  stop: () => Promise<void>;
  /** Alias for `stop()` (so it feels like an event subscription too). */
  remove: () => Promise<void>;
}

/** Internal: maps a friendly mode to the SDK's preset + Accuracy. */
export const MODE_TO_ACCURACY: Record<TrackMode, Accuracy> = {
  eco: 2, // Accuracy.Low
  balanced: 1, // Accuracy.Balanced
  high: 0, // Accuracy.High
};
