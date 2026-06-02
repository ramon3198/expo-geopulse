import type { EventSubscription } from 'expo-modules-core';
import { AppRegistry } from 'react-native';

import NativeModule from './ExpoGeopulseModule';
import type {
  GeoPulseConfig,
  GeoPulseState,
  Location,
  CurrentPositionOptions,
  Geofence,
  PermissionStatus,
  MotionChangeEvent,
  ActivityChangeEvent,
  GeofenceEvent,
  ProviderChangeEvent,
  HeartbeatEvent,
  GeoPulseError,
  HeadlessEvent,
  VisitEvent,
  TripEvent,
  Trip,
  DrivingEvent,
} from './ExpoGeopulse.types';

/** Must match `GeoPulseHeadlessService.TASK_KEY` on the native side. */
const HEADLESS_TASK_KEY = 'ExpoGeopulseHeadless';
import {
  MODE_TO_ACCURACY,
  type EventName,
  type PermissionResult,
  type TrackOptions,
  type Tracker,
} from './convenience.types';

/**
 * High-level, promise-based facade over the native module.
 *
 * ```ts
 * import GeoPulse, { Accuracy } from 'expo-geopulse';
 *
 * await GeoPulse.ready({ desiredAccuracy: Accuracy.High, distanceFilter: 10 });
 * const sub = GeoPulse.onLocation((loc) => console.log(loc.coords));
 * await GeoPulse.start();
 * // ...later
 * sub.remove();
 * await GeoPulse.stop();
 * ```
 */
class GeoPulse {
  // ---- lifecycle / tracking ----

  /** Apply configuration and prepare the SDK. Call once before {@link start}. */
  ready(config: GeoPulseConfig = {}): Promise<GeoPulseState> {
    return NativeModule.ready(config);
  }

  /**
   * Merge configuration while running. Only the fields you pass are changed —
   * everything else (e.g. `url`, `autoSync`, trip/driving detection) is kept.
   *
   * ```ts
   * await GeoPulse.setConfig({ preset: 'eco' }); // changes mode only; sync etc. untouched
   * ```
   *
   * Presets and manual tuning interact predictably: passing `preset` applies
   * that mode's accuracy/interval/distance values. Hand-tuning one of those
   * fields (e.g. `{ distanceFilter: 100 }`) instead drops to manual mode so your
   * value sticks, rather than being overwritten by the active preset.
   */
  setConfig(config: Partial<GeoPulseConfig>): Promise<GeoPulseState> {
    return NativeModule.setConfig(config);
  }

  /** Begin background tracking (starts the foreground service). */
  start(): Promise<GeoPulseState> {
    return NativeModule.start();
  }

  /** Stop background tracking. */
  stop(): Promise<GeoPulseState> {
    return NativeModule.stop();
  }

  /** Current SDK state snapshot. */
  getState(): Promise<GeoPulseState> {
    return NativeModule.getState();
  }

  /** Request a single, fresh location fix. */
  getCurrentPosition(options: CurrentPositionOptions = {}): Promise<Location> {
    return NativeModule.getCurrentPosition(options);
  }

  // ---- permissions ----

  requestPermissions(): Promise<PermissionStatus> {
    return NativeModule.requestPermissions();
  }

  getProviderState(): Promise<PermissionStatus> {
    return NativeModule.getProviderState();
  }

  /**
   * Prompt the user to turn on device location via the native Play-services
   * dialog (no trip to Settings). Resolves `true` if location is enabled.
   */
  requestEnableLocation(): Promise<boolean> {
    return NativeModule.requestEnableLocation();
  }

  /**
   * Request "Allow all the time" (background) location. On Android 10+ this must
   * be called *after* foreground location is granted, and the OS may only allow
   * it via Settings — if the returned status still has `background: false`, send
   * the user to {@link openAppSettings}. Rejects with `NEEDS_FOREGROUND` if
   * foreground location isn't granted yet.
   */
  requestBackgroundPermission(): Promise<PermissionStatus> {
    return NativeModule.requestBackgroundPermission();
  }

  /** Open this app's system settings page (for manual "Allow all the time"). */
  openAppSettings(): Promise<void> {
    return NativeModule.openAppSettings();
  }

  /** Whether the app is exempt from Doze battery optimization. */
  isIgnoringBatteryOptimizations(): Promise<boolean> {
    return NativeModule.isIgnoringBatteryOptimizations();
  }

  /** Opens the system dialog asking the user to exempt the app from Doze. Resolves with the new state. */
  requestIgnoreBatteryOptimizations(): Promise<boolean> {
    return NativeModule.requestIgnoreBatteryOptimizations();
  }

  // ---- geofences ----

  addGeofence(geofence: Geofence): Promise<void> {
    return NativeModule.addGeofence(geofence);
  }

  addGeofences(geofences: Geofence[]): Promise<void> {
    return NativeModule.addGeofences(geofences);
  }

  removeGeofence(identifier: string): Promise<void> {
    return NativeModule.removeGeofence(identifier);
  }

  removeGeofences(): Promise<void> {
    return NativeModule.removeGeofences();
  }

  getGeofences(): Promise<Geofence[]> {
    return NativeModule.getGeofences();
  }

  // ---- trip & visit ----

  /** The trip currently in progress (between two visits), or null. Requires `enableTripDetection`. */
  getActiveTrip(): Promise<Trip | null> {
    return NativeModule.getActiveTrip();
  }

  // ---- persistence + sync ----

  /** Returns buffered locations (chronological). `limit` 0 = the native default cap (1000). */
  getLocations(limit = 0): Promise<Location[]> {
    return NativeModule.getLocations(limit);
  }

  getCount(): Promise<number> {
    return NativeModule.getCount();
  }

  destroyLocations(): Promise<void> {
    return NativeModule.destroyLocations();
  }

  sync(): Promise<Location[]> {
    return NativeModule.sync();
  }

  // ---- odometer ----

  getOdometer(): Promise<number> {
    return NativeModule.getOdometer();
  }

  /** Set the running odometer (meters). Resolves with the last known location, or null if none yet. */
  setOdometer(value: number): Promise<Location | null> {
    return NativeModule.setOdometer(value);
  }

  // ---- events ----

  onLocation(listener: (location: Location) => void): EventSubscription {
    return NativeModule.addListener('onLocation', listener);
  }

  onMotionChange(listener: (event: MotionChangeEvent) => void): EventSubscription {
    return NativeModule.addListener('onMotionChange', listener);
  }

  onActivityChange(listener: (event: ActivityChangeEvent) => void): EventSubscription {
    return NativeModule.addListener('onActivityChange', listener);
  }

  onGeofence(listener: (event: GeofenceEvent) => void): EventSubscription {
    return NativeModule.addListener('onGeofence', listener);
  }

  onProviderChange(listener: (event: ProviderChangeEvent) => void): EventSubscription {
    return NativeModule.addListener('onProviderChange', listener);
  }

  /**
   * Subscribe to periodic heartbeat events.
   * @remarks Reserved — not yet implemented; no `onHeartbeat` events are emitted yet.
   */
  onHeartbeat(listener: (event: HeartbeatEvent) => void): EventSubscription {
    return NativeModule.addListener('onHeartbeat', listener);
  }

  onError(listener: (error: GeoPulseError) => void): EventSubscription {
    return NativeModule.addListener('onError', listener);
  }

  /**
   * Register a task that runs for SDK events **while the app is killed** (the
   * foreground service keeps tracking even with no JS runtime alive). Requires
   * `enableHeadless: true` in your config.
   *
   * **Call this once at your app's entry point — at the top level of `index.js`,
   * outside any React component** — so it's registered when the JS bundle loads.
   * When the OS spawns the headless JS context, only this task runs (your app
   * UI does not mount).
   *
   * ```ts
   * // index.js
   * import GeoPulse from 'expo-geopulse';
   * GeoPulse.registerHeadlessTask(async ({ event, data }) => {
   *   if (event === 'onLocation') await fetch('https://api.me/loc', {
   *     method: 'POST', body: JSON.stringify(data),
   *   });
   * });
   * ```
   */
  registerHeadlessTask(task: (event: HeadlessEvent) => Promise<void>): void {
    AppRegistry.registerHeadlessTask(
      HEADLESS_TASK_KEY,
      () => async (raw: { event: string; payload: string }) => {
        let data: unknown = {};
        try {
          data = JSON.parse(raw?.payload ?? '{}');
        } catch {
          /* keep {} */
        }
        await task({ event: raw?.event, data });
      }
    );
  }

  /** Fires on visit arrival/departure (stay-points). Requires `enableTripDetection`. */
  onVisit(listener: (event: VisitEvent) => void): EventSubscription {
    return NativeModule.addListener('onVisit', listener);
  }

  /** Fires on trip start/end (journey between visits). Requires `enableTripDetection`. */
  onTrip(listener: (event: TripEvent) => void): EventSubscription {
    return NativeModule.addListener('onTrip', listener);
  }

  /** Fires on harsh braking/acceleration, speeding or idling. Requires `enableDrivingEvents`. */
  onDrivingEvent(listener: (event: DrivingEvent) => void): EventSubscription {
    return NativeModule.addListener('onDrivingEvent', listener);
  }

  // ---- high-level convenience (the "just works" API) ----

  /**
   * Start tracking in **one call**: requests permissions, turns on GPS if
   * needed, applies a sensible config, starts the background service, and streams
   * locations to your callback. Returns a handle with `stop()`.
   *
   * ```ts
   * const tracker = await GeoPulse.track((loc) => console.log(loc.coords));
   * // ...later
   * await tracker.stop();
   * ```
   *
   * Throws a coded error (`PERMISSION_DENIED` / `LOCATION_OFF`) if it can't start.
   */
  async track(
    onLocation: (location: Location) => void,
    options: TrackOptions = {}
  ): Promise<Tracker> {
    const { mode = 'balanced', background = true } = options;

    const perm = await this.ensurePermissions({ background });
    if (!perm.granted) {
      const err = new Error(
        perm.reason === 'location_off'
          ? 'Location services are turned off.'
          : 'Location permission was not granted.'
      ) as Error & { code: string };
      err.code = perm.reason === 'location_off' ? 'LOCATION_OFF' : 'PERMISSION_DENIED';
      throw err;
    }

    // A hand-tuned distanceFilter implies manual mode: send it WITHOUT a preset,
    // otherwise the native `ready()` resolves the preset and overwrites it.
    const manualDistance = options.distanceFilter != null;
    await this.ready({
      desiredAccuracy: MODE_TO_ACCURACY[mode],
      ...(manualDistance
        ? { distanceFilter: options.distanceFilter }
        : { preset: mode === 'balanced' ? 'standard' : mode }),
      enableTripDetection: options.trips,
      enableDrivingEvents: options.driving,
      url: options.url,
      autoSync: options.url != null,
      headers: options.headers,
      notification: options.notification,
    });

    const sub = this.onLocation(onLocation);
    await this.start();

    const stop = async () => {
      sub.remove();
      await this.stop();
    };
    return { stop, remove: stop };
  }

  /**
   * Run the full permission flow (foreground → GPS → background) and report the
   * outcome — no need to orchestrate `requestPermissions` / `requestEnableLocation`
   * / `requestBackgroundPermission` by hand.
   *
   * **`granted` means tracking can start now** — i.e. foreground location is
   * granted *and* device location services are on. Background ("Allow all the
   * time") is best-effort and reported **separately**: even when `granted` is
   * `true`, check `result.background` — if it's `false` (with
   * `reason === 'background_denied'`), foreground tracking works but you should
   * send the user to {@link openAppSettings} for true background tracking.
   */
  async ensurePermissions(options: { background?: boolean } = {}): Promise<PermissionResult> {
    const { background = true } = options;

    let status = await this.requestPermissions();
    if (!status.fine && !status.coarse) {
      return {
        granted: false,
        foreground: false,
        background: false,
        locationServicesEnabled: status.locationServicesEnabled,
        reason: 'foreground_denied',
      };
    }

    if (!status.locationServicesEnabled) {
      const on = await this.requestEnableLocation();
      status = await this.getProviderState();
      if (!on && !status.locationServicesEnabled) {
        return {
          granted: false,
          foreground: true,
          background: status.background,
          locationServicesEnabled: false,
          reason: 'location_off',
        };
      }
    }

    if (background && !status.background) {
      status = await this.requestBackgroundPermission();
      // Note: on Android 11+ the OS may require Settings; caller can check `background`.
    }

    return {
      granted: true,
      foreground: status.fine || status.coarse,
      background: status.background,
      locationServicesEnabled: status.locationServicesEnabled,
      reason: background && !status.background ? 'background_denied' : undefined,
    };
  }

  /** Short alias for {@link getCurrentPosition} — a single fresh fix. */
  currentPosition(): Promise<Location> {
    return this.getCurrentPosition();
  }

  /**
   * One subscriber for every event, by name:
   * `on('location' | 'motion' | 'activity' | 'geofence' | 'provider' | 'heartbeat'
   *     | 'error' | 'visit' | 'trip' | 'driving', cb)`.
   *
   * ```ts
   * const sub = GeoPulse.on('trip', (e) => console.log(e.action));
   * sub.remove();
   * ```
   */
  on(event: 'location', cb: (e: Location) => void): EventSubscription;
  on(event: 'motion', cb: (e: MotionChangeEvent) => void): EventSubscription;
  on(event: 'activity', cb: (e: ActivityChangeEvent) => void): EventSubscription;
  on(event: 'geofence', cb: (e: GeofenceEvent) => void): EventSubscription;
  on(event: 'provider', cb: (e: ProviderChangeEvent) => void): EventSubscription;
  on(event: 'heartbeat', cb: (e: HeartbeatEvent) => void): EventSubscription;
  on(event: 'error', cb: (e: GeoPulseError) => void): EventSubscription;
  on(event: 'visit', cb: (e: VisitEvent) => void): EventSubscription;
  on(event: 'trip', cb: (e: TripEvent) => void): EventSubscription;
  on(event: 'driving', cb: (e: DrivingEvent) => void): EventSubscription;
  on(event: EventName, cb: (e: never) => void): EventSubscription {
    const map: Record<EventName, string> = {
      location: 'onLocation',
      motion: 'onMotionChange',
      activity: 'onActivityChange',
      geofence: 'onGeofence',
      provider: 'onProviderChange',
      heartbeat: 'onHeartbeat',
      error: 'onError',
      visit: 'onVisit',
      trip: 'onTrip',
      driving: 'onDrivingEvent',
    };
    return NativeModule.addListener(map[event] as never, cb as never);
  }

  // ---- debug / testing ----

  /** Emit a synthetic `onLocation` event. Useful to validate wiring end-to-end. */
  emitTestLocation(): void {
    NativeModule.emitTestLocation();
  }

  /**
   * Inject a fix through the full pipeline (Kalman, trips/visits, geofences,
   * persistence) as if it came from GPS. A testing aid for exercising geofences
   * and trip/visit logic without walking a route. Pass increasing `timestamp`
   * values (epoch ms) to simulate motion over time.
   */
  simulateLocation(location: {
    latitude: number;
    longitude: number;
    accuracy?: number;
    speed?: number;
    timestamp?: number;
  }): Promise<void> {
    return NativeModule.simulateLocation(location);
  }
}

export default new GeoPulse();
